//! # Microsoft Graph calendars
//!
//! The calendar verbs of the [`Msgraph`] backend: the user's calendars as
//! collections, their events as iCalendar through io-msgraph's `ical`
//! projection.
//!
//! Graph's event delta runs only over a time window, which an event
//! leaving would read as deleted. So each pass lists a calendar's lone
//! events and series masters in full, at the master's `changeKey`,
//! removals told by absence; a series reads with its range's exceptions.
//!
//! An entry is read by `$batch`, 20 requests a call: the events, then each
//! series' instances over each window, sparing a first sync hundreds of
//! requests. One the batch could not serve goes again alone, where the
//! transport rides out throttling; an event gone by then is left out.
//!
//! Graph has no conditional event write: the staged revision is checked
//! right before it, a moved event answering 412 as on CalDAV. A series is
//! written as its master, then each occurrence held otherwise as its
//! instance: patched, deleted, or patched back, Graph having no reset.

use std::collections::BTreeMap;

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::{
    coroutine::*,
    v1::{
        rest::users::{
            calendars::{
                MsgraphCalendar,
                list::{
                    MsgraphCalendarsList, MsgraphCalendarsListParams, MsgraphCalendarsListResponse,
                },
            },
            events::{
                MsgraphEvent, MsgraphEventType,
                delete::MsgraphEventDelete,
                get::MsgraphEventGet,
                ical::{MSGRAPH_EVENT_ICAL_SELECT, MSGRAPH_EVENT_STASH_EXPAND},
                instances::MsgraphEventInstances,
                list::{MsgraphEventsList, MsgraphEventsListParams, MsgraphEventsListResponse},
                update::MsgraphEventUpdate,
            },
        },
        send::{MsgraphSend, MsgraphSendError, MsgraphSendOutput},
    },
};
use jiff::{Timestamp, ToSpan, civil::Date, tz::TimeZone};
use serde_json::{Map, Value, to_value};
use sha2::{Digest, Sha256};
use url::Url;

use crate::{
    calendar::{
        Calendar, Event, EventRef,
        occurrence::{
            OccurrenceChange, OccurrenceWrite, occurrence_changes, occurrence_window, occurrences,
        },
    },
    client::{
        Host,
        msgraph::{Msgraph, alone, batched, graph_url, parse_graph_url},
    },
    error::{BridgeError, REFUSED, parts_failure},
    offline::default_role,
};

/// The `$select` of the enumeration: an event's identity, kind and
/// revision.
const LIST_SELECT: &str = "id,changeKey,type,seriesMasterId";

/// The page size asked for when listing events.
const PAGE_SIZE: u32 = 500;

/// How far either side of today a series is searched for exceptions.
const SERIES_YEARS: i16 = 5;

/// The longest window Graph lists a series' instances over.
const MAX_WINDOW_YEARS: i16 = 5;

impl<H: Host> Msgraph<'_, H> {
    /// Lists the user's calendars. Collection URLs are left empty: the
    /// caller composes them, only it knowing the account they belong to.
    pub fn list_calendars(&mut self) -> Result<Vec<Calendar>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let params = MsgraphCalendarsListParams {
            top: Some(100),
            ..Default::default()
        };

        let coroutine =
            MsgraphCalendarsList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
        let mut page = self.run(coroutine)?;

        let mut calendars = Vec::new();
        loop {
            calendars.extend(page.value.into_iter().filter_map(graph_calendar));

            let Some(next) = page.next_link else {
                break;
            };
            let url = parse_graph_url(&next)?;
            page = self.run(MsgraphSend::<MsgraphCalendarsListResponse>::get(&auth, url))?;
        }

        Ok(calendars)
    }

    /// Enumerates a calendar's lone events and series masters, in full,
    /// each named by its Graph id at its `changeKey`.
    pub fn list_events(&mut self, calendar: &str) -> Result<Vec<EventRef>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let params = MsgraphEventsListParams {
            top: Some(PAGE_SIZE),
            select: Some(LIST_SELECT),
            ..Default::default()
        };

        let coroutine = MsgraphEventsList::new(&auth, "me", Some(calendar), &params)
            .map_err(|err| err.to_string())?;
        let events = self.event_pages(coroutine)?;

        Ok(unique(events)?
            .into_iter()
            .filter(|event| !event.id.is_empty() && event.series_master_id.is_none())
            .map(|event| EventRef {
                id: event.id,
                etag: event.change_key,
            })
            .collect())
    }

    /// The iCalendar objects of the named events, each at its master's
    /// `changeKey`, read by `$batch` ([`Self::read_entries`]).
    pub fn read_events(&mut self, ids: &[&str]) -> Result<Vec<Event>, BridgeError> {
        let today = Timestamp::now().to_zoned(TimeZone::UTC).date();

        self.read_entries(ids, today)
    }

    /// Files an iCalendar object as an event, answering its id and revision.
    ///
    /// A `transactionId` ([`create_body`]) lands a resent create on the
    /// first's event, not a second inviting twice. Read back with its
    /// stash: one whose UID was lost (a second event elsewhere) is deleted.
    pub fn create_event(&mut self, calendar: &str, ical: &str) -> Result<EventRef, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let event =
            MsgraphEvent::create_from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;
        let uid = event.stashed_uid();

        let url = graph_url(&format!("me/calendars/{calendar}/events"))?;
        let coroutine =
            MsgraphSend::<MsgraphEvent>::post_json(&auth, url, &create_body(&event, ical))
                .map_err(|err| err.to_string())?;
        let created = self.run(coroutine)?;
        let stored = self.fetch_event(&created.id)?;

        if uid.is_some() && stored.stashed_uid() != uid {
            let coroutine =
                MsgraphEventDelete::new(&auth, "me", &created.id).map_err(|err| err.to_string())?;
            if let Err(err) = self.run(coroutine) {
                log::warn!(
                    "cannot delete event {} that lost its UID: {err}",
                    created.id
                );
            }
            return Err(format!(
                "Graph did not keep the UID of the event created in {calendar}, so it would \
                 read back as another event"
            )
            .into());
        }

        Ok(EventRef {
            id: stored.id,
            etag: stored.change_key,
        })
    }

    /// Writes an entry from an iCalendar object: its master, and each
    /// occurrence the object holds otherwise than Graph
    /// ([`GraphEntry::update`]).
    pub fn update_event(
        &mut self,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        GraphEntry {
            msgraph: Msgraph {
                client: self.client,
                token: self.token,
            },
            id,
        }
        .update(ical, if_match)
    }

    /// Deletes an event, the whole series for a master, conditionally on
    /// `if_match`.
    pub fn delete_event(&mut self, id: &str, if_match: Option<&str>) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        if if_match.is_some() {
            let current = self.fetch_event(id)?;
            check_revision(id, &current, if_match)?;
        }

        let coroutine = MsgraphEventDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run(coroutine)?;

        Ok(())
    }

    /// Reads one event with everything the projection reads, its stash
    /// expanded.
    fn fetch_event(&mut self, id: &str) -> Result<MsgraphEvent, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = MsgraphEventGet::new(
            &auth,
            "me",
            id,
            Some(MSGRAPH_EVENT_ICAL_SELECT),
            Some(MSGRAPH_EVENT_STASH_EXPAND),
        )
        .map_err(|err| err.to_string())?;

        self.run(coroutine)
    }

    /// Reads one entry: the event, and the exceptions of a series.
    fn fetch_series(&mut self, id: &str) -> Result<(MsgraphEvent, Vec<MsgraphEvent>), BridgeError> {
        let master = self.fetch_event(id)?;
        if master.event_type != Some(MsgraphEventType::SeriesMaster) {
            return Ok((master, Vec::new()));
        }

        let today = self.client.host.now().to_zoned(TimeZone::UTC).date();
        let Some(windows) = series_windows(&master, today) else {
            log::warn!("series {id} has no readable range, its exceptions are skipped");
            return Ok((master, Vec::new()));
        };
        let auth = HttpAuthBearer::new(self.token);

        let params = MsgraphEventsListParams {
            top: Some(PAGE_SIZE),
            // NOTE: an exception needs its originalStart for its
            // RECURRENCE-ID, which the default listing leaves out.
            select: Some(MSGRAPH_EVENT_ICAL_SELECT),
            ..Default::default()
        };

        let mut exceptions = Vec::new();
        for (start, end) in windows {
            let coroutine = MsgraphEventInstances::new(&auth, "me", id, &start, &end, &params)
                .map_err(|err| err.to_string())?;
            exceptions.extend(
                self.event_pages(coroutine)?
                    .into_iter()
                    .filter(|event| event.event_type == Some(MsgraphEventType::Exception)),
            );
        }

        Ok((master, unique(exceptions)?))
    }

    /// Every event of a listing, its paging links followed.
    fn event_pages<C>(&mut self, coroutine: C) -> Result<Vec<MsgraphEvent>, BridgeError>
    where
        C: MsgraphCoroutine<
                Yield = MsgraphYield,
                Return = Result<MsgraphSendOutput<MsgraphEventsListResponse>, MsgraphSendError>,
            >,
    {
        let auth = HttpAuthBearer::new(self.token);
        let mut page = self.run(coroutine)?;

        let mut events = Vec::new();
        loop {
            events.extend(page.value);

            let Some(next) = page.next_link else {
                break;
            };
            let url = parse_graph_url(&next)?;
            page = self.run(MsgraphSend::<MsgraphEventsListResponse>::get(&auth, url))?;
        }

        Ok(events)
    }
}

/// The requests an entry read or write sends beside the `$batch` and the
/// event read: a page of a listing, a window of a series' instances, and
/// one event patched or deleted.
impl<H: Host> Msgraph<'_, H> {
    /// Lists the instances of a series between two instants, every page.
    fn instances(
        &mut self,
        id: &str,
        start: &str,
        end: &str,
    ) -> Result<Vec<MsgraphEvent>, BridgeError> {
        let params = MsgraphEventsListParams {
            top: Some(PAGE_SIZE),
            // NOTE: an instance is told by its originalStart, which the
            // default listing leaves out.
            select: Some(MSGRAPH_EVENT_ICAL_SELECT),
            ..Default::default()
        };
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = MsgraphEventInstances::new(&auth, "me", id, start, end, &params)
            .map_err(|err| err.to_string())?;
        self.event_pages(coroutine)
    }

    /// Patches one event, answering it as patched.
    fn patch_event(&mut self, id: &str, patch: &MsgraphEvent) -> Result<MsgraphEvent, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine =
            MsgraphEventUpdate::new(&auth, "me", id, patch).map_err(|err| err.to_string())?;
        self.run(coroutine)
    }

    /// Deletes one event.
    fn remove_event(&mut self, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = MsgraphEventDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run(coroutine)?;
        Ok(())
    }

    /// Reads one page of an event listing from its absolute URL.
    fn event_page(&mut self, url: &str) -> Result<MsgraphEventsListResponse, BridgeError> {
        let url = parse_graph_url(url)?;
        let auth = HttpAuthBearer::new(self.token);
        self.run(MsgraphSend::<MsgraphEventsListResponse>::get(&auth, url))
    }
}

/// One Graph entry as its writes address it: a series or a lone event.
pub struct GraphEntry<'w, H> {
    /// The Graph API the writes go through.
    pub msgraph: Msgraph<'w, H>,
    /// The id of the entry's master.
    pub id: &'w str,
}

impl<H: Host> GraphEntry<'_, H> {
    /// Writes the master, patched from the server copy, then each occurrence.
    ///
    /// The patch is what changed against the copy `if_match` checked. An
    /// occurrence not landing answers 412 once the rest is written, the edit
    /// staying staged; refused for good alone, [`REFUSED`], not a wait.
    pub fn update(
        &mut self,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let id = self.id;
        let (current, exceptions) = self.msgraph.fetch_series(id)?;
        check_revision(id, &current, if_match)?;

        let held: Vec<&MsgraphEvent> = exceptions.iter().collect();
        let base = current.to_ical_series(&held);
        let patch = MsgraphEvent::update_from_ical(ical.as_bytes(), base.as_bytes())
            .map_err(|err| err.to_string())?;
        let pending = occurrence_changes(ical, &base)?;

        // NOTE: an edit of occurrences alone leaves the master as it is.
        let wrote = patch != MsgraphEvent::default();
        let mut change_key = current.change_key.clone();
        if wrote {
            change_key = self.msgraph.patch_event(id, &patch)?.change_key;
        }
        if pending.is_empty() {
            return Ok(change_key);
        }

        let read = (!wrote).then_some((current, exceptions));
        self.write_occurrences(ical, read)
            .map_err(|err| match err.status {
                Some(REFUSED) => BridgeError {
                    message: format!("Graph refuses an occurrence of event {id} for good: {err}"),
                    status: Some(REFUSED),
                },
                _ => BridgeError {
                    message: format!(
                        "Event {id} was written to Graph without all its occurrences: {err}"
                    ),
                    status: Some(412),
                },
            })
    }

    /// Writes each occurrence the object holds otherwise than its series,
    /// answering the master's revision once all landed, else the first error.
    ///
    /// The series is the one `read`, unless the master was written since:
    /// Exchange discards the exceptions when a series' pattern moves.
    fn write_occurrences(
        &mut self,
        ical: &str,
        read: Option<(MsgraphEvent, Vec<MsgraphEvent>)>,
    ) -> Result<Option<String>, BridgeError> {
        let id = self.id;
        let (master, exceptions) = match read {
            Some(read) => read,
            None => self.msgraph.fetch_series(id)?,
        };
        let held: Vec<&MsgraphEvent> = exceptions.iter().collect();
        let server = master.to_ical_series(&held);

        let mut failures = Vec::new();
        for change in occurrence_changes(ical, &server)? {
            if let Err(err) = self.write_occurrence(&master, &exceptions, &change) {
                log::warn!(
                    "cannot write occurrence {} of event {id}: {err}",
                    change.stamp()
                );
                failures.push(err);
            }
        }

        match parts_failure(failures) {
            None => Ok(self.msgraph.fetch_event(id)?.change_key),
            Some(err) => Err(err),
        }
    }

    /// Writes one occurrence through its instance: among the exceptions read
    /// with the series, else among the instances of a window around it, each
    /// told by the identity its projection gives it.
    fn write_occurrence(
        &mut self,
        master: &MsgraphEvent,
        exceptions: &[MsgraphEvent],
        change: &OccurrenceChange,
    ) -> Result<(), BridgeError> {
        let id = self.id;
        let told = |instance: &MsgraphEvent| {
            occurrences(&master.to_ical_series(&[instance]))
                .is_ok_and(|ids| ids.first() == Some(&change.id))
        };

        let instance = match exceptions.iter().find(|instance| told(instance)) {
            Some(instance) => Some(instance.id.clone()),
            None => {
                let (start, end) = occurrence_window(change.id);
                self.msgraph
                    .instances(id, &start, &end)?
                    .into_iter()
                    .find(|instance| told(instance))
                    .map(|instance| instance.id)
            }
        };

        // NOTE: an occurrence with no instance is gone already, which is all
        // a removal asks, or an override of no occurrence the series has,
        // which Graph has nowhere to keep.
        let Some(instance) = instance else {
            if change.write != OccurrenceWrite::Delete {
                log::warn!(
                    "occurrence {} of event {id} is no instance on Graph, left out",
                    change.stamp()
                );
            }
            return Ok(());
        };

        match &change.write {
            OccurrenceWrite::Delete => match self.msgraph.remove_event(&instance) {
                Err(err) if err.status == Some(404) => Ok(()),
                deleted => deleted,
            },
            OccurrenceWrite::Update { staged, server } => {
                let patch =
                    MsgraphEvent::instance_update_from_ical(staged.as_bytes(), server.as_bytes())
                        .map_err(|err| err.to_string())?;
                match patch {
                    Some(patch) => self.msgraph.patch_event(&instance, &patch).map(drop),
                    None => Ok(()),
                }
            }
        }
    }
}

/// An io-msgraph calendar as a [`Calendar`], [`None`] without id.
///
/// `isDefaultCalendar` makes it the account's default, and `canEdit`
/// false (a shared calendar read only, a holidays one) not writable.
fn graph_calendar(calendar: MsgraphCalendar) -> Option<Calendar> {
    if calendar.id.is_empty() {
        return None;
    }

    Some(Calendar {
        name: calendar
            .name
            .as_option()
            .cloned()
            .unwrap_or_else(|| calendar.id.clone()),
        id: calendar.id,
        url: String::new(),
        description: None,
        color: calendar.hex_color.filter(|color| !color.is_empty()),
        role: default_role(calendar.is_default_calendar == Some(true)),
        writable: calendar.can_edit != Some(false),
    })
}

/// The body of an event create: the event, and a `transactionId` making
/// Graph answer a second create with the event the first made.
///
/// It is the object's digest, the same for every attempt of one create.
fn create_body(event: &MsgraphEvent, ical: &str) -> Value {
    let mut body = to_value(event).unwrap_or_else(|_| Value::Object(Map::new()));
    if let Value::Object(fields) = &mut body {
        let digest = Sha256::digest(ical.as_bytes());
        let id: String = digest[..16]
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        fields.insert("transactionId".into(), Value::String(id));
    }
    body
}

/// The address one event is read at: the read [`MsgraphEventGet`] sends.
fn event_url(id: &str) -> Result<Url, BridgeError> {
    let mut url = graph_url(&format!("me/events/{id}"))?;
    url.query_pairs_mut()
        .append_pair("$select", MSGRAPH_EVENT_ICAL_SELECT)
        .append_pair("$expand", MSGRAPH_EVENT_STASH_EXPAND);
    Ok(url)
}

/// The address of a series' instances over one window: the first page
/// [`MsgraphEventInstances`] asks for.
fn instances_url(id: &str, start: &str, end: &str) -> Result<Url, BridgeError> {
    let mut url = graph_url(&format!("me/events/{id}/instances"))?;
    url.query_pairs_mut()
        .append_pair("startDateTime", start)
        .append_pair("endDateTime", end)
        .append_pair("$top", &PAGE_SIZE.to_string())
        // NOTE: an exception needs its originalStart for its
        // RECURRENCE-ID, which the default listing leaves out.
        .append_pair("$select", MSGRAPH_EVENT_ICAL_SELECT);
    Ok(url)
}

/// The entry read, every request riding a `$batch`.
impl<H: Host> Msgraph<'_, H> {
    /// Reads the named entries: each event, and each series' exceptions over
    /// its windows ([`series_windows`]), every request riding a `$batch`.
    ///
    /// A reply the batch could not serve is sent again alone. An event gone
    /// (404 on it or its instances) is left out; any other failure fails.
    fn read_entries(&mut self, ids: &[&str], today: Date) -> Result<Vec<Event>, BridgeError> {
        let urls = ids
            .iter()
            .map(|id| event_url(id))
            .collect::<Result<Vec<_>, _>>()?;

        let mut masters: Vec<Option<MsgraphEvent>> = Vec::with_capacity(ids.len());
        for (index, reply) in batched(|requests| self.batch(requests), &urls)?
            .into_iter()
            .enumerate()
        {
            let read = match reply {
                Some(reply) if reply.status == 404 => None,
                Some(reply) if (200..300).contains(&reply.status) => {
                    match reply.parse::<MsgraphEvent>() {
                        Ok(event) => Some(event),
                        Err(_) => alone(self.fetch_event(ids[index]))?,
                    }
                }
                _ => alone(self.fetch_event(ids[index]))?,
            };
            masters.push(read);
        }

        // NOTE: one job per window of every series, kept in window order, so
        // the exceptions are gathered as the one-by-one read gathered them.
        let mut jobs: Vec<(usize, Url)> = Vec::new();
        for (index, master) in masters.iter().enumerate() {
            let Some(master) = master else { continue };
            if master.event_type != Some(MsgraphEventType::SeriesMaster) {
                continue;
            }
            let Some(windows) = series_windows(master, today) else {
                log::warn!(
                    "series {} has no readable range, its exceptions are skipped",
                    ids[index]
                );
                continue;
            };
            for (start, end) in windows {
                jobs.push((index, instances_url(ids[index], &start, &end)?));
            }
        }

        let urls: Vec<Url> = jobs.iter().map(|(_, url)| url.clone()).collect();
        let mut instances: Vec<Vec<MsgraphEvent>> = Vec::with_capacity(ids.len());
        instances.resize_with(ids.len(), Vec::new);
        let mut gone = vec![false; ids.len()];
        for ((index, url), reply) in jobs
            .iter()
            .zip(batched(|requests| self.batch(requests), &urls)?)
        {
            if gone[*index] {
                continue;
            }
            let page = match reply {
                Some(reply) if reply.status == 404 => None,
                Some(reply) if (200..300).contains(&reply.status) => {
                    match reply.parse::<MsgraphEventsListResponse>() {
                        Ok(page) => Some(page),
                        Err(_) => alone(self.event_page(url.as_str()))?,
                    }
                }
                _ => alone(self.event_page(url.as_str()))?,
            };
            let Some(mut page) = page else {
                gone[*index] = true;
                continue;
            };
            loop {
                instances[*index].extend(page.value);
                let Some(next) = page.next_link else {
                    break;
                };
                page = self.event_page(&next)?;
            }
        }

        let mut events = Vec::with_capacity(ids.len());
        for (index, (master, instances)) in masters.into_iter().zip(instances).enumerate() {
            let Some(master) = master else { continue };
            if gone[index] {
                continue;
            }
            let exceptions = unique(
                instances
                    .into_iter()
                    .filter(|event| event.event_type == Some(MsgraphEventType::Exception))
                    .collect(),
            )?;
            let exceptions: Vec<&MsgraphEvent> = exceptions.iter().collect();
            events.push(Event {
                id: master.id.clone(),
                etag: master.change_key.clone(),
                ical: master.to_ical_series(&exceptions),
            });
        }

        Ok(events)
    }
}

/// Drops the events a page boundary repeated, Graph overlapping
/// consecutive `nextLink` pages; one id read at two revisions is an
/// error.
fn unique(events: Vec<MsgraphEvent>) -> Result<Vec<MsgraphEvent>, BridgeError> {
    let mut seen = BTreeMap::new();
    let mut unique = Vec::with_capacity(events.len());

    for event in events {
        match seen.get(&event.id) {
            None => {
                seen.insert(event.id.clone(), event.change_key.clone());
                unique.push(event);
            }
            Some(change_key) if *change_key == event.change_key => {}
            Some(_) => {
                return Err(format!(
                    "Event {} listed twice at different revisions by Graph",
                    event.id
                )
                .into());
            }
        }
    }

    Ok(unique)
}

/// The windows a series' exceptions are read in, each at most five years.
///
/// Graph answers 400 past that. A series is read within [`SERIES_YEARS`] of
/// `today`, none if it ended before: a 1604 birthday (Outlook's yearless
/// year) or a 1601-to-9000 invitation would cost thousands of requests.
fn series_windows(master: &MsgraphEvent, today: Date) -> Option<Vec<(String, String)>> {
    let (start, end) = master.recurrence.as_option()?.bounds()?;
    let years = SERIES_YEARS.years();
    let floor = today.checked_sub(years).ok()?;
    let ceiling = today.checked_add(years).ok()?;
    let end = match end {
        Some(end) => end.min(ceiling).checked_add(1.day()).ok()?,
        None => ceiling,
    };
    let start = start.max(floor);

    let instant = |date: Date| -> Option<String> {
        let timestamp = date.to_zoned(TimeZone::UTC).ok()?.timestamp();
        Some(timestamp.strftime("%Y-%m-%dT%H:%M:%SZ").to_string())
    };

    let mut windows = Vec::new();
    let mut from = start;
    while from < end {
        let longest = from
            .checked_add(MAX_WINDOW_YEARS.years())
            .ok()?
            .checked_sub(1.day())
            .ok()?;
        let to = longest.min(end);
        windows.push((instant(from)?, instant(to)?));
        from = to;
    }

    Some(windows)
}

/// Refuses a write when the event moved on Graph since `if_match`, with
/// the status a CalDAV server answers a failed precondition with.
fn check_revision(
    id: &str,
    current: &MsgraphEvent,
    if_match: Option<&str>,
) -> Result<(), BridgeError> {
    match if_match {
        Some(expected) if current.change_key.as_deref() != Some(expected) => Err(BridgeError {
            message: format!("Event {id} changed on Graph since it was read"),
            status: Some(412),
        }),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests;
