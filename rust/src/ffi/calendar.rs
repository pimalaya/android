//! Calendar and event JNI entry points.
//!
//! The listing and the expansion the agenda reads, plus the three verbs
//! its page writes: an entry is created, edited and deleted here, each
//! guarded by a precondition so a shared calendar is never overwritten
//! blind.
//!
//! Three backends, told apart by the account's base URL the way the card
//! and mail entry points do it: a CalDAV context root, the
//! draft-ietf-jmap-calendars verbs behind the `jmap://` marker, or
//! Microsoft Graph behind the `msgraph://` one. All hand the agenda
//! iCalendar text, the JMAP and Graph ones converting their JSON at the
//! client boundary so the store keeps one shape under its `text/calendar`
//! kind.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use serde_json::{from_str, json, to_string};

use crate::{
    account::{self, Backend},
    client::Client,
    ffi::{error_json, parse_url, read_string},
    types::{BridgeError, Calendar, Credentials, Event, EventRef},
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

/// `Native.syncEvents`: enumerates a calendar collection from the
/// cursor the last pass stored, answering which events moved and no
/// bodies. Returns `{changed, vanished, token, complete}`.
///
/// An empty cursor is an initial round. A cursor the server rejects is
/// re-run as one, and the reply says so through `complete`, so the
/// caller never reads a rejected round as an emptied calendar.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_syncEvents<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    cursor: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let cursor = read_string(env, &cursor);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let cursor = Some(cursor.as_str()).filter(|value| !value.is_empty());
        let json = match client.sync_events(&base_url, &url, &credentials, cursor) {
            Ok(delta) => to_string(&delta).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.multigetEvents`: the iCalendar text of the named events, in
/// one round. Returns a JSON array of `{id, etag, ical}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_multigetEvents<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    ids: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let ids = read_string(env, &ids);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match multiget(&mut client, &base_url, &url, &credentials, &ids) {
            Ok(events) => to_string(&events).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// The named events, the ids arriving as a JSON array of strings.
fn multiget(
    client: &mut Client<'_, '_>,
    base_url: &str,
    calendar_url: &str,
    credentials: &Credentials,
    ids: &str,
) -> Result<Vec<Event>, BridgeError> {
    let ids: Vec<String> = from_str(ids).map_err(|err| format!("Invalid id list: {err}"))?;
    let borrowed: Vec<&str> = ids.iter().map(String::as_str).collect();

    client.multiget_events(base_url, calendar_url, credentials, &borrowed)
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
                // NOTE: the listing already put the account-scoped path
                // there; only the base is missing.
                calendar.url = format!("{base_url}/{}", calendar.url);
            }
            Ok(calendars)
        }
        Backend::Graph => {
            let mut calendars = client.list_graph_calendars(credentials.password)?;
            for calendar in &mut calendars {
                calendar.url = format!("{base_url}/{}", calendar.id);
            }
            Ok(calendars)
        }
        Backend::Google => {
            let mut calendars = client.list_gcal_calendars(credentials.password)?;
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

/// `Native.expandEvent`: the occurrences one calendar object denotes
/// inside a civil window, its recurrence set composed (RFC 5545 3.8.5
/// through ical-rs). Pure computation, no transport.
///
/// Returns a JSON array of `{component, start, end, recurrenceId,
/// summary, location, allDay}` objects, each time a `{time, kind, tzid,
/// offset}` the Java side makes an instant of. A non-recurring event
/// yields at most one.
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

/// `Native.writeEvent`: applies one edit to a calendar object and
/// returns the new iCalendar text. A patch through the concrete syntax
/// tree, so everything the form does not manage survives; pure
/// computation, no transport.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_writeEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ical: JString<'local>,
    edit: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let ical = read_string(env, &ical);
        let edit = read_string(env, &edit);

        // NOTE: the reply is the iCalendar itself rather than a JSON
        // wrapper, so an error has to be told apart from a body. It is:
        // an object is a JSON error, and no calendar object starts with
        // a brace.
        let json = match crate::calendar::write(&ical, &edit) {
            Ok(written) => written,
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.splitEvent`: splits a series at the occurrence an edit
/// names, the series ended before it and a new one carrying the edit
/// from it on. Pure computation, no transport. Returns
/// `{master, series}`, `series` null when the occurrence was the
/// series' first and the edit one of all of it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_splitEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ical: JString<'local>,
    edit: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let ical = read_string(env, &ical);
        let edit = read_string(env, &edit);

        let json = match crate::calendar::split(&ical, &edit) {
            Ok((master, series)) => to_string(&json!({ "master": master, "series": series }))
                .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.removeEvent`: removes the occurrences an edit's scope names
/// from a series and returns the iCalendar left, or an empty string
/// when nothing is and the entry itself goes. Pure computation, no
/// transport.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_removeEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ical: JString<'local>,
    edit: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let ical = read_string(env, &ical);
        let edit = read_string(env, &edit);

        // NOTE: told apart from an error the way `writeEvent`'s reply is,
        // and from a removal by being empty.
        let json = match crate::calendar::remove(&ical, &edit) {
            Ok(left) => left.unwrap_or_default(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.updateEvent`: pushes an edited object back to the calendar
/// it came from, guarded by its ETag. Returns the new ETag, or the
/// error the server answered with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_updateEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    calendar_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    ical: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let calendar_url = read_string(env, &calendar_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let ical = read_string(env, &ical);
        let etag = read_string(env, &etag);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match update_event(
            &mut client,
            &base_url,
            &calendar_url,
            &credentials,
            &id,
            &ical,
            &etag,
        ) {
            Ok(etag) => to_string(&etag).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Pushes one edited object with whichever backend its base URL names.
fn update_event(
    client: &mut Client<'_, '_>,
    base_url: &str,
    calendar_url: &str,
    credentials: &Credentials,
    id: &str,
    ical: &str,
    etag: &str,
) -> Result<Option<String>, BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => client.update_jmap_event(
            &account::jmap_session_url(base_url)?,
            credentials,
            id,
            ical,
            Some(etag).filter(|etag| !etag.is_empty()),
        ),
        Backend::Graph => client.update_graph_event(
            credentials.password,
            id,
            ical,
            Some(etag).filter(|etag| !etag.is_empty()),
        ),
        Backend::Google => client.update_gcal_event(
            credentials.password,
            account::book_segment(base_url, calendar_url),
            id,
            ical,
            Some(etag).filter(|etag| !etag.is_empty()),
        ),
        _ => client.update_caldav_event(
            &parse_url(calendar_url)?,
            credentials,
            id,
            ical,
            match etag.is_empty() {
                true => None,
                false => Some(etag),
            },
        ),
    }
}

/// `Native.projectEvent`: one calendar object as the phone's calendar
/// provider carries it (docs/calendar-mapping.md), `now` a UTC stamp
/// placing the window a series the provider cannot show is listed over.
/// Pure computation, no transport. Returns `{uid, master, overrides,
/// listed}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_projectEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ical: JString<'local>,
    now: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let ical = read_string(env, &ical);
        let now = read_string(env, &now);

        let json = match crate::calendar::phone::project(&ical, &now) {
            Ok(view) => to_string(&view).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.applyEvent`: patches a view the phone edited onto the object,
/// the fields it changed alone, so everything else survives byte for
/// byte; an empty object becomes a new one. Pure computation, no
/// transport. Returns the object itself, or a JSON error object.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_applyEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ical: JString<'local>,
    edit: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let ical = read_string(env, &ical);
        let edit = read_string(env, &edit);

        // NOTE: told apart from an error as `writeEvent`'s reply is: no
        // calendar object opens on a brace.
        let json = match crate::calendar::phone::apply(&ical, &edit) {
            Ok(written) => written,
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.readEvent`: one calendar object read whole, for the page that
/// shows it: the series, or the override of the occurrence a non-empty
/// `recurrenceId` names. Pure computation, no transport, like the
/// expansion beside it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_readEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    ical: JString<'local>,
    recurrence_id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let ical = read_string(env, &ical);
        let recurrence_id = read_string(env, &recurrence_id);

        let json = match crate::calendar::read(&ical, &recurrence_id) {
            Ok(detail) => to_string(&detail).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.mergeEvent`: three-way merges a conflicted calendar object,
/// the body staged here and the one its source holds against their base
/// (empty when none was agreed). Pure computation, no transport. Returns
/// `{ical, resolved, conflicts}`, `ical` the resolution when `resolved`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_mergeEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    local: JString<'local>,
    remote: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base = read_string(env, &base);
        let local = read_string(env, &local);
        let remote = read_string(env, &remote);

        let json = match crate::calendar::merge(&base, &local, &remote) {
            Ok(merged) => to_string(&merged).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.resolveEvent`: the resolution of a conflicted calendar object,
/// its merge with each conflict `picks` names taking that side. Pure
/// computation, no transport. Returns the iCalendar text, told apart from
/// an error the way `writeEvent`'s reply is.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_resolveEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    local: JString<'local>,
    remote: JString<'local>,
    picks: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base = read_string(env, &base);
        let local = read_string(env, &local);
        let remote = read_string(env, &remote);
        let picks = read_string(env, &picks);

        let json = match crate::calendar::resolve(&base, &local, &remote, &picks) {
            Ok(resolved) => resolved,
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.newEvent`: the object a new entry starts from, as iCalendar
/// text. Pure computation, no transport: the id, the two stamps and the
/// start's zone with its `VTIMEZONE` are the caller's.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_newEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    component: JString<'local>,
    uid: JString<'local>,
    stamp: JString<'local>,
    start: JString<'local>,
    tzid: JString<'local>,
    vtimezone: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let component = read_string(env, &component);
        let uid = read_string(env, &uid);
        let stamp = read_string(env, &stamp);
        let start = read_string(env, &start);
        let tzid = read_string(env, &tzid);
        let vtimezone = read_string(env, &vtimezone);

        // NOTE: the reply is the iCalendar itself rather than a JSON
        // wrapper, as `writeEvent`'s is, and told apart the same way: an
        // object is an error, and no calendar object opens on a brace.
        let json =
            match crate::calendar::create(&component, &uid, &stamp, &start, &tzid, &vtimezone) {
                Ok(created) => created,
                Err(err) => error_json(err),
            };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.createEvent`: files a new object in a calendar, guarded on
/// the resource not existing. Returns `{id, etag}`, the resource it landed
/// under, or the error the server answered with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_createEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    calendar_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    ical: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let calendar_url = read_string(env, &calendar_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let ical = read_string(env, &ical);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match create_event(
            &mut client,
            &base_url,
            &calendar_url,
            &credentials,
            &id,
            &ical,
        ) {
            Ok(created) => to_string(&created).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteEvent`: removes one object from its calendar, guarded
/// by its ETag. Returns an empty JSON object, or the error the server
/// answered with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_deleteEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    calendar_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let calendar_url = read_string(env, &calendar_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let etag = read_string(env, &etag);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match delete_event(
            &mut client,
            &base_url,
            &calendar_url,
            &credentials,
            &id,
            &etag,
        ) {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Files one new object with whichever backend the base URL names,
/// answering the resource it landed under: the name asked for on CalDAV,
/// the id Graph, Google or the JMAP server minted.
fn create_event(
    client: &mut Client<'_, '_>,
    base_url: &str,
    calendar_url: &str,
    credentials: &Credentials,
    id: &str,
    ical: &str,
) -> Result<EventRef, BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => client.create_jmap_event(
            &account::jmap_session_url(base_url)?,
            credentials,
            account::jmap_collection_id(calendar_url),
            ical,
        ),
        Backend::Graph => client.create_graph_event(
            credentials.password,
            account::book_segment(base_url, calendar_url),
            ical,
        ),
        Backend::Google => client.create_gcal_event(
            credentials.password,
            account::book_segment(base_url, calendar_url),
            ical,
        ),
        _ => {
            let etag =
                client.create_caldav_event(&parse_url(calendar_url)?, credentials, id, ical)?;
            Ok(EventRef {
                id: id.to_string(),
                etag,
            })
        }
    }
}

/// Removes one object with whichever backend the base URL names.
fn delete_event(
    client: &mut Client<'_, '_>,
    base_url: &str,
    calendar_url: &str,
    credentials: &Credentials,
    id: &str,
    etag: &str,
) -> Result<(), BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => client.delete_jmap_event(
            &account::jmap_session_url(base_url)?,
            credentials,
            id,
            Some(etag).filter(|etag| !etag.is_empty()),
        ),
        Backend::Graph => client.delete_graph_event(
            credentials.password,
            id,
            Some(etag).filter(|etag| !etag.is_empty()),
        ),
        Backend::Google => client.delete_gcal_event(
            credentials.password,
            account::book_segment(base_url, calendar_url),
            id,
            Some(etag).filter(|etag| !etag.is_empty()),
        ),
        _ => client.delete_caldav_event(
            &parse_url(calendar_url)?,
            credentials,
            id,
            match etag.is_empty() {
                true => None,
                false => Some(etag),
            },
        ),
    }
}
