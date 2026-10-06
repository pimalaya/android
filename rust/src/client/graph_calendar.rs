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
//! Graph has no conditional write for events, so the revision an edit was
//! staged against is checked against the server's right before the write,
//! and a moved event answers 412 like a CalDAV server would. Only the
//! series master is written: an exception edited here does not push.

use std::collections::BTreeMap;

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::{
    coroutine::*,
    v1::{
        rest::users::{
            calendars::list::{
                MsgraphCalendarsList, MsgraphCalendarsListParams, MsgraphCalendarsListResponse,
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

use crate::{
    client::{Client, graph::parse_graph_url},
    types::{BridgeError, Calendar, Event, EventRef},
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
            for calendar in page.value {
                if calendar.id.is_empty() {
                    continue;
                }
                calendars.push(Calendar {
                    name: calendar
                        .name
                        .as_option()
                        .cloned()
                        .unwrap_or_else(|| calendar.id.clone()),
                    id: calendar.id,
                    url: String::new(),
                    description: None,
                    color: calendar.hex_color.filter(|color| !color.is_empty()),
                });
            }

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
    /// `changeKey`.
    pub fn read_graph_events(
        &mut self,
        token: &str,
        ids: &[&str],
    ) -> Result<Vec<Event>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let mut events = Vec::with_capacity(ids.len());

        for id in ids {
            let (master, exceptions) = self.graph_series(&auth, id)?;
            let exceptions: Vec<&MsgraphEvent> = exceptions.iter().collect();
            events.push(Event {
                id: master.id.clone(),
                etag: master.change_key.clone(),
                ical: master.to_ical_series(&exceptions),
            });
        }

        Ok(events)
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

    /// Replaces a series master from an iCalendar object, sending only what
    /// changed against the server copy, which also serves the `if_match`
    /// check.
    pub fn update_graph_event(
        &mut self,
        token: &str,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let (current, exceptions) = self.graph_series(&auth, id)?;
        check_revision(id, &current, if_match)?;

        let exceptions: Vec<&MsgraphEvent> = exceptions.iter().collect();
        let base = current.to_ical_series(&exceptions);
        let patch = MsgraphEvent::update_from_ical(ical.as_bytes(), base.as_bytes())
            .map_err(|err| err.to_string())?;

        let coroutine =
            MsgraphEventUpdate::new(&auth, "me", id, &patch).map_err(|err| err.to_string())?;
        let updated = self.run_msgraph(coroutine)?;

        Ok(updated.change_key)
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
mod tests {
    use io_msgraph::v1::{
        field::MsgraphField,
        rest::users::events::{
            MsgraphPatternedRecurrence, MsgraphRecurrenceRange, MsgraphRecurrenceRangeType,
        },
    };

    use super::*;

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
