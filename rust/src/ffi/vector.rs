//! # Sync vector exports
//!
//! The seam of the app's sync vector runner (pimdir SYNC §11), in a debug
//! build alone: the `offline*` verbs run as their exports run them,
//! answering the run's events in place of its counts, and the meta a
//! scripted remote names a member by.
//!
//! The Java test class `SyncVectors` declares them; no release library
//! holds them.

use core::fmt::Display;

use io_pimdir::{
    placement::PimdirLinkId,
    summary::{PimdirDerivation, PimdirSummary, derive, file, mail::derive_meta},
    sync::{PimdirParked, PimdirSyncEvent},
};
use jni::{
    EnvUnowned,
    errors::Error,
    objects::{JByteArray, JClass, JObject, JString},
    sys::jboolean,
};
use serde::{Deserialize, Serialize};
use serde_json::{from_str, to_string};

use crate::{
    error::BridgeError,
    ffi::{ErrorReply, error_json, host::JniHost, parse_strings, read_string},
    offline::{
        Driver, OfflineSync, mutating,
        summary::{FileJson, SummaryJson},
        upgrading,
        wire::sorted,
    },
};

/// `SyncVectors.sync`: `Native.offlineSync`, answering [`RanJson`].
///
/// A run its driver failed answers what its landed pages did (SYNC §5),
/// under `error`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_SyncVectors_sync<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    full: jboolean,
    content: jboolean,
    since: JString<'local>,
    scope_bound: jboolean,
    accept_absences: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let since = read_string(env, &since);

        let mut sync = OfflineSync {
            collection: &collection,
            full,
            content,
            since: &since,
            scope_bound,
            accept_absences,
        }
        .coroutine();
        let ran = match Driver::new(JniHost::offline(env, &driver)).drive(&mut sync) {
            Ok(Ok(report)) => RanJson::done(&report.events, report.parked),
            Ok(Err(err)) => RanJson::refused(&sync.report().events, err),
            Err(err) => RanJson::cut(&sync.report().events, err),
        };

        Ok(env.new_string(ran.json())?.into())
    })
    .resolve::<ErrorReply>()
}

/// `SyncVectors.upgrade`: `Native.offlineUpgrade`, answering [`RanJson`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_SyncVectors_upgrade<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    handles: JString<'local>,
    content: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let handles = read_string(env, &handles);

        let json = match parse_strings(&handles) {
            Err(err) => error_json(err),
            Ok(handles) => {
                let mut upgrade = upgrading(&collection, handles, content);
                match Driver::new(JniHost::offline(env, &driver)).drive(&mut upgrade) {
                    Ok(Ok(report)) => RanJson::done(&report.events, None),
                    Ok(Err(err)) => RanJson::refused(&[], err),
                    Err(err) => RanJson::cut(&[], err),
                }
                .json()
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `SyncVectors.mutate`: `Native.offlineMutate`, answering [`RanJson`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_SyncVectors_mutate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    mutation: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let mutation = read_string(env, &mutation);

        let json = match mutating(&collection, &mutation) {
            Err(err) => error_json(err),
            Ok(mut mutate) => {
                match Driver::new(JniHost::offline(env, &driver)).drive(&mut mutate) {
                    Ok(Ok(report)) => RanJson::done(&report.events, None),
                    Ok(Err(err)) => RanJson::refused(&[], err),
                    Err(err) => RanJson::cut(&[], err),
                }
                .json()
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `SyncVectors.named`: what a scripted remote names a member of `kind`
/// by, from the fixture standing for it and what its case states of it
/// ([`StatedJson`]); answers `{"linkId", "sortKey", "summary"?}`.
///
/// A message is named off its header, as a listing names one (Annex A.1);
/// a file by the key and summary its source states (A.7); another kind
/// from its body.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_SyncVectors_named<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    stated: JString<'local>,
    body: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let stated = read_string(env, &stated);
        let body = env.convert_byte_array(&body)?;

        let json = match from_str::<StatedJson>(&stated) {
            Err(err) => error_json(format!("Unreadable stated meta: {err}")),
            Ok(stated) => match named(&kind, stated, &body) {
                None => error_json(format!("No pimdir derivation for {kind}")),
                Some(derived) => to_string(&NamedJson {
                    link_id: &derived.link_id.0,
                    sort_key: derived.sort_key.as_str(),
                    summary: derived.summary.as_ref().map(SummaryJson::from),
                })
                .unwrap_or_else(|err| error_json(err.to_string())),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// What a case states of a member beside its fixture (SYNC §11).
#[derive(Deserialize)]
struct StatedJson {
    /// The size its source states of a message.
    #[serde(default)]
    size: Option<u64>,
    /// The attachment mark its source states of a message.
    #[serde(default)]
    attachment: Option<bool>,
    /// The key its source states of a file.
    #[serde(default)]
    link_id: Option<String>,
    /// The summary its source states of a file.
    #[serde(default)]
    summary: Option<FileJson>,
}

/// The reply of `SyncVectors.named`.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NamedJson<'a> {
    link_id: &'a str,
    sort_key: &'a str,
    #[serde(skip_serializing_if = "Option::is_none", serialize_with = "sorted")]
    summary: Option<SummaryJson>,
}

/// What a run did, as SYNC §11 compares it.
#[derive(Serialize)]
struct RanJson {
    /// The driver's failure that cut the run short.
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    events: Vec<EventJson>,
    /// Whether the engine failed the run.
    failed: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    parked: Option<ParkedJson>,
    /// Why the engine failed it.
    #[serde(skip_serializing_if = "Option::is_none")]
    refusal: Option<String>,
}

impl RanJson {
    /// A run that completed.
    fn done(events: &[PimdirSyncEvent], parked: Option<PimdirParked>) -> Self {
        Self {
            error: None,
            events: events.iter().map(EventJson::from).collect(),
            failed: false,
            parked: parked.map(|parked| ParkedJson {
                absent: parked.absent,
                bound: parked.bound,
            }),
            refusal: None,
        }
    }

    /// A run the engine failed.
    fn refused(events: &[PimdirSyncEvent], refusal: impl Display) -> Self {
        Self {
            failed: true,
            refusal: Some(refusal.to_string()),
            ..Self::done(events, None)
        }
    }

    /// A run its driver cut short.
    fn cut(events: &[PimdirSyncEvent], error: BridgeError) -> Self {
        Self {
            error: Some(error.message),
            ..Self::done(events, None)
        }
    }

    fn json(&self) -> String {
        to_string(self).unwrap_or_else(|err| error_json(err.to_string()))
    }
}

/// A parked round's counts.
#[derive(Serialize)]
struct ParkedJson {
    absent: usize,
    bound: usize,
}

/// One event as a case spells it.
#[derive(Serialize)]
struct EventJson {
    handle: String,
    kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    link_id: Option<String>,
}

impl From<&PimdirSyncEvent> for EventJson {
    fn from(event: &PimdirSyncEvent) -> Self {
        let (kind, handle, link_id) = match event {
            PimdirSyncEvent::Added(handle) => ("Added", handle, None),
            PimdirSyncEvent::FlagsChanged(handle) => ("FlagsChanged", handle, None),
            PimdirSyncEvent::ContentChanged(handle) => ("ContentChanged", handle, None),
            PimdirSyncEvent::Vanished(handle) => ("Vanished", handle, None),
            PimdirSyncEvent::Conflicted(handle) => ("Conflicted", handle, None),
            PimdirSyncEvent::Created(handle) => ("Created", handle, None),
            PimdirSyncEvent::Abandoned(handle) => ("Abandoned", handle, None),
            PimdirSyncEvent::Overruled { handle, link_id } => ("Overruled", handle, Some(link_id)),
            PimdirSyncEvent::Disagreed { handle, link_id } => ("Disagreed", handle, Some(link_id)),
            _ => return Self::unknown(),
        };

        Self {
            handle: handle.as_str().into(),
            kind,
            link_id: link_id.map(|link| link.as_str().into()),
        }
    }
}

impl EventJson {
    /// An event this runner does not know, which no case expects.
    fn unknown() -> Self {
        Self {
            handle: String::new(),
            kind: "Unknown",
            link_id: None,
        }
    }
}

/// The derivation a scripted remote names a member by (SYNC §11).
fn named(kind: &str, stated: StatedJson, body: &[u8]) -> Option<PimdirDerivation> {
    if kind == file::KIND {
        let PimdirSummary::File(summary) = SummaryJson::File(stated.summary?).into() else {
            return None;
        };
        return Some(summary.derivation(PimdirLinkId(stated.link_id?)));
    }

    match kind.split(';').next().unwrap_or_default().trim() {
        "message/rfc822" => Some(derive_meta(
            body,
            Some(stated.size.unwrap_or(body.len() as u64)),
            stated.attachment,
        )),
        _ => derive(kind, body),
    }
}
