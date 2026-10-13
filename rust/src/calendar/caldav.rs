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
            multiget::CaldavItemMultiget,
            update::CaldavItemUpdate,
        },
    },
    rfc4918::WebdavAuth,
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

fn into_calendar(home_set: &Url, calendar: DavCalendar) -> Calendar {
    let mut url = home_set.clone();
    url.set_path(&format!(
        "{}/{}/",
        home_set.path().trim_end_matches('/'),
        calendar.id
    ));

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
    }
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
}
