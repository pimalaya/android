//! CalDAV operations: the RFC 4791 calendar and item verbs, run over the
//! WebDAV coroutines.
//!
//! The mirror of [`super::carddav`], and deliberately so: CalDAV and
//! CardDAV are the same WebDAV walk (well-known probe,
//! current-user-principal, home-set, list) against a different collection
//! type, so the discovery half is shared outright and only the two
//! reports differ.

use std::collections::BTreeSet;

use io_http::{rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic};
use io_pim_discovery::rfc6764::{service::DiscoveryDavService, well_known::DiscoveryWellKnown};
use io_webdav::{
    rfc4791::{
        calendar::{Calendar as DavCalendar, home_set::CalendarHomeSet, list::ListCalendars},
        item::list::{ItemEntry, ListItems},
    },
    rfc4918::WebdavAuth,
};
use url::Url;

use crate::{
    client::{Client, USER_AGENT},
    types::{BridgeError, Calendar, Event},
};

/// The VCALENDAR child the agenda reads. Journals and to-dos live in the
/// same collections and would otherwise arrive as undated rows.
const VEVENT_FILTER: &str = r#"<C:comp-filter name="VEVENT" />"#;

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

        let coroutine = ListCalendars::new(&home_set, &auth, USER_AGENT, home_set.path());
        let calendars: BTreeSet<DavCalendar> = self.run(&home_set, coroutine)?;

        Ok(calendars
            .into_iter()
            .map(|calendar| into_calendar(&home_set, calendar))
            .collect())
    }

    /// Lists the events of the calendar collection at `url`.
    ///
    /// One `calendar-query` REPORT carries every event's `calendar-data`
    /// back, so a whole calendar costs a single round trip and no
    /// per-item GET.
    pub fn list_caldav_events(
        &mut self,
        url: &Url,
        credentials: &crate::types::Credentials,
    ) -> Result<Vec<Event>, BridgeError> {
        let auth = auth(credentials);
        let coroutine = ListItems::new(url, &auth, USER_AGENT, url.path(), VEVENT_FILTER);
        let items: BTreeSet<ItemEntry> = self.run(url, coroutine)?;

        Ok(items.into_iter().map(into_event).collect())
    }

    /// PROPFIND the principal for `CALDAV:calendar-home-set`.
    fn calendar_home_set(
        &mut self,
        principal: &Url,
        auth: &WebdavAuth,
    ) -> Result<Option<Url>, BridgeError> {
        self.run_redirect(principal, |url| {
            CalendarHomeSet::new(url, auth, USER_AGENT, url.path())
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
    }
}

/// io-webdav item entry to the JNI-facing shape; iCalendar is text, so
/// the raw bytes are decoded lossily.
fn into_event(entry: ItemEntry) -> Event {
    Event {
        id: entry.id,
        etag: entry.etag,
        ical: String::from_utf8_lossy(&entry.data).into_owned(),
    }
}
