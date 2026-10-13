//! # JMAP calendars
//!
//! The calendar verbs of the [`Jmap`] backend (draft-ietf-jmap-calendars),
//! CalendarEvents converted to and from iCalendar by ical-rs.
//!
//! A write is the staged object as the one JSCalendar entry
//! `CalendarEvent/set` takes ([`jscalendar_event`]), or the update patch
//! between two such entries ([`JmapCalendarEventPatch::diff`]).

use std::collections::BTreeMap;

use ical::ical::Ical;
use io_jmap::{
    calendars::{
        JMAP_CALENDARS_CAPABILITY,
        calendar::{JmapCalendar, JmapCalendarRights, get::*},
        calendar_event::{JmapCalendarEvent, get::*, query::*, set::*},
    },
    rfc8620::{error::JmapMethodError, session::JmapSession, set::JmapSetError},
};
use serde_json::Value;
use url::Url;

use crate::{
    calendar::{Calendar, Event, EventRef, cst_of, read::organized},
    client::{
        Host,
        jmap::{Jmap, JmapCalls, etag, jmap_collection_path, page_size, paged, refused, sessions},
    },
    error::{BridgeError, coroutine_error},
    offline::default_role,
};

/// The Calendar and CalendarEvent reads, each event converted to the
/// iCalendar the rest of the app renders.
impl<H: Host> Jmap<'_, H> {
    /// Lists the account's JMAP Calendars, doubling as the onboarding
    /// connection check.
    ///
    /// URLs are account-scoped paths, the caller prefixing the base URL it
    /// alone knows.
    pub fn list_calendars(&mut self) -> Result<Vec<Calendar>, BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();

        let opts = JmapCalendarGetOptions::default();
        let coroutine =
            JmapCalendarGet::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run(&api_url, coroutine)?;

        Ok(out
            .calendars
            .into_iter()
            .map(|calendar| jmap_calendar(&session, calendar))
            .collect())
    }

    /// Lists a JMAP Calendar's events as iCalendar, and whether it ended.
    ///
    /// Via [`JmapCalls::list_events`], recurrence folded: `CalendarEvent/query`
    /// could expand it, but one expansion path every backend feeds is worth
    /// more than a round trip; server expansion is later, this its oracle.
    pub fn list_events(&mut self, calendar_id: &str) -> Result<(Vec<Event>, bool), BridgeError> {
        let mut calls = self.calls()?;
        let limit = page_size(&calls.session);

        calls.list_events(calendar_id, limit)
    }
}

/// JMAP calendar writes: draft-ietf-jmap-calendars `CalendarEvent/set`,
/// an override or an `EXDATE` travelling inside its event as a
/// `recurrenceOverrides` entry rather than as a write of its own.
impl<H: Host> Jmap<'_, H> {
    /// Files one object in the calendar `calendar_id`, as
    /// [`JmapCalls::create_event`] says, `address` the account's.
    pub fn create_event(
        &mut self,
        calendar_id: &str,
        ical: &str,
        address: &str,
    ) -> Result<EventRef, BridgeError> {
        let mut calls = self.calls()?;
        calls.create_event(calendar_id, ical, &[address.to_string()])
    }

    /// Writes one edited object over the event `id`, as
    /// [`JmapCalls::update_event`] says, answering its new revision,
    /// `address` the account's.
    pub fn update_event(
        &mut self,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
        address: &str,
    ) -> Result<Option<String>, BridgeError> {
        let mut calls = self.calls()?;
        calls.update_event(id, ical, if_match, &[address.to_string()])
    }

    /// Destroys the event `id`, as [`JmapCalls::destroy_event`] says,
    /// `address` the account's.
    pub fn delete_event(
        &mut self,
        id: &str,
        if_match: Option<&str>,
        address: &str,
    ) -> Result<(), BridgeError> {
        let mut calls = self.calls()?;
        calls.destroy_event(id, if_match, &[address.to_string()])
    }

    /// Runs one `CalendarEvent/set`, a call refused for its `ifInState`
    /// answering 412 ([`set_failure`]) rather than a bare error.
    fn run_event_set(
        &mut self,
        api_url: &Url,
        coroutine: JmapCalendarEventSet,
    ) -> Result<JmapCalendarEventSetOutput, BridgeError> {
        self.try_run(api_url, coroutine)?.map_err(|err| {
            sessions().forget(api_url);
            set_failure(&err)
        })
    }
}

/// The requests a calendar verb sends over the session it read once: one
/// `CalendarEvent/query` page with its events, one event read with the
/// state the account's events stand at, and one `CalendarEvent/set`.
impl<H: Host> JmapCalls<'_, '_, H> {
    /// One page of a calendar's events from `position`, `limit` at most.
    fn event_page(
        &mut self,
        calendar_id: &str,
        position: u64,
        limit: u64,
    ) -> Result<JmapCalendarEventQueryOutput, BridgeError> {
        let opts = JmapCalendarEventQueryOptions {
            filter: Some(JmapCalendarEventFilter {
                in_calendar: Some(calendar_id.to_string()),
                ..Default::default()
            }),
            position: Some(position),
            limit: Some(limit),
            ..Default::default()
        };
        let coroutine = JmapCalendarEventQuery::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        self.jmap.run(&self.session.api_url, coroutine)
    }

    /// The event `id`, [`None`] when the server holds none, and the
    /// `CalendarEvent` state it was read at.
    fn event(&mut self, id: &str) -> Result<(Option<JmapCalendarEvent>, String), BridgeError> {
        let opts = JmapCalendarEventGetOptions {
            ids: Some(vec![id.to_string()]),
            ..Default::default()
        };
        let coroutine = JmapCalendarEventGet::new(&self.session, &self.auth, opts)
            .map_err(|err| err.to_string())?;
        let out = self.jmap.run(&self.session.api_url, coroutine)?;
        Ok((out.events.into_iter().next(), out.new_state))
    }

    /// Runs one `CalendarEvent/set`, one refused for its `ifInState`
    /// answering 412.
    fn set_events(
        &mut self,
        args: JmapCalendarEventSetArgs,
    ) -> Result<JmapCalendarEventSetOutput, BridgeError> {
        let coroutine = JmapCalendarEventSet::new(&self.session, &self.auth, args)
            .map_err(|err| err.to_string())?;
        self.jmap.run_event_set(&self.session.api_url, coroutine)
    }
}

/// The calendar verbs over the session they read once.
impl<H: Host> JmapCalls<'_, '_, H> {
    /// Lists one calendar's events a page of `limit` at a time ([`paged`]),
    /// each converted to iCalendar, once each.
    fn list_events(
        &mut self,
        calendar_id: &str,
        limit: u64,
    ) -> Result<(Vec<Event>, bool), BridgeError> {
        let (events, complete) = paged(|position| {
            let out = self.event_page(calendar_id, position, limit)?;
            Ok((out.events, out.total, out.position))
        })?;

        let mut named = BTreeMap::new();
        for event in events {
            let event = jmap_event(event)?;
            named.insert(event.id.clone(), event);
        }
        Ok((named.into_values().collect(), complete))
    }

    /// Files one object as a new CalendarEvent ([`jscalendar_event`]).
    ///
    /// Its one JSCalendar entry, under the id the server gives it, read back
    /// for the revision the next edit is staged against. The server invites
    /// the attendees of an event the user organizes ([`organized`]).
    fn create_event(
        &mut self,
        calendar_id: &str,
        ical: &str,
        addresses: &[String],
    ) -> Result<EventRef, BridgeError> {
        let event = JmapCalendarEvent {
            calendar_ids: BTreeMap::from([(calendar_id.to_string(), true)]),
            ..jscalendar_event(ical).map_err(refused)?
        };
        let args = JmapCalendarEventSetArgs {
            create: Some(BTreeMap::from([("e0".to_string(), event)])),
            send_scheduling_messages: organized(ical, addresses),
            ..Default::default()
        };

        let out = self.set_events(args)?;
        if let Some(err) = out.not_created.get("e0") {
            return Err(item_failure("The server refused the new event", err));
        }
        let id = out
            .created
            .get("e0")
            .and_then(|created| created.id.clone())
            .ok_or("The server created the event under no id")?;

        Ok(EventRef {
            etag: self.written_revision(&id),
            id,
        })
    }

    /// Writes one edited object over the event `id`, answering its revision.
    ///
    /// Read first: one gone or moved since `if_match` answers 412. The
    /// patch compares staged and server copy both through the conversion
    /// ([`JmapCalendarEventPatch::diff`]), so a member it cannot carry is
    /// never sent.
    fn update_event(
        &mut self,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
        addresses: &[String],
    ) -> Result<Option<String>, BridgeError> {
        let (current, state) = self.event(id)?;
        let Some(current) = current else {
            return Err(moved(format!("Event {id} is gone from the server")));
        };
        let revision = etag(&current);
        if if_match.is_some_and(|expected| revision.as_deref() != Some(expected)) {
            return Err(moved(format!(
                "Event {id} changed on the server since it was read"
            )));
        }

        let held = jmap_event(current)?;
        let base = jscalendar_event(&held.ical).map_err(refused)?;
        let staged = jscalendar_event(ical).map_err(refused)?;
        let patch = JmapCalendarEventPatch::diff(&staged.event, &base.event);
        if patch.0.is_empty() {
            return Ok(revision);
        }

        let args = JmapCalendarEventSetArgs {
            // NOTE: nothing lands between the check and the write where the
            // server honours it; Stalwart 0.16 ignores it.
            if_in_state: Some(state),
            update: Some(BTreeMap::from([(id.to_string(), patch)])),
            // NOTE: organized as staged or as held, so an organizer taking
            // every attendee off still cancels them.
            send_scheduling_messages: organized(ical, addresses)
                || organized(&held.ical, addresses),
            ..Default::default()
        };
        let out = self.set_events(args)?;
        if let Some(err) = out.not_updated.get(id) {
            return Err(item_failure("The server refused the event change", err));
        }

        Ok(self.written_revision(id))
    }

    /// The revision of an event just written, read back; none when the read
    /// fails, the write having landed: failing it would push it again, and
    /// the next listing names the revision anyway.
    fn written_revision(&mut self, id: &str) -> Option<String> {
        match self.event(id) {
            Ok((stored, _)) => stored.as_ref().and_then(etag),
            Err(err) => {
                log::warn!("event {id} written, its revision unread: {err}");
                None
            }
        }
    }

    /// Destroys the event `id`, one gone (read first or at destroy) converged.
    ///
    /// One moved since `if_match` answers 412, as [`Self::update_event`],
    /// the destroy under the state that read answered. The server cancels
    /// it for the attendees when the user organizes it ([`organized`]).
    fn destroy_event(
        &mut self,
        id: &str,
        if_match: Option<&str>,
        addresses: &[String],
    ) -> Result<(), BridgeError> {
        let (current, state) = self.event(id)?;
        let Some(current) = current else {
            return Ok(());
        };
        if if_match.is_some_and(|expected| etag(&current).as_deref() != Some(expected)) {
            return Err(moved(format!(
                "Event {id} changed on the server since it was read"
            )));
        }
        // NOTE: an event the conversion cannot read is deleted unannounced
        // rather than kept.
        let announced = jmap_event(current).is_ok_and(|held| organized(&held.ical, addresses));

        let args = JmapCalendarEventSetArgs {
            if_in_state: if_match.and(Some(state)),
            destroy: Some(vec![id.to_string()]),
            send_scheduling_messages: announced,
            ..Default::default()
        };
        match self.set_events(args)?.not_destroyed.get(id) {
            None | Some(JmapCalendarEventSetItemError::NotFound { .. }) => Ok(()),
            Some(err) => Err(item_failure("The server refused to delete the event", err)),
        }
    }
}

/// What one event a `CalendarEvent/set` refused answers.
///
/// Refused for good ([`crate::error::REFUSED`]) when the server objects to
/// the change itself; 412 otherwise (gone, rate limit, anything unknown),
/// kept staged for a pass that reads the event again.
fn item_failure(what: &str, err: &JmapCalendarEventSetItemError) -> BridgeError {
    let message = format!("{what}: {err}");
    match err {
        JmapCalendarEventSetItemError::Forbidden { .. }
        | JmapCalendarEventSetItemError::InvalidProperties { .. }
        | JmapCalendarEventSetItemError::InvalidPatch { .. }
        | JmapCalendarEventSetItemError::TooLarge { .. }
        | JmapCalendarEventSetItemError::OverQuota { .. }
        | JmapCalendarEventSetItemError::Singleton { .. }
        | JmapCalendarEventSetItemError::NoSupportedScheduleMethods { .. } => refused(message),
        JmapCalendarEventSetItemError::NotFound { .. }
        | JmapCalendarEventSetItemError::RateLimit { .. }
        | JmapCalendarEventSetItemError::WillDestroy { .. }
        | JmapCalendarEventSetItemError::Unknown => moved(message),
    }
}

/// A `CalendarEvent/set` that failed as a whole: refused for its
/// `ifInState` (RFC 8620 section 5.3), the account's events moved between
/// the read and the write, it answers 412 as a moved event does.
fn set_failure(err: &JmapCalendarEventSetError) -> BridgeError {
    match err {
        JmapCalendarEventSetError::Set(JmapSetError::Method(JmapMethodError::StateMismatch {
            ..
        })) => moved("The calendar changed on the server since the event was read"),
        err => coroutine_error(err),
    }
}

/// A write staged against something the server no longer holds: 412, as
/// a CalDAV server answers a failed precondition, the change kept staged.
fn moved(message: impl Into<String>) -> BridgeError {
    BridgeError {
        message: message.into(),
        status: Some(412),
    }
}

/// An io-jmap Calendar as a [`Calendar`], writable by its rights
/// ([`writable_calendar`]).
///
/// The twin of [`jmap_addressbook`](crate::contact::jmap::jmap_addressbook)
/// down to the account-scoped URL.
fn jmap_calendar(session: &JmapSession, calendar: JmapCalendar) -> Calendar {
    let id = calendar.id.unwrap_or_default();

    Calendar {
        name: calendar.name.clone().unwrap_or_else(|| id.clone()),
        url: jmap_collection_path(session, JMAP_CALENDARS_CAPABILITY, &id),
        id,
        description: calendar.description,
        color: calendar.color,
        role: default_role(calendar.is_default),
        writable: writable_calendar(&calendar.my_rights),
    }
}

/// Whether the user may write events into a calendar: any write right,
/// or rights the server left unsaid (every one false, reading included),
/// which says nothing rather than read only.
fn writable_calendar(rights: &JmapCalendarRights) -> bool {
    rights.may_write_all || rights.may_write_own || !rights.may_read_items
}

/// An io-jmap CalendarEvent as an [`Event`], from JSCalendar 2.0 or RFC 8984.
///
/// Converted here (2.0 being the draft's base) as the store's kind is a media
/// type: a second format under `text/calendar` would fork every agenda reader.
/// No per-object ETag: the revision hashes the event's JSON, as a card's does.
fn jmap_event(event: JmapCalendarEvent) -> Result<Event, String> {
    let etag = etag(&event);
    let id = event.id.unwrap_or_default();
    let mut payload = event.event;

    // NOTE: a JSCalendar object with no `@type` reads as a Group, which
    // one CalendarEvent is not. The draft has the server send it; a
    // server that did not would otherwise convert to an empty calendar.
    payload
        .entry("@type")
        .or_insert_with(|| Value::from("Event"));

    let payload = Value::Object(payload);
    let ical = Ical::from_jscalendar(&payload)
        .map_err(|err| format!("Invalid JSCalendar event `{id}`: {err}"))?;

    Ok(Event {
        id,
        etag,
        ical: ical.to_string(),
    })
}

/// The staged object as a CalendarEvent ([`JmapCalendarEvent::from_ical`]),
/// parsed under the nesting guard of [`cst_of`].
fn jscalendar_event(ical: &str) -> Result<JmapCalendarEvent, String> {
    let cst = cst_of(ical).map_err(|err| format!("Invalid iCalendar: {err}"))?;
    JmapCalendarEvent::from_ical(&cst.decode()).map_err(|err| err.to_string())
}

#[cfg(test)]
mod tests;
