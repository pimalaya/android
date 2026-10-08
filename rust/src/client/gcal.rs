//! Google Calendar API calendars: the user's calendar list as
//! collections, their events as iCalendar through io-gcal's `ical`
//! projection.
//!
//! Google is instance-granular where the store is resource-granular: a
//! series is a master plus one event per changed or cancelled instance,
//! each with its own ETag, and editing an instance leaves the master's
//! untouched. An entry's revision is therefore the master's ETag folded
//! with its instances', so an instance edit reads as the entry moving.
//!
//! A first round lists a calendar in full and keeps the `syncToken` its
//! last page issues; every round after lists only the events changed
//! since, with the same parameters. What changed is instance-granular
//! too, so a changed instance names its series, which is read again
//! whole through its `iCalUID` (one listing, the master and every
//! instance), and a token Google expired (410) starts the calendar over
//! in full.
//!
//! Writes go to the master alone, with Google's own `If-Match` on its
//! ETag, after the folded revision the edit was staged against is
//! checked: a moved entry answers 412 like a CalDAV server would.

use std::collections::BTreeMap;

use io_gcal::{
    coroutine::*,
    v3::{
        rest::{
            calendar_list::list::{GcalCalendarListList, GcalCalendarListListParams},
            events::{
                GcalEvent, GcalEventStatus, GcalEvents,
                delete::GcalEventDelete,
                get::GcalEventGet,
                import::{GcalEventImport, GcalEventImportParams},
                list::{GcalEventsList, GcalEventsListParams},
                update::{GcalEventUpdate, GcalEventUpdateParams},
            },
        },
        send::{GCAL_API_BASE, GcalSendError, GcalSendOutput},
    },
};
use io_http::rfc6750::bearer::HttpAuthBearer;
use sha2::{Digest, Sha256};

use crate::{
    client::{Client, convert::coroutine_error},
    types::{BridgeError, Calendar, Event, EventDelta, EventRef},
};

/// The page size asked for when listing events, the API's ceiling.
const PAGE_SIZE: u32 = 2500;

impl<'a, 'local> Client<'a, 'local> {
    /// Lists the user's calendars. Collection URLs are left empty: the
    /// caller composes them, only it knowing the account they belong to.
    pub fn list_gcal_calendars(&mut self, token: &str) -> Result<Vec<Calendar>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let mut calendars = Vec::new();
        let mut page_token: Option<String> = None;

        loop {
            let params = GcalCalendarListListParams {
                page_token: page_token.as_deref(),
                ..Default::default()
            };
            let coroutine =
                GcalCalendarListList::new(&auth, &params).map_err(|err| err.to_string())?;
            let page = self.run_gcal(coroutine)?;

            for entry in page.items {
                let Some(id) = entry.id.filter(|id| !id.is_empty()) else {
                    continue;
                };
                calendars.push(Calendar {
                    name: entry
                        .summary_override
                        .or(entry.summary)
                        .unwrap_or_else(|| id.clone()),
                    id,
                    url: String::new(),
                    description: entry.description,
                    color: entry.background_color.filter(|color| !color.is_empty()),
                });
            }

            match page.next_page_token {
                Some(next) => page_token = Some(next),
                None => return Ok(calendars),
            }
        }
    }

    /// One round of a calendar from its `syncToken`, or in full without
    /// one ([`sync_events`]); [`None`] when Google expired the token.
    pub fn sync_gcal_events(
        &mut self,
        token: &str,
        calendar: &str,
        cursor: Option<&str>,
    ) -> Result<Option<EventDelta>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        sync_events(&mut GcalCalls::new(self, &auth), calendar, cursor)
    }

    /// The iCalendar objects of the named events, each at its folded
    /// revision.
    pub fn read_gcal_events(
        &mut self,
        token: &str,
        calendar: &str,
        ids: &[&str],
    ) -> Result<Vec<Event>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let mut events = Vec::with_capacity(ids.len());

        for id in ids {
            let (master, instances) = self.gcal_series(&auth, calendar, id)?;
            let mut event = entry(master, &instances);
            event.id = id.to_string();
            events.push(event);
        }

        Ok(events)
    }

    /// Creates an event from an iCalendar object in a calendar, answering
    /// the id Google gave it and its revision.
    ///
    /// Imported rather than inserted: an insert mints its own `iCalUID`,
    /// a second event for every other client, where an import keeps the
    /// one the object carries.
    pub fn create_gcal_event(
        &mut self,
        token: &str,
        calendar: &str,
        ical: &str,
    ) -> Result<EventRef, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let event = GcalEvent::from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;

        let params = GcalEventImportParams::default();
        let coroutine = GcalEventImport::new(&auth, &calendar_path(calendar), &event, &params)
            .map_err(|err| err.to_string())?;
        let created = self.run_gcal(coroutine)?;

        Ok(EventRef {
            etag: revision(&created, &[]),
            id: created.id.unwrap_or_default(),
        })
    }

    /// Replaces a series master from an iCalendar object, merged onto the
    /// server copy so what the projection does not model survives.
    pub fn update_gcal_event(
        &mut self,
        token: &str,
        calendar: &str,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let (current, instances) = self.gcal_series(&auth, calendar, id)?;
        check_revision(id, &current, &instances, if_match)?;

        let projected = GcalEvent::from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;
        let event = projected.merge(&current);

        let params = GcalEventUpdateParams::default();
        let coroutine = GcalEventUpdate::new(
            &auth,
            &calendar_path(calendar),
            id,
            &event,
            &params,
            current.etag.as_deref(),
        )
        .map_err(|err| err.to_string())?;
        let updated = self.run_gcal(coroutine)?;

        Ok(revision(&updated, &instances))
    }

    /// Deletes an event, the whole series for a master, conditionally on
    /// `if_match`.
    pub fn delete_gcal_event(
        &mut self,
        token: &str,
        calendar: &str,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let mut etag = None;
        if if_match.is_some() {
            let (current, instances) = self.gcal_series(&auth, calendar, id)?;
            check_revision(id, &current, &instances, if_match)?;
            etag = current.etag;
        }

        let coroutine =
            GcalEventDelete::new(&auth, &calendar_path(calendar), id, None, etag.as_deref())
                .map_err(|err| err.to_string())?;
        self.run_gcal(coroutine)?;

        Ok(())
    }

    /// Reads one entry: the event, and the changed or cancelled instances
    /// of a series, which share its `iCalUID`.
    fn gcal_series(
        &mut self,
        auth: &HttpAuthBearer,
        calendar: &str,
        id: &str,
    ) -> Result<(GcalEvent, Vec<GcalEvent>), BridgeError> {
        series(&mut GcalCalls::new(self, auth), calendar, id)
    }

    /// Runs one Google Calendar coroutine to completion over the
    /// transport.
    fn run_gcal<C, T>(&mut self, coroutine: C) -> Result<T, BridgeError>
    where
        C: GcalCoroutine<Yield = GcalYield, Return = Result<GcalSendOutput<T>, GcalSendError>>,
    {
        self.try_gcal(coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// [`Self::run_gcal`] keeping Calendar's own error, so a caller can
    /// tell an expired sync token apart; the outer error is the
    /// transport's.
    fn try_gcal<C, T>(&mut self, mut coroutine: C) -> Result<Result<T, GcalSendError>, BridgeError>
    where
        C: GcalCoroutine<Yield = GcalYield, Return = Result<GcalSendOutput<T>, GcalSendError>>,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                GcalCoroutineState::Complete(Ok(output)) => return Ok(Ok(output.response)),
                GcalCoroutineState::Complete(Err(err)) => return Ok(Err(err)),
                GcalCoroutineState::Yielded(GcalYield::WantsRead) => {
                    arg = Some(self.http_read(GCAL_API_BASE)?);
                }
                GcalCoroutineState::Yielded(GcalYield::WantsWrite(bytes)) => {
                    self.http_write(GCAL_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// The two requests a calendar read sends, apart so the reads can run
/// over a fake: a page of `events.list`, and one event.
pub(super) trait GcalReads {
    /// Lists one page of a calendar's events; Calendar's own error is
    /// kept, so an expired sync token can be told apart.
    fn events(
        &mut self,
        calendar: &str,
        params: &GcalEventsListParams,
    ) -> Result<Result<GcalEvents, GcalSendError>, BridgeError>;

    /// Reads one event.
    fn event(&mut self, calendar: &str, id: &str) -> Result<GcalEvent, BridgeError>;
}

/// The calendar reads of one native call, over its transport.
struct GcalCalls<'c, 'a, 'local> {
    client: &'c mut Client<'a, 'local>,
    auth: &'c HttpAuthBearer,
}

impl<'c, 'a, 'local> GcalCalls<'c, 'a, 'local> {
    fn new(client: &'c mut Client<'a, 'local>, auth: &'c HttpAuthBearer) -> Self {
        Self { client, auth }
    }
}

impl GcalReads for GcalCalls<'_, '_, '_> {
    fn events(
        &mut self,
        calendar: &str,
        params: &GcalEventsListParams,
    ) -> Result<Result<GcalEvents, GcalSendError>, BridgeError> {
        let coroutine = GcalEventsList::new(self.auth, &calendar_path(calendar), params)
            .map_err(|err| err.to_string())?;
        self.client.try_gcal(coroutine)
    }

    fn event(&mut self, calendar: &str, id: &str) -> Result<GcalEvent, BridgeError> {
        let coroutine = GcalEventGet::new(self.auth, &calendar_path(calendar), id, None, None)
            .map_err(|err| err.to_string())?;
        self.client.run_gcal(coroutine)
    }
}

/// One round of a calendar: in full without a cursor, else what changed
/// since its `syncToken`; [`None`] when Google expired the token (410),
/// so the caller starts over in full.
///
/// Whole either way: a full listing reads every event and every changed
/// or cancelled instance, which is all an entry is built from, so each
/// body comes with the round rather than being read again. A changed
/// lone event is whole in the changes; a series changed in any part,
/// master or instance, is read again through its `iCalUID`, since the
/// changes carry only the parts that moved. A cancelled lone event or
/// master is the entry gone.
pub(super) fn sync_events<R: GcalReads>(
    reads: &mut R,
    calendar: &str,
    cursor: Option<&str>,
) -> Result<Option<EventDelta>, BridgeError> {
    let Some(token) = cursor else {
        let (events, token) =
            listing(reads, calendar, None, None)?.map_err(|err| coroutine_error(&err))?;
        let bodies: Vec<Event> = entries(events)
            .into_iter()
            .map(|(master, instances)| entry(master, &instances))
            .collect();
        return Ok(Some(delta(bodies, Vec::new(), token, true)));
    };

    let (changes, token) = match listing(reads, calendar, None, Some(token))? {
        Ok(changes) => changes,
        Err(err) if err.is_sync_token_expired() => {
            log::info!("calendar sync token expired, listing in full");
            return Ok(None);
        }
        Err(err) => return Err(coroutine_error(&err)),
    };

    let mut bodies = Vec::new();
    let mut vanished = Vec::new();
    // NOTE: the series to read again, by master id, with the iCalUID
    // the changes named them by.
    let mut series: BTreeMap<String, Option<String>> = BTreeMap::new();

    for event in changes {
        if let Some(master) = event.recurring_event_id.clone() {
            let uid = series.entry(master).or_default();
            if uid.is_none() {
                *uid = event.ical_uid;
            }
            continue;
        }
        let Some(id) = event.id.clone().filter(|id| !id.is_empty()) else {
            continue;
        };
        if event.status == Some(GcalEventStatus::Cancelled) {
            vanished.push(id);
        } else if !event.recurrence.is_empty() {
            series.insert(id, event.ical_uid);
        } else {
            bodies.push(entry(event, &[]));
        }
    }

    for (id, uid) in series {
        if vanished.contains(&id) {
            continue;
        }
        match series_by_uid(reads, calendar, &id, uid.as_deref())? {
            Some((master, instances)) => bodies.push(entry(master, &instances)),
            None => vanished.push(id),
        }
    }

    Ok(Some(delta(bodies, vanished, token, false)))
}

/// A round's delta: every body it read, named at its revision.
fn delta(
    bodies: Vec<Event>,
    vanished: Vec<String>,
    token: Option<String>,
    complete: bool,
) -> EventDelta {
    EventDelta {
        changed: bodies
            .iter()
            .map(|event| EventRef {
                id: event.id.clone(),
                etag: event.etag.clone(),
            })
            .collect(),
        bodies,
        vanished,
        token,
        complete,
    }
}

/// A listing's events, and the `syncToken` its last page issued.
type Listed = (Vec<GcalEvent>, Option<String>);

/// Every event of a calendar, or of one `iCalUID`, its pages followed,
/// and the `syncToken` its last page issued. Cancelled ones included: a
/// cancelled instance is how Google says an occurrence was removed from
/// a series.
///
/// The same parameters every time, a sync token only ever added to
/// them: Google answers a token only to the listing shape that issued
/// it.
fn listing<R: GcalReads>(
    reads: &mut R,
    calendar: &str,
    uid: Option<&str>,
    sync_token: Option<&str>,
) -> Result<Result<Listed, GcalSendError>, BridgeError> {
    let mut events = Vec::new();
    let mut page_token: Option<String> = None;

    loop {
        let params = GcalEventsListParams {
            ical_uid: uid,
            max_results: Some(PAGE_SIZE),
            page_token: page_token.as_deref(),
            show_deleted: true,
            sync_token,
            ..Default::default()
        };
        let page = match reads.events(calendar, &params)? {
            Ok(page) => page,
            Err(err) => return Ok(Err(err)),
        };
        events.extend(page.items);

        match page.next_page_token {
            Some(next) => page_token = Some(next),
            None => return Ok(Ok((events, page.next_sync_token))),
        }
    }
}

/// Reads one entry: the event, and the changed or cancelled instances
/// of a series, which share its `iCalUID`.
fn series<R: GcalReads>(
    reads: &mut R,
    calendar: &str,
    id: &str,
) -> Result<(GcalEvent, Vec<GcalEvent>), BridgeError> {
    let master = reads.event(calendar, id)?;
    if master.recurrence.is_empty() {
        return Ok((master, Vec::new()));
    }
    let Some(uid) = master.ical_uid.clone() else {
        return Ok((master, Vec::new()));
    };

    let instances = listing(reads, calendar, Some(&uid), None)?
        .map_err(|err| coroutine_error(&err))?
        .0
        .into_iter()
        .filter(|event| event.recurring_event_id.as_deref() == Some(id))
        .collect();

    Ok((master, instances))
}

/// Reads one changed series again, whole: the listing of its `iCalUID`
/// carries the master and every instance at once. [`None`] when the
/// series is gone.
///
/// A series named without its `iCalUID`, or whose listing leaves the
/// master out, is read the way a fetch reads it, the master on its own
/// and its instances after.
fn series_by_uid<R: GcalReads>(
    reads: &mut R,
    calendar: &str,
    id: &str,
    uid: Option<&str>,
) -> Result<Option<(GcalEvent, Vec<GcalEvent>)>, BridgeError> {
    if let Some(uid) = uid {
        let (events, _) =
            listing(reads, calendar, Some(uid), None)?.map_err(|err| coroutine_error(&err))?;
        let mut master = None;
        let mut instances = Vec::new();
        for event in events {
            if event.id.as_deref() == Some(id) {
                master = Some(event);
            } else if event.recurring_event_id.as_deref() == Some(id) {
                instances.push(event);
            }
        }
        if let Some(master) = master {
            return Ok(standing(master).map(|master| (master, instances)));
        }
    }

    match series(reads, calendar, id) {
        Ok((master, instances)) => Ok(standing(master).map(|master| (master, instances))),
        Err(err) if matches!(err.status, Some(404 | 410)) => Ok(None),
        Err(err) => Err(err),
    }
}

/// The event, unless it was cancelled.
fn standing(event: GcalEvent) -> Option<GcalEvent> {
    (event.status != Some(GcalEventStatus::Cancelled)).then_some(event)
}

/// A full listing grouped into entries: each lone event or series master
/// still standing, with the instances that belong to it.
fn entries(events: Vec<GcalEvent>) -> Vec<(GcalEvent, Vec<GcalEvent>)> {
    let mut masters = BTreeMap::new();
    let mut instances: BTreeMap<String, Vec<GcalEvent>> = BTreeMap::new();

    for event in events {
        match event.recurring_event_id.clone() {
            Some(master) => instances.entry(master).or_default().push(event),
            None => {
                let Some(id) = event.id.clone().filter(|id| !id.is_empty()) else {
                    continue;
                };
                if event.status == Some(GcalEventStatus::Cancelled) {
                    continue;
                }
                masters.insert(id, event);
            }
        }
    }

    masters
        .into_iter()
        .map(|(id, master)| (master, instances.remove(&id).unwrap_or_default()))
        .collect()
}

/// One entry as the store keeps it: the master and its instances as one
/// iCalendar object, at the folded revision.
fn entry(master: GcalEvent, instances: &[GcalEvent]) -> Event {
    let overrides: Vec<&GcalEvent> = instances.iter().collect();
    Event {
        etag: revision(&master, instances),
        ical: master.to_ical_series(&overrides),
        id: master.id.unwrap_or_default(),
    }
}

/// An entry's revision: the master's ETag alone, or folded with its
/// instances' when the series has any.
fn revision(master: &GcalEvent, instances: &[GcalEvent]) -> Option<String> {
    let etag = master.etag.clone()?;
    if instances.is_empty() {
        return Some(etag);
    }

    let mut etags: Vec<&str> = instances
        .iter()
        .filter_map(|instance| instance.etag.as_deref())
        .collect();
    etags.sort_unstable();

    let mut digest = Sha256::new();
    for instance in etags {
        digest.update(instance.as_bytes());
        digest.update(b"\n");
    }
    let hex: String = digest
        .finalize()
        .iter()
        .take(8)
        .map(|byte| format!("{byte:02x}"))
        .collect();

    Some(format!("{etag}+{hex}"))
}

/// Refuses a write when the entry moved on Google since `if_match`, with
/// the status a CalDAV server answers a failed precondition with.
fn check_revision(
    id: &str,
    master: &GcalEvent,
    instances: &[GcalEvent],
    if_match: Option<&str>,
) -> Result<(), BridgeError> {
    match if_match {
        Some(expected) if revision(master, instances).as_deref() != Some(expected) => {
            Err(BridgeError {
                message: format!("Event {id} changed on Google since it was read"),
                status: Some(412),
            })
        }
        _ => Ok(()),
    }
}

/// A calendar id as a path segment.
///
/// Google's ids carry `@` and, for the subscribed ones, `#`, which a URL
/// would read as the start of a fragment, and io-gcal pastes the id into
/// the path as it is given.
fn calendar_path(id: &str) -> String {
    let mut encoded = String::with_capacity(id.len());
    for byte in id.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' | b'@' => {
                encoded.push(char::from(byte))
            }
            _ => encoded.push_str(&format!("%{byte:02X}")),
        }
    }
    encoded
}

#[cfg(test)]
mod tests {
    use super::*;

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

    impl GcalReads for FakeCalendar {
        fn events(
            &mut self,
            calendar: &str,
            params: &GcalEventsListParams,
        ) -> Result<Result<GcalEvents, GcalSendError>, BridgeError> {
            assert_eq!(calendar, "jane@example.com");
            assert!(params.show_deleted, "the same parameters every time");
            assert_eq!(params.max_results, Some(PAGE_SIZE));
            assert!(params.time_min.is_none() && params.updated_min.is_none());
            assert!(params.page_token.is_none());
            self.listings.push((
                params.ical_uid.map(str::to_string),
                params.sync_token.map(str::to_string),
            ));

            let items = match (params.ical_uid, params.sync_token) {
                (Some(_), Some(_)) => panic!("a sync token beside an iCalUID"),
                (None, Some(token)) => {
                    assert_eq!(token, "token-1");
                    if self.expired {
                        return Ok(Err(GcalSendError::Api {
                            status: 410,
                            message: "Sync token is no longer valid".into(),
                        }));
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

            Ok(Ok(GcalEvents {
                items,
                next_sync_token: params.ical_uid.is_none().then(|| "token-2".into()),
                ..Default::default()
            }))
        }

        fn event(&mut self, _: &str, id: &str) -> Result<GcalEvent, BridgeError> {
            self.gets += 1;
            self.events
                .iter()
                .find(|event| event.id.as_deref() == Some(id))
                .cloned()
                .ok_or(BridgeError {
                    message: "Not Found".into(),
                    status: Some(404),
                })
        }
    }

    /// What a full listing makes of the calendar, by entry id.
    fn whole(calendar: &mut FakeCalendar) -> BTreeMap<String, (Option<String>, String)> {
        sync_events(calendar, "jane@example.com", None)
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

        let delta = sync_events(&mut calendar, "jane@example.com", None)
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

        let delta = sync_events(&mut calendar, "jane@example.com", Some("token-1"))
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

        let delta = sync_events(&mut calendar, "jane@example.com", Some("token-1"))
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

        let delta = sync_events(&mut calendar, "jane@example.com", Some("token-1"))
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

        let delta = sync_events(&mut calendar, "jane@example.com", Some("token-1"))
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

        let delta = sync_events(&mut calendar, "jane@example.com", Some("token-1"))
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

        let delta = sync_events(&mut calendar, "jane@example.com", Some("token-1"))
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

        let delta = sync_events(&mut calendar, "jane@example.com", Some("token-1"))
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
            sync_events(&mut calendar, "jane@example.com", Some("token-1"))
                .unwrap()
                .is_none()
        );
        assert_eq!(calendar.listings.len(), 1);
    }
}
