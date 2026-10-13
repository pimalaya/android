//! # JMAP contacts
//!
//! The contact verbs of the [`Jmap`] backend (RFC 9610), ContactCards
//! converted to vCards by io-jmap (RFC 9555).
//!
//! The conversion is lossless, unmapped members riding the vCardProps and
//! JSPROP escape hatches. FN maps from name.full only, never derived from
//! the components as RFC 9555 allows: the app never mints a display name.
//!
//! No per-card ETag: the one surfaced is a hash of the card's JSON, which
//! only drives the store's "unchanged, skip" path. Updates carry no
//! If-Match and are last-write-wins.

use std::collections::{BTreeMap, BTreeSet};

use io_jmap::{
    rfc8620::{changes::*, session::JmapSession},
    rfc9610::{
        JMAP_CONTACTS_CAPABILITY,
        address_book::{JmapAddressBook, get::*},
        contact_card::{JmapContactCard, changes::*, get::*, query::*, set::*},
    },
};
use serde_json::Value;

use crate::{
    client::{
        Host,
        jmap::{Jmap, JmapCalls, etag, jmap_collection_path, page_size, paged},
    },
    contact::{
        Addressbook, Card, CardDelta,
        push::{PushChange, PushOutcome, rejected, required},
    },
    error::BridgeError,
    offline::default_role,
};

/// How many changes one JMAP ContactCard/set call carries: well under
/// every server's advertised maxObjectsInSet, so no session lookup.
const JMAP_SET_CHUNK: usize = 50;

/// JMAP AddressBook and ContactCard verbs.
impl<H: Host> Jmap<'_, H> {
    /// Lists the account's AddressBooks (RFC 9610 section 2.1).
    ///
    /// URLs carry the account-scoped path only, the caller adding the base
    /// it alone knows. Doubles as the onboarding connection check.
    pub fn list_addressbooks(&mut self) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();

        let coroutine =
            JmapAddressBookGet::new(&session, &auth, JmapAddressBookGetOptions::default())
                .map_err(|err| err.to_string())?;
        let out = self.run(&api_url, coroutine)?;

        Ok(out
            .address_books
            .into_iter()
            .map(|book| jmap_addressbook(&session, book))
            .collect())
    }

    /// Lists the account's ContactCards across every AddressBook, paged
    /// as [`JmapCalls::list_cards`] says.
    pub fn list_cards(&mut self) -> Result<Vec<Card>, BridgeError> {
        let mut calls = self.calls()?;
        let limit = page_size(&calls.session);

        Ok(calls.list_cards(limit)?.0)
    }

    /// Creates the vCard as a ContactCard in the AddressBook `book_id`,
    /// under the id the server assigns.
    pub fn create_card(&mut self, book_id: &str, vcard: &str) -> Result<Card, BridgeError> {
        let card = JmapContactCard::from_vcard(vcard).map_err(|err| err.to_string())?;
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();

        let create = BTreeMap::from([(
            "c0".to_string(),
            JmapContactCard {
                address_book_ids: BTreeMap::from([(book_id.to_string(), true)]),
                ..card
            },
        )]);
        let args = JmapContactCardSetArgs {
            create: Some(create),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run(&api_url, coroutine)?;

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
    pub fn read_card(&mut self, id: &str) -> Result<Card, BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();

        let opts = JmapContactCardGetOptions {
            ids: Some(vec![id.to_string()]),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run(&api_url, coroutine)?;

        let card = out
            .cards
            .into_iter()
            .next()
            .ok_or_else(|| format!("JMAP ContactCard `{id}` not found"))?;

        Ok(to_card(card)?)
    }

    /// Lists the ContactCard changes since `since_state`, as
    /// [`JmapCalls::card_delta`] says.
    pub fn changes_cards(
        &mut self,
        since_state: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let mut calls = self.calls()?;
        let limit = page_size(&calls.session);

        calls.card_delta(since_state, limit)
    }

    /// Updates the ContactCard `id` by the patch of
    /// [`JmapContactCard::update_from_vcard`], with no If-Match guard:
    /// last-write-wins.
    pub fn update_card(
        &mut self,
        id: &str,
        vcard: &str,
        base_vcard: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let patch =
            JmapContactCard::update_from_vcard(vcard, base_vcard).map_err(|err| err.to_string())?;
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();

        let update = BTreeMap::from([(id.to_string(), patch)]);
        let args = JmapContactCardSetArgs {
            update: Some(update),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run(&api_url, coroutine)?;

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
    pub fn delete_card(&mut self, id: &str) -> Result<(), BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();

        let args = JmapContactCardSetArgs {
            destroy: Some(vec![id.to_string()]),
            ..Default::default()
        };
        let coroutine =
            JmapContactCardSet::new(&session, &auth, args).map_err(|err| err.to_string())?;
        let out = self.run(&api_url, coroutine)?;

        if let Some(err) = out.not_destroyed.into_values().next() {
            return Err(format!("The server refused to delete the contact: {err}").into());
        }

        Ok(())
    }

    /// Adds and removes the ContactCard `id`'s addressBookIds in one
    /// patch, an added book to true, a removed one to null.
    pub fn update_card_books(
        &mut self,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
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
        let out = self.run(&api_url, coroutine)?;

        if let Some(err) = out.not_updated.into_values().next() {
            return Err(format!("The server refused the address book change: {err}").into());
        }

        Ok(())
    }

    /// Pushes one round of changes as ContactCard/set calls, chunks of
    /// [`JMAP_SET_CHUNK`].
    ///
    /// Each change gets its outcome: a rejected object does not fail its
    /// round, and a destroy the server no longer finds has converged.
    pub fn push_cards(
        &mut self,
        book_id: &str,
        changes: &[PushChange],
    ) -> Result<Vec<PushOutcome>, BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
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
                                address_book_ids: BTreeMap::from([(book_id.to_string(), true)]),
                                ..JmapContactCard::from_vcard(vcard)
                                    .map_err(|err| err.to_string())?
                            },
                        );
                        create_refs.insert(key, change.reference.clone());
                    }
                    "update" => {
                        let id = required(&change.id, "update", "id")?;
                        let vcard = required(&change.vcard, "update", "vcard")?;
                        let patch =
                            JmapContactCard::update_from_vcard(vcard, change.base_vcard.as_deref())
                                .map_err(|err| err.to_string())?;
                        update.insert(id.to_string(), patch);
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
            let out = self.run(&api_url, coroutine)?;

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

/// The requests a contacts round sends over the session it read once: the
/// `ContactCard` state, one `ContactCard/query` page with its cards, named
/// cards, and one `ContactCard/changes` page.
impl<H: Host> JmapCalls<'_, '_, H> {
    /// The account's `ContactCard` state, what a delta lists from.
    fn card_state(&mut self) -> Result<String, BridgeError> {
        let opts = JmapContactCardGetOptions {
            ids: Some(Vec::new()),
            ..Default::default()
        };
        let coroutine = JmapContactCardGet::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        Ok(self.jmap.run(&self.session.api_url, coroutine)?.new_state)
    }

    /// One page of the account's cards from `position`, `limit` at most.
    fn card_page(
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
        self.jmap.run(&self.session.api_url, coroutine)
    }

    /// The cards named.
    fn cards(&mut self, ids: Vec<String>) -> Result<Vec<JmapContactCard>, BridgeError> {
        let opts = JmapContactCardGetOptions {
            ids: Some(ids),
            ..Default::default()
        };
        let coroutine = JmapContactCardGet::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        Ok(self.jmap.run(&self.session.api_url, coroutine)?.cards)
    }

    /// One page of changes since `state`; [`None`] when the server can no
    /// longer compute changes from it.
    fn card_changes(&mut self, state: &str) -> Result<Option<JmapChangesOutput>, BridgeError> {
        let opts = JmapContactCardChangesOptions::default();
        let coroutine =
            JmapContactCardChanges::new(&self.session, &self.auth, state.to_string(), opts)
                .map_err(|err| err.to_string())?;
        self.jmap.run_changes(&self.session.api_url, coroutine)
    }

    /// Lists the account's cards a page of `limit` at a time ([`paged`]),
    /// each once.
    ///
    /// Paged since RFC 8620 section 5.1 lets a server refuse a get of
    /// every card past its `maxObjectsInGet`.
    fn list_cards(&mut self, limit: u64) -> Result<(Vec<Card>, bool), BridgeError> {
        let (cards, complete) = paged(|position| {
            let out = self.card_page(position, limit)?;
            Ok((out.cards, out.total, out.position))
        })?;

        let mut named = BTreeMap::new();
        for card in cards {
            let card = to_card(card)?;
            named.insert(card.id.clone(), card);
        }
        Ok((named.into_values().collect(), complete))
    }

    /// Lists ContactCard changes since `since` (RFC 8620 `/changes`), whole.
    ///
    /// Whole, so the ETag is the content revision. The initial round reads the
    /// state before every card, so what changes meanwhile is the next delta's.
    /// [`None`] when the server can no longer compute changes from the state.
    fn card_delta(
        &mut self,
        since: Option<&str>,
        limit: u64,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let Some(since) = since else {
            let state = self.card_state()?;
            let (changed, complete) = self.list_cards(limit)?;
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
            let Some(out) = self.card_changes(&cursor)? else {
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
            for card in self.cards(chunk.to_vec())? {
                changed.push(to_card(card)?);
            }
        }

        Ok(Some(CardDelta {
            changed,
            vanished: vanished.into_iter().collect(),
            token: Some(cursor),
            complete: false,
        }))
    }
}

/// An io-jmap AddressBook as an [`Addressbook`], its URL the path of
/// [`jmap_collection_path`] the caller prefixes with the account base.
pub fn jmap_addressbook(session: &JmapSession, book: JmapAddressBook) -> Addressbook {
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

/// A ContactCard as a [`Card`]: its id as id and uri, the JSON hash as
/// ETag, the addressBookIds as books.
pub fn to_card(card: JmapContactCard) -> Result<Card, String> {
    let etag = etag(&card);
    let vcard = card.to_vcard();
    let books = card
        .address_book_ids
        .iter()
        .filter(|(_, member)| **member)
        .map(|(id, _)| id.clone())
        .collect();
    let id = card
        .id
        .ok_or_else(|| "JMAP ContactCard is missing its id".to_string())?;

    Ok(Card {
        uri: id.clone(),
        id,
        etag,
        vcard,
        books,
    })
}

#[cfg(test)]
mod tests {
    use secrecy::SecretString;
    use serde_json::json;
    use url::Url;

    use crate::{
        client::{
            Auth, Client,
            fake::{FakeRequest, FakeResponse, FakeServer, jmap_session},
        },
        contact::jmap::*,
    };

    /// One JSContact card the converter reads, in the book `b1`.
    fn card(index: usize) -> Value {
        json!({
            "id": format!("k{index:04}"),
            "addressBookIds": { "b1": true },
            "@type": "Card",
            "version": "1.0",
            "uid": format!("urn:uuid:{index}"),
            "name": { "full": format!("Jane {index}") },
        })
    }

    /// A JMAP account's cards, served over the JMAP API.
    struct FakeBook {
        cards: Vec<Value>,
        /// The `/changes` responses a delta answers, in order.
        changes: Vec<Value>,
        /// Whether the server can no longer compute changes.
        expired: bool,
    }

    fn book(size: usize) -> FakeBook {
        FakeBook {
            cards: (0..size).map(card).collect(),
            changes: Vec::new(),
            expired: false,
        }
    }

    impl FakeBook {
        /// Answers one JMAP API request, its method calls in order (RFC 8620
        /// section 3.4), a `#ids` back-reference reading the query before.
        fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
            assert_eq!(request.url, "https://api.example.com/jmap/");
            assert_eq!(request.header("Authorization"), Some("Bearer token"));
            assert_eq!(
                (request.method.as_str(), request.path.as_str()),
                ("POST", "/jmap/")
            );
            let calls: Vec<(String, Value, String)> =
                serde_json::from_value(request.json::<Value>()["methodCalls"].take()).unwrap();

            let mut queried = Vec::new();
            let mut responses = Vec::new();
            for (name, args, id) in calls {
                let (name, result) = match name.as_str() {
                    "ContactCard/get" => {
                        let ids: Vec<String> = match args.get("#ids") {
                            Some(_) => queried.clone(),
                            None => serde_json::from_value(args["ids"].clone()).unwrap(),
                        };
                        let list: Vec<&Value> = self
                            .cards
                            .iter()
                            .filter(|card| ids.contains(&card["id"].as_str().unwrap().to_string()))
                            .collect();
                        let result = json!({
                            "accountId": "a1",
                            "state": "s1",
                            "list": list,
                            "notFound": [],
                        });
                        ("ContactCard/get", result)
                    }
                    "ContactCard/query" => {
                        let position = args["position"].as_u64().unwrap_or(0);
                        let limit = args["limit"].as_u64().unwrap();
                        let end = (position + limit).min(self.cards.len() as u64);
                        queried = self.cards[position as usize..end as usize]
                            .iter()
                            .map(|card| card["id"].as_str().unwrap().to_string())
                            .collect();
                        let result = json!({
                            "accountId": "a1",
                            "queryState": "q1",
                            "canCalculateChanges": false,
                            "position": position,
                            "ids": queried,
                            "total": self.cards.len(),
                        });
                        ("ContactCard/query", result)
                    }
                    "ContactCard/changes" if self.expired => {
                        ("error", json!({"type": "cannotCalculateChanges"}))
                    }
                    "ContactCard/changes" => {
                        let result = self.changes.remove(0);
                        ("ContactCard/changes", result)
                    }
                    other => panic!("unexpected method {other}"),
                };
                responses.push(json!([name, result, id]));
            }

            let body = json!({"methodResponses": responses, "sessionState": "s0"});
            FakeResponse::json(200, &body)
        }
    }

    /// A `ContactCard/changes` response from the state `s{since}`.
    fn changes(since: usize, created: &[&str], destroyed: &[&str], more: bool) -> Value {
        json!({
            "accountId": "a1",
            "oldState": format!("s{since}"),
            "newState": format!("s{}", since + 1),
            "hasMoreChanges": more,
            "created": created,
            "updated": [],
            "destroyed": destroyed,
        })
    }

    /// What each request asked of the account, in order.
    fn asked(requests: &[FakeRequest]) -> Vec<String> {
        requests
            .iter()
            .map(|request| {
                let (name, args, _): (String, Value, String) =
                    serde_json::from_value(request.json::<Value>()["methodCalls"][0].take())
                        .unwrap();
                match name.as_str() {
                    "ContactCard/get" => match args["ids"].as_array().unwrap().len() {
                        0 => "state".to_string(),
                        count => format!("cards {count}"),
                    },
                    "ContactCard/query" => {
                        format!("page {} {}", args["position"], args["limit"])
                    }
                    "ContactCard/changes" => {
                        format!("changes {}", args["sinceState"].as_str().unwrap())
                    }
                    other => panic!("unexpected method {other}"),
                }
            })
            .collect()
    }

    /// One contacts round over `book` from `since`, `limit` to a page, and
    /// what it asked of the account.
    fn round(
        mut book: FakeBook,
        since: Option<&str>,
        limit: u64,
    ) -> (Option<CardDelta>, Vec<String>) {
        let mut client = Client::new(FakeServer::new(|request| book.answer(request)));
        let mut jmap = Jmap {
            client: &mut client,
            session_url: Url::parse("https://api.example.com/jmap/session").unwrap(),
            auth: Auth::Bearer("token"),
        };
        let mut calls = JmapCalls {
            jmap: &mut jmap,
            session: jmap_session(json!({}), json!({"urn:ietf:params:jmap:contacts": "a1"})),
            auth: SecretString::from("Bearer token"),
        };

        let delta = calls.card_delta(since, limit).unwrap();
        (delta, asked(&client.host.requests))
    }

    #[test]
    fn a_first_round_reads_the_state_then_every_card_a_page_at_a_time() {
        let (delta, asked) = round(book(2500), None, 1000);
        let delta = delta.unwrap();

        assert_eq!(
            asked,
            ["state", "page 0 1000", "page 1000 1000", "page 2000 1000"],
            "the state first, so what changes while it lists is the next delta's"
        );
        assert_eq!(delta.changed.len(), 2500);
        assert_eq!(delta.token.as_deref(), Some("s1"));
        assert!(delta.complete);
    }

    #[test]
    fn a_delta_reads_its_changed_cards_a_chunk_at_a_time() {
        let mut book = book(5);
        book.changes = vec![
            changes(1, &["k0000", "k0001", "k0002"], &[], true),
            changes(2, &["k0003", "k0004"], &["k0002"], false),
        ];

        let (delta, asked) = round(book, Some("s1"), 2);
        let delta = delta.unwrap();

        assert_eq!(
            asked,
            ["changes s1", "changes s2", "cards 2", "cards 2"],
            "four changed cards, the destroyed one left out, two a get"
        );
        assert_eq!(delta.changed.len(), 4);
        assert_eq!(delta.vanished, ["k0002"]);
        assert_eq!(delta.token.as_deref(), Some("s3"));
        assert!(!delta.complete);
    }

    #[test]
    fn a_state_the_server_cannot_compute_from_asks_for_a_first_round() {
        let mut book = book(1);
        book.expired = true;

        assert!(round(book, Some("s1"), 500).0.is_none());
    }
}
