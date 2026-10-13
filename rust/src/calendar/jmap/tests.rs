//! # JMAP calendar tests
//!
//! The [`Jmap`] calendar verbs over fake JMAP accounts: the listing's
//! paging, the JSCalendar conversion both ways, and the writes with their
//! state guards, refusals and scheduling.

use serde_json::{from_value, json};

use crate::{calendar::jmap::*, client::fake::jmap_session, contact::jmap::jmap_addressbook};

/// `isDefault` names the default; a calendar or a book shared read
/// only takes nothing, and rights the server left unsaid say nothing.
#[test]
fn the_default_is_the_one_jmap_names() {
    let session = jmap_session(json!({}), json!({ "urn:ietf:params:jmap:mail": "u42" }));
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

mod fake {
    use io_jmap::coroutine::{JmapCoroutine, JmapCoroutineState, JmapYield};
    use secrecy::SecretString;
    use serde_json::{Map, Value, from_value, json};

    use crate::{
        calendar::{edit::write, jmap::*, series::remove},
        client::{
            Auth, Client,
            fake::{BoxedServer, FakeRequest, FakeResponse, FakeServer, jmap_session},
        },
        error::REFUSED,
    };

    /// One JSCalendar event the converter reads.
    fn event(index: usize) -> Value {
        json!({
            "id": format!("e{index:04}"),
            "@type": "Event",
            "uid": format!("u{index}"),
            "title": format!("Event {index}"),
            "start": "2026-10-01T09:00:00",
            "duration": "PT1H",
        })
    }

    /// Runs `verb` over the calls of a JMAP account `answer` serves.
    fn serve<T>(
        answer: impl FnMut(&FakeRequest) -> FakeResponse,
        verb: impl FnOnce(&mut JmapCalls<'_, '_, BoxedServer<'_>>) -> T,
    ) -> T {
        let mut client = Client::new(FakeServer::boxed(answer));
        let mut jmap = Jmap {
            client: &mut client,
            session_url: Url::parse("https://api.example.com/jmap/session").unwrap(),
            auth: Auth::Bearer("token"),
        };
        let mut calls = JmapCalls {
            jmap: &mut jmap,
            session: jmap_session(json!({}), json!({"urn:ietf:params:jmap:calendars": "a1"})),
            auth: SecretString::from("Bearer token"),
        };
        verb(&mut calls)
    }

    /// Answers one JMAP API request, its method calls in order (RFC 8620
    /// section 3.4), each answered by `call` from its name and arguments.
    fn methods(
        request: &FakeRequest,
        mut call: impl FnMut(&str, Value) -> (&'static str, Value),
    ) -> FakeResponse {
        assert_eq!(request.url, "https://api.example.com/jmap/");
        assert_eq!(request.header("Authorization"), Some("Bearer token"));
        assert_eq!(
            (request.method.as_str(), request.path.as_str()),
            ("POST", "/jmap/")
        );
        let calls: Vec<(String, Value, String)> =
            from_value(request.json::<Value>()["methodCalls"].take()).unwrap();

        let responses: Vec<Value> = calls
            .into_iter()
            .map(|(name, args, id)| {
                let (name, result) = call(&name, args);
                json!([name, result, id])
            })
            .collect();
        let body = json!({"methodResponses": responses, "sessionState": "s0"});
        FakeResponse::json(200, &body)
    }

    /// A JMAP calendar of `size` events, and what was asked of it.
    struct FakeCalendar {
        events: Vec<Value>,
        /// Whether the query states its total.
        total: bool,
        /// The position from which the server answers empty pages.
        stops_at: Option<u64>,
        /// Whether the server answers the first page whatever the position.
        ignores_position: bool,
        /// Every page asked for: its position and limit.
        asked: Vec<(u64, u64)>,
    }

    fn calendar(size: usize) -> FakeCalendar {
        FakeCalendar {
            events: (0..size).map(event).collect(),
            total: true,
            stops_at: None,
            ignores_position: false,
            asked: Vec::new(),
        }
    }

    impl FakeCalendar {
        /// Runs `verb` over the calls of the account this calendar is in.
        fn serve<T>(
            &mut self,
            verb: impl FnOnce(&mut JmapCalls<'_, '_, BoxedServer<'_>>) -> T,
        ) -> T {
            serve(|request| self.answer(request), verb)
        }

        /// Answers a `CalendarEvent/query` page and the `CalendarEvent/get`
        /// of its ids by back-reference.
        fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
            let mut queried: Vec<Value> = Vec::new();
            methods(request, |name, args| match name {
                "CalendarEvent/query" => {
                    assert_eq!(args["filter"]["inCalendar"], "c1");
                    let position = args["position"].as_u64().unwrap();
                    let limit = args["limit"].as_u64().unwrap();
                    self.asked.push((position, limit));

                    let start = if self.ignores_position { 0 } else { position };
                    let end = match self.stops_at {
                        Some(stop) if start >= stop => start,
                        _ => (start + limit).min(self.events.len() as u64),
                    };
                    queried = self.events[start as usize..end as usize].to_vec();
                    let ids: Vec<&Value> = queried.iter().map(|event| &event["id"]).collect();
                    let mut result = json!({
                        "accountId": "a1",
                        "queryState": "q1",
                        "canCalculateChanges": false,
                        "position": start,
                        "ids": ids,
                    });
                    if self.total {
                        result["total"] = json!(self.events.len());
                    }
                    ("CalendarEvent/query", result)
                }
                "CalendarEvent/get" => {
                    assert!(args.get("#ids").is_some(), "the query's ids");
                    let result = json!({
                        "accountId": "a1",
                        "state": "s1",
                        "list": queried,
                        "notFound": [],
                    });
                    ("CalendarEvent/get", result)
                }
                other => panic!("unexpected method {other}"),
            })
        }
    }

    #[test]
    fn a_calendar_larger_than_a_page_is_read_page_by_page() {
        let mut calendar = calendar(1200);

        let (events, complete) = calendar
            .serve(|calls| calls.list_events("c1", 500))
            .unwrap();

        assert!(complete);
        assert_eq!(events.len(), 1200);
        assert_eq!(calendar.asked, [(0, 500), (500, 500), (1000, 500)]);
        assert!(events[0].ical.contains("UID:u0"), "{}", events[0].ical);
    }

    #[test]
    fn a_listing_cut_short_of_its_total_is_never_complete() {
        let mut calendar = calendar(1200);
        calendar.stops_at = Some(500);

        let (events, complete) = calendar
            .serve(|calls| calls.list_events("c1", 500))
            .unwrap();

        assert!(!complete, "the 700 left out would read as deleted");
        assert_eq!(events.len(), 500);
    }

    #[test]
    fn a_server_stating_no_total_is_read_until_an_empty_page() {
        let mut calendar = calendar(700);
        calendar.total = false;

        let (events, complete) = calendar
            .serve(|calls| calls.list_events("c1", 500))
            .unwrap();

        assert!(complete);
        assert_eq!(events.len(), 700);
        assert_eq!(calendar.asked, [(0, 500), (500, 500), (700, 500)]);
    }

    #[test]
    fn a_server_ignoring_the_position_ends_the_listing_incomplete() {
        let mut calendar = calendar(700);
        calendar.total = false;
        calendar.ignores_position = true;

        let (events, complete) = calendar
            .serve(|calls| calls.list_events("c1", 500))
            .unwrap();

        assert!(!complete);
        assert_eq!(events.len(), 500, "the repeated page is read once");
        assert_eq!(calendar.asked.len(), 2);
    }

    /// A weekly series on a JMAP calendar as a JSCalendar 2.0 server holds
    /// it, with an attendee, and a member the conversion carries only through
    /// its escape hatch.
    fn standup() -> Value {
        json!({
            "id": "ev1",
            "calendarIds": { "c1": true },
            "@type": "Event",
            "uid": "standup",
            "title": "Standup",
            "start": "2026-10-01T09:00:00",
            "timeZone": "Europe/Paris",
            "duration": "PT30M",
            "recurrenceRule": { "@type": "RecurrenceRule", "frequency": "weekly" },
            "organizerCalendarAddress": "mailto:jane@example.com",
            "participants": {
                "p1": {
                    "@type": "Participant",
                    "name": "Ada",
                    "calendarAddress": "mailto:ada@example.com",
                    "roles": { "attendee": true },
                },
            },
            "useDefaultAlerts": true,
        })
    }

    /// A JMAP account's events, applying the writes it takes, and what was
    /// asked of it.
    struct FakeEvents {
        events: BTreeMap<String, Value>,
        state: u32,
        /// Whether another client writes between each read and the next write.
        races: bool,
        /// The refusal each write of these ids or creation keys answers.
        refusals: BTreeMap<String, Value>,
        /// Every `CalendarEvent/set` asked for, as sent.
        sets: Vec<Value>,
    }

    fn events(events: &[Value]) -> FakeEvents {
        FakeEvents {
            events: events
                .iter()
                .map(|event| (event["id"].as_str().unwrap().to_string(), event.clone()))
                .collect(),
            state: 1,
            races: false,
            refusals: BTreeMap::new(),
            sets: Vec::new(),
        }
    }

    impl FakeEvents {
        /// The iCalendar the listing gives the event `id`, which is what an
        /// edit starts from.
        fn listed(&self, id: &str) -> Event {
            jmap_event(from_value(self.events[id].clone()).unwrap()).unwrap()
        }

        /// The one patch the last write sent.
        fn patch(&self) -> &serde_json::Map<String, Value> {
            let update = self.sets.last().unwrap()["update"].as_object().unwrap();
            assert_eq!(update.len(), 1, "{update:?}");
            update.values().next().unwrap().as_object().unwrap()
        }
    }

    impl FakeEvents {
        /// Runs `verb` over the calls of this account.
        fn serve<T>(
            &mut self,
            verb: impl FnOnce(&mut JmapCalls<'_, '_, BoxedServer<'_>>) -> T,
        ) -> T {
            serve(|request| self.answer(request), verb)
        }

        /// Answers a `CalendarEvent/get` of named events and a
        /// `CalendarEvent/set`, applying it.
        fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
            methods(request, |name, args| match name {
                "CalendarEvent/get" => {
                    let ids: Vec<String> = from_value(args["ids"].clone()).unwrap();
                    let (list, not_found): (Vec<&String>, Vec<&String>) =
                        ids.iter().partition(|id| self.events.contains_key(*id));
                    let list: Vec<&Value> = list.into_iter().map(|id| &self.events[id]).collect();
                    let result = json!({
                        "accountId": "a1",
                        "state": format!("s{}", self.state),
                        "list": list,
                        "notFound": not_found,
                    });
                    if self.races {
                        self.state += 1;
                    }
                    ("CalendarEvent/get", result)
                }
                "CalendarEvent/set" => self.set(args),
                other => panic!("unexpected method {other}"),
            })
        }

        /// Applies one `CalendarEvent/set`, unless its `ifInState` is not
        /// the state the events stand at.
        fn set(&mut self, sent: Value) -> (&'static str, Value) {
            self.sets.push(sent.clone());
            if sent["ifInState"]
                .as_str()
                .is_some_and(|state| state != format!("s{}", self.state))
            {
                return ("error", json!({"type": "stateMismatch"}));
            }

            let old_state = format!("s{}", self.state);
            let mut created = Map::new();
            let mut updated = Map::new();
            let mut destroyed = Vec::new();
            let mut not_created = Map::new();
            let mut not_updated = Map::new();
            let mut not_destroyed = Map::new();
            let not_found = json!({"type": "notFound"});

            for (key, event) in sent["create"].as_object().into_iter().flatten() {
                if let Some(err) = self.refusals.get(key) {
                    not_created.insert(key.clone(), err.clone());
                    continue;
                }
                let id = format!("ev{}", self.events.len() + 1);
                let mut event = event.clone();
                event["id"] = Value::from(id.clone());
                self.events.insert(id.clone(), event);
                created.insert(key.clone(), json!({ "id": id }));
            }
            for (id, patch) in sent["update"].as_object().into_iter().flatten() {
                match (self.refusals.get(id), self.events.get_mut(id)) {
                    (Some(err), _) => {
                        not_updated.insert(id.clone(), err.clone());
                    }
                    (None, None) => {
                        not_updated.insert(id.clone(), not_found.clone());
                    }
                    (None, Some(event)) => {
                        for (pointer, value) in patch.as_object().unwrap() {
                            let path: Vec<String> = pointer
                                .split('/')
                                .map(|part| part.replace("~1", "/").replace("~0", "~"))
                                .collect();
                            let (last, parents) = path.split_last().unwrap();
                            let mut target = &mut *event;
                            for parent in parents {
                                target = &mut target[parent.as_str()];
                            }
                            let target = target.as_object_mut().unwrap();
                            match value {
                                Value::Null => target.remove(last),
                                value => target.insert(last.clone(), value.clone()),
                            };
                        }
                        updated.insert(id.clone(), Value::Null);
                    }
                }
            }
            for id in sent["destroy"].as_array().into_iter().flatten() {
                let id = id.as_str().unwrap().to_string();
                match (self.refusals.get(&id), self.events.remove(&id)) {
                    (Some(err), _) => {
                        not_destroyed.insert(id, err.clone());
                    }
                    (None, None) => {
                        not_destroyed.insert(id, not_found.clone());
                    }
                    (None, Some(_)) => destroyed.push(id),
                }
            }

            self.state += 1;
            let result = json!({
                "accountId": "a1",
                "oldState": old_state,
                "newState": format!("s{}", self.state),
                "created": created,
                "updated": updated,
                "destroyed": destroyed,
                "notCreated": not_created,
                "notUpdated": not_updated,
                "notDestroyed": not_destroyed,
            });
            ("CalendarEvent/set", result)
        }
    }

    /// A new entry as the entry page starts it.
    const NEW_ENTRY: &str = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Pimalaya//Android//EN\r\n\
        BEGIN:VEVENT\r\nUID:lunch\r\nDTSTAMP:20261009T080000Z\r\n\
        DTSTART;TZID=Europe/Paris:20261012T120000\r\nDURATION:PT1H\r\nSUMMARY:Lunch\r\n\
        END:VEVENT\r\nEND:VCALENDAR\r\n";

    #[test]
    fn a_new_entry_lands_in_its_calendar_under_the_id_the_server_gives() {
        let mut account = events(&[]);

        let created = account
            .serve(|calls| calls.create_event("c1", NEW_ENTRY, &[]))
            .unwrap();

        assert_eq!(created.id, "ev1");
        let stored = &account.events["ev1"];
        assert_eq!(stored["calendarIds"], json!({ "c1": true }));
        assert_eq!(stored["uid"], "lunch");
        assert_eq!(stored["title"], "Lunch");
        assert_eq!(stored["start"], "2026-10-12T12:00:00");
        assert_eq!(stored["timeZone"], "Europe/Paris");
        assert!(stored.get("isOrigin").is_none(), "{stored}");
        assert_eq!(
            created.etag,
            account.listed("ev1").etag,
            "the revision is the one the next listing reads"
        );
    }

    #[test]
    fn an_edit_patches_only_what_it_changed() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");

        let etag = account
            .serve(|calls| calls.update_event("ev1", &edited, listed.etag.as_deref(), &[]))
            .unwrap();

        let patch = account.patch();
        assert_eq!(
            patch.keys().collect::<Vec<_>>(),
            ["title"],
            "the escape hatch's member is never sent: {patch:?}"
        );
        assert_eq!(account.sets[0]["ifInState"], "s1");
        let stored = &account.events["ev1"];
        assert_eq!(stored["title"], "Daily");
        assert_eq!(stored["useDefaultAlerts"], true);
        assert_eq!(etag, account.listed("ev1").etag);
        assert_ne!(etag, listed.etag);
    }

    #[test]
    fn an_edit_changing_nothing_writes_nothing() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");

        let etag = account
            .serve(|calls| calls.update_event("ev1", &listed.ical, listed.etag.as_deref(), &[]))
            .unwrap();

        assert!(account.sets.is_empty());
        assert_eq!(etag, listed.etag);
    }

    #[test]
    fn one_occurrence_moved_sends_that_occurrence_alone() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        let edit = json!({
            "scope": "this",
            "recurrenceId": "20261008T090000",
            "start": {"time": "20261008T100000"},
            "summary": "Standup, later",
        });
        let moved = write(&listed.ical, &edit.to_string()).unwrap();

        account
            .serve(|calls| calls.update_event("ev1", &moved, listed.etag.as_deref(), &[]))
            .unwrap();

        let patch = account.patch();
        assert_eq!(
            patch.keys().collect::<Vec<_>>(),
            ["recurrenceOverrides"],
            "no override existed, so the member is set whole: {patch:?}"
        );
        let over = &account.events["ev1"]["recurrenceOverrides"]["2026-10-08T09:00:00"];
        assert_eq!(over["start"], "2026-10-08T10:00:00", "{over}");
        assert_eq!(over["title"], "Standup, later", "{over}");
        assert_eq!(account.events["ev1"]["title"], "Standup");

        // NOTE: moved again, the override already there is replaced alone.
        let listed = account.listed("ev1");
        let edit = json!({
            "scope": "this",
            "recurrenceId": "20261008T090000",
            "start": {"time": "20261008T110000"},
        });
        let again = write(&listed.ical, &edit.to_string()).unwrap();

        account
            .serve(|calls| calls.update_event("ev1", &again, listed.etag.as_deref(), &[]))
            .unwrap();

        let patch = account.patch();
        assert_eq!(
            patch.keys().collect::<Vec<_>>(),
            ["recurrenceOverrides/2026-10-08T09:00:00"],
            "{patch:?}"
        );
        let over = &account.events["ev1"]["recurrenceOverrides"]["2026-10-08T09:00:00"];
        assert_eq!(over["start"], "2026-10-08T11:00:00", "{over}");
    }

    #[test]
    fn one_occurrence_deleted_is_excluded_in_the_event() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        let edit = json!({"scope": "this", "recurrenceId": "20261015T090000"});
        let left = remove(&listed.ical, &edit.to_string()).unwrap().unwrap();

        account
            .serve(|calls| calls.update_event("ev1", &left, listed.etag.as_deref(), &[]))
            .unwrap();

        assert_eq!(
            account.events["ev1"]["recurrenceOverrides"],
            json!({ "2026-10-15T09:00:00": { "excluded": true } })
        );
        assert_eq!(account.events["ev1"]["title"], "Standup");
    }

    #[test]
    fn an_override_removed_is_nulled_alone() {
        let mut series = standup();
        series["recurrenceOverrides"] = json!({
            "2026-10-08T09:00:00": { "title": "Moved" },
            "2026-10-15T09:00:00": { "excluded": true },
        });
        let mut account = events(&[series]);
        let listed = account.listed("ev1");
        // NOTE: the occurrence edited back to the series' own values is what
        // a removed override is; here it is taken out of the object by hand.
        let start = listed
            .ical
            .match_indices("BEGIN:VEVENT\r\n")
            .map(|(at, _)| at)
            .find(|at| {
                let component = listed.ical[*at..].split("END:VEVENT").next().unwrap();
                component.contains("SUMMARY:Moved")
            })
            .expect("the override of the 8th");
        let end =
            start + listed.ical[start..].find("END:VEVENT\r\n").unwrap() + "END:VEVENT\r\n".len();
        let reverted = format!("{}{}", &listed.ical[..start], &listed.ical[end..]);

        account
            .serve(|calls| calls.update_event("ev1", &reverted, listed.etag.as_deref(), &[]))
            .unwrap();

        assert_eq!(
            account.patch().clone(),
            json!({ "recurrenceOverrides/2026-10-08T09:00:00": null })
                .as_object()
                .unwrap()
                .clone()
        );
        assert_eq!(
            account.events["ev1"]["recurrenceOverrides"],
            json!({ "2026-10-15T09:00:00": { "excluded": true } })
        );
    }

    #[test]
    fn an_edit_of_an_event_that_moved_is_never_written() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        account.events.get_mut("ev1").unwrap()["title"] = Value::from("Renamed elsewhere");
        let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");

        let err = account
            .serve(|calls| calls.update_event("ev1", &edited, listed.etag.as_deref(), &[]))
            .unwrap_err();

        assert_eq!(err.status, Some(412));
        assert!(account.sets.is_empty());
        assert_eq!(account.events["ev1"]["title"], "Renamed elsewhere");
    }

    #[test]
    fn a_write_racing_another_client_lands_nothing_and_waits() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        account.races = true;
        let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");

        let err = account
            .serve(|calls| calls.update_event("ev1", &edited, listed.etag.as_deref(), &[]))
            .unwrap_err();

        assert_eq!(err.status, Some(412), "{}", err.message);
        assert_eq!(account.events["ev1"]["title"], "Standup");
    }

    #[test]
    fn a_set_refused_for_its_state_waits() {
        // NOTE: the answer a server gives an `ifInState` it no longer
        // stands at, through the real coroutine.
        let session = jmap_session(
            json!({ "urn:ietf:params:jmap:calendars": {} }),
            json!({ "urn:ietf:params:jmap:calendars": "a1" }),
        );
        let args = JmapCalendarEventSetArgs {
            if_in_state: Some("s1".into()),
            destroy: Some(vec!["ev1".into()]),
            ..Default::default()
        };
        let mut coroutine =
            JmapCalendarEventSet::new(&session, &SecretString::from("Bearer jane"), args).unwrap();
        let body =
            br#"{"methodResponses":[["error",{"type":"stateMismatch"},"c0"]],"sessionState":"s2"}"#;
        let mut reply = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n",
            body.len()
        )
        .into_bytes();
        reply.extend_from_slice(body);

        let mut arg: Option<&[u8]> = None;
        let err = loop {
            match coroutine.resume(arg.take()) {
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(_)) => {}
                JmapCoroutineState::Yielded(JmapYield::WantsRead) => arg = Some(&reply),
                JmapCoroutineState::Complete(Err(err)) => break err,
                JmapCoroutineState::Complete(Ok(_)) => panic!("a refused set completed"),
            }
        };

        assert_eq!(set_failure(&err).status, Some(412));
    }

    #[test]
    fn a_property_the_server_refuses_is_refused_for_good() {
        let mut account = events(&[standup()]);
        account.refusals.insert(
            "ev1".into(),
            json!({ "type": "invalidProperties", "properties": ["recurrenceOverrides"] }),
        );
        let listed = account.listed("ev1");
        let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");

        let err = account
            .serve(|calls| calls.update_event("ev1", &edited, listed.etag.as_deref(), &[]))
            .unwrap_err();

        assert_eq!(err.status, Some(REFUSED));
        assert!(
            err.message.contains("recurrenceOverrides"),
            "{}",
            err.message
        );
    }

    #[test]
    fn a_rate_limited_create_waits() {
        let mut account = events(&[]);
        account
            .refusals
            .insert("e0".into(), json!({ "type": "rateLimit" }));

        let err = account
            .serve(|calls| calls.create_event("c1", NEW_ENTRY, &[]))
            .err()
            .expect("a refused create");

        assert_eq!(err.status, Some(412));
        assert!(account.events.is_empty());
    }

    #[test]
    fn a_delete_goes_under_the_state_its_check_read() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");

        account
            .serve(|calls| calls.destroy_event("ev1", listed.etag.as_deref(), &[]))
            .unwrap();

        assert!(account.events.is_empty());
        assert_eq!(account.sets[0]["ifInState"], "s1");
        assert_eq!(account.sets[0]["destroy"], json!(["ev1"]));
    }

    #[test]
    fn a_delete_of_an_event_already_gone_converges() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        account.events.clear();

        account
            .serve(|calls| calls.destroy_event("ev1", listed.etag.as_deref(), &[]))
            .unwrap();
        account
            .serve(|calls| calls.destroy_event("ev1", None, &[]))
            .unwrap();

        assert!(
            account.sets.is_empty(),
            "a delete of an event gone sends nothing"
        );
    }

    #[test]
    fn a_delete_of_an_event_that_moved_is_never_sent() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        account.events.get_mut("ev1").unwrap()["title"] = Value::from("Renamed elsewhere");

        let err = account
            .serve(|calls| calls.destroy_event("ev1", listed.etag.as_deref(), &[]))
            .unwrap_err();

        assert_eq!(err.status, Some(412));
        assert!(account.sets.is_empty());
        assert!(account.events.contains_key("ev1"));
    }

    #[test]
    fn an_object_of_two_events_is_refused_for_good() {
        let two = NEW_ENTRY.replace(
            "END:VEVENT\r\n",
            "END:VEVENT\r\nBEGIN:VEVENT\r\nUID:other\r\nDTSTAMP:20261009T080000Z\r\n\
             DTSTART:20261013T120000\r\nEND:VEVENT\r\n",
        );
        let mut account = events(&[]);

        let err = account
            .serve(|calls| calls.create_event("c1", &two, &[]))
            .err()
            .expect("a refused create");

        assert_eq!(err.status, Some(REFUSED));
        assert!(account.sets.is_empty());
    }

    #[test]
    fn a_series_reads_as_a_series_with_its_people() {
        let account = events(&[standup()]);

        let listed = account.listed("ev1");

        assert!(
            listed.ical.contains("RRULE:FREQ=WEEKLY\r\n"),
            "{}",
            listed.ical
        );
        assert!(
            listed.ical.contains("mailto:ada@example.com"),
            "{}",
            listed.ical
        );
        assert!(listed.ical.contains("ORGANIZER"), "{}", listed.ical);
        assert!(
            !listed.ical.contains("JSPTR=recurrenceRule"),
            "{}",
            listed.ical
        );
    }

    #[test]
    fn a_new_series_is_written_as_jscalendar_2_0() {
        let mut account = events(&[]);
        let series = NEW_ENTRY
            .replace("VERSION:2.0\r\n", "VERSION:2.0\r\nMETHOD:REQUEST\r\n")
            .replace(
                "SUMMARY:Lunch\r\n",
                "SUMMARY:Lunch\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\n\
                 ORGANIZER:mailto:jane@example.com\r\nATTENDEE;CN=Ada:mailto:ada@example.com\r\n",
            );

        account
            .serve(|calls| calls.create_event("c1", &series, &[]))
            .unwrap();

        let stored = &account.events["ev1"];
        // NOTE: Stalwart 0.16 refuses a `version` as an invalid property;
        // the draft has the server set it.
        assert!(stored.get("version").is_none(), "{stored}");
        assert!(stored.get("method").is_none(), "{stored}");
        assert_eq!(stored["recurrenceRule"]["frequency"], "weekly", "{stored}");
        assert_eq!(stored["recurrenceRule"]["count"], 4, "{stored}");
        assert!(stored.get("recurrenceRules").is_none(), "{stored}");
        assert_eq!(
            stored["organizerCalendarAddress"],
            "mailto:jane@example.com"
        );
        assert!(stored.get("replyTo").is_none(), "{stored}");
        let ada = stored["participants"]
            .as_object()
            .unwrap()
            .values()
            .find(|participant| participant["name"] == "Ada")
            .expect("the attendee");
        assert_eq!(ada["calendarAddress"], "mailto:ada@example.com", "{stored}");
        assert!(ada.get("sendTo").is_none(), "{stored}");
    }

    /// JSCalendar 2.0 holds one rule, and a second would ride a hatch
    /// Stalwart 0.16 drops: the series is refused rather than cut.
    #[test]
    fn a_series_of_two_rules_is_refused_for_good() {
        let rules = "SUMMARY:Lunch\r\nRRULE:FREQ=WEEKLY\r\nRRULE:FREQ=MONTHLY\r\n";
        let mut account = events(&[]);
        let series = NEW_ENTRY.replace("SUMMARY:Lunch\r\n", rules);

        let err = account
            .serve(|calls| calls.create_event("c1", &series, &[]))
            .err()
            .expect("a refused create");

        assert_eq!(err.status, Some(REFUSED));
        assert!(
            err.message.contains("one recurrence rule"),
            "{}",
            err.message
        );
        assert!(account.sets.is_empty());

        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        let edited = listed.ical.replace(
            "RRULE:FREQ=WEEKLY\r\n",
            "RRULE:FREQ=WEEKLY\r\nRRULE:FREQ=MONTHLY\r\n",
        );
        assert_ne!(edited, listed.ical);

        let err = account
            .serve(|calls| calls.update_event("ev1", &edited, listed.etag.as_deref(), &[]))
            .unwrap_err();

        assert_eq!(err.status, Some(REFUSED));
        assert!(account.sets.is_empty());
    }

    /// Jane's address, which organizes [`standup`].
    fn jane() -> Vec<String> {
        vec!["jane@example.com".to_string()]
    }

    /// Whether the last `CalendarEvent/set` asked the server to schedule.
    fn scheduled(account: &FakeEvents) -> bool {
        account.sets.last().unwrap()["sendSchedulingMessages"] == true
    }

    #[test]
    fn every_write_of_a_meeting_the_user_organizes_is_scheduled() {
        let mut account = events(&[]);
        let series = NEW_ENTRY.replace(
            "SUMMARY:Lunch\r\n",
            "SUMMARY:Lunch\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nATTENDEE;CN=Ada:mailto:ada@example.com\r\n",
        );
        account
            .serve(|calls| calls.create_event("c1", &series, &jane()))
            .unwrap();
        assert!(
            scheduled(&account),
            "a meeting with no organizer is the user's"
        );

        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");
        account
            .serve(|calls| calls.update_event("ev1", &edited, listed.etag.as_deref(), &jane()))
            .unwrap();
        assert!(scheduled(&account));

        let listed = account.listed("ev1");
        let edit = json!({
            "scope": "this",
            "recurrenceId": "20261008T090000",
            "start": {"time": "20261008T100000"},
        });
        let moved = write(&listed.ical, &edit.to_string()).unwrap();
        account
            .serve(|calls| calls.update_event("ev1", &moved, listed.etag.as_deref(), &jane()))
            .unwrap();
        assert_eq!(
            account.patch().keys().collect::<Vec<_>>(),
            ["recurrenceOverrides"]
        );
        assert!(scheduled(&account));

        let listed = account.listed("ev1");
        let edit = json!({"scope": "this", "recurrenceId": "20261015T090000"});
        let left = remove(&listed.ical, &edit.to_string()).unwrap().unwrap();
        account
            .serve(|calls| calls.update_event("ev1", &left, listed.etag.as_deref(), &jane()))
            .unwrap();
        assert!(scheduled(&account));

        let listed = account.listed("ev1");
        account
            .serve(|calls| calls.destroy_event("ev1", listed.etag.as_deref(), &jane()))
            .unwrap();
        assert_eq!(account.sets.last().unwrap()["destroy"], json!(["ev1"]));
        assert!(scheduled(&account));
    }

    /// The attendees taken off a meeting are cancelled: the copy the edit
    /// replaces had them.
    #[test]
    fn a_meeting_whose_attendees_are_all_taken_off_is_scheduled() {
        let mut account = events(&[standup()]);
        let listed = account.listed("ev1");
        let alone: String = listed
            .ical
            .split_inclusive("\r\n")
            .filter(|line| !line.starts_with("ATTENDEE"))
            .collect();
        assert_ne!(alone, listed.ical);

        account
            .serve(|calls| calls.update_event("ev1", &alone, listed.etag.as_deref(), &jane()))
            .unwrap();

        assert!(scheduled(&account));
    }

    #[test]
    fn a_write_of_an_event_without_attendees_is_not_scheduled() {
        let mut account = events(&[]);
        let lunch = NEW_ENTRY.replace(
            "SUMMARY:Lunch\r\n",
            "SUMMARY:Lunch\r\nORGANIZER:mailto:jane@example.com\r\n",
        );
        account
            .serve(|calls| calls.create_event("c1", &lunch, &jane()))
            .unwrap();
        assert!(!scheduled(&account));

        let listed = account.listed("ev1");
        let edited = listed.ical.replace("SUMMARY:Lunch", "SUMMARY:Brunch");
        account
            .serve(|calls| calls.update_event("ev1", &edited, listed.etag.as_deref(), &jane()))
            .unwrap();
        assert!(!scheduled(&account));

        account
            .serve(|calls| calls.destroy_event("ev1", None, &jane()))
            .unwrap();
        assert_eq!(account.sets.last().unwrap()["destroy"], json!(["ev1"]));
        assert!(!scheduled(&account));
    }

    /// Answering someone else's invitation would send the organizer an iTIP
    /// `REPLY` under `sendSchedulingMessages`, a reply flow the app does not
    /// drive: nothing new is sent.
    #[test]
    fn an_answer_to_someone_elses_invitation_is_not_scheduled() {
        let mut invitation = standup();
        invitation["organizerCalendarAddress"] = json!("mailto:boss@example.com");
        invitation["participants"]["p2"] = json!({
            "@type": "Participant",
            "calendarAddress": "mailto:jane@example.com",
            "roles": { "attendee": true },
            "participationStatus": "needs-action",
        });
        let mut account = events(&[invitation]);
        let listed = account.listed("ev1");
        let answered = listed
            .ical
            .replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED");
        assert_ne!(answered, listed.ical);

        account
            .serve(|calls| calls.update_event("ev1", &answered, listed.etag.as_deref(), &jane()))
            .unwrap();

        assert_eq!(
            account.events["ev1"]["participants"]["p2"]["participationStatus"],
            "accepted"
        );
        assert!(!scheduled(&account));

        let listed = account.listed("ev1");
        account
            .serve(|calls| calls.destroy_event("ev1", listed.etag.as_deref(), &jane()))
            .unwrap();
        assert!(!scheduled(&account));
    }
}
