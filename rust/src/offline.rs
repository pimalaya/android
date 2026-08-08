//! io-replica engine bridge: runs the offline-first replica engine's
//! coroutines (sync, upgrade, mutate) to completion, upcalling a Java
//! `ReplicaDriver` with one JSON envelope per yield.
//!
//! The engine is I/O-free: storage yields are serviced by the Java
//! CardStore and remote yields by the Java backend clients, so this
//! module only translates between the engine types and the JSON wire
//! shape, and never performs any I/O itself. Yields are batched by
//! design (one fetch carries many handles, one push many changes), so
//! the JNI chatter stays proportional to sync phases, not to cards.
//!
//! Content hashes are opaque here: the Java side computes them
//! (SHA-256 of the vCard bytes) on both the storage and the remote
//! seam, and the engine only ever compares them.

use core::fmt::Display;

use std::collections::BTreeMap;

use io_replica::{
    change::{ReplicaChange, ReplicaWriteOp},
    collection::ReplicaCheckpoint,
    coroutine::*,
    mutate::{ReplicaMutate, ReplicaMutation},
    object::{ReplicaHash, ReplicaObject},
    placement::{
        ReplicaBase, ReplicaFlags, ReplicaHandle, ReplicaLevel, ReplicaLinkId, ReplicaMeta,
        ReplicaOrigin, ReplicaPlacement, ReplicaStatus,
    },
    remote::{
        ReplicaFetchedBody, ReplicaFetchedItem, ReplicaPushOutcome, ReplicaPushResult,
        ReplicaRemoteItem, ReplicaRemoteSnapshot, ReplicaTier,
    },
    storage::ReplicaLoaded,
    sync::{ReplicaSync, ReplicaSyncOptions},
    upgrade::ReplicaUpgrade,
};
use jni::{
    Env, JValue, jni_sig, jni_str,
    objects::{JObject, JString},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, from_str, json};

use crate::{client::clear_and_fail, types::BridgeError};

/// Reconciles `collection` with its remote through the Java driver,
/// returning the sync report as JSON. With `full` the checkpoint is
/// ignored and the whole remote is enumerated (recovery path).
pub fn sync<'local>(
    env: &mut Env<'local>,
    driver: &JObject<'local>,
    collection: &str,
    full: bool,
) -> Result<Value, BridgeError> {
    let opts = ReplicaSyncOptions {
        push: true,
        full,
        ..Default::default()
    };
    let report = Driver::new(env, driver).run(ReplicaSync::new(collection, opts))?;

    Ok(json!({
        "pulled": report.pulled,
        "pushed": report.pushed,
        "conflicts": report.conflicts,
        "rejected": report.rejected,
        "refreshed": report.refreshed,
    }))
}

/// Raises `handles` in `collection` to the full detail tier through
/// the Java driver (bodies deduped by link id against the object
/// store), returning the upgrade report as JSON.
pub fn upgrade<'local>(
    env: &mut Env<'local>,
    driver: &JObject<'local>,
    collection: &str,
    handles: Vec<String>,
) -> Result<Value, BridgeError> {
    let handles = handles.into_iter().map(ReplicaHandle::from).collect();
    let coroutine = ReplicaUpgrade::new(collection, handles, ReplicaTier::Full);
    let report = Driver::new(env, driver).run(coroutine)?;

    Ok(json!({
        "upgraded": report.upgraded,
        "fetched": report.fetched,
        "deduped": report.deduped,
    }))
}

/// Applies a local mutation to `collection` through the Java driver
/// (storage yields only, the remote is never touched).
pub fn mutate<'local>(
    env: &mut Env<'local>,
    driver: &JObject<'local>,
    collection: &str,
    mutation: &str,
) -> Result<(), BridgeError> {
    let mutation: MutationJson =
        from_str(mutation).map_err(|err| format!("Invalid mutation: {err}"))?;
    Driver::new(env, driver).run(ReplicaMutate::new(collection, mutation.into()))
}

/// Upcall handle to the Java `ReplicaDriver` servicing engine yields.
struct Driver<'a, 'local> {
    env: &'a mut Env<'local>,
    driver: &'a JObject<'local>,
}

impl<'a, 'local> Driver<'a, 'local> {
    fn new(env: &'a mut Env<'local>, driver: &'a JObject<'local>) -> Self {
        Self { env, driver }
    }

    /// Runs an offline coroutine to completion, servicing every yield
    /// through the Java driver.
    fn run<C, T, E>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: ReplicaCoroutine<Yield = ReplicaYield, Return = Result<T, E>>,
        E: Display,
    {
        let mut arg: Option<ReplicaArg> = None;

        loop {
            match coroutine.resume(arg.take()) {
                ReplicaCoroutineState::Complete(Ok(value)) => return Ok(value),
                ReplicaCoroutineState::Complete(Err(err)) => return Err(err.to_string().into()),
                ReplicaCoroutineState::Yielded(yielded) => {
                    let reply = self.upcall(&yield_json(&yielded))?;
                    arg = Some(parse_arg(&yielded, &reply)?);
                }
            }
        }
    }

    /// Upcalls `ReplicaDriver.serve` with one yield envelope and
    /// returns the raw JSON reply.
    fn upcall(&mut self, request: &str) -> Result<String, String> {
        let request = self
            .env
            .new_string(request)
            .map_err(|err| err.to_string())?;
        let value = self
            .env
            .call_method(
                self.driver,
                jni_str!("serve"),
                jni_sig!("(Ljava/lang/String;)Ljava/lang/String;"),
                &[JValue::Object(&request)],
            )
            .map_err(|err| clear_and_fail(self.env, "offline driver serve", err))?;
        let object = value.l().map_err(|err| err.to_string())?;
        let reply = unsafe { JString::from_raw(self.env, object.into_raw()) };

        reply.try_to_string(self.env).map_err(|err| err.to_string())
    }
}

/// Serializes one engine yield to its JSON envelope.
fn yield_json(yielded: &ReplicaYield) -> String {
    let envelope = match yielded {
        ReplicaYield::WantsLoad(collection) => json!({
            "op": "load",
            "collection": collection.as_str(),
        }),
        ReplicaYield::WantsLookupObject(links) => json!({
            "op": "lookup",
            "links": links.iter().map(ReplicaLinkId::as_str).collect::<Vec<_>>(),
        }),
        ReplicaYield::WantsWrite(ops) => json!({
            "op": "write",
            "writes": ops.iter().map(WriteOpJson::from).collect::<Vec<_>>(),
        }),
        ReplicaYield::WantsEnumerate { collection, cursor } => json!({
            "op": "enumerate",
            "collection": collection.as_str(),
            "cursor": cursor.as_ref().map(checkpoint_str).filter(|c| !c.is_empty()),
        }),
        ReplicaYield::WantsFetch {
            collection,
            handles,
            tier,
        } => json!({
            "op": "fetch",
            "collection": collection.as_str(),
            "handles": handles.iter().map(ReplicaHandle::as_str).collect::<Vec<_>>(),
            "tier": match tier {
                ReplicaTier::Meta => "meta",
                ReplicaTier::Full => "full",
            },
        }),
        ReplicaYield::WantsPush {
            collection,
            changes,
        } => json!({
            "op": "push",
            "collection": collection.as_str(),
            "changes": changes.iter().map(ChangeJson::from).collect::<Vec<_>>(),
        }),
    };

    envelope.to_string()
}

/// Parses the Java reply matching the pending yield into the engine
/// arg fed back on the next resume. A reply carrying an `error` field
/// aborts the run, keeping the HTTP status the driver reported (a 401
/// surfacing here is what triggers the token refresh upstairs).
fn parse_arg(yielded: &ReplicaYield, reply: &str) -> Result<ReplicaArg, BridgeError> {
    let probe: ErrorJson =
        from_str(reply).map_err(|err| format!("Unreadable driver reply: {err}"))?;
    if let Some(error) = probe.error {
        return Err(BridgeError {
            message: error,
            status: probe.status,
        });
    }

    let arg = match yielded {
        ReplicaYield::WantsLoad(_) => {
            let loaded: LoadedJson = parse(reply)?;
            ReplicaArg::Load(ReplicaLoaded {
                placements: loaded
                    .placements
                    .into_iter()
                    .map(ReplicaPlacement::from)
                    .collect(),
                checkpoint: loaded
                    .checkpoint
                    .filter(|token| !token.is_empty())
                    .map(|token| ReplicaCheckpoint(token.into_bytes())),
            })
        }
        ReplicaYield::WantsLookupObject(_) => {
            let lookup: LookupJson = parse(reply)?;
            let objects: BTreeMap<ReplicaLinkId, ReplicaHash> = lookup
                .objects
                .into_iter()
                .map(|(link, hash)| (ReplicaLinkId(link), ReplicaHash(hash)))
                .collect();
            ReplicaArg::LookupObject(objects)
        }
        ReplicaYield::WantsWrite(_) => ReplicaArg::Write,
        ReplicaYield::WantsEnumerate { .. } => {
            let snapshot: SnapshotJson = parse(reply)?;
            ReplicaArg::Enumerate(ReplicaRemoteSnapshot {
                items: snapshot
                    .items
                    .into_iter()
                    .map(|item| ReplicaRemoteItem {
                        handle: ReplicaHandle(item.handle),
                        flags: ReplicaFlags::from_iter(item.flags),
                        revision: item.revision,
                    })
                    .collect(),
                vanished: snapshot.vanished.into_iter().map(ReplicaHandle).collect(),
                complete: snapshot.complete,
                checkpoint: ReplicaCheckpoint(snapshot.checkpoint.unwrap_or_default().into_bytes()),
            })
        }
        ReplicaYield::WantsFetch { .. } => {
            let fetched: FetchedJson = parse(reply)?;
            let items = fetched
                .items
                .into_iter()
                .map(|item| ReplicaFetchedItem {
                    handle: ReplicaHandle(item.handle),
                    link_id: ReplicaLinkId(item.link_id),
                    meta: ReplicaMeta(item.meta),
                    sort_key: item.sort_key.into(),
                    body: match (item.hash, item.body) {
                        (Some(hash), Some(body)) => Some(ReplicaFetchedBody::Inline {
                            hash: ReplicaHash(hash),
                            bytes: body.into_bytes(),
                        }),
                        _ => None,
                    },
                    revision: item.revision,
                })
                .collect();
            ReplicaArg::Fetch(items)
        }
        ReplicaYield::WantsPush { .. } => {
            let pushed: PushedJson = parse(reply)?;
            let results = pushed
                .results
                .into_iter()
                .map(|result| ReplicaPushResult {
                    handle: ReplicaHandle(result.handle),
                    outcome: if result.accepted {
                        ReplicaPushOutcome::Accepted
                    } else {
                        ReplicaPushOutcome::Rejected
                    },
                    assigned: result.assigned.map(ReplicaHandle),
                    revision: result.revision,
                })
                .collect();
            ReplicaArg::Push(results)
        }
    };

    Ok(arg)
}

fn parse<'de, T: Deserialize<'de>>(reply: &'de str) -> Result<T, String> {
    from_str(reply).map_err(|err| format!("Unreadable driver reply: {err}"))
}

/// Checkpoints are opaque bytes to the engine; every token this app
/// round-trips (WebDAV sync-token, JMAP state) is text, so the wire
/// carries them as plain strings.
fn checkpoint_str(checkpoint: &ReplicaCheckpoint) -> String {
    String::from_utf8_lossy(&checkpoint.0).into_owned()
}

/// Probe for the `error` field any driver reply may carry, plus the
/// HTTP status the driver attaches when the failure was an HTTP round.
#[derive(Deserialize)]
struct ErrorJson {
    #[serde(default)]
    error: Option<String>,
    #[serde(default)]
    status: Option<u16>,
}

/// One placement on the JSON wire, both directions.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct PlacementJson {
    collection: String,
    handle: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    link_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    object: Option<String>,
    level: LevelJson,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    meta: Option<String>,
    /// The presentation sort key (pimdir SPEC.md 9.3). Round-tripped rather
    /// than defaulted on the way out: the reference write is a replace-all, so
    /// a sync that dropped the key would silently reset the ordering of every
    /// item it touched. Empty means unknown.
    #[serde(default)]
    sort_key: String,
    #[serde(default)]
    flags: Vec<String>,
    status: StatusJson,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    conflict_revision: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    base: Option<BaseJson>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    origin: Option<OriginJson>,
}

impl From<PlacementJson> for ReplicaPlacement {
    fn from(wire: PlacementJson) -> Self {
        Self {
            collection: wire.collection.into(),
            handle: ReplicaHandle(wire.handle),
            link_id: wire.link_id.map(ReplicaLinkId),
            object: wire.object.map(ReplicaHash),
            level: wire.level.into(),
            meta: wire.meta.map(ReplicaMeta),
            sort_key: wire.sort_key.into(),
            flags: ReplicaFlags::from_iter(wire.flags),
            status: wire.status.into(),
            conflict_revision: wire.conflict_revision,
            base: wire.base.map(ReplicaBase::from),
            origin: wire.origin.map(ReplicaOrigin::from),
        }
    }
}

impl From<&ReplicaPlacement> for PlacementJson {
    fn from(placement: &ReplicaPlacement) -> Self {
        Self {
            collection: placement.collection.as_str().into(),
            handle: placement.handle.as_str().into(),
            link_id: placement.link_id.as_ref().map(|link| link.as_str().into()),
            object: placement.object.as_ref().map(|hash| hash.as_str().into()),
            level: placement.level.into(),
            meta: placement.meta.as_ref().map(|meta| meta.0.clone()),
            sort_key: placement.sort_key.0.clone(),
            flags: placement.flags.0.iter().cloned().collect(),
            status: placement.status.into(),
            conflict_revision: placement.conflict_revision.clone(),
            base: placement.base.as_ref().map(BaseJson::from),
            origin: placement.origin.as_ref().map(OriginJson::from),
        }
    }
}

/// A placement's sync base on the JSON wire, both directions. The
/// base's existence is the membership base, so it carries no separate
/// present field.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct BaseJson {
    #[serde(default)]
    flags: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    revision: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    object: Option<String>,
}

impl From<BaseJson> for ReplicaBase {
    fn from(wire: BaseJson) -> Self {
        Self {
            flags: ReplicaFlags::from_iter(wire.flags),
            revision: wire.revision,
            object: wire.object.map(ReplicaHash),
        }
    }
}

impl From<&ReplicaBase> for BaseJson {
    fn from(base: &ReplicaBase) -> Self {
        Self {
            flags: base.flags.0.iter().cloned().collect(),
            revision: base.revision.clone(),
            object: base.object.as_ref().map(|hash| hash.as_str().into()),
        }
    }
}

/// A pending create's content source on the JSON wire, both directions.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct OriginJson {
    collection: String,
    handle: String,
}

impl From<OriginJson> for ReplicaOrigin {
    fn from(wire: OriginJson) -> Self {
        Self {
            collection: wire.collection.into(),
            handle: ReplicaHandle(wire.handle),
        }
    }
}

impl From<&ReplicaOrigin> for OriginJson {
    fn from(origin: &ReplicaOrigin) -> Self {
        Self {
            collection: origin.collection.as_str().into(),
            handle: origin.handle.as_str().into(),
        }
    }
}

/// The detail level on the JSON wire.
#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum LevelJson {
    Probed,
    Meta,
    Full,
}

impl From<LevelJson> for ReplicaLevel {
    fn from(wire: LevelJson) -> Self {
        match wire {
            LevelJson::Probed => Self::Probed,
            LevelJson::Meta => Self::Meta,
            LevelJson::Full => Self::Full,
        }
    }
}

impl From<ReplicaLevel> for LevelJson {
    fn from(level: ReplicaLevel) -> Self {
        match level {
            ReplicaLevel::Probed => Self::Probed,
            ReplicaLevel::Meta => Self::Meta,
            ReplicaLevel::Full => Self::Full,
        }
    }
}

/// The sync status on the JSON wire.
#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum StatusJson {
    Clean,
    Dirty,
    Tombstone,
    Conflict,
    Created,
}

impl From<StatusJson> for ReplicaStatus {
    fn from(wire: StatusJson) -> Self {
        match wire {
            StatusJson::Clean => Self::Clean,
            StatusJson::Dirty => Self::Dirty,
            StatusJson::Tombstone => Self::Tombstone,
            StatusJson::Conflict => Self::Conflict,
            StatusJson::Created => Self::Created,
        }
    }
}

impl From<ReplicaStatus> for StatusJson {
    fn from(status: ReplicaStatus) -> Self {
        match status {
            ReplicaStatus::Clean => Self::Clean,
            ReplicaStatus::Dirty => Self::Dirty,
            ReplicaStatus::Tombstone => Self::Tombstone,
            ReplicaStatus::Conflict => Self::Conflict,
            ReplicaStatus::Created => Self::Created,
        }
    }
}

/// A storage write on the JSON wire (engine to Java only).
// NOTE: upserts dominate every write batch, so boxing the placement to
// shrink the enum would only add indirection on the hot variant.
#[allow(clippy::large_enum_variant)]
#[derive(Serialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum WriteOpJson {
    Upsert {
        placement: PlacementJson,
    },
    Drop {
        collection: String,
        handle: String,
    },
    StoreObject {
        hash: String,
        size: usize,
        body: String,
    },
    SetCheckpoint {
        collection: String,
        checkpoint: String,
    },
}

impl From<&ReplicaWriteOp> for WriteOpJson {
    fn from(op: &ReplicaWriteOp) -> Self {
        match op {
            ReplicaWriteOp::UpsertPlacement(placement) => Self::Upsert {
                placement: placement.into(),
            },
            ReplicaWriteOp::DropPlacement { collection, handle } => Self::Drop {
                collection: collection.as_str().into(),
                handle: handle.as_str().into(),
            },
            ReplicaWriteOp::StoreObject { object, body } => Self::StoreObject {
                hash: object.hash.as_str().into(),
                size: object.size,
                // A byteless op (an object streamed into the store during fetch)
                // does not arise for the contacts bridge, whose remote always
                // returns inline bodies; map it to an empty payload for safety.
                body: body
                    .as_deref()
                    .map(|bytes| String::from_utf8_lossy(bytes).into_owned())
                    .unwrap_or_default(),
            },
            ReplicaWriteOp::SetCheckpoint {
                collection,
                checkpoint,
            } => Self::SetCheckpoint {
                collection: collection.as_str().into(),
                checkpoint: checkpoint_str(checkpoint),
            },
        }
    }
}

/// A remote change on the JSON wire (engine to Java only). The `add`
/// variant carries no body: the Java side resolves the staged body
/// from its own store by handle, or by the object hash when the
/// handle is a provisional one it never staged (an engine-side
/// resurrect). The link id is the idempotency key for a retried add.
#[derive(Serialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum ChangeJson {
    Add {
        handle: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        link_id: Option<String>,
        #[serde(skip_serializing_if = "Vec::is_empty")]
        flags: Vec<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        origin: Option<OriginJson>,
        #[serde(skip_serializing_if = "Option::is_none")]
        object: Option<String>,
    },
    Remove {
        handle: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        to: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        if_match: Option<String>,
    },
    SetFlags {
        handle: String,
        flags: Vec<String>,
    },
    Update {
        handle: String,
        object: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        if_match: Option<String>,
    },
}

impl From<&ReplicaChange> for ChangeJson {
    fn from(change: &ReplicaChange) -> Self {
        match change {
            ReplicaChange::Add {
                handle,
                link_id,
                flags,
                origin,
                object,
            } => Self::Add {
                handle: handle.as_str().into(),
                link_id: link_id.as_ref().map(|link| link.as_str().into()),
                flags: flags.0.iter().cloned().collect(),
                origin: origin.as_ref().map(OriginJson::from),
                object: object.as_ref().map(|hash| hash.as_str().into()),
            },
            ReplicaChange::Remove {
                handle,
                to,
                if_match,
            } => Self::Remove {
                handle: handle.as_str().into(),
                to: to.as_ref().map(|to| to.as_str().into()),
                if_match: if_match.clone(),
            },
            ReplicaChange::SetFlags { handle, flags } => Self::SetFlags {
                handle: handle.as_str().into(),
                flags: flags.0.iter().cloned().collect(),
            },
            ReplicaChange::Update {
                handle,
                object,
                if_match,
            } => Self::Update {
                handle: handle.as_str().into(),
                object: object.as_str().into(),
                if_match: if_match.clone(),
            },
        }
    }
}

/// A local mutation on the JSON wire (Java to engine only); the app
/// only stages content edits through the engine.
#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum MutationJson {
    Edit {
        handle: String,
        hash: String,
        size: usize,
        body: String,
        #[serde(default)]
        meta: Option<String>,
        /// Absent leaves the stored key alone; the spec makes a write that
        /// does not restate it preserve it, so an edit that has no new key
        /// must not send one rather than send an empty one.
        #[serde(default)]
        sort_key: Option<String>,
    },
}

impl From<MutationJson> for ReplicaMutation {
    fn from(wire: MutationJson) -> Self {
        match wire {
            MutationJson::Edit {
                handle,
                hash,
                size,
                body,
                meta,
                sort_key,
            } => Self::Edit {
                handle: ReplicaHandle(handle),
                object: ReplicaObject {
                    hash: ReplicaHash(hash),
                    size,
                },
                body: body.into_bytes(),
                meta: meta.map(ReplicaMeta),
                sort_key: sort_key.map(Into::into),
            },
        }
    }
}

/// Reply to a `load` yield.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LoadedJson {
    #[serde(default)]
    placements: Vec<PlacementJson>,
    #[serde(default)]
    checkpoint: Option<String>,
}

/// Reply to a `lookup` yield.
#[derive(Deserialize)]
struct LookupJson {
    #[serde(default)]
    objects: BTreeMap<String, String>,
}

/// Reply to an `enumerate` yield.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotJson {
    #[serde(default)]
    items: Vec<RemoteItemJson>,
    #[serde(default)]
    vanished: Vec<String>,
    complete: bool,
    #[serde(default)]
    checkpoint: Option<String>,
}

/// One enumerated member on the JSON wire.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoteItemJson {
    handle: String,
    #[serde(default)]
    flags: Vec<String>,
    #[serde(default)]
    revision: Option<String>,
}

/// Reply to a `fetch` yield.
#[derive(Deserialize)]
struct FetchedJson {
    #[serde(default)]
    items: Vec<FetchedItemJson>,
}

/// One fetched item on the JSON wire.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct FetchedItemJson {
    handle: String,
    link_id: String,
    #[serde(default)]
    meta: String,
    /// The sort key the remote side derived beside the summary; empty when it
    /// derived none, which is the unknown key rather than one sorting first.
    #[serde(default)]
    sort_key: String,
    #[serde(default)]
    hash: Option<String>,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    revision: Option<String>,
}

/// Reply to a `push` yield.
#[derive(Deserialize)]
struct PushedJson {
    #[serde(default)]
    results: Vec<PushResultJson>,
}

/// One push outcome on the JSON wire.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PushResultJson {
    handle: String,
    accepted: bool,
    #[serde(default)]
    assigned: Option<String>,
    #[serde(default)]
    revision: Option<String>,
}
