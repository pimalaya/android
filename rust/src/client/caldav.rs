//! CalDAV operations: the RFC 4791 calendar and item verbs, run over the
//! WebDAV coroutines.
//!
//! The mirror of [`super::carddav`], and deliberately so: CalDAV and
//! CardDAV are the same WebDAV walk (well-known probe,
//! current-user-principal, home-set, list) against a different collection
//! type, so the discovery half is shared outright and only the two
//! reports differ.
//!
//! The enumeration takes the whole collection rather than filtering it to
//! VEVENT, as the old `calendar-query` did: a `sync-collection` has no
//! component filter, and the app wanted the others anyway. The expander
//! places a to-do and a journal entry on a day like anything else with a
//! date, and the entry page edits all three; the filter was the one place
//! that disagreed.

use std::collections::BTreeSet;

use io_http::{rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic};
use io_pim_discovery::rfc6764::{service::DiscoveryDavService, well_known::DiscoveryWellKnown};
use io_webdav::{
    rfc4791::{
        calendar::{
            CaldavCalendar as DavCalendar, home_set::CaldavCalendarHomeSet,
            list::CaldavCalendarList,
        },
        item::{
            CaldavItemEntry, create::CaldavItemCreate, delete::CaldavItemDelete,
            multiget::CaldavItemMultiget, update::CaldavItemUpdate,
        },
    },
    rfc4918::WebdavAuth,
    rfc6578::sync_collection::WebdavSyncDelta,
};
use url::Url;

use crate::{
    client::{Client, USER_AGENT},
    types::{BridgeError, Calendar, Event},
};

impl<'a, 'local> Client<'a, 'local> {
    /// Walks current-user-principal -> calendar-home-set -> list,
    /// returning the account's calendars. Doubles as the connection
    /// check when a CalDAV account is added: any failure (TLS, auth,
    /// discovery walk) surfaces here.
    pub fn list_caldav_calendars(
        &mut self,
        base_url: &Url,
        credentials: &crate::types::Credentials,
    ) -> Result<Vec<Calendar>, BridgeError> {
        let auth = auth(credentials);

        // NOTE: RFC 6764 5: a bare origin is not necessarily the context
        // root, so probe .well-known/caldav for the real one; no
        // redirect keeps the origin.
        let base_url = match base_url.path() {
            "" | "/" => {
                let probe = DiscoveryWellKnown::new(base_url.clone(), DiscoveryDavService::Caldav);
                match self.run_discovery(probe) {
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
            .principal(base_url, &auth)?
            .unwrap_or_else(|| base_url.clone());

        let home_set = self
            .calendar_home_set(&principal, &auth)?
            .ok_or_else(|| "No calendar home set found".to_string())?;

        let coroutine = CaldavCalendarList::new(&home_set, &auth, USER_AGENT, home_set.path());
        let calendars: BTreeSet<DavCalendar> = self.run(&home_set, coroutine)?;

        Ok(calendars
            .into_iter()
            .map(|calendar| into_calendar(&home_set, calendar))
            .collect())
    }

    /// Lists the events of the calendar collection at `url`.
    ///
    /// Answers hrefs and ETags alone, from the cursor the last pass
    /// stored: what a pass needs to know is which events moved, and a
    /// calendar that answered with every body would send five hundred of
    /// them to report that none did.
    pub fn sync_caldav_events(
        &mut self,
        url: &Url,
        credentials: &crate::types::Credentials,
        sync_token: Option<&str>,
    ) -> Result<Option<WebdavSyncDelta>, BridgeError> {
        self.sync_dav_collection(url, &auth(credentials), sync_token)
    }

    /// Batch-fetches the events at the given resource names inside the
    /// calendar collection at `url`, via REPORT `calendar-multiget`
    /// (RFC 4791 section 7.9).
    pub fn multiget_caldav_events(
        &mut self,
        url: &Url,
        credentials: &crate::types::Credentials,
        ids: &[&str],
    ) -> Result<Vec<Event>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = CaldavItemMultiget::new(url, &auth, USER_AGENT, url.path(), ids);
        let items: Vec<CaldavItemEntry> = self.run(url, coroutine)?;

        Ok(items.into_iter().map(into_event).collect())
    }

    /// Replaces the object at resource id `id` inside the calendar
    /// collection at `url`, guarded by `if_match` when an ETag is
    /// known, returning the new ETag when the server sends one.
    ///
    /// Guarded on purpose: a calendar is a shared thing, and a blind
    /// PUT is how one client silently overwrites another's edit. A
    /// rejected precondition surfaces as the error it is.
    pub fn update_caldav_event(
        &mut self,
        url: &Url,
        credentials: &crate::types::Credentials,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = CaldavItemUpdate::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            id,
            ical.as_bytes().to_vec(),
            if_match,
        );

        Ok(self.run(url, coroutine)?.etag)
    }

    /// Creates the object at resource id `id` inside the calendar
    /// collection at `url`, returning the new ETag when the server
    /// sends one.
    ///
    /// The create is guarded on the resource not existing, which
    /// io-webdav puts on the request as `If-None-Match: *`: a `UID`
    /// collision is then refused by the server rather than silently
    /// overwriting whatever holds that name.
    pub fn create_caldav_event(
        &mut self,
        url: &Url,
        credentials: &crate::types::Credentials,
        id: &str,
        ical: &str,
    ) -> Result<Option<String>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = CaldavItemCreate::new(
            url,
            &auth,
            USER_AGENT,
            url.path(),
            id,
            ical.as_bytes().to_vec(),
        );

        Ok(self.run(url, coroutine)?.etag)
    }

    /// Deletes the object at resource id `id` inside the calendar
    /// collection at `url`, guarded by `if_match` when an ETag is
    /// known, for the same reason the update is.
    pub fn delete_caldav_event(
        &mut self,
        url: &Url,
        credentials: &crate::types::Credentials,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let auth = auth(credentials);
        let coroutine = CaldavItemDelete::new(url, &auth, USER_AGENT, url.path(), id, if_match);
        self.run(url, coroutine)?;

        Ok(())
    }

    /// PROPFIND the principal for `CALDAV:calendar-home-set`.
    fn calendar_home_set(
        &mut self,
        principal: &Url,
        auth: &WebdavAuth,
    ) -> Result<Option<Url>, BridgeError> {
        self.run_redirect(principal, |url| {
            CaldavCalendarHomeSet::new(url, auth, USER_AGENT, url.path())
        })
    }
}

fn auth(credentials: &crate::types::Credentials) -> WebdavAuth {
    if credentials.login.is_empty() {
        WebdavAuth::Bearer(HttpAuthBearer::new(credentials.password))
    } else {
        WebdavAuth::Basic(HttpAuthBasic::new(credentials.login, credentials.password))
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

/// io-webdav item entry to the JNI-facing shape; iCalendar is text, so
/// the raw bytes are decoded lossily.
fn into_event(entry: CaldavItemEntry) -> Event {
    Event {
        id: entry.id,
        etag: entry.etag,
        ical: String::from_utf8_lossy(&entry.data).into_owned(),
    }
}
