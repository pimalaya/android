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
            CALENDAR, CaldavCalendar as DavCalendar, home_set::CaldavCalendarHomeSet,
            list::CaldavCalendarList,
        },
        item::{
            CaldavItemEntry,
            create::{CaldavItemCreate, CaldavItemCreateOk},
            delete::CaldavItemDelete,
            r#move::CaldavItemMove,
            multiget::CaldavItemMultiget,
            read::CaldavItemRead,
            update::CaldavItemUpdate,
        },
    },
    rfc4918::{
        RESOURCETYPE, SUPPORTED_REPORT_SET, WebdavAuth, WebdavMultistatus, collection_path,
        propfind::WebdavPropfind,
    },
    rfc6578::sync_collection::{SYNC_COLLECTION, WebdavSyncCollectionOptions, WebdavSyncDelta},
};
use percent_encoding::percent_decode_str;
use url::Url;

use crate::{
    calendar::{Calendar, Event, EventRef},
    client::{
        Client, Host, USER_AGENT,
        trust::trusted_href,
        webdav::{Webdav, gone_row, is_gone, maybe_lossy, served, unreadable},
    },
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
    /// five hundred of them to report that none did. A token-less round
    /// checks collection capabilities before choosing REPORT or PROPFIND.
    pub fn sync_events(
        &mut self,
        url: &Url,
        sync_token: Option<&str>,
    ) -> Result<Option<WebdavSyncDelta>, BridgeError> {
        let mut opts = WebdavSyncCollectionOptions::default();
        if sync_token.is_none() {
            let auth = self.auth.webdav();
            let coroutine = WebdavPropfind::new(
                url,
                &auth,
                USER_AGENT,
                url.path(),
                0,
                &[RESOURCETYPE, SUPPORTED_REPORT_SET],
            );
            let properties: WebdavMultistatus = self.client.run(url, coroutine)?;
            let collection = properties
                .responses
                .iter()
                .find(|entry| {
                    url.join(&entry.href).is_ok_and(|href| {
                        href.origin() == url.origin()
                            && percent_decode_str(href.path().trim_end_matches('/'))
                                .eq(percent_decode_str(url.path().trim_end_matches('/')))
                    })
                })
                .ok_or_else(|| format!("No calendar capabilities returned for {url}"))?;
            if collection.status.is_some_and(|status| status / 100 != 2)
                || !collection.has_resource_type(RESOURCETYPE, CALENDAR)
            {
                return Err(BridgeError {
                    message: format!("Cannot read calendar capabilities at {url}"),
                    status: collection.status,
                    ..Default::default()
                });
            }
            // NOTE: Mail.ru calendars must be enumerated without an unadvertised sync REPORT.
            opts.fallback = !collection
                .supported_reports()
                .contains(SYNC_COLLECTION.local);
        }
        self.sync_collection(url, sync_token, opts)
    }

    /// Batch-fetches the named events of the collection at `url`, via
    /// REPORT `calendar-multiget` (RFC 4791 section 7.9).
    ///
    /// A row answered 404 or 410 is an event gone, left out. Any other row
    /// answered without its data is read alone, and fails the fetch if still
    /// unreadable: an event left out would be retired by a complete round.
    pub fn multiget_events(&mut self, url: &Url, ids: &[&str]) -> Result<Vec<Event>, BridgeError> {
        let auth = self.auth.webdav();
        let coroutine = CaldavItemMultiget::new(url, &auth, USER_AGENT, url.path(), ids);
        let fetched = self.client.run(url, coroutine)?;

        let mut alone = Vec::new();
        for entry in &fetched.unreadable {
            log::warn!("{url}: event {} unreadable: {:?}", entry.id, entry.status);
            if gone_row(entry.status) {
                continue;
            }
            let coroutine = CaldavItemRead::new(url, &auth, USER_AGENT, url.path(), &entry.id);
            match self.client.run(url, coroutine) {
                Ok(body) => {
                    let (ical, ical_base64) = served(body.data);
                    alone.push(Event {
                        id: entry.id.clone(),
                        etag: body.etag,
                        ical,
                        ical_base64,
                    });
                }
                Err(err) if is_gone(&err) => {}
                Err(err) => return Err(unreadable(url, &entry.id, err)),
            }
        }

        let mut events: Vec<Event> = fetched.items.into_iter().map(into_event).collect();
        for event in events.iter_mut().filter(|event| maybe_lossy(&event.ical)) {
            let coroutine = CaldavItemRead::new(url, &auth, USER_AGENT, url.path(), &event.id);
            match self.client.run(url, coroutine) {
                Ok(body) => {
                    let (ical, ical_base64) = served(body.data);
                    if ical_base64.is_some() {
                        event.ical = ical;
                        event.ical_base64 = ical_base64;
                    }
                }
                Err(err) => {
                    log::warn!(
                        "{url}: event {} not read as served: {}",
                        event.id,
                        err.message
                    )
                }
            }
        }
        events.extend(alone);
        Ok(events)
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
                ..Default::default()
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

/// An io-webdav item entry as an [`Event`], its bytes kept as served
/// ([`served`]).
fn into_event(entry: CaldavItemEntry) -> Event {
    let (ical, ical_base64) = served(entry.data);
    Event {
        id: entry.id,
        etag: entry.etag,
        ical,
        ical_base64,
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
    //! # CalDAV tests
    //!
    //! Canned replies exercise collection capabilities, enumeration, fetching
    //! and the names and statuses retained by writes.

    use std::collections::HashMap;

    use io_webdav::coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield};

    use crate::{
        account::{Account, Backend},
        calendar::{Calendars, caldav::*},
        client::{
            Auth, Client,
            fake::{FakeRequest, FakeResponse, FakeServer},
            webdav::Webdav,
        },
    };

    const SYNC_REPORT: &str = "<D:supported-report><D:report>\
        <D:sync-collection/></D:report></D:supported-report>";

    fn multistatus(body: &str) -> FakeResponse {
        FakeResponse {
            content_type: "application/xml",
            body: format!(
                "<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\
                 {body}</D:multistatus>"
            )
            .into_bytes(),
            ..FakeResponse::empty(207)
        }
    }

    fn capabilities(path: &str, reports: &str) -> FakeResponse {
        multistatus(&format!(
            "<D:response><D:href>{path}</D:href><D:propstat><D:prop>\
             <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>\
             <D:supported-report-set>{reports}</D:supported-report-set>\
             </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        ))
    }

    #[test]
    fn calendars_without_collection_sync_are_listed_and_fetched_again() {
        let mut rounds = HashMap::<String, usize>::new();
        let server = FakeServer::new(move |request| {
            let path = &request.path;
            match (request.method.as_str(), request.header("Depth")) {
                ("PROPFIND", Some("0")) => capabilities(path, ""),
                ("PROPFIND", Some("1")) => {
                    let round = rounds.entry(path.clone()).or_default();
                    *round += 1;
                    let ids = if *round == 1 {
                        vec!["keep.ics", "gone.ics"]
                    } else {
                        vec!["keep.ics"]
                    };
                    let rows: String = ids
                        .iter()
                        .map(|id| {
                            format!(
                                "<D:response><D:href>{path}{id}</D:href><D:propstat><D:prop>\
                         <D:getetag>\"e{round}\"</D:getetag></D:prop>\
                         <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
                            )
                        })
                        .collect();
                    multistatus(&rows)
                }
                ("REPORT", _) => {
                    assert!(request.text.contains("calendar-multiget"));
                    assert!(!request.text.contains("sync-collection"));
                    let round = rounds[path];
                    let ids = if round == 1 {
                        vec!["keep.ics", "gone.ics"]
                    } else {
                        vec!["keep.ics"]
                    };
                    let rows: String = ids
                        .iter()
                        .map(|id| {
                            assert!(request.text.contains(&format!("{path}{id}")));
                            format!(
                                "<D:response><D:href>{path}{id}</D:href><D:propstat><D:prop>\
                             <D:getetag>\"e{round}\"</D:getetag><C:calendar-data>\
                             BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n\
                             UID:{path}{id}\r\nDTSTART:20261116T090000Z\r\n\
                             END:VEVENT\r\nEND:VCALENDAR\r\n</C:calendar-data></D:prop>\
                             <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
                            )
                        })
                        .collect();
                    multistatus(&rows)
                }
                _ => panic!("unexpected request: {request:?}"),
            }
        });
        let mut client = Client::new(server);
        let account = Account {
            base_url: "https://calendar.mail.ru/",
            backend: Backend::Carddav,
            auth: Auth::Basic {
                login: "test",
                password: "test",
            },
            zone: "UTC",
        };

        for round in 1..=2 {
            for calendar in ["personal", "work"] {
                let url = format!("https://calendar.mail.ru/calendars/{calendar}/");
                let delta = Calendars {
                    client: &mut client,
                    account: &account,
                }
                .sync_events(&url, None)
                .unwrap();
                assert!(delta.complete);
                assert!(delta.token.is_none());
                assert!(delta.vanished.is_empty());
                assert_eq!(delta.changed.len(), if round == 1 { 2 } else { 1 });
                assert_eq!(
                    delta.changed[0].etag.as_deref(),
                    Some(if round == 1 { "e1" } else { "e2" })
                );
                let ids: Vec<_> = delta
                    .changed
                    .iter()
                    .map(|entry| entry.id.as_str())
                    .collect();
                let events = Webdav {
                    client: &mut client,
                    auth: account.auth,
                }
                .multiget_events(&url.parse().unwrap(), &ids)
                .unwrap();
                assert_eq!(events.len(), ids.len());
                assert!(
                    events
                        .iter()
                        .all(|event| event.ical.contains(&format!("/calendars/{calendar}/")))
                );
            }
        }
        assert_eq!(client.host.requests.len(), 12);
    }

    #[test]
    fn advertised_collection_sync_keeps_incremental_rounds() {
        let server = FakeServer::new(|request| {
            if request.method == "PROPFIND" {
                assert_eq!(request.header("Depth"), Some("0"));
                return capabilities(&request.path, SYNC_REPORT);
            }
            assert_eq!(request.method, "REPORT");
            assert!(request.text.contains("sync-collection"));
            let token = if request.text.contains("checkpoint-1") {
                "checkpoint-2"
            } else {
                "checkpoint-1"
            };
            multistatus(&format!("<D:sync-token>{token}</D:sync-token>"))
        });
        let mut client = Client::new(server);
        let url = "https://caldav.example.org/calendar/".parse().unwrap();
        let auth = Auth::Basic {
            login: "test",
            password: "test",
        };
        let initial = Webdav {
            client: &mut client,
            auth,
        }
        .sync_events(&url, None)
        .unwrap()
        .unwrap();
        assert_eq!(initial.sync_token.as_deref(), Some("checkpoint-1"));
        let next = Webdav {
            client: &mut client,
            auth,
        }
        .sync_events(&url, initial.sync_token.as_deref())
        .unwrap()
        .unwrap();
        assert_eq!(next.sync_token.as_deref(), Some("checkpoint-2"));
        assert_eq!(client.host.requests.len(), 3);
    }

    #[test]
    fn existing_report_refusals_still_use_the_listing_fallback() {
        for status in [400, 405, 501] {
            let server = FakeServer::new(|request| match request.method.as_str() {
                "PROPFIND" if request.header("Depth") == Some("0") => {
                    capabilities(&request.path, SYNC_REPORT)
                }
                "REPORT" => FakeResponse::empty(status),
                "PROPFIND" => {
                    assert_eq!(request.header("Depth"), Some("1"));
                    multistatus("")
                }
                _ => panic!("unexpected request: {request:?}"),
            });
            let mut client = Client::new(server);
            let delta = Webdav {
                client: &mut client,
                auth: Auth::Basic {
                    login: "test",
                    password: "test",
                },
            }
            .sync_events(
                &"https://caldav.example.org/calendar/".parse().unwrap(),
                None,
            )
            .unwrap()
            .unwrap();
            assert!(delta.sync_token.is_none());
            assert_eq!(client.host.requests.len(), 3);
        }
    }

    #[test]
    fn real_http_failures_are_not_reinterpreted_as_missing_report_support() {
        for failed_phase in [
            "capabilities",
            "listing",
            "sync-collection",
            "calendar-multiget",
        ] {
            for status in [404, 401, 403] {
                let server = FakeServer::new(|request| {
                    let phase = match (request.method.as_str(), request.header("Depth")) {
                        ("PROPFIND", Some("0")) => "capabilities",
                        ("PROPFIND", Some("1")) => "listing",
                        ("REPORT", _) if request.text.contains("sync-collection") => {
                            "sync-collection"
                        }
                        ("REPORT", _) => "calendar-multiget",
                        _ => panic!("unexpected request: {request:?}"),
                    };
                    if phase == failed_phase {
                        return FakeResponse::json(
                            status,
                            &serde_json::json!({"title":"not found","type":"caldav"}),
                        );
                    }
                    assert_eq!(phase, "capabilities");
                    capabilities(
                        &request.path,
                        if failed_phase == "sync-collection" {
                            SYNC_REPORT
                        } else {
                            ""
                        },
                    )
                });
                let mut client = Client::new(server);
                let url = "https://caldav.example.org/calendar/".parse().unwrap();
                let mut dav = Webdav {
                    client: &mut client,
                    auth: Auth::Basic {
                        login: "test",
                        password: "test",
                    },
                };
                let error = if failed_phase == "calendar-multiget" {
                    dav.multiget_events(&url, &["keep.ics"]).err().unwrap()
                } else {
                    dav.sync_events(&url, None).err().unwrap()
                };
                assert_eq!(
                    error.status,
                    Some(status),
                    "{failed_phase}: {}",
                    error.message
                );
                assert_eq!(
                    client.host.requests.len(),
                    if matches!(failed_phase, "capabilities" | "calendar-multiget") {
                        1
                    } else {
                        2
                    }
                );
            }
        }
    }

    #[test]
    fn missing_or_mismatched_capabilities_never_become_an_empty_calendar() {
        for path in ["/other/", "https://other.example.org/calendar/"] {
            let server = FakeServer::new(|_| capabilities(path, ""));
            let mut client = Client::new(server);
            let result = Webdav {
                client: &mut client,
                auth: Auth::Basic {
                    login: "test",
                    password: "test",
                },
            }
            .sync_events(
                &"https://caldav.example.org/calendar/".parse().unwrap(),
                None,
            );
            assert!(result.is_err());
            assert_eq!(client.host.requests.len(), 1);
        }
        for body in [
            "",
            "<D:response><D:href>/calendar/</D:href><D:status>HTTP/1.1 404 Not Found</D:status></D:response>",
        ] {
            let server = FakeServer::new(|_| multistatus(body));
            let mut client = Client::new(server);
            assert!(
                Webdav {
                    client: &mut client,
                    auth: Auth::Basic {
                        login: "test",
                        password: "test"
                    },
                }
                .sync_events(
                    &"https://caldav.example.org/calendar/".parse().unwrap(),
                    None
                )
                .is_err()
            );
            assert_eq!(client.host.requests.len(), 1);
        }
    }

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

    /// An entry read whole crosses as the bytes its server sent, a Latin-1
    /// one included, beside the text the phone is shown.
    #[test]
    fn an_entry_read_whole_keeps_the_bytes_served() {
        let served = b"BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:e1\r\n\
            DTSTART:20261013T090000Z\r\nSUMMARY:R\xe9union\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let mut body = b"<?xml version=\"1.0\" encoding=\"utf-8\"?>\
            <d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">\
            <d:response><d:href>/cal/e1.ics</d:href><d:propstat><d:prop>\
            <d:getetag>\"e1\"</d:getetag><c:calendar-data>"
            .to_vec();
        body.extend_from_slice(served);
        body.extend_from_slice(
            b"</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status>\
            </d:propstat></d:response></d:multistatus>",
        );
        let answer = FakeResponse {
            status: 207,
            headers: Vec::new(),
            content_type: "application/xml; charset=utf-8",
            body,
            lost: false,
            cut: false,
        };
        let resource = FakeResponse {
            status: 200,
            body: served.to_vec(),
            ..answer.clone()
        };
        let mut client = Client::new(FakeServer::new(move |request: &FakeRequest| match request
            .method
            .as_str()
        {
            "GET" => resource.clone(),
            _ => answer.clone(),
        }));
        let mut dav = Webdav {
            client: &mut client,
            auth: Auth::Bearer("token"),
        };
        let url: Url = "https://dav.example.com/cal/".parse().unwrap();

        let events = dav.multiget_events(&url, &["e1.ics"]).unwrap();

        let event = serde_json::to_value(&events[0]).unwrap();
        let encoded = event["icalBase64"].as_str().unwrap_or_default();
        assert_eq!(crate::mail::unbase64(encoded).unwrap(), served);
        assert!(
            event["ical"]
                .as_str()
                .unwrap()
                .contains("SUMMARY:R\u{fffd}union")
        );
    }

    /// A multiget answered row by row: `e1.ics` whole, `e2.ics` refused
    /// (403) and `e3.ics` gone (404), `e2.ics` then read alone with `get`.
    fn multiget_refusing_one(get: FakeResponse) -> Result<Vec<Event>, BridgeError> {
        let rows = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\
            <d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">\
            <d:response><d:href>/cal/e1.ics</d:href><d:propstat><d:prop>\
            <d:getetag>\"e1\"</d:getetag><c:calendar-data>\
            BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n\
            UID:e1\r\nSUMMARY:One\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n</c:calendar-data></d:prop>\
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>\
            <d:response><d:href>/cal/e2.ics</d:href>\
            <d:status>HTTP/1.1 403 Forbidden</d:status></d:response>\
            <d:response><d:href>/cal/e3.ics</d:href>\
            <d:status>HTTP/1.1 404 Not Found</d:status></d:response>\
            </d:multistatus>";
        let answer = FakeResponse {
            status: 207,
            content_type: "application/xml; charset=utf-8",
            body: rows.as_bytes().to_vec(),
            ..FakeResponse::empty(207)
        };
        let mut client = Client::new(FakeServer::new(move |request: &FakeRequest| match request
            .method
            .as_str()
        {
            "GET" => {
                assert_eq!(request.path, "/cal/e2.ics", "only the refused row");
                get.clone()
            }
            _ => answer.clone(),
        }));
        let mut dav = Webdav {
            client: &mut client,
            auth: Auth::Bearer("token"),
        };
        let url: Url = "https://dav.example.com/cal/".parse().unwrap();
        dav.multiget_events(&url, &["e1.ics", "e2.ics", "e3.ics"])
    }

    /// A row the server refused is read alone: a member it did not answer
    /// as gone is never left out, so a complete round never retires it.
    #[test]
    fn a_multiget_row_refused_is_read_alone() {
        let event = FakeResponse {
            content_type: "text/calendar",
            body: b"BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:e2\r\n\
                SUMMARY:Two\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
                .to_vec(),
            ..FakeResponse::empty(200)
        };

        let events = multiget_refusing_one(event).unwrap();

        let ids: Vec<&str> = events.iter().map(|event| event.id.as_str()).collect();
        assert_eq!(ids, ["e1.ics", "e2.ics"], "the 404 alone is gone");
        assert!(events[1].ical.contains("SUMMARY:Two"));
    }

    /// A row still unreadable alone fails the read, which fails the round.
    #[test]
    fn a_multiget_row_unreadable_alone_fails_the_read() {
        let refused = multiget_refusing_one(FakeResponse::empty(403));

        assert!(refused.is_err(), "the member would be retired");
    }
}
