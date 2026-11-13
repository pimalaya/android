//! # Wire
//!
//! The serde mirrors of io-pimdir's engine types on the JSON wire between
//! [`Driver`](crate::offline::Driver) and the Java `OfflineDriver`.

use std::collections::BTreeMap;

use io_pimdir::{
    change::{PimdirChange, PimdirChangeKind, PimdirDropReason, PimdirWriteOp},
    collection::{PimdirCheckpoint, PimdirCursor, PimdirScope},
    mutate::{PimdirMutateError, PimdirMutateReport, PimdirMutation},
    object::{PimdirHash, PimdirHeldObject, PimdirObject},
    placement::{
        PimdirBase, PimdirFlags, PimdirHandle, PimdirLevel, PimdirLinkId, PimdirOrigin,
        PimdirPlacement, PimdirStatus, PimdirSuperseded,
    },
    remote::{
        PimdirEnumerated, PimdirFetchedBody, PimdirRemoteItem, PimdirRemoteMeta,
        PimdirRemoteSnapshot,
    },
    summary::{PimdirSummary, mail::PimdirMailSummary},
    sync::{PimdirSyncEvent, PimdirSyncReport},
    upgrade::PimdirUpgradeReport,
};
use serde::{Deserialize, Serialize, Serializer, ser::Error as _};
use serde_json::to_value;

use crate::{
    error::BridgeError,
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
    /// A message's `Subject` (Annex A.1), on a load carrying no summary,
    /// beside its date and sender: what tells a listed member reusing a
    /// `Message-ID` from the create it would land (SYNC §6); read only.
    #[serde(default, skip_serializing)]
    subject: Option<String>,
    /// A message's sender's address (Annex A.1), on the same terms.
    #[serde(default, skip_serializing)]
    sender: Option<String>,
}

impl From<PlacementJson> for PimdirPlacement {
    fn from(wire: PlacementJson) -> Self {
        // NOTE: a summary of the date, subject and sender alone, which the
        // driver never writes back (`Dated`).
        let date = wire.date.filter(|date| !date.is_empty());
        let summary = match (wire.summary, date, wire.subject) {
            (Some(summary), _, _) => Some(summary.into()),
            (None, None, None) => None,
            (None, date, subject) => Some(PimdirSummary::Mail(PimdirMailSummary {
                date,
                subject: subject.unwrap_or_default(),
                sender: wire.sender,
                ..Default::default()
            })),
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
            subject: None,
            sender: None,
        }
    }
}

/// A placement's sync base on the JSON wire, both directions.
///
/// Its existence is the membership base, so no separate present field.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BaseJson {
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

/// The member a pending create may be moved from (pimdir SYNC §3), both
/// directions: a load names it, a push carries it on the add.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SupersededJson {
    collection: String,
    handle: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    if_match: Option<String>,
}

impl From<SupersededJson> for PimdirSuperseded {
    fn from(wire: SupersededJson) -> Self {
        Self {
            collection: wire.collection.into(),
            handle: PimdirHandle(wire.handle),
            if_match: wire.if_match.filter(|etag| !etag.is_empty()),
        }
    }
}

impl From<&PimdirSuperseded> for SupersededJson {
    fn from(superseded: &PimdirSuperseded) -> Self {
        Self {
            collection: superseded.collection.as_str().into(),
            handle: superseded.handle.as_str().into(),
            if_match: superseded.if_match.clone(),
        }
    }
}

/// A loaded pending create and the member it may be moved from, on a load
/// reply (`list_superseded_bindings`).
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SupersedingJson {
    /// The pending create's handle.
    pub handle: String,
    /// The member it may be moved from.
    pub supersedes: SupersededJson,
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
    /// Records the create under a provisional handle delivered (pimdir SYNC
    /// §2, §4): its binding gains a base naming nothing while it holds none.
    DeliverPlacement {
        collection: String,
        handle: String,
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
        /// Absent keeps the cursor a page landed (a delta head's write).
        #[serde(skip_serializing_if = "Option::is_none")]
        cursor: Option<String>,
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
    /// Settles the conflict of the next upsert of `handle` by an overruling
    /// edit (SYNC §7, §9): the diverging bodies it does not adopt kept as
    /// retained items, the write refused while one was never read.
    Overrule {
        collection: String,
        handle: String,
    },
    /// Settles the item conflict of the next upsert of `handle` by a
    /// person's edit (SYNC §7): the item takes the edit's body, its conflict
    /// cleared and its diverging body released, nothing kept. The owner
    /// hands it to the hub before the upsert ([`crate::offline::merge`]).
    Settle {
        collection: String,
        handle: String,
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
            PimdirWriteOp::DeliverPlacement { collection, handle } => Self::DeliverPlacement {
                collection: collection.as_str().into(),
                handle: handle.as_str().into(),
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
                cursor: cursor.as_ref().map(cursor_str),
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
            PimdirWriteOp::Overrule { collection, handle } => Self::Overrule {
                collection: collection.as_str().into(),
                handle: handle.as_str().into(),
            },
            PimdirWriteOp::Settle { collection, handle } => Self::Settle {
                collection: collection.as_str().into(),
                handle: handle.as_str().into(),
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
        /// The member a connector able to move may deliver it from.
        #[serde(skip_serializing_if = "Option::is_none")]
        supersedes: Option<SupersededJson>,
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
    /// Two deltas, applied as such, never a whole set (SYNC §4): a flag
    /// the server changed since the listing survives the push.
    SetFlags {
        key: String,
        handle: String,
        add: Vec<String>,
        remove: Vec<String>,
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
                supersedes,
                object,
            } => Self::Add {
                key,
                handle: handle.as_str().into(),
                link_id: link_id.as_ref().map(|link| link.as_str().into()),
                flags: flags_json(flags),
                origin: origin.as_ref().map(OriginJson::from),
                supersedes: supersedes.as_ref().map(SupersededJson::from),
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
            PimdirChangeKind::SetFlags {
                handle,
                add,
                remove,
            } => Self::SetFlags {
                key,
                handle: handle.as_str().into(),
                add: add.iter().cloned().collect(),
                remove: remove.iter().cloned().collect(),
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
        /// The set the caller read and changed into `flags`: the change
        /// alone is applied to the set held (SYNC §7). Absent replaces it.
        #[serde(default)]
        base: Option<Vec<String>>,
    },
    Remove {
        handle: String,
        /// The body the remove was decided over: refused once another
        /// write replaced it (SYNC §7). Absent applies over whatever is
        /// held.
        #[serde(default)]
        if_match: Option<String>,
        /// The diverging bodies a person's resolution by deleting was
        /// decided over (SYNC §7). Absent for an ordinary remove, which
        /// a conflict refuses.
        #[serde(default)]
        resolves: Option<Vec<String>>,
    },
    /// A move into `target`: a create there, a tombstone here (SYNC §3).
    Move {
        handle: String,
        target: String,
        /// Whether the placement's source syncs `target` (SYNC §7).
        #[serde(default)]
        syncs_target: bool,
    },
    /// A copy into `target`, the source kept.
    Copy {
        handle: String,
        target: String,
        /// Whether the placement's source syncs `target` (SYNC §7).
        #[serde(default)]
        syncs_target: bool,
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
        /// The body the edit was made over: refused once another write
        /// replaced it (SYNC §7). Absent applies over whatever is held.
        #[serde(default)]
        if_match: Option<String>,
        /// Whether a rule of the app's settles the conflict rather than a
        /// person (SYNC §7): the bodies it does not adopt are kept.
        #[serde(default)]
        overrule: bool,
        /// The diverging bodies a person's resolution was decided over, the
        /// `conflict_object` of each conflict its form or merge read (SYNC
        /// §7). Absent for an ordinary edit, which a conflict refuses.
        #[serde(default)]
        resolves: Option<Vec<String>>,
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

impl MutationJson {
    /// Whether a copy or a move names a target its source syncs.
    pub fn syncs_target(&self) -> bool {
        match self {
            Self::Move { syncs_target, .. } | Self::Copy { syncs_target, .. } => *syncs_target,
            _ => false,
        }
    }
}

impl TryFrom<MutationJson> for PimdirMutation {
    type Error = String;

    fn try_from(wire: MutationJson) -> Result<Self, String> {
        Ok(match wire {
            MutationJson::SetFlags {
                handle,
                flags,
                base,
            } => Self::SetFlags {
                handle: PimdirHandle(handle),
                flags: PimdirFlags::from_iter(flags),
                base: base.map(PimdirFlags::from_iter),
            },
            MutationJson::Remove {
                handle,
                if_match,
                resolves,
            } => Self::Remove {
                handle: PimdirHandle(handle),
                if_match: if_match.filter(|hash| !hash.is_empty()).map(PimdirHash),
                resolves: resolves.map(|names| names.into_iter().map(PimdirHash).collect()),
            },
            MutationJson::Move { handle, target, .. } => Self::Move {
                handle: PimdirHandle(handle),
                target: target.into(),
            },
            MutationJson::Copy { handle, target, .. } => Self::Copy {
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
                if_match,
                overrule,
                resolves,
            } => Self::Edit {
                handle: PimdirHandle(handle),
                object: PimdirObject {
                    hash: PimdirHash(hash),
                    size,
                },
                body: body.into_bytes(),
                summary: summary.map(Into::into),
                sort_key: sort_key.map(Into::into),
                if_match: if_match.filter(|hash| !hash.is_empty()).map(PimdirHash),
                overrule,
                resolves: resolves.map(|names| names.into_iter().map(PimdirHash).collect()),
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
    /// The loaded tombstones whose remove is held: an add superseded them
    /// and its create is still pending (`list_superseded_pending`).
    #[serde(default)]
    pub held: Vec<String>,
    /// The loaded pending creates superseding a member of their identity
    /// this source binds in another collection, each with that member
    /// (`list_superseded_bindings`, SYNC §3).
    #[serde(default)]
    pub superseded: Vec<SupersedingJson>,
    /// The loaded placements whose item is conflicted (`items.conflicted`,
    /// SYNC §9), each with the item's diverging body, absent while nobody
    /// has read it: what an `Edit` meets beside its binding's conflict.
    #[serde(default)]
    pub conflicted: Vec<ConflictedJson>,
    /// The loaded `Created` placements the source binds not, the copies
    /// the store offers it of items other sources hold (SYNC §3, §9).
    #[serde(default)]
    pub offered: Vec<String>,
}

/// A loaded placement whose item is conflicted, and the item's diverging
/// body (`items.conflict_object`), absent while nobody has read it.
#[derive(Deserialize)]
pub struct ConflictedJson {
    pub handle: String,
    #[serde(default)]
    pub object: Option<String>,
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
    /// Every row of the account holding a body under each link id.
    #[serde(default)]
    pub objects: BTreeMap<String, Vec<HeldObjectJson>>,
}

/// A body a row holds under a link id: its hash, the size an immutable
/// link needs, and what the row's mail summary states, each absent for a
/// row holding no mail summary, which a message never agrees with (SYNC
/// §6).
#[derive(Deserialize)]
pub struct HeldObjectJson {
    hash: String,
    size: usize,
    #[serde(default)]
    subject: Option<String>,
    #[serde(default)]
    date: Option<String>,
    #[serde(default)]
    sender: Option<String>,
}

impl From<HeldObjectJson> for PimdirHeldObject {
    fn from(wire: HeldObjectJson) -> Self {
        Self {
            object: PimdirObject {
                hash: PimdirHash(wire.hash),
                size: wire.size,
            },
            subject: wire.subject,
            date: wire.date,
            sender: wire.sender,
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
    /// Whether the page closes its listing. One saying it does not while
    /// handing no cursor is the engine's to refuse (SYNC §4, §5).
    #[serde(default = "closing")]
    last: bool,
    /// Where the round's next page resumes, on every page but the last.
    #[serde(default)]
    cursor: Option<String>,
    #[serde(default)]
    checkpoint: Option<String>,
    /// Whether a date filter narrowed the round's listing, on every page of
    /// such a round and on no other (SYNC §4, §5).
    #[serde(default)]
    narrowed: bool,
    /// Every handle the collection holds, read by an enumeration no date
    /// filter narrowed, on the last page of a narrowed round.
    #[serde(default)]
    enumerated: Option<Vec<String>>,
    /// Whether the source caps the round's listing (SYNC §4, §5).
    #[serde(default)]
    capped: bool,
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
            last: wire.last,
            cursor,
            checkpoint: wire
                .checkpoint
                .filter(|token| !token.is_empty())
                .map(|token| PimdirCheckpoint(token.into_bytes())),
            narrowed: wire.narrowed,
            enumerated: wire
                .enumerated
                .map(|handles| handles.into_iter().map(PimdirHandle).collect()),
            capped: wire.capped,
        })
    }
}

/// One listed member on the JSON wire, named by its meta (SYNC §4).
///
/// The link id is the identity read, else the handle (kinds named by their
/// resource: calendar resources). A member carrying its body (a DAV listing
/// that read it) carries its hash.
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
    /// Whether an accepted removal carrying `to` relocated the member, which
    /// alone delivers the target's create (pimdir SYNC §4).
    #[serde(default)]
    pub relocated: bool,
}

/// One engine yield on the JSON wire (engine to Java only), tagged by `op`.
#[derive(Serialize)]
#[serde(tag = "op", rename_all = "lowercase")]
pub enum YieldJson<'a> {
    Load {
        collection: &'a str,
        selection: LoadSelectionJson<'a>,
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
    /// Read each body whole and check it against its name (STORAGE §7).
    Verify {
        hashes: Vec<&'a str>,
    },
}

/// The placements a `load` yield asks for, its selection, tagged by `kind`.
#[derive(Serialize)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum LoadSelectionJson<'a> {
    All,
    /// Every placement, as `all`, for a round's last page: the unstamped
    /// bindings answered from `list_unstamped_bindings` bound with
    /// `:narrowed`, `:enumerated` and the enumeration (SYNC §4, §5).
    LastPage {
        narrowed: bool,
        /// Absent when the page hands no unfiltered enumeration.
        #[serde(skip_serializing_if = "Option::is_none")]
        enumerated: Option<Vec<&'a str>>,
    },
    Handles {
        handles: Vec<&'a str>,
    },
    /// The named handles, and every placement that may owe the source a
    /// push: `list_owing_bindings` and `list_lacking_items` (SYNC §10).
    Owing {
        handles: Vec<&'a str>,
    },
    Links {
        links: Vec<&'a str>,
    },
}

/// Reply to a `write` yield: what the cross-source merge recorded beyond
/// the ops (SYNC §5, §9), nothing for a write recording nothing more.
#[derive(Deserialize)]
pub struct WrittenJson {
    /// The handles whose upsert the write recorded as a divergence on the
    /// shared item.
    #[serde(default)]
    pub conflicted: Vec<String>,
    /// The bodies a policy did not adopt, each kept as a retained item.
    #[serde(default)]
    pub overruled: Vec<OverruledJson>,
}

/// A body kept as a retained item, and the handle whose write kept it.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OverruledJson {
    pub handle: String,
    pub link_id: String,
}

/// Reply to a `verify` yield: the bodies that read whole and hash to their
/// name, a missing or corrupt one left out (STORAGE §7).
#[derive(Deserialize)]
pub struct VerifiedJson {
    #[serde(default)]
    pub verified: Vec<String>,
}

/// What an `enumerate` yield lists, tagged by `kind`.
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum ListingJson {
    Delta {
        checkpoint: String,
    },
    Round {
        cursor: Option<String>,
        band: bool,
    },
    /// The open round's head (SYNC §5). Never asked: the bridge syncs with
    /// no heads (`PimdirHeads::NONE`, the default), so a connector reading
    /// one fails on a kind it does not know rather than list it as another.
    Head,
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
/// `absent` and `bound` are a parked round's counts, 0 for a closed one.
pub struct SyncReportJson {
    /// The based bindings a parked round's listing left out.
    pub absent: usize,
    /// The based bindings a parked round counted.
    pub bound: usize,
    pub conflicts: usize,
    /// The bodies a policy did not adopt, kept as retained items (SYNC §9).
    pub overruled: usize,
    /// Whether the round was parked rather than closed, its last page
    /// finding most of its scope absent (SYNC §5).
    pub parked: bool,
    pub pulled: usize,
    pub pushed: usize,
    pub refreshed: usize,
    pub rejected: usize,
    /// The changes withheld because their body failed its name or was
    /// missing (STORAGE §7).
    pub unreadable: usize,
    pub waiting: usize,
}

impl From<&PimdirSyncReport> for SyncReportJson {
    fn from(report: &PimdirSyncReport) -> Self {
        Self {
            absent: report.parked.map_or(0, |parked| parked.absent),
            bound: report.parked.map_or(0, |parked| parked.bound),
            conflicts: report.conflicts,
            overruled: overruled(&report.events),
            parked: report.parked.is_some(),
            pulled: report.pulled,
            pushed: report.pushed,
            refreshed: report.refreshed,
            rejected: report.rejected,
            unreadable: report.unreadable,
            waiting: report.waiting.len(),
        }
    }
}

/// How many bodies the events say a policy kept as retained items.
fn overruled(events: &[PimdirSyncEvent]) -> usize {
    events
        .iter()
        .filter(|event| matches!(event, PimdirSyncEvent::Overruled { .. }))
        .count()
}

/// An upgrade report on the JSON wire (engine to Java only).
#[derive(Serialize)]
pub struct UpgradeReportJson {
    pub deduped: usize,
    pub fetched: usize,
    /// The bodies a policy did not adopt, kept as retained items (SYNC §9).
    pub overruled: usize,
    pub upgraded: usize,
}

impl From<&PimdirUpgradeReport> for UpgradeReportJson {
    fn from(report: &PimdirUpgradeReport) -> Self {
        Self {
            deduped: report.deduped,
            fetched: report.fetched,
            overruled: overruled(&report.events),
            upgraded: report.upgraded,
        }
    }
}

/// A mutation report on the JSON wire (engine to Java only).
#[derive(Serialize)]
pub struct MutateReportJson {
    /// The bodies its write kept as retained items (SYNC §9).
    pub overruled: usize,
}

impl From<&PimdirMutateReport> for MutateReportJson {
    fn from(report: &PimdirMutateReport) -> Self {
        Self {
            overruled: overruled(&report.events),
        }
    }
}

/// A mutation's failure on the JSON wire (engine to Java only): the
/// bridge's `{"error", "status"?}`, and the engine's refusal code when the
/// engine refused it, which Java branches on rather than the message.
#[derive(Debug, Serialize)]
pub struct MutateFailureJson {
    #[serde(flatten)]
    pub error: BridgeError,
    /// io-pimdir's stable code for the refusal (`body-replaced`...), absent
    /// for a failure of the driver or the wire.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub refused: Option<&'static str>,
}

impl From<&PimdirMutateError> for MutateFailureJson {
    fn from(err: &PimdirMutateError) -> Self {
        Self {
            error: err.to_string().into(),
            refused: Some(err.code()),
        }
    }
}

impl From<BridgeError> for MutateFailureJson {
    fn from(error: BridgeError) -> Self {
        Self {
            error,
            refused: None,
        }
    }
}

impl From<String> for MutateFailureJson {
    fn from(message: String) -> Self {
        BridgeError::from(message).into()
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
