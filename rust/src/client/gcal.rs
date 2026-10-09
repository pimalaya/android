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
//! Writes go to the master, with Google's own `If-Match` on its ETag,
//! after the folded revision the edit was staged against is checked: a
//! moved entry answers 412 like a CalDAV server would. Each occurrence the
//! object holds otherwise goes to its instance after it: replaced from an
//! override, deleted for an `EXDATE` or a cancelled override, replaced
//! back with the series for an override the edit removed, Google having
//! no reset of an exception.

use std::collections::BTreeMap;

use io_gcal::{
    coroutine::*,
    v3::{
        rest::{
            acl::GcalAccessRole,
            calendar_list::{
                GcalCalendarListEntry,
                list::{GcalCalendarListList, GcalCalendarListListParams},
            },
            events::{
                GcalEvent, GcalEventStatus, GcalEvents,
                delete::GcalEventDelete,
                get::GcalEventGet,
                import::{GcalEventImport, GcalEventImportParams},
                instances::{GcalEventInstances, GcalEventInstancesParams},
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
    calendar::{
        OccurrenceChange, OccurrenceWrite, occurrence_changes, occurrence_window, occurrences,
    },
    client::{
        Client,
        convert::{REFUSED, coroutine_error, parts_failure},
    },
    types::{BridgeError, Calendar, Event, EventDelta, EventRef, default_role},
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

            calendars.extend(page.items.into_iter().filter_map(gcal_calendar));

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

    /// Writes an entry from an iCalendar object: its master, and each
    /// occurrence the object holds otherwise than Google ([`update_entry`]).
    pub fn update_gcal_event(
        &mut self,
        token: &str,
        calendar: &str,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        update_entry(
            &mut GcalCalls::new(self, &auth),
            calendar,
            id,
            ical,
            if_match,
        )
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

/// The requests an entry write sends beside its reads, apart so the write
/// can run over a fake: a window of a series' instances, and one event
/// replaced or deleted, each on its ETag.
pub(super) trait GcalWrites: GcalReads {
    /// Lists the instances of a series starting between two instants, at
    /// most one page: a window of days holds fewer than a page.
    fn instances(
        &mut self,
        calendar: &str,
        id: &str,
        start: &str,
        end: &str,
    ) -> Result<Vec<GcalEvent>, BridgeError>;

    /// Replaces one event, answering it as replaced.
    fn update(
        &mut self,
        calendar: &str,
        id: &str,
        event: &GcalEvent,
        if_match: Option<&str>,
    ) -> Result<GcalEvent, BridgeError>;

    /// Deletes one event.
    fn delete(
        &mut self,
        calendar: &str,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError>;
}

impl GcalWrites for GcalCalls<'_, '_, '_> {
    fn instances(
        &mut self,
        calendar: &str,
        id: &str,
        start: &str,
        end: &str,
    ) -> Result<Vec<GcalEvent>, BridgeError> {
        let params = GcalEventInstancesParams {
            time_min: Some(start),
            time_max: Some(end),
            max_results: Some(PAGE_SIZE),
            ..Default::default()
        };
        let coroutine = GcalEventInstances::new(self.auth, &calendar_path(calendar), id, &params)
            .map_err(|err| err.to_string())?;
        Ok(self.client.run_gcal(coroutine)?.items)
    }

    fn update(
        &mut self,
        calendar: &str,
        id: &str,
        event: &GcalEvent,
        if_match: Option<&str>,
    ) -> Result<GcalEvent, BridgeError> {
        let params = GcalEventUpdateParams::default();
        let coroutine = GcalEventUpdate::new(
            self.auth,
            &calendar_path(calendar),
            id,
            event,
            &params,
            if_match,
        )
        .map_err(|err| err.to_string())?;
        self.client.run_gcal(coroutine)
    }

    fn delete(
        &mut self,
        calendar: &str,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let coroutine =
            GcalEventDelete::new(self.auth, &calendar_path(calendar), id, None, if_match)
                .map_err(|err| err.to_string())?;
        self.client.run_gcal(coroutine)?;
        Ok(())
    }
}

/// Writes one entry: its master, merged onto the server copy so what the
/// projection does not model survives, then each occurrence the object
/// holds otherwise, through its instance.
///
/// The occurrences are diffed against the series as the master's write
/// left it, read again when there was one, since that write can change
/// its instances. An occurrence that does not land fails the write with a
/// 412 once the others are written, the master standing: the entry moved
/// under the edit, which stays staged, and the next pass reconciles it as
/// any entry that moved.
/// Occurrences the server refuses for good, and nothing else failing,
/// answer [`REFUSED`] instead, which the engine records as a refusal of
/// the edit rather than a wait.
pub(super) fn update_entry<W: GcalWrites>(
    writes: &mut W,
    calendar: &str,
    id: &str,
    ical: &str,
    if_match: Option<&str>,
) -> Result<Option<String>, BridgeError> {
    let (current, instances) = series(writes, calendar, id)?;
    check_revision(id, &current, &instances, if_match)?;

    let base = entry(current.clone(), &instances).ical;
    let pending = occurrence_changes(ical, &base)?;
    let projected = GcalEvent::from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;
    let held = GcalEvent::from_ical(base.as_bytes()).map_err(|err| err.to_string())?;

    // NOTE: an edit of occurrences alone leaves the master as it is.
    let wrote = projected != held;
    let mut written = revision(&current, &instances);
    if wrote {
        let event = projected.merge(&current);
        let updated = writes.update(calendar, id, &event, current.etag.as_deref())?;
        written = revision(&updated, &instances);
    }
    if pending.is_empty() {
        return Ok(written);
    }

    let read = (!wrote).then_some((current, instances));
    write_occurrences(writes, calendar, id, ical, read).map_err(|err| match err.status {
        Some(REFUSED) => BridgeError {
            message: format!("Google refuses an occurrence of event {id} for good: {err}"),
            status: Some(REFUSED),
        },
        _ => BridgeError {
            message: format!("Event {id} was written to Google without all its occurrences: {err}"),
            status: Some(412),
        },
    })
}

/// Writes every occurrence an object holds otherwise than its series on
/// Google, each on its own, answering the entry's revision once all
/// landed and the first failure otherwise. The series is the one `read`,
/// unless the master was written since, which can change its instances.
fn write_occurrences<W: GcalWrites>(
    writes: &mut W,
    calendar: &str,
    id: &str,
    ical: &str,
    read: Option<(GcalEvent, Vec<GcalEvent>)>,
) -> Result<Option<String>, BridgeError> {
    let (master, instances) = match read {
        Some(read) => read,
        None => series(writes, calendar, id)?,
    };
    let server = entry(master.clone(), &instances).ical;

    let mut failures = Vec::new();
    for change in occurrence_changes(ical, &server)? {
        if let Err(err) = write_occurrence(writes, calendar, id, &master, &instances, &change) {
            log::warn!(
                "cannot write occurrence {} of event {id}: {err}",
                change.stamp()
            );
            failures.push(err);
        }
    }

    if let Some(err) = parts_failure(failures) {
        return Err(err);
    }
    let (master, instances) = series(writes, calendar, id)?;
    Ok(revision(&master, &instances))
}

/// Writes one occurrence through its instance: among the changed and
/// cancelled ones read with the series, else among the instances of a
/// window around it, each told by the identity its projection gives it.
fn write_occurrence<W: GcalWrites>(
    writes: &mut W,
    calendar: &str,
    id: &str,
    master: &GcalEvent,
    instances: &[GcalEvent],
    change: &OccurrenceChange,
) -> Result<(), BridgeError> {
    let told = |instance: &GcalEvent| {
        occurrences(&master.to_ical_series(&[instance]))
            .is_ok_and(|ids| ids.first() == Some(&change.id))
    };

    let instance = match instances.iter().find(|instance| told(instance)) {
        Some(instance) => Some(instance.clone()),
        None => {
            let (start, end) = occurrence_window(change.id);
            writes
                .instances(calendar, id, &start, &end)?
                .into_iter()
                .find(|instance| told(instance))
        }
    };

    // NOTE: an occurrence with no instance is gone already, which is all
    // a removal asks, or an override of no occurrence the series has,
    // which Google has nowhere to keep.
    let Some(instance) = instance.filter(|instance| instance.id.is_some()) else {
        if change.write != OccurrenceWrite::Delete {
            log::warn!(
                "occurrence {} of event {id} is no instance on Google, left out",
                change.stamp()
            );
        }
        return Ok(());
    };
    let handle = instance.id.as_deref().unwrap_or_default();
    let etag = instance.etag.as_deref();

    match &change.write {
        OccurrenceWrite::Delete if instance.status == Some(GcalEventStatus::Cancelled) => Ok(()),
        OccurrenceWrite::Delete => match writes.delete(calendar, handle, etag) {
            Err(err) if matches!(err.status, Some(404 | 410)) => Ok(()),
            deleted => deleted,
        },
        OccurrenceWrite::Update { staged, server } => {
            let projected =
                GcalEvent::from_ical(staged.as_bytes()).map_err(|err| err.to_string())?;
            let held = GcalEvent::from_ical(server.as_bytes()).map_err(|err| err.to_string())?;
            if projected == held {
                return Ok(());
            }
            let event = projected.merge(&instance);
            writes.update(calendar, handle, &event, etag).map(drop)
        }
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

/// io-gcal calendar list entry to the JNI-facing shape, [`None`] for one
/// with no id. The primary calendar is the account's default; one the
/// user only reads (`reader`, `freeBusyReader`) is not writable.
fn gcal_calendar(entry: GcalCalendarListEntry) -> Option<Calendar> {
    let id = entry.id.filter(|id| !id.is_empty())?;
    let writable = !matches!(
        entry.access_role,
        Some(GcalAccessRole::None | GcalAccessRole::FreeBusyReader | GcalAccessRole::Reader)
    );

    Some(Calendar {
        name: entry
            .summary_override
            .or(entry.summary)
            .unwrap_or_else(|| id.clone()),
        id,
        url: String::new(),
        description: entry.description,
        color: entry.background_color.filter(|color| !color.is_empty()),
        role: default_role(entry.primary == Some(true)),
        writable,
    })
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, from_value, json, to_value};

    use crate::calendar;

    use super::*;

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
        /// Every write, its method, the event it names, its body and its
        /// ETag.
        sent: Vec<(&'static str, String, Value, Option<String>)>,
        /// The event whose write Google refuses, with the status and the
        /// message it answers.
        failing: Option<(String, u16, &'static str)>,
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

    impl FakeCalendar {
        fn written(
            &mut self,
            method: &'static str,
            id: &str,
            body: Value,
            if_match: Option<&str>,
        ) -> Result<(), BridgeError> {
            self.sent
                .push((method, id.to_string(), body, if_match.map(str::to_string)));
            match &self.failing {
                Some((failing, status, message)) if failing == id => Err(BridgeError {
                    message: message.to_string(),
                    status: Some(*status),
                }),
                _ => Ok(()),
            }
        }

        fn writes(&self) -> Vec<(&'static str, &str)> {
            self.sent
                .iter()
                .map(|(method, id, _, _)| (*method, id.as_str()))
                .collect()
        }

        /// The entry as a read hands it over.
        fn ical(&mut self) -> String {
            let (master, instances) = series(self, "jane@example.com", "m").unwrap();
            entry(master, &instances).ical
        }
    }

    impl GcalWrites for FakeCalendar {
        fn instances(
            &mut self,
            _: &str,
            id: &str,
            start: &str,
            end: &str,
        ) -> Result<Vec<GcalEvent>, BridgeError> {
            assert_eq!(id, "m");
            Ok(self
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
                .collect())
        }

        fn update(
            &mut self,
            _: &str,
            id: &str,
            event: &GcalEvent,
            if_match: Option<&str>,
        ) -> Result<GcalEvent, BridgeError> {
            self.written("PUT", id, to_value(event).unwrap(), if_match)?;
            Ok(GcalEvent {
                etag: Some(format!("\"{}\"", self.sent.len() + 1)),
                ..event.clone()
            })
        }

        fn delete(&mut self, _: &str, id: &str, if_match: Option<&str>) -> Result<(), BridgeError> {
            self.written("DELETE", id, Value::Null, if_match)
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
        let staged = calendar::write(&calendar.ical(), &moved("2026-10-19", "10")).unwrap();

        let revision = update_entry(
            &mut calendar,
            "jane@example.com",
            "m",
            &staged,
            Some("\"1\""),
        )
        .unwrap();

        assert_eq!(calendar.writes(), [("PUT", "m_20261019T090000Z")]);
        let (_, _, body, if_match) = &calendar.sent[0];
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
        let staged = calendar::remove(&calendar.ical(), &this("2026-10-26", json!({})))
            .unwrap()
            .unwrap();

        update_entry(
            &mut calendar,
            "jane@example.com",
            "m",
            &staged,
            Some("\"1\""),
        )
        .unwrap();

        assert_eq!(
            calendar.writes(),
            [("PUT", "m"), ("DELETE", "m_20261026T090000Z")]
        );
        assert!(
            calendar.sent[0].2["recurrence"][1]
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

        update_entry(
            &mut calendar,
            "jane@example.com",
            "m",
            &staged,
            revision.as_deref(),
        )
        .unwrap();

        assert_eq!(calendar.writes(), [("PUT", "m_20261012T090000Z")]);
        let body = &calendar.sent[0].2;
        assert_eq!(body["summary"], "Series");
        assert_eq!(body["start"]["dateTime"], "2026-10-12T09:00:00Z");
    }

    #[test]
    fn a_cancelled_instance_is_not_deleted_again() {
        let gone = cancelled(occurrence("2026-10-12", "09", "Series"));
        let mut calendar = weekly(vec![gone.clone()]);
        let revision = revision(&calendar.events[0], &[gone]);
        let staged = calendar.ical();

        update_entry(
            &mut calendar,
            "jane@example.com",
            "m",
            &staged,
            revision.as_deref(),
        )
        .unwrap();

        assert!(calendar.sent.is_empty(), "{:?}", calendar.writes());
    }

    /// Two occurrences moved, Google answering the first's write with
    /// `status` and `message`: what the write answers, the second
    /// occurrence landed either way.
    fn refused_with(status: u16, message: &'static str) -> BridgeError {
        let mut calendar = weekly(Vec::new());
        calendar.failing = Some(("m_20261019T090000Z".into(), status, message));
        let first = calendar::write(&calendar.ical(), &moved("2026-10-19", "10")).unwrap();
        let staged = calendar::write(&first, &moved("2026-11-02", "11")).unwrap();

        let refused = update_entry(
            &mut calendar,
            "jane@example.com",
            "m",
            &staged,
            Some("\"1\""),
        )
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
}
