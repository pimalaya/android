//! io-pimdir engine bridge: runs the pimdir sync engine's coroutines
//! (sync, upgrade, mutate) to completion, upcalling a Java
//! `OfflineDriver` with one JSON envelope per yield.
//!
//! The engine is I/O-free: storage yields are serviced by the Java
//! pimdir store and remote yields by the Java backend clients, so this
//! module only translates between the engine types and the JSON wire
//! shape, and never performs any I/O itself. Yields are batched by
//! design (one fetch carries many handles, one push many changes), so
//! the JNI chatter stays proportional to sync phases, not to cards.
//!
//! Content hashes are opaque here: the Java side computes them
//! (SHA-256 of the vCard bytes) on both the storage and the remote
//! seam, and the engine only ever compares them. Summaries are not: a
//! placement carries the typed row STORAGE Annex A defines, which
//! [`crate::summary`] translates.

use core::fmt::Display;

use std::collections::BTreeMap;

use io_pimdir::{
    change::{PimdirChange, PimdirChangeKind, PimdirDropReason, PimdirWriteOp},
    collection::PimdirCheckpoint,
    coroutine::*,
    load::{PimdirLoadScope, PimdirLoaded},
    mutate::{PimdirMutate, PimdirMutation},
    object::{PimdirHash, PimdirObject},
    placement::{
        PimdirBase, PimdirFlags, PimdirHandle, PimdirLevel, PimdirLinkId, PimdirOrigin,
        PimdirPlacement, PimdirStatus,
    },
    remote::{
        PimdirFetchedBody, PimdirFetchedItem, PimdirPushOutcome, PimdirPushResult,
        PimdirRemoteItem, PimdirRemoteSnapshot, PimdirTier,
    },
    sync::{PimdirSync, PimdirSyncOptions},
    upgrade::PimdirUpgrade,
};
use jni::{
    Env, JValue, jni_sig, jni_str,
    objects::{JObject, JString},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, from_str, json};

use crate::{client::clear_and_fail, summary::SummaryJson, types::BridgeError};

/// Reconciles `collection` with its remote through the Java driver,
/// returning the sync report as JSON. With `full` the checkpoint is
/// ignored and the whole remote is enumerated (recovery path).
pub fn sync<'local>(
    env: &mut Env<'local>,
    driver: &JObject<'local>,
    collection: &str,
    full: bool,
) -> Result<Value, BridgeError> {
    let opts = PimdirSyncOptions {
        push: true,
        full,
        ..Default::default()
    };
    let report = Driver::new(env, driver).run(PimdirSync::new(collection, opts))?;

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
    let handles = handles.into_iter().map(PimdirHandle::from).collect();
    let coroutine = PimdirUpgrade::new(collection, handles, PimdirTier::Full);
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
    Driver::new(env, driver).run(PimdirMutate::new(collection, mutation.into()))
}

/// Upcall handle to the Java `OfflineDriver` servicing engine yields.
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
        C: PimdirCoroutine<Yield = PimdirYield, Return = Result<T, E>>,
        E: Display,
    {
        let mut arg: Option<PimdirArg> = None;

        loop {
            match coroutine.resume(arg.take()) {
                PimdirCoroutineState::Complete(Ok(value)) => return Ok(value),
                PimdirCoroutineState::Complete(Err(err)) => return Err(err.to_string().into()),
                PimdirCoroutineState::Yielded(yielded) => {
                    let reply = self.upcall(&yield_json(&yielded))?;
                    arg = Some(parse_arg(&yielded, &reply)?);
                }
            }
        }
    }

    /// Upcalls `OfflineDriver.serve` with one yield envelope and
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
fn yield_json(yielded: &PimdirYield) -> String {
    let envelope = match yielded {
        PimdirYield::WantsLoad { collection, scope } => json!({
            "op": "load",
            "collection": collection.as_str(),
            "scope": scope_json(scope),
        }),
        PimdirYield::WantsLookupObject(links) => json!({
            "op": "lookup",
            "links": links.iter().map(PimdirLinkId::as_str).collect::<Vec<_>>(),
        }),
        PimdirYield::WantsWrite(ops) => json!({
            "op": "write",
            "writes": ops.iter().map(WriteOpJson::from).collect::<Vec<_>>(),
        }),
        PimdirYield::WantsEnumerate { collection, cursor } => json!({
            "op": "enumerate",
            "collection": collection.as_str(),
            "cursor": cursor.as_ref().map(checkpoint_str).filter(|c| !c.is_empty()),
        }),
        PimdirYield::WantsFetch {
            collection,
            handles,
            tier,
        } => json!({
            "op": "fetch",
            "collection": collection.as_str(),
            "handles": handles.iter().map(PimdirHandle::as_str).collect::<Vec<_>>(),
            "tier": match tier {
                PimdirTier::Meta => "meta",
                PimdirTier::Full => "full",
            },
        }),
        PimdirYield::WantsPush {
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
fn parse_arg(yielded: &PimdirYield, reply: &str) -> Result<PimdirArg, BridgeError> {
    let probe: ErrorJson =
        from_str(reply).map_err(|err| format!("Unreadable driver reply: {err}"))?;
    if let Some(error) = probe.error {
        return Err(BridgeError {
            message: error,
            status: probe.status,
        });
    }

    let arg = match yielded {
        PimdirYield::WantsLoad { .. } => {
            let loaded: LoadedJson = parse(reply)?;
            PimdirArg::Load(PimdirLoaded {
                placements: loaded
                    .placements
                    .into_iter()
                    .map(PimdirPlacement::from)
                    .collect(),
                checkpoint: loaded
                    .checkpoint
                    .filter(|token| !token.is_empty())
                    .map(|token| PimdirCheckpoint(token.into_bytes())),
            })
        }
        PimdirYield::WantsLookupObject(_) => {
            let lookup: LookupJson = parse(reply)?;
            let objects: BTreeMap<PimdirLinkId, PimdirObject> = lookup
                .objects
                .into_iter()
                .map(|(link, object)| (PimdirLinkId(link), object.into()))
                .collect();
            PimdirArg::LookupObject(objects)
        }
        PimdirYield::WantsWrite(_) => PimdirArg::Write,
        PimdirYield::WantsEnumerate { .. } => {
            let snapshot: SnapshotJson = parse(reply)?;
            PimdirArg::Enumerate(PimdirRemoteSnapshot {
                items: snapshot
                    .items
                    .into_iter()
                    .map(|item| PimdirRemoteItem {
                        handle: PimdirHandle(item.handle),
                        flags: PimdirFlags::from_iter(item.flags),
                        revision: item.revision,
                    })
                    .collect(),
                vanished: snapshot.vanished.into_iter().map(PimdirHandle).collect(),
                complete: snapshot.complete,
                checkpoint: PimdirCheckpoint(snapshot.checkpoint.unwrap_or_default().into_bytes()),
            })
        }
        PimdirYield::WantsFetch { .. } => {
            let fetched: FetchedJson = parse(reply)?;
            let items = fetched
                .items
                .into_iter()
                .map(|item| PimdirFetchedItem {
                    handle: PimdirHandle(item.handle),
                    link_id: PimdirLinkId(item.link_id),
                    summary: item.summary.map(Into::into),
                    sort_key: item.sort_key.into(),
                    body: match (item.hash, item.body) {
                        (Some(hash), Some(body)) => Some(PimdirFetchedBody::Inline {
                            hash: PimdirHash(hash),
                            bytes: body.into_bytes(),
                        }),
                        _ => None,
                    },
                    revision: item.revision,
                })
                .collect();
            PimdirArg::Fetch(items)
        }
        PimdirYield::WantsPush { .. } => {
            let pushed: PushedJson = parse(reply)?;
            let results = pushed
                .results
                .into_iter()
                .map(|result| PimdirPushResult {
                    handle: PimdirHandle(result.handle),
                    outcome: if result.accepted {
                        PimdirPushOutcome::Accepted
                    } else {
                        PimdirPushOutcome::Rejected
                    },
                    assigned: result.assigned.map(PimdirHandle),
                    revision: result.revision,
                })
                .collect();
            PimdirArg::Push(results)
        }
    };

    Ok(arg)
}

fn parse<'de, T: Deserialize<'de>>(reply: &'de str) -> Result<T, String> {
    from_str(reply).map_err(|err| format!("Unreadable driver reply: {err}"))
}

/// Which placements a load has to return, as the JSON wire states it.
///
/// A floor rather than a ceiling: the engine reads only the rows a
/// mutation edits or an upgrade raises, so a whole-collection read costs
/// the size of the mailbox where a flag change costs one row. A storage
/// answering more than the scope asks for stays correct, which is what
/// lets the Java side widen a list too long for one SQLite statement.
fn scope_json(scope: &PimdirLoadScope) -> Value {
    match scope {
        PimdirLoadScope::All => json!({ "kind": "all" }),
        PimdirLoadScope::Handles(handles) => json!({
            "kind": "handles",
            "handles": handles.iter().map(PimdirHandle::as_str).collect::<Vec<_>>(),
        }),
        PimdirLoadScope::Links(links) => json!({
            "kind": "links",
            "links": links.iter().map(PimdirLinkId::as_str).collect::<Vec<_>>(),
        }),
    }
}

/// Checkpoints are opaque bytes to the engine; every token this app
/// round-trips (WebDAV sync-token, JMAP state) is text, so the wire
/// carries them as plain strings.
fn checkpoint_str(checkpoint: &PimdirCheckpoint) -> String {
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
    /// The typed summary row (STORAGE Annex A), absent while nothing has
    /// derived one. A write carrying none leaves the stored row alone,
    /// on the same terms as the sort key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    summary: Option<SummaryJson>,
    /// The presentation sort key (pimdir SPEC.md 9.3). Round-tripped rather
    /// than defaulted on the way out: the reference write is a replace-all, so
    /// a sync that dropped the key would silently reset the ordering of every
    /// item it touched. Empty means unknown.
    #[serde(default)]
    sort_key: String,
    /// The markers, or absent while nobody has read them: an item
    /// enumerated but never fetched holds no opinion about its flags, and
    /// reading that as "no markers" pushes the absence onto whichever
    /// side did know them.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    flags: Option<Vec<String>>,
    status: StatusJson,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    conflict_revision: Option<String>,
    /// The remote body the conflict diverged from, at the revision beside
    /// it, so the resolver reads base, local and remote from the store
    /// alone. Absent until the upgrade pass fetches it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    conflict_object: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    base: Option<BaseJson>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    origin: Option<OriginJson>,
}

impl From<PlacementJson> for PimdirPlacement {
    fn from(wire: PlacementJson) -> Self {
        Self {
            collection: wire.collection.into(),
            handle: PimdirHandle(wire.handle),
            link_id: wire.link_id.map(PimdirLinkId),
            object: wire.object.map(PimdirHash),
            level: wire.level.into(),
            summary: wire.summary.map(Into::into),
            sort_key: wire.sort_key.into(),
            flags: flags_from(wire.flags),
            status: wire.status.into(),
            conflict_revision: wire.conflict_revision,
            conflict_object: wire.conflict_object.map(PimdirHash),
            base: wire.base.map(PimdirBase::from),
            origin: wire.origin.map(PimdirOrigin::from),
        }
    }
}

impl From<&PimdirPlacement> for PlacementJson {
    fn from(placement: &PimdirPlacement) -> Self {
        Self {
            collection: placement.collection.as_str().into(),
            handle: placement.handle.as_str().into(),
            link_id: placement.link_id.as_ref().map(|link| link.as_str().into()),
            object: placement.object.as_ref().map(|hash| hash.as_str().into()),
            level: placement.level.into(),
            summary: placement.summary.as_ref().map(SummaryJson::from),
            sort_key: placement.sort_key.0.clone(),
            flags: flags_json(&placement.flags),
            status: placement.status.into(),
            conflict_revision: placement.conflict_revision.clone(),
            conflict_object: placement
                .conflict_object
                .as_ref()
                .map(|hash| hash.as_str().into()),
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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    flags: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    revision: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    object: Option<String>,
}

impl From<BaseJson> for PimdirBase {
    fn from(wire: BaseJson) -> Self {
        Self {
            flags: flags_from(wire.flags),
            revision: wire.revision,
            object: wire.object.map(PimdirHash),
        }
    }
}

impl From<&PimdirBase> for BaseJson {
    fn from(base: &PimdirBase) -> Self {
        Self {
            flags: flags_json(&base.flags),
            revision: base.revision.clone(),
            object: base.object.as_ref().map(|hash| hash.as_str().into()),
        }
    }
}

/// A known marker set as a JSON array, or `None` for one nobody has read
/// yet: the wire says unknown by leaving the field out, matching the
/// `NULL` column pimdir stores it in.
fn flags_json(flags: &PimdirFlags) -> Option<Vec<String>> {
    flags
        .known()
        .map(|flags| flags.iter().cloned().collect::<Vec<_>>())
}

/// The inverse of [`flags_json`].
fn flags_from(flags: Option<Vec<String>>) -> PimdirFlags {
    match flags {
        Some(flags) => PimdirFlags::from_iter(flags),
        None => PimdirFlags::Unknown,
    }
}

/// A pending create's content source on the JSON wire, both directions.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct OriginJson {
    collection: String,
    handle: String,
}

impl From<OriginJson> for PimdirOrigin {
    fn from(wire: OriginJson) -> Self {
        Self {
            collection: wire.collection.into(),
            handle: PimdirHandle(wire.handle),
        }
    }
}

impl From<&PimdirOrigin> for OriginJson {
    fn from(origin: &PimdirOrigin) -> Self {
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

impl From<LevelJson> for PimdirLevel {
    fn from(wire: LevelJson) -> Self {
        match wire {
            LevelJson::Probed => Self::Probed,
            LevelJson::Meta => Self::Meta,
            LevelJson::Full => Self::Full,
        }
    }
}

impl From<PimdirLevel> for LevelJson {
    fn from(level: PimdirLevel) -> Self {
        match level {
            PimdirLevel::Probed => Self::Probed,
            PimdirLevel::Meta => Self::Meta,
            PimdirLevel::Full => Self::Full,
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

impl From<StatusJson> for PimdirStatus {
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

impl From<PimdirStatus> for StatusJson {
    fn from(status: PimdirStatus) -> Self {
        match status {
            PimdirStatus::Clean => Self::Clean,
            PimdirStatus::Dirty => Self::Dirty,
            PimdirStatus::Tombstone => Self::Tombstone,
            PimdirStatus::Conflict => Self::Conflict,
            PimdirStatus::Created => Self::Created,
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
        reason: DropReasonJson,
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

impl From<&PimdirWriteOp> for WriteOpJson {
    fn from(op: &PimdirWriteOp) -> Self {
        match op {
            PimdirWriteOp::UpsertPlacement(placement) => Self::Upsert {
                placement: placement.into(),
            },
            PimdirWriteOp::DropPlacement {
                collection,
                handle,
                reason,
            } => Self::Drop {
                collection: collection.as_str().into(),
                handle: handle.as_str().into(),
                reason: (*reason).into(),
            },
            PimdirWriteOp::StoreObject { object, body } => Self::StoreObject {
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
            PimdirWriteOp::SetCheckpoint {
                collection,
                checkpoint,
            } => Self::SetCheckpoint {
                collection: collection.as_str().into(),
                checkpoint: checkpoint_str(checkpoint),
            },
        }
    }
}

/// Why a placement is dropped, on the JSON wire.
///
/// The difference is whether the row's disappearance propagates: only a
/// `deleted` drop retires the item. A `superseded` one is a provisional
/// handle an accepted add replaced, and a `rekeyed` one a handle a
/// rebuild renumbered (SYNC §8); the store must read neither as a
/// removal.
#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
enum DropReasonJson {
    Deleted,
    Superseded,
    Rekeyed,
}

impl From<PimdirDropReason> for DropReasonJson {
    fn from(reason: PimdirDropReason) -> Self {
        match reason {
            PimdirDropReason::Deleted => Self::Deleted,
            PimdirDropReason::Superseded => Self::Superseded,
            PimdirDropReason::Rekeyed => Self::Rekeyed,
        }
    }
}

/// A remote change on the JSON wire (engine to Java only). The `add`
/// variant carries no body: the Java side resolves the staged body
/// from its own store by handle, or by the object hash when the
/// handle is a provisional one it never staged (an engine-side
/// resurrect). The link id is the idempotency key for a retried add.
///
/// Every change carries the engine's idempotency `key`, which names the
/// target state it makes true: a driver that records the keys it applied
/// recognises the replay a crash between a serviced push and its
/// recording write produces, now that a run pushes in chunks.
#[derive(Serialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum ChangeJson {
    Add {
        key: String,
        handle: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        link_id: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        flags: Option<Vec<String>>,
        #[serde(skip_serializing_if = "Option::is_none")]
        origin: Option<OriginJson>,
        #[serde(skip_serializing_if = "Option::is_none")]
        object: Option<String>,
    },
    Remove {
        key: String,
        handle: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        to: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        link_id: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        if_match: Option<String>,
    },
    SetFlags {
        key: String,
        handle: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        flags: Option<Vec<String>>,
    },
    Update {
        key: String,
        handle: String,
        object: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        if_match: Option<String>,
    },
}

impl From<&PimdirChange> for ChangeJson {
    fn from(change: &PimdirChange) -> Self {
        let key = change.key.as_str().into();

        match &change.kind {
            PimdirChangeKind::Add {
                handle,
                link_id,
                flags,
                origin,
                object,
            } => Self::Add {
                key,
                handle: handle.as_str().into(),
                link_id: link_id.as_ref().map(|link| link.as_str().into()),
                flags: flags_json(flags),
                origin: origin.as_ref().map(OriginJson::from),
                object: object.as_ref().map(|hash| hash.as_str().into()),
            },
            PimdirChangeKind::Remove {
                handle,
                to,
                link_id,
                if_match,
            } => Self::Remove {
                key,
                handle: handle.as_str().into(),
                to: to.as_ref().map(|to| to.as_str().into()),
                link_id: link_id.as_ref().map(|link| link.as_str().into()),
                if_match: if_match.clone(),
            },
            PimdirChangeKind::SetFlags { handle, flags } => Self::SetFlags {
                key,
                handle: handle.as_str().into(),
                flags: flags_json(flags),
            },
            PimdirChangeKind::Update {
                handle,
                object,
                if_match,
            } => Self::Update {
                key,
                handle: handle.as_str().into(),
                object: object.as_str().into(),
                if_match: if_match.clone(),
            },
        }
    }
}

/// A local mutation on the JSON wire (Java to engine only): the staged
/// write every offline action of every domain is, reconciled by the sync
/// that follows.
#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum MutationJson {
    SetFlags {
        handle: String,
        flags: Vec<String>,
    },
    Remove {
        handle: String,
    },
    Edit {
        handle: String,
        hash: String,
        size: usize,
        body: String,
        #[serde(default)]
        summary: Option<SummaryJson>,
        /// Absent leaves the stored key alone; the spec makes a write that
        /// does not restate it preserve it, so an edit that has no new key
        /// must not send one rather than send an empty one.
        #[serde(default)]
        sort_key: Option<String>,
    },
    Add {
        link_id: String,
        #[serde(default)]
        flags: Vec<String>,
        hash: String,
        size: usize,
        body: String,
        #[serde(default)]
        summary: Option<SummaryJson>,
        /// Stated rather than optional: a create has no stored key to keep,
        /// so the empty string is the key nobody derived.
        #[serde(default)]
        sort_key: String,
    },
}

impl From<MutationJson> for PimdirMutation {
    fn from(wire: MutationJson) -> Self {
        match wire {
            MutationJson::SetFlags { handle, flags } => Self::SetFlags {
                handle: PimdirHandle(handle),
                flags: PimdirFlags::from_iter(flags),
            },
            MutationJson::Remove { handle } => Self::Remove(PimdirHandle(handle)),
            MutationJson::Edit {
                handle,
                hash,
                size,
                body,
                summary,
                sort_key,
            } => Self::Edit {
                handle: PimdirHandle(handle),
                object: PimdirObject {
                    hash: PimdirHash(hash),
                    size,
                },
                body: body.into_bytes(),
                summary: summary.map(Into::into),
                sort_key: sort_key.map(Into::into),
            },
            MutationJson::Add {
                link_id,
                flags,
                hash,
                size,
                body,
                summary,
                sort_key,
            } => Self::Add {
                link_id: PimdirLinkId(link_id),
                flags: PimdirFlags::from_iter(flags),
                object: PimdirObject {
                    hash: PimdirHash(hash),
                    size,
                },
                body: body.into_bytes(),
                summary: summary.map(Into::into),
                sort_key: sort_key.into(),
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
    objects: BTreeMap<String, ObjectJson>,
}

/// A stored body on the JSON wire: the hash naming it and the size the
/// immutable link needs as its witness (SYNC §6).
#[derive(Deserialize)]
struct ObjectJson {
    hash: String,
    size: usize,
}

impl From<ObjectJson> for PimdirObject {
    fn from(wire: ObjectJson) -> Self {
        Self {
            hash: PimdirHash(wire.hash),
            size: wire.size,
        }
    }
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
    /// The summary the remote side derived, absent for a tier or a kind
    /// that yields none.
    #[serde(default)]
    summary: Option<SummaryJson>,
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
