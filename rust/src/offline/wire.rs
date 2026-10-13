//! # Wire
//!
//! The serde mirrors of io-pimdir's engine types on the JSON wire between
//! [`Driver`](crate::offline::Driver) and the Java `OfflineDriver`.

use std::collections::BTreeMap;

use io_pimdir::{
    change::{PimdirChange, PimdirChangeKind, PimdirDropReason, PimdirWriteOp},
    collection::{PimdirCheckpoint, PimdirCursor, PimdirScope},
    mutate::PimdirMutation,
    object::{PimdirHash, PimdirObject},
    placement::{
        PimdirBase, PimdirFlags, PimdirHandle, PimdirLevel, PimdirLinkId, PimdirOrigin,
        PimdirPlacement, PimdirStatus,
    },
    remote::{
        PimdirEnumerated, PimdirFetchedBody, PimdirRemoteItem, PimdirRemoteMeta,
        PimdirRemoteSnapshot,
    },
    summary::{PimdirSummary, mail::PimdirMailSummary},
    sync::PimdirSyncReport,
    upgrade::PimdirUpgradeReport,
};
use serde::{Deserialize, Serialize, Serializer, ser::Error as _};
use serde_json::to_value;

use crate::{
    mail::{base64, unbase64},
    offline::summary::SummaryJson,
};

/// A resume cursor as text, the form this app's connectors write it in.
pub fn cursor_str(cursor: &PimdirCursor) -> String {
    String::from_utf8_lossy(&cursor.0).into_owned()
}

/// A checkpoint as text: opaque bytes to the engine, but every token this
/// app round-trips (WebDAV sync-token, JMAP state) is text.
pub fn checkpoint_str(checkpoint: &PimdirCheckpoint) -> String {
    String::from_utf8_lossy(&checkpoint.0).into_owned()
}

/// Serializes a mirror through a [`Value`](serde_json::Value), keys sorted.
///
/// A typed reply nesting a mirror declared in another order keeps the bytes
/// the `json!` reply it replaced wrote.
pub fn sorted<T: Serialize, S: Serializer>(mirror: &T, serializer: S) -> Result<S::Ok, S::Error> {
    to_value(mirror)
        .map_err(S::Error::custom)?
        .serialize(serializer)
}

/// Probe for the `error` any driver reply may carry, with its HTTP status.
#[derive(Deserialize)]
pub struct ErrorJson {
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default)]
    pub status: Option<u16>,
}

/// One placement on the JSON wire, both directions.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlacementJson {
    collection: String,
    handle: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    link_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    object: Option<String>,
    level: LevelJson,
    /// The typed summary row (STORAGE Annex A), absent while underived.
    ///
    /// A write carrying none leaves the stored row alone, like the sort key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    summary: Option<SummaryJson>,
    /// The presentation sort key (pimdir SPEC.md 9.3), empty when unknown.
    ///
    /// Round-tripped, not defaulted: the write is a replace-all, so dropping
    /// it would silently reset the ordering of every item a sync touched.
    #[serde(default)]
    sort_key: String,
    /// The markers, absent while nobody has read them.
    ///
    /// An item enumerated but never fetched holds no opinion: reading it as
    /// "no markers" would push the absence onto the side that knew them.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    flags: Option<Vec<String>>,
    status: StatusJson,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    conflict_revision: Option<String>,
    /// The remote body the conflict diverged from, at the revision beside it.
    ///
    /// Lets the resolver read base, local and remote from the store alone;
    /// absent until the upgrade pass fetches it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    conflict_object: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    base: Option<BaseJson>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    origin: Option<OriginJson>,
    /// A message's `Date` (Annex A.1), on a load carrying no summary.
    ///
    /// What the engine tells a round's scope by (SYNC §5); read only.
    #[serde(default, skip_serializing)]
    date: Option<String>,
}

impl From<PlacementJson> for PimdirPlacement {
    fn from(wire: PlacementJson) -> Self {
        // NOTE: a date-only summary, which the driver never writes back
        // (`Dated`).
        let summary = match (wire.summary, wire.date.filter(|date| !date.is_empty())) {
            (Some(summary), _) => Some(summary.into()),
            (None, Some(date)) => Some(PimdirSummary::Mail(PimdirMailSummary {
                date: Some(date),
                ..Default::default()
            })),
            (None, None) => None,
        };
        Self {
            collection: wire.collection.into(),
            handle: PimdirHandle(wire.handle),
            link_id: wire.link_id.map(PimdirLinkId),
            object: wire.object.map(PimdirHash),
            level: wire.level.into(),
            summary,
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
            date: None,
        }
    }
}

/// A placement's sync base on the JSON wire, both directions.
///
/// Its existence is the membership base, so no separate present field.
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

/// A known marker set as a JSON array, `None` for one nobody has read.
///
/// The wire says unknown by leaving the field out, as pimdir stores `NULL`.
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
pub struct OriginJson {
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
///
/// `probed`, written by earlier stores for a row no listing named, reads as
/// `meta` (STORAGE §13), restated by the next listing carrying it.
#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum LevelJson {
    #[serde(alias = "probed")]
    Meta,
    Full,
}

impl From<LevelJson> for PimdirLevel {
    fn from(wire: LevelJson) -> Self {
        match wire {
            LevelJson::Meta => Self::Meta,
            LevelJson::Full => Self::Full,
        }
    }
}

impl From<PimdirLevel> for LevelJson {
    fn from(level: PimdirLevel) -> Self {
        match level {
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
// NOTE: upserts dominate write batches; boxing the placement to shrink the
// enum would add indirection on the hot variant.
#[allow(clippy::large_enum_variant)]
#[derive(Serialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum WriteOpJson {
    Upsert {
        placement: PlacementJson,
    },
    Drop {
        collection: String,
        handle: String,
        reason: DropReasonJson,
    },
    /// A body, as text when UTF-8 and base64 otherwise.
    ///
    /// Base64 stores a message's 8-bit bytes as fetched; `persisted`, with no
    /// bytes, writes only the row of one the driver filed while fetching.
    StoreObject {
        hash: String,
        size: usize,
        #[serde(skip_serializing_if = "Option::is_none")]
        body: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        body_base64: Option<String>,
        #[serde(skip_serializing_if = "core::ops::Not::not")]
        persisted: bool,
    },
    SetCheckpoint {
        collection: String,
        checkpoint: String,
    },
    /// Opens a round over a scope (SYNC §5), drawing its id.
    OpenRound {
        collection: String,
        scope: ScopeJson,
        /// Whether it lists only a coverage's gap, inferring no delete of an
        /// undated member (SYNC §5).
        band: bool,
    },
    /// Stamps a page's listed bindings with the open round's id, after the
    /// batch's upserts.
    Stamp {
        collection: String,
        handles: Vec<String>,
    },
    /// Lands a page's resume cursor, and the checkpoint it carried.
    SetRoundCursor {
        collection: String,
        cursor: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        checkpoint: Option<String>,
    },
    /// Closes the open round: its coverage, and the checkpoint it lands.
    CloseRound {
        collection: String,
        coverage: ScopeJson,
        #[serde(skip_serializing_if = "Option::is_none")]
        checkpoint: Option<String>,
    },
    /// Restates a narrower coverage beside a delta's checkpoint.
    SetCoverage {
        collection: String,
        scope: ScopeJson,
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
            PimdirWriteOp::StoreObject { object, body } => {
                let Some(bytes) = body.as_deref() else {
                    return Self::StoreObject {
                        hash: object.hash.as_str().into(),
                        size: object.size,
                        body: None,
                        body_base64: None,
                        persisted: true,
                    };
                };
                let (body, body_base64) = match core::str::from_utf8(bytes) {
                    Ok(text) => (Some(text.to_owned()), None),
                    Err(_) => (None, Some(base64(bytes))),
                };
                Self::StoreObject {
                    hash: object.hash.as_str().into(),
                    size: object.size,
                    body,
                    body_base64,
                    persisted: false,
                }
            }
            PimdirWriteOp::SetCheckpoint {
                collection,
                checkpoint,
            } => Self::SetCheckpoint {
                collection: collection.as_str().into(),
                checkpoint: checkpoint_str(checkpoint),
            },
            PimdirWriteOp::OpenRound {
                collection,
                scope,
                band,
            } => Self::OpenRound {
                collection: collection.as_str().into(),
                scope: scope.into(),
                band: *band,
            },
            PimdirWriteOp::Stamp {
                collection,
                handles,
            } => Self::Stamp {
                collection: collection.as_str().into(),
                handles: handles.iter().map(|h| h.as_str().into()).collect(),
            },
            PimdirWriteOp::SetRoundCursor {
                collection,
                cursor,
                checkpoint,
            } => Self::SetRoundCursor {
                collection: collection.as_str().into(),
                cursor: cursor_str(cursor),
                checkpoint: checkpoint.as_ref().map(checkpoint_str),
            },
            PimdirWriteOp::CloseRound {
                collection,
                coverage,
                checkpoint,
            } => Self::CloseRound {
                collection: collection.as_str().into(),
                coverage: coverage.into(),
                checkpoint: checkpoint.as_ref().map(checkpoint_str),
            },
            PimdirWriteOp::SetCoverage { collection, scope } => Self::SetCoverage {
                collection: collection.as_str().into(),
                scope: scope.into(),
            },
        }
    }
}

/// Why a placement is dropped, on the JSON wire.
///
/// Only `deleted` retires the item: `superseded` (a provisional handle an
/// add replaced) and `rekeyed` (renumbered by a rebuild, SYNC §8) must not
/// read as a removal.
#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum DropReasonJson {
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

/// A remote change on the JSON wire (engine to Java only).
///
/// `add` carries no body (Java resolves it by handle, or by hash for a
/// resurrect) and its link id keys a retry. Each `key` names the state it
/// makes true, so a driver recording applied keys spots a crash's replay.
#[derive(Serialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum ChangeJson {
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

/// A local mutation on the JSON wire (Java to engine only).
///
/// Every offline action of every domain is one, reconciled by the next sync.
#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum MutationJson {
    SetFlags {
        handle: String,
        flags: Vec<String>,
    },
    Remove {
        handle: String,
    },
    /// A move into `target`: a create there, a tombstone here (SYNC §3).
    Move {
        handle: String,
        target: String,
    },
    /// A copy into `target`, the source kept.
    Copy {
        handle: String,
        target: String,
    },
    Edit {
        handle: String,
        hash: String,
        size: usize,
        body: String,
        #[serde(default)]
        summary: Option<SummaryJson>,
        /// Absent leaves the stored key alone, as the spec preserves a key a
        /// write does not restate: no new key sends none, never an empty one.
        #[serde(default)]
        sort_key: Option<String>,
    },
    Add {
        link_id: String,
        #[serde(default)]
        flags: Vec<String>,
        hash: String,
        size: usize,
        #[serde(default)]
        body: Option<String>,
        /// The body as base64 in place of a non-UTF-8 `body`.
        #[serde(default)]
        body_base64: Option<String>,
        #[serde(default)]
        summary: Option<SummaryJson>,
        /// Required: a create has no stored key to keep, empty for underived.
        #[serde(default)]
        sort_key: String,
    },
}

impl TryFrom<MutationJson> for PimdirMutation {
    type Error = String;

    fn try_from(wire: MutationJson) -> Result<Self, String> {
        Ok(match wire {
            MutationJson::SetFlags { handle, flags } => Self::SetFlags {
                handle: PimdirHandle(handle),
                flags: PimdirFlags::from_iter(flags),
            },
            MutationJson::Remove { handle } => Self::Remove(PimdirHandle(handle)),
            MutationJson::Move { handle, target } => Self::Move {
                handle: PimdirHandle(handle),
                target: target.into(),
            },
            MutationJson::Copy { handle, target } => Self::Copy {
                handle: PimdirHandle(handle),
                target: target.into(),
            },
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
                body_base64,
                summary,
                sort_key,
            } => Self::Add {
                link_id: PimdirLinkId(link_id),
                flags: PimdirFlags::from_iter(flags),
                object: PimdirObject {
                    hash: PimdirHash(hash),
                    size,
                },
                body: match (body, body_base64) {
                    (Some(body), _) => body.into_bytes(),
                    (None, Some(encoded)) => unbase64(&encoded)?,
                    (None, None) => return Err("An add carries no body".into()),
                },
                summary: summary.map(Into::into),
                sort_key: sort_key.into(),
            },
        })
    }
}

/// Reply to a `load` yield: the placements and the source's sync state.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoadedJson {
    #[serde(default)]
    pub placements: Vec<PlacementJson>,
    #[serde(default)]
    pub checkpoint: Option<String>,
    /// What the source's last closed round covered, absent before one closed.
    #[serde(default)]
    pub coverage: Option<CoverageJson>,
    /// The source's round under way, absent when none is open.
    #[serde(default)]
    pub round: Option<RoundJson>,
    /// The open round's unstamped based bindings, in scope or undated.
    #[serde(default)]
    pub unstamped: Vec<String>,
}

/// A scope `[since, until)` on the mail `Date`, an absent bound open.
#[derive(Deserialize, Serialize)]
pub struct ScopeJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    since: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    until: Option<String>,
}

impl From<ScopeJson> for PimdirScope {
    fn from(wire: ScopeJson) -> Self {
        Self {
            since: wire.since,
            until: wire.until,
        }
    }
}

impl From<&PimdirScope> for ScopeJson {
    fn from(scope: &PimdirScope) -> Self {
        Self {
            since: scope.since.clone(),
            until: scope.until.clone(),
        }
    }
}

/// A source's coverage on the JSON wire (Java to engine).
#[derive(Deserialize)]
pub struct CoverageJson {
    #[serde(flatten)]
    pub scope: ScopeJson,
    pub at: String,
}

/// A source's open round on the JSON wire (Java to engine).
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoundJson {
    #[serde(flatten)]
    pub scope: ScopeJson,
    #[serde(default)]
    pub cursor: Option<String>,
    #[serde(default)]
    pub checkpoint: Option<String>,
    pub started_at: String,
    /// Whether the round lists only its coverage's gap (SYNC §5).
    #[serde(default)]
    pub band: bool,
}

/// Reply to a `lookup` yield.
#[derive(Deserialize)]
pub struct LookupJson {
    #[serde(default)]
    pub objects: BTreeMap<String, ObjectJson>,
}

/// A stored body: its hash and the size an immutable link needs (SYNC §6).
#[derive(Deserialize)]
pub struct ObjectJson {
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

/// Reply to an `enumerate` yield: one page, or a refused resume cursor.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotJson {
    /// The cursor or delta checkpoint was refused: the round restarts (SYNC
    /// §5).
    #[serde(default)]
    cursor_rejected: bool,
    #[serde(default)]
    items: Vec<RemoteItemJson>,
    #[serde(default)]
    vanished: Vec<String>,
    #[serde(default)]
    complete: bool,
    /// Whether the page closes its listing; one without a cursor always does.
    #[serde(default = "closing")]
    last: bool,
    /// Where the round's next page resumes, on every page but the last.
    #[serde(default)]
    cursor: Option<String>,
    #[serde(default)]
    checkpoint: Option<String>,
}

/// A page closes its listing unless it says otherwise, as non-paging
/// connectors answer.
fn closing() -> bool {
    true
}

impl From<SnapshotJson> for PimdirEnumerated {
    fn from(wire: SnapshotJson) -> Self {
        if wire.cursor_rejected {
            return Self::CursorRejected;
        }

        let cursor = wire
            .cursor
            .filter(|cursor| !cursor.is_empty())
            .map(|cursor| PimdirCursor(cursor.into_bytes()));
        Self::Page(PimdirRemoteSnapshot {
            items: wire.items.into_iter().map(PimdirRemoteItem::from).collect(),
            vanished: wire.vanished.into_iter().map(PimdirHandle).collect(),
            complete: wire.complete,
            last: wire.last || cursor.is_none(),
            cursor,
            checkpoint: wire
                .checkpoint
                .filter(|token| !token.is_empty())
                .map(|token| PimdirCheckpoint(token.into_bytes())),
        })
    }
}

/// One listed member on the JSON wire, named by its meta (SYNC §4).
///
/// The link id is the identity read, else the handle (kinds named by their
/// resource: mail here, calendar resources). A member carrying its body (a
/// DAV listing that read it) carries its hash.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoteItemJson {
    handle: String,
    #[serde(default)]
    flags: Vec<String>,
    #[serde(default)]
    revision: Option<String>,
    #[serde(default)]
    link_id: Option<String>,
    #[serde(default)]
    summary: Option<SummaryJson>,
    #[serde(default)]
    sort_key: String,
    #[serde(default)]
    hash: Option<String>,
    #[serde(default)]
    body: Option<String>,
}

impl From<RemoteItemJson> for PimdirRemoteItem {
    fn from(wire: RemoteItemJson) -> Self {
        let body = match (wire.hash, wire.body) {
            (Some(hash), Some(body)) => Some(PimdirFetchedBody::Inline {
                hash: PimdirHash(hash),
                bytes: body.into_bytes(),
            }),
            _ => None,
        };

        Self {
            meta: PimdirRemoteMeta {
                link_id: PimdirLinkId(wire.link_id.unwrap_or_else(|| wire.handle.clone())),
                summary: wire.summary.map(Into::into),
                sort_key: wire.sort_key.into(),
                body,
            },
            handle: PimdirHandle(wire.handle),
            flags: PimdirFlags::from_iter(wire.flags),
            revision: wire.revision,
        }
    }
}

/// Reply to a `fetch` yield.
#[derive(Deserialize)]
pub struct FetchedJson {
    #[serde(default)]
    pub items: Vec<FetchedItemJson>,
}

/// One fetched item on the JSON wire.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FetchedItemJson {
    pub handle: String,
    pub link_id: String,
    /// The summary the remote derived, absent for a tier or kind without.
    #[serde(default)]
    pub summary: Option<SummaryJson>,
    /// The sort key the remote derived; empty is unknown, not sorting first.
    #[serde(default)]
    pub sort_key: String,
    #[serde(default)]
    pub hash: Option<String>,
    #[serde(default)]
    pub body: Option<String>,
    /// The body as base64 in place of a non-UTF-8 `body`.
    #[serde(default)]
    pub body_base64: Option<String>,
    /// The size of a body the driver filed under `hash`, in place of bytes.
    #[serde(default)]
    pub persisted: Option<usize>,
    #[serde(default)]
    pub revision: Option<String>,
}

/// Reply to a `push` yield.
#[derive(Deserialize)]
pub struct PushedJson {
    #[serde(default)]
    pub results: Vec<PushResultJson>,
}

/// One push outcome on the JSON wire.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushResultJson {
    pub handle: String,
    pub accepted: bool,
    #[serde(default)]
    pub assigned: Option<String>,
    #[serde(default)]
    pub revision: Option<String>,
}

/// One engine yield on the JSON wire (engine to Java only), tagged by `op`.
#[derive(Serialize)]
#[serde(tag = "op", rename_all = "lowercase")]
pub enum YieldJson<'a> {
    Load {
        collection: &'a str,
        scope: LoadScopeJson<'a>,
    },
    Lookup {
        links: Vec<&'a str>,
    },
    Write {
        writes: Vec<WriteOpJson>,
    },
    Enumerate {
        collection: &'a str,
        cursor: Option<String>,
        listing: ListingJson,
        scope: ScopeJson,
    },
    Fetch {
        collection: &'a str,
        handles: Vec<&'a str>,
        tier: TierJson,
    },
    Push {
        collection: &'a str,
        changes: Vec<ChangeJson>,
    },
}

/// The placements a `load` yield asks for, tagged by `kind`.
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum LoadScopeJson<'a> {
    All,
    Handles { handles: Vec<&'a str> },
    Links { links: Vec<&'a str> },
}

/// What an `enumerate` yield lists, tagged by `kind`.
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum ListingJson {
    Delta { checkpoint: String },
    Round { cursor: Option<String>, band: bool },
}

/// The detail tier a `fetch` yield asks for.
#[derive(Serialize)]
#[serde(rename_all = "lowercase")]
pub enum TierJson {
    Meta,
    Full,
}

/// A sync report on the JSON wire (engine to Java only).
#[derive(Serialize)]
pub struct SyncReportJson {
    pub conflicts: usize,
    pub pulled: usize,
    pub pushed: usize,
    pub refreshed: usize,
    pub rejected: usize,
    pub waiting: usize,
}

impl From<&PimdirSyncReport> for SyncReportJson {
    fn from(report: &PimdirSyncReport) -> Self {
        Self {
            conflicts: report.conflicts,
            pulled: report.pulled,
            pushed: report.pushed,
            refreshed: report.refreshed,
            rejected: report.rejected,
            waiting: report.waiting,
        }
    }
}

/// An upgrade report on the JSON wire (engine to Java only).
#[derive(Serialize)]
pub struct UpgradeReportJson {
    pub deduped: usize,
    pub fetched: usize,
    pub upgraded: usize,
}

impl From<&PimdirUpgradeReport> for UpgradeReportJson {
    fn from(report: &PimdirUpgradeReport) -> Self {
        Self {
            deduped: report.deduped,
            fetched: report.fetched,
            upgraded: report.upgraded,
        }
    }
}

#[cfg(test)]
pub mod tests {
    use io_pimdir::{
        change::PimdirWriteOp,
        collection::{PimdirCheckpoint, PimdirCollectionId, PimdirCursor, PimdirScope},
        mutate::PimdirMutation,
        placement::PimdirHandle,
        remote::PimdirEnumerated,
    };
    use serde_json::from_str;

    use crate::offline::wire::{MutationJson, RoundJson, SnapshotJson, WriteOpJson};

    /// A connector's JSON page as the engine reads it.
    pub fn enumerated(reply: &str) -> PimdirEnumerated {
        serde_json::from_str::<SnapshotJson>(reply)
            .expect("a page the engine reads")
            .into()
    }

    fn mutation(json: &str) -> PimdirMutation {
        from_str::<MutationJson>(json).unwrap().try_into().unwrap()
    }

    #[test]
    fn a_move_names_its_target() {
        assert_eq!(
            mutation(r#"{"op": "move", "handle": "42", "target": "a/Trash"}"#),
            PimdirMutation::Move {
                handle: PimdirHandle("42".into()),
                target: PimdirCollectionId("a/Trash".into()),
            }
        );
    }

    #[test]
    fn a_copy_names_its_target() {
        assert_eq!(
            mutation(r#"{"op": "copy", "handle": "42", "target": "a/Archive"}"#),
            PimdirMutation::Copy {
                handle: PimdirHandle("42".into()),
                target: PimdirCollectionId("a/Archive".into()),
            }
        );
    }

    #[test]
    fn an_add_carries_bytes_in_base64() {
        let PimdirMutation::Add { body, .. } = mutation(
            r#"{"op": "add", "linkId": "l", "hash": "h", "size": 3, "bodyBase64": "/wBh"}"#,
        ) else {
            panic!("an add");
        };
        assert_eq!(body, b"\xff\x00a");
    }

    fn page(json: &str) -> PimdirEnumerated {
        from_str::<SnapshotJson>(json).unwrap().into()
    }

    #[test]
    fn a_refused_cursor_reads_as_one() {
        assert_eq!(
            page(r#"{"cursorRejected": true}"#),
            PimdirEnumerated::CursorRejected
        );
    }

    #[test]
    fn a_page_with_a_cursor_is_not_the_last() {
        let PimdirEnumerated::Page(snapshot) = page(
            r#"{"items": [{"handle": "9", "linkId": "9", "sortKey": "2026-10-07T08:00:00Z"}],
                "complete": true, "last": false, "cursor": "7:9", "checkpoint": "7:42"}"#,
        ) else {
            panic!("a page");
        };
        assert!(!snapshot.last);
        assert_eq!(snapshot.cursor, Some(PimdirCursor(b"7:9".to_vec())));
        assert_eq!(
            snapshot.checkpoint,
            Some(PimdirCheckpoint(b"7:42".to_vec()))
        );
        assert_eq!(snapshot.items[0].meta.link_id.as_str(), "9");
        assert_eq!(snapshot.items[0].meta.sort_key.0, "2026-10-07T08:00:00Z");
    }

    #[test]
    fn a_connector_that_does_not_page_answers_one_closing_page() {
        // NOTE: the DAV, Google and Graph contacts drivers' answer verbatim.
        let PimdirEnumerated::Page(snapshot) =
            page(r#"{"items": [{"handle": "a.ics"}], "complete": true}"#)
        else {
            panic!("a page");
        };
        assert!(snapshot.last);
        assert_eq!(snapshot.items[0].meta.link_id.as_str(), "a.ics");
        assert_eq!(snapshot.items[0].meta.body, None);
    }

    #[test]
    fn a_band_round_keeps_its_kind_across_the_wire() {
        // NOTE: the store records the round's kind (SYNC §5).
        let op = WriteOpJson::from(&PimdirWriteOp::OpenRound {
            collection: PimdirCollectionId::from("acct/INBOX"),
            scope: PimdirScope::since("2026-04-01T00:00:00Z"),
            band: true,
        });
        let op = serde_json::to_value(op).unwrap();
        assert_eq!(op["op"], "openRound");
        assert_eq!(op["band"], true);

        let round: RoundJson = from_str(
            r#"{"since": "2026-04-01T00:00:00Z", "startedAt": "2026-10-07T08:00:00Z",
                "band": true}"#,
        )
        .unwrap();
        assert!(round.band);
        let round: RoundJson = from_str(r#"{"startedAt": "2026-10-07T08:00:00Z"}"#).unwrap();
        assert!(
            !round.band,
            "a round loaded with no kind lists its whole scope"
        );
    }
}
