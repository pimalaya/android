//! The Graph card read over a fake Graph: what one `$batch` read answers
//! is what reading the same contacts one request at a time answers, for a
//! twentieth of the requests.
//!
//! The fake serves the two requests of [`GraphCardReads`]: a `$batch` of
//! contact reads and a contact alone, and counts each.

use std::collections::{BTreeMap, BTreeSet};

use io_msgraph::coroutine::{MsgraphCoroutine, MsgraphCoroutineState, MsgraphYield};
use serde_json::{Value, json};

use super::*;

/// A Graph contacts folder, and what was asked of it.
#[derive(Default)]
struct FakeGraph {
    /// Contacts by id.
    contacts: BTreeMap<String, Value>,
    /// The contacts whose request inside a batch is refused, with the
    /// status it is refused with.
    refused: BTreeMap<String, u16>,
    /// Whether every request inside a batch is refused (throttled).
    throttled: bool,
    /// Batches sent.
    batches: usize,
    /// Contacts read alone.
    singles: usize,
}

fn contact(id: &str) -> Value {
    json!({
        "id": id,
        "changeKey": format!("ck-{id}"),
        "displayName": format!("Jane {id}"),
        "givenName": "Jane",
        "surname": id,
        "emailAddresses": [{"name": format!("Jane {id}"), "address": format!("{id}@example.com")}],
        "singleValueExtendedProperties": [{
            "id": "String {c8e5e5cf-3f6c-4f0a-9d4e-52f1e7b2a9d3} Name cardamum-vcard",
            "value": format!("NOTE:kept {id}"),
        }],
    })
}

/// A folder of `size` contacts.
fn folder(size: usize) -> FakeGraph {
    let mut graph = FakeGraph::default();
    for index in 0..size {
        let id = format!("AAMkc{index:03}=");
        graph.contacts.insert(id.clone(), contact(&id));
    }
    graph
}

impl FakeGraph {
    /// Answers one GET by its address, relative to the API version.
    fn get(&self, url: &str) -> (u16, Value) {
        let url = Url::parse(&format!("https://graph.microsoft.com/v1.0{url}")).unwrap();
        let segments: Vec<&str> = url.path_segments().unwrap().collect();
        let query: BTreeMap<String, String> = url.query_pairs().into_owned().collect();
        match segments.as_slice() {
            ["v1.0", "me", "contacts", id] => {
                assert_eq!(query["$expand"], MSGRAPH_CONTACT_STASH_EXPAND);
                assert_eq!(query.len(), 1, "the single read's query and no other");
                match self.contacts.get(*id) {
                    Some(contact) => (200, contact.clone()),
                    None => (404, json!({"error": {"code": "ErrorItemNotFound"}})),
                }
            }
            other => panic!("unexpected request {other:?}"),
        }
    }

    fn ids(&self) -> Vec<String> {
        self.contacts.keys().cloned().collect()
    }
}

impl GraphCardReads for FakeGraph {
    fn batch(
        &mut self,
        requests: &[MsgraphBatchRequest],
    ) -> Result<Vec<MsgraphBatchResponse>, BridgeError> {
        assert!(!requests.is_empty() && requests.len() <= MSGRAPH_BATCH_MAX_REQUESTS);
        self.batches += 1;
        // NOTE: in no particular order, as Graph answers them.
        Ok(requests
            .iter()
            .rev()
            .map(|request| {
                assert_eq!(request.method, "GET");
                let id = request
                    .url
                    .trim_start_matches("/me/contacts/")
                    .split('?')
                    .next()
                    .unwrap();
                let (status, body) = match self.refused.get(id) {
                    _ if self.throttled => (429, json!({"error": {"code": "TooManyRequests"}})),
                    Some(status) => (*status, json!({"error": {"code": "Refused"}})),
                    None => self.get(&request.url),
                };
                MsgraphBatchResponse {
                    id: request.id.clone(),
                    status,
                    body: Some(body),
                    ..Default::default()
                }
            })
            .collect())
    }

    fn contact(&mut self, id: &str) -> Result<MsgraphContact, BridgeError> {
        self.singles += 1;
        match self.get(&relative(&contact_url(id)?)) {
            (200, body) => Ok(serde_json::from_value(body).unwrap()),
            (status, _) => Err(BridgeError {
                message: format!("status {status}"),
                status: Some(status),
            }),
        }
    }
}

/// What the one-by-one read ([`Client::read_graph_card`]) built.
fn one_by_one(graph: &FakeGraph, ids: &[&str]) -> Vec<(String, String, Option<String>, String)> {
    ids.iter()
        .map(|id| {
            let contact: MsgraphContact =
                serde_json::from_value(graph.contacts[*id].clone()).unwrap();
            graph_card(contact)
        })
        .map(|card| plain(&card))
        .collect()
}

fn plain(card: &Card) -> (String, String, Option<String>, String) {
    (
        card.id.clone(),
        card.uri.clone(),
        card.etag.clone(),
        card.vcard.clone(),
    )
}

fn plains(cards: &[Card]) -> Vec<(String, String, Option<String>, String)> {
    cards.iter().map(plain).collect()
}

#[test]
fn a_batched_read_builds_the_cards_the_one_by_one_read_built() {
    let mut graph = folder(45);
    let ids = graph.ids();
    let ids: Vec<&str> = ids.iter().map(String::as_str).collect();

    let read = read_cards(&mut graph, &ids).unwrap();

    assert_eq!(plains(&read), one_by_one(&graph, &ids));
    assert!(read[0].vcard.contains("NOTE:kept"), "the stash rode along");
    assert_eq!(graph.batches, 3);
    assert_eq!(graph.singles, 0, "everything rode a batch");
}

#[test]
fn a_batch_graph_could_not_serve_is_read_again_alone() {
    let mut graph = folder(24);
    let ids = graph.ids();
    let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
    graph.throttled = true;

    let read = read_cards(&mut graph, &ids).unwrap();

    assert_eq!(plains(&read), one_by_one(&graph, &ids));
    assert_eq!(graph.batches, 2);
    assert_eq!(graph.singles, 24);
}

#[test]
fn only_the_requests_the_batch_refused_are_read_again() {
    let mut graph = folder(20);
    let ids = graph.ids();
    let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
    graph.refused.insert(ids[3].to_owned(), 429);
    graph.refused.insert(ids[11].to_owned(), 503);
    graph.refused.insert(ids[17].to_owned(), 500);

    let read = read_cards(&mut graph, &ids).unwrap();

    assert_eq!(plains(&read), one_by_one(&graph, &ids));
    assert_eq!(graph.batches, 1);
    assert_eq!(graph.singles, 3);
}

#[test]
fn a_hundred_and_twenty_changed_contacts_ride_six_batches() {
    // NOTE: a bulk change elsewhere (an import, a merge, Outlook editing
    // many at once) names every contact in one delta round.
    let mut graph = folder(120);
    let ids = graph.ids();
    let ids: Vec<&str> = ids.iter().map(String::as_str).collect();

    let read = read_cards(&mut graph, &ids).unwrap();

    assert_eq!(read.len(), 120);
    assert_eq!(graph.batches + graph.singles, 6, "where it was 120 GETs");
}

#[test]
fn a_contact_gone_since_the_delta_is_left_out() {
    let mut graph = folder(3);
    let ids = graph.ids();
    let read = read_cards(&mut graph, &[&ids[0], "AAMkgone=", &ids[2]]).unwrap();

    let read: Vec<&str> = read.iter().map(|card| card.uri.as_str()).collect();
    assert_eq!(read, [ids[0].as_str(), ids[2].as_str()]);
    assert_eq!(graph.singles, 0, "a 404 is not read again");
}

#[test]
fn a_contact_gone_before_its_lone_read_is_left_out() {
    struct Vanishing(FakeGraph);

    impl GraphCardReads for Vanishing {
        fn batch(
            &mut self,
            requests: &[MsgraphBatchRequest],
        ) -> Result<Vec<MsgraphBatchResponse>, BridgeError> {
            let replies = self.0.batch(requests)?;
            // NOTE: gone once the batch refused to serve it.
            self.0.contacts.remove("AAMkc001=");
            Ok(replies)
        }

        fn contact(&mut self, id: &str) -> Result<MsgraphContact, BridgeError> {
            self.0.contact(id)
        }
    }

    let mut graph = folder(3);
    graph.refused.insert("AAMkc001=".into(), 429);
    let mut graph = Vanishing(graph);
    let read = read_cards(&mut graph, &["AAMkc000=", "AAMkc001=", "AAMkc002="]).unwrap();

    let read: Vec<&str> = read.iter().map(|card| card.uri.as_str()).collect();
    assert_eq!(read, ["AAMkc000=", "AAMkc002="]);
}

#[test]
fn any_other_failure_of_a_lone_read_fails_the_read() {
    struct Broken(FakeGraph);

    impl GraphCardReads for Broken {
        fn batch(
            &mut self,
            requests: &[MsgraphBatchRequest],
        ) -> Result<Vec<MsgraphBatchResponse>, BridgeError> {
            self.0.batch(requests)
        }

        fn contact(&mut self, _id: &str) -> Result<MsgraphContact, BridgeError> {
            Err(BridgeError {
                message: "status 500".into(),
                status: Some(500),
            })
        }
    }

    let mut graph = folder(2);
    graph.throttled = true;
    let err = read_cards(&mut Broken(graph), &["AAMkc000=", "AAMkc001="])
        .err()
        .unwrap();
    assert_eq!(err.status, Some(500));
}

/// The path and query a coroutine's request line asks for.
fn requested<C>(mut coroutine: C) -> String
where
    C: MsgraphCoroutine<Yield = MsgraphYield>,
{
    match coroutine.resume(None) {
        MsgraphCoroutineState::Yielded(MsgraphYield::WantsWrite(bytes)) => {
            let request = String::from_utf8(bytes).unwrap();
            let line = request.lines().next().unwrap();
            line.split(' ').nth(1).unwrap().to_owned()
        }
        _ => panic!("no request"),
    }
}

#[test]
fn a_batched_read_asks_what_the_single_read_asks() {
    let auth = HttpAuthBearer::new("token");
    let ids = ["AAMk-1=", "AAMkAGI2_x-y=="];

    let mut asked = BTreeSet::new();
    let urls: Vec<Url> = ids.iter().map(|id| contact_url(id).unwrap()).collect();
    batched(
        |requests| {
            asked.extend(requests.iter().map(|request| request.url.clone()));
            Ok(Vec::new())
        },
        &urls,
    )
    .unwrap();

    for id in ids {
        let single = requested(
            MsgraphContactGet::new(&auth, "me", id, Some(MSGRAPH_CONTACT_STASH_EXPAND)).unwrap(),
        );
        let batched = relative(&contact_url(id).unwrap());
        assert_eq!(single, format!("/v1.0{batched}"));
        assert!(asked.contains(&batched));
    }
}
