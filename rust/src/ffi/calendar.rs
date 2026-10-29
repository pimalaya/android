//! # Calendar exports
//!
//! The calendar and event exports: the listing and expansion the agenda
//! reads, and the writes its page makes, each guarded by a precondition so
//! a shared calendar is never overwritten blind.
//!
//! [`Calendars`] picks the backend off the account's base URL (CalDAV, JMAP
//! calendars, Graph). All hand iCalendar text, JMAP and Graph converting
//! their JSON at the client boundary, so the store keeps one shape under
//! its `text/calendar` kind.

use jni::{
    EnvUnowned,
    errors::Error,
    objects::{JClass, JObject, JString},
};
use serde::Serialize;
use serde_json::{from_str, to_string};

use crate::{
    calendar::{
        Calendars, EventCreate, EventDelete, EventMove, EventUpdate,
        conflict::{merge, resolve},
        edit::{EventDraft, create, write},
        expand::expand,
        phone,
        read::read,
        series::{remove, split},
        task,
    },
    client::Client,
    ffi::{ErrorReply, error_json, host::JniHost, parse_account, read_string},
};

/// `Native.listCalendars`: lists the account's calendars.
///
/// Returns a JSON array of calendars carrying the collection URL every event
/// listing addresses.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_listCalendars<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);

        let json = match parse_account(&account) {
            Err(err) => error_json(err),
            Ok(account) => {
                let account = account.account();
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut calendars = Calendars {
                    client: &mut client,
                    account: &account,
                };
                match calendars.list_calendars() {
                    Ok(calendars) => {
                        to_string(&calendars).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.syncEvents`: which events of a calendar moved since the cursor.
///
/// No bodies. Returns `{changed, vanished, token, complete}`. An empty cursor
/// is an initial round, and a rejected one is re-run as one, `complete` said
/// so the caller never reads a rejected round as an emptied calendar.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_syncEvents<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
    url: JString<'local>,
    cursor: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);
        let url = read_string(env, &url);
        let cursor = read_string(env, &cursor);
        let cursor = Some(cursor.as_str()).filter(|value| !value.is_empty());

        let json = match parse_account(&account) {
            Err(err) => error_json(err),
            Ok(account) => {
                let account = account.account();
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut calendars = Calendars {
                    client: &mut client,
                    account: &account,
                };
                match calendars.sync_events(&url, cursor) {
                    Ok(delta) => {
                        to_string(&delta).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.multigetEvents`: the iCalendar text of the named events.
///
/// In one round. Returns a JSON array of `{id, etag, ical}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_multigetEvents<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
    url: JString<'local>,
    ids: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);
        let url = read_string(env, &url);
        let ids = read_string(env, &ids);
        let ids = from_str::<Vec<String>>(&ids).map_err(|err| format!("Invalid id list: {err}"));

        let json = match (parse_account(&account), ids) {
            (Err(err), _) | (_, Err(err)) => error_json(err),
            (Ok(account), Ok(ids)) => {
                let account = account.account();
                let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut calendars = Calendars {
                    client: &mut client,
                    account: &account,
                };
                match calendars.multiget_events(&url, &ids) {
                    Ok(events) => {
                        to_string(&events).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.expandEvent`: the occurrences of one object in a civil window.
///
/// Its recurrence set composed (RFC 5545 3.8.5); pure. Returns `[{component,
/// start, end, recurrenceId, summary, location, allDay}]`, each time a
/// `{time, kind, tzid, offset}` Java makes an instant of.
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

        let json = match expand(&ical, &from, &until) {
            Ok(occurrences) => {
                to_string(&occurrences).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.writeEvent`: applies one edit, returning the new iCalendar text.
///
/// A patch through the concrete syntax tree, so everything the form does
/// not manage survives; pure. Returns `{ical}`.
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

        let json = match write(&ical, &edit) {
            Ok(written) => to_string(&IcalReply {
                ical: Some(written),
            })
            .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.splitEvent`: splits a series at the occurrence an edit names.
///
/// The series ends before it and a new one carries the edit on; pure.
/// Returns `{master, series}`, `series` null when the occurrence was the
/// first and the edit one of the whole series.
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

        let json = match split(&ical, &edit) {
            Ok((master, series)) => to_string(&SplitReply { master, series })
                .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.removeEvent`: removes the occurrences an edit's scope names.
///
/// Returns `{ical}`, the iCalendar left, null when nothing is and the
/// entry itself goes; pure.
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

        let json = match remove(&ical, &edit) {
            Ok(left) => to_string(&IcalReply { ical: left })
                .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.updateEvent`: pushes an edited object back, guarded by its ETag.
///
/// Returns `{etag}`, the new ETag, or the error the server answered with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_updateEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
    calendar_url: JString<'local>,
    id: JString<'local>,
    ical: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);
        let calendar_url = read_string(env, &calendar_url);
        let id = read_string(env, &id);
        let ical = read_string(env, &ical);
        let etag = read_string(env, &etag);

        let json = match parse_account(&account) {
            Err(err) => error_json(err),
            Ok(owned) => {
                let account = owned.account();
                let event = EventUpdate {
                    id: &id,
                    ical: &ical,
                    etag: (!etag.is_empty()).then_some(etag.as_str()),
                    address: &owned.address,
                };
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut calendars = Calendars {
                    client: &mut client,
                    account: &account,
                };
                match calendars.update_event(&calendar_url, &event) {
                    Ok(etag) => to_string(&EtagReply { etag })
                        .unwrap_or_else(|err| error_json(err.to_string())),
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.projectEvent`: one object as the phone's calendar provider has it.
///
/// See docs/calendar-mapping.md; `now`, a UTC stamp, places the window a
/// series the provider cannot show is listed over. Pure. Returns `{uid,
/// master, overrides, listed}`.
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

        let json = match phone::project(&ical, &now) {
            Ok(view) => to_string(&view).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.applyEvent`: patches a view the phone edited onto the object.
///
/// Only the fields it changed, the rest surviving byte for byte; an empty
/// object becomes a new one. Pure. Returns `{ical}`, the object byte for
/// byte.
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

        let json = match phone::apply(&ical, &edit) {
            Ok(written) => to_string(&IcalReply {
                ical: Some(written),
            })
            .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.readEvent`: one calendar object read whole, for its page.
///
/// The series, or the override of the occurrence a non-empty
/// `recurrenceId` names; pure.
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

        let json = match read(&ical, &recurrence_id) {
            Ok(detail) => to_string(&detail).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.readTasks`: every to-do among a list of objects, the GTD way.
///
/// Takes `[{key, ical}]` and returns `[{key, task}]` for the objects
/// holding a VTODO, the rest left out; pure. One call for a whole list.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_readTasks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    objects: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let objects = read_string(env, &objects);

        let json = match task::tasks(&objects) {
            Ok(rows) => to_string(&rows).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.mergeEvent`: three-way merges a conflicted calendar object.
///
/// The body staged here and its source's against their base (empty when
/// none was agreed); pure. Returns `{ical, resolved, conflicts}`, `ical`
/// the resolution when `resolved`.
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

        let json = match merge(&base, &local, &remote) {
            Ok(merged) => to_string(&merged).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.resolveEvent`: resolves a conflicted object by its `picks`.
///
/// The merge with each conflict `picks` names taking that side; pure.
/// Returns `{ical}`.
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

        let json = match resolve(&base, &local, &remote, &picks) {
            Ok(resolved) => to_string(&IcalReply {
                ical: Some(resolved),
            })
            .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.newEvent`: the iCalendar text a new entry starts from.
///
/// Pure: the id, the two stamps and the start's zone with its `VTIMEZONE`
/// are the caller's. Returns `{ical}`.
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

        let draft = EventDraft {
            component: &component,
            uid: &uid,
            stamp: &stamp,
            start: &start,
            tzid: &tzid,
            vtimezone: &vtimezone,
        };
        let json = match create(&draft) {
            Ok(created) => to_string(&IcalReply {
                ical: Some(created),
            })
            .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.createEvent`: files a new object, guarded on it not existing.
///
/// Returns `{id, etag}`, the resource it landed under, or the error the
/// server answered with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_createEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
    calendar_url: JString<'local>,
    id: JString<'local>,
    ical: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);
        let calendar_url = read_string(env, &calendar_url);
        let id = read_string(env, &id);
        let ical = read_string(env, &ical);

        let json = match parse_account(&account) {
            Err(err) => error_json(err),
            Ok(owned) => {
                let account = owned.account();
                let event = EventCreate {
                    id: &id,
                    ical: &ical,
                    address: &owned.address,
                };
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut calendars = Calendars {
                    client: &mut client,
                    account: &account,
                };
                match calendars.create_event(&calendar_url, &event) {
                    Ok(created) => {
                        to_string(&created).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.moveEvent`: moves one object into another calendar of the
/// account, guarded by its ETag.
///
/// Returns `{id, etag}`, the resource it landed under, or the error the
/// server answered with (a 501 for a backend that moves nothing).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_moveEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
    calendar_url: JString<'local>,
    id: JString<'local>,
    etag: JString<'local>,
    target_url: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);
        let calendar_url = read_string(env, &calendar_url);
        let id = read_string(env, &id);
        let etag = read_string(env, &etag);
        let target_url = read_string(env, &target_url);

        let json = match parse_account(&account) {
            Err(err) => error_json(err),
            Ok(owned) => {
                let account = owned.account();
                let event = EventMove {
                    id: &id,
                    etag: (!etag.is_empty()).then_some(etag.as_str()),
                    target_url: &target_url,
                };
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut calendars = Calendars {
                    client: &mut client,
                    account: &account,
                };
                match calendars.move_event(&calendar_url, &event) {
                    Ok(moved) => {
                        to_string(&moved).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.deleteEvent`: removes one object, guarded by its ETag.
///
/// Returns `{}`, or the error the server answered with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_deleteEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
    calendar_url: JString<'local>,
    id: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);
        let calendar_url = read_string(env, &calendar_url);
        let id = read_string(env, &id);
        let etag = read_string(env, &etag);

        let json = match parse_account(&account) {
            Err(err) => error_json(err),
            Ok(owned) => {
                let account = owned.account();
                let event = EventDelete {
                    id: &id,
                    etag: (!etag.is_empty()).then_some(etag.as_str()),
                    address: &owned.address,
                };
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut calendars = Calendars {
                    client: &mut client,
                    account: &account,
                };
                match calendars.delete_event(&calendar_url, &event) {
                    Ok(()) => String::from("{}"),
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// The reply of `splitEvent`: `series` null when nothing was split off.
#[derive(Serialize)]
struct SplitReply {
    master: String,
    series: Option<String>,
}

/// The reply of the verbs answering one object: `ical` null when none is
/// left.
#[derive(Serialize)]
struct IcalReply {
    ical: Option<String>,
}

/// The reply of `updateEvent`: `etag` null when the server named none.
#[derive(Serialize)]
struct EtagReply {
    etag: Option<String>,
}
