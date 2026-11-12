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
        MutateFailureJson, MutateReportJson, MutationJson, PushedJson, ScopeJson, SnapshotJson,
        SyncReportJson, TierJson, UpgradeReportJson, VerifiedJson, WriteOpJson, WrittenJson,
        YieldJson, checkpoint_str, cursor_str,
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
    ///
    /// A refusal of the engine's keeps its code: an edit made over a body
    /// another write replaced since is `body-replaced` (SYNC §7).
    pub fn mutate(
        &mut self,
        collection: &str,
        mutation: &str,
    ) -> Result<MutateReportJson, MutateFailureJson> {
        let mutation: MutationJson =
            from_str(mutation).map_err(|err| format!("Invalid mutation: {err}"))?;
        let syncs_target = mutation.syncs_target();
        let mutation = PimdirMutation::try_from(mutation)?;
        let mutate = PimdirMutate::new(collection, mutation).syncs_target(syncs_target);
        let report = self
            .drive(mutate)?
            .map_err(|err| MutateFailureJson::from(&err))?;

        Ok(MutateReportJson::from(&report))
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
        }
    }

    /// Runs an offline coroutine to completion through the Java driver.
    fn run<C, T, E>(&mut self, coroutine: C) -> Result<T, BridgeError>
    where
        C: PimdirCoroutine<Yield = PimdirYield, Return = Result<T, E>>,
        E: Display,
    {
        self.drive(coroutine)?.map_err(|err| err.to_string().into())
    }

    /// Runs an offline coroutine to completion, answering what it returned,
    /// its own failure included; the driver's or the wire's fails the run.
    fn drive<C, R>(&mut self, mut coroutine: C) -> Result<R, BridgeError>
    where
        C: PimdirCoroutine<Yield = PimdirYield, Return = R>,
    {
        let mut arg: Option<PimdirArg> = None;

        loop {
            match coroutine.resume(arg.take()) {
                PimdirCoroutineState::Complete(returned) => return Ok(returned),
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
                held: loaded.held.into_iter().map(PimdirHandle).collect(),
                superseded: loaded
                    .superseded
                    .into_iter()
                    .map(|create| (PimdirHandle(create.handle), create.supersedes.into()))
                    .collect(),
                // NOTE: an item conflict is the storage's to state: a
                // placement projecting `Conflict` with no binding conflict
                // and no entry here reads as one nobody has read, which no
                // resolution names (SYNC §7).
                conflicted: loaded
                    .conflicted
                    .into_iter()
                    .map(|item| {
                        let object = item.object.filter(|hash| !hash.is_empty());
                        (PimdirHandle(item.handle), object.map(PimdirHash))
                    })
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
                    relocated: result.relocated,
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
        PimdirLoadSelection::Owing(handles) => LoadSelectionJson::Owing {
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
pub(crate) mod tests {
    use io_pimdir::{
        change::PimdirWriteOp,
        collection::{PimdirCheckpoint, PimdirCollectionId, PimdirCursor, PimdirScope},
        coroutine::{PimdirArg, PimdirYield},
        object::{PimdirHash, PimdirObject},
        placement::PimdirHandle,
        remote::{PimdirEnumerate, PimdirFetchedBody, PimdirListing, PimdirTier},
    };
    use serde_json::{Value, from_str, to_value};

    use io_pimdir::{
        coroutine::{PimdirCoroutine, PimdirCoroutineState},
        sync::{PimdirSync, PimdirSyncOptions},
    };

    use crate::offline::{
        enumerate_json, parse_arg,
        wire::{MutationJson, WriteOpJson},
        yield_json,
    };

    /// A file of the pimdir spec io-pimdir vendors ([`io_pimdir_spec_dir`]).
    pub fn io_pimdir_spec(file: &str) -> std::path::PathBuf {
        io_pimdir_spec_dir().join(file)
    }

    /// The pimdir spec io-pimdir vendors (spec/), so its vectors run against
    /// the bridge: `PIMALAYA_IO_PIMDIR_SPEC`, else the source cargo fetched
    /// of the io-pimdir rust/Cargo.lock locks, a git checkout or a registry
    /// package.
    pub fn io_pimdir_spec_dir() -> std::path::PathBuf {
        use std::{env, fs, path::PathBuf};

        if let Ok(dir) = env::var("PIMALAYA_IO_PIMDIR_SPEC") {
            return PathBuf::from(dir);
        }

        let lock = include_str!("../Cargo.lock");
        let package = lock
            .split("[[package]]")
            .find(|package| package.contains("\nname = \"io-pimdir\"\n"))
            .expect("io-pimdir in Cargo.lock");
        let field = |name: &str| {
            package
                .lines()
                .find_map(|line| line.strip_prefix(name))
                .map(|value| value.trim_matches('"'))
                .unwrap_or_default()
        };
        let version = field("version = ");
        let source = field("source = ");

        let home = env::var("CARGO_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from(env::var("HOME").unwrap()).join(".cargo"));
        let (root, name) = match source.split_once('#') {
            Some((_, commit)) => (home.join("git/checkouts"), commit[..7].to_string()),
            None => (home.join("registry/src"), format!("io-pimdir-{version}")),
        };
        let found = fs::read_dir(&root)
            .into_iter()
            .flatten()
            .filter_map(Result::ok)
            .map(|entry| entry.path().join(&name).join("spec"))
            .find(|spec| spec.join("vectors").is_dir());

        found.unwrap_or_else(|| {
            panic!(
                "no io-pimdir {version} spec with its vectors under {}: the \
                 published crate leaves them out, point \
                 PIMALAYA_IO_PIMDIR_SPEC at a checkout's spec/",
                root.display()
            )
        })
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
        let narrowed: Value =
            serde_json::from_str(&yield_json(&load(PimdirLoadSelection::LastPage {
                narrowed: true,
                enumerated: Some(vec![PimdirHandle("7".into())]),
            })))
            .unwrap();
        assert_eq!(
            narrowed["selection"],
            serde_json::json!({"kind": "lastPage", "narrowed": true, "enumerated": ["7"]})
        );

        let whole: Value =
            serde_json::from_str(&yield_json(&load(PimdirLoadSelection::LastPage {
                narrowed: false,
                enumerated: None,
            })))
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
        let envelope: Value = serde_json::from_str(&yield_json(&verify)).unwrap();
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
            let envelope: Value = serde_json::from_str(&yield_json(&yielded)).unwrap();
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
            let envelope: Value = serde_json::from_str(&yield_json(&yielded)).unwrap();
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

    /// The edit `mutation` (its wire JSON) of a placement whose load reply
    /// is `placement` and `conflicted` (Java's JSON), answering the ops it
    /// crosses with, or the refusal's text.
    fn edit_over(
        mutation: &str,
        placement: Value,
        conflicted: Value,
    ) -> Result<Vec<String>, String> {
        use io_pimdir::mutate::{PimdirMutate, PimdirMutation};

        let mutation: MutationJson = from_str(mutation).unwrap();
        let mut mutate = PimdirMutate::new("acct/book", PimdirMutation::try_from(mutation)?);
        let PimdirCoroutineState::Yielded(load) = mutate.resume(None) else {
            panic!("a load");
        };
        let reply = serde_json::json!({"placements": [placement], "conflicted": conflicted});
        let loaded = parse_arg(&load, &reply.to_string()).unwrap();
        match mutate.resume(Some(loaded)) {
            PimdirCoroutineState::Yielded(yielded) => {
                let envelope: Value = from_str(&yield_json(&yielded)).unwrap();
                Ok(envelope["writes"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|op| op["op"].as_str().unwrap().to_string())
                    .collect())
            }
            PimdirCoroutineState::Complete(Err(err)) => Err(err.to_string()),
            PimdirCoroutineState::Complete(Ok(_)) => panic!("an edit writes"),
        }
    }

    /// A card's placement on the load reply's wire, its status and its
    /// binding's conflict as stated.
    fn card(status: &str, conflict: Option<(&str, &str)>) -> Value {
        let mut placement = serde_json::json!({
            "collection": "acct/book", "handle": "a.vcf", "linkId": "uid-a",
            "object": "b1", "level": "full", "sortKey": "", "status": status,
            "base": {"revision": "e1", "object": "b1"},
        });
        if let Some((revision, object)) = conflict {
            placement["conflictRevision"] = revision.into();
            placement["conflictObject"] = object.into();
        }
        placement
    }

    /// The item conflict the Java load states (`conflicted`) reaches the
    /// engine: an ordinary edit over it is refused, staging nothing, and a
    /// resolution naming its diverging body crosses with io-pimdir's
    /// `Settle` before its upsert, which the Java owner hands to the hub
    /// (SYNC §7); an overruling edit, a rule's, crosses with its overrule.
    #[test]
    fn the_loaded_item_conflict_reaches_the_edit() {
        let ordinary =
            r#"{"op": "edit", "handle": "a.vcf", "hash": "b2", "size": 2, "body": "b2"}"#;
        let resolution = r#"{"op": "edit", "handle": "a.vcf", "hash": "b2", "size": 2,
            "body": "b2", "resolves": ["b9"]}"#;
        let overruling = r#"{"op": "edit", "handle": "a.vcf", "hash": "b2", "size": 2,
            "body": "b2", "overrule": true}"#;
        let item = serde_json::json!([{"handle": "a.vcf", "object": "b9"}]);

        for status in ["conflict", "tombstone"] {
            let refused = edit_over(ordinary, card(status, None), item.clone()).unwrap_err();
            assert!(refused.contains("b9"), "{refused}");
            assert_eq!(
                edit_over(resolution, card(status, None), item.clone()).unwrap(),
                ["storeObject", "settle", "upsert"],
            );
        }
        assert_eq!(
            edit_over(overruling, card("conflict", None), item.clone()).unwrap(),
            ["storeObject", "overrule", "upsert"],
        );

        // NOTE: a projected conflict the load does not state is one nobody
        // has read, which no resolution names.
        let unread = edit_over(resolution, card("conflict", None), Value::Array(vec![]));
        assert!(unread.unwrap_err().contains("an unread body"));
        // NOTE: an item in no conflict takes an ordinary edit, and refuses a
        // resolution of nothing.
        let none = Value::Array(vec![]);
        assert_eq!(
            edit_over(ordinary, card("clean", None), none.clone()).unwrap(),
            ["storeObject", "upsert"],
        );
        assert!(edit_over(resolution, card("clean", None), none).is_err());
    }

    /// A binding's own conflict takes a resolution naming its diverging
    /// body, the base adopting it, and refuses an ordinary edit; one whose
    /// item is conflicted too takes only a resolution naming both.
    #[test]
    fn a_binding_conflict_takes_only_a_resolution_naming_it() {
        let edit = |resolves: &str| {
            format!(
                r#"{{"op": "edit", "handle": "a.vcf", "hash": "b2", "size": 2, "body": "b2"{resolves}}}"#
            )
        };
        let binding = || card("conflict", Some(("e2", "b7")));
        let none = Value::Array(vec![]);
        let item = serde_json::json!([{"handle": "a.vcf", "object": "b9"}]);

        assert!(edit_over(&edit(""), binding(), none.clone()).is_err());
        assert_eq!(
            edit_over(&edit(r#", "resolves": ["b7"]"#), binding(), none.clone()).unwrap(),
            ["storeObject", "upsert"],
        );
        assert!(edit_over(&edit(r#", "resolves": ["b7"]"#), binding(), item.clone()).is_err());
        assert_eq!(
            edit_over(&edit(r#", "resolves": ["b7", "b9"]"#), binding(), item).unwrap(),
            ["storeObject", "settle", "upsert"],
        );
    }

    /// A store answering every load with one placement, and nothing else.
    struct Holding(Value);

    impl crate::client::Host for Holding {
        fn read(&mut self, _: &str) -> Result<Vec<u8>, crate::error::BridgeError> {
            unreachable!("a mutation reads no stream")
        }

        fn write(&mut self, _: &str, _: &[u8]) -> Result<(), crate::error::BridgeError> {
            unreachable!("a mutation writes no stream")
        }

        fn begin(&mut self, _: &str) -> bool {
            unreachable!("a mutation opens no request")
        }

        fn settle(&mut self, _: &str) {}

        fn reset(&mut self, _: &str) {}

        fn starttls(&mut self, _: &str) -> Result<(), crate::error::BridgeError> {
            unreachable!("a mutation opens no stream")
        }

        fn serve(&mut self, request: &str) -> Result<String, crate::error::BridgeError> {
            let request: Value = from_str(request).unwrap();
            assert_eq!(request["op"], "load", "a refused edit writes nothing");
            Ok(serde_json::json!({"placements": [self.0.clone()]}).to_string())
        }
    }

    /// An edit naming the body it was made over is refused once another
    /// write replaced it, the failure keeping io-pimdir's code for Java to
    /// branch on; a failure of the wire states none.
    #[test]
    fn an_edit_over_a_replaced_body_is_refused_with_its_code() {
        let edit = |read: &str| {
            format!(
                r#"{{"op": "edit", "handle": "a.vcf", "hash": "b2", "size": 2,
                "body": "b2", "ifMatch": "{read}"}}"#
            )
        };
        let mut driver = crate::offline::Driver::new(Holding(card("clean", None)));

        let Err(failure) = driver.mutate("acct/book", &edit("b0")) else {
            panic!("an edit over a replaced body is refused");
        };
        let reply = to_value(&failure).unwrap();
        assert_eq!(reply["refused"], "body-replaced", "{reply}");
        assert!(reply["error"].as_str().unwrap().contains("b0"), "{reply}");

        let Err(invalid) = driver.mutate("acct/book", "{}") else {
            panic!("a mutation of no op is refused");
        };
        assert_eq!(to_value(&invalid).unwrap().get("refused"), None);
    }
}
