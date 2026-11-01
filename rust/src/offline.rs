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

pub mod blobs;
pub mod merge;
pub mod shape;
pub mod store;
pub mod summary;
pub mod wire;

use core::fmt::Display;

use std::collections::BTreeMap;

use io_pimdir::{
    change::PimdirWriteOp,
    collection::{PimdirCheckpoint, PimdirCoverage, PimdirCursor, PimdirRound, PimdirScope},
    coroutine::*,
    load::{PimdirLoadSelection, PimdirLoaded},
    mutate::{PimdirMutate, PimdirMutation},
    object::{PimdirHash, PimdirObject},
    placement::{PimdirHandle, PimdirLinkId, PimdirPlacement},
    remote::{
        PimdirEnumerate, PimdirFetchedBody, PimdirFetchedItem, PimdirListing, PimdirPushOutcome,
        PimdirPushResult, PimdirTier,
    },
    summary::{PimdirSummary, mail::PimdirMailSummary},
    sync::{PimdirPushRights, PimdirSync, PimdirSyncOptions},
    upgrade::{PimdirUpgrade, PimdirUpgradeOptions},
};
use serde::Deserialize;
use serde_json::{from_str, to_value};

use crate::{
    client::Host,
    error::BridgeError,
    mail::unbase64,
    offline::wire::{
        ChangeJson, ErrorJson, FetchedJson, ListingJson, LoadSelectionJson, LoadedJson, LookupJson,
        MutateReportJson, MutationJson, PushedJson, ScopeJson, SnapshotJson, SyncReportJson,
        TierJson, UpgradeReportJson, VerifiedJson, WriteOpJson, WrittenJson, YieldJson,
        checkpoint_str, cursor_str,
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
    /// Whether the caller confirmed the absences a round finds, so one
    /// finding most of its scope absent, or a listing naming no member,
    /// closes rather than parks (SYNC §5).
    pub accept_absences: bool,
}

/// The engine's driver: the host whose `OfflineDriver` services its yields.
pub struct Driver<H> {
    host: H,
    dated: Dated,
    /// The handle a person's edit under way settles, whose upsert the write
    /// yield marks so (SYNC §7, [`settles`]).
    settle: Option<PimdirHandle>,
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
            full: sync.full,
            scope,
            accept_absences: sync.accept_absences,
            ..sync_options(sync.content)
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
    /// theirs from full bodies. A divergence whose body it fetches is settled
    /// by the options the collection's sync runs under (SYNC §6), `content`
    /// being whether that sync pushes bodies.
    pub fn upgrade(
        &mut self,
        collection: &str,
        handles: Vec<String>,
        content: bool,
    ) -> Result<UpgradeReportJson, BridgeError> {
        let handles = handles.into_iter().map(PimdirHandle::from).collect();
        let options = PimdirUpgradeOptions::from(&sync_options(content));
        let coroutine = PimdirUpgrade::new(collection, handles, PimdirTier::Full).options(options);
        let report = self.run(coroutine)?;

        Ok(UpgradeReportJson::from(&report))
    }

    /// Applies a local mutation to `collection`, never touching the remote.
    pub fn mutate(
        &mut self,
        collection: &str,
        mutation: &str,
    ) -> Result<MutateReportJson, BridgeError> {
        let mutation: MutationJson =
            from_str(mutation).map_err(|err| format!("Invalid mutation: {err}"))?;
        let mutation = PimdirMutation::try_from(mutation)?;
        self.settle = settles(&mutation);
        let report = self.run(PimdirMutate::new(collection, mutation));
        self.settle = None;
        let report = report?;

        Ok(MutateReportJson::from(&report))
    }
}

/// The handle whose item conflict `mutation` settles as a person's choice
/// (SYNC §7): an `Edit` stating no `overrule`, which resolves a conflicted
/// item whichever body it keeps, and keeps nothing it discards.
///
/// io-pimdir bff5737 says so of the binding alone: its hub clears the item's
/// conflict only when the edit is a change of a body one source alone made,
/// so an edit keeping the item's own body, or made through the source the
/// item moved away from, left it conflicted.
fn settles(mutation: &PimdirMutation) -> Option<PimdirHandle> {
    match mutation {
        PimdirMutation::Edit {
            handle,
            overrule: false,
            ..
        } => Some(handle.clone()),
        _ => None,
    }
}

/// The options every sync of the app runs under, `content` whether it
/// pushes bodies; an upgrade settles a divergence by the same (SYNC §6).
fn sync_options(content: bool) -> PimdirSyncOptions {
    PimdirSyncOptions {
        push: true,
        rights: PimdirPushRights {
            content,
            ..PimdirPushRights::all()
        },
        ..Default::default()
    }
}

/// Partial summaries a load made up, by collection and handle or link id.
///
/// A load carries a mail placement's `Date`, `Subject` and sender alone,
/// all the engine reads: the date for a round's scope (SYNC §5), the three
/// to tell a listed member reusing a `Message-ID` from the pending create
/// it would land (SYNC §6). A write handing one back unchanged, under its
/// handle, link id or origin, goes out with none to keep the stored row.
#[derive(Default)]
struct Dated(BTreeMap<(String, String), PimdirSummary>);

impl Dated {
    /// Records the partial summaries a load reply carried.
    fn record(&mut self, arg: &PimdirArg) {
        let PimdirArg::Load(loaded) = arg else {
            return;
        };
        for placement in &loaded.placements {
            if let Some(summary @ PimdirSummary::Mail(mail)) = &placement.summary
                && *mail
                    == (PimdirMailSummary {
                        date: mail.date.clone(),
                        subject: mail.subject.clone(),
                        sender: mail.sender.clone(),
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

    /// The yield with every partial summary it would write back taken out.
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
            settle: None,
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
                    let reply = self
                        .host
                        .serve(&yield_json(&yielded, self.settle.as_ref()))?;
                    let parsed = parse_arg(&yielded, &reply)?;
                    self.dated.record(&parsed);
                    arg = Some(parsed);
                }
            }
        }
    }
}

/// Serializes one engine yield to its JSON envelope, marking the upsert of
/// `settle` as a person's settle (SYNC §7) when a write carries it.
fn yield_json(yielded: &PimdirYield, settle: Option<&PimdirHandle>) -> String {
    let envelope = match yielded {
        PimdirYield::WantsLoad {
            collection,
            selection,
        } => YieldJson::Load {
            collection: collection.as_str(),
            selection: selection_json(selection),
        },
        PimdirYield::WantsVerify(hashes) => YieldJson::Verify {
            hashes: hashes.iter().map(PimdirHash::as_str).collect(),
        },
        PimdirYield::WantsLookupObject(links) => YieldJson::Lookup {
            links: links.iter().map(PimdirLinkId::as_str).collect(),
        },
        PimdirYield::WantsWrite(ops) => YieldJson::Write {
            writes: write_ops(ops, settle),
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

/// A write batch's ops, a `settle` op before the upsert of `settle`.
fn write_ops(ops: &[PimdirWriteOp], settle: Option<&PimdirHandle>) -> Vec<WriteOpJson> {
    let mut writes = Vec::with_capacity(ops.len() + 1);
    for op in ops {
        if let PimdirWriteOp::UpsertPlacement(placement) = op
            && Some(&placement.handle) == settle
        {
            writes.push(WriteOpJson::Settle {
                collection: placement.collection.as_str().into(),
                handle: placement.handle.as_str().into(),
            });
        }
        writes.push(WriteOpJson::from(op));
    }
    writes
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
                held: loaded.held.into_iter().map(PimdirHandle).collect(),
                superseded: loaded
                    .superseded
                    .into_iter()
                    .map(|create| (PimdirHandle(create.handle), create.supersedes.into()))
                    .collect(),
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
        PimdirYield::WantsWrite(_) => {
            let written: WrittenJson = parse(reply)?;
            PimdirArg::Written(PimdirWritten {
                conflicted: written.conflicted.into_iter().map(PimdirHandle).collect(),
                overruled: written
                    .overruled
                    .into_iter()
                    .map(|kept| (PimdirHandle(kept.handle), PimdirLinkId(kept.link_id)))
                    .collect(),
            })
        }
        PimdirYield::WantsVerify(_) => {
            let verified: VerifiedJson = parse(reply)?;
            PimdirArg::Verified(verified.verified.into_iter().map(PimdirHash).collect())
        }
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
/// A floor, not a ceiling: answering more stays correct, while a flag
/// change still reads one row instead of the whole mailbox.
fn selection_json(selection: &PimdirLoadSelection) -> LoadSelectionJson<'_> {
    match selection {
        PimdirLoadSelection::All => LoadSelectionJson::All,
        PimdirLoadSelection::LastPage {
            narrowed,
            enumerated,
        } => LoadSelectionJson::LastPage {
            narrowed: *narrowed,
            enumerated: enumerated
                .as_ref()
                .map(|handles| handles.iter().map(PimdirHandle::as_str).collect()),
        },
        PimdirLoadSelection::Handles(handles) => LoadSelectionJson::Handles {
            handles: handles.iter().map(PimdirHandle::as_str).collect(),
        },
        PimdirLoadSelection::Links(links) => LoadSelectionJson::Links {
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
        PimdirListing::Head(_) => ListingJson::Head,
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

    use io_pimdir::{
        coroutine::{PimdirCoroutine, PimdirCoroutineState},
        sync::{PimdirSync, PimdirSyncOptions},
    };

    use crate::offline::{enumerate_json, parse_arg, wire::WriteOpJson, yield_json};

    /// A yield as the driver sends it outside a person's edit.
    fn yield_json_unsettled(yielded: &PimdirYield) -> String {
        yield_json(yielded, None)
    }

    /// A file of the pimdir spec io-pimdir vendors (spec/), from the git
    /// checkout cargo made of the pinned revision (Cargo.toml's
    /// `[patch.crates-io]`), so its vectors run against the bridge.
    pub fn io_pimdir_spec(file: &str) -> std::path::PathBuf {
        let manifest = include_str!("../Cargo.toml");
        let rev = manifest
            .lines()
            .find(|line| line.starts_with("io-pimdir = { git"))
            .and_then(|line| line.split("rev = \"").nth(1))
            .map(|rev| &rev[..7])
            .expect("io-pimdir pinned by git rev");
        let home = std::env::var("CARGO_HOME")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|_| {
                std::path::PathBuf::from(std::env::var("HOME").unwrap()).join(".cargo")
            });
        let checkouts = home.join("git").join("checkouts");
        std::fs::read_dir(&checkouts)
            .expect("cargo's git checkouts")
            .filter_map(Result::ok)
            .filter(|entry| {
                entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with("io-pimdir-")
            })
            .map(|entry| entry.path().join(rev).join("spec").join(file))
            .find(|path| path.is_file())
            .unwrap_or_else(|| panic!("io-pimdir {rev} spec/{file} under {}", checkouts.display()))
    }

    /// A round's last page asks its load with what the page states of its
    /// listing, for `list_unstamped_bindings`' `:narrowed` and
    /// `:enumerated` (pimdir SYNC §4, §5).
    #[test]
    fn a_last_page_load_carries_the_enumeration() {
        use io_pimdir::load::PimdirLoadSelection;

        let load = |selection| PimdirYield::WantsLoad {
            collection: PimdirCollectionId::from("acct/INBOX"),
            selection,
        };
        let narrowed: Value = serde_json::from_str(&yield_json_unsettled(&load(
            PimdirLoadSelection::LastPage {
                narrowed: true,
                enumerated: Some(vec![PimdirHandle("7".into())]),
            },
        )))
        .unwrap();
        assert_eq!(
            narrowed["selection"],
            serde_json::json!({"kind": "lastPage", "narrowed": true, "enumerated": ["7"]})
        );

        let whole: Value = serde_json::from_str(&yield_json_unsettled(&load(
            PimdirLoadSelection::LastPage {
                narrowed: false,
                enumerated: None,
            },
        )))
        .unwrap();
        assert_eq!(
            whole["selection"],
            serde_json::json!({"kind": "lastPage", "narrowed": false}),
            "no enumeration key, never a null"
        );
    }

    /// The store answers a verification with the bodies it read whole under
    /// their name, and a write with what its merge recorded (STORAGE §7,
    /// SYNC §9).
    #[test]
    fn verify_and_write_replies_reach_the_engine() {
        let verify = PimdirYield::WantsVerify(vec![PimdirHash("a".into()), PimdirHash("b".into())]);
        let envelope: Value = serde_json::from_str(&yield_json_unsettled(&verify)).unwrap();
        assert_eq!(envelope["op"], "verify");
        assert_eq!(envelope["hashes"], serde_json::json!(["a", "b"]));
        let PimdirArg::Verified(verified) = parse_arg(&verify, r#"{"verified": ["b"]}"#).unwrap()
        else {
            panic!("a verification");
        };
        assert_eq!(
            verified.into_iter().collect::<Vec<_>>(),
            [PimdirHash("b".into())]
        );

        let write = PimdirYield::WantsWrite(Vec::new());
        let PimdirArg::Written(written) = parse_arg(
            &write,
            r#"{"conflicted": ["7"], "overruled": [{"handle": "7", "linkId": "dup:l#7"}]}"#,
        )
        .unwrap() else {
            panic!("a write");
        };
        assert_eq!(written.conflicted, [PimdirHandle("7".into())]);
        assert_eq!(written.overruled[0].1.as_str(), "dup:l#7");
        let PimdirArg::Written(nothing) = parse_arg(&write, "{}").unwrap() else {
            panic!("a write");
        };
        assert_eq!(nothing, Default::default());
    }

    /// A flag push crosses as two deltas, never a whole set (SYNC §4).
    #[test]
    fn a_flag_push_carries_deltas() {
        use std::collections::BTreeSet;

        use io_pimdir::change::{PimdirChange, PimdirChangeKind};

        use crate::offline::wire::ChangeJson;

        let change = PimdirChange::new(
            &PimdirCollectionId::from("acct/INBOX"),
            PimdirChangeKind::SetFlags {
                handle: PimdirHandle("7".into()),
                add: BTreeSet::from(["\\Seen".to_string()]),
                remove: BTreeSet::from(["\\Flagged".to_string()]),
            },
        );
        let json = to_value(ChangeJson::from(&change)).unwrap();
        assert_eq!(json["op"], "setFlags");
        assert_eq!(json["add"], serde_json::json!(["\\Seen"]));
        assert_eq!(json["remove"], serde_json::json!(["\\Flagged"]));
        assert_eq!(json.get("flags"), None);
    }

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

    /// A pending mail create, as the Java store loads it, lands on its
    /// arrival listed with the same subject, date and sender rather than
    /// being pushed again: the engine tells a member reusing a `Message-ID`
    /// by those three (pimdir SYNC §6), so the load carries all of them.
    #[test]
    fn a_pending_create_lands_on_its_arrival_rather_than_being_pushed() {
        let opts = PimdirSyncOptions {
            push: true,
            rights: io_pimdir::sync::PimdirPushRights {
                content: false,
                ..io_pimdir::sync::PimdirPushRights::all()
            },
            ..Default::default()
        };
        let mut sync = PimdirSync::new("acct/Sent", opts);
        let loaded = r#"{"placements": [{"collection": "acct/Sent",
            "handle": "\u0001m1@example.com", "linkId": "m1@example.com", "object": "h1",
            "level": "full", "status": "created", "flags": ["\\Seen"],
            "sortKey": "2026-10-08T09:00:00Z", "date": "2026-10-08T09:00:00Z",
            "subject": "Hi", "sender": "jane@example.com"}]}"#;
        let listed = r#"{"items": [{"handle": "900", "linkId": "m1@example.com",
            "flags": ["\\Seen"], "sortKey": "2026-10-08T09:00:00Z",
            "summary": {"mail": {"message_id": "m1@example.com", "subject": "Hi",
            "sender": "jane@example.com", "date": "2026-10-08T09:00:00Z",
            "in_reply_to": [], "addresses": []}}}], "complete": false}"#;
        let mut pushed = Vec::new();
        let mut written = Vec::new();
        let mut arg = None;

        loop {
            let yielded = match sync.resume(arg.take()) {
                PimdirCoroutineState::Yielded(yielded) => yielded,
                PimdirCoroutineState::Complete(done) => {
                    done.unwrap();
                    break;
                }
            };
            let envelope: Value = serde_json::from_str(&yield_json_unsettled(&yielded)).unwrap();
            let reply = match envelope["op"].as_str().unwrap() {
                "load" => loaded.to_string(),
                "enumerate" => listed.to_string(),
                "verify" => serde_json::json!({"verified": envelope["hashes"]}).to_string(),
                "push" => {
                    pushed.extend(envelope["changes"].as_array().unwrap().clone());
                    r#"{"results": []}"#.to_string()
                }
                "write" => {
                    written.extend(envelope["writes"].as_array().unwrap().clone());
                    "{}".to_string()
                }
                _ => "{}".to_string(),
            };
            arg = Some(parse_arg(&yielded, &reply).unwrap());
        }

        assert!(pushed.is_empty(), "landed, not pushed again: {pushed:?}");
        assert!(
            written
                .iter()
                .any(|op| op["op"] == "upsert" && op["placement"]["handle"] == "900"),
            "{written:?}"
        );
    }

    /// A pending create superseding a member of its identity, as the Java
    /// store loads it, is pushed naming that member, so the calendar driver
    /// can deliver it by a move (pimdir SYNC §3, §4).
    #[test]
    fn a_superseding_create_is_pushed_naming_the_member_it_moves() {
        let opts = PimdirSyncOptions {
            push: true,
            ..Default::default()
        };
        let mut sync = PimdirSync::new("acct/home", opts);
        let loaded = r#"{"placements": [{"collection": "acct/home",
            "handle": "\u0001gate.ics", "linkId": "gate.ics", "object": "h1",
            "level": "full", "status": "created"}],
            "superseded": [{"handle": "\u0001gate.ics", "supersedes":
            {"collection": "acct/work", "handle": "gate.ics", "ifMatch": "e0"}}]}"#;
        let mut pushed = Vec::new();
        let mut arg = None;

        loop {
            let yielded = match sync.resume(arg.take()) {
                PimdirCoroutineState::Yielded(yielded) => yielded,
                PimdirCoroutineState::Complete(done) => {
                    done.unwrap();
                    break;
                }
            };
            let envelope: Value = serde_json::from_str(&yield_json_unsettled(&yielded)).unwrap();
            let reply = match envelope["op"].as_str().unwrap() {
                "load" => loaded.to_string(),
                "enumerate" => r#"{"items": [], "complete": true}"#.to_string(),
                "verify" => serde_json::json!({"verified": envelope["hashes"]}).to_string(),
                "push" => {
                    pushed.extend(envelope["changes"].as_array().unwrap().clone());
                    r#"{"results": [{"handle": "\u0001gate.ics", "accepted": true,
                        "assigned": "gate.ics", "revision": "e1"}]}"#
                        .to_string()
                }
                _ => "{}".to_string(),
            };
            arg = Some(parse_arg(&yielded, &reply).unwrap());
        }

        assert_eq!(pushed.len(), 1, "{pushed:?}");
        assert_eq!(pushed[0]["op"], "add");
        assert_eq!(
            pushed[0]["supersedes"],
            serde_json::json!({"collection": "acct/work", "handle": "gate.ics", "ifMatch": "e0"}),
        );
    }

    /// A person's edit crosses with a `settle` op before its upsert, which
    /// the Java owner reads to settle the item's conflict (SYNC §7); an
    /// overruling edit, a rule's, crosses without one.
    #[test]
    fn a_persons_edit_marks_its_upsert_as_a_settle() {
        use io_pimdir::{
            load::PimdirLoaded,
            mutate::{PimdirMutate, PimdirMutation},
            placement::{
                PimdirBase, PimdirFlags, PimdirLevel, PimdirLinkId, PimdirPlacement, PimdirStatus,
            },
        };

        use crate::offline::settles;

        let ops = |overrule| {
            let edit = PimdirMutation::Edit {
                handle: PimdirHandle("a.vcf".into()),
                object: PimdirObject {
                    hash: PimdirHash("b2".into()),
                    size: 2,
                },
                body: b"b2".to_vec(),
                summary: None,
                sort_key: None,
                if_match: None,
                overrule,
            };
            let settle = settles(&edit);
            let mut mutate = PimdirMutate::new("acct/book", edit);
            let _ = mutate.resume(None);
            let loaded = PimdirLoaded {
                placements: vec![PimdirPlacement {
                    collection: "acct/book".into(),
                    handle: PimdirHandle("a.vcf".into()),
                    link_id: Some(PimdirLinkId("uid-a".into())),
                    object: Some(PimdirHash("b1".into())),
                    level: PimdirLevel::Full,
                    summary: None,
                    sort_key: Default::default(),
                    flags: PimdirFlags::default(),
                    status: PimdirStatus::Conflict,
                    conflict_revision: None,
                    conflict_object: None,
                    base: Some(PimdirBase {
                        flags: PimdirFlags::default(),
                        revision: Some("e1".into()),
                        object: Some(PimdirHash("b1".into())),
                    }),
                    origin: None,
                }],
                ..Default::default()
            };
            let PimdirCoroutineState::Yielded(yielded) =
                mutate.resume(Some(PimdirArg::Load(loaded)))
            else {
                panic!("a write");
            };
            let envelope: Value =
                serde_json::from_str(&yield_json(&yielded, settle.as_ref())).unwrap();
            envelope["writes"]
                .as_array()
                .unwrap()
                .iter()
                .map(|op| op["op"].as_str().unwrap().to_string())
                .collect::<Vec<_>>()
        };

        assert_eq!(ops(false), ["storeObject", "settle", "upsert"]);
        assert_eq!(ops(true), ["storeObject", "overrule", "upsert"]);
    }
}
