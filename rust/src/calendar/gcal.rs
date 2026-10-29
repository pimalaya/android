//! # Google calendars
//!
//! The [`Gcal`] backend: the user's calendar list as collections, their
//! events as iCalendar through io-gcal's `ical` projection.
//!
//! Google is instance-granular where the store is resource-granular: a
//! series is a master plus one event per changed or cancelled instance,
//! each with its own ETag. An entry's revision is the master's folded with
//! its instances', so an instance edit reads as the entry moving.
//!
//! A first round lists a calendar in full and keeps its last page's
//! `syncToken`; later ones list what changed since, same parameters. A
//! changed instance names its series, read again whole by `iCalUID`; a
//! token Google expired (410) starts the calendar over in full.
//!
//! Writes go to the master, `If-Match` on its ETag, once the folded
//! revision checks (a moved entry answers 412, as on CalDAV). Each
//! occurrence held otherwise then goes to its instance: replaced, deleted,
//! or replaced back with the series, Google having no reset of an exception.
//!
//! Google mails the guests only when a write names `sendUpdates`, so
//! every write names it: `all` when the user organizes the event with
//! attendees ([`organized`]), `none` otherwise. A create announced so is
//! an insert, an import inviting nobody.

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
                GcalEvent, GcalEventStatus, GcalEvents, GcalSendUpdates,
                delete::GcalEventDelete,
                get::GcalEventGet,
                import::{GcalEventImport, GcalEventImportParams},
                insert::{GcalEventInsert, GcalEventInsertParams},
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
        Calendar, Event, EventDelta, EventRef, EventUpdate,
        occurrence::{
            OccurrenceChange, OccurrenceWrite, occurrence_changes, occurrence_window, occurrences,
        },
        read::organized,
    },
    client::{Client, Host},
    error::{BridgeError, REFUSED, coroutine_error, parts_failure},
    offline::default_role,
};

/// The page size asked for when listing events, the API's ceiling.
const PAGE_SIZE: u32 = 2500;

/// A Google account's Calendar API, signed in to with an OAuth 2.0 token.
pub struct Gcal<'c, H> {
    pub client: &'c mut Client<H>,
    pub token: &'c str,
}

impl<H: Host> Gcal<'_, H> {
    /// Lists the user's calendars. Collection URLs are left empty: the
    /// caller composes them, only it knowing the account they belong to.
    pub fn list_calendars(&mut self) -> Result<Vec<Calendar>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let mut calendars = Vec::new();
        let mut page_token: Option<String> = None;

        loop {
            let params = GcalCalendarListListParams {
                page_token: page_token.as_deref(),
                ..Default::default()
            };
            let coroutine =
                GcalCalendarListList::new(&auth, &params).map_err(|err| err.to_string())?;
            let page = self.run(coroutine)?;

            calendars.extend(page.items.into_iter().filter_map(gcal_calendar));

            match page.next_page_token {
                Some(next) => page_token = Some(next),
                None => return Ok(calendars),
            }
        }
    }

    /// One round of a calendar: all, or what changed since its `syncToken`.
    ///
    /// [`None`] once Google expired it (410). Bodies come whole: a series
    /// changed in any part is reread by `iCalUID` (changes carry what moved);
    /// a cancelled lone event or master is the entry gone.
    pub fn sync_events(
        &mut self,
        calendar: &str,
        cursor: Option<&str>,
    ) -> Result<Option<EventDelta>, BridgeError> {
        let Some(token) = cursor else {
            let (events, token) = self
                .listing(calendar, None, None)?
                .map_err(|err| coroutine_error(&err))?;
            let bodies: Vec<Event> = entries(events)
                .into_iter()
                .map(|(master, instances)| entry(master, &instances))
                .collect();
            return Ok(Some(delta(bodies, Vec::new(), token, true)));
        };

        let (changes, token) = match self.listing(calendar, None, Some(token))? {
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
            match self.series_by_uid(calendar, &id, uid.as_deref())? {
                Some((master, instances)) => bodies.push(entry(master, &instances)),
                None => vanished.push(id),
            }
        }

        Ok(Some(delta(bodies, vanished, token, false)))
    }

    /// The iCalendar objects of the named events, each at its folded
    /// revision.
    pub fn read_events(&mut self, calendar: &str, ids: &[&str]) -> Result<Vec<Event>, BridgeError> {
        let mut events = Vec::with_capacity(ids.len());

        for id in ids {
            let (master, instances) = self.series(calendar, id)?;
            let mut event = entry(master, &instances);
            event.id = id.to_string();
            events.push(event);
        }

        Ok(events)
    }

    /// Creates an event from an iCalendar object in a calendar, as
    /// [`Self::create_entry`] says, `address` the account's.
    pub fn create_event(
        &mut self,
        calendar: &str,
        ical: &str,
        address: &str,
    ) -> Result<EventRef, BridgeError> {
        self.create_entry(calendar, ical, &addresses(address, calendar))
    }

    /// Writes an entry from an iCalendar object: its master, and each
    /// occurrence the object holds otherwise than Google
    /// ([`GcalEntry::update`]).
    pub fn update_event(
        &mut self,
        calendar: &str,
        event: &EventUpdate,
    ) -> Result<Option<String>, BridgeError> {
        GcalEntry {
            gcal: Gcal {
                client: self.client,
                token: self.token,
            },
            calendar,
            id: event.id,
        }
        .update(event.ical, event.etag, &addresses(event.address, calendar))
    }

    /// Deletes an event, as [`GcalEntry::delete`] says, `address` the
    /// account's.
    pub fn delete_event(
        &mut self,
        calendar: &str,
        id: &str,
        if_match: Option<&str>,
        address: &str,
    ) -> Result<(), BridgeError> {
        GcalEntry {
            gcal: Gcal {
                client: self.client,
                token: self.token,
            },
            calendar,
            id,
        }
        .delete(if_match, &addresses(address, calendar))
    }

    /// Runs one Google Calendar coroutine to completion over the
    /// transport.
    fn run<C, T>(&mut self, coroutine: C) -> Result<T, BridgeError>
    where
        C: GcalCoroutine<Yield = GcalYield, Return = Result<GcalSendOutput<T>, GcalSendError>>,
    {
        self.try_run(coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// [`Self::run`] keeping Calendar's own error, so a caller can
    /// tell an expired sync token apart; the outer error is the
    /// transport's.
    fn try_run<C, T>(&mut self, mut coroutine: C) -> Result<Result<T, GcalSendError>, BridgeError>
    where
        C: GcalCoroutine<Yield = GcalYield, Return = Result<GcalSendOutput<T>, GcalSendError>>,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                GcalCoroutineState::Complete(Ok(output)) => return Ok(Ok(output.response)),
                GcalCoroutineState::Complete(Err(err)) => return Ok(Err(err)),
                GcalCoroutineState::Yielded(GcalYield::WantsRead) => {
                    arg = Some(self.client.http_read(GCAL_API_BASE)?);
                }
                GcalCoroutineState::Yielded(GcalYield::WantsWrite(bytes)) => {
                    self.client.http_write(GCAL_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// The requests a calendar read sends: a page of `events.list`, and one
/// event.
impl<H: Host> Gcal<'_, H> {
    /// Lists one page of a calendar's events; Calendar's own error is
    /// kept, so an expired sync token can be told apart.
    fn events(
        &mut self,
        calendar: &str,
        params: &GcalEventsListParams,
    ) -> Result<Result<GcalEvents, GcalSendError>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GcalEventsList::new(&auth, &calendar_path(calendar), params)
            .map_err(|err| err.to_string())?;
        self.try_run(coroutine)
    }

    /// Reads one event.
    fn event(&mut self, calendar: &str, id: &str) -> Result<GcalEvent, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GcalEventGet::new(&auth, &calendar_path(calendar), id, None, None)
            .map_err(|err| err.to_string())?;
        self.run(coroutine)
    }
}

/// The requests an entry write sends beside its reads, each on its ETag
/// and naming who Google notifies where the request has the option.
impl<H: Host> Gcal<'_, H> {
    /// Imports one event, which notifies nobody.
    fn import(&mut self, calendar: &str, event: &GcalEvent) -> Result<GcalEvent, BridgeError> {
        let params = GcalEventImportParams::default();
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GcalEventImport::new(&auth, &calendar_path(calendar), event, &params)
            .map_err(|err| err.to_string())?;
        self.run(coroutine)
    }

    /// Inserts one event.
    fn insert(
        &mut self,
        calendar: &str,
        event: &GcalEvent,
        send_updates: GcalSendUpdates,
    ) -> Result<GcalEvent, BridgeError> {
        let params = GcalEventInsertParams {
            send_updates: Some(send_updates),
            ..Default::default()
        };
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GcalEventInsert::new(&auth, &calendar_path(calendar), event, &params)
            .map_err(|err| err.to_string())?;
        self.run(coroutine)
    }

    /// Lists the instances of a series starting between two instants, at
    /// most one page: a window of days holds fewer than a page.
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
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GcalEventInstances::new(&auth, &calendar_path(calendar), id, &params)
            .map_err(|err| err.to_string())?;
        Ok(self.run(coroutine)?.items)
    }

    /// Replaces one event, answering it as replaced.
    fn update(
        &mut self,
        calendar: &str,
        id: &str,
        event: &GcalEvent,
        if_match: Option<&str>,
        send_updates: GcalSendUpdates,
    ) -> Result<GcalEvent, BridgeError> {
        let params = GcalEventUpdateParams {
            send_updates: Some(send_updates),
            ..Default::default()
        };
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GcalEventUpdate::new(
            &auth,
            &calendar_path(calendar),
            id,
            event,
            &params,
            if_match,
        )
        .map_err(|err| err.to_string())?;
        self.run(coroutine)
    }

    /// Deletes one event.
    fn delete(
        &mut self,
        calendar: &str,
        id: &str,
        if_match: Option<&str>,
        send_updates: GcalSendUpdates,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GcalEventDelete::new(
            &auth,
            &calendar_path(calendar),
            id,
            Some(send_updates),
            if_match,
        )
        .map_err(|err| err.to_string())?;
        self.run(coroutine)?;
        Ok(())
    }

    /// Files an iCalendar object as an event, answering its id and revision.
    ///
    /// An event the user organizes with attendees is inserted, notifying
    /// every guest, under the object's `iCalUID` (taken in place of an
    /// `id`). Any other is imported, which has no `sendUpdates`.
    fn create_entry(
        &mut self,
        calendar: &str,
        ical: &str,
        addresses: &[String],
    ) -> Result<EventRef, BridgeError> {
        let event = GcalEvent::from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;
        let created = match organized(ical, addresses) {
            true => self.insert(calendar, &event, GcalSendUpdates::All)?,
            false => self.import(calendar, &event)?,
        };

        Ok(EventRef {
            etag: revision(&created, &[]),
            id: created.id.unwrap_or_default(),
        })
    }
}

/// Who Google notifies of a write: every guest when the user organizes
/// one of the objects it concerns with attendees ([`organized`]), nobody
/// otherwise.
fn notified(objects: &[&str], addresses: &[String]) -> GcalSendUpdates {
    match objects.iter().any(|ical| organized(ical, addresses)) {
        true => GcalSendUpdates::All,
        false => GcalSendUpdates::None,
    }
}

/// The addresses the user organizes a Google calendar's events under:
/// the account's, and the calendar's own id, which is the organizer of
/// an event created on a calendar other than the primary one.
fn addresses(address: &str, calendar: &str) -> Vec<String> {
    [address, calendar]
        .into_iter()
        .filter(|address| !address.is_empty())
        .map(str::to_string)
        .collect()
}

/// One Google entry as its writes address it: a series in a calendar.
pub struct GcalEntry<'w, H> {
    /// The Calendar API the writes go through.
    pub gcal: Gcal<'w, H>,
    /// The calendar holding the entry.
    pub calendar: &'w str,
    /// The id of the entry's master.
    pub id: &'w str,
}

impl<H: Host> GcalEntry<'_, H> {
    /// Deletes the entry, the whole series for a master, on `if_match`.
    ///
    /// Read first either way: the guests hear of the deletion when the
    /// user organizes the copy it removes.
    pub fn delete(
        &mut self,
        if_match: Option<&str>,
        addresses: &[String],
    ) -> Result<(), BridgeError> {
        let (current, instances) = self.gcal.series(self.calendar, self.id)?;
        check_revision(self.id, &current, &instances, if_match)?;

        let held = entry(current.clone(), &instances).ical;
        let etag = if_match.and(current.etag.as_deref());
        self.gcal
            .delete(self.calendar, self.id, etag, notified(&[&held], addresses))
    }

    /// Writes the master, merged onto the server copy, then each occurrence.
    ///
    /// The merge keeps what the projection does not model. An occurrence not
    /// landing answers 412 once the rest is written, the edit staying staged;
    /// refused for good alone, [`REFUSED`], a refusal not a wait.
    pub fn update(
        &mut self,
        ical: &str,
        if_match: Option<&str>,
        addresses: &[String],
    ) -> Result<Option<String>, BridgeError> {
        let id = self.id;
        let (current, instances) = self.gcal.series(self.calendar, id)?;
        check_revision(id, &current, &instances, if_match)?;

        let base = entry(current.clone(), &instances).ical;
        // NOTE: both copies judged, so dropped guests still hear.
        let send_updates = notified(&[ical, &base], addresses);
        let pending = occurrence_changes(ical, &base)?;
        let projected = GcalEvent::from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;
        let held = GcalEvent::from_ical(base.as_bytes()).map_err(|err| err.to_string())?;

        // NOTE: an edit of occurrences alone leaves the master as it is.
        let wrote = projected != held;
        let mut written = revision(&current, &instances);
        if wrote {
            let event = projected.merge(&current);
            let updated = self.gcal.update(
                self.calendar,
                id,
                &event,
                current.etag.as_deref(),
                send_updates,
            )?;
            written = revision(&updated, &instances);
        }
        if pending.is_empty() {
            return Ok(written);
        }

        let read = (!wrote).then_some((current, instances));
        self.write_occurrences(ical, read, send_updates)
            .map_err(|err| match err.status {
                Some(REFUSED) => BridgeError {
                    message: format!("Google refuses an occurrence of event {id} for good: {err}"),
                    status: Some(REFUSED),
                },
                _ => BridgeError {
                    message: format!(
                        "Event {id} was written to Google without all its occurrences: {err}"
                    ),
                    status: Some(412),
                },
            })
    }

    /// Writes each occurrence the object holds otherwise than its series,
    /// answering the entry's revision once all landed, else the first error.
    ///
    /// The series is the one `read`, unless the master was written since,
    /// which can change its instances.
    fn write_occurrences(
        &mut self,
        ical: &str,
        read: Option<(GcalEvent, Vec<GcalEvent>)>,
        send_updates: GcalSendUpdates,
    ) -> Result<Option<String>, BridgeError> {
        let id = self.id;
        let (master, instances) = match read {
            Some(read) => read,
            None => self.gcal.series(self.calendar, id)?,
        };
        let server = entry(master.clone(), &instances).ical;

        let mut failures = Vec::new();
        for change in occurrence_changes(ical, &server)? {
            if let Err(err) = self.write_occurrence(&master, &instances, &change, send_updates) {
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
        let (master, instances) = self.gcal.series(self.calendar, id)?;
        Ok(revision(&master, &instances))
    }

    /// Writes one occurrence through its instance: among the changed and
    /// cancelled ones read with the series, else among the instances of a
    /// window around it, each told by the identity its projection gives it.
    fn write_occurrence(
        &mut self,
        master: &GcalEvent,
        instances: &[GcalEvent],
        change: &OccurrenceChange,
        send_updates: GcalSendUpdates,
    ) -> Result<(), BridgeError> {
        let (calendar, id) = (self.calendar, self.id);
        let told = |instance: &GcalEvent| {
            occurrences(&master.to_ical_series(&[instance]))
                .is_ok_and(|ids| ids.first() == Some(&change.id))
        };

        let instance = match instances.iter().find(|instance| told(instance)) {
            Some(instance) => Some(instance.clone()),
            None => {
                let (start, end) = occurrence_window(change.id);
                self.gcal
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
            OccurrenceWrite::Delete if instance.status == Some(GcalEventStatus::Cancelled) => {
                Ok(())
            }
            OccurrenceWrite::Delete => {
                match self.gcal.delete(calendar, handle, etag, send_updates) {
                    Err(err) if matches!(err.status, Some(404 | 410)) => Ok(()),
                    deleted => deleted,
                }
            }
            OccurrenceWrite::Update { staged, server } => {
                let projected =
                    GcalEvent::from_ical(staged.as_bytes()).map_err(|err| err.to_string())?;
                let held =
                    GcalEvent::from_ical(server.as_bytes()).map_err(|err| err.to_string())?;
                if projected == held {
                    return Ok(());
                }
                let event = projected.merge(&instance);
                self.gcal
                    .update(calendar, handle, &event, etag, send_updates)
                    .map(drop)
            }
        }
    }
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

/// The reads an entry is built from: a listing followed to its end, and
/// one series read whole.
impl<H: Host> Gcal<'_, H> {
    /// Every event of a calendar or one `iCalUID`, and its last `syncToken`.
    ///
    /// Pages followed, cancelled ones included: that is how Google removes an
    /// occurrence. The parameters never change but for the token, which Google
    /// answers only to the listing shape that issued it.
    fn listing(
        &mut self,
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
            let page = match self.events(calendar, &params)? {
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
    fn series(
        &mut self,
        calendar: &str,
        id: &str,
    ) -> Result<(GcalEvent, Vec<GcalEvent>), BridgeError> {
        let master = self.event(calendar, id)?;
        if master.recurrence.is_empty() {
            return Ok((master, Vec::new()));
        }
        let Some(uid) = master.ical_uid.clone() else {
            return Ok((master, Vec::new()));
        };

        let instances = self
            .listing(calendar, Some(&uid), None)?
            .map_err(|err| coroutine_error(&err))?
            .0
            .into_iter()
            .filter(|event| event.recurring_event_id.as_deref() == Some(id))
            .collect();

        Ok((master, instances))
    }

    /// Reads one changed series again, whole, by its `iCalUID` listing (the
    /// master and every instance at once); [`None`] when it is gone.
    ///
    /// Without an `iCalUID`, or when the listing leaves the master out, it
    /// is read as a fetch reads it: the master, then its instances.
    fn series_by_uid(
        &mut self,
        calendar: &str,
        id: &str,
        uid: Option<&str>,
    ) -> Result<Option<(GcalEvent, Vec<GcalEvent>)>, BridgeError> {
        if let Some(uid) = uid {
            let (events, _) = self
                .listing(calendar, Some(uid), None)?
                .map_err(|err| coroutine_error(&err))?;
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

        match self.series(calendar, id) {
            Ok((master, instances)) => Ok(standing(master).map(|master| (master, instances))),
            Err(err) if matches!(err.status, Some(404 | 410)) => Ok(None),
            Err(err) => Err(err),
        }
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

/// An io-gcal calendar list entry as a [`Calendar`], [`None`] without id.
///
/// The primary calendar is the account's default; one the user only reads
/// (`reader`, `freeBusyReader`) is not writable.
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
        vtodo: None,
    })
}

#[cfg(test)]
mod tests;
