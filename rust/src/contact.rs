//! # Contacts
//!
//! The contact domain: addressbooks and vCards as they cross the bridge,
//! and the [`Contacts`] router dispatching each operation on its backend.
//!
//! [`carddav`], [`msgraph`], [`jmap`] and [`gpeople`] add the contact verbs
//! to their backend. The rest works on vCard text alone: the field model
//! ([`fields`], [`form`], [`props`]), merges and duplicates ([`merge`],
//! [`dedup`], [`group`]), and the sync policy ([`push`], [`sync`]).

pub mod carddav;
pub mod dedup;
pub mod fields;
pub mod form;
pub mod gpeople;
pub mod group;
pub mod jmap;
pub mod merge;
mod msgraph;
pub mod props;
pub mod push;
pub mod sync;

use io_webdav::rfc6578::sync_collection::WebdavSyncDelta;
use serde::Serialize;

use crate::{
    account::{self, Account, Backend},
    client::{
        Client, Host,
        jmap::Jmap,
        msgraph::Msgraph,
        parse_url,
        webdav::{Webdav, href_resource},
    },
    contact::{
        gpeople::Gpeople,
        push::{PushChange, PushOutcome},
    },
    error::BridgeError,
};

/// One addressbook surfaced to the Java client.
#[derive(Serialize)]
pub struct Addressbook {
    /// Last non-empty path segment of the collection URL.
    pub id: String,
    /// Human-readable name (display name, falling back to the id).
    pub name: String,
    /// Absolute collection URL, the target of every card operation.
    pub url: String,
    /// Free-form description, when the server exposes one.
    pub description: Option<String>,
    /// Display colour (`#RRGGBB`), when the server exposes one.
    pub color: Option<String>,
    /// What the source says the book is for, in pimdir's role vocabulary.
    ///
    /// [`crate::offline::DEFAULT_ROLE`] for the one it writes to when none
    /// is named, empty where it says nothing.
    pub role: String,
    /// Whether the user may write into it, true where the source says
    /// nothing.
    pub writable: bool,
}

/// One vCard surfaced to the Java client.
#[derive(Serialize)]
pub struct Card {
    /// Display identifier (resource name with any `.vcf` stripped).
    pub id: String,
    /// Resource name exactly as the server returned it, the addressing
    /// key of updates and deletes (servers need not suffix `.vcf`).
    pub uri: String,
    /// Entity tag guarding concurrent updates, when the server sent one.
    pub etag: Option<String>,
    /// Raw vCard text.
    pub vcard: String,
    /// Ids of the addressbooks the card is a member of.
    ///
    /// Set where cards are account-level with m:n memberships (JMAP book
    /// ids, Google group ids), empty where a card lives in the one
    /// collection it was listed from (CardDAV, Graph).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub books: Vec<String>,
}

/// Incremental changes of one collection since a sync cursor.
#[derive(Serialize)]
pub struct CardDelta {
    /// Cards created or updated since the cursor, spine-only or full per
    /// backend.
    pub changed: Vec<Card>,
    /// Resource names removed since the cursor.
    pub vanished: Vec<String>,
    /// The next cursor, when the backend issued one.
    pub token: Option<String>,
    /// True when the round listed the complete member set (an initial
    /// round, or an expired cursor re-run as one).
    pub complete: bool,
}

/// One account's addressbooks and cards, as the bridge routes them.
pub struct Contacts<'c, H> {
    /// The client the requests go through.
    pub client: &'c mut Client<H>,
    /// The account whose backend serves every operation.
    pub account: &'c Account<'c>,
}

/// The card [`Contacts::create_card`] files.
pub struct CardCreate<'a> {
    /// On CardDAV the whole resource name asked for, which a server may
    /// replace with its own; the other backends name the card themselves.
    pub id: &'a str,
    /// The vCard text.
    pub vcard: &'a str,
}

/// The card [`Contacts::update_card`] writes.
pub struct CardUpdate<'a> {
    /// The card's display identifier, the addressing key of a row with no
    /// resource name.
    pub id: &'a str,
    /// The resource name as the server returned it, empty when unknown.
    pub uri: &'a str,
    /// The edited vCard text.
    pub vcard: &'a str,
    /// The state last synced with the server, [`None`] when unknown.
    pub base_vcard: Option<&'a str>,
    /// The ETag guarding the write, [`None`] when unknown.
    pub etag: Option<&'a str>,
}

/// The card [`Contacts::delete_card`] removes.
pub struct CardDelete<'a> {
    /// The card's display identifier, as [`CardUpdate::id`].
    pub id: &'a str,
    /// The resource name, as [`CardUpdate::uri`].
    pub uri: &'a str,
    /// The ETag guarding the deletion, [`None`] when unknown.
    pub etag: Option<&'a str>,
}

/// The memberships [`Contacts::update_card_books`] changes.
pub struct CardBooksUpdate<'a> {
    /// The card's id.
    pub id: &'a str,
    /// The ids of the addressbooks the card joins.
    pub add: &'a [String],
    /// The ids of the addressbooks the card leaves.
    pub remove: &'a [String],
}

/// The protocol-agnostic card and addressbook API, each method routing
/// to the backend behind the account's base URL.
impl<H: Host> Contacts<'_, H> {
    /// Lists the account's addressbooks, with the absolute collection URL
    /// every card operation targets.
    ///
    /// Doubles as the connection check during onboarding.
    pub fn list_addressbooks(&mut self) -> Result<Vec<Addressbook>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Carddav => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .list_addressbooks(&parse_url(account.base_url)?),
            Backend::Graph => {
                let mut books = Msgraph {
                    client: self.client,
                    token: account.auth.secret(),
                }
                .list_addressbooks()?;
                for book in &mut books {
                    let segment = match book.id.is_empty() {
                        true => account::MSGRAPH_DEFAULT_FOLDER,
                        false => &book.id,
                    };
                    book.url = format!("{}/{segment}", account.base_url);
                }
                Ok(books)
            }
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                let mut books = Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .list_addressbooks()?;
                for book in &mut books {
                    // NOTE: the listing already put the account-scoped
                    // path there; only the base is missing.
                    book.url = format!("{}/{}", account.base_url, book.url);
                }
                Ok(books)
            }
            Backend::Google => {
                let mut books = Gpeople {
                    client: self.client,
                    token: account.auth.secret(),
                }
                .list_addressbooks()?;
                for book in &mut books {
                    let segment = match book.id.is_empty() {
                        true => account::GOOGLE_DEFAULT_BOOK,
                        false => &book.id,
                    };
                    book.url = format!("{}/{segment}", account.base_url);
                }
                Ok(books)
            }
        }
    }

    /// Lists every card of an account-level backend (JMAP, Google) in
    /// one pass, each carrying its addressbook memberships as book ids.
    pub fn list_account_cards(&mut self) -> Result<Vec<Card>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .list_cards()
            }
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .list_cards(),
            backend => Err(format!(
                "Cards of a {} account are listed per collection",
                backend.name(),
            )
            .into()),
        }
    }

    /// Lists the cards of the addressbook collection at
    /// `addressbook_url` (CardDAV, Graph); the account-level backends
    /// list once per account through [`Self::list_account_cards`].
    pub fn list_cards(&mut self, addressbook_url: &str) -> Result<Vec<Card>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Carddav => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .list_cards(&parse_url(addressbook_url)?),
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .list_cards(account::graph_folder(account.base_url, addressbook_url)),
            backend => Err(format!(
                "Cards of a {} account are listed account-wide",
                backend.name(),
            )
            .into()),
        }
    }

    /// Lists the collection's changes since the cursor (RFC 6578 on DAV).
    ///
    /// No cursor runs the initial round: the complete member set and the
    /// next cursor. A rejected cursor, or a DAV server without
    /// sync-collection, re-runs one; the delta says whether it is complete.
    pub fn sync_cards(
        &mut self,
        addressbook_url: &str,
        cursor: Option<&str>,
    ) -> Result<CardDelta, BridgeError> {
        // NOTE: a JMAP initial round is paged, and only it knows whether
        // its listing reached the end; its deltas are never complete.
        let paged = self.account.backend == Backend::Jmap;
        let delta = self.sync_cards_round(addressbook_url, cursor)?;

        if let Some(mut delta) = delta {
            if !paged {
                delta.complete = cursor.is_none();
            }
            return Ok(delta);
        }

        // NOTE: the cursor was rejected, so re-run an initial round; a
        // still-rejected round must error, never read as an empty
        // collection (that would look remote-deleted).
        match self.sync_cards_round(addressbook_url, None)? {
            Some(mut delta) => {
                if !paged {
                    delta.complete = true;
                }
                Ok(delta)
            }
            None => Err(format!("Initial sync round rejected for {addressbook_url}").into()),
        }
    }

    /// One sync round against the backend behind the base URL;
    /// [`None`] when the server rejected the cursor.
    fn sync_cards_round(
        &mut self,
        addressbook_url: &str,
        cursor: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Carddav => {
                let url = parse_url(addressbook_url)?;
                let delta = Webdav {
                    client: self.client,
                    auth: account.auth,
                }
                .sync_cards(&url, cursor)?;
                Ok(delta.map(into_card_delta))
            }
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .delta_cards(
                account::graph_folder(account.base_url, addressbook_url),
                cursor,
            ),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .changes_cards(cursor)
            }
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .sync_cards(cursor),
        }
    }

    /// Creates the card in the addressbook collection, returning it
    /// with its ETag, under the name the server filed it by.
    pub fn create_card(
        &mut self,
        addressbook_url: &str,
        card: &CardCreate,
    ) -> Result<Card, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Carddav => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .create_card(&parse_url(addressbook_url)?, card.id, card.vcard),
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .create_card(
                account::graph_folder(account.base_url, addressbook_url),
                card.vcard,
            ),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                let book_id = account::jmap_collection_id(addressbook_url);
                Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .create_card(book_id, card.vcard)
            }
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .create_card(card.vcard),
        }
    }

    /// Reads the card at the given resource name (as the server
    /// returned it) from the addressbook collection.
    pub fn read_card(&mut self, addressbook_url: &str, uri: &str) -> Result<Card, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Carddav => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .read_card(&parse_url(addressbook_url)?, uri),
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .read_card(uri),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .read_card(uri)
            }
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .read_card(uri),
        }
    }

    /// Updates the card in the addressbook collection, with its new ETag.
    ///
    /// The base vCard trims patches to the changed fields (CardDAV PUTs
    /// the full vCard). The ETag guards CardDAV and Google; Graph and JMAP
    /// carry no guard (last-write-wins).
    pub fn update_card(
        &mut self,
        addressbook_url: &str,
        card: &CardUpdate,
    ) -> Result<Card, BridgeError> {
        let account = self.account;
        let key = account::addressing_key(account.backend, card.id, card.uri);

        match account.backend {
            Backend::Carddav => {
                let url = parse_url(addressbook_url)?;
                let etag = Webdav {
                    client: self.client,
                    auth: account.auth,
                }
                .update_card(&url, &key, card.vcard, card.etag)?;

                Ok(Card {
                    id: card.id.to_string(),
                    uri: key,
                    etag,
                    vcard: card.vcard.to_string(),
                    books: Vec::new(),
                })
            }
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .update_card(&key, card.vcard, card.base_vcard),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .update_card(&key, card.vcard, card.base_vcard)
            }
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .update_card(&key, card.vcard, card.base_vcard, card.etag),
        }
    }

    /// Deletes the card from the addressbook collection; the ETag
    /// guards the CardDAV deletion only.
    pub fn delete_card(
        &mut self,
        addressbook_url: &str,
        card: &CardDelete,
    ) -> Result<(), BridgeError> {
        let account = self.account;
        let key = account::addressing_key(account.backend, card.id, card.uri);

        match account.backend {
            Backend::Carddav => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .delete_card(&parse_url(addressbook_url)?, &key, card.etag),
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .delete_card(&key),
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .delete_card(&key)
            }
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .delete_card(&key),
        }
    }

    /// Creates a batch of cards, returned in input order.
    ///
    /// Google-only: People is the one backend with a batch create verb,
    /// and its per-minute write quota throttles per-card calls.
    pub fn create_cards(&mut self, vcards: &[String]) -> Result<Vec<Card>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .create_cards(vcards),
            _ => Err("Batch card creation is Google-only".into()),
        }
    }

    /// Deletes a batch of cards from the addressbook collection by id.
    /// Google-only, like [`Self::create_cards`].
    pub fn delete_cards(&mut self, ids: &[String]) -> Result<(), BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .delete_cards(ids),
            _ => Err("Batch card deletion is Google-only".into()),
        }
    }

    /// Pushes one round of changes as JMAP ContactCard/set or Graph $batch.
    ///
    /// Both answer one outcome per change, so a rejected card does not
    /// fail its round. Other backends push through their own verbs.
    pub fn push_cards(
        &mut self,
        addressbook_url: &str,
        changes: &[PushChange],
    ) -> Result<Vec<PushOutcome>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                let book_id = account::jmap_collection_id(addressbook_url);
                Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .push_cards(book_id, changes)
            }
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .push_cards(
                account::graph_folder(account.base_url, addressbook_url),
                changes,
            ),
            _ => Err("Batch card push is JMAP and Graph only".into()),
        }
    }

    /// Adds and removes the card's memberships by book id, on an
    /// account-level backend (JMAP addressBookIds, Google group members).
    pub fn update_card_books(&mut self, books: &CardBooksUpdate) -> Result<(), BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .update_card_books(books.id, books.add, books.remove)
            }
            Backend::Google => Gpeople {
                client: self.client,
                token: account.auth.secret(),
            }
            .update_card_books(books.id, books.add, books.remove),
            backend => Err(format!(
                "Memberships of a {} card are fixed to its collection",
                backend.name(),
            )
            .into()),
        }
    }
}

/// A sync-collection delta as a [`CardDelta`], hrefs as resource names.
///
/// The collection's own href, when a server lists it, yields an empty
/// name and is skipped.
fn into_card_delta(delta: WebdavSyncDelta) -> CardDelta {
    let changed = delta
        .changed
        .into_iter()
        .filter_map(|change| {
            let uri = href_resource(&change.href)?;
            Some(Card {
                id: uri.trim_end_matches(".vcf").to_string(),
                uri,
                etag: change.etag,
                vcard: String::new(),
                books: Vec::new(),
            })
        })
        .collect();
    let vanished = delta
        .vanished
        .iter()
        .filter_map(|href| href_resource(href))
        .collect();

    CardDelta {
        changed,
        vanished,
        token: delta.sync_token,
        complete: false,
    }
}
