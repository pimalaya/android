//! Google Calendar API calendars: the user's calendar list as
//! collections, their events as iCalendar through io-gcal's `ical`
//! projection.
//!
//! Google is instance-granular where the store is resource-granular: a
//! series is a master plus one event per changed or cancelled instance,
//! each with its own ETag, and editing an instance leaves the master's
//! untouched. An entry's revision is therefore the master's ETag folded
//! with its instances', so an instance edit reads as the entry moving.
//! The enumeration lists a calendar in full every pass, a complete
//! listing reporting removals by absence, and a series is read with its
//! instances through its `iCalUID`.
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
    types::{BridgeError, Calendar, Event, EventRef},
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

    /// Enumerates a calendar's lone events and series masters, in full,
    /// each named by its Google id at its folded revision.
    pub fn list_gcal_events(
        &mut self,
        token: &str,
        calendar: &str,
    ) -> Result<Vec<EventRef>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let events = self.gcal_events(&auth, calendar, None)?;

        Ok(entries(events)
            .into_iter()
            .map(|(master, instances)| EventRef {
                etag: revision(&master, &instances),
                id: master.id.unwrap_or_default(),
            })
            .collect())
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
            let overrides: Vec<&GcalEvent> = instances.iter().collect();
            events.push(Event {
                id: id.to_string(),
                etag: revision(&master, &instances),
                ical: master.to_ical_series(&overrides),
            });
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
        let path = calendar_path(calendar);
        let coroutine =
            GcalEventGet::new(auth, &path, id, None, None).map_err(|err| err.to_string())?;
        let master = self.run_gcal(coroutine)?;

        if master.recurrence.is_empty() {
            return Ok((master, Vec::new()));
        }
        let Some(uid) = master.ical_uid.clone() else {
            return Ok((master, Vec::new()));
        };

        let instances = self
            .gcal_events(auth, calendar, Some(&uid))?
            .into_iter()
            .filter(|event| event.recurring_event_id.as_deref() == Some(id))
            .collect();

        Ok((master, instances))
    }

    /// Every event of a calendar, or of one `iCalUID`, its pages
    /// followed. Cancelled ones included: a cancelled instance is how
    /// Google says an occurrence was removed from a series.
    fn gcal_events(
        &mut self,
        auth: &HttpAuthBearer,
        calendar: &str,
        uid: Option<&str>,
    ) -> Result<Vec<GcalEvent>, BridgeError> {
        let path = calendar_path(calendar);
        let mut events = Vec::new();
        let mut page_token: Option<String> = None;

        loop {
            let params = GcalEventsListParams {
                ical_uid: uid,
                max_results: Some(PAGE_SIZE),
                page_token: page_token.as_deref(),
                show_deleted: true,
                ..Default::default()
            };
            let coroutine =
                GcalEventsList::new(auth, &path, &params).map_err(|err| err.to_string())?;
            let page: GcalEvents = self.run_gcal(coroutine)?;
            events.extend(page.items);

            match page.next_page_token {
                Some(next) => page_token = Some(next),
                None => return Ok(events),
            }
        }
    }

    /// Runs one Google Calendar coroutine to completion over the
    /// transport.
    fn run_gcal<C, T>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: GcalCoroutine<Yield = GcalYield, Return = Result<GcalSendOutput<T>, GcalSendError>>,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                GcalCoroutineState::Complete(Ok(output)) => return Ok(output.response),
                GcalCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
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
    fn a_subscribed_calendar_id_stays_one_segment() {
        assert_eq!(
            calendar_path("fr.french#holiday@group.v.calendar.google.com"),
            "fr.french%23holiday@group.v.calendar.google.com"
        );
    }
}
