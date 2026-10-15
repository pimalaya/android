//! # Offline engine
//!
//! The io-pimdir bridge: runs the engine's coroutines (sync, upgrade,
//! mutate) to completion, upcalling the Java `OfflineDriver` with one JSON
//! envelope per yield ([`wire`]).
//!
//! Storage yields are served by the Java pimdir store, remote ones by the
//! domain exports. Yields are batched, so JNI chatter scales with sync
//! phases, not items. Java computes content hashes, the engine only
//! compares them; summaries are typed rows (STORAGE Annex A, [`summary`]).

pub mod summary;
pub mod wire;

use core::fmt::Display;

use std::collections::BTreeMap;

use io_pimdir::{
    change::PimdirWriteOp,
    collection::{PimdirCheckpoint, PimdirCoverage, PimdirCursor, PimdirRound, PimdirScope},
    coroutine::*,
    load::{PimdirLoadScope, PimdirLoaded},
    mutate::{PimdirMutate, PimdirMutation},
    object::{PimdirHash, PimdirObject},
    placement::{PimdirHandle, PimdirLinkId, PimdirPlacement},
    remote::{
        PimdirEnumerate, PimdirFetchedBody, PimdirFetchedItem, PimdirListing, PimdirPushOutcome,
        PimdirPushResult, PimdirTier,
    },
    summary::{PimdirSummary, mail::PimdirMailSummary},
    sync::{PimdirPushRights, PimdirSync, PimdirSyncOptions},
    upgrade::PimdirUpgrade,
};
use serde::Deserialize;
use serde_json::{from_str, to_value};

use crate::{
    client::Host,
    error::BridgeError,
    mail::unbase64,
    offline::wire::{
        ChangeJson, ErrorJson, FetchedJson, ListingJson, LoadScopeJson, LoadedJson, LookupJson,
        MutationJson, PushedJson, ScopeJson, SnapshotJson, SyncReportJson, TierJson,
        UpgradeReportJson, WriteOpJson, YieldJson, checkpoint_str, cursor_str,
    },
};

/// pimdir's role (STORAGE §14) for the collection written to when none is
/// named.
pub const DEFAULT_ROLE: &str = "default";

/// The role of a collection the source does or does not call its default.
pub fn default_role(default: bool) -> String {
    if default { DEFAULT_ROLE } else { "" }.into()
}

/// What one sync pass reconciles, and how.
pub struct OfflineSync<'a> {
    /// The collection reconciled with its remote.
    pub collection: &'a str,
    /// Whether the checkpoint is ignored and the whole remote enumerated.
    pub full: bool,
    /// Whether bodies are pushed.
    ///
    /// Never for an immutable kind: a body stored by a read reads as a local
    /// edit and would withhold the flags beside it in the pushed update.
    pub content: bool,
    /// The mail scope's floor on the `Date` header (SYNC §5), empty for none.
    pub since: &'a str,
    /// Whether the checkpoint is bound to the scope it was made under.
    ///
    /// A Graph delta link under a `$filter` is; IMAP `CHANGEDSINCE`, Gmail
    /// history and JMAP state are not, so a widening lists only its band.
    pub scope_bound: bool,
}

/// The engine's driver: the host whose `OfflineDriver` services its yields.
pub struct Driver<H> {
    host: H,
    dated: Dated,
}

/// The engine's coroutines, run to completion through the Java driver.
impl<H: Host> Driver<H> {
    /// Reconciles a collection with its remote, returning the sync report.
    pub fn sync(&mut self, sync: &OfflineSync) -> Result<SyncReportJson, BridgeError> {
        let scope = match sync.since.is_empty() {
            true => PimdirScope::unbounded(),
            false => PimdirScope::since(sync.since),
        };
        let opts = PimdirSyncOptions {
            push: true,
            rights: PimdirPushRights {
                content: sync.content,
                ..PimdirPushRights::all()
            },
            full: sync.full,
            scope,
            ..Default::default()
        };
        let coroutine = PimdirSync::new(sync.collection, opts).scope_bound(sync.scope_bound);
        let report = self.run(coroutine)?;

        Ok(SyncReportJson::from(&report))
    }

    /// Raises `collection`'s `handles` to the full tier, returning the
    /// upgrade report.
    ///
    /// Bodies are deduped by link id against the object store. The meta tier
    /// is never asked: a mail listing names its members, other domains name
    /// theirs from full bodies.
    pub fn upgrade(
        &mut self,
        collection: &str,
        handles: Vec<String>,
    ) -> Result<UpgradeReportJson, BridgeError> {
        let handles = handles.into_iter().map(PimdirHandle::from).collect();
        let coroutine = PimdirUpgrade::new(collection, handles, PimdirTier::Full);
        let report = self.run(coroutine)?;

        Ok(UpgradeReportJson::from(&report))
    }

    /// Applies a local mutation to `collection`, never touching the remote.
    pub fn mutate(&mut self, collection: &str, mutation: &str) -> Result<(), BridgeError> {
        let mutation: MutationJson =
            from_str(mutation).map_err(|err| format!("Invalid mutation: {err}"))?;
        let mutation = PimdirMutation::try_from(mutation)?;
        self.run(PimdirMutate::new(collection, mutation))
    }
}

/// Date-only summaries a load made up, by collection and handle or link id.
///
/// A load carries a mail placement's `Date` alone (all the engine reads,
/// for a round's scope, SYNC §5): a write handing it back unchanged, under
/// its handle, link id or origin, goes out with none to keep the stored row.
#[derive(Default)]
struct Dated(BTreeMap<(String, String), PimdirSummary>);

impl Dated {
    /// Records the date-only summaries a load reply carried.
    fn record(&mut self, arg: &PimdirArg) {
        let PimdirArg::Load(loaded) = arg else {
            return;
        };
        for placement in &loaded.placements {
            if let Some(summary @ PimdirSummary::Mail(mail)) = &placement.summary
                && *mail
                    == (PimdirMailSummary {
                        date: mail.date.clone(),
                        ..Default::default()
                    })
            {
                let collection = placement.collection.as_str().to_string();
                if let Some(link) = &placement.link_id {
                    let key = (collection.clone(), link_key(link.as_str()));
                    self.0.insert(key, summary.clone());
                }
                let key = (collection, placement.handle.as_str().to_string());
                self.0.insert(key, summary.clone());
            }
        }
    }

    /// The yield with every date-only summary it would write back taken out.
    fn strip(&self, yielded: PimdirYield) -> PimdirYield {
        let PimdirYield::WantsWrite(ops) = yielded else {
            return yielded;
        };
        if self.0.is_empty() {
            return PimdirYield::WantsWrite(ops);
        }
        let ops = ops
            .into_iter()
            .map(|op| match op {
                PimdirWriteOp::UpsertPlacement(mut placement) => {
                    let collection = placement.collection.as_str().to_string();
                    let mut keys =
                        vec![(collection.clone(), placement.handle.as_str().to_string())];
                    if let Some(link) = &placement.link_id {
                        keys.push((collection, link_key(link.as_str())));
                    }
                    if let Some(origin) = &placement.origin {
                        keys.push((
                            origin.collection.as_str().to_string(),
                            origin.handle.as_str().to_string(),
                        ));
                    }
                    let dated = keys
                        .iter()
                        .any(|key| self.0.get(key) == placement.summary.as_ref());
                    if placement.summary.is_some() && dated {
                        placement.summary = None;
                    }
                    PimdirWriteOp::UpsertPlacement(placement)
                }
                other => other,
            })
            .collect();
        PimdirYield::WantsWrite(ops)
    }
}

/// A link id as a [`Dated`] key, apart from every handle: no handle is
/// spelled with a leading `U+0002`.
fn link_key(link: &str) -> String {
    format!("\u{2}{link}")
}

impl<H: Host> Driver<H> {
    /// The driver of one engine run over `host`.
    pub fn new(host: H) -> Self {
        Self {
            host,
            dated: Dated::default(),
        }
    }

    /// Runs an offline coroutine to completion through the Java driver.
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
                    let yielded = self.dated.strip(yielded);
                    let reply = self.host.serve(&yield_json(&yielded))?;
                    let parsed = parse_arg(&yielded, &reply)?;
                    self.dated.record(&parsed);
                    arg = Some(parsed);
                }
            }
        }
    }
}

/// Serializes one engine yield to its JSON envelope.
fn yield_json(yielded: &PimdirYield) -> String {
    let envelope = match yielded {
        PimdirYield::WantsLoad { collection, scope } => YieldJson::Load {
            collection: collection.as_str(),
            scope: scope_json(scope),
        },
        PimdirYield::WantsLookupObject(links) => YieldJson::Lookup {
            links: links.iter().map(PimdirLinkId::as_str).collect(),
        },
        PimdirYield::WantsWrite(ops) => YieldJson::Write {
            writes: ops.iter().map(WriteOpJson::from).collect(),
        },
        PimdirYield::WantsEnumerate {
            collection,
            request,
        } => enumerate_json(collection.as_str(), request),
        PimdirYield::WantsFetch {
            collection,
            handles,
            tier,
        } => YieldJson::Fetch {
            collection: collection.as_str(),
            handles: handles.iter().map(PimdirHandle::as_str).collect(),
            tier: match tier {
                PimdirTier::Meta => TierJson::Meta,
                PimdirTier::Full => TierJson::Full,
            },
        },
        PimdirYield::WantsPush {
            collection,
            changes,
        } => YieldJson::Push {
            collection: collection.as_str(),
            changes: changes.iter().map(ChangeJson::from).collect(),
        },
    };

    // NOTE: through a `Value`, keys come out alphabetical, as Java expects;
    // a typed tag would lead, and nested mirrors keep declaration order.
    to_value(&envelope).unwrap_or_default().to_string()
}

/// Parses the Java reply to the pending yield into the next resume's arg.
///
/// An `error` reply aborts the run keeping the driver's HTTP status: a 401
/// here is what triggers the token refresh upstairs.
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
                coverage: loaded.coverage.map(|coverage| PimdirCoverage {
                    scope: coverage.scope.into(),
                    at: coverage.at,
                }),
                round: loaded.round.map(|round| PimdirRound {
                    scope: round.scope.into(),
                    cursor: round
                        .cursor
                        .filter(|cursor| !cursor.is_empty())
                        .map(|cursor| PimdirCursor(cursor.into_bytes())),
                    checkpoint: round
                        .checkpoint
                        .filter(|token| !token.is_empty())
                        .map(|token| PimdirCheckpoint(token.into_bytes())),
                    started_at: round.started_at,
                    band: round.band,
                }),
                unstamped: loaded.unstamped.into_iter().map(PimdirHandle).collect(),
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
            PimdirArg::Enumerate(snapshot.into())
        }
        PimdirYield::WantsFetch { .. } => {
            let fetched: FetchedJson = parse(reply)?;
            let mut items = Vec::with_capacity(fetched.items.len());
            for item in fetched.items {
                // NOTE: a message is bytes and a Java string UTF-8, so a
                // body that is not text crosses in base64.
                let bytes = match (item.body, item.body_base64) {
                    (Some(body), _) => Some(body.into_bytes()),
                    (None, Some(encoded)) => Some(unbase64(&encoded)?),
                    (None, None) => None,
                };
                items.push(PimdirFetchedItem {
                    handle: PimdirHandle(item.handle),
                    link_id: PimdirLinkId(item.link_id),
                    summary: item.summary.map(Into::into),
                    sort_key: item.sort_key.into(),
                    body: match (item.hash, bytes, item.persisted) {
                        (Some(hash), Some(bytes), _) => Some(PimdirFetchedBody::Inline {
                            hash: PimdirHash(hash),
                            bytes,
                        }),
                        (Some(hash), None, Some(size)) => Some(PimdirFetchedBody::Persisted {
                            hash: PimdirHash(hash),
                            size,
                        }),
                        _ => None,
                    },
                    revision: item.revision,
                });
            }
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
/// A floor, not a ceiling: answering more stays correct, which lets Java
/// widen a list too long for one SQLite statement, while a flag change
/// still reads one row instead of the whole mailbox.
fn scope_json(scope: &PimdirLoadScope) -> LoadScopeJson<'_> {
    match scope {
        PimdirLoadScope::All => LoadScopeJson::All,
        PimdirLoadScope::Handles(handles) => LoadScopeJson::Handles {
            handles: handles.iter().map(PimdirHandle::as_str).collect(),
        },
        PimdirLoadScope::Links(links) => LoadScopeJson::Links {
            links: links.iter().map(PimdirLinkId::as_str).collect(),
        },
    }
}

/// The `enumerate` yield on the JSON wire.
///
/// `listing` is a `delta` or a `round` (resumed from `cursor`, `band` for a
/// coverage's gap); `scope` bounds mail on `Date`. The top `cursor` repeats
/// a delta's checkpoint for single-page connectors, absent listing all.
fn enumerate_json<'a>(collection: &'a str, request: &PimdirEnumerate) -> YieldJson<'a> {
    let listing = match &request.listing {
        PimdirListing::Delta(checkpoint) => ListingJson::Delta {
            checkpoint: checkpoint_str(checkpoint),
        },
        PimdirListing::Round { cursor, band } => ListingJson::Round {
            cursor: cursor.as_ref().map(cursor_str),
            band: *band,
        },
    };

    YieldJson::Enumerate {
        collection,
        cursor: request
            .checkpoint()
            .map(checkpoint_str)
            .filter(|c| !c.is_empty()),
        listing,
        scope: ScopeJson::from(&request.scope),
    }
}

#[cfg(test)]
mod tests {
    use io_pimdir::{
        change::PimdirWriteOp,
        collection::{PimdirCheckpoint, PimdirCollectionId, PimdirCursor, PimdirScope},
        coroutine::{PimdirArg, PimdirYield},
        object::{PimdirHash, PimdirObject},
        placement::PimdirHandle,
        remote::{PimdirEnumerate, PimdirFetchedBody, PimdirListing, PimdirTier},
    };
    use serde_json::{Value, to_value};

    use crate::offline::{enumerate_json, parse_arg, wire::WriteOpJson};

    #[test]
    fn the_yield_carries_the_listing_and_the_scope() {
        let collection = PimdirCollectionId::from("acct/INBOX");
        let round = to_value(enumerate_json(
            collection.as_str(),
            &PimdirEnumerate {
                listing: PimdirListing::Round {
                    cursor: Some(PimdirCursor(b"7:9".to_vec())),
                    band: false,
                },
                scope: PimdirScope::since("2026-04-01T00:00:00Z"),
            },
        ))
        .unwrap();
        assert_eq!(round["listing"]["kind"], "round");
        assert_eq!(round["listing"]["cursor"], "7:9");
        assert_eq!(round["scope"]["since"], "2026-04-01T00:00:00Z");
        assert_eq!(round["cursor"], Value::Null, "no checkpoint on a round");

        let delta = to_value(enumerate_json(
            collection.as_str(),
            &PimdirEnumerate {
                listing: PimdirListing::Delta(PimdirCheckpoint(b"7:42".to_vec())),
                scope: PimdirScope::unbounded(),
            },
        ))
        .unwrap();
        assert_eq!(delta["listing"]["kind"], "delta");
        assert_eq!(
            delta["cursor"], "7:42",
            "the checkpoint for a one-page connector"
        );
    }

    #[test]
    fn a_body_that_is_not_text_crosses_in_base64() {
        let yielded = PimdirYield::WantsFetch {
            collection: PimdirCollectionId::from("acct/INBOX"),
            handles: vec![PimdirHandle("7".into())],
            tier: PimdirTier::Full,
        };
        let reply =
            r#"{"items": [{"handle": "7", "linkId": "7", "hash": "h", "bodyBase64": "/wBh"}]}"#;
        let PimdirArg::Fetch(items) = parse_arg(&yielded, reply).unwrap() else {
            panic!("a fetch");
        };
        let Some(PimdirFetchedBody::Inline { bytes, .. }) = &items[0].body else {
            panic!("an inline body");
        };
        assert_eq!(bytes, &[0xff, 0x00, b'a']);

        let stored = serde_json::to_value(WriteOpJson::from(&PimdirWriteOp::StoreObject {
            object: PimdirObject {
                hash: PimdirHash("h".into()),
                size: 3,
            },
            body: Some(vec![0xff, 0x00, b'a']),
        }))
        .unwrap();
        assert_eq!(stored["bodyBase64"], "/wBh");
        assert_eq!(stored.get("body"), None, "never a lossy copy beside it");
    }
}
