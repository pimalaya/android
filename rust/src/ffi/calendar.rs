//! Calendar and event JNI entry points.
//!
//! CalDAV only, read-only: the merged agenda needs listing and nothing
//! else yet, so there is no create, update or delete counterpart to the
//! card verbs.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use serde_json::to_string;

use crate::{
    client::Client,
    ffi::{error_json, parse_url, read_string},
    types::Credentials,
};

/// `Native.listCalendars`: lists the account's calendars off its CalDAV
/// base URL. Returns a JSON array of calendars carrying absolute
/// collection URLs.
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

        let json = match parse_url(&base_url) {
            Ok(url) => match Client::new(env, &transport).list_caldav_calendars(&url, &credentials)
            {
                Ok(calendars) => {
                    to_string(&calendars).unwrap_or_else(|err| error_json(err.to_string()))
                }
                Err(err) => error_json(err),
            },
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.listEvents`: lists a calendar collection's events, each
/// carrying its raw iCalendar text. Returns a JSON array of
/// `{id, etag, ical}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_listEvents<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let json = match parse_url(&url) {
            Ok(url) => match Client::new(env, &transport).list_caldav_events(&url, &credentials) {
                Ok(events) => to_string(&events).unwrap_or_else(|err| error_json(err.to_string())),
                Err(err) => error_json(err),
            },
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
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
