//! JMAP operations: the RFC 9610 AddressBook and ContactCard verbs, the
//! ContactCard/set push and the ContactCard/changes sync round, the RFC
//! 8621 Mailbox and Email reads behind the account-wide mail walk, and
//! the draft-ietf-jmap-calendars Calendar and CalendarEvent reads.
//!
//! One JMAP session serves all three domains, which is why they share a
//! file: the session fetch, the auth header and the resume loops are the
//! same code whichever capability a verb declares.

use core::error::Error as StdError;

use std::collections::{BTreeMap, BTreeSet};

use ical::ical::Ical;
use io_http::{rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic};
use io_jmap::{
    calendars::{
        JMAP_CALENDARS_CAPABILITY,
        calendar::{JmapCalendar, get::*},
        calendar_event::{JmapCalendarEvent, query::*},
    },
    coroutine::{JmapCoroutine, JmapCoroutineState, JmapYield},
    rfc8620::{
        blob_download::JmapBlobDownload, changes::*, coroutine::JmapRedirectYield,
        error::JmapMethodError, filter::JmapFilter, session::JmapSession, session_get::*,
    },
    rfc8621::{
        JMAP_MAIL_CAPABILITY,
        email::{
            JMAP_KEYWORD_ANSWERED, JMAP_KEYWORD_FLAGGED, JMAP_KEYWORD_SEEN, JmapEmail,
            JmapEmailAddress, JmapEmailProperty,
            changes::{JmapEmailChanges, JmapEmailChangesError, JmapEmailChangesOptions},
            get::*,
            query::*,
            set::*,
        },
        mailbox::{JmapMailbox, JmapMailboxRole, get::*},
    },
    rfc9610::{
        JMAP_CONTACTS_CAPABILITY,
        address_book::{JmapAddressBook, get::*},
        contact_card::{JmapContactCard, changes::*, get::*, query::*, set::*},
    },
};
use io_pimdir::summary::{
    PimdirAddress,
    mail::{PimdirMailSummary, decode},
};
use secrecy::SecretString;
use serde_json::Value;
use url::Url;

use crate::{
    client::{
        Client,
        convert::{coroutine_error, rejected, required},
        listing::{
            Floor, JMAP_PAGE, Listing, MailPage, MailRequest, Named, RECEIVED_MARGIN_DAYS, Scope,
            flags, utc,
        },
    },
    jmap,
    types::{
        Addressbook, BridgeError, Calendar, Card, CardDelta, Credentials, Event, Mailbox,
        PushChange, PushOutcome,
    },
};

/// The `Email` properties a listing names a member by: Annex A's summary
/// and addresses, the markers, and the mailboxes a delta checks the
/// member is still in. The date is `sentAt`, the `Date` header, never
/// `receivedAt`; the attachment mark the server's own `hasAttachment`.
const SUMMARY_PROPERTIES: [JmapEmailProperty; 12] = [
    JmapEmailProperty::Id,
    JmapEmailProperty::MailboxIds,
    JmapEmailProperty::Keywords,
    JmapEmailProperty::Size,
    JmapEmailProperty::MessageId,
    JmapEmailProperty::InReplyTo,
    JmapEmailProperty::From,
    JmapEmailProperty::To,
    JmapEmailProperty::Cc,
    JmapEmailProperty::Subject,
    JmapEmailProperty::SentAt,
    JmapEmailProperty::HasAttachment,
];

/// How many changes one JMAP ContactCard/set call carries: well under
/// every server's advertised maxObjectsInSet, so no session lookup.
const JMAP_SET_CHUNK: usize = 50;

/// JMAP operations: the RFC 9610 AddressBook and ContactCard verbs, the
/// ContactCard/set push and the ContactCard/changes sync round.
impl<'a, 'local> Client<'a, 'local> {
    /// Lists the account's JMAP AddressBooks (RFC 9610 §2.1).
    /// Collection URLs are left empty: the caller composes them, since
    /// only it knows the account base they hang off. Doubles as the
    /// connection check during onboarding.
    pub fn list_jmap_addressbooks(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let coroutine =
            JmapAddressBookGet::new(&session, &auth, JmapAddressBookGetOptions::default())
                .map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        Ok(out
            .address_books
            .into_iter()
            .map(|book| jmap_addressbook(&session, book))
            .collect())
    }

    /// Lists the account's ContactCards across every AddressBook, each
    /// converted to a vCard document; the ContactCard id is the
    /// addressing key, a JSON hash the ETag, and the addressBookIds
    /// the card's books (natively m:n).
    pub fn list_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Card>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapContactCardQueryOptions::default();
        let coroutine =
            JmapContactCardQuery::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        let cards: Result<Vec<Card>, String> = out.cards.into_iter().map(jmap::to_card).collect();
        Ok(cards?)
    }

    /// Creates the vCard as a ContactCard in the AddressBook
    /// `book_id`. The server names the card, so the returned card
    /// carries the server-assigned id.
    pub fn create_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        book_id: &str,
        vcard: &str,
    ) -> Result<Card, BridgeError> {
        let card = jmap::to_jscontact(vcard)?;
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let create = BTreeMap::from([(
            "c0".to_string(),
            JmapContactCard {
                id: None,
                address_book_ids: BTreeMap::from([(book_id.to_string(), true)]),
                card,
            },
        )]);
        let args = JmapContactCardSetArgs {
            create: Some(create),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        if let Some(err) = out.not_created.into_values().next() {
            return Err(format!("JMAP ContactCard create rejected: {err:?}").into());
        }
        let id = out
            .created
            .into_values()
            .next()
            .and_then(|created| created.id)
            .ok_or_else(|| "JMAP create response is missing the card id".to_string())?;

        Ok(Card {
            id: id.clone(),
            uri: id,
            etag: None,
            vcard: vcard.to_string(),
            books: vec![book_id.to_string()],
        })
    }

    /// Reads the ContactCard `id`, converted to a vCard document.
    pub fn read_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<Card, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapContactCardGetOptions {
            ids: Some(vec![id.to_string()]),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        let card = out
            .cards
            .into_iter()
            .next()
            .ok_or_else(|| format!("JMAP ContactCard `{id}` not found"))?;

        Ok(jmap::to_card(card)?)
    }

    /// Lists the ContactCard changes since `since_state` (RFC 8620
    /// `/changes`), the changed cards fetched in full so their
    /// JSON-hash ETag doubles as the content revision; without a
    /// state, the initial round gets every card plus the state to
    /// delta from next time. Returns [`None`] when the server can no
    /// longer compute changes from the state, so the caller falls
    /// back to an initial round.
    pub fn changes_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        since_state: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let Some(since) = since_state else {
            let opts = JmapContactCardGetOptions::default();
            let coroutine =
                JmapContactCardGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
            let out = self.run_jmap(&api_url, coroutine)?;

            let changed: Vec<Card> = out
                .cards
                .into_iter()
                .map(jmap::to_card)
                .collect::<Result<_, _>>()?;
            return Ok(Some(CardDelta {
                changed,
                vanished: Vec::new(),
                token: Some(out.new_state),
                complete: false,
            }));
        };

        let mut cursor = since.to_string();
        let mut changed_ids = BTreeSet::new();
        let mut vanished = BTreeSet::new();

        loop {
            let opts = JmapContactCardChangesOptions::default();
            let coroutine = JmapContactCardChanges::new(&session, &auth, cursor.clone(), opts)
                .map_err(|err| err.to_string())?;
            let out = match self.run_jmap_changes(&api_url, coroutine)? {
                Some(out) => out,
                None => return Ok(None),
            };

            changed_ids.extend(out.created);
            changed_ids.extend(out.updated);
            vanished.extend(out.destroyed);
            cursor = out.new_state;

            if !out.has_more_changes {
                break;
            }
        }

        // NOTE: a card created and destroyed within the window is in
        // both lists; the destroy wins.
        changed_ids.retain(|id| !vanished.contains(id));

        let mut changed = Vec::new();
        if !changed_ids.is_empty() {
            let opts = JmapContactCardGetOptions {
                ids: Some(changed_ids.into_iter().collect()),
                ..Default::default()
            };
            let coroutine =
                JmapContactCardGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
            let out = self.run_jmap(&api_url, coroutine)?;
            changed = out
                .cards
                .into_iter()
                .map(jmap::to_card)
                .collect::<Result<_, _>>()?;
        }

        Ok(Some(CardDelta {
            changed,
            vanished: vanished.into_iter().collect(),
            token: Some(cursor),
            complete: false,
        }))
    }

    /// Updates the ContactCard `id` from the vCard. With a base vCard
    /// (the state last synced with the server) the patch shrinks to
    /// the properties the edit changed, plus nulls for the removed
    /// ones; without one it replaces every property the vCard carries.
    /// There is no If-Match guard, updates are last-write-wins.
    pub fn update_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        vcard: &str,
        base_vcard: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let patch = jmap::to_patch(vcard, base_vcard)?;
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let update = BTreeMap::from([(id.to_string(), JmapContactCardPatch(patch))]);
        let args = JmapContactCardSetArgs {
            update: Some(update),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        if let Some(err) = out.not_updated.into_values().next() {
            return Err(format!("JMAP ContactCard update rejected: {err:?}").into());
        }

        Ok(Card {
            id: id.to_string(),
            uri: id.to_string(),
            etag: None,
            vcard: vcard.to_string(),
            books: Vec::new(),
        })
    }

    /// Destroys the ContactCard `id`.
    pub fn delete_jmap_card(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<(), BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let args = JmapContactCardSetArgs {
            destroy: Some(vec![id.to_string()]),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        if let Some(err) = out.not_destroyed.into_values().next() {
            return Err(format!("JMAP ContactCard destroy rejected: {err:?}").into());
        }

        Ok(())
    }

    /// Adds and removes AddressBook memberships of the ContactCard
    /// `id` in one patch (RFC 9610 addressBookIds is m:n: an added
    /// book id patches to true, a removed one to null).
    pub fn update_jmap_card_books(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let mut patch = BTreeMap::new();
        for book in add {
            patch.insert(format!("addressBookIds/{book}"), Value::Bool(true));
        }
        for book in remove {
            patch.insert(format!("addressBookIds/{book}"), Value::Null);
        }

        let update = BTreeMap::from([(id.to_string(), JmapContactCardPatch(patch))]);
        let args = JmapContactCardSetArgs {
            update: Some(update),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        if let Some(err) = out.not_updated.into_values().next() {
            return Err(format!("JMAP membership update rejected: {err:?}").into());
        }

        Ok(())
    }

    /// Pushes one round of changes as ContactCard/set calls (chunks of
    /// [`JMAP_SET_CHUNK`]): creates, content updates, membership
    /// patches and destroys ride the same request, and the per-object
    /// response maps back to one outcome per change (a destroy the
    /// server no longer finds reads as already converged, and a
    /// rejected object no longer fails its round).
    pub(crate) fn push_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        book_id: &str,
        changes: &[PushChange],
    ) -> Result<Vec<PushOutcome>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let mut outcomes = Vec::with_capacity(changes.len());
        for chunk in changes.chunks(JMAP_SET_CHUNK) {
            let mut create = BTreeMap::new();
            let mut create_refs = BTreeMap::new();
            let mut update = BTreeMap::new();
            let mut update_refs = BTreeMap::new();
            let mut destroy = Vec::new();
            let mut destroy_refs = BTreeMap::new();

            for (index, change) in chunk.iter().enumerate() {
                match change.op.as_str() {
                    "create" => {
                        let vcard = required(&change.vcard, "create", "vcard")?;
                        let key = format!("c{index}");
                        create.insert(
                            key.clone(),
                            JmapContactCard {
                                id: None,
                                address_book_ids: BTreeMap::from([(book_id.to_string(), true)]),
                                card: jmap::to_jscontact(vcard)?,
                            },
                        );
                        create_refs.insert(key, change.reference.clone());
                    }
                    "update" => {
                        let id = required(&change.id, "update", "id")?;
                        let vcard = required(&change.vcard, "update", "vcard")?;
                        let patch = jmap::to_patch(vcard, change.base_vcard.as_deref())?;
                        update.insert(id.to_string(), JmapContactCardPatch(patch));
                        update_refs.insert(id.to_string(), change.reference.clone());
                    }
                    "books" => {
                        let id = required(&change.id, "books", "id")?;
                        let mut patch = BTreeMap::new();
                        for book in &change.add {
                            patch.insert(format!("addressBookIds/{book}"), Value::Bool(true));
                        }
                        for book in &change.remove {
                            patch.insert(format!("addressBookIds/{book}"), Value::Null);
                        }
                        update.insert(id.to_string(), JmapContactCardPatch(patch));
                        update_refs.insert(id.to_string(), change.reference.clone());
                    }
                    "destroy" => {
                        let id = required(&change.id, "destroy", "id")?;
                        destroy.push(id.to_string());
                        destroy_refs.insert(id.to_string(), change.reference.clone());
                    }
                    op => return Err(format!("Unknown push op `{op}`").into()),
                }
            }

            let args = JmapContactCardSetArgs {
                create: (!create.is_empty()).then_some(create),
                update: (!update.is_empty()).then_some(update),
                destroy: (!destroy.is_empty()).then_some(destroy),
            };
            let coroutine =
                JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
            let out = self.run_jmap(&api_url, coroutine)?;

            for (key, reference) in create_refs {
                if let Some(err) = out.not_created.get(&key) {
                    outcomes.push(rejected(reference, format!("{err:?}")));
                } else if let Some(id) = out.created.get(&key).and_then(|card| card.id.clone()) {
                    outcomes.push(PushOutcome {
                        reference,
                        accepted: true,
                        id: Some(id),
                        ..Default::default()
                    });
                } else {
                    return Err("JMAP create response is missing the card id".into());
                }
            }
            for (id, reference) in update_refs {
                match out.not_updated.get(&id) {
                    Some(err) => outcomes.push(rejected(reference, format!("{err:?}"))),
                    None => outcomes.push(PushOutcome {
                        reference,
                        accepted: true,
                        ..Default::default()
                    }),
                }
            }
            for (id, reference) in destroy_refs {
                match out.not_destroyed.get(&id) {
                    // NOTE: NotFound means already gone upstream, so the
                    // destroy converged.
                    Some(JmapContactCardSetItemError::NotFound { .. }) | None => {
                        outcomes.push(PushOutcome {
                            reference,
                            accepted: true,
                            ..Default::default()
                        })
                    }
                    Some(err) => outcomes.push(rejected(reference, format!("{err:?}"))),
                }
            }
        }

        Ok(outcomes)
    }
}

/// JMAP mail operations: the RFC 8621 Mailbox roster and the per-mailbox
/// envelope spine, the JMAP half of what `syncMail` does over IMAP.
impl<'a, 'local> Client<'a, 'local> {
    /// Walks a whole account: one session fetch, the mailbox roster,
    /// then the newest `limit` messages of each mailbox.
    ///
    /// Mirrors the IMAP walk, and for the same reason: the session is
    /// what authenticates, so fetching it once and reusing it for every
    /// mailbox is the difference between one round trip and one per
    /// collection. An unreadable mailbox is skipped rather than failing
    /// the account, exactly as a mailbox the IMAP session cannot
    /// EXAMINE is.
    pub fn list_jmap_mailbox_roster(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<(String, Mailbox)>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        Ok(self
            .list_jmap_mailboxes(&session, &auth, &api_url)?
            .into_iter()
            .map(|(id, path, role)| (id, Mailbox { name: path, role }))
            .collect())
    }

    /// One page of a mailbox's listing (pimdir SYNC §4, §5).
    ///
    /// A round is an `Email/query` in the mailbox sorted by `receivedAt`
    /// newest first, narrowed to what was received from two days below
    /// the scope's floor, 500 a page capped by the server's
    /// `maxObjectsInGet`, its position the resume cursor; the `Email`
    /// state read before the first page is the checkpoint. A delta is
    /// `Email/changes` from it: what was created or updated is read again
    /// and is a member while it is still in this mailbox, gone from it
    /// otherwise, and what was destroyed is gone. A state the server can
    /// no longer compute changes from is refused for the engine to open a
    /// round.
    pub fn list_jmap_page(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        mailbox_id: &str,
        request: &MailRequest,
    ) -> Result<MailPage, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();
        let scope = &request.scope;

        let (position, band) = match &request.listing {
            Listing::Delta { checkpoint } => {
                return self
                    .jmap_mail_delta(&session, &auth, &api_url, mailbox_id, checkpoint, scope);
            }
            Listing::Round { cursor, band } => match cursor.as_deref() {
                None => (0, *band),
                Some(cursor) => match cursor.parse::<u64>() {
                    Ok(position) => (position, *band),
                    Err(_) => return Ok(MailPage::rejected()),
                },
            },
        };

        // NOTE: before the query, so what moves while the round lists is
        // the next delta's.
        let checkpoint = match (position, band) {
            (0, false) => Some(self.jmap_email_state(&session, &auth, &api_url)?),
            _ => None,
        };

        let limit = JMAP_PAGE.min(max_objects_in_get(&session));
        let filter = JmapEmailFilter {
            in_mailbox: Some(mailbox_id.to_string()),
            after: scope
                .received_since(RECEIVED_MARGIN_DAYS)
                .map(|since| since.strftime("%Y-%m-%dT%H:%M:%SZ").to_string()),
            before: scope
                .received_until(RECEIVED_MARGIN_DAYS)
                .map(|until| until.strftime("%Y-%m-%dT%H:%M:%SZ").to_string()),
            ..Default::default()
        };
        let opts = JmapEmailQueryOptions {
            filter: Some(JmapFilter::Condition(filter)),
            sort: Some(vec![JmapEmailComparator::received_at_desc()]),
            position: Some(position),
            limit: Some(limit),
            properties: Some(SUMMARY_PROPERTIES.to_vec()),
        };
        let coroutine =
            JmapEmailQuery::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        let listed = out.emails.len() as u64;
        let reached = out.position + listed;
        let more = listed > 0 && out.total.is_none_or(|total| reached < total);
        let items = out
            .emails
            .into_iter()
            .filter_map(|email| named(email, scope))
            .collect();

        Ok(MailPage::round(
            items,
            more.then(|| reached.to_string()),
            checkpoint,
        ))
    }

    /// Takes a mailbox's newest emails below the floor's ceiling until its
    /// chunk is full: `Email/query` sorted by `receivedAt`, narrowed two
    /// days above the ceiling, each email's `sentAt` (the `Date` header)
    /// taken a page at a time, as many a page as the chunk asks for.
    pub fn jmap_floor(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        mailbox_id: &str,
        floor: &mut Floor,
    ) -> Result<(), BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();
        let limit = (floor.count() as u64)
            .min(max_objects_in_get(&session))
            .max(1);
        let mut position = 0;

        loop {
            let filter = JmapEmailFilter {
                in_mailbox: Some(mailbox_id.to_string()),
                before: floor
                    .scope()
                    .received_until(RECEIVED_MARGIN_DAYS)
                    .map(|until| until.strftime("%Y-%m-%dT%H:%M:%SZ").to_string()),
                ..Default::default()
            };
            let opts = JmapEmailQueryOptions {
                filter: Some(JmapFilter::Condition(filter)),
                sort: Some(vec![JmapEmailComparator::received_at_desc()]),
                position: Some(position),
                limit: Some(limit),
                properties: Some(vec![JmapEmailProperty::Id, JmapEmailProperty::SentAt]),
            };
            let coroutine =
                JmapEmailQuery::new(&session, &auth, opts).map_err(|err| err.to_string())?;
            let out = self.run_jmap(&api_url, coroutine)?;

            let listed = out.emails.len() as u64;
            for email in &out.emails {
                let date = email.sent_at.as_deref().and_then(utc);
                if floor.take(date.as_deref()) {
                    return Ok(());
                }
            }
            position = out.position + listed;
            if listed == 0 || out.total.is_some_and(|total| position >= total) {
                return Ok(());
            }
        }
    }

    /// The account's `Email` state, what a delta lists from.
    fn jmap_email_state(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
    ) -> Result<String, BridgeError> {
        let opts = JmapEmailGetOptions {
            properties: Some(vec![JmapEmailProperty::Id]),
            ..Default::default()
        };
        let coroutine =
            JmapEmailGet::new(session, auth, Vec::new(), opts).map_err(|err| err.to_string())?;
        Ok(self.run_jmap(api_url, coroutine)?.new_state)
    }

    /// What changed in one mailbox since `state`, as one delta page.
    fn jmap_mail_delta(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
        mailbox_id: &str,
        state: &str,
        scope: &Scope,
    ) -> Result<MailPage, BridgeError> {
        let mut touched = Vec::new();
        let mut vanished = Vec::new();
        let mut since = state.to_string();

        loop {
            let coroutine = JmapEmailChanges::new(
                session,
                auth,
                since.clone(),
                JmapEmailChangesOptions::default(),
            )
            .map_err(|err| err.to_string())?;
            let Some(out) = self.run_email_changes(api_url, coroutine)? else {
                return Ok(MailPage::rejected());
            };
            touched.extend(out.created);
            touched.extend(out.updated);
            vanished.extend(out.destroyed);
            since = out.new_state;
            if !out.has_more_changes {
                break;
            }
        }
        touched.sort();
        touched.dedup();

        let mut items = Vec::new();
        let chunk = JMAP_PAGE.min(max_objects_in_get(session)).max(1) as usize;
        for ids in touched.chunks(chunk) {
            let opts = JmapEmailGetOptions {
                properties: Some(SUMMARY_PROPERTIES.to_vec()),
                ..Default::default()
            };
            let coroutine = JmapEmailGet::new(session, auth, ids.to_vec(), opts)
                .map_err(|err| err.to_string())?;
            let out = self.run_jmap(api_url, coroutine)?;
            vanished.extend(out.not_found);

            for email in out.emails {
                let filed = email
                    .mailbox_ids
                    .as_ref()
                    .is_some_and(|mailboxes| mailboxes.get(mailbox_id).copied().unwrap_or(false));
                match filed {
                    true => items.extend(named(email, scope)),
                    // NOTE: moved out of this mailbox, which is a removal
                    // here whatever its date.
                    false => vanished.extend(email.id),
                }
            }
        }

        Ok(MailPage::delta(items, vanished, since))
    }

    /// Drives one `Email/changes`, surfacing a state the server can no
    /// longer compute changes from as [`None`].
    fn run_email_changes(
        &mut self,
        api_url: &Url,
        mut coroutine: JmapEmailChanges,
    ) -> Result<Option<JmapChangesOutput>, BridgeError> {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(Ok(out)) => return Ok(Some(out)),
                JmapCoroutineState::Complete(Err(JmapEmailChangesError::Changes(
                    JmapChangesError::Method(JmapMethodError::CannotCalculateChanges { .. }),
                ))) => return Ok(None),
                JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                JmapCoroutineState::Yielded(JmapYield::WantsRead) => {
                    arg = Some(self.http_read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.http_write(api_url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// The account's mailboxes as `(id, path, role)` triples, the path
    /// being the names of the mailbox and its ancestors joined by `/`.
    ///
    /// The path rather than the bare name, because the name is what the
    /// store keys a collection by and JMAP lets two mailboxes under
    /// different parents share one: an Archive inside two accounts of a
    /// unified mailbox would otherwise collapse into a single
    /// collection. IMAP hands the app hierarchical names already, so
    /// this is what keeps the two backends storing the same shape.
    ///
    /// The role is the RFC 8621 counterpart of RFC 6154's attributes, of
    /// the one a write needs: which mailbox a delete moves into.
    fn list_jmap_mailboxes(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
    ) -> Result<Vec<(String, String, String)>, BridgeError> {
        let opts = JmapMailboxGetOptions::default();
        let coroutine = JmapMailboxGet::new(session, auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(api_url, coroutine)?;

        let named: BTreeMap<String, JmapMailbox> = out
            .mailboxes
            .into_iter()
            .filter_map(|mailbox| mailbox.id.clone().map(|id| (id, mailbox)))
            .collect();

        Ok(named
            .iter()
            .map(|(id, mailbox)| {
                // NOTE: RFC 8621's roles are pimdir's vocabulary already.
                let role = match mailbox.role {
                    Some(JmapMailboxRole::Inbox) => "inbox",
                    Some(JmapMailboxRole::Sent) => "sent",
                    Some(JmapMailboxRole::Drafts) => "drafts",
                    Some(JmapMailboxRole::Junk) => "junk",
                    Some(JmapMailboxRole::Trash) => "trash",
                    Some(JmapMailboxRole::Archive) => "archive",
                    _ => "",
                };
                (id.clone(), mailbox_path(&named, mailbox), role.into())
            })
            .collect())
    }

    /// Reads one message whole, as the RFC 5322 bytes behind it.
    ///
    /// Two round trips where the body values would have been one: an
    /// `Email/get` for the `blobId`, then the RFC 8620 section 6.2
    /// download of that blob. It buys the one thing the body values
    /// cannot, which is a message the store can hold: what comes back
    /// is the message rather than a server's rendering of it, so it is
    /// filed once and read from the store every time after, offline
    /// included, through the same parser the IMAP side uses.
    pub fn fetch_jmap_source(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<Vec<u8>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapEmailGetOptions {
            properties: Some(vec![JmapEmailProperty::Id, JmapEmailProperty::BlobId]),
            ..Default::default()
        };

        let coroutine = JmapEmailGet::new(&session, &auth, vec![id.to_string()], opts)
            .map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        let blob = out
            .emails
            .into_iter()
            .next()
            .and_then(|email| email.blob_id)
            .ok_or_else(|| BridgeError::from(format!("No message `{id}`")))?;

        let url = download_url(&session, &blob)?;
        self.run_jmap_download(&auth, &url)
    }

    /// Downloads one blob, following the redirects the download URL may
    /// answer with, and returns its bytes.
    fn run_jmap_download(
        &mut self,
        auth: &SecretString,
        download_url: &Url,
    ) -> Result<Vec<u8>, BridgeError> {
        let mut target = download_url.clone();

        loop {
            let mut coroutine = JmapBlobDownload::new(auth, &target);
            let mut arg: Option<Vec<u8>> = None;

            loop {
                match coroutine.resume(arg.as_deref()) {
                    JmapCoroutineState::Complete(Ok(out)) => return Ok(out.data),
                    JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsWrite(bytes)) => {
                        self.http_write(target.as_str(), &bytes)?;
                        arg = None;
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRead) => {
                        arg = Some(self.http_read(target.as_str())?);
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRedirect {
                        url, ..
                    }) => {
                        target = url;
                        break;
                    }
                }
            }
        }
    }

    /// Adds or removes one keyword on one message.
    ///
    /// The IMAP marker crosses as the keyword RFC 8621 section 4.1.1
    /// maps it to, so the caller says `\Seen` whichever backend answers.
    pub fn set_jmap_keyword(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        flag: &str,
        add: bool,
    ) -> Result<(), BridgeError> {
        let keyword = keyword_of(flag)?;
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let mut args = JmapEmailSetArgs::default();
        if add {
            args.set_keyword(id, keyword);
        } else {
            args.unset_keyword(id, keyword);
        }

        self.run_email_set(&session, &auth, &api_url, args, id)
    }

    /// Relocates one message from the mailbox `from` to the mailbox `to`
    /// (mailbox ids), as [`relocation_args`] says.
    pub fn relocate_jmap_message(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        from: &str,
        to: &str,
    ) -> Result<(), BridgeError> {
        let args = relocation_args(id, from, to);
        self.write_jmap_message(session_url, credentials, id, args)
    }

    /// Files one message in the mailbox `to` (a mailbox id) too.
    pub fn copy_jmap_message(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        to: &str,
    ) -> Result<(), BridgeError> {
        let args = copy_args(id, to);
        self.write_jmap_message(session_url, credentials, id, args)
    }

    /// Destroys one message for good.
    pub fn destroy_jmap_message(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<(), BridgeError> {
        let args = destroy_args(id);
        self.write_jmap_message(session_url, credentials, id, args)
    }

    /// Runs one message's `Email/set`.
    fn write_jmap_message(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        args: JmapEmailSetArgs,
    ) -> Result<(), BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        self.run_email_set(&session, &auth, &api_url, args, id)
    }

    /// Runs one `Email/set` and reports the per-object refusal as the
    /// error it is: the method itself succeeds while refusing the one
    /// change it was given, so a bare `Ok` would claim a write nobody
    /// made.
    fn run_email_set(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
        args: JmapEmailSetArgs,
        id: &str,
    ) -> Result<(), BridgeError> {
        let coroutine = JmapEmailSet::new(session, auth, args).map_err(|err| err.to_string())?;
        let out = self.run_jmap(api_url, coroutine)?;

        match out.not_updated.get(id).or(out.not_destroyed.get(id)) {
            Some(refused) => Err(format!("The server refused the change: {refused:?}").into()),
            None => Ok(()),
        }
    }
}

/// The `Email/set` relocating one message from the mailbox `from` to the
/// mailbox `to`: the one membership traded for the other, any other
/// mailbox holding it left alone (RFC 8621 section 4.1.1).
fn relocation_args(id: &str, from: &str, to: &str) -> JmapEmailSetArgs {
    let mut args = JmapEmailSetArgs::default();
    args.add_to_mailbox(id, to).remove_from_mailbox(id, from);
    args
}

/// The `Email/set` filing one message in the mailbox `to` too: the copy a
/// membership is, one object with one id in both.
fn copy_args(id: &str, to: &str) -> JmapEmailSetArgs {
    let mut args = JmapEmailSetArgs::default();
    args.add_to_mailbox(id, to);
    args
}

/// The `Email/set` destroying one message for good.
fn destroy_args(id: &str) -> JmapEmailSetArgs {
    let mut args = JmapEmailSetArgs::default();
    args.destroy(id);
    args
}

/// The JMAP keyword an IMAP marker maps to (RFC 8621 section 4.1.1).
///
/// The three the section names, and no more: `\Deleted` and `\Recent`
/// have no counterpart, JMAP expressing the first as a move and the
/// second not at all.
fn keyword_of(flag: &str) -> Result<&'static str, BridgeError> {
    match flag {
        "\\Seen" => Ok(JMAP_KEYWORD_SEEN),
        "\\Answered" => Ok(JMAP_KEYWORD_ANSWERED),
        "\\Flagged" => Ok(JMAP_KEYWORD_FLAGGED),
        other => Err(format!("No JMAP keyword for the marker `{other}`").into()),
    }
}

/// JMAP calendar operations: the draft-ietf-jmap-calendars Calendar and
/// CalendarEvent reads, each event converted to the iCalendar text the
/// rest of the app renders.
impl<'a, 'local> Client<'a, 'local> {
    /// Lists the account's JMAP Calendars. Collection URLs come out as
    /// the account-scoped path the caller prefixes with the account
    /// base URL, since only it knows that base. Doubles as the
    /// connection check during onboarding.
    pub fn list_jmap_calendars(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Calendar>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapCalendarGetOptions::default();
        let coroutine =
            JmapCalendarGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        Ok(out
            .calendars
            .into_iter()
            .map(|calendar| jmap_calendar(&session, calendar))
            .collect())
    }

    /// Lists a JMAP Calendar's events, each converted to iCalendar.
    ///
    /// Recurrence is left folded: the server could expand it here (that
    /// is the whole point of `CalendarEvent/query`), but the agenda
    /// already expands iCalendar for the CalDAV path, and one expansion
    /// path that both backends feed is worth more than a round trip
    /// saved. The server-side expansion is the optimisation to add
    /// later, with this as its oracle.
    pub fn list_jmap_events(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        calendar_id: &str,
    ) -> Result<Vec<Event>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapCalendarEventQueryOptions {
            filter: Some(JmapCalendarEventFilter {
                in_calendar: Some(calendar_id.to_string()),
                ..Default::default()
            }),
            ..Default::default()
        };
        let coroutine =
            JmapCalendarEventQuery::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        let events: Result<Vec<Event>, String> = out.events.into_iter().map(jmap_event).collect();
        Ok(events?)
    }
}

/// JMAP coroutine runners: the JMAP session fetch and the resume loops
/// that pump a JMAP method coroutine, routing every yield to the
/// transport stream opened on the session's API URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Fetches the JMAP session (RFC 8620 §2) from the session URL
    /// (a bare origin triggers /.well-known/jmap discovery), rebuilding
    /// the coroutine whenever the server answers 3xx.
    /// Fetches the session resource and discards it, so a credential that
    /// cannot sign in fails where the connection is opened rather than on
    /// whichever verb happens to run first.
    pub fn jmap_session_check(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<(), BridgeError> {
        let auth = jmap_auth(credentials);
        self.jmap_session(session_url, &auth).map(|_| ())
    }

    fn jmap_session(
        &mut self,
        session_url: &Url,
        http_auth: &SecretString,
    ) -> Result<JmapSession, BridgeError> {
        let mut target = session_url.clone();

        loop {
            let mut coroutine = JmapSessionGet::new(http_auth, &target);
            let mut arg: Option<Vec<u8>> = None;

            loop {
                match coroutine.resume(arg.as_deref()) {
                    JmapCoroutineState::Complete(Ok(out)) => return Ok(out.session),
                    JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsWrite(bytes)) => {
                        self.http_write(target.as_str(), &bytes)?;
                        arg = None;
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRead) => {
                        arg = Some(self.http_read(target.as_str())?);
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRedirect {
                        url, ..
                    }) => {
                        target = url;
                        break;
                    }
                }
            }
        }
    }

    /// Runs a JMAP method coroutine to completion, routing every yield
    /// to the transport stream opened on the session's API URL.
    fn run_jmap<C, T, E>(&mut self, api_url: &Url, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: JmapCoroutine<Yield = JmapYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(Ok(value)) => return Ok(value),
                JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                JmapCoroutineState::Yielded(JmapYield::WantsRead) => {
                    arg = Some(self.http_read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.http_write(api_url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Drives one JMAP `ContactCard/changes` round, surfacing an
    /// uncomputable state as [`None`] instead of an error (the generic
    /// [`Self::run_jmap`] erases the error variant the fallback needs).
    fn run_jmap_changes(
        &mut self,
        api_url: &Url,
        mut coroutine: JmapContactCardChanges,
    ) -> Result<Option<JmapChangesOutput>, BridgeError> {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(Ok(out)) => return Ok(Some(out)),
                JmapCoroutineState::Complete(Err(JmapContactCardChangesError::Changes(
                    JmapChangesError::Method(JmapMethodError::CannotCalculateChanges { .. }),
                ))) => return Ok(None),
                JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                JmapCoroutineState::Yielded(JmapYield::WantsRead) => {
                    arg = Some(self.http_read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.http_write(api_url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// Authorization header value from the credentials: an empty login means
/// the password field carries an OAuth 2.0 access token (Bearer),
/// otherwise HTTP Basic.
fn jmap_auth(credentials: &Credentials) -> SecretString {
    let value = if credentials.login.is_empty() {
        HttpAuthBearer::new(credentials.password).to_authorization()
    } else {
        HttpAuthBasic::new(credentials.login, credentials.password).to_authorization()
    };

    SecretString::from(value)
}

/// The path of a mailbox: the names of its ancestors and its own,
/// joined by `/`, the way IMAP hands the app a hierarchical name.
///
/// A parent the roster does not carry (unreadable, or removed between
/// the two) ends the walk: a partial path still names the mailbox, and
/// a mailbox dropped for a missing ancestor would hide its mail.
fn mailbox_path(named: &BTreeMap<String, JmapMailbox>, mailbox: &JmapMailbox) -> String {
    let mut segments = vec![mailbox.name.clone().unwrap_or_default()];
    let mut parent = mailbox.parent_id.clone();

    // NOTE: bounded by the roster size, so a server answering with a
    // parent cycle cannot spin here.
    while let Some(id) = parent.take() {
        let Some(ancestor) = named.get(&id) else {
            break;
        };
        if segments.len() > named.len() {
            break;
        }
        segments.push(ancestor.name.clone().unwrap_or_default());
        parent = ancestor.parent_id.clone();
    }

    segments.reverse();
    segments.join("/")
}

/// Where one blob is downloaded from: the session's RFC 6570 template
/// (RFC 8620 section 6.2) with its four variables filled in.
///
/// Filled by substitution rather than by a template engine, because the
/// template is specified variable by variable and there are four of
/// them: the account the blob belongs to, the blob, a name the download
/// is offered under, and the media type it is asked for.
fn download_url(session: &JmapSession, blob: &str) -> Result<Url, BridgeError> {
    let account = session.primary_account_id_for(JMAP_MAIL_CAPABILITY);
    let filled = session
        .download_url
        .replace("{accountId}", &encode(&account))
        .replace("{blobId}", &encode(blob))
        .replace("{name}", &encode("message.eml"))
        .replace("{type}", &encode("message/rfc822"));

    Url::parse(&filled).map_err(|err| BridgeError::from(format!("Invalid download URL: {err}")))
}

/// One template variable as a URL path or query component: everything
/// outside the RFC 3986 unreserved set percent-encoded, a blob id being
/// opaque and free to carry anything.
fn encode(value: &str) -> String {
    let mut encoded = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                encoded.push(char::from(byte))
            }
            _ => encoded.push_str(&format!("%{byte:02X}")),
        }
    }
    encoded
}

/// The server's `maxObjectsInGet` (RFC 8620 §2), the ceiling of one
/// page; the page size itself when the server states none.
fn max_objects_in_get(session: &JmapSession) -> u64 {
    session
        .capabilities
        .get("urn:ietf:params:jmap:core")
        .and_then(|core| core.get("maxObjectsInGet"))
        .and_then(Value::as_u64)
        .filter(|max| *max > 0)
        .unwrap_or(JMAP_PAGE)
}

/// One JMAP Email, named by the summary its properties read, when its
/// `Date` falls in the scope.
///
/// Seen is a keyword rather than a flag on this backend, mapped here so
/// the rest of the app keeps one notion of seen.
fn named(email: JmapEmail, scope: &Scope) -> Option<Named> {
    let summary = email_summary(&email);
    if !scope.contains(summary.date.as_deref()) {
        return None;
    }
    let flags = flags(
        keyword(&email, JMAP_KEYWORD_SEEN),
        keyword(&email, JMAP_KEYWORD_ANSWERED),
        keyword(&email, JMAP_KEYWORD_FLAGGED),
    );
    Some(Named::new(email.id?, flags, summary))
}

/// The Annex A summary of one JMAP Email: the date is `sentAt` (the
/// `Date` header) in UTC, the attachment mark `hasAttachment`.
fn email_summary(email: &JmapEmail) -> PimdirMailSummary {
    let list = |addresses: &Option<Vec<JmapEmailAddress>>| -> Vec<PimdirAddress> {
        addresses
            .iter()
            .flatten()
            .filter_map(|address| {
                let canonical = PimdirAddress::canonical(&address.email);
                (!canonical.is_empty()).then(|| PimdirAddress {
                    address: canonical,
                    name: Some(display_name(address)).filter(|name| !name.is_empty()),
                })
            })
            .collect()
    };
    let from = list(&email.from);
    let first = from.first().cloned();

    PimdirMailSummary {
        message_id: email
            .message_id
            .as_ref()
            .and_then(|ids| ids.first())
            .cloned(),
        in_reply_to: email.in_reply_to.clone().unwrap_or_default(),
        subject: email.subject.as_deref().map(decode).unwrap_or_default(),
        sender: first.as_ref().map(|address| address.address.clone()),
        sender_name: first.and_then(|address| address.name),
        date: email.sent_at.as_deref().and_then(utc),
        size: email.size,
        attachment: email.has_attachment,
        from,
        to: list(&email.to),
        cc: list(&email.cc),
        bcc: Vec::new(),
    }
}

/// Whether one keyword is set on an email.
fn keyword(email: &JmapEmail, keyword: &str) -> bool {
    email
        .keywords
        .as_ref()
        .and_then(|keywords| keywords.get(keyword).copied())
        .unwrap_or(false)
}

/// An email address's display name, empty when it carries none.
fn display_name(address: &JmapEmailAddress) -> String {
    match address.name.as_deref() {
        Some(name) if !name.trim().is_empty() => name.to_string(),
        _ => String::new(),
    }
}

/// io-jmap Calendar to the JNI-facing shape, the twin of
/// [`jmap_addressbook`] down to the account-scoped URL.
fn jmap_calendar(session: &JmapSession, calendar: JmapCalendar) -> Calendar {
    let id = calendar.id.unwrap_or_default();

    Calendar {
        name: calendar.name.clone().unwrap_or_else(|| id.clone()),
        url: jmap_collection_path(session, JMAP_CALENDARS_CAPABILITY, &id),
        id,
        description: calendar.description,
        color: calendar.color,
    }
}

/// io-jmap CalendarEvent to the JNI-facing shape, its JSCalendar
/// payload converted to iCalendar.
///
/// The conversion happens here rather than downstream because the
/// store's collection kind is a media type: `text/calendar` promises
/// one shape, and a second payload format under it would fork every
/// reader of the agenda. The fidelity of the conversion is ical-rs's
/// problem, which is where it belongs.
///
/// There is no ETag: JMAP has no per-object one, and the store does not
/// read the field for calendar items.
fn jmap_event(event: JmapCalendarEvent) -> Result<Event, String> {
    let id = event.id.unwrap_or_default();
    let mut payload = event.event;

    // NOTE: a JSCalendar object with no `@type` reads as a Group, which
    // one CalendarEvent is not. The draft has the server send it; a
    // server that did not would otherwise convert to an empty calendar.
    payload
        .entry("@type")
        .or_insert_with(|| Value::from("Event"));

    let payload = Value::Object(payload);
    let ical = Ical::from_jscalendar(&payload)
        .map_err(|err| format!("Invalid JSCalendar event `{id}`: {err}"))?;

    Ok(Event {
        id,
        etag: None,
        ical: ical.to_string(),
    })
}

/// io-jmap AddressBook to the JNI-facing shape: the display name
/// defaults to the id when the server returned none, and the URL is the
/// account-scoped path of [`jmap_collection_path`], which the caller
/// prefixes with the account base URL.
fn jmap_addressbook(session: &JmapSession, book: JmapAddressBook) -> Addressbook {
    let id = book.id.unwrap_or_default();

    Addressbook {
        name: book.name.clone().unwrap_or_else(|| id.clone()),
        url: jmap_collection_path(session, JMAP_CONTACTS_CAPABILITY, &id),
        id,
        description: book.description,
        color: None,
    }
}

/// The account-scoped path of one JMAP collection: the JMAP account id
/// serving the capability, then the collection's own id.
///
/// A collection id is unique inside its JMAP account and nowhere else,
/// so two accounts on one provider routinely name a book or a calendar
/// the same. The app keys a stored collection by its URL, so without
/// the account id in front the two would be one row that each sync
/// round re-points at the other account, merging two people's data.
/// [`account::jmap_collection_id`] takes the prefix back off wherever
/// the protocol wants the bare id.
///
/// A session naming no account for the capability yields the bare id,
/// which is what the app addressed before and is no worse.
fn jmap_collection_path(session: &JmapSession, capability: &str, id: &str) -> String {
    match session.primary_accounts.get(capability) {
        Some(account) if !account.is_empty() => format!("{account}/{id}"),
        _ => id.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use io_jmap::{rfc8620::session::JmapSession, rfc8621::JMAP_MAIL_CAPABILITY};
    use std::collections::BTreeMap;
    use url::Url;

    use serde_json::{json, to_value};

    use super::{copy_args, destroy_args, download_url, relocation_args};

    fn session(template: &str) -> JmapSession {
        JmapSession {
            username: String::new(),
            accounts: BTreeMap::new(),
            primary_accounts: BTreeMap::from([(
                JMAP_MAIL_CAPABILITY.to_string(),
                "u42".to_string(),
            )]),
            capabilities: BTreeMap::new(),
            api_url: Url::parse("https://api.example.com/jmap/").unwrap(),
            download_url: template.to_string(),
            upload_url: String::new(),
            event_source_url: String::new(),
            state: String::new(),
        }
    }

    /// Every variable, or the download reaches a path with a brace in it
    /// and the server answers 404 rather than the message.
    #[test]
    fn a_download_url_leaves_no_variable_behind() {
        let session = session(
            "https://api.example.com/jmap/download/{accountId}/{blobId}/{name}?accept={type}",
        );

        let url = download_url(&session, "G12ab").unwrap();

        assert_eq!(
            url.as_str(),
            "https://api.example.com/jmap/download/u42/G12ab/message.eml?accept=message%2Frfc822"
        );
    }

    /// A blob id is opaque, so it routinely carries what a path separates
    /// segments with.
    #[test]
    fn a_blob_id_is_encoded_rather_than_pasted() {
        let session = session("https://api.example.com/d/{accountId}/{blobId}");

        let url = download_url(&session, "a/b c").unwrap();

        assert_eq!(url.as_str(), "https://api.example.com/d/u42/a%2Fb%20c");
    }

    #[test]
    fn a_relocation_trades_one_membership_for_the_other() {
        let args = to_value(relocation_args("m1", "inbox", "trash")).unwrap();

        assert_eq!(
            args,
            json!({ "update": { "m1": { "mailboxIds/trash": true, "mailboxIds/inbox": null } } })
        );
    }

    #[test]
    fn a_copy_adds_a_membership() {
        let args = to_value(copy_args("m1", "archive")).unwrap();

        assert_eq!(
            args,
            json!({ "update": { "m1": { "mailboxIds/archive": true } } })
        );
    }

    #[test]
    fn a_delete_destroys() {
        let args = to_value(destroy_args("m1")).unwrap();

        assert_eq!(args, json!({ "destroy": ["m1"] }));
    }
}
