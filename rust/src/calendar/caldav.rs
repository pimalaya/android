//! # CalDAV
//!
//! The calendar verbs of the [`Webdav`] backend: RFC 4791 calendars and
//! items over the WebDAV coroutines.
//!
//! The mirror of [`crate::contact::carddav`]: the same WebDAV walk
//! (well-known probe, current-user-principal, home-set, list) against
//! another collection type, so only the two reports differ.
//!
//! The enumeration takes the whole collection, not VEVENTs alone: a
//! `sync-collection` has no component filter, and the expander and the
//! entry page handle to-dos and journal entries like events.

use std::collections::BTreeSet;

use io_pim_discovery::rfc6764::{service::DiscoveryDavService, well_known::DiscoveryWellKnown};
use io_webdav::{
    rfc4791::{
        calendar::{
            CaldavCalendar as DavCalendar, home_set::CaldavCalendarHomeSet,
            list::CaldavCalendarList,
        },
        item::{
            CaldavItemEntry,
            create::{CaldavItemCreate, CaldavItemCreateOk},
            delete::CaldavItemDelete,
            r#move::CaldavItemMove,
            multiget::CaldavItemMultiget,
            update::CaldavItemUpdate,
        },
    },
    rfc4918::{WebdavAuth, collection_path},
    rfc6578::sync_collection::WebdavSyncDelta,
};
use url::Url;

use crate::{
    calendar::{Calendar, Event, EventRef},
    client::{Client, Host, USER_AGENT, trust::trusted_href, webdav::Webdav},
    error::BridgeError,
};

impl<H: Host> Webdav<'_, H> {
    /// Walks current-user-principal -> calendar-home-set -> list.
    ///
    /// Doubles as the connection check when a CalDAV account is added: any
    /// failure (TLS, auth, discovery walk) surfaces here.
    pub fn list_calendars(&mut self, base_url: &Url) -> Result<Vec<Calendar>, BridgeError> {
        let auth = self.auth.webdav();

        // NOTE: RFC 6764 5: a bare origin is not necessarily the context
        // root, so probe .well-known/caldav for the real one; no
        // redirect keeps the origin.
        let base_url = match base_url.path() {
            "" | "/" => {
                let probe = DiscoveryWellKnown::new(base_url.clone(), DiscoveryDavService::Caldav);
                match self.client.run_discovery(probe) {
                    Ok(Some(root)) => root,
                    _ => base_url.clone(),
                }
            }
            _ => base_url.clone(),
        };
        let base_url = &base_url;

        // NOTE: some servers expose the home-set off the context root,
        // so a missing principal is not fatal.
        let principal = self
            .client
            .principal(base_url, &auth)?
            .unwrap_or_else(|| base_url.clone());

        let home_set = self
            .client
            .calendar_home_set(&principal, &auth)?
            .ok_or_else(|| "No calendar home set found".to_string())?;

        let coroutine = CaldavCalendarList::new(&home_set, &auth, USER_AGENT, home_set.path());
        let calendars: BTreeSet<DavCalendar> = self.client.run(&home_set, coroutine)?;

        Ok(calendars
            .into_iter()
            .map(|calendar| into_calendar(&home_set, calendar))
            .collect())
    }

    /// Lists the events of the calendar collection at `url`.
    ///
    /// Answers hrefs and ETags alone, from the cursor the last pass
    /// stored: a pass needs which events moved, and every body would send
    /// five hundred of them to report that none did.
    pub fn sync_events(
        &mut self,
        url: &Url,
        sync_token: Option<&str>,
    ) -> Result<Option<WebdavSyncDelta>, BridgeError> {
        self.sync_collection(url, sync_token)
    }

    /// Batch-fetches the named events of the collection at `url`, via
    /// REPORT `calendar-multiget` (RFC 4791 section 7.9).
    pub fn multiget_events(&mut self, url: &Url, ids: &[&str]) -> Result<Vec<Event>, BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CaldavItemMultiget::new(url, &auth, USER_AGENT, url.path(), ids);
        let items: Vec<CaldavItemEntry> = self.client.run(url, coroutine)?;

        Ok(items.into_iter().map(into_event).collect())
    }

    /// Replaces the object `id` at `url`, answering the new ETag if sent.
    ///
    /// Guarded by `if_match` when known: a blind PUT is how one client
    /// silently overwrites another's edit. A rejected precondition
    /// surfaces as the error it is.
    pub fn update_event(
        &mut self,
        url: &Url,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CaldavItemUpdate::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            id,
            ical.as_bytes().to_vec(),
            if_match,
        );

        Ok(self.client.run(url, coroutine)?.etag)
    }

    /// Creates the object `id` in the collection at `url`, answering the
    /// name the server filed it by ([`into_created_event`]) and its ETag.
    ///
    /// Guarded by `If-None-Match: *` (io-webdav): a `UID` collision is
    /// refused rather than silently overwriting what holds that name.
    pub fn create_event(
        &mut self,
        url: &Url,
        id: &str,
        ical: &str,
    ) -> Result<EventRef, BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CaldavItemCreate::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            id,
            ical.as_bytes().to_vec(),
        );

        Ok(into_created_event(self.client.run(url, coroutine)?))
    }

    /// Moves the object `id` of the collection at `url` into the collection
    /// at `target` with WebDAV `MOVE` (RFC 4918 §9.9), guarded by `if_match`
    /// when known, answering the name it lands under and its ETag if sent.
    ///
    /// The member itself moves, so its `UID` never lives in two calendars:
    /// Fastmail refuses a create of a `UID` another of its calendars holds.
    /// `Overwrite: F` (io-webdav) refuses to replace what the target holds
    /// under that name. A target on another origin is refused here with a
    /// 501, as a move the server cannot do, rather than sent: the
    /// credential is this origin's.
    pub fn move_event(
        &mut self,
        url: &Url,
        id: &str,
        target: &Url,
        if_match: Option<&str>,
    ) -> Result<EventRef, BridgeError> {
        if url.origin() != target.origin() {
            return Err(BridgeError {
                message: format!("Cannot move {id} to another server"),
                status: Some(501),
            });
        }

        let auth = self.auth.webdav();
        let coroutine = CaldavItemMove::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            id,
            target.path(),
            if_match,
        );
        let moved = self.client.run(url, coroutine)?;

        Ok(EventRef {
            id: moved.id,
            etag: moved.etag,
        })
    }

    /// Deletes the object `id` of the collection at `url`, guarded by
    /// `if_match` when known, as the update is.
    pub fn delete_event(
        &mut self,
        url: &Url,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CaldavItemDelete::new(url, &auth, USER_AGENT, url.path(), id, if_match);
        self.client.run(url, coroutine)?;

        Ok(())
    }
}

impl<H: Host> Client<H> {
    /// PROPFIND the principal for `CALDAV:calendar-home-set`, on a server
    /// the credential may follow to ([`trusted_href`]).
    fn calendar_home_set(
        &mut self,
        principal: &Url,
        auth: &WebdavAuth,
    ) -> Result<Option<Url>, BridgeError> {
        let home = self.run_redirect(principal, |url| {
            CaldavCalendarHomeSet::new(url, auth, USER_AGENT, url.path())
        })?;
        home.map(|home| trusted_href(principal, home)).transpose()
    }
}

/// An io-webdav calendar as a [`Calendar`], its URL rebuilt from the home set
/// and the id ([`collection_path`]): the last path segment of a calendar
/// under the home set, the path itself of one outside it (Google lists each
/// as `/caldav/v2/<calendar>/events/`).
fn into_calendar(home_set: &Url, calendar: DavCalendar) -> Calendar {
    let mut url = home_set.clone();
    url.set_path(&collection_path(home_set.path(), &calendar.id));

    Calendar {
        name: calendar.display_name.unwrap_or_else(|| calendar.id.clone()),
        id: calendar.id,
        url: url.to_string(),
        description: calendar.description,
        color: calendar.color,
        // NOTE: io-webdav reads neither RFC 6638's
        // schedule-default-calendar-URL nor RFC 3744 privileges yet: no
        // default, and writable.
        role: String::new(),
        writable: true,
        vtodo: vtodo_of(&calendar.components),
    }
}

/// Whether a calendar of this `supported-calendar-component-set` takes a
/// to-do: [`None`] for an empty set, which restricts nothing (RFC 4791
/// §5.2.3).
fn vtodo_of(components: &BTreeSet<String>) -> Option<bool> {
    (!components.is_empty()).then(|| {
        components
            .iter()
            .any(|component| component.eq_ignore_ascii_case("VTODO"))
    })
}

/// An io-webdav item entry as an [`Event`], its bytes decoded lossily.
fn into_event(entry: CaldavItemEntry) -> Event {
    Event {
        id: entry.id,
        etag: entry.etag,
        ical: String::from_utf8_lossy(&entry.data).into_owned(),
    }
}

/// A created object named as filed: the name asked for, or `Location`'s.
///
/// Google files an event under a name of its own: a binding kept on the
/// name asked for would delete with a 404, read as converged, and the
/// next listing would bring the event back.
fn into_created_event(created: CaldavItemCreateOk) -> EventRef {
    EventRef {
        id: created.id,
        etag: created.etag,
    }
}

#[cfg(test)]
mod tests {
    //! # CalDAV create tests
    //!
    //! The CalDAV create over a canned reply: the event is kept under the
    //! name the server filed it by, which is what a later delete addresses.

    use io_webdav::coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield};

    use crate::calendar::caldav::*;

    /// Runs a create against one reply, answering the event the bridge
    /// keeps.
    fn create(name: &str, reply: &str) -> EventRef {
        let base: Url = "https://apidata.googleusercontent.com/".parse().unwrap();
        let mut coroutine = CaldavItemCreate::new(
            &base,
            &WebdavAuth::None,
            USER_AGENT,
            "/caldav/v2/google%40pimalaya.org/events/",
            name,
            b"BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".to_vec(),
        );

        let WebdavCoroutineState::Yielded(WebdavYield::WantsWrite(_)) = coroutine.resume(None)
        else {
            panic!("no request");
        };
        let WebdavCoroutineState::Yielded(WebdavYield::WantsRead) = coroutine.resume(None) else {
            panic!("no read");
        };
        let WebdavCoroutineState::Complete(Ok(created)) = coroutine.resume(Some(reply.as_bytes()))
        else {
            panic!("no event");
        };

        into_created_event(created)
    }

    /// Runs a create the server refuses, answering the failure Java gets.
    fn refused(reply: &str) -> BridgeError {
        let base: Url = "https://caldav.fastmail.com/".parse().unwrap();
        let mut coroutine = CaldavItemCreate::new(
            &base,
            &WebdavAuth::None,
            USER_AGENT,
            "/dav/calendars/user/jane@fastmail.com/home/",
            "gate.ics",
            b"BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".to_vec(),
        );

        coroutine.resume(None);
        coroutine.resume(None);
        let WebdavCoroutineState::Complete(Err(err)) = coroutine.resume(Some(reply.as_bytes()))
        else {
            panic!("not refused");
        };

        crate::error::coroutine_error(&err)
    }

    /// Fastmail refuses a second resource of one `UID` in a calendar home
    /// with a 403 io-webdav reads as a duplicate `UID`: Java tells that
    /// item's refusal from a failed pass by the status alone.
    #[test]
    fn a_duplicate_uid_refusal_crosses_with_its_status() {
        let body = "<?xml version=\"1.0\"?><D:error xmlns:D=\"DAV:\" \
                    xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\
                    <C:unique-scheduling-object-resource/></D:error>";
        let fastmail = refused(&format!(
            "HTTP/1.1 403 Forbidden\r\nContent-Type: application/xml\r\n\
             Content-Length: {}\r\n\r\n{body}",
            body.len(),
        ));
        assert!(
            fastmail.message.contains("same UID"),
            "{}",
            fastmail.message
        );
        assert_eq!(fastmail.status, Some(403));

        let body = "<?xml version=\"1.0\"?><D:error xmlns:D=\"DAV:\" \
                    xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\
                    <C:no-uid-conflict><D:href>/home/other.ics</D:href>\
                    </C:no-uid-conflict></D:error>";
        let conflict = refused(&format!(
            "HTTP/1.1 409 Conflict\r\nContent-Type: application/xml\r\n\
             Content-Length: {}\r\n\r\n{body}",
            body.len(),
        ));
        assert_eq!(conflict.status, Some(409));
    }

    /// Google files the event under a name of its own and says so: that
    /// name is the event's, so its delete reaches it rather than a 404.
    #[test]
    fn an_event_google_files_elsewhere_is_kept_under_googles_name() {
        let created = create(
            "3f2a.ics",
            "HTTP/1.1 201 Created\r\n\
             Location: /caldav/v2/google%40pimalaya.org/events/k9q2r1.ics\r\n\
             ETag: \"e-1\"\r\n\
             Content-Length: 0\r\n\r\n",
        );

        assert_eq!(created.id, "k9q2r1.ics");
        assert_eq!(created.etag.as_deref(), Some("e-1"));
    }

    /// A server keeping the name asked for files the event under it.
    #[test]
    fn an_event_filed_as_asked_is_kept_under_the_name_asked() {
        let created = create(
            "3f2a.ics",
            "HTTP/1.1 201 Created\r\nETag: \"e-1\"\r\nContent-Length: 0\r\n\r\n",
        );

        assert_eq!(created.id, "3f2a.ics");
    }

    /// Runs a move the server answers with `reply`, answering the failure
    /// Java gets.
    fn move_refused(reply: &str) -> BridgeError {
        let base: Url = "https://caldav.fastmail.com/".parse().unwrap();
        let mut coroutine = CaldavItemMove::new(
            &base,
            &WebdavAuth::None,
            USER_AGENT,
            "/dav/calendars/user/jane@fastmail.com/work/",
            "gate.ics",
            "/dav/calendars/user/jane@fastmail.com/home/",
            Some("e0"),
        );

        coroutine.resume(None);
        coroutine.resume(None);
        let WebdavCoroutineState::Complete(Err(err)) = coroutine.resume(Some(reply.as_bytes()))
        else {
            panic!("not refused");
        };

        crate::error::coroutine_error(&err)
    }

    /// Java tells a move the server cannot do (Google answers 404, others
    /// 405 or 501), which it meets by an upload, from one refused for the
    /// member's `UID` or its ETag, which refuses the create: by the status.
    #[test]
    fn a_refused_move_crosses_with_its_status() {
        let google = move_refused("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
        assert_eq!(google.status, Some(404));

        let stale = move_refused("HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\n\r\n");
        assert_eq!(stale.status, Some(412));

        let body = "<?xml version=\"1.0\"?><D:error xmlns:D=\"DAV:\" \
                    xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\
                    <C:no-uid-conflict><D:href>/home/other.ics</D:href>\
                    </C:no-uid-conflict></D:error>";
        let conflict = move_refused(&format!(
            "HTTP/1.1 409 Conflict\r\nContent-Type: application/xml\r\n\
             Content-Length: {}\r\n\r\n{body}",
            body.len(),
        ));
        assert!(
            conflict.message.contains("same UID"),
            "{}",
            conflict.message
        );
        assert_eq!(conflict.status, Some(409));
    }

    /// Google lists every calendar outside the home set, as
    /// `/caldav/v2/<calendar>/events/`: io-webdav names it by that path,
    /// which its URL is, never the home set joined with it.
    #[test]
    fn a_calendar_outside_the_home_set_is_addressed_by_its_path() {
        let home: Url = "https://apidata.googleusercontent.com/caldav/v2/jane%40pimalaya.org/"
            .parse()
            .unwrap();
        let at = |id: &str| {
            into_calendar(
                &home,
                DavCalendar {
                    id: id.into(),
                    ..Default::default()
                },
            )
            .url
        };

        assert_eq!(
            at("/caldav/v2/team%40group.calendar.google.com/events"),
            "https://apidata.googleusercontent.com/caldav/v2/team%40group.calendar.google.com/events/",
        );
        assert_eq!(
            at("work"),
            "https://apidata.googleusercontent.com/caldav/v2/jane%40pimalaya.org/work/",
        );
    }

    #[test]
    fn a_calendar_states_whether_it_takes_tasks() {
        let home: Url = "https://caldav.example.org/dav/calendars/user/"
            .parse()
            .unwrap();
        let listed = |components: &[&str]| {
            into_calendar(
                &home,
                DavCalendar {
                    id: "work".into(),
                    components: components.iter().map(|c| c.to_string()).collect(),
                    ..Default::default()
                },
            )
            .vtodo
        };

        // NOTE: an events-only calendar refuses a VTODO PUT (Google answers
        // 403); no set stated restricts nothing (RFC 4791 §5.2.3).
        assert_eq!(listed(&["VEVENT"]), Some(false));
        assert_eq!(listed(&["VEVENT", "VTODO"]), Some(true));
        assert_eq!(listed(&[]), None);
    }
}
