//! JMAP operations: the RFC 9610 AddressBook and ContactCard verbs, the
//! ContactCard/set push and the ContactCard/changes sync round, the RFC
//! 8621 Mailbox and Email reads behind the account-wide mail walk and the
//! RFC 8621 section 7 submission, and the draft-ietf-jmap-calendars
//! Calendar and CalendarEvent reads and the CalendarEvent/set writes.
//!
//! One JMAP session serves all three domains, which is why they share a
//! file: the session fetch and its cache, the auth header and the resume
//! loops are the same code whichever capability a verb declares.

use core::error::Error as StdError;

use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{Mutex, MutexGuard},
    time::{Duration, Instant},
};

use ical::ical::Ical;
use io_http::{rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic};
use io_jmap::{
    calendars::{
        JMAP_CALENDARS_CAPABILITY,
        calendar::{JmapCalendar, JmapCalendarRights, get::*},
        calendar_event::{JmapCalendarEvent, get::*, query::*, set::*},
    },
    coroutine::{JmapCoroutine, JmapCoroutineState, JmapYield},
    rfc8620::{
        blob_download::JmapBlobDownload, blob_upload::JmapBlobUpload, changes::*,
        coroutine::JmapRedirectYield, error::JmapMethodError, filter::JmapFilter,
        session::JmapSession, session_get::*, set::JmapSetError,
    },
    rfc8621::{
        JMAP_MAIL_CAPABILITY,
        email::{
            JMAP_KEYWORD_ANSWERED, JMAP_KEYWORD_DRAFT, JMAP_KEYWORD_FLAGGED, JMAP_KEYWORD_SEEN,
            JmapEmail, JmapEmailAddress, JmapEmailProperty,
            changes::{JmapEmailChanges, JmapEmailChangesError, JmapEmailChangesOptions},
            get::*,
            import::*,
            query::*,
            set::*,
        },
        email_submission::{
            JMAP_SUBMISSION_CAPABILITY, JmapEmailAddressWithParameters,
            JmapEmailSubmissionSetItemError, JmapEnvelope, set::*,
        },
        identity::{JmapIdentity, get::*},
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
use secrecy::{ExposeSecret, SecretString};
use serde_json::Value;
use sha2::{Digest, Sha256};
use url::Url;

use crate::{
    client::{
        Client,
        convert::{REFUSED, coroutine_error, rejected, required},
        listing::{
            Floor, JMAP_PAGE, Listing, MailPage, MailRequest, Named, RECEIVED_MARGIN_DAYS, Scope,
            flags, utc,
        },
    },
    jmap,
    mail::{self, Composed},
    types::{
        Addressbook, BridgeError, Calendar, Card, CardDelta, Credentials, Event, EventRef, Mailbox,
        PushChange, PushOutcome, default_role,
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
    /// the card's books (natively m:n). Paged as [`list_cards`] says.
    pub fn list_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<Card>, BridgeError> {
        let mut calls = self.jmap_calls(session_url, credentials)?;
        let limit = page_size(&calls.session);

        Ok(list_cards(&mut calls, limit)?.0)
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
            return Err(format!("The server refused the new contact: {err}").into());
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

    /// Lists the ContactCard changes since `since_state`, as
    /// [`card_delta`] says.
    pub fn changes_jmap_cards(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        since_state: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let mut calls = self.jmap_calls(session_url, credentials)?;
        let limit = page_size(&calls.session);

        card_delta(&mut calls, since_state, limit)
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
            return Err(format!("The server refused the contact change: {err}").into());
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
            return Err(format!("The server refused to delete the contact: {err}").into());
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
            return Err(format!("The server refused the address book change: {err}").into());
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
                    outcomes.push(rejected(reference, err.to_string()));
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
                    Some(err) => outcomes.push(rejected(reference, err.to_string())),
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
                    Some(err) => outcomes.push(rejected(reference, err.to_string())),
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

        let limit = page_size(&session);
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
        let chunk = page_size(session).max(1) as usize;
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
                JmapCoroutineState::Complete(Err(err)) => {
                    sessions().forget(api_url);
                    return Err(coroutine_error(&err));
                }
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
        let out = self.run_jmap_redirect(&url, |target| JmapBlobDownload::new(&auth, target))?;
        Ok(out.data)
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
    ///
    /// A message the server no longer finds is the exception: whatever
    /// the change was, there is nothing left for it to apply to, so it
    /// converged, as a destroyed ContactCard does.
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
            Some(JmapEmailSetItemError::NotFound { .. }) | None => Ok(()),
            Some(refused) => Err(format!("The server refused the change: {refused}").into()),
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

    /// Lists a JMAP Calendar's events, each converted to iCalendar, and
    /// whether the listing reached its end ([`list_events`]).
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
    ) -> Result<(Vec<Event>, bool), BridgeError> {
        let mut calls = self.jmap_calls(session_url, credentials)?;
        let limit = page_size(&calls.session);

        list_events(&mut calls, calendar_id, limit)
    }
}

/// JMAP calendar writes: draft-ietf-jmap-calendars `CalendarEvent/set`,
/// an override or an `EXDATE` travelling inside its event as a
/// `recurrenceOverrides` entry rather than as a write of its own.
impl<'a, 'local> Client<'a, 'local> {
    /// Files one object in the calendar `calendar_id`, as [`create_event`]
    /// says.
    pub fn create_jmap_event(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        calendar_id: &str,
        ical: &str,
    ) -> Result<EventRef, BridgeError> {
        let mut calls = self.jmap_calls(session_url, credentials)?;
        create_event(&mut calls, calendar_id, ical)
    }

    /// Writes one edited object over the event `id`, as [`update_event`]
    /// says, answering its new revision.
    pub fn update_jmap_event(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let mut calls = self.jmap_calls(session_url, credentials)?;
        update_event(&mut calls, id, ical, if_match)
    }

    /// Destroys the event `id`, as [`destroy_event`] says.
    pub fn delete_jmap_event(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let mut calls = self.jmap_calls(session_url, credentials)?;
        destroy_event(&mut calls, id, if_match)
    }
}

/// JMAP submission: the RFC 8621 section 7 send of one stored message.
impl<'a, 'local> Client<'a, 'local> {
    /// Sends one stored message over the session, as [`send`] says.
    ///
    /// A session advertising no submission is refused for good before
    /// anything is written: no later pass changes what the server offers.
    pub fn send_jmap_message(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        raw: &[u8],
    ) -> Result<(), BridgeError> {
        let mut calls = self.jmap_calls(session_url, credentials)?;
        if !calls
            .session
            .capabilities
            .contains_key(JMAP_SUBMISSION_CAPABILITY)
        {
            return Err(refused("This server does not offer sending over JMAP"));
        }
        let max_upload = calls.session.core_capability().max_size_upload;

        send(&mut calls, raw, max_upload)
    }
}

/// JMAP coroutine runners: the JMAP session fetch and the resume loops
/// that pump a JMAP method coroutine, routing every yield to the
/// transport stream opened on the session's API URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Fetches the session resource afresh, so a credential that cannot
    /// sign in fails where the connection is opened rather than on
    /// whichever verb happens to run first, and the verbs after it read
    /// the session this fetch cached.
    pub fn jmap_session_check(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<(), BridgeError> {
        let auth = jmap_auth(credentials);
        self.fetch_jmap_session(session_url, &auth).map(|_| ())
    }

    /// The capability URNs the session serves an account for (see
    /// [`served_capabilities`]), from a fresh fetch: what the connection
    /// flow decides which domains to connect by.
    pub fn jmap_capabilities(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<Vec<&'static str>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.fetch_jmap_session(session_url, &auth)?;
        Ok(served_capabilities(&session))
    }

    /// The verbs' calls over the session, cached or fetched.
    fn jmap_calls<'c>(
        &'c mut self,
        session_url: &Url,
        credentials: &Credentials,
    ) -> Result<JmapCalls<'c, 'a, 'local>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        Ok(JmapCalls {
            client: self,
            session,
            auth,
        })
    }

    /// The session resource (RFC 8620 §2), from the cache when it holds
    /// one for this URL and credential ([`SessionCache`]), fetched
    /// otherwise.
    fn jmap_session(
        &mut self,
        session_url: &Url,
        http_auth: &SecretString,
    ) -> Result<JmapSession, BridgeError> {
        let key = session_key(session_url, http_auth);
        let cached = sessions().get(&key, Instant::now());
        match cached {
            Some(session) => Ok(session),
            None => self.fetch_jmap_session(session_url, http_auth),
        }
    }

    /// Fetches the session resource from the session URL (a bare origin
    /// triggers /.well-known/jmap discovery), following redirects, and
    /// caches it.
    fn fetch_jmap_session(
        &mut self,
        session_url: &Url,
        http_auth: &SecretString,
    ) -> Result<JmapSession, BridgeError> {
        let out =
            self.run_jmap_redirect(session_url, |target| JmapSessionGet::new(http_auth, target))?;
        let key = session_key(session_url, http_auth);
        sessions().put(key, Instant::now(), out.session.clone());
        Ok(out.session)
    }

    /// Runs a coroutine that may answer 3xx, rebuilding it against the
    /// new target each time.
    fn run_jmap_redirect<C, T, E>(
        &mut self,
        start: &Url,
        make: impl Fn(&Url) -> C,
    ) -> Result<T, BridgeError>
    where
        C: JmapCoroutine<Yield = JmapRedirectYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut target = start.clone();

        loop {
            let mut coroutine = make(&target);
            let mut arg: Option<Vec<u8>> = None;

            loop {
                match coroutine.resume(arg.as_deref()) {
                    JmapCoroutineState::Complete(Ok(out)) => return Ok(out),
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
    /// to the transport stream opened on the session's API URL. A
    /// failure drops the sessions cached for that API, so the next verb
    /// reads the session again rather than trusting a stale one.
    fn run_jmap<C, T, E>(&mut self, api_url: &Url, coroutine: C) -> Result<T, BridgeError>
    where
        C: JmapCoroutine<Yield = JmapYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        self.resume_jmap(api_url, coroutine)?.map_err(|err| {
            sessions().forget(api_url);
            coroutine_error(&err)
        })
    }

    /// Drives one JMAP `ContactCard/changes` round, surfacing an
    /// uncomputable state as [`None`] instead of an error (the generic
    /// [`Self::run_jmap`] erases the error variant the fallback needs).
    fn run_jmap_changes(
        &mut self,
        api_url: &Url,
        coroutine: JmapContactCardChanges,
    ) -> Result<Option<JmapChangesOutput>, BridgeError> {
        match self.resume_jmap(api_url, coroutine)? {
            Ok(out) => Ok(Some(out)),
            Err(JmapContactCardChangesError::Changes(JmapChangesError::Method(
                JmapMethodError::CannotCalculateChanges { .. },
            ))) => Ok(None),
            Err(err) => {
                sessions().forget(api_url);
                Err(coroutine_error(&err))
            }
        }
    }

    /// Runs one `CalendarEvent/set`, a call refused for its `ifInState`
    /// answering 412 ([`set_failure`]) rather than a bare error.
    fn run_jmap_event_set(
        &mut self,
        api_url: &Url,
        coroutine: JmapCalendarEventSet,
    ) -> Result<JmapCalendarEventSetOutput, BridgeError> {
        self.resume_jmap(api_url, coroutine)?.map_err(|err| {
            sessions().forget(api_url);
            set_failure(&err)
        })
    }

    /// Resumes a JMAP method coroutine to completion, routing every yield
    /// to the transport stream opened on the session's API URL: the
    /// transport's failures as the outer error, the coroutine's own result
    /// as it completed, for the runners above to read.
    fn resume_jmap<C, T, E>(
        &mut self,
        api_url: &Url,
        mut coroutine: C,
    ) -> Result<Result<T, E>, BridgeError>
    where
        C: JmapCoroutine<Yield = JmapYield, Return = Result<T, E>>,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(result) => return Ok(result),
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

/// How long a cached session resource is trusted.
///
/// The session's `state` would say when it changed, but io-jmap's method
/// outputs do not surface the `sessionState` each response carries, so
/// the cache is bounded by age instead, by any call failing on it, and by
/// every mail session opening afresh.
const SESSION_TTL: Duration = Duration::from_secs(15 * 60);

/// The session resources fetched so far, by session URL and credential:
/// one fetch serves every verb of a pass, and the passes of every domain
/// the account's one session covers.
struct SessionCache {
    entries: BTreeMap<String, (Instant, JmapSession)>,
}

impl SessionCache {
    /// The session cached under `key`, unless it is older than
    /// [`SESSION_TTL`].
    fn get(&mut self, key: &str, now: Instant) -> Option<JmapSession> {
        let (at, session) = self.entries.get(key)?;
        if now.duration_since(*at) < SESSION_TTL {
            return Some(session.clone());
        }
        self.entries.remove(key);
        None
    }

    /// Caches one session, dropping those past [`SESSION_TTL`]: a refreshed
    /// token is a new key, and the old one would stay for good otherwise.
    fn put(&mut self, key: String, now: Instant, session: JmapSession) {
        self.entries
            .retain(|_, (at, _)| now.duration_since(*at) < SESSION_TTL);
        self.entries.insert(key, (now, session));
    }

    /// Drops every session reached through `api_url`.
    fn forget(&mut self, api_url: &Url) {
        self.entries
            .retain(|_, (_, session)| session.api_url != *api_url);
    }
}

/// The process's session cache.
fn sessions() -> MutexGuard<'static, SessionCache> {
    static SESSIONS: Mutex<SessionCache> = Mutex::new(SessionCache {
        entries: BTreeMap::new(),
    });
    SESSIONS.lock().unwrap_or_else(|err| err.into_inner())
}

/// The cache key of one session: its URL and a digest of the credential,
/// so a refreshed token or another login never reads a session another
/// one fetched.
fn session_key(session_url: &Url, auth: &SecretString) -> String {
    let digest = Sha256::digest(auth.expose_secret().as_bytes());
    let hex: String = digest.iter().map(|byte| format!("{byte:02x}")).collect();
    format!("{session_url} {hex}")
}

/// The requests of one JMAP verb over one native call's transport, apart
/// so the verb's logic can run over a fake.
struct JmapCalls<'c, 'a, 'local> {
    client: &'c mut Client<'a, 'local>,
    session: JmapSession,
    auth: SecretString,
}

/// The request a calendar listing sends: one `CalendarEvent/query` page,
/// its events read with it.
trait JmapEventPages {
    /// One page of a calendar's events from `position`, `limit` at most.
    fn events(
        &mut self,
        calendar_id: &str,
        position: u64,
        limit: u64,
    ) -> Result<JmapCalendarEventQueryOutput, BridgeError>;
}

impl JmapEventPages for JmapCalls<'_, '_, '_> {
    fn events(
        &mut self,
        calendar_id: &str,
        position: u64,
        limit: u64,
    ) -> Result<JmapCalendarEventQueryOutput, BridgeError> {
        let opts = JmapCalendarEventQueryOptions {
            filter: Some(JmapCalendarEventFilter {
                in_calendar: Some(calendar_id.to_string()),
                ..Default::default()
            }),
            position: Some(position),
            limit: Some(limit),
            ..Default::default()
        };
        let coroutine = JmapCalendarEventQuery::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        self.client.run_jmap(&self.session.api_url, coroutine)
    }
}

/// The requests a calendar write sends: one event read with the state
/// the account's events stand at, and one `CalendarEvent/set`.
trait JmapEventWrites {
    /// The event `id`, [`None`] when the server holds none, and the
    /// `CalendarEvent` state it was read at.
    fn event(&mut self, id: &str) -> Result<(Option<JmapCalendarEvent>, String), BridgeError>;

    /// Runs one `CalendarEvent/set`, one refused for its `ifInState`
    /// answering 412.
    fn set(
        &mut self,
        args: JmapCalendarEventSetArgs,
    ) -> Result<JmapCalendarEventSetOutput, BridgeError>;
}

impl JmapEventWrites for JmapCalls<'_, '_, '_> {
    fn event(&mut self, id: &str) -> Result<(Option<JmapCalendarEvent>, String), BridgeError> {
        let opts = JmapCalendarEventGetOptions {
            ids: Some(vec![id.to_string()]),
            ..Default::default()
        };
        let coroutine = JmapCalendarEventGet::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        let out = self.client.run_jmap(&self.session.api_url, coroutine)?;
        Ok((out.events.into_iter().next(), out.new_state))
    }

    fn set(
        &mut self,
        args: JmapCalendarEventSetArgs,
    ) -> Result<JmapCalendarEventSetOutput, BridgeError> {
        let coroutine = JmapCalendarEventSet::new(&self.session, &self.auth, args)
            .map_err(|err| err.to_string())?;
        self.client
            .run_jmap_event_set(&self.session.api_url, coroutine)
    }
}

/// The requests a contacts round sends: the `ContactCard` state, one
/// `ContactCard/query` page with its cards, named cards, and one
/// `ContactCard/changes` page.
trait JmapCardReads {
    /// The account's `ContactCard` state, what a delta lists from.
    fn state(&mut self) -> Result<String, BridgeError>;

    /// One page of the account's cards from `position`, `limit` at most.
    fn page(
        &mut self,
        position: u64,
        limit: u64,
    ) -> Result<JmapContactCardQueryOutput, BridgeError>;

    /// The cards named.
    fn cards(&mut self, ids: Vec<String>) -> Result<Vec<JmapContactCard>, BridgeError>;

    /// One page of changes since `state`; [`None`] when the server can no
    /// longer compute changes from it.
    fn changes(&mut self, state: &str) -> Result<Option<JmapChangesOutput>, BridgeError>;
}

impl JmapCardReads for JmapCalls<'_, '_, '_> {
    fn state(&mut self) -> Result<String, BridgeError> {
        let opts = JmapContactCardGetOptions {
            ids: Some(Vec::new()),
            ..Default::default()
        };
        let coroutine = JmapContactCardGet::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        Ok(self
            .client
            .run_jmap(&self.session.api_url, coroutine)?
            .new_state)
    }

    fn page(
        &mut self,
        position: u64,
        limit: u64,
    ) -> Result<JmapContactCardQueryOutput, BridgeError> {
        let opts = JmapContactCardQueryOptions {
            position: Some(position),
            limit: Some(limit),
            ..Default::default()
        };
        let coroutine = JmapContactCardQuery::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        self.client.run_jmap(&self.session.api_url, coroutine)
    }

    fn cards(&mut self, ids: Vec<String>) -> Result<Vec<JmapContactCard>, BridgeError> {
        let opts = JmapContactCardGetOptions {
            ids: Some(ids),
            ..Default::default()
        };
        let coroutine = JmapContactCardGet::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        Ok(self
            .client
            .run_jmap(&self.session.api_url, coroutine)?
            .cards)
    }

    fn changes(&mut self, state: &str) -> Result<Option<JmapChangesOutput>, BridgeError> {
        let opts = JmapContactCardChangesOptions::default();
        let coroutine =
            JmapContactCardChanges::new(&self.session, &self.auth, state.to_string(), opts)
                .map_err(|err| err.to_string())?;
        self.client
            .run_jmap_changes(&self.session.api_url, coroutine)
    }
}

/// The requests a submission sends (RFC 8621 sections 4.8, 6.1, 7.5 and
/// RFC 8620 section 6.1).
trait JmapSubmit {
    /// The identities the account may send as.
    fn identities(&mut self) -> Result<Vec<JmapIdentity>, BridgeError>;

    /// The account's mailboxes as `(id, path, role)` triples.
    fn mailboxes(&mut self) -> Result<Vec<(String, String, String)>, BridgeError>;

    /// Uploads one message, answering its blob id.
    fn upload(&mut self, message: Vec<u8>) -> Result<String, BridgeError>;

    /// Imports the blob into the mailbox `drafts` as a draft, seen.
    fn import(&mut self, blob_id: &str, drafts: &str)
    -> Result<JmapEmailImportOutput, BridgeError>;

    /// Runs one `EmailSubmission/set`.
    fn submit(
        &mut self,
        args: JmapEmailSubmissionSetArgs,
    ) -> Result<JmapEmailSubmissionSetOutput, BridgeError>;

    /// Destroys one email.
    fn destroy(&mut self, id: &str) -> Result<(), BridgeError>;
}

impl JmapSubmit for JmapCalls<'_, '_, '_> {
    fn identities(&mut self) -> Result<Vec<JmapIdentity>, BridgeError> {
        let opts = JmapIdentityGetOptions::default();
        let coroutine =
            JmapIdentityGet::new(&self.session, &self.auth, opts).map_err(|err| err.to_string())?;
        Ok(self
            .client
            .run_jmap(&self.session.api_url, coroutine)?
            .identities)
    }

    fn mailboxes(&mut self) -> Result<Vec<(String, String, String)>, BridgeError> {
        self.client
            .list_jmap_mailboxes(&self.session, &self.auth, &self.session.api_url)
    }

    fn upload(&mut self, message: Vec<u8>) -> Result<String, BridgeError> {
        let account = self.session.primary_account_id_for(JMAP_MAIL_CAPABILITY);
        let url = self
            .session
            .resolve_upload_url(&account)
            .map_err(|err| format!("Invalid upload URL: {err}"))?;
        let auth = &self.auth;
        let out = self.client.run_jmap_redirect(&url, |target| {
            JmapBlobUpload::new(auth, target, "message/rfc822", message.clone())
        })?;
        Ok(out.blob_id)
    }

    fn import(
        &mut self,
        blob_id: &str,
        drafts: &str,
    ) -> Result<JmapEmailImportOutput, BridgeError> {
        let import = JmapEmailImportArgs {
            blob_id: blob_id.to_string(),
            mailbox_ids: BTreeMap::from([(drafts.to_string(), true)]),
            keywords: Some(BTreeMap::from([
                (JMAP_KEYWORD_DRAFT.to_string(), true),
                (JMAP_KEYWORD_SEEN.to_string(), true),
            ])),
            received_at: None,
        };
        let emails = BTreeMap::from([("m0".to_string(), import)]);
        let coroutine = JmapEmailImport::new(&self.session, &self.auth, emails)
            .map_err(|err| err.to_string())?;
        self.client.run_jmap(&self.session.api_url, coroutine)
    }

    fn submit(
        &mut self,
        args: JmapEmailSubmissionSetArgs,
    ) -> Result<JmapEmailSubmissionSetOutput, BridgeError> {
        let coroutine = JmapEmailSubmissionSet::new(&self.session, &self.auth, args)
            .map_err(|err| err.to_string())?;
        self.client.run_jmap(&self.session.api_url, coroutine)
    }

    fn destroy(&mut self, id: &str) -> Result<(), BridgeError> {
        self.client.run_email_set(
            &self.session,
            &self.auth,
            &self.session.api_url,
            destroy_args(id),
            id,
        )
    }
}

/// Reads a query a page at a time from position 0, `page` answering one
/// page's objects, the total the server stated and the position the page
/// starts at; answers what was read and whether that is everything.
///
/// Everything once the stated total is reached, or once a page comes
/// back empty from a server stating none. A page that comes back empty
/// short of the total, or that does not move past the last one, is a
/// server capping the listing: it ends there, incomplete, so nothing it
/// left out reads as removed.
fn paged<T>(
    mut page: impl FnMut(u64) -> Result<(Vec<T>, Option<u64>, u64), BridgeError>,
) -> Result<(Vec<T>, bool), BridgeError> {
    let mut read = Vec::new();
    let mut position = 0;

    loop {
        let (objects, total, at) = page(position)?;
        let count = objects.len() as u64;
        read.extend(objects);
        let reached = at + count;
        if total.is_some_and(|total| reached >= total) {
            return Ok((read, true));
        }
        if count == 0 || reached <= position {
            return Ok((read, total.is_none() && count == 0));
        }
        position = reached;
    }
}

/// Lists one calendar's events a page of `limit` at a time ([`paged`]),
/// each converted to iCalendar, once each.
fn list_events(
    calls: &mut impl JmapEventPages,
    calendar_id: &str,
    limit: u64,
) -> Result<(Vec<Event>, bool), BridgeError> {
    let (events, complete) = paged(|position| {
        let out = calls.events(calendar_id, position, limit)?;
        Ok((out.events, out.total, out.position))
    })?;

    let mut named = BTreeMap::new();
    for event in events {
        let event = jmap_event(event)?;
        named.insert(event.id.clone(), event);
    }
    Ok((named.into_values().collect(), complete))
}

/// Files one object in the calendar as a new CalendarEvent: its one
/// JSCalendar entry ([`jmap::to_jscalendar_event`]) created in the
/// calendar, under the id the server gives it, then read back for the
/// revision the next edit is staged against.
fn create_event(
    calls: &mut impl JmapEventWrites,
    calendar_id: &str,
    ical: &str,
) -> Result<EventRef, BridgeError> {
    let event = JmapCalendarEvent {
        calendar_ids: BTreeMap::from([(calendar_id.to_string(), true)]),
        event: jmap::to_jscalendar_event(ical).map_err(refused)?,
        ..Default::default()
    };
    let args = JmapCalendarEventSetArgs {
        create: Some(BTreeMap::from([("e0".to_string(), event)])),
        ..Default::default()
    };

    let out = calls.set(args)?;
    if let Some(err) = out.not_created.get("e0") {
        return Err(item_failure("The server refused the new event", err));
    }
    let id = out
        .created
        .get("e0")
        .and_then(|created| created.id.clone())
        .ok_or("The server created the event under no id")?;

    Ok(EventRef {
        etag: written_revision(calls, &id),
        id,
    })
}

/// Writes one edited object over the event `id`, answering its new
/// revision.
///
/// The event is read first: one gone, or whose revision moved since
/// `if_match`, answers 412, the edit staged against something the server
/// no longer holds. The patch is what differs between the staged object
/// and the server copy as the app sees it, converted to iCalendar and
/// back ([`jmap::to_event_patch`]), so a member the conversion cannot
/// carry compares equal on both sides and is never sent: the write
/// changes what the edit changed and nothing the app never had. It goes
/// under `ifInState`, the state that read answered, so nothing lands
/// between the check and the write where the server honours it (Stalwart
/// 0.16 ignores it, the revision check standing alone there).
fn update_event(
    calls: &mut impl JmapEventWrites,
    id: &str,
    ical: &str,
    if_match: Option<&str>,
) -> Result<Option<String>, BridgeError> {
    let (current, state) = calls.event(id)?;
    let Some(current) = current else {
        return Err(moved(format!("Event {id} is gone from the server")));
    };
    let revision = jmap::etag(&current);
    if if_match.is_some_and(|expected| revision.as_deref() != Some(expected)) {
        return Err(moved(format!(
            "Event {id} changed on the server since it was read"
        )));
    }

    let held = jmap_event(current)?;
    let base = jmap::to_jscalendar_event(&held.ical).map_err(refused)?;
    let staged = jmap::to_jscalendar_event(ical).map_err(refused)?;
    let patch = jmap::to_event_patch(&staged, &base);
    if patch.is_empty() {
        return Ok(revision);
    }

    let args = JmapCalendarEventSetArgs {
        if_in_state: Some(state),
        update: Some(BTreeMap::from([(
            id.to_string(),
            JmapCalendarEventPatch(patch),
        )])),
        ..Default::default()
    };
    let out = calls.set(args)?;
    if let Some(err) = out.not_updated.get(id) {
        return Err(item_failure("The server refused the event change", err));
    }

    Ok(written_revision(calls, id))
}

/// The revision of an event just written, read back; none when the read
/// fails, the write having landed: failing it would push it again, and
/// the next listing names the revision anyway.
fn written_revision(calls: &mut impl JmapEventWrites, id: &str) -> Option<String> {
    match calls.event(id) {
        Ok((stored, _)) => stored.as_ref().and_then(jmap::etag),
        Err(err) => {
            log::warn!("event {id} written, its revision unread: {err}");
            None
        }
    }
}

/// Destroys the event `id`. Staged against a revision, the event is read
/// first, as [`update_event`] does: one gone already converged, one moved
/// answers 412, and the destroy goes under the state that read answered.
/// One the server no longer finds converged as well.
fn destroy_event(
    calls: &mut impl JmapEventWrites,
    id: &str,
    if_match: Option<&str>,
) -> Result<(), BridgeError> {
    let mut if_in_state = None;
    if let Some(expected) = if_match {
        let (current, state) = calls.event(id)?;
        let Some(current) = current else {
            return Ok(());
        };
        if jmap::etag(&current).as_deref() != Some(expected) {
            return Err(moved(format!(
                "Event {id} changed on the server since it was read"
            )));
        }
        if_in_state = Some(state);
    }

    let args = JmapCalendarEventSetArgs {
        if_in_state,
        destroy: Some(vec![id.to_string()]),
        ..Default::default()
    };
    match calls.set(args)?.not_destroyed.get(id) {
        None | Some(JmapCalendarEventSetItemError::NotFound { .. }) => Ok(()),
        Some(err) => Err(item_failure("The server refused to delete the event", err)),
    }
}

/// What one event a `CalendarEvent/set` refused answers: refused for good
/// ([`REFUSED`]) when the server objects to the change itself, which no
/// later pass changes; 412 otherwise, the change kept staged for a pass
/// that reads the event again (an event gone, a rate limit, anything this
/// app does not know).
fn item_failure(what: &str, err: &JmapCalendarEventSetItemError) -> BridgeError {
    let message = format!("{what}: {err}");
    match err {
        JmapCalendarEventSetItemError::Forbidden { .. }
        | JmapCalendarEventSetItemError::InvalidProperties { .. }
        | JmapCalendarEventSetItemError::InvalidPatch { .. }
        | JmapCalendarEventSetItemError::TooLarge { .. }
        | JmapCalendarEventSetItemError::OverQuota { .. }
        | JmapCalendarEventSetItemError::Singleton { .. }
        | JmapCalendarEventSetItemError::NoSupportedScheduleMethods { .. } => refused(message),
        JmapCalendarEventSetItemError::NotFound { .. }
        | JmapCalendarEventSetItemError::RateLimit { .. }
        | JmapCalendarEventSetItemError::WillDestroy { .. }
        | JmapCalendarEventSetItemError::Unknown => moved(message),
    }
}

/// A `CalendarEvent/set` that failed as a whole: refused for its
/// `ifInState` (RFC 8620 section 5.3), the account's events moved between
/// the read and the write, it answers 412 as a moved event does.
fn set_failure(err: &JmapCalendarEventSetError) -> BridgeError {
    match err {
        JmapCalendarEventSetError::Set(JmapSetError::Method(JmapMethodError::StateMismatch {
            ..
        })) => moved("The calendar changed on the server since the event was read"),
        err => coroutine_error(err),
    }
}

/// A write staged against something the server no longer holds: 412, as
/// a CalDAV server answers a failed precondition, the change kept staged.
fn moved(message: impl Into<String>) -> BridgeError {
    BridgeError {
        message: message.into(),
        status: Some(412),
    }
}

/// Lists the account's cards a page of `limit` at a time ([`paged`]),
/// each converted to a vCard document, once each: RFC 8620 section 5.1
/// lets a server refuse a `ContactCard/get` of every card past its
/// `maxObjectsInGet`, which is what the unpaged get it replaces hit.
fn list_cards(
    calls: &mut impl JmapCardReads,
    limit: u64,
) -> Result<(Vec<Card>, bool), BridgeError> {
    let (cards, complete) = paged(|position| {
        let out = calls.page(position, limit)?;
        Ok((out.cards, out.total, out.position))
    })?;

    let mut named = BTreeMap::new();
    for card in cards {
        let card = jmap::to_card(card)?;
        named.insert(card.id.clone(), card);
    }
    Ok((named.into_values().collect(), complete))
}

/// Lists the ContactCard changes since `since` (RFC 8620 `/changes`), the
/// changed cards read whole, `limit` to a get, so their JSON-hash ETag
/// doubles as the content revision. Without a state, the initial round
/// reads the state first, then every card ([`list_cards`]), so what
/// changes while it lists is the next delta's; it is complete when the
/// listing is. Answers [`None`] when the server can no longer compute
/// changes from the state, for the caller to fall back to an initial
/// round.
fn card_delta(
    calls: &mut impl JmapCardReads,
    since: Option<&str>,
    limit: u64,
) -> Result<Option<CardDelta>, BridgeError> {
    let Some(since) = since else {
        let state = calls.state()?;
        let (changed, complete) = list_cards(calls, limit)?;
        return Ok(Some(CardDelta {
            changed,
            vanished: Vec::new(),
            token: Some(state),
            complete,
        }));
    };

    let mut cursor = since.to_string();
    let mut changed_ids = BTreeSet::new();
    let mut vanished = BTreeSet::new();

    loop {
        let Some(out) = calls.changes(&cursor)? else {
            return Ok(None);
        };
        changed_ids.extend(out.created);
        changed_ids.extend(out.updated);
        vanished.extend(out.destroyed);
        cursor = out.new_state;
        if !out.has_more_changes {
            break;
        }
    }

    // NOTE: a card created and destroyed within the window is in both
    // lists; the destroy wins.
    changed_ids.retain(|id| !vanished.contains(id));

    let ids: Vec<String> = changed_ids.into_iter().collect();
    let mut changed = Vec::with_capacity(ids.len());
    for chunk in ids.chunks(limit.max(1) as usize) {
        for card in calls.cards(chunk.to_vec())? {
            changed.push(jmap::to_card(card)?);
        }
    }

    Ok(Some(CardDelta {
        changed,
        vanished: vanished.into_iter().collect(),
        token: Some(cursor),
        complete: false,
    }))
}

/// Sends one stored message over a JMAP session (RFC 8621 section 7).
///
/// The identity whose address is the sender ([`identity_for`]), the
/// message with its `Bcc` header taken out ([`mail::envelope`]) uploaded
/// and imported into Drafts as a seen draft, then submitted with the
/// envelope its address headers name, the blind recipients among them,
/// and moved to Sent with `$draft` unset once the server accepts it; an
/// account with no Sent mailbox has it destroyed instead, keeping no
/// copy, as on IMAP.
///
/// What no later pass would change is refused for good ([`refused`]):
/// no identity, no Drafts mailbox, a message past `max_upload`, an
/// import or a submission the server refuses for the message or its
/// sender. A submission refused has its draft destroyed; one whose
/// answer never came keeps it, since the server may have sent and moved
/// it already.
fn send(
    calls: &mut impl JmapSubmit,
    raw: &[u8],
    max_upload: Option<u64>,
) -> Result<(), BridgeError> {
    let Composed {
        message,
        sender,
        recipients,
    } = mail::envelope(raw).map_err(|err| refused(err.message))?;

    let identities = calls.identities()?;
    let identity = identity_for(&identities, &sender)
        .ok_or_else(|| refused(format!("No identity of this account sends as {sender}")))?;

    let mailboxes = calls.mailboxes()?;
    let role = |wanted: &str| {
        mailboxes
            .iter()
            .find(|(_, _, role)| role == wanted)
            .map(|(id, _, _)| id.clone())
    };
    let drafts =
        role("drafts").ok_or_else(|| refused("This account has no Drafts mailbox to send from"))?;
    let sent = role("sent");

    if let Some(max) = max_upload.filter(|max| message.len() as u64 > *max) {
        return Err(refused(format!(
            "The message is larger than the {max} bytes the server takes"
        )));
    }

    let blob = calls.upload(message)?;
    let imported = calls.import(&blob, &drafts)?;
    if let Some(err) = imported.not_created.into_values().next() {
        let message = format!("The server refused the message: {err}");
        return Err(match err {
            JmapEmailImportItemError::Unknown => message.into(),
            _ => refused(message),
        });
    }
    let email = imported
        .created
        .into_values()
        .next()
        .and_then(|email| email.id)
        .ok_or("The server imported the message under no id")?;

    let address = |email: &str| JmapEmailAddressWithParameters {
        email: email.to_string(),
        parameters: None,
    };
    let create = JmapEmailSubmissionCreate {
        identity_id: identity.id.clone(),
        email_id: email.clone(),
        envelope: Some(JmapEnvelope {
            mail_from: address(&sender),
            rcpt_to: recipients.iter().map(|to| address(to)).collect(),
        }),
    };
    let filed = match &sent {
        Some(sent) => JmapEmailPatch::default()
            .remove_from_mailbox(&drafts)
            .add_to_mailbox(sent)
            .unset_keyword(JMAP_KEYWORD_DRAFT),
        None => JmapEmailPatch::default(),
    };
    let args = JmapEmailSubmissionSetArgs {
        create: BTreeMap::from([("s0".to_string(), create)]),
        on_success_update_email: sent
            .is_some()
            .then(|| BTreeMap::from([("#s0".to_string(), filed)])),
        on_success_destroy_email: sent.is_none().then(|| vec!["#s0".to_string()]),
    };

    let out = calls.submit(args)?;
    if out.created.contains_key("s0") {
        return Ok(());
    }

    // NOTE: never sent, so the draft imported for it is litter; one the
    // destroy misses costs a row in Drafts and nothing else.
    if let Err(err) = calls.destroy(&email) {
        log::warn!("draft {email} left behind: {err}");
    }
    let Some(err) = out.not_created.into_values().next() else {
        return Err("The server answered the submission with nothing".into());
    };
    let message = format!("The server refused to send the message: {err}");
    Err(match err {
        JmapEmailSubmissionSetItemError::RateLimit { .. }
        | JmapEmailSubmissionSetItemError::NotFound { .. }
        | JmapEmailSubmissionSetItemError::Unknown => message.into(),
        _ => refused(message),
    })
}

/// The identity a message from `sender` goes out as: the one with that
/// address, else the one whose address is `*` at its domain, which RFC
/// 8621 section 6 lets send as any address there; compared without case.
fn identity_for<'i>(identities: &'i [JmapIdentity], sender: &str) -> Option<&'i JmapIdentity> {
    let (_, domain) = sender.rsplit_once('@')?;
    let wildcard = format!("*@{domain}");

    identities
        .iter()
        .find(|identity| identity.email.eq_ignore_ascii_case(sender))
        .or_else(|| {
            identities
                .iter()
                .find(|identity| identity.email.eq_ignore_ascii_case(&wildcard))
        })
}

/// A failure no later pass changes, answered with [`REFUSED`].
fn refused(message: impl Into<String>) -> BridgeError {
    BridgeError {
        message: message.into(),
        status: Some(REFUSED),
    }
}

/// The capability URNs of the four this app reads that the session
/// serves: advertised, with a primary account to use them in. Submission
/// rides the mail account, which is the one `EmailSubmission/set` names.
fn served_capabilities(session: &JmapSession) -> Vec<&'static str> {
    [
        (JMAP_MAIL_CAPABILITY, JMAP_MAIL_CAPABILITY),
        (JMAP_SUBMISSION_CAPABILITY, JMAP_MAIL_CAPABILITY),
        (JMAP_CONTACTS_CAPABILITY, JMAP_CONTACTS_CAPABILITY),
        (JMAP_CALENDARS_CAPABILITY, JMAP_CALENDARS_CAPABILITY),
    ]
    .into_iter()
    .filter(|(urn, account)| {
        session.capabilities.contains_key(*urn)
            && !session.primary_account_id_for(account).is_empty()
    })
    .map(|(urn, _)| urn)
    .collect()
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

/// Where one message's blob is downloaded from: the session's RFC 8620
/// section 6.2 template, for the mail account, offered as a message.
fn download_url(session: &JmapSession, blob: &str) -> Result<Url, BridgeError> {
    let account = session.primary_account_id_for(JMAP_MAIL_CAPABILITY);
    session
        .resolve_download_url(&account, blob, "message.eml", "message/rfc822")
        .map_err(|err| BridgeError::from(format!("Invalid download URL: {err}")))
}

/// The server's `maxObjectsInGet` (RFC 8620 §2), the ceiling of one
/// page; the page size itself when the server states none.
fn max_objects_in_get(session: &JmapSession) -> u64 {
    session
        .core_capability()
        .max_objects_in_get
        .filter(|max| *max > 0)
        .unwrap_or(JMAP_PAGE)
}

/// One page of a listing: [`JMAP_PAGE`], capped by the server's
/// `maxObjectsInGet`.
fn page_size(session: &JmapSession) -> u64 {
    JMAP_PAGE.min(max_objects_in_get(session))
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
/// [`jmap_addressbook`] down to the account-scoped URL, writable by its
/// rights ([`writable_calendar`]).
fn jmap_calendar(session: &JmapSession, calendar: JmapCalendar) -> Calendar {
    let id = calendar.id.unwrap_or_default();

    Calendar {
        name: calendar.name.clone().unwrap_or_else(|| id.clone()),
        url: jmap_collection_path(session, JMAP_CALENDARS_CAPABILITY, &id),
        id,
        description: calendar.description,
        color: calendar.color,
        role: default_role(calendar.is_default),
        writable: writable_calendar(&calendar.my_rights),
    }
}

/// Whether the user may write events into a calendar: any write right,
/// or rights the server left unsaid (every one false, reading included),
/// which says nothing rather than read only.
fn writable_calendar(rights: &JmapCalendarRights) -> bool {
    rights.may_write_all || rights.may_write_own || !rights.may_read_items
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
/// JMAP has no per-object ETag, so the revision is a hash of the
/// CalendarEvent JSON, as a card's is: what an edit is staged against,
/// and what a write checks the server copy against first.
fn jmap_event(event: JmapCalendarEvent) -> Result<Event, String> {
    let etag = jmap::etag(&event);
    let id = event.id.unwrap_or_default();
    let mut payload = event.event;

    // NOTE: a JSCalendar object with no `@type` reads as a Group, which
    // one CalendarEvent is not. The draft has the server send it; a
    // server that did not would otherwise convert to an empty calendar.
    payload
        .entry("@type")
        .or_insert_with(|| Value::from("Event"));
    jmap::from_jscalendarbis(&mut payload);

    let payload = Value::Object(payload);
    let ical = Ical::from_jscalendar(&payload)
        .map_err(|err| format!("Invalid JSCalendar event `{id}`: {err}"))?;

    Ok(Event {
        id,
        etag,
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
        role: default_role(book.is_default),
        writable: book.my_rights.may_write || !book.my_rights.may_read,
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
#[path = "jmap_tests.rs"]
mod fake_tests;

#[cfg(test)]
mod tests {
    use io_jmap::{rfc8620::session::JmapSession, rfc8621::JMAP_MAIL_CAPABILITY};
    use std::collections::BTreeMap;
    use url::Url;

    use serde_json::{from_value, json, to_value};

    use super::{
        copy_args, destroy_args, download_url, jmap_addressbook, jmap_calendar, relocation_args,
        writable_calendar,
    };

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

    /// `isDefault` names the default; a calendar or a book shared read
    /// only takes nothing, and rights the server left unsaid say nothing.
    #[test]
    fn the_default_is_the_one_jmap_names() {
        let session = session("");
        let calendar = |value| jmap_calendar(&session, from_value(value).unwrap());
        let book = |value| jmap_addressbook(&session, from_value(value).unwrap());
        let may_write = |value| writable_calendar(&from_value(value).unwrap());

        let default = calendar(json!({
            "id": "c1", "isDefault": true,
            "myRights": { "mayReadItems": true, "mayWriteAll": true },
        }));
        let shared = calendar(json!({ "id": "c2", "myRights": { "mayReadItems": true } }));

        assert_eq!(default.role, "default");
        assert!(default.writable);
        assert!(!shared.writable);
        assert!(may_write(
            json!({ "mayReadItems": true, "mayWriteAll": true })
        ));
        assert!(!may_write(json!({ "mayReadItems": true })));
        assert!(may_write(json!({})));

        let rights = |write| json!({ "mayRead": true, "mayWrite": write, "mayShare": false, "mayDelete": false });
        let default = book(json!({ "id": "b1", "isDefault": true, "myRights": rights(true) }));
        let shared = book(json!({ "id": "b2", "myRights": rights(false) }));

        assert_eq!(default.role, "default");
        assert!(default.writable);
        assert_eq!(shared.role, "");
        assert!(!shared.writable);
    }
}
