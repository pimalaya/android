//! # Microsoft Graph calendar tests
//!
//! The [`Msgraph`] calendar verbs over fake Graph servers: the create's
//! transaction, the calendar listing, the `$batch` entry read and the
//! entry write.

use io_msgraph::v1::{
    field::MsgraphField,
    rest::users::events::{
        MsgraphPatternedRecurrence, MsgraphRecurrenceRange, MsgraphRecurrenceRangeType,
    },
};

use serde_json::{from_value, json};

use crate::calendar::msgraph::*;

/// A create sent again after a lost answer names the same transaction,
/// another create another one.
#[test]
fn a_create_names_one_transaction_per_object() {
    let ical = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u1\r\nSUMMARY:Lunch\r\n\
                DTSTART:20261012T120000Z\r\nDTEND:20261012T130000Z\r\nEND:VEVENT\r\n\
                END:VCALENDAR\r\n";
    let other = ical.replace("Lunch", "Dinner");
    let event = MsgraphEvent::create_from_ical(ical.as_bytes()).unwrap();

    let first = create_body(&event, ical);
    let again = create_body(&event, ical);
    let id = first["transactionId"].as_str().unwrap();

    assert_eq!(id.len(), 32);
    assert_eq!(again["transactionId"], first["transactionId"]);
    assert_ne!(
        create_body(&event, &other)["transactionId"],
        first["transactionId"]
    );
    assert_eq!(first["subject"], "Lunch");
}

/// The default calendar says so; a calendar shared read only takes
/// no event.
#[test]
fn the_default_calendar_is_the_one_graph_names() {
    let listed = |calendar| graph_calendar(from_value(calendar).unwrap()).unwrap();
    let default = listed(json!({
        "id": "AAA", "name": "Calendar", "isDefaultCalendar": true, "canEdit": true,
    }));
    let shared = listed(json!({
        "id": "BBB", "name": "Boss", "isDefaultCalendar": false, "canEdit": false,
    }));

    assert_eq!(default.role, "default");
    assert!(default.writable);
    assert_eq!(shared.role, "");
    assert!(!shared.writable);
}

fn event(id: &str, change_key: &str) -> MsgraphEvent {
    MsgraphEvent {
        id: id.into(),
        change_key: Some(change_key.into()),
        ..Default::default()
    }
}

#[test]
fn an_event_repeated_across_pages_lists_once() {
    let events = vec![event("A", "1"), event("B", "1"), event("B", "1")];
    let ids: Vec<String> = unique(events)
        .unwrap()
        .into_iter()
        .map(|event| event.id)
        .collect();

    assert_eq!(ids, ["A", "B"]);
    assert!(unique(vec![event("A", "1"), event("A", "2")]).is_err());
}

#[test]
fn a_write_against_a_moved_event_answers_412() {
    let current = event("e1", "v2");

    assert!(check_revision("e1", &current, Some("v2")).is_ok());
    assert!(check_revision("e1", &current, None).is_ok());
    assert_eq!(
        check_revision("e1", &current, Some("v1"))
            .unwrap_err()
            .status,
        Some(412)
    );
}

#[test]
fn an_open_series_is_read_around_today() {
    let series = MsgraphEvent {
        recurrence: MsgraphField::Set(MsgraphPatternedRecurrence {
            range: MsgraphRecurrenceRange {
                range_type: Some(MsgraphRecurrenceRangeType::NoEnd),
                start_date: Some("1604-03-12".into()),
                end_date: Some("0001-01-01".into()),
                ..Default::default()
            },
            ..Default::default()
        }),
        ..Default::default()
    };
    let today: Date = "2026-10-04".parse().unwrap();
    let pair = |from: &str, to: &str| (format!("{from}T00:00:00Z"), format!("{to}T00:00:00Z"));

    assert_eq!(
        series_windows(&series, today).unwrap(),
        [
            pair("2021-10-04", "2026-10-03"),
            pair("2026-10-03", "2031-10-02"),
            pair("2031-10-02", "2031-10-04"),
        ]
    );
}

#[test]
fn a_bounded_series_is_read_around_today() {
    let series = |from: &str, to: &str| MsgraphEvent {
        recurrence: MsgraphField::Set(MsgraphPatternedRecurrence {
            range: MsgraphRecurrenceRange {
                range_type: Some(MsgraphRecurrenceRangeType::EndDate),
                start_date: Some(from.into()),
                end_date: Some(to.into()),
                ..Default::default()
            },
            ..Default::default()
        }),
        ..Default::default()
    };
    let today: Date = "2026-10-04".parse().unwrap();
    let pair = |from: &str, to: &str| (format!("{from}T00:00:00Z"), format!("{to}T00:00:00Z"));

    assert_eq!(
        series_windows(&series("1601-01-01", "9000-12-31"), today).unwrap(),
        [
            pair("2021-10-04", "2026-10-03"),
            pair("2026-10-03", "2031-10-02"),
            pair("2031-10-02", "2031-10-05"),
        ]
    );
    assert_eq!(
        series_windows(&series("2026-01-01", "2026-03-31"), today).unwrap(),
        [pair("2026-01-01", "2026-04-01")]
    );
    assert!(
        series_windows(&series("1990-01-01", "2000-12-31"), today)
            .unwrap()
            .is_empty()
    );
}

mod batch {
    //! # Entry read tests
    //!
    //! The Graph entry read over a fake Graph: one `$batch` read answers what
    //! reading one request at a time did, for a fraction of the requests.
    //!
    //! The fake answers an entry read's three requests (a `$batch` of event
    //! reads and instance listings, an event alone, a listing page by its
    //! absolute URL), and counts each.

    use std::collections::BTreeMap;

    use io_msgraph::coroutine::{MsgraphCoroutine, MsgraphCoroutineState, MsgraphYield};
    use io_msgraph::v1::rest::batch::{
        MSGRAPH_BATCH_MAX_REQUESTS, MsgraphBatchRequest, MsgraphBatchResponse,
        MsgraphBatchResponses,
    };
    use serde_json::{Value, json};

    use crate::{
        calendar::msgraph::*,
        client::{
            Client,
            fake::{BoxedServer, FakeRequest, FakeResponse, FakeServer},
            msgraph::relative,
        },
    };

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
        /// The event deleted once a batch has read it.
        vanishing: Option<String>,
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
        /// Runs `verb` over a Graph API this calendar serves.
        fn serve<T>(&mut self, verb: impl FnOnce(Msgraph<'_, BoxedServer<'_>>) -> T) -> T {
            let mut client = Client::new(FakeServer::boxed(|request| self.answer(request)));
            verb(Msgraph {
                client: &mut client,
                token: "token",
            })
        }

        /// Answers one Graph request: a `$batch`, or a GET alone.
        fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
            assert_eq!(request.header("Authorization"), Some("Bearer token"));
            match (request.method.as_str(), request.path.as_str()) {
                ("POST", "/v1.0/$batch") => self.batch(request),
                ("GET", path) => {
                    let mut url =
                        Url::parse(&format!("https://graph.microsoft.com{path}")).unwrap();
                    url.query_pairs_mut().extend_pairs(&request.query);
                    match path.ends_with("/instances") {
                        true => self.pages += 1,
                        false => self.singles += 1,
                    }
                    let (status, body) = self.get(&url);
                    FakeResponse::json(status, &body)
                }
                other => panic!("unexpected request {other:?}: {}", request.text),
            }
        }

        /// Answers one `$batch` of GETs.
        fn batch(&mut self, request: &FakeRequest) -> FakeResponse {
            let requests: Vec<MsgraphBatchRequest> =
                serde_json::from_value(request.json::<Value>()["requests"].take()).unwrap();
            assert!(!requests.is_empty() && requests.len() <= MSGRAPH_BATCH_MAX_REQUESTS);
            self.batches += 1;

            // NOTE: in no particular order, as Graph answers them.
            let responses = requests
                .iter()
                .rev()
                .map(|request| {
                    assert_eq!(request.method, "GET");
                    let (status, body) = if self.throttled {
                        (429, json!({"error": {"code": "TooManyRequests"}}))
                    } else {
                        let url = format!("https://graph.microsoft.com/v1.0{}", request.url);
                        self.get(&Url::parse(&url).unwrap())
                    };
                    MsgraphBatchResponse {
                        id: request.id.clone(),
                        status,
                        body: Some(body),
                        ..Default::default()
                    }
                })
                .collect();
            if let Some(id) = &self.vanishing {
                self.events.remove(id);
            }
            FakeResponse::json(200, &MsgraphBatchResponses { responses })
        }

        /// Answers one GET by its address.
        fn get(&self, url: &Url) -> (u16, Value) {
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
                let master: MsgraphEvent =
                    serde_json::from_value(graph.events[*id].clone()).unwrap();
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

        let read = graph
            .serve(|mut msgraph| msgraph.read_entries(&ids, today()))
            .unwrap();
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
        let wanted = graph
            .serve(|mut msgraph| msgraph.read_entries(&ids, today()))
            .unwrap();

        graph.throttled = true;
        graph.batches = 0;
        let read = graph
            .serve(|mut msgraph| msgraph.read_entries(&ids, today()))
            .unwrap();

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

            let read = graph
                .serve(|mut msgraph| msgraph.read_entries(&ids, today()))
                .unwrap();

            assert_eq!(read.len(), 40);
            sent += graph.batches + graph.singles + graph.pages;
            one_by_one += one_by_one_requests(&graph, &ids);
        }

        // NOTE: 40 events are 2 batches, the 5 series' 15 windows a third.
        assert_eq!(sent, 9);
        assert_eq!(one_by_one, 3 * (40 + 5 * 3));
    }

    #[test]
    fn an_event_gone_since_the_listing_is_left_out() {
        let mut graph = calendar("a", 9);
        let read = graph
            .serve(|mut msgraph| msgraph.read_entries(&["a001", "missing", "a008"], today()))
            .unwrap();

        let ids: Vec<&str> = read.iter().map(|event| event.id.as_str()).collect();
        assert_eq!(ids, ["a001", "a008"]);
    }

    #[test]
    fn a_series_deleted_between_its_read_and_its_instances_is_left_out() {
        let mut graph = calendar("a", 3);
        // NOTE: gone once its event has been read.
        graph.vanishing = Some("a000".into());
        let read = graph
            .serve(|mut msgraph| msgraph.read_entries(&["a000", "a001"], today()))
            .unwrap();

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

        let read = graph
            .serve(|mut msgraph| msgraph.read_entries(&["a000"], today()))
            .unwrap();

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
        let single = requested(
            MsgraphEventInstances::new(&auth, "me", "AAMk-1=", start, end, &params).unwrap(),
        );
        assert_eq!(
            single,
            format!(
                "/v1.0{}",
                relative(&instances_url("AAMk-1=", start, end).unwrap())
            )
        );
    }
}

mod write {
    //! # Entry write tests
    //!
    //! The Graph entry write over a fake Graph: the master patched with what
    //! changed, then each occurrence held otherwise through its instance, a
    //! refused instance leaving the edit staged.
    //!
    //! The fake serves one weekly series of six Mondays at 09:00 UTC from
    //! 5 October 2026, and records every write.

    use serde_json::{Value, from_value, json};

    use crate::{
        calendar::{edit::write, msgraph::*, series::remove},
        client::{
            Client,
            fake::{BoxedServer, FakeRequest, FakeResponse, FakeServer},
        },
    };

    /// A Graph series, and what was written to it.
    struct FakeGraph {
        master: MsgraphEvent,
        exceptions: Vec<MsgraphEvent>,
        /// Every write, its method, the event it names and its body.
        sent: Vec<(&'static str, String, Value)>,
        /// The instance whose write Graph fails, as it may not next time.
        failing: Option<String>,
        /// The instance whose write Graph refuses for good.
        refusing: Option<String>,
        /// Instance listings asked for.
        listings: usize,
        /// Series read whole.
        reads: usize,
    }

    const DAYS: [&str; 6] = [
        "2026-10-05",
        "2026-10-12",
        "2026-10-19",
        "2026-10-26",
        "2026-11-02",
        "2026-11-09",
    ];

    fn graph() -> FakeGraph {
        FakeGraph {
            master: from_value(json!({
                "id": "m",
                "iCalUId": "uid-m",
                "changeKey": "ck-0",
                "type": "seriesMaster",
                "subject": "Series",
                "start": {"dateTime": "2026-10-05T09:00:00.0000000", "timeZone": "UTC"},
                "end": {"dateTime": "2026-10-05T10:00:00.0000000", "timeZone": "UTC"},
                "recurrence": {
                    "pattern": {"type": "weekly", "interval": 1, "daysOfWeek": ["monday"]},
                    "range": {"type": "numbered", "startDate": "2026-10-05", "numberOfOccurrences": 6},
                },
            }))
            .unwrap(),
            exceptions: Vec::new(),
            sent: Vec::new(),
            failing: None,
            refusing: None,
            listings: 0,
            reads: 0,
        }
    }

    /// The instance of the occurrence on `day`, starting at `hour` under
    /// `subject`: an exception unless it is the series' own.
    fn instance(day: &str, hour: &str, subject: &str) -> MsgraphEvent {
        let kind = match (hour, subject) {
            ("09", "Series") => "occurrence",
            _ => "exception",
        };
        from_value(json!({
            "id": format!("m-{day}"),
            "changeKey": format!("ck-m-{day}"),
            "type": kind,
            "seriesMasterId": "m",
            "originalStart": format!("{day}T09:00:00Z"),
            "subject": subject,
            "start": {"dateTime": format!("{day}T{hour}:00:00.0000000"), "timeZone": "UTC"},
            "end": {"dateTime": format!("{day}T{hour}:30:00.0000000"), "timeZone": "UTC"},
        }))
        .unwrap()
    }

    /// Where a series read lists the exceptions from: the series' first
    /// day, the start of its one window.
    const SERIES_FROM: &str = "2026-10-05T00:00:00Z";

    impl FakeGraph {
        /// Runs `verb` over a Graph API this series stands on.
        fn serve<T>(&mut self, verb: impl FnOnce(Msgraph<'_, BoxedServer<'_>>) -> T) -> T {
            let mut client = Client::new(FakeServer::boxed(|request| self.answer(request)));
            verb(Msgraph {
                client: &mut client,
                token: "token",
            })
        }

        /// Answers one Graph request, routed on its method and path.
        fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
            assert_eq!(request.header("Authorization"), Some("Bearer token"));
            let path = request.path.strip_prefix("/v1.0/me/events/").unwrap();
            let segments: Vec<&str> = path.split('/').collect();

            match (request.method.as_str(), segments.as_slice()) {
                ("GET", [id]) => {
                    assert_eq!(*id, "m");
                    assert_eq!(request.param("$select"), Some(MSGRAPH_EVENT_ICAL_SELECT));
                    assert_eq!(request.param("$expand"), Some(MSGRAPH_EVENT_STASH_EXPAND));
                    FakeResponse::json(200, &self.master)
                }
                ("GET", [id, "instances"]) => self.instances(id, request),
                ("PATCH", [id]) => {
                    let body = request.json();
                    match self.written("PATCH", id, body) {
                        Ok(()) => FakeResponse::json(200, &self.master),
                        Err(refused) => refused,
                    }
                }
                ("DELETE", [id]) => match self.written("DELETE", id, Value::Null) {
                    Ok(()) => FakeResponse::empty(204),
                    Err(refused) => refused,
                },
                other => panic!("unexpected request {other:?}: {}", request.text),
            }
        }

        /// Answers an instance listing: every occurrence of the series in
        /// the window, an exception standing in for its own.
        fn instances(&mut self, id: &str, request: &FakeRequest) -> FakeResponse {
            assert_eq!(id, "m");
            let start = request.param("startDateTime").unwrap();
            let end = request.param("endDateTime").unwrap();
            match start == SERIES_FROM {
                true => self.reads += 1,
                false => self.listings += 1,
            }

            let value: Vec<MsgraphEvent> = DAYS
                .iter()
                .map(|day| {
                    self.exceptions
                        .iter()
                        .find(|exception| exception.id == format!("m-{day}"))
                        .cloned()
                        .unwrap_or_else(|| instance(day, "09", "Series"))
                })
                .filter(|instance| {
                    let original = instance.original_start.as_deref().unwrap();
                    original >= start && original < end
                })
                .collect();
            FakeResponse::json(200, &json!({ "value": value }))
        }

        /// The entry as a read hands it over.
        fn ical(&self) -> String {
            let exceptions: Vec<&MsgraphEvent> = self.exceptions.iter().collect();
            self.master.to_ical_series(&exceptions)
        }

        /// Records one write; the error Graph answers when it names the
        /// failing or the refusing instance.
        fn written(
            &mut self,
            method: &'static str,
            id: &str,
            body: Value,
        ) -> Result<(), FakeResponse> {
            self.sent.push((method, id.to_string(), body));
            if self.failing.as_deref() == Some(id) {
                let error = json!({"error": {
                    "code": "InternalServerError",
                    "message": "Internal server error",
                }});
                return Err(FakeResponse::json(500, &error));
            }
            if self.refusing.as_deref() == Some(id) {
                let error = json!({"error": {
                    "code": "ErrorOccurrenceCrossingBoundary",
                    "message": "ErrorOccurrenceCrossingBoundary",
                }});
                return Err(FakeResponse::json(400, &error));
            }
            self.master.change_key = Some(format!("ck-{}", self.sent.len()));
            Ok(())
        }

        fn writes(&self) -> Vec<(&'static str, &str)> {
            self.sent
                .iter()
                .map(|(method, id, _)| (*method, id.as_str()))
                .collect()
        }
    }

    fn this(day: &str, edit: Value) -> String {
        let mut edit = edit;
        edit["scope"] = json!("this");
        edit["recurrenceId"] = json!(format!("{}T090000", day.replace('-', "")));
        edit.to_string()
    }

    fn moved(day: &str, hour: &str) -> String {
        let stamp = day.replace('-', "");
        this(
            day,
            json!({
                "start": {"time": format!("{stamp}T{hour}0000")},
                "end": {"time": format!("{stamp}T{hour}3000")},
            }),
        )
    }

    #[test]
    fn an_occurrence_moved_alone_patches_its_instance_alone() {
        let mut graph = graph();
        let staged = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();

        let revision = graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap();

        assert_eq!(graph.writes(), [("PATCH", "m-2026-10-19")]);
        let body = &graph.sent[0].2;
        assert_eq!(body["start"]["dateTime"], "2026-10-19T10:00:00");
        assert_eq!(body["end"]["dateTime"], "2026-10-19T10:30:00");
        assert!(body.get("subject").is_none(), "{body}");
        assert!(body.get("recurrence").is_none(), "{body}");
        assert_eq!(revision.as_deref(), Some("ck-1"));
        assert_eq!(
            graph.reads, 1,
            "the master unwritten, the series read stands"
        );
    }

    #[test]
    fn an_edit_of_the_series_and_one_occurrence_writes_both() {
        let mut graph = graph();
        let renamed = write(
            &graph.ical(),
            &json!({"scope": "all", "summary": "Renamed"}).to_string(),
        )
        .unwrap();
        let staged = write(&renamed, &moved("2026-10-26", "11")).unwrap();

        graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap();

        assert_eq!(graph.writes(), [("PATCH", "m"), ("PATCH", "m-2026-10-26")]);
        assert_eq!(graph.sent[0].2["subject"], "Renamed");
        assert!(graph.sent[0].2.get("start").is_none());
        assert_eq!(graph.reads, 2, "read again once the master is written");
    }

    #[test]
    fn an_occurrence_deleted_alone_deletes_its_instance() {
        let mut graph = graph();
        let staged = remove(&graph.ical(), &this("2026-10-26", json!({})))
            .unwrap()
            .unwrap();

        graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap();

        assert_eq!(graph.writes(), [("DELETE", "m-2026-10-26")]);
    }

    #[test]
    fn an_exception_edited_again_is_patched_from_what_graph_holds() {
        let mut graph = graph();
        graph.exceptions = vec![instance("2026-10-12", "10", "Moved")];
        let staged = write(
            &graph.ical(),
            &this("2026-10-12", json!({"summary": "Moved twice"})),
        )
        .unwrap();

        graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap();

        assert_eq!(graph.writes(), [("PATCH", "m-2026-10-12")]);
        assert_eq!(graph.sent[0].2["subject"], "Moved twice");
        assert!(graph.sent[0].2.get("start").is_none());
        assert_eq!(graph.listings, 0, "the exception came with the series");
    }

    #[test]
    fn an_exception_reverted_is_patched_back_to_the_series() {
        let mut graph = graph();
        graph.exceptions = vec![instance("2026-10-12", "10", "Moved")];
        let staged = graph.master.to_ical();

        graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap();

        assert_eq!(graph.writes(), [("PATCH", "m-2026-10-12")]);
        let body = &graph.sent[0].2;
        assert_eq!(body["subject"], "Series");
        assert_eq!(body["start"]["dateTime"], "2026-10-12T09:00:00");
        assert_eq!(body["end"]["dateTime"], "2026-10-12T10:00:00");
    }

    #[test]
    fn an_occurrence_graph_refuses_leaves_the_edit_staged() {
        let mut graph = graph();
        graph.failing = Some("m-2026-10-19".into());
        let first = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();
        let staged = write(&first, &moved("2026-11-02", "10")).unwrap();

        let refused = graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap_err();

        assert_eq!(refused.status, Some(412), "{}", refused.message);
        assert_eq!(
            graph.writes(),
            [("PATCH", "m-2026-10-19"), ("PATCH", "m-2026-11-02")],
            "the other occurrence still lands"
        );
    }

    #[test]
    fn a_series_moved_on_graph_writes_nothing() {
        let mut graph = graph();
        let staged = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();
        graph.master.change_key = Some("ck-elsewhere".into());

        let refused = graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap_err();

        assert_eq!(refused.status, Some(412));
        assert!(graph.sent.is_empty());
    }

    #[test]
    fn an_occurrence_graph_refuses_for_good_refuses_the_edit() {
        let mut graph = graph();
        graph.refusing = Some("m-2026-10-19".into());
        let first = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();
        let staged = write(&first, &moved("2026-11-02", "10")).unwrap();

        let refused = graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap_err();

        assert_eq!(refused.status, Some(422), "{}", refused.message);
        assert_eq!(
            graph.writes(),
            [("PATCH", "m-2026-10-19"), ("PATCH", "m-2026-11-02")],
            "the other occurrence still lands"
        );
    }

    #[test]
    fn a_refusal_beside_a_failure_keeps_the_edit_waiting() {
        let mut graph = graph();
        graph.refusing = Some("m-2026-10-19".into());
        graph.failing = Some("m-2026-11-02".into());
        let first = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();
        let staged = write(&first, &moved("2026-11-02", "10")).unwrap();

        let refused = graph
            .serve(|msgraph| GraphEntry { msgraph, id: "m" }.update(&staged, Some("ck-0")))
            .unwrap_err();

        assert_eq!(refused.status, Some(412), "{}", refused.message);
    }
}
