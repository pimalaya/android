//! # Merge
//!
//! The cross-source content merge (SYNC §9) the Java owner runs on every
//! upsert of a mutable kind, decided by io-pimdir's own hub rather than
//! restated: the item and every binding it holds are rebuilt as a
//! one-item [`PimdirHub`], the upsert absorbed after the `Overrule` or
//! `Settle` of its handle the batch stated, as io-pimdir's own write routes
//! them, and what the hub settled read back (the body the item keeps, its
//! conflict, the binding's `shared_object`).
//!
//! Whether the upsert recorded a divergence (`Written.conflicted`), the
//! body a policy did not adopt (`Written.overruled`) and whether the item
//! adopted a body nobody summarized are the hub's own
//! verdict, [`PimdirHubItem::reconcile`] run on a copy of the item as
//! `PimdirHub::absorb` runs it: not for a tombstone, nor for a person's
//! settle of a conflicted item.

use std::collections::BTreeMap;

use io_pimdir::{
    change::PimdirWriteOp,
    codec::conflict_from_str,
    hub::{
        PimdirBinding, PimdirHub, PimdirHubItem, PimdirHubLoserSide, PimdirHubVerdict,
        PimdirSourceId,
    },
    object::PimdirHash,
    placement::{
        PimdirBase, PimdirFlags, PimdirHandle, PimdirLevel, PimdirLinkId, PimdirPlacement,
        PimdirSortKey, PimdirStatus,
    },
};
use serde::{Deserialize, Serialize};

use crate::offline::wire::{BaseJson, PlacementJson};

/// What the owner holds of one item before an upsert, and the upsert.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MergeJson {
    /// The collection's `conflict` policy, `manual` when absent.
    #[serde(default)]
    policy: Option<String>,
    /// The source the upsert is from.
    source: String,
    /// The item as stored, absent when the upsert creates it.
    #[serde(default)]
    item: Option<HeldItemJson>,
    /// Every binding the item holds, by source.
    #[serde(default)]
    bindings: BTreeMap<String, HeldBindingJson>,
    /// The upsert, as the engine wrote it.
    placement: PlacementJson,
    /// Whether an `Overrule` of its handle precedes it in the batch.
    #[serde(default)]
    overruling: bool,
    /// Whether a `Settle` of its handle precedes it in the batch, a
    /// person's edit settling its item's conflict (SYNC §7).
    #[serde(default)]
    settling: bool,
}

/// The item's content columns the merge reads.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct HeldItemJson {
    #[serde(default)]
    object: Option<String>,
    #[serde(default)]
    conflicted: bool,
    #[serde(default)]
    conflict_object: Option<String>,
}

/// One binding's columns the merge reads.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct HeldBindingJson {
    handle: String,
    #[serde(default)]
    base: Option<BaseJson>,
    #[serde(default)]
    conflicted: bool,
    #[serde(default)]
    conflict_revision: Option<String>,
    #[serde(default)]
    conflict_object: Option<String>,
    #[serde(default)]
    shared_object: Option<String>,
    /// The id the source's server gives the member's content (SYNC §4).
    #[serde(default)]
    server_object_id: Option<String>,
}

/// What the merge settled: the item's body and conflict, and the
/// source's `shared_object`, every absent body left out.
#[derive(Debug, Default, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MergedJson {
    /// The body the item holds after the upsert.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub object: Option<String>,
    /// Whether a cross-source conflict stands on the item.
    pub conflicted: bool,
    /// The diverging body the item's conflict recorded.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conflict_object: Option<String>,
    /// What the source last agreed with the shared item (SYNC §9).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shared_object: Option<String>,
    /// The upsert recorded a divergence on the item, reported in
    /// `Written.conflicted` (SYNC §5, §9).
    pub diverged: bool,
    /// The item kept its own body over the upsert's, so its summary and
    /// sort key stay.
    pub kept_shared: bool,
    /// The item adopted a body whose summary it does not hold, for the
    /// owner to derive its summary and sort key from (Annex A.2).
    pub unsummarized: bool,
    /// The body a policy did not adopt, for the owner to keep as a
    /// retained item (SYNC §9, STORAGE §11).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lost: Option<LostJson>,
}

/// A body a policy did not adopt, and which one it was: `shared`, the
/// item's own; `incoming`, the upsert's; `diverging`, the one the item's
/// conflict recorded, which a fill settles.
#[derive(Debug, Eq, PartialEq, Serialize)]
pub struct LostJson {
    /// The body.
    pub object: String,
    /// Which one it was.
    pub side: &'static str,
}

/// Absorbs one upsert into the item it names by io-pimdir's hub.
pub fn merge(input: MergeJson) -> MergedJson {
    let source = PimdirSourceId::from(input.source.as_str());
    let placement = PimdirPlacement::from(input.placement);
    let link = placement
        .link_id
        .clone()
        .unwrap_or_else(|| PimdirLinkId::from(placement.handle.as_str()));
    let policy = input
        .policy
        .as_deref()
        .map(conflict_from_str)
        .unwrap_or_default();

    let sources: BTreeMap<PimdirSourceId, PimdirBinding> = input
        .bindings
        .into_iter()
        .map(|(source, held)| (PimdirSourceId::from(source), binding(held)))
        .collect();
    let mut hub = PimdirHub::default();
    hub.conflict = policy;
    let before = input.item.map(|held| PimdirHubItem {
        flags: PimdirFlags::default(),
        object: held.object.map(PimdirHash),
        summary: None,
        sort_key: PimdirSortKey::default(),
        level: PimdirLevel::Meta,
        deleted: false,
        conflicted: held.conflicted,
        conflict_object: held.conflict_object.map(PimdirHash),
        sources,
    });
    if let Some(item) = &before {
        hub.items.insert(link.clone(), item.clone());
    }
    let settled = input.settling
        && placement.object.is_some()
        && before.as_ref().is_some_and(|item| item.conflicted);
    let verdict = match &before {
        Some(item) if placement.status != PimdirStatus::Tombstone && !settled => {
            item.clone().reconcile(&source, &placement, policy)
        }
        _ => PimdirHubVerdict::default(),
    };

    let mut ops = Vec::with_capacity(3);
    if input.overruling {
        ops.push(PimdirWriteOp::Overrule {
            collection: placement.collection.clone(),
            handle: placement.handle.clone(),
        });
    }
    if input.settling {
        ops.push(PimdirWriteOp::Settle {
            collection: placement.collection.clone(),
            handle: placement.handle.clone(),
        });
    }
    let incoming = placement.object.clone();
    ops.push(PimdirWriteOp::UpsertPlacement(placement));
    hub.absorb(&source, &ops);

    let Some(after) = hub.items.get(&link) else {
        return MergedJson::default();
    };
    let shared_object = after
        .sources
        .get(&source)
        .and_then(|binding| binding.shared_object.clone());
    MergedJson {
        object: after.object.as_ref().map(|hash| hash.0.clone()),
        conflicted: after.conflicted,
        conflict_object: after.conflict_object.as_ref().map(|hash| hash.0.clone()),
        shared_object: shared_object.map(|hash| hash.0),
        diverged: verdict.diverged,
        kept_shared: before.is_some() && after.object != incoming,
        unsummarized: verdict.unsummarized,
        lost: verdict.lost.map(|lost| LostJson {
            object: lost.object.0,
            side: match lost.side {
                PimdirHubLoserSide::Shared => "shared",
                PimdirHubLoserSide::Incoming => "incoming",
                PimdirHubLoserSide::Diverging => "diverging",
            },
        }),
    }
}

/// A binding as the hub holds it.
fn binding(held: HeldBindingJson) -> PimdirBinding {
    PimdirBinding {
        handle: PimdirHandle(held.handle),
        base: held.base.map(PimdirBase::from),
        conflicted: held.conflicted,
        conflict_revision: held.conflict_revision,
        conflict_object: held.conflict_object.map(PimdirHash),
        shared_object: held.shared_object.map(PimdirHash),
        server_object_id: held.server_object_id,
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use crate::offline::merge::{MergedJson, merge};

    fn run(input: serde_json::Value) -> MergedJson {
        merge(serde_json::from_value(input).unwrap())
    }

    fn placement(object: &str, base: serde_json::Value) -> serde_json::Value {
        json!({
            "collection": "acct/book",
            "handle": "a.vcf",
            "linkId": "uid-a",
            "object": object,
            "level": "full",
            "status": "clean",
            "base": base,
        })
    }

    /// A body only this source changed fast-forwards the item, and the
    /// binding agrees with it.
    #[test]
    fn a_change_one_source_made_flows_in() {
        let merged = run(json!({
            "source": "server",
            "item": { "object": "b1" },
            "bindings": {
                "server": { "handle": "a.vcf", "base": { "object": "b1", "revision": "e1" }, "sharedObject": "b1" },
                "phone": { "handle": "1", "base": { "object": "b1", "revision": "v1" }, "sharedObject": "b1" },
            },
            "placement": placement("b2", json!({ "object": "b2", "revision": "e2" })),
        }));
        assert_eq!(
            merged,
            MergedJson {
                object: Some("b2".into()),
                shared_object: Some("b2".into()),
                ..MergedJson::default()
            }
        );
    }

    /// The server and the phone both changed the card: the item keeps the
    /// shared body, records the incoming one and stands conflicted, and the
    /// agreement point does not move (SYNC §9).
    #[test]
    fn two_sources_changing_one_item_diverge() {
        let merged = run(json!({
            "source": "server",
            "item": { "object": "phone2" },
            "bindings": {
                "server": { "handle": "a.vcf", "base": { "object": "b1", "revision": "e1" }, "sharedObject": "b1" },
                "phone": { "handle": "1", "base": { "object": "phone2", "revision": "v2" }, "sharedObject": "phone2" },
            },
            "placement": placement("server2", json!({ "object": "server2", "revision": "e2" })),
        }));
        assert_eq!(
            merged,
            MergedJson {
                object: Some("phone2".into()),
                conflicted: true,
                conflict_object: Some("server2".into()),
                shared_object: Some("b1".into()),
                diverged: true,
                kept_shared: true,
                unsummarized: false,
                lost: None,
            }
        );
    }

    /// An upsert carrying no body agreed to no body: the shared object
    /// stays where it was.
    #[test]
    fn a_bodiless_upsert_moves_no_agreement() {
        let mut bodiless = placement("x", json!({ "revision": "e2" }));
        bodiless.as_object_mut().unwrap().remove("object");
        bodiless["level"] = json!("meta");
        let merged = run(json!({
            "source": "server",
            "item": { "object": "b1" },
            "bindings": {
                "server": { "handle": "a.vcf", "base": { "revision": "e1" }, "sharedObject": "b1" },
            },
            "placement": bodiless,
        }));
        assert_eq!(merged.shared_object.as_deref(), Some("b1"));
        assert!(!merged.diverged);
    }

    /// A first binding agrees with the body it brings; the sync base
    /// stands in until a source has folded once.
    #[test]
    fn a_new_item_agrees_with_its_body() {
        let merged = run(json!({
            "source": "server",
            "placement": placement("b1", json!({ "object": "b1", "revision": "e1" })),
        }));
        assert_eq!(merged.object.as_deref(), Some("b1"));
        assert_eq!(merged.shared_object.as_deref(), Some("b1"));
        assert!(!merged.kept_shared && !merged.conflicted);

        let folded_never = run(json!({
            "source": "server",
            "item": { "object": "b1" },
            "bindings": {
                "server": { "handle": "a.vcf", "base": { "object": "b1", "revision": "e1" } },
            },
            "placement": placement("b2", json!({ "object": "b2", "revision": "e2" })),
        }));
        assert_eq!(
            folded_never.object.as_deref(),
            Some("b2"),
            "the base stood in"
        );
        assert!(!folded_never.diverged);
    }

    /// An edit settling the item's conflict clears it and agrees again.
    #[test]
    fn an_edit_settles_the_item_conflict() {
        let merged = run(json!({
            "source": "phone",
            "item": { "object": "phone2", "conflicted": true, "conflictObject": "server2" },
            "bindings": {
                "server": { "handle": "a.vcf", "base": { "object": "server2", "revision": "e2" }, "sharedObject": "b1" },
                "phone": { "handle": "1", "base": { "object": "phone2", "revision": "v2" }, "sharedObject": "phone2" },
            },
            "overruling": true,
            "placement": {
                "collection": "acct/book",
                "handle": "1",
                "linkId": "uid-a",
                "object": "phone2",
                "level": "full",
                "status": "dirty",
                "base": { "object": "phone2", "revision": "v2" },
            },
        }));
        assert!(!merged.conflicted, "{merged:?}");
        assert_eq!(merged.conflict_object, None);
        assert_eq!(merged.shared_object.as_deref(), Some("phone2"));
    }

    /// The item conflict two sources left: the phone's edit kept, the
    /// server's recorded, the server agreeing still on the ancestor.
    fn item_conflict(source: &str, handle: &str, object: &str, base: &str) -> serde_json::Value {
        json!({
            "source": source,
            "item": { "object": "phone2", "conflicted": true, "conflictObject": "server2" },
            "bindings": {
                "server": { "handle": "a.vcf", "base": { "object": "server2", "revision": "e2" }, "sharedObject": "b1" },
                "phone": { "handle": "1", "base": { "object": "phone2", "revision": "v2" }, "sharedObject": "phone2" },
            },
            "settling": true,
            "placement": {
                "collection": "acct/book",
                "handle": handle,
                "linkId": "uid-a",
                "object": object,
                "level": "full",
                "status": "dirty",
                "base": { "object": base, "revision": "x" },
            },
        })
    }

    /// A person's edit of a conflicted item settles it whichever body it
    /// keeps and whichever source it is made through (SYNC §7): the item
    /// takes the body, its diverging one released, and the source agrees on
    /// it, the hub reading the `Settle` handed before the upsert. Without
    /// it, the three facts leave the item conflicted for the phone's own
    /// body restated through the phone, as a sync's upsert must.
    #[test]
    fn a_persons_edit_settles_the_item_conflict_whatever_it_keeps() {
        for (source, handle, base) in [("server", "a.vcf", "server2"), ("phone", "1", "phone2")] {
            for pick in ["phone2", "server2", "merged"] {
                let merged = run(item_conflict(source, handle, pick, base));
                assert_eq!(
                    merged,
                    MergedJson {
                        object: Some(pick.into()),
                        shared_object: Some(pick.into()),
                        ..MergedJson::default()
                    },
                    "{pick} through the {source}"
                );
            }
        }

        let mut restated = item_conflict("phone", "1", "phone2", "phone2");
        restated.as_object_mut().unwrap().remove("settling");
        let merged = run(restated);
        assert!(merged.conflicted, "{merged:?}");
        assert_eq!(merged.conflict_object.as_deref(), Some("server2"));
    }

    /// A person's edit of an item in no conflict is the hub's to fold: one
    /// settling a source's own conflict while the item moved through
    /// another diverges (vector 28).
    #[test]
    fn a_persons_edit_of_an_item_in_no_conflict_is_the_hubs() {
        let merged = run(json!({
            "source": "server",
            "item": { "object": "phone2" },
            "bindings": {
                "server": { "handle": "a.vcf", "base": { "object": "b1", "revision": "e1" }, "conflicted": true, "conflictRevision": "e2", "conflictObject": "server2", "sharedObject": "b1" },
                "phone": { "handle": "1", "base": { "object": "phone2", "revision": "v2" }, "sharedObject": "phone2" },
            },
            "settling": true,
            "placement": placement("server2", json!({ "object": "server2", "revision": "e2" })),
        }));
        assert!(merged.conflicted && merged.diverged, "{merged:?}");
        assert_eq!(merged.object.as_deref(), Some("phone2"));
        assert_eq!(merged.conflict_object.as_deref(), Some("server2"));
    }
}
