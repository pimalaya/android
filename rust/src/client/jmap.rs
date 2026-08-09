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
        changes::*, coroutine::JmapRedirectYield, error::JmapMethodError, filter::JmapFilter,
        session::JmapSession, session_get::*,
    },
    rfc8621::{
        email::{
            JMAP_KEYWORD_ANSWERED, JMAP_KEYWORD_FLAGGED, JMAP_KEYWORD_SEEN, JmapEmail,
            JmapEmailAddress, JmapEmailBodyPart, JmapEmailBodyValue, JmapEmailProperty, get::*,
            query::*,
        },
        mailbox::{JmapMailbox, get::*},
    },
    rfc9610::{
        JMAP_CONTACTS_CAPABILITY,
        address_book::{JmapAddressBook, get::*},
        contact_card::{JmapContactCard, changes::*, get::*, query::*, set::*},
    },
};
use secrecy::SecretString;
use serde_json::Value;
use url::Url;

use crate::{
    client::{
        Client,
        convert::{coroutine_error, rejected, required},
    },
    jmap,
    types::{
        Addressbook, BridgeError, Calendar, Card, CardDelta, Credentials, Event, Message,
        MessageAttachment, MessageBody, PushChange, PushOutcome,
    },
};

/// How many changes one JMAP ContactCard/set call carries: well under
/// every server's advertised maxObjectsInSet, so no session lookup.
const JMAP_SET_CHUNK: usize = 50;

/// How much of one body value a reader is handed, in octets. Generous
/// for anything meant to be read, and a ceiling on the mailing-list
/// digest that would otherwise arrive whole over a phone connection.
const MAX_BODY_BYTES: u64 = 512 * 1024;

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
    pub fn sync_jmap_account(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        limit: u32,
    ) -> Result<Vec<Message>, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let mailboxes = self.list_jmap_mailboxes(&session, &auth, &api_url)?;

        let mut messages = Vec::new();
        for (id, path) in mailboxes {
            match self.list_jmap_messages(&session, &auth, &api_url, &id, &path, limit) {
                Ok(found) => messages.extend(found),
                Err(err) => log::warn!("skip mailbox {path}: {err}"),
            }
        }

        Ok(messages)
    }

    /// The account's mailboxes as `(id, path)` pairs, the path being the
    /// names of the mailbox and its ancestors joined by `/`.
    ///
    /// The path rather than the bare name, because the name is what the
    /// store keys a collection by and JMAP lets two mailboxes under
    /// different parents share one: an Archive inside two accounts of a
    /// unified mailbox would otherwise collapse into a single
    /// collection. IMAP hands the app hierarchical names already, so
    /// this is what keeps the two backends storing the same shape.
    fn list_jmap_mailboxes(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
    ) -> Result<Vec<(String, String)>, BridgeError> {
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
            .map(|(id, mailbox)| (id.clone(), mailbox_path(&named, mailbox)))
            .collect())
    }

    /// Reads one message whole, headers and body together.
    ///
    /// Body values are asked for by the same call that asks for the
    /// headers, capped: a reader shows what fits on a phone, and a
    /// message carrying a megabyte of quoted history should not be
    /// pulled whole to render its first screen.
    pub fn fetch_jmap_message(
        &mut self,
        session_url: &Url,
        credentials: &Credentials,
        id: &str,
    ) -> Result<MessageBody, BridgeError> {
        let auth = jmap_auth(credentials);
        let session = self.jmap_session(session_url, &auth)?;
        let api_url = session.api_url.clone();

        let opts = JmapEmailGetOptions {
            properties: Some(vec![
                JmapEmailProperty::Id,
                JmapEmailProperty::Subject,
                JmapEmailProperty::From,
                JmapEmailProperty::To,
                JmapEmailProperty::Cc,
                JmapEmailProperty::SentAt,
                JmapEmailProperty::ReceivedAt,
                JmapEmailProperty::TextBody,
                JmapEmailProperty::HtmlBody,
                JmapEmailProperty::BodyValues,
                JmapEmailProperty::Attachments,
            ]),
            fetch_text_body_values: true,
            fetch_html_body_values: true,
            max_body_value_bytes: MAX_BODY_BYTES,
        };

        let coroutine = JmapEmailGet::new(&session, &auth, vec![id.to_string()], opts)
            .map_err(|err| err.to_string())?;
        let out = self.run_jmap(&api_url, coroutine)?;

        out.emails
            .into_iter()
            .next()
            .map(message_body)
            .ok_or_else(|| BridgeError::from(format!("No message `{id}`")))
    }

    /// The newest `limit` messages of one mailbox: an `Email/query`
    /// bounded to it, batched with the `Email/get` that fetches the
    /// envelope spine of what it matched.
    fn list_jmap_messages(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
        mailbox_id: &str,
        mailbox_path: &str,
        limit: u32,
    ) -> Result<Vec<Message>, BridgeError> {
        let filter = JmapEmailFilter {
            in_mailbox: Some(mailbox_id.to_string()),
            ..Default::default()
        };
        let opts = JmapEmailQueryOptions {
            filter: Some(JmapFilter::Condition(filter)),
            sort: Some(vec![JmapEmailComparator::received_at_desc()]),
            limit: Some(limit.into()),
            properties: Some(vec![
                JmapEmailProperty::Id,
                JmapEmailProperty::Subject,
                JmapEmailProperty::From,
                JmapEmailProperty::ReceivedAt,
                JmapEmailProperty::Keywords,
                JmapEmailProperty::HasAttachment,
            ]),
            ..Default::default()
        };

        let coroutine = JmapEmailQuery::new(session, auth, opts).map_err(|err| err.to_string())?;
        let out = self.run_jmap(api_url, coroutine)?;

        Ok(out
            .emails
            .into_iter()
            .map(|email| message(mailbox_path, email))
            .collect())
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
                        self.write(target.as_str(), &bytes)?;
                        arg = None;
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRead) => {
                        arg = Some(self.read(target.as_str())?);
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
                    arg = Some(self.read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.write(api_url.as_str(), &bytes)?;
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
                    arg = Some(self.read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.write(api_url.as_str(), &bytes)?;
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

/// One JMAP Email read whole to the reader's shape.
///
/// The body comes back inside the same `Email/get` that returns the
/// headers, because JMAP hands over decoded body values: there is no
/// second download and no MIME tree to walk, which is the whole
/// difference from the IMAP path.
fn message_body(email: JmapEmail) -> MessageBody {
    let sender = email.from.as_deref().and_then(<[JmapEmailAddress]>::first);
    let values = email.body_values.unwrap_or_default();

    // HTML first, the alternative the sender laid out, and the reader
    // sandboxes it; text is what a message with no HTML part leaves.
    let html = part_value(&values, email.html_body.as_deref());
    let text = part_value(&values, email.text_body.as_deref());
    let (kind, body) = match html {
        Some(html) => ("html", html),
        None => match text {
            Some(text) => ("plain", text),
            None => ("", String::new()),
        },
    };

    MessageBody {
        subject: email.subject.unwrap_or_default(),
        from: sender.map(display_name).unwrap_or_default(),
        from_address: sender
            .map(|address| address.email.clone())
            .unwrap_or_default(),
        to: addresses(email.to.as_deref()),
        cc: addresses(email.cc.as_deref()),
        // The `Date` the sender wrote when the server kept it, else
        // when it arrived: a reader asks when a message was sent.
        date: email.sent_at.or(email.received_at).unwrap_or_default(),
        kind: kind.to_string(),
        body,
        attachments: email
            .attachments
            .unwrap_or_default()
            .into_iter()
            .map(|part| MessageAttachment {
                name: part.name.unwrap_or_default(),
                mime: part
                    .r#type
                    .unwrap_or_else(|| String::from("application/octet-stream"))
                    .to_lowercase(),
                size: part.size.unwrap_or(0),
            })
            .collect(),
    }
}

/// The text of the first body part that has one, fetched by part id.
fn part_value(
    values: &BTreeMap<String, JmapEmailBodyValue>,
    parts: Option<&[JmapEmailBodyPart]>,
) -> Option<String> {
    parts?
        .iter()
        .filter_map(|part| part.part_id.as_deref())
        .find_map(|id| values.get(id))
        .map(|value| value.value.clone())
}

/// A header's addresses as one line, the way a header reads them.
fn addresses(addresses: Option<&[JmapEmailAddress]>) -> String {
    addresses
        .unwrap_or_default()
        .iter()
        .map(|address| match address.name.as_deref().map(str::trim) {
            Some(name) if !name.is_empty() => format!("{name} <{}>", address.email),
            _ => address.email.clone(),
        })
        .collect::<Vec<_>>()
        .join(", ")
}

/// One JMAP Email to the JNI-facing shape.
///
/// Seen is a keyword rather than a flag on this backend, and it is
/// mapped here so the rest of the app keeps one notion of seen. The
/// date crosses as the RFC 3339 `receivedAt` the server sent, which the
/// store's sort key normalises alongside the RFC 5322 dates IMAP
/// returns.
fn message(mailbox: &str, email: JmapEmail) -> Message {
    let sender = email.from.as_deref().and_then(<[JmapEmailAddress]>::first);
    let from = sender.map(display_name).unwrap_or_default();
    let from_address = sender
        .map(|address| address.email.clone())
        .unwrap_or_default();

    let seen = keyword(&email, JMAP_KEYWORD_SEEN);
    let answered = keyword(&email, JMAP_KEYWORD_ANSWERED);
    let flagged = keyword(&email, JMAP_KEYWORD_FLAGGED);

    Message {
        mailbox: mailbox.to_string(),
        id: email.id.unwrap_or_default(),
        subject: email.subject.unwrap_or_default(),
        from,
        from_address,
        date: email.received_at.unwrap_or_default(),
        seen,
        answered,
        flagged,
        has_attachment: email.has_attachment.unwrap_or(false),
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
