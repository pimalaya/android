//! Calendar and event JNI entry points.
//!
//! Read-only: the merged agenda needs listing and nothing else yet, so
//! there is no create, update or delete counterpart to the card verbs.
//!
//! Two backends, told apart by the account's base URL the way the card
//! and mail entry points do it: a CalDAV context root, or the
//! draft-ietf-jmap-calendars verbs behind the `jmap://` marker. Both
//! hand the agenda iCalendar text, the JMAP one converting its
//! JSCalendar payload at the client boundary so the store keeps one
//! shape under its `text/calendar` kind.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use serde_json::to_string;

use crate::{
    account::{self, Backend},
    client::Client,
    ffi::{error_json, parse_url, read_string},
    types::{BridgeError, Calendar, Credentials, Event},
};

/// `Native.listCalendars`: lists the account's calendars off its base
/// URL. Returns a JSON array of calendars carrying the collection URL
/// every event listing addresses.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_listCalendars<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match list_calendars(&mut client, &base_url, &credentials) {
            Ok(calendars) => {
                to_string(&calendars).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listEvents`: lists a calendar collection's events, each
/// carrying its iCalendar text. Returns a JSON array of
/// `{id, etag, ical}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_listEvents<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match list_events(&mut client, &base_url, &url, &credentials) {
            Ok(events) => to_string(&events).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Lists the account's calendars with whichever backend its base URL
/// names, each carrying the URL its events are listed from: the CalDAV
/// collection URL, or the calendar id behind the account's marker.
fn list_calendars(
    client: &mut Client<'_, '_>,
    base_url: &str,
    credentials: &Credentials,
) -> Result<Vec<Calendar>, BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => {
            let session_url = account::jmap_session_url(base_url)?;
            let mut calendars = client.list_jmap_calendars(&session_url, credentials)?;
            for calendar in &mut calendars {
                calendar.url = format!("{base_url}/{}", calendar.id);
            }
            Ok(calendars)
        }
        // NOTE: a calendar account with no sentinel is a CalDAV context
        // root, the only other endpoint the connection flow builds.
        _ => client.list_caldav_calendars(&parse_url(base_url)?, credentials),
    }
}

/// Lists one calendar collection's events, on either backend.
fn list_events(
    client: &mut Client<'_, '_>,
    base_url: &str,
    calendar_url: &str,
    credentials: &Credentials,
) -> Result<Vec<Event>, BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => {
            let session_url = account::jmap_session_url(base_url)?;
            let calendar_id = account::book_segment(base_url, calendar_url);
            client.list_jmap_events(&session_url, credentials, calendar_id)
        }
        _ => client.list_caldav_events(&parse_url(calendar_url)?, credentials),
    }
}

/// `Native.expandEvent`: the occurrences one VEVENT denotes inside a
/// civil window, recurrence expanded (RFC 5545 3.3.10 through ical-rs's
/// `recur` feature). Pure computation, no transport.
///
/// Returns a JSON array of `{start, end, summary, location, allDay}`
/// objects, each start and end a `YYYYMMDDTHHMMSS` civil stamp. A
/// non-recurring event yields at most one.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_expandEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ical: JString<'local>,
    window_start: JString<'local>,
    window_end: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let ical = read_string(env, &ical);
        let from = read_string(env, &window_start);
        let until = read_string(env, &window_end);

        let json = match crate::calendar::expand(&ical, &from, &until) {
            Ok(occurrences) => {
                to_string(&occurrences).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}
