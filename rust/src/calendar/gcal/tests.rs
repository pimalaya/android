//! # Google calendar tests
//!
//! The [`Gcal`] backend over `FakeCalendar`, a model of one Google
//! calendar: listings and sync tokens, series and their instances, and
//! each write with who it has Google notify.

use serde_json::{Value, from_value, json, to_value};

use crate::{
    calendar::{edit::write, gcal::*, series::remove},
    client::fake::{BoxedServer, FakeRequest, FakeResponse, FakeServer},
};

fn listed(entry: Value) -> Option<Calendar> {
    gcal_calendar(from_value(entry).unwrap())
}

/// The primary calendar is the default; a subscribed one is not, and
/// one the user only reads takes no event.
#[test]
fn the_primary_calendar_is_the_default() {
    let primary = listed(json!({
        "id": "jane@example.com", "summary": "Jane",
        "primary": true, "accessRole": "owner",
    }))
    .unwrap();
    let holidays = listed(json!({
        "id": "en.usa#holiday@group.v.calendar.google.com",
        "summary": "Holidays", "accessRole": "reader",
    }))
    .unwrap();
    let shared = listed(json!({
        "id": "team@example.com", "summary": "Team", "accessRole": "writer",
    }))
    .unwrap();

    assert_eq!(primary.role, "default");
    assert!(primary.writable);
    assert_eq!(holidays.role, "");
    assert!(!holidays.writable);
    assert_eq!(shared.role, "");
    assert!(shared.writable);
}

fn event(id: &str, etag: &str, master: Option<&str>) -> GcalEvent {
    GcalEvent {
        id: Some(id.into()),
        etag: Some(etag.into()),
        recurring_event_id: master.map(Into::into),
        ..Default::default()
    }
}

#[test]
fn an_instance_edit_moves_its_entry() {
    let master = event("m", "\"1\"", None);
    let before = revision(&master, &[event("m_1", "\"1\"", Some("m"))]);
    let after = revision(&master, &[event("m_1", "\"2\"", Some("m"))]);

    assert_ne!(before, after);
    assert_eq!(revision(&master, &[]).as_deref(), Some("\"1\""));
}

#[test]
fn a_listing_groups_instances_under_their_master() {
    let mut deleted = event("gone", "\"1\"", None);
    deleted.status = Some(GcalEventStatus::Cancelled);
    let listed = vec![
        event("m_1", "\"1\"", Some("m")),
        event("m", "\"1\"", None),
        event("lone", "\"1\"", None),
        deleted,
    ];

    let grouped: Vec<(String, usize)> = entries(listed)
        .into_iter()
        .map(|(master, instances)| (master.id.unwrap(), instances.len()))
        .collect();

    assert_eq!(grouped, [("lone".into(), 0), ("m".into(), 1)]);
}

#[test]
fn a_listing_names_each_entry_with_its_body() {
    let instance = event("m_1", "\"2\"", Some("m"));
    let listed = vec![
        instance.clone(),
        event("m", "\"1\"", None),
        event("lone", "\"1\"", None),
    ];

    let read: Vec<Event> = entries(listed)
        .into_iter()
        .map(|(master, instances)| entry(master, &instances))
        .collect();

    assert_eq!(read[0].id, "lone");
    assert_eq!(read[0].etag.as_deref(), Some("\"1\""));
    assert_eq!(read[1].id, "m");
    assert_eq!(
        read[1].etag,
        revision(&event("m", "\"1\"", None), &[instance])
    );
    assert!(
        read.iter()
            .all(|entry| entry.ical.contains("BEGIN:VCALENDAR"))
    );
}

#[test]
fn a_subscribed_calendar_id_stays_one_segment() {
    assert_eq!(
        calendar_path("fr.french#holiday@group.v.calendar.google.com"),
        "fr.french%23holiday@group.v.calendar.google.com"
    );
}

/// A Google calendar, and what was asked of it.
#[derive(Default)]
struct FakeCalendar {
    /// Every event as a full listing answers it: lone events, series
    /// masters and their instances, cancelled ones included.
    events: Vec<GcalEvent>,
    /// The ids a listing from the sync token answers.
    changed: Vec<String>,
    /// Whether the sync token expired.
    expired: bool,
    /// Every listing sent: its iCalUID and its sync token.
    listings: Vec<(Option<String>, Option<String>)>,
    /// Events read alone.
    gets: usize,
    /// The instances a window listing answers, every occurrence of the
    /// series as it would be were none changed.
    occurrences: Vec<GcalEvent>,
    /// Every write, in order.
    sent: Vec<Sent>,
    /// The event whose write Google refuses, with the status and the
    /// message it answers.
    failing: Option<(String, u16, &'static str)>,
}

/// One write asked of a [`FakeCalendar`]: its method, the event it
/// names, its body, its ETag and who it has Google notify.
struct Sent {
    method: &'static str,
    id: String,
    body: Value,
    if_match: Option<String>,
    send_updates: Option<GcalSendUpdates>,
}

fn series_event(id: &str, etag: &str) -> GcalEvent {
    GcalEvent {
        recurrence: vec!["RRULE:FREQ=WEEKLY".into()],
        ical_uid: Some(format!("uid-{id}")),
        ..event(id, etag, None)
    }
}

fn instance(master: &str, day: &str, etag: &str) -> GcalEvent {
    GcalEvent {
        ical_uid: Some(format!("uid-{master}")),
        ..event(&format!("{master}_{day}"), etag, Some(master))
    }
}

fn cancelled(event: GcalEvent) -> GcalEvent {
    GcalEvent {
        status: Some(GcalEventStatus::Cancelled),
        ..event
    }
}

/// A lone event, a series with two changed instances, another lone
/// event.
fn calendar() -> FakeCalendar {
    FakeCalendar {
        events: vec![
            event("lone", "\"1\"", None),
            series_event("m", "\"1\""),
            instance("m", "20261012", "\"1\""),
            instance("m", "20261019", "\"1\""),
            event("other", "\"1\"", None),
        ],
        ..Default::default()
    }
}

impl FakeCalendar {
    /// Runs `verb` over a Calendar API this calendar serves.
    fn serve<T>(&mut self, verb: impl FnOnce(Gcal<'_, BoxedServer<'_>>) -> T) -> T {
        let mut client = Client::new(FakeServer::boxed(|request| self.answer(request)));
        verb(Gcal {
            client: &mut client,
            token: "token",
        })
    }

    /// Runs `write` on the entry `m` of this calendar.
    fn entry<T>(&mut self, write: impl FnOnce(&mut GcalEntry<'_, BoxedServer<'_>>) -> T) -> T {
        self.serve(|gcal| {
            write(&mut GcalEntry {
                gcal,
                calendar: "jane@example.com",
                id: "m",
            })
        })
    }

    /// Answers one Calendar API request, routed on its method and path.
    fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
        assert_eq!(request.header("Authorization"), Some("Bearer token"));
        let path = request
            .path
            .strip_prefix("/calendar/v3/calendars/")
            .unwrap();
        let segments: Vec<&str> = path.split('/').collect();
        let (calendar, segments) = segments.split_first().unwrap();
        assert_eq!(*calendar, "jane@example.com");

        match (request.method.as_str(), segments) {
            ("GET", ["events"]) => self.listing(request),
            ("GET", ["events", id]) => self.get(id),
            ("GET", ["events", id, "instances"]) => self.window(id, request),
            ("POST", ["events"]) => self.created("INSERT", request),
            ("POST", ["events", "import"]) => self.created("IMPORT", request),
            ("PUT", ["events", id]) => self.replaced(id, request),
            ("DELETE", ["events", id]) => self.deleted(id, request),
            other => panic!("unexpected request {other:?}: {}", request.text),
        }
    }

    /// Answers one page of `events.list`, the whole listing in one.
    fn listing(&mut self, request: &FakeRequest) -> FakeResponse {
        assert_eq!(
            request.param("showDeleted"),
            Some("true"),
            "the same parameters every time"
        );
        assert_eq!(request.param("maxResults"), Some("2500"));
        assert!(request.param("timeMin").is_none() && request.param("updatedMin").is_none());
        assert!(request.param("pageToken").is_none());
        let uid = request.param("iCalUID");
        let token = request.param("syncToken");
        self.listings
            .push((uid.map(str::to_string), token.map(str::to_string)));

        let items = match (uid, token) {
            (Some(_), Some(_)) => panic!("a sync token beside an iCalUID"),
            (None, Some(token)) => {
                assert_eq!(token, "token-1");
                if self.expired {
                    let gone =
                        json!({"error": {"code": 410, "message": "Sync token is no longer valid"}});
                    return FakeResponse::json(410, &gone);
                }
                self.events
                    .iter()
                    .filter(|event| self.changed.contains(event.id.as_ref().unwrap()))
                    .cloned()
                    .collect()
            }
            (Some(uid), None) => self
                .events
                .iter()
                .filter(|event| event.ical_uid.as_deref() == Some(uid))
                .cloned()
                .collect(),
            (None, None) => self.events.clone(),
        };

        let page = GcalEvents {
            items,
            next_sync_token: uid.is_none().then(|| "token-2".into()),
            ..Default::default()
        };
        FakeResponse::json(200, &page)
    }

    /// Answers `events.get`.
    fn get(&mut self, id: &str) -> FakeResponse {
        self.gets += 1;
        match self
            .events
            .iter()
            .find(|event| event.id.as_deref() == Some(id))
        {
            Some(event) => FakeResponse::json(200, event),
            None => FakeResponse::json(
                404,
                &json!({"error": {"code": 404, "message": "Not Found"}}),
            ),
        }
    }

    /// Answers `events.instances` over its window: every occurrence of the
    /// series, changed where a changed instance stands in for it.
    fn window(&self, id: &str, request: &FakeRequest) -> FakeResponse {
        assert_eq!(id, "m");
        let start = request.param("timeMin").unwrap();
        let end = request.param("timeMax").unwrap();

        let items = self
            .occurrences
            .iter()
            .map(|plain| {
                self.events
                    .iter()
                    .find(|event| event.id == plain.id)
                    .unwrap_or(plain)
                    .clone()
            })
            .filter(|instance| {
                let original = instance.original_start_time.as_ref().unwrap();
                let original = original.date_time.as_deref().unwrap();
                original >= start && original < end
            })
            .collect();
        FakeResponse::json(
            200,
            &GcalEvents {
                items,
                ..Default::default()
            },
        )
    }
}

/// What a full listing makes of the calendar, by entry id.
fn whole(calendar: &mut FakeCalendar) -> BTreeMap<String, (Option<String>, String)> {
    calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", None))
        .unwrap()
        .unwrap()
        .bodies
        .into_iter()
        .map(|event| (event.id, (event.etag, event.ical)))
        .collect()
}

#[test]
fn a_first_round_lists_in_full_and_keeps_the_token() {
    let mut calendar = calendar();

    let delta = calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", None))
        .unwrap()
        .unwrap();

    assert_eq!(calendar.listings, [(None, None)]);
    assert!(delta.complete);
    assert_eq!(delta.token.as_deref(), Some("token-2"));
    let ids: Vec<&str> = delta
        .changed
        .iter()
        .map(|event| event.id.as_str())
        .collect();
    assert_eq!(ids, ["lone", "m", "other"]);
    assert_eq!(delta.bodies.len(), 3);
}

#[test]
fn a_quiet_calendar_costs_one_listing() {
    let mut calendar = calendar();

    let delta = calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", Some("token-1")))
        .unwrap()
        .unwrap();

    assert_eq!(calendar.listings, [(None, Some("token-1".into()))]);
    assert!(!delta.complete);
    assert!(delta.changed.is_empty() && delta.bodies.is_empty() && delta.vanished.is_empty());
    assert_eq!(delta.token.as_deref(), Some("token-2"));
}

#[test]
fn a_changed_lone_event_comes_whole_with_the_changes() {
    let mut calendar = calendar();
    calendar.events[0] = event("lone", "\"2\"", None);
    calendar.changed = vec!["lone".into()];
    let expected = whole(&mut calendar)["lone"].clone();
    calendar.listings.clear();

    let delta = calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", Some("token-1")))
        .unwrap()
        .unwrap();

    assert_eq!(calendar.listings.len(), 1);
    assert_eq!(calendar.gets, 0);
    assert_eq!(delta.bodies.len(), 1);
    assert_eq!(delta.bodies[0].id, "lone");
    assert_eq!(
        (delta.bodies[0].etag.clone(), delta.bodies[0].ical.clone()),
        expected
    );
}

#[test]
fn an_instance_edit_reads_its_series_whole_by_its_uid() {
    let mut calendar = calendar();
    calendar.events[2] = instance("m", "20261012", "\"2\"");
    calendar.changed = vec!["m_20261012".into()];
    let expected = whole(&mut calendar)["m"].clone();
    calendar.listings.clear();

    let delta = calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", Some("token-1")))
        .unwrap()
        .unwrap();

    assert_eq!(
        calendar.listings,
        [(None, Some("token-1".into())), (Some("uid-m".into()), None)]
    );
    assert_eq!(calendar.gets, 0);
    let ids: Vec<&str> = delta
        .changed
        .iter()
        .map(|event| event.id.as_str())
        .collect();
    assert_eq!(ids, ["m"]);
    assert_eq!(
        (delta.bodies[0].etag.clone(), delta.bodies[0].ical.clone()),
        expected
    );
    assert_eq!(delta.changed[0].etag, expected.0);
}

#[test]
fn a_series_changed_in_several_parts_is_read_once() {
    let mut calendar = calendar();
    calendar.changed = vec!["m".into(), "m_20261012".into(), "m_20261019".into()];

    let delta = calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", Some("token-1")))
        .unwrap()
        .unwrap();

    assert_eq!(calendar.listings.len(), 2);
    assert_eq!(delta.bodies.len(), 1);
}

#[test]
fn a_cancelled_event_or_series_is_gone_unread() {
    let mut calendar = calendar();
    calendar.events[0] = cancelled(event("lone", "\"2\"", None));
    calendar.events[1] = cancelled(series_event("m", "\"2\""));
    calendar.events[2] = cancelled(instance("m", "20261012", "\"2\""));
    calendar.changed = vec!["lone".into(), "m".into(), "m_20261012".into()];

    let delta = calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", Some("token-1")))
        .unwrap()
        .unwrap();

    assert_eq!(calendar.listings.len(), 1);
    assert!(delta.bodies.is_empty());
    assert_eq!(delta.vanished, ["lone", "m"]);
}

#[test]
fn a_cancelled_occurrence_moves_its_series() {
    let mut calendar = calendar();
    calendar.events[3] = cancelled(instance("m", "20261019", "\"2\""));
    calendar.changed = vec!["m_20261019".into()];
    let expected = whole(&mut calendar)["m"].clone();

    let delta = calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", Some("token-1")))
        .unwrap()
        .unwrap();

    assert!(delta.vanished.is_empty());
    assert_eq!(delta.bodies[0].id, "m");
    assert_eq!(
        (delta.bodies[0].etag.clone(), delta.bodies[0].ical.clone()),
        expected
    );
}

#[test]
fn a_series_whose_listing_lacks_its_master_reads_it_alone() {
    let mut calendar = calendar();
    // NOTE: the master carries another iCalUID than its instances,
    // so the listing by theirs answers the instances alone.
    calendar.events[1].ical_uid = Some("uid-elsewhere".into());
    calendar.changed = vec!["m_20261012".into()];

    let delta = calendar
        .serve(|mut gcal| gcal.sync_events("jane@example.com", Some("token-1")))
        .unwrap()
        .unwrap();

    assert_eq!(calendar.gets, 1);
    assert_eq!(delta.bodies[0].id, "m");
}

#[test]
fn an_expired_token_starts_over() {
    let mut calendar = calendar();
    calendar.expired = true;

    assert!(
        calendar
            .serve(|mut gcal| gcal.sync_events("jane@example.com", Some("token-1")))
            .unwrap()
            .is_none()
    );
    assert_eq!(calendar.listings.len(), 1);
}

impl FakeCalendar {
    /// Records one write; the refusal it answers when it names the
    /// failing event, with a `Retry-After` longer than a call waits, so
    /// the client hands it over at once.
    fn written(
        &mut self,
        method: &'static str,
        id: &str,
        request: &FakeRequest,
    ) -> Result<(), FakeResponse> {
        self.sent.push(Sent {
            method,
            id: id.to_string(),
            body: match request.body.is_empty() {
                true => Value::Null,
                false => request.json(),
            },
            if_match: request.header("If-Match").map(str::to_string),
            send_updates: request
                .param("sendUpdates")
                .map(|value| from_value(json!(value)).unwrap()),
        });
        match &self.failing {
            Some((failing, status, message)) if failing == id => {
                let body = json!({"error": {"code": status, "message": message}});
                Err(FakeResponse {
                    headers: vec![("Retry-After".into(), "3600".into())],
                    ..FakeResponse::json(*status, &body)
                })
            }
            _ => Ok(()),
        }
    }

    /// Answers `events.insert` or `events.import`.
    fn created(&mut self, method: &'static str, request: &FakeRequest) -> FakeResponse {
        if let Err(refused) = self.written(method, "new", request) {
            return refused;
        }
        let event: GcalEvent = request.json();
        let created = GcalEvent {
            id: Some("new".into()),
            etag: Some("\"1\"".into()),
            ..event
        };
        FakeResponse::json(200, &created)
    }

    /// Answers `events.update`.
    fn replaced(&mut self, id: &str, request: &FakeRequest) -> FakeResponse {
        if let Err(refused) = self.written("PUT", id, request) {
            return refused;
        }
        let event: GcalEvent = request.json();
        let updated = GcalEvent {
            etag: Some(format!("\"{}\"", self.sent.len() + 1)),
            ..event
        };
        FakeResponse::json(200, &updated)
    }

    /// Answers `events.delete`.
    fn deleted(&mut self, id: &str, request: &FakeRequest) -> FakeResponse {
        match self.written("DELETE", id, request) {
            Ok(()) => FakeResponse::empty(204),
            Err(refused) => refused,
        }
    }

    fn writes(&self) -> Vec<(&'static str, &str)> {
        self.sent
            .iter()
            .map(|sent| (sent.method, sent.id.as_str()))
            .collect()
    }

    /// Who each write had Google notify, in order.
    fn notified(&self) -> Vec<Option<GcalSendUpdates>> {
        self.sent.iter().map(|sent| sent.send_updates).collect()
    }

    /// The entry as a read hands it over.
    fn ical(&mut self) -> String {
        let mut read = self
            .serve(|mut gcal| gcal.read_events("jane@example.com", &["m"]))
            .unwrap();
        read.remove(0).ical
    }
}

const MONDAYS: [&str; 6] = [
    "2026-10-05",
    "2026-10-12",
    "2026-10-19",
    "2026-10-26",
    "2026-11-02",
    "2026-11-09",
];

/// The occurrence of the series `m` on `day` at `hour`, under
/// `summary`.
fn occurrence(day: &str, hour: &str, summary: &str) -> GcalEvent {
    let stamp = day.replace('-', "");
    from_value(json!({
        "id": format!("m_{stamp}T090000Z"),
        "etag": format!("\"{stamp}\""),
        "iCalUID": "uid-m",
        "recurringEventId": "m",
        "originalStartTime": {"dateTime": format!("{day}T09:00:00Z"), "timeZone": "UTC"},
        "summary": summary,
        "start": {"dateTime": format!("{day}T{hour}:00:00Z"), "timeZone": "UTC"},
        "end": {"dateTime": format!("{day}T{hour}:30:00Z"), "timeZone": "UTC"},
    }))
    .unwrap()
}

/// A weekly series of six Mondays at 09:00 UTC, from 5 October 2026,
/// with `exceptions` among its instances.
fn weekly(exceptions: Vec<GcalEvent>) -> FakeCalendar {
    let master = from_value(json!({
        "id": "m",
        "etag": "\"1\"",
        "iCalUID": "uid-m",
        "summary": "Series",
        "start": {"dateTime": "2026-10-05T09:00:00Z", "timeZone": "UTC"},
        "end": {"dateTime": "2026-10-05T09:30:00Z", "timeZone": "UTC"},
        "recurrence": ["RRULE:FREQ=WEEKLY;COUNT=6"],
    }))
    .unwrap();

    let mut events = vec![master];
    events.extend(exceptions);
    FakeCalendar {
        events,
        occurrences: MONDAYS
            .iter()
            .map(|day| occurrence(day, "09", "Series"))
            .collect(),
        ..Default::default()
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
fn an_occurrence_moved_alone_replaces_its_instance_alone() {
    let mut calendar = weekly(Vec::new());
    let staged = write(&calendar.ical(), &moved("2026-10-19", "10")).unwrap();

    let revision = calendar
        .entry(|entry| entry.update(&staged, Some("\"1\""), &[]))
        .unwrap();

    assert_eq!(calendar.writes(), [("PUT", "m_20261019T090000Z")]);
    let Sent { body, if_match, .. } = &calendar.sent[0];
    assert_eq!(if_match.as_deref(), Some("\"20261019\""));
    assert_eq!(body["start"]["dateTime"], "2026-10-19T10:00:00Z");
    assert_eq!(body["recurringEventId"], "m");
    assert_eq!(
        body["originalStartTime"]["dateTime"],
        "2026-10-19T09:00:00Z"
    );
    assert_eq!(body["summary"], "Series");
    assert!(body.get("recurrence").is_none(), "{body}");
    assert!(revision.is_some());
}

/// Google takes the `EXDATE` in the master's recurrence, and the
/// instance a listing still shows is deleted besides: a removal
/// Google already made answers 410, which counts as done.
#[test]
fn an_occurrence_deleted_alone_deletes_its_instance() {
    let mut calendar = weekly(Vec::new());
    let staged = remove(&calendar.ical(), &this("2026-10-26", json!({})))
        .unwrap()
        .unwrap();

    calendar
        .entry(|entry| entry.update(&staged, Some("\"1\""), &[]))
        .unwrap();

    assert_eq!(
        calendar.writes(),
        [("PUT", "m"), ("DELETE", "m_20261026T090000Z")]
    );
    assert!(
        calendar.sent[0].body["recurrence"][1]
            .as_str()
            .unwrap()
            .starts_with("EXDATE")
    );
}

#[test]
fn an_exception_reverted_is_replaced_back_with_the_series() {
    let moved = occurrence("2026-10-12", "10", "Moved");
    let mut calendar = weekly(vec![moved.clone()]);
    let base = calendar.ical();
    let revision = revision(&calendar.events[0], &[moved]);
    let staged = calendar.events[0].to_ical();
    assert_ne!(staged, base);

    calendar
        .entry(|entry| entry.update(&staged, revision.as_deref(), &[]))
        .unwrap();

    assert_eq!(calendar.writes(), [("PUT", "m_20261012T090000Z")]);
    let body = &calendar.sent[0].body;
    assert_eq!(body["summary"], "Series");
    assert_eq!(body["start"]["dateTime"], "2026-10-12T09:00:00Z");
}

#[test]
fn a_cancelled_instance_is_not_deleted_again() {
    let gone = cancelled(occurrence("2026-10-12", "09", "Series"));
    let mut calendar = weekly(vec![gone.clone()]);
    let revision = revision(&calendar.events[0], &[gone]);
    let staged = calendar.ical();

    calendar
        .entry(|entry| entry.update(&staged, revision.as_deref(), &[]))
        .unwrap();

    assert!(calendar.sent.is_empty(), "{:?}", calendar.writes());
}

/// Two occurrences moved, Google answering the first's write with
/// `status` and `message`: what the write answers, the second
/// occurrence landed either way.
fn refused_with(status: u16, message: &'static str) -> BridgeError {
    let mut calendar = weekly(Vec::new());
    calendar.failing = Some(("m_20261019T090000Z".into(), status, message));
    let first = write(&calendar.ical(), &moved("2026-10-19", "10")).unwrap();
    let staged = write(&first, &moved("2026-11-02", "11")).unwrap();

    let refused = calendar
        .entry(|entry| entry.update(&staged, Some("\"1\""), &[]))
        .unwrap_err();

    assert_eq!(
        calendar.writes(),
        [("PUT", "m_20261019T090000Z"), ("PUT", "m_20261102T090000Z")],
        "the other occurrence still lands"
    );
    refused
}

#[test]
fn an_occurrence_google_fails_leaves_the_edit_staged() {
    assert_eq!(refused_with(503, "Backend Error").status, Some(412));
    assert_eq!(refused_with(403, "Rate Limit Exceeded").status, Some(412));
    assert_eq!(refused_with(412, "Precondition Failed").status, Some(412));
}

#[test]
fn an_occurrence_google_refuses_for_good_refuses_the_edit() {
    assert_eq!(refused_with(400, "Invalid start time.").status, Some(422));
    assert_eq!(refused_with(403, "Forbidden").status, Some(422));
}

/// The weekly series, organized by `organizer`, with `attendees`.
fn meeting(organizer: &str, attendees: Value) -> FakeCalendar {
    let mut calendar = weekly(Vec::new());
    let mut master = to_value(&calendar.events[0]).unwrap();
    master["organizer"] = json!({ "email": organizer });
    master["attendees"] = attendees;
    calendar.events[0] = from_value(master).unwrap();
    calendar
}

/// Jane's addresses on her primary calendar.
fn jane() -> Vec<String> {
    addresses("jane@example.com", "jane@example.com")
}

/// A new event at lunch, with `people` among its lines.
fn lunch(people: &str) -> String {
    format!(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Pimalaya//Android//EN\r\n\
         BEGIN:VEVENT\r\nUID:lunch\r\nDTSTAMP:20261009T080000Z\r\n\
         DTSTART:20261012T120000Z\r\nDTEND:20261012T130000Z\r\nSUMMARY:Lunch\r\n\
         {people}END:VEVENT\r\nEND:VCALENDAR\r\n"
    )
}

#[test]
fn a_new_meeting_is_inserted_with_its_guests_invited() {
    for people in [
        "ATTENDEE:mailto:ada@example.com\r\n",
        "ORGANIZER:mailto:Jane@Example.com\r\nATTENDEE:mailto:ada@example.com\r\n",
    ] {
        let mut calendar = FakeCalendar::default();

        let created = calendar
            .serve(|mut gcal| gcal.create_entry("jane@example.com", &lunch(people), &jane()))
            .unwrap();

        assert_eq!(calendar.writes(), [("INSERT", "new")], "{people}");
        assert_eq!(calendar.notified(), [Some(GcalSendUpdates::All)]);
        assert_eq!(calendar.sent[0].body["iCalUID"], "lunch", "the UID is kept");
        assert_eq!(created.id, "new");
    }
}

#[test]
fn a_new_event_nobody_is_invited_to_is_imported() {
    for people in [
        "",
        "ORGANIZER:mailto:jane@example.com\r\n",
        "ORGANIZER:mailto:boss@example.com\r\nATTENDEE:mailto:jane@example.com\r\n",
    ] {
        let mut calendar = FakeCalendar::default();

        calendar
            .serve(|mut gcal| gcal.create_entry("jane@example.com", &lunch(people), &jane()))
            .unwrap();

        assert_eq!(calendar.writes(), [("IMPORT", "new")], "{people}");
        assert_eq!(calendar.notified(), [None]);
    }
}

#[test]
fn every_write_of_a_meeting_the_user_organizes_tells_its_guests() {
    let ada = json!([{ "email": "ada@example.com", "responseStatus": "needsAction" }]);
    let all = Some(GcalSendUpdates::All);

    let mut calendar = meeting("jane@example.com", ada.clone());
    let staged = calendar.ical().replace("SUMMARY:Series", "SUMMARY:Weekly");
    calendar
        .entry(|entry| entry.update(&staged, None, &jane()))
        .unwrap();
    assert_eq!(calendar.writes(), [("PUT", "m")]);
    assert_eq!(calendar.notified(), [all]);

    let mut calendar = meeting("jane@example.com", ada.clone());
    let staged = write(&calendar.ical(), &moved("2026-10-19", "10")).unwrap();
    calendar
        .entry(|entry| entry.update(&staged, None, &jane()))
        .unwrap();
    assert_eq!(calendar.writes(), [("PUT", "m_20261019T090000Z")]);
    assert_eq!(calendar.notified(), [all]);

    let mut calendar = meeting("jane@example.com", ada.clone());
    let staged = remove(&calendar.ical(), &this("2026-10-26", json!({})))
        .unwrap()
        .unwrap();
    calendar
        .entry(|entry| entry.update(&staged, None, &jane()))
        .unwrap();
    assert_eq!(
        calendar.writes(),
        [("PUT", "m"), ("DELETE", "m_20261026T090000Z")]
    );
    assert_eq!(calendar.notified(), [all, all]);

    let mut calendar = meeting("jane@example.com", ada);
    calendar
        .entry(|entry| entry.delete(Some("\"1\""), &jane()))
        .unwrap();
    assert_eq!(calendar.writes(), [("DELETE", "m")]);
    assert_eq!(calendar.sent[0].if_match.as_deref(), Some("\"1\""));
    assert_eq!(calendar.notified(), [all]);
}

/// The guests taken off a meeting hear of it: the copy the edit
/// replaces had them.
#[test]
fn a_meeting_whose_guests_are_all_taken_off_still_tells_them() {
    let ada = json!([{ "email": "ada@example.com" }]);
    let mut calendar = meeting("jane@example.com", ada);
    let staged: String = calendar
        .ical()
        .split_inclusive("\r\n")
        .filter(|line| !line.starts_with("ATTENDEE"))
        .collect();

    calendar
        .entry(|entry| entry.update(&staged, None, &jane()))
        .unwrap();

    assert!(calendar.sent[0].body.get("attendees").is_none());
    assert_eq!(calendar.notified(), [Some(GcalSendUpdates::All)]);
}

/// An event created on a secondary calendar is organized by that
/// calendar, which is the user's as much as their address is.
#[test]
fn a_meeting_a_secondary_calendar_organizes_tells_its_guests() {
    let team = "team@group.calendar.google.com";
    let mut calendar = meeting(team, json!([{ "email": "ada@example.com" }]));
    let staged = calendar.ical().replace("SUMMARY:Series", "SUMMARY:Weekly");

    let addresses = addresses("jane@example.com", team);
    calendar
        .entry(|entry| entry.update(&staged, None, &addresses))
        .unwrap();

    assert_eq!(calendar.notified(), [Some(GcalSendUpdates::All)]);
}

#[test]
fn a_write_of_an_event_without_guests_tells_nobody() {
    let none = Some(GcalSendUpdates::None);

    let mut calendar = weekly(Vec::new());
    let staged = calendar.ical().replace("SUMMARY:Series", "SUMMARY:Weekly");
    calendar
        .entry(|entry| entry.update(&staged, None, &jane()))
        .unwrap();
    let staged = write(&calendar.ical(), &moved("2026-10-19", "10")).unwrap();
    calendar
        .entry(|entry| entry.update(&staged, None, &jane()))
        .unwrap();
    calendar.entry(|entry| entry.delete(None, &jane())).unwrap();

    assert_eq!(
        calendar.writes(),
        [("PUT", "m"), ("PUT", "m_20261019T090000Z"), ("DELETE", "m")]
    );
    assert_eq!(calendar.notified(), [none, none, none]);
    assert_eq!(
        calendar.sent[2].if_match, None,
        "an unguarded delete names no ETag"
    );
}

/// Answering someone else's invitation is the attendee's own reply,
/// which no `sendUpdates` stands for: nobody is told anything new.
#[test]
fn an_answer_to_someone_elses_invitation_tells_nobody() {
    let people = json!([
        { "email": "jane@example.com", "responseStatus": "needsAction" },
        { "email": "ada@example.com", "responseStatus": "accepted" },
    ]);
    let mut calendar = meeting("boss@example.com", people.clone());
    let ical = calendar.ical();
    let staged = ical.replace(
        "PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:jane@",
        "PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:jane@",
    );
    assert_ne!(staged, ical);

    calendar
        .entry(|entry| entry.update(&staged, None, &jane()))
        .unwrap();

    assert_eq!(calendar.writes(), [("PUT", "m")]);
    assert_eq!(
        calendar.sent[0].body["attendees"][0]["responseStatus"],
        "accepted"
    );
    assert_eq!(calendar.notified(), [Some(GcalSendUpdates::None)]);

    let mut calendar = meeting("boss@example.com", people);
    calendar.entry(|entry| entry.delete(None, &jane())).unwrap();
    assert_eq!(calendar.notified(), [Some(GcalSendUpdates::None)]);
}
