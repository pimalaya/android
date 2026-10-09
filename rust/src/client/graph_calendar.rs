//! Microsoft Graph calendars: the user's calendars as collections, their
//! events as iCalendar through io-msgraph's `ical` projection.
//!
//! Graph's event delta only runs over a calendar view, a time window, and
//! an event leaving a sliding window would read as deleted. So the
//! enumeration lists a calendar's lone events and series masters in full
//! every pass, the master's `changeKey` as the revision; a complete
//! listing reports removals by absence. A series is one entry, read with
//! the exceptions of its own date range.
//!
//! An entry is read by `$batch`, 20 requests a call: the events first,
//! then the first page of each series' instances over each of its
//! windows. One request an event and one more per window of a series was
//! what a first sync waited on, about 190 a hundred events on the owner's
//! test tenant; a request the batch could not serve (throttled inside it,
//! say) is sent again on its own, where the transport rides out the
//! throttling. An event gone by the time it is read is left out, as a
//! `calendar-multiget` leaves out a resource it no longer finds.
//!
//! Graph has no conditional write for events, so the revision an edit was
//! staged against is checked against the server's right before the write,
//! and a moved event answers 412 like a CalDAV server would. A series is
//! written as its master, then each occurrence the object holds otherwise
//! as its instance: patched from an override, deleted for an `EXDATE` or
//! a cancelled override, patched back to the series for an override the
//! edit removed, Graph having no reset of an exception.

use std::collections::BTreeMap;

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::{
    coroutine::*,
    v1::{
        rest::batch::{MsgraphBatch, MsgraphBatchRequest, MsgraphBatchResponse},
        rest::users::{
            calendars::{
                MsgraphCalendar,
                list::{
                    MsgraphCalendarsList, MsgraphCalendarsListParams, MsgraphCalendarsListResponse,
                },
            },
            events::{
                MsgraphEvent, MsgraphEventType,
                create::MsgraphEventCreate,
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
use url::Url;

use crate::{
    calendar::{
        OccurrenceChange, OccurrenceWrite, occurrence_changes, occurrence_window, occurrences,
    },
    client::{
        Client,
        convert::{REFUSED, parts_failure},
        graph::{alone, batched, graph_url, parse_graph_url},
    },
    types::{BridgeError, Calendar, Event, EventRef, default_role},
};

/// The `$select` of the enumeration: an event's identity, kind and
/// revision.
const LIST_SELECT: &str = "id,changeKey,type,seriesMasterId";

/// The page size asked for when listing events.
const PAGE_SIZE: u32 = 500;

/// How far either side of today an open-ended series is searched for
/// exceptions.
const OPEN_SERIES_YEARS: i16 = 5;

/// The longest window Graph lists a series' instances over.
const MAX_WINDOW_YEARS: i16 = 5;

impl<'a, 'local> Client<'a, 'local> {
    /// Lists the user's calendars. Collection URLs are left empty: the
    /// caller composes them, only it knowing the account they belong to.
    pub fn list_graph_calendars(&mut self, token: &str) -> Result<Vec<Calendar>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let params = MsgraphCalendarsListParams {
            top: Some(100),
            ..Default::default()
        };

        let coroutine =
            MsgraphCalendarsList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
        let mut page = self.run_msgraph(coroutine)?;

        let mut calendars = Vec::new();
        loop {
            calendars.extend(page.value.into_iter().filter_map(graph_calendar));

            let Some(next) = page.next_link else {
                break;
            };
            let url = parse_graph_url(&next)?;
            page =
                self.run_msgraph(MsgraphSend::<MsgraphCalendarsListResponse>::get(&auth, url))?;
        }

        Ok(calendars)
    }

    /// Enumerates a calendar's lone events and series masters, in full,
    /// each named by its Graph id at its `changeKey`.
    pub fn list_graph_events(
        &mut self,
        token: &str,
        calendar: &str,
    ) -> Result<Vec<EventRef>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let params = MsgraphEventsListParams {
            top: Some(PAGE_SIZE),
            select: Some(LIST_SELECT),
            ..Default::default()
        };

        let coroutine = MsgraphEventsList::new(&auth, "me", Some(calendar), &params)
            .map_err(|err| err.to_string())?;
        let events = self.graph_event_pages(&auth, coroutine)?;

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
    /// `changeKey`, read by `$batch` ([`read_entries`]).
    pub fn read_graph_events(
        &mut self,
        token: &str,
        ids: &[&str],
    ) -> Result<Vec<Event>, BridgeError> {
        let mut reads = GraphCalls {
            client: self,
            auth: HttpAuthBearer::new(token),
        };
        let today = Timestamp::now().to_zoned(TimeZone::UTC).date();

        read_entries(&mut reads, ids, today)
    }

    /// Creates an event from an iCalendar object in a calendar, answering
    /// the id Graph gave it and its revision.
    ///
    /// The event is read back with its stash: one whose UID did not
    /// survive would read back under Graph's own `iCalUId`, a second event
    /// for every other client, so it is deleted again and the write
    /// refused.
    pub fn create_graph_event(
        &mut self,
        token: &str,
        calendar: &str,
        ical: &str,
    ) -> Result<EventRef, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let event =
            MsgraphEvent::create_from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;
        let uid = event.stashed_uid();

        let coroutine = MsgraphEventCreate::new(&auth, "me", Some(calendar), &event)
            .map_err(|err| err.to_string())?;
        let created = self.run_msgraph(coroutine)?;
        let stored = self.graph_event(&auth, &created.id)?;

        if uid.is_some() && stored.stashed_uid() != uid {
            let coroutine =
                MsgraphEventDelete::new(&auth, "me", &created.id).map_err(|err| err.to_string())?;
            if let Err(err) = self.run_msgraph(coroutine) {
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
    /// occurrence the object holds otherwise than Graph ([`update_entry`]).
    pub fn update_graph_event(
        &mut self,
        token: &str,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let mut writes = GraphCalls {
            client: self,
            auth: HttpAuthBearer::new(token),
        };

        update_entry(&mut writes, id, ical, if_match)
    }

    /// Deletes an event, the whole series for a master, conditionally on
    /// `if_match`.
    pub fn delete_graph_event(
        &mut self,
        token: &str,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        if if_match.is_some() {
            let current = self.graph_event(&auth, id)?;
            check_revision(id, &current, if_match)?;
        }

        let coroutine = MsgraphEventDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;

        Ok(())
    }

    /// Reads one event with everything the projection reads, its stash
    /// expanded.
    fn graph_event(
        &mut self,
        auth: &HttpAuthBearer,
        id: &str,
    ) -> Result<MsgraphEvent, BridgeError> {
        let coroutine = MsgraphEventGet::new(
            auth,
            "me",
            id,
            Some(MSGRAPH_EVENT_ICAL_SELECT),
            Some(MSGRAPH_EVENT_STASH_EXPAND),
        )
        .map_err(|err| err.to_string())?;

        self.run_msgraph(coroutine)
    }

    /// Reads one entry: the event, and the exceptions of a series.
    fn graph_series(
        &mut self,
        auth: &HttpAuthBearer,
        id: &str,
    ) -> Result<(MsgraphEvent, Vec<MsgraphEvent>), BridgeError> {
        let master = self.graph_event(auth, id)?;
        if master.event_type != Some(MsgraphEventType::SeriesMaster) {
            return Ok((master, Vec::new()));
        }

        let today = Timestamp::now().to_zoned(TimeZone::UTC).date();
        let Some(windows) = series_windows(&master, today) else {
            log::warn!("series {id} has no readable range, its exceptions are skipped");
            return Ok((master, Vec::new()));
        };

        let params = MsgraphEventsListParams {
            top: Some(PAGE_SIZE),
            // NOTE: an exception needs its originalStart for its
            // RECURRENCE-ID, which the default listing leaves out.
            select: Some(MSGRAPH_EVENT_ICAL_SELECT),
            ..Default::default()
        };

        let mut exceptions = Vec::new();
        for (start, end) in windows {
            let coroutine = MsgraphEventInstances::new(auth, "me", id, &start, &end, &params)
                .map_err(|err| err.to_string())?;
            exceptions.extend(
                self.graph_event_pages(auth, coroutine)?
                    .into_iter()
                    .filter(|event| event.event_type == Some(MsgraphEventType::Exception)),
            );
        }

        Ok((master, unique(exceptions)?))
    }

    /// Every event of a listing, its paging links followed.
    fn graph_event_pages<C>(
        &mut self,
        auth: &HttpAuthBearer,
        coroutine: C,
    ) -> Result<Vec<MsgraphEvent>, BridgeError>
    where
        C: MsgraphCoroutine<
                Yield = MsgraphYield,
                Return = Result<MsgraphSendOutput<MsgraphEventsListResponse>, MsgraphSendError>,
            >,
    {
        let mut page = self.run_msgraph(coroutine)?;

        let mut events = Vec::new();
        loop {
            events.extend(page.value);

            let Some(next) = page.next_link else {
                break;
            };
            let url = parse_graph_url(&next)?;
            page = self.run_msgraph(MsgraphSend::<MsgraphEventsListResponse>::get(auth, url))?;
        }

        Ok(events)
    }
}

/// The three requests an entry read sends, apart so the read can run over
/// a fake: a `$batch`, one event on its own, one page of a listing.
pub(super) trait GraphReads {
    /// Sends one `$batch` of at most
    /// [`MSGRAPH_BATCH_MAX_REQUESTS`](io_msgraph::v1::rest::batch::MSGRAPH_BATCH_MAX_REQUESTS).
    fn batch(
        &mut self,
        requests: &[MsgraphBatchRequest],
    ) -> Result<Vec<MsgraphBatchResponse>, BridgeError>;

    /// Reads one event, everything the projection reads.
    fn event(&mut self, id: &str) -> Result<MsgraphEvent, BridgeError>;

    /// Reads one page of a listing from its absolute URL.
    fn page(&mut self, url: &str) -> Result<MsgraphEventsListResponse, BridgeError>;
}

/// The reads of one native call, over its transport.
struct GraphCalls<'c, 'a, 'local> {
    client: &'c mut Client<'a, 'local>,
    auth: HttpAuthBearer,
}

impl GraphReads for GraphCalls<'_, '_, '_> {
    fn batch(
        &mut self,
        requests: &[MsgraphBatchRequest],
    ) -> Result<Vec<MsgraphBatchResponse>, BridgeError> {
        let coroutine = MsgraphBatch::new(&self.auth, requests).map_err(|err| err.to_string())?;
        Ok(self.client.run_msgraph(coroutine)?.responses)
    }

    fn event(&mut self, id: &str) -> Result<MsgraphEvent, BridgeError> {
        self.client.graph_event(&self.auth, id)
    }

    fn page(&mut self, url: &str) -> Result<MsgraphEventsListResponse, BridgeError> {
        let url = parse_graph_url(url)?;
        self.client
            .run_msgraph(MsgraphSend::<MsgraphEventsListResponse>::get(
                &self.auth, url,
            ))
    }
}

/// The requests an entry write sends, apart so the write can run over a
/// fake: the entry read whole, its master alone, a window of its
/// instances, and one event patched or deleted.
pub(super) trait GraphWrites {
    /// Reads one entry: the event, and the exceptions of a series.
    fn series(&mut self, id: &str) -> Result<(MsgraphEvent, Vec<MsgraphEvent>), BridgeError>;

    /// Reads one event, everything the projection reads.
    fn master(&mut self, id: &str) -> Result<MsgraphEvent, BridgeError>;

    /// Lists the instances of a series between two instants, every page.
    fn instances(
        &mut self,
        id: &str,
        start: &str,
        end: &str,
    ) -> Result<Vec<MsgraphEvent>, BridgeError>;

    /// Patches one event, answering it as patched.
    fn update(&mut self, id: &str, patch: &MsgraphEvent) -> Result<MsgraphEvent, BridgeError>;

    /// Deletes one event.
    fn delete(&mut self, id: &str) -> Result<(), BridgeError>;
}

impl GraphWrites for GraphCalls<'_, '_, '_> {
    fn series(&mut self, id: &str) -> Result<(MsgraphEvent, Vec<MsgraphEvent>), BridgeError> {
        self.client.graph_series(&self.auth, id)
    }

    fn master(&mut self, id: &str) -> Result<MsgraphEvent, BridgeError> {
        self.client.graph_event(&self.auth, id)
    }

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
        let coroutine = MsgraphEventInstances::new(&self.auth, "me", id, start, end, &params)
            .map_err(|err| err.to_string())?;
        self.client.graph_event_pages(&self.auth, coroutine)
    }

    fn update(&mut self, id: &str, patch: &MsgraphEvent) -> Result<MsgraphEvent, BridgeError> {
        let coroutine =
            MsgraphEventUpdate::new(&self.auth, "me", id, patch).map_err(|err| err.to_string())?;
        self.client.run_msgraph(coroutine)
    }

    fn delete(&mut self, id: &str) -> Result<(), BridgeError> {
        let coroutine =
            MsgraphEventDelete::new(&self.auth, "me", id).map_err(|err| err.to_string())?;
        self.client.run_msgraph(coroutine)?;
        Ok(())
    }
}

/// Writes one entry: its master, patched with what changed against the
/// server copy, which also serves the `if_match` check, then each
/// occurrence the object holds otherwise, through its instance.
///
/// The occurrences are diffed against the series as the master's write
/// left it, read again when there was one: that write can change the
/// exceptions, Exchange discarding them when a series' pattern moves. An
/// occurrence that does not land fails the write with a 412 once the
/// others are written, the master standing: the entry moved under the
/// edit, which stays staged, and the next pass reconciles it as any entry
/// that moved.
/// Occurrences the server refuses for good, and nothing else failing,
/// answer [`REFUSED`] instead, which the engine records as a refusal of
/// the edit rather than a wait.
pub(super) fn update_entry<W: GraphWrites>(
    writes: &mut W,
    id: &str,
    ical: &str,
    if_match: Option<&str>,
) -> Result<Option<String>, BridgeError> {
    let (current, exceptions) = writes.series(id)?;
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
        change_key = writes.update(id, &patch)?.change_key;
    }
    if pending.is_empty() {
        return Ok(change_key);
    }

    let read = (!wrote).then_some((current, exceptions));
    write_occurrences(writes, id, ical, read).map_err(|err| match err.status {
        Some(REFUSED) => BridgeError {
            message: format!("Graph refuses an occurrence of event {id} for good: {err}"),
            status: Some(REFUSED),
        },
        _ => BridgeError {
            message: format!("Event {id} was written to Graph without all its occurrences: {err}"),
            status: Some(412),
        },
    })
}

/// Writes every occurrence an object holds otherwise than its series on
/// Graph, each on its own, answering the master's revision once all
/// landed and the first failure otherwise. The series is the one `read`,
/// unless the master was written since, which can change its exceptions.
fn write_occurrences<W: GraphWrites>(
    writes: &mut W,
    id: &str,
    ical: &str,
    read: Option<(MsgraphEvent, Vec<MsgraphEvent>)>,
) -> Result<Option<String>, BridgeError> {
    let (master, exceptions) = match read {
        Some(read) => read,
        None => writes.series(id)?,
    };
    let held: Vec<&MsgraphEvent> = exceptions.iter().collect();
    let server = master.to_ical_series(&held);

    let mut failures = Vec::new();
    for change in occurrence_changes(ical, &server)? {
        if let Err(err) = write_occurrence(writes, id, &master, &exceptions, &change) {
            log::warn!(
                "cannot write occurrence {} of event {id}: {err}",
                change.stamp()
            );
            failures.push(err);
        }
    }

    match parts_failure(failures) {
        None => Ok(writes.master(id)?.change_key),
        Some(err) => Err(err),
    }
}

/// Writes one occurrence through its instance: among the exceptions read
/// with the series, else among the instances of a window around it, each
/// told by the identity its projection gives it.
fn write_occurrence<W: GraphWrites>(
    writes: &mut W,
    id: &str,
    master: &MsgraphEvent,
    exceptions: &[MsgraphEvent],
    change: &OccurrenceChange,
) -> Result<(), BridgeError> {
    let told = |instance: &MsgraphEvent| {
        occurrences(&master.to_ical_series(&[instance]))
            .is_ok_and(|ids| ids.first() == Some(&change.id))
    };

    let instance = match exceptions.iter().find(|instance| told(instance)) {
        Some(instance) => Some(instance.id.clone()),
        None => {
            let (start, end) = occurrence_window(change.id);
            writes
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
        OccurrenceWrite::Delete => match writes.delete(&instance) {
            Err(err) if err.status == Some(404) => Ok(()),
            deleted => deleted,
        },
        OccurrenceWrite::Update { staged, server } => {
            let patch =
                MsgraphEvent::instance_update_from_ical(staged.as_bytes(), server.as_bytes())
                    .map_err(|err| err.to_string())?;
            match patch {
                Some(patch) => writes.update(&instance, &patch).map(drop),
                None => Ok(()),
            }
        }
    }
}

/// io-msgraph calendar to the JNI-facing shape, [`None`] for one with no
/// id: `isDefaultCalendar` makes it the account's default, and `canEdit`
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

/// Reads the named entries: each event, and the exceptions of each series
/// over its windows ([`series_windows`]), every request riding a
/// `$batch`; a reply the batch could not serve is sent again on its own.
///
/// An event Graph no longer holds (404 on the event or on its instances) is
/// left out; any other failure fails the read.
pub(super) fn read_entries<R: GraphReads>(
    reads: &mut R,
    ids: &[&str],
    today: Date,
) -> Result<Vec<Event>, BridgeError> {
    let urls = ids
        .iter()
        .map(|id| event_url(id))
        .collect::<Result<Vec<_>, _>>()?;

    let mut masters: Vec<Option<MsgraphEvent>> = Vec::with_capacity(ids.len());
    for (index, reply) in batched(|requests| reads.batch(requests), &urls)?
        .into_iter()
        .enumerate()
    {
        let read = match reply {
            Some(reply) if reply.status == 404 => None,
            Some(reply) if (200..300).contains(&reply.status) => {
                match reply.parse::<MsgraphEvent>() {
                    Ok(event) => Some(event),
                    Err(_) => alone(reads.event(ids[index]))?,
                }
            }
            _ => alone(reads.event(ids[index]))?,
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
        .zip(batched(|requests| reads.batch(requests), &urls)?)
    {
        if gone[*index] {
            continue;
        }
        let page = match reply {
            Some(reply) if reply.status == 404 => None,
            Some(reply) if (200..300).contains(&reply.status) => {
                match reply.parse::<MsgraphEventsListResponse>() {
                    Ok(page) => Some(page),
                    Err(_) => alone(reads.page(url.as_str()))?,
                }
            }
            _ => alone(reads.page(url.as_str()))?,
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
            page = reads.page(&next)?;
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

/// The windows a series' exceptions are read in, none longer than the
/// five years Graph allows an instances listing (it answers 400 past
/// them).
///
/// A bounded series is read over its whole range, an open-ended one
/// within [`OPEN_SERIES_YEARS`] either side of `today`: a birthday from
/// 1604, Outlook's year for one without a year, would otherwise take
/// dozens of requests for exceptions nobody looks at.
fn series_windows(master: &MsgraphEvent, today: Date) -> Option<Vec<(String, String)>> {
    let (start, end) = master.recurrence.as_option()?.bounds()?;
    let (start, end) = match end {
        Some(end) => (start, end.checked_add(1.day()).ok()?),
        None => {
            let years = OPEN_SERIES_YEARS.years();
            let floor = today.checked_sub(years).ok()?;
            (start.max(floor), today.checked_add(years).ok()?)
        }
    };

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
#[path = "graph_calendar_tests.rs"]
mod batch_tests;

#[cfg(test)]
#[path = "graph_calendar_write_tests.rs"]
mod write_tests;

#[cfg(test)]
mod tests {
    use io_msgraph::v1::{
        field::MsgraphField,
        rest::users::events::{
            MsgraphPatternedRecurrence, MsgraphRecurrenceRange, MsgraphRecurrenceRangeType,
        },
    };

    use serde_json::{from_value, json};

    use super::*;

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
}
