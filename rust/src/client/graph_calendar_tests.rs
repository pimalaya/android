//! The Graph entry read over a fake Graph: what one `$batch` read answers
//! is what reading the same events one request at a time answers, for a
//! fraction of the requests.
//!
//! The fake serves the three requests of [`GraphReads`]: a `$batch` of
//! event reads and instance listings, an event alone, and a page of a
//! listing by its absolute URL, and counts each.

use std::collections::BTreeMap;

use io_msgraph::coroutine::{MsgraphCoroutine, MsgraphCoroutineState, MsgraphYield};
use io_msgraph::v1::rest::batch::MSGRAPH_BATCH_MAX_REQUESTS;
use serde_json::{Value, json};

use super::*;
use crate::client::graph::relative;

/// A Graph calendar, and what was asked of it.
#[derive(Default)]
struct FakeGraph {
    /// Lone events and series masters, by id.
    events: BTreeMap<String, Value>,
    /// Each series' exceptions, by master id.
    exceptions: BTreeMap<String, Vec<Value>>,
    /// The page a listing is cut at, none when a window is one page.
    page: Option<usize>,
    /// Whether every request inside a batch is refused (throttled).
    throttled: bool,
    /// Batches sent.
    batches: usize,
    /// Events read alone.
    singles: usize,
    /// Listing pages read alone.
    pages: usize,
}

const TODAY: &str = "2026-10-07";

fn today() -> Date {
    TODAY.parse().unwrap()
}

fn lone(id: &str) -> Value {
    json!({
        "id": id,
        "iCalUId": format!("uid-{id}"),
        "changeKey": format!("ck-{id}"),
        "type": "singleInstance",
        "subject": format!("Event {id}"),
        "start": {"dateTime": "2026-10-08T09:00:00.0000000", "timeZone": "UTC"},
        "end": {"dateTime": "2026-10-08T10:00:00.0000000", "timeZone": "UTC"},
    })
}

/// A weekly series begun in 2020 that never ends: three windows around
/// today (2021 to 2026, 2026 to 2031, and the last days).
fn series(id: &str) -> Value {
    json!({
        "id": id,
        "iCalUId": format!("uid-{id}"),
        "changeKey": format!("ck-{id}"),
        "type": "seriesMaster",
        "subject": format!("Series {id}"),
        "start": {"dateTime": "2020-01-06T09:00:00.0000000", "timeZone": "UTC"},
        "end": {"dateTime": "2020-01-06T10:00:00.0000000", "timeZone": "UTC"},
        "recurrence": {
            "pattern": {"type": "weekly", "interval": 1, "daysOfWeek": ["monday"]},
            "range": {"type": "noEnd", "startDate": "2020-01-06"},
        },
    })
}

/// An exception of `master`, moved an hour later on `day`.
fn exception(master: &str, day: &str) -> Value {
    json!({
        "id": format!("{master}-{day}"),
        "changeKey": format!("ck-{master}-{day}"),
        "type": "exception",
        "seriesMasterId": master,
        "originalStart": format!("{day}T09:00:00Z"),
        "subject": format!("Series {master}, moved"),
        "start": {"dateTime": format!("{day}T10:00:00.0000000"), "timeZone": "UTC"},
        "end": {"dateTime": format!("{day}T11:00:00.0000000"), "timeZone": "UTC"},
    })
}

/// A calendar of `size` events, every eighth a series with two exceptions
/// in different windows.
fn calendar(name: &str, size: usize) -> FakeGraph {
    let mut graph = FakeGraph::default();
    for index in 0..size {
        let id = format!("{name}{index:03}");
        if index % 8 == 0 {
            graph.events.insert(id.clone(), series(&id));
            graph.exceptions.insert(
                id.clone(),
                vec![exception(&id, "2023-03-06"), exception(&id, "2026-10-12")],
            );
        } else {
            graph.events.insert(id.clone(), lone(&id));
        }
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
            ["v1.0", "me", "events", id] => {
                assert_eq!(query["$select"], MSGRAPH_EVENT_ICAL_SELECT);
                assert_eq!(query["$expand"], MSGRAPH_EVENT_STASH_EXPAND);
                match self.events.get(*id) {
                    Some(event) => (200, event.clone()),
                    None => (404, json!({"error": {"code": "ErrorItemNotFound"}})),
                }
            }
            ["v1.0", "me", "events", id, "instances"] => {
                if !self.events.contains_key(*id) {
                    return (404, json!({"error": {"code": "ErrorItemNotFound"}}));
                }
                let start = query["startDateTime"].as_str();
                let end = query["endDateTime"].as_str();
                let skip: usize = query.get("$skip").map_or(0, |skip| skip.parse().unwrap());
                let inside: Vec<Value> = self
                    .exceptions
                    .get(*id)
                    .into_iter()
                    .flatten()
                    .filter(|event| {
                        let original = event["originalStart"].as_str().unwrap();
                        original >= start && original < end
                    })
                    .cloned()
                    .collect();
                let size = self.page.unwrap_or(usize::MAX);
                let value: Vec<Value> = inside.iter().skip(skip).take(size).cloned().collect();
                let mut page = json!({"value": value});
                if skip + size < inside.len() {
                    let mut next = url.clone();
                    next.query_pairs_mut()
                        .append_pair("$skip", &(skip + size).to_string());
                    page["@odata.nextLink"] = json!(next.as_str());
                }
                (200, page)
            }
            other => panic!("unexpected request {other:?}"),
        }
    }
}

impl GraphReads for FakeGraph {
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
                let (status, body) = if self.throttled {
                    (429, json!({"error": {"code": "TooManyRequests"}}))
                } else {
                    self.get(&request.url)
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

    fn event(&mut self, id: &str) -> Result<MsgraphEvent, BridgeError> {
        self.singles += 1;
        match self.get(&relative(&event_url(id)?)) {
            (200, body) => Ok(serde_json::from_value(body).unwrap()),
            (status, _) => Err(BridgeError {
                message: format!("status {status}"),
                status: Some(status),
            }),
        }
    }

    fn page(&mut self, url: &str) -> Result<MsgraphEventsListResponse, BridgeError> {
        self.pages += 1;
        let url = Url::parse(url).unwrap();
        match self.get(&relative(&url)) {
            (200, body) => Ok(serde_json::from_value(body).unwrap()),
            (status, _) => Err(BridgeError {
                message: format!("status {status}"),
                status: Some(status),
            }),
        }
    }
}

/// What the one-by-one read built an entry from: the event, and its
/// exceptions window after window.
fn expected(graph: &FakeGraph, id: &str) -> Event {
    let master: MsgraphEvent = serde_json::from_value(graph.events[id].clone()).unwrap();
    let mut exceptions: Vec<MsgraphEvent> = Vec::new();
    if let Some(windows) = series_windows(&master, today()) {
        for (start, end) in windows {
            for exception in graph.exceptions.get(id).into_iter().flatten() {
                let original = exception["originalStart"].as_str().unwrap();
                if original >= start.as_str() && original < end.as_str() {
                    exceptions.push(serde_json::from_value(exception.clone()).unwrap());
                }
            }
        }
    }
    let exceptions: Vec<&MsgraphEvent> = exceptions.iter().collect();
    Event {
        id: master.id.clone(),
        etag: master.change_key.clone(),
        ical: master.to_ical_series(&exceptions),
    }
}

/// The requests the one-by-one read sent for `ids`: one an event, and one
/// a window of each series.
fn one_by_one_requests(graph: &FakeGraph, ids: &[&str]) -> usize {
    ids.iter()
        .map(|id| {
            let master: MsgraphEvent = serde_json::from_value(graph.events[*id].clone()).unwrap();
            let windows = match master.event_type {
                Some(MsgraphEventType::SeriesMaster) => {
                    series_windows(&master, today()).map_or(0, |windows| windows.len())
                }
                _ => 0,
            };
            1 + windows
        })
        .sum()
}

fn plain(events: &[Event]) -> Vec<(String, Option<String>, String)> {
    events
        .iter()
        .map(|event| (event.id.clone(), event.etag.clone(), event.ical.clone()))
        .collect()
}

#[test]
fn a_batched_read_builds_the_entries_the_one_by_one_read_built() {
    let mut graph = calendar("a", 24);
    let ids: Vec<String> = graph.events.keys().cloned().collect();
    let ids: Vec<&str> = ids.iter().map(String::as_str).collect();

    let read = read_entries(&mut graph, &ids, today()).unwrap();
    let wanted: Vec<Event> = ids.iter().map(|id| expected(&graph, id)).collect();

    assert_eq!(plain(&read), plain(&wanted));
    let series = read.iter().find(|event| event.id == "a000").unwrap();
    assert_eq!(series.ical.matches("RECURRENCE-ID").count(), 2);
    assert_eq!(graph.singles + graph.pages, 0, "everything rode a batch");
}

#[test]
fn a_batch_graph_could_not_serve_is_read_again_alone() {
    let mut graph = calendar("a", 24);
    let ids: Vec<String> = graph.events.keys().cloned().collect();
    let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
    let wanted = read_entries(&mut graph, &ids, today()).unwrap();

    graph.throttled = true;
    graph.batches = 0;
    let read = read_entries(&mut graph, &ids, today()).unwrap();

    assert_eq!(plain(&read), plain(&wanted));
    assert_eq!(graph.singles, 24);
    assert_eq!(graph.pages, one_by_one_requests(&graph, &ids) - 24);
}

#[test]
fn a_hundred_and_twenty_events_ride_a_few_batches() {
    // NOTE: the owner's tenant, rounded: three calendars, 118 events,
    // read one request an event and one more per window of a series.
    let mut sent = 0;
    let mut one_by_one = 0;
    for name in ["a", "b", "c"] {
        let mut graph = calendar(name, 40);
        let ids: Vec<String> = graph.events.keys().cloned().collect();
        let ids: Vec<&str> = ids.iter().map(String::as_str).collect();

        let read = read_entries(&mut graph, &ids, today()).unwrap();

        assert_eq!(read.len(), 40);
        sent += graph.batches + graph.singles + graph.pages;
        one_by_one += one_by_one_requests(&graph, &ids);
    }

    // 40 events are 2 batches, the 5 series' 15 windows a third.
    assert_eq!(sent, 9);
    assert_eq!(one_by_one, 3 * (40 + 5 * 3));
}

#[test]
fn an_event_gone_since_the_listing_is_left_out() {
    let mut graph = calendar("a", 9);
    let read = read_entries(&mut graph, &["a001", "missing", "a008"], today()).unwrap();

    let ids: Vec<&str> = read.iter().map(|event| event.id.as_str()).collect();
    assert_eq!(ids, ["a001", "a008"]);
}

#[test]
fn a_series_deleted_between_its_read_and_its_instances_is_left_out() {
    struct Vanishing(FakeGraph);

    impl GraphReads for Vanishing {
        fn batch(
            &mut self,
            requests: &[MsgraphBatchRequest],
        ) -> Result<Vec<MsgraphBatchResponse>, BridgeError> {
            let replies = self.0.batch(requests)?;
            // NOTE: gone once its event has been read.
            self.0.events.remove("a000");
            Ok(replies)
        }

        fn event(&mut self, id: &str) -> Result<MsgraphEvent, BridgeError> {
            self.0.event(id)
        }

        fn page(&mut self, url: &str) -> Result<MsgraphEventsListResponse, BridgeError> {
            self.0.page(url)
        }
    }

    let mut graph = Vanishing(calendar("a", 3));
    let read = read_entries(&mut graph, &["a000", "a001"], today()).unwrap();

    let ids: Vec<&str> = read.iter().map(|event| event.id.as_str()).collect();
    assert_eq!(ids, ["a001"]);
}

#[test]
fn a_window_of_several_pages_is_read_to_its_end() {
    let mut graph = calendar("a", 1);
    graph.exceptions.insert(
        "a000".into(),
        [
            "2026-10-12",
            "2026-10-19",
            "2026-10-26",
            "2026-11-02",
            "2026-11-09",
        ]
        .iter()
        .map(|day| exception("a000", day))
        .collect(),
    );
    graph.page = Some(2);

    let read = read_entries(&mut graph, &["a000"], today()).unwrap();

    assert_eq!(read[0].ical.matches("RECURRENCE-ID").count(), 5);
    assert_eq!(graph.pages, 2, "the next links followed");
    assert_eq!(plain(&read), plain(&[expected(&graph, "a000")]));
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
fn a_batched_read_asks_what_the_single_reads_ask() {
    let auth = HttpAuthBearer::new("token");

    let single = requested(
        MsgraphEventGet::new(
            &auth,
            "me",
            "AAMk-1=",
            Some(MSGRAPH_EVENT_ICAL_SELECT),
            Some(MSGRAPH_EVENT_STASH_EXPAND),
        )
        .unwrap(),
    );
    assert_eq!(
        single,
        format!("/v1.0{}", relative(&event_url("AAMk-1=").unwrap()))
    );

    let params = MsgraphEventsListParams {
        top: Some(PAGE_SIZE),
        select: Some(MSGRAPH_EVENT_ICAL_SELECT),
        ..Default::default()
    };
    let start = "2021-10-07T00:00:00Z";
    let end = "2026-10-06T00:00:00Z";
    let single =
        requested(MsgraphEventInstances::new(&auth, "me", "AAMk-1=", start, end, &params).unwrap());
    assert_eq!(
        single,
        format!(
            "/v1.0{}",
            relative(&instances_url("AAMk-1=", start, end).unwrap())
        )
    );
}
