//! The Graph listing over a fake Graph folder, driven by io-pimdir's own
//! sync engine and store: the first chunk, the round that makes the
//! unfiltered delta link, widenings, deltas, interruptions.
//!
//! The fake answers the three requests of [`GraphFolder`] the way Graph
//! does where it matters here: `/messages` filtered and ordered on
//! `sentDateTime`, a delta whose first pass names the folder as it was
//! when the pass began (a message gone since being skipped), and whose
//! link then reports every change made after that, whatever the date.

use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
};

use io_msgraph::v1::rest::users::{
    contacts::{MsgraphSingleValueExtendedProperty, delta::MsgraphRemoved},
    messages::{
        MsgraphFlagStatus, MsgraphFollowupFlag, MsgraphMessage,
        delta::{MsgraphMessageDelta, MsgraphMessagesDeltaResponse},
    },
};
use io_pimdir::{
    change::PimdirChange,
    client::{PimdirRunError, PimdirSourceStore, PimdirStore, reader::PimdirReader},
    collection::{PimdirCollectionId, PimdirScope},
    placement::PimdirHandle,
    remote::{
        PimdirEnumerate, PimdirEnumerated, PimdirFetchedItem, PimdirListing, PimdirPushResult,
        PimdirRemote, PimdirTier,
    },
    summary::PimdirSummary,
    sync::PimdirSyncOptions,
};
use jiff::{SignedDuration, Timestamp};

use super::{Band, GraphFolder, MessagesPage, list_folder, name_messages};
use crate::{
    client::listing::{Floor, Listing, MailRequest, Named, Scope},
    ffi::session::MailListing,
    types::{BridgeError, Mailbox},
};

const SOURCE: &str = "graph";
const INBOX: &str = "Inbox";

/// One message of the fake folder.
#[derive(Clone, Debug)]
struct Message {
    date: Option<String>,
    read: bool,
    /// The MAPI size the summary's `$expand` reads, [`None`] when Graph
    /// answers none.
    size: Option<u64>,
}

/// A Graph mail folder, and what was asked of it.
#[derive(Default)]
struct FakeGraph {
    folder: BTreeMap<String, Message>,
    /// The ids touched, in order: what a delta link reports past its mark.
    log: Vec<String>,
    /// The ids each first pass names, as the folder was when it began.
    passes: Vec<Vec<String>>,
    /// The page Graph cuts `/messages` and delta pages at.
    page: usize,
    /// Messages `/messages` answered, all pages counted.
    listed: usize,
    /// Rows delta pages answered.
    delta_rows: usize,
    /// First passes begun.
    delta_starts: usize,
    /// Delta requests, first passes and links alike.
    delta_calls: usize,
    /// Messages read by id.
    reads: usize,
    /// The delta request that fails, counted from one.
    fail_delta_call: Option<usize>,
    /// Whether the delta links are expired (410).
    expired: bool,
}

/// The date of the message `hours` before the folder's newest.
fn hours_back(hours: i64) -> String {
    let top: Timestamp = "2026-10-01T12:00:00Z".parse().unwrap();
    top.checked_sub(SignedDuration::from_hours(hours))
        .unwrap()
        .strftime("%Y-%m-%dT%H:%M:%SZ")
        .to_string()
}

impl FakeGraph {
    /// A folder of `size` messages, one an hour, `m0000` the newest, each
    /// of 1,000 bytes plus its index.
    fn new(size: usize) -> Self {
        let mut graph = Self {
            page: 1000,
            ..Default::default()
        };
        for index in 0..size {
            graph.folder.insert(
                format!("m{index:04}"),
                Message {
                    date: Some(hours_back(index as i64)),
                    read: false,
                    size: Some(1000 + index as u64),
                },
            );
        }
        graph
    }

    /// Adds a message Graph states no size for.
    fn add(&mut self, id: &str, date: Option<String>) {
        let message = Message {
            date,
            read: false,
            size: None,
        };
        self.folder.insert(id.into(), message);
        self.log.push(id.into());
    }

    fn remove(&mut self, id: &str) {
        self.folder.remove(id);
        self.log.push(id.into());
    }

    fn mark_read(&mut self, id: &str) {
        self.folder.get_mut(id).unwrap().read = true;
        self.log.push(id.into());
    }

    /// The floor of the next chunk below `before`, the way `mailFloor`
    /// reads it: the oldest `Date` among the `count` newest below it.
    fn floor(&self, before: Option<&str>, count: usize) -> Option<String> {
        let mut floor = Floor::new(before, count);
        for message in self.newest_first() {
            if floor.take(message.date.as_deref()) {
                break;
            }
        }
        floor.reply().floor
    }

    fn newest_first(&self) -> Vec<&Message> {
        let mut messages: Vec<&Message> = self.folder.values().collect();
        messages.sort_by(|a, b| b.date.cmp(&a.date));
        messages
    }

    fn message(&self, id: &str) -> MsgraphMessage {
        let message = &self.folder[id];
        MsgraphMessage {
            id: id.into(),
            subject: Some(format!("Subject {id}")),
            sent_date_time: message.date.clone(),
            is_read: Some(message.read),
            flag: Some(MsgraphFollowupFlag {
                flag_status: Some(MsgraphFlagStatus::NotFlagged),
            }),
            single_value_extended_properties: message
                .size
                .map(|size| MsgraphSingleValueExtendedProperty {
                    id: "Integer 0xe08".into(),
                    value: size.to_string(),
                })
                .into_iter()
                .collect(),
            ..Default::default()
        }
    }

    /// The row a delta answers for a message: its id, `Date` and markers,
    /// what the delta's `$select` names.
    fn row(&self, id: &str) -> MsgraphMessageDelta {
        match self.folder.get(id) {
            Some(message) => MsgraphMessageDelta {
                message: MsgraphMessage {
                    id: id.into(),
                    sent_date_time: message.date.clone(),
                    is_read: Some(message.read),
                    ..Default::default()
                },
                removed: None,
            },
            None => MsgraphMessageDelta {
                message: MsgraphMessage {
                    id: id.into(),
                    ..Default::default()
                },
                removed: Some(MsgraphRemoved {
                    reason: "deleted".into(),
                }),
            },
        }
    }
}

impl GraphFolder for FakeGraph {
    fn messages(&mut self, band: &Band, top: u32) -> Result<MessagesPage, BridgeError> {
        let open = band.since.is_none() && band.until.is_none();
        let mut matching: Vec<(&String, &Message)> = self
            .folder
            .iter()
            .filter(|(_, message)| match message.date.as_deref() {
                None => open,
                Some(date) => {
                    band.since.as_deref().is_none_or(|since| date >= since)
                        && band
                            .until
                            .as_deref()
                            .is_none_or(|until| match band.inclusive {
                                true => date <= until,
                                false => date < until,
                            })
                }
            })
            .collect();
        // NOTE: Graph orders ties as it likes; the id keeps the fake's
        // order fixed.
        matching.sort_by(|a, b| b.1.date.cmp(&a.1.date).then(b.0.cmp(a.0)));

        let take = (top as usize).min(self.page);
        let ids: Vec<String> = matching
            .iter()
            .take(take)
            .map(|(id, _)| (*id).clone())
            .collect();
        self.listed += ids.len();
        Ok(MessagesPage {
            more: matching.len() > take,
            value: ids.iter().map(|id| self.message(id)).collect(),
        })
    }

    fn delta(
        &mut self,
        link: Option<&str>,
    ) -> Result<Option<MsgraphMessagesDeltaResponse>, BridgeError> {
        self.delta_calls += 1;
        if self.fail_delta_call == Some(self.delta_calls) {
            return Err("connection lost".into());
        }

        // NOTE: a first pass is `pass:<pass>:<mark>:<offset>`, a delta
        // link `since:<mark>`.
        let (pass, mark, offset) = match link {
            None => {
                self.delta_starts += 1;
                let mut ids: Vec<String> = self.folder.keys().cloned().collect();
                ids.sort_by(|a, b| self.folder[b].date.cmp(&self.folder[a].date));
                self.passes.push(ids);
                (self.passes.len() - 1, self.log.len(), 0)
            }
            Some(link) if link.starts_with("since:") => {
                if self.expired {
                    return Ok(None);
                }
                let mark: usize = link["since:".len()..].parse().unwrap();
                let touched: BTreeSet<String> = self.log[mark..].iter().cloned().collect();
                let value: Vec<MsgraphMessageDelta> =
                    touched.iter().map(|id| self.row(id)).collect();
                self.delta_rows += value.len();
                return Ok(Some(MsgraphMessagesDeltaResponse {
                    value,
                    next_link: None,
                    delta_link: Some(format!("since:{}", self.log.len())),
                }));
            }
            Some(link) => {
                let parts: Vec<usize> = link["pass:".len()..]
                    .split(':')
                    .map(|part| part.parse().unwrap())
                    .collect();
                (parts[0], parts[1], parts[2])
            }
        };

        let ids = &self.passes[pass];
        let end = (offset + self.page).min(ids.len());
        let value: Vec<MsgraphMessageDelta> = ids[offset..end]
            .iter()
            .filter(|id| self.folder.contains_key(*id))
            .map(|id| self.row(id))
            .collect();
        self.delta_rows += value.len();
        let (next_link, delta_link) = match end < ids.len() {
            true => (Some(format!("pass:{pass}:{mark}:{end}")), None),
            false => (None, Some(format!("since:{mark}"))),
        };
        Ok(Some(MsgraphMessagesDeltaResponse {
            value,
            next_link,
            delta_link,
        }))
    }

    fn read(&mut self, ids: &[String]) -> Result<Vec<MsgraphMessage>, BridgeError> {
        let read: Vec<MsgraphMessage> = ids
            .iter()
            .filter(|id| self.folder.contains_key(*id))
            .map(|id| self.message(id))
            .collect();
        self.reads += read.len();
        Ok(read)
    }
}

/// The engine's remote over the fake: the request as Java forwards it,
/// `covered` read off the store's coverage, and the members a delta lists
/// by id named the way `MailEngine` names them, the ones the store does
/// not bind read by id.
struct Remote<'g> {
    graph: &'g mut FakeGraph,
    dir: PathBuf,
}

impl PimdirRemote for Remote<'_> {
    type Error = String;

    fn enumerate(
        &mut self,
        collection: &PimdirCollectionId,
        request: PimdirEnumerate,
    ) -> Result<PimdirEnumerated, String> {
        let reader = PimdirReader::open(&self.dir).map_err(|err| err.to_string())?;
        let covered = reader
            .list_coverage(collection.as_str())
            .unwrap()
            .iter()
            .filter(|source| source.source == SOURCE)
            .any(|source| {
                source
                    .coverage
                    .as_ref()
                    .is_some_and(|coverage| coverage.scope.covers(&request.scope))
            });
        let listing = match request.listing {
            PimdirListing::Delta(checkpoint) => Listing::Delta {
                checkpoint: String::from_utf8(checkpoint.0).unwrap(),
            },
            PimdirListing::Round { cursor, band } => Listing::Round {
                cursor: cursor.map(|cursor| String::from_utf8(cursor.0).unwrap()),
                band,
            },
        };
        let request = MailRequest {
            listing,
            scope: Scope {
                since: request.scope.since.clone(),
                until: request.scope.until.clone(),
            },
            covered,
            received: None,
        };
        let mut page = list_folder(self.graph, &request).map_err(|err| err.message)?;

        let bound = handles(&self.dir);
        let unbound: Vec<String> = page
            .items
            .iter()
            .filter(|item| item.summary.is_none() && !bound.contains(&item.handle))
            .map(|item| item.handle.clone())
            .collect();
        let mut read: BTreeMap<String, Named> = name_messages(self.graph, &unbound)
            .map_err(|err| err.message)?
            .into_iter()
            .map(|item| (item.handle.clone(), item))
            .collect();
        page.items = page
            .items
            .into_iter()
            .filter_map(
                |item| match item.summary.is_some() || bound.contains(&item.handle) {
                    true => Some(item),
                    false => read.remove(&item.handle),
                },
            )
            .collect();

        Ok(crate::offline::enumerated(
            &serde_json::to_string(&page).unwrap(),
        ))
    }

    fn fetch(
        &mut self,
        _: &PimdirCollectionId,
        _: Vec<PimdirHandle>,
        _: PimdirTier,
    ) -> Result<Vec<PimdirFetchedItem>, String> {
        Ok(Vec::new())
    }

    fn push(
        &mut self,
        _: &PimdirCollectionId,
        _: Vec<PimdirChange>,
    ) -> Result<Vec<PimdirPushResult>, String> {
        Ok(Vec::new())
    }

    fn scope_bound(&self) -> bool {
        false
    }
}

/// The message ids the store holds in the inbox.
fn handles(dir: &Path) -> BTreeSet<String> {
    let reader = PimdirReader::open(dir).unwrap();
    reader
        .list_items(INBOX, None, 100_000)
        .unwrap()
        .into_iter()
        .map(|item| item.link_id.as_str().to_string())
        .collect()
}

/// Whether the store holds the message as read.
fn is_read(dir: &Path, id: &str) -> bool {
    let reader = PimdirReader::open(dir).unwrap();
    reader
        .list_items(INBOX, None, 100_000)
        .unwrap()
        .into_iter()
        .find(|item| item.link_id.as_str() == id)
        .is_some_and(|item| item.flags.contains("\\Seen"))
}

/// The size the store's summary holds for the message.
fn size(dir: &Path, id: &str) -> Option<u64> {
    let reader = PimdirReader::open(dir).unwrap();
    let item = reader
        .list_summaries(INBOX, None, 100_000)
        .unwrap()
        .into_iter()
        .find(|item| item.link_id.as_str() == id)
        .unwrap();
    match item.summary {
        Some(PimdirSummary::Mail(summary)) => summary.size,
        other => panic!("no mail summary: {other:?}"),
    }
}

/// Whether the inbox has a round under way.
fn round_open(dir: &Path) -> bool {
    let reader = PimdirReader::open(dir).unwrap();
    reader.list_coverage(INBOX).unwrap()[0].round.is_some()
}

/// A store with an empty inbox.
fn store(dir: &Path) -> PimdirSourceStore {
    let store = PimdirStore::open(dir).unwrap().for_source(SOURCE);
    store.ensure_collection(INBOX, "message/rfc822").unwrap();
    store
}

/// One pass over the inbox from `since`.
fn sync(
    store: &mut PimdirSourceStore,
    graph: &mut FakeGraph,
    dir: &Path,
    since: Option<&str>,
) -> Result<(), PimdirRunError<String, io_pimdir::coroutine::PimdirArgError>> {
    let opts = PimdirSyncOptions {
        scope: PimdirScope {
            since: since.map(String::from),
            until: None,
        },
        ..Default::default()
    };
    let mut remote = Remote {
        graph,
        dir: dir.to_path_buf(),
    };
    store.sync(INBOX, opts, &mut remote).map(|_| ())
}

#[test]
fn a_band_filters_on_the_date_header_exactly() {
    let band = Band {
        since: Some("2026-09-01T00:00:00Z".into()),
        until: Some("2026-10-01T00:00:00Z".into()),
        inclusive: false,
    };
    assert_eq!(
        band.filter().as_deref(),
        Some("sentDateTime ge 2026-09-01T00:00:00Z and sentDateTime lt 2026-10-01T00:00:00Z"),
    );

    let resumed = Band {
        inclusive: true,
        ..band.clone()
    };
    assert_eq!(
        resumed.filter().as_deref(),
        Some("sentDateTime ge 2026-09-01T00:00:00Z and sentDateTime le 2026-10-01T00:00:00Z"),
        "a page resuming among one second's messages takes that second",
    );

    let first = Band {
        since: Some("2026-09-01T00:00:00Z".into()),
        ..Default::default()
    };
    assert_eq!(
        first.filter().as_deref(),
        Some("sentDateTime ge 2026-09-01T00:00:00Z")
    );
    assert_eq!(Band::default().filter(), None);
}

#[test]
fn the_first_chunk_lands_before_any_delta_link_exists() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(300);
    graph.page = 100;
    let floor = graph.floor(None, 50).unwrap();

    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(handles(dir.path()).len(), 50, "the first chunk landed");
    assert_eq!(graph.listed, 50, "by /messages, the band alone");
    assert_eq!(graph.delta_calls, 0, "no delta was asked for");
    assert!(!round_open(dir.path()), "the round closed with the chunk");

    // NOTE: the next pass makes the delta link: the first pass names the
    // folder's 300 messages by id, three pages, and reads none of them.
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(graph.delta_starts, 1);
    assert_eq!(graph.delta_calls, 3);
    assert_eq!(graph.delta_rows, 300);
    assert_eq!(graph.reads, 0, "every member in scope is bound already");
    assert_eq!(graph.listed, 50, "nothing relisted");
    assert_eq!(handles(dir.path()).len(), 50);

    // NOTE: and the pass after it is one delta request.
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(graph.delta_calls, 4);
    assert_eq!(graph.delta_starts, 1);
    assert_eq!(graph.delta_rows, 300);
}

#[test]
fn widening_three_times_lists_each_band_once() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(400);

    let mut floor = graph.floor(None, 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();

    // NOTE: a widening before the delta link exists lists its band too.
    floor = graph.floor(Some(&floor), 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(graph.delta_calls, 0);

    // NOTE: the pass that makes the delta link, then two more widenings.
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    for _ in 0..2 {
        floor = graph.floor(Some(&floor), 50).unwrap();
        sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    }
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();

    assert_eq!(handles(dir.path()).len(), 200);
    assert_eq!(graph.listed, 200, "the first chunk and three bands of 50");
    assert_eq!(
        graph.delta_starts, 1,
        "one delta link, kept across widenings"
    );
    assert_eq!(graph.delta_rows, 400, "the folder named by id once");
    assert_eq!(graph.reads, 0);
}

#[test]
fn a_delta_drops_what_lies_out_of_scope() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(200);
    let floor = graph.floor(None, 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    let rows = graph.delta_rows;

    graph.mark_read("m0150");
    graph.add("old", Some(hours_back(1000)));
    graph.add("new", Some(hours_back(-1)));
    graph.remove("m0003");
    graph.mark_read("m0010");

    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(graph.delta_rows, rows + 5, "the delta reports all five");
    assert_eq!(graph.reads, 1, "only the new message in scope is read");

    let held = handles(dir.path());
    assert!(held.contains("new"));
    assert!(!held.contains("old"), "dated out of scope");
    assert!(!held.contains("m0150"), "dated out of scope");
    assert!(!held.contains("m0003"), "removed");
    assert_eq!(held.len(), 50);
    assert!(is_read(dir.path(), "m0010"));
    assert_eq!(graph.listed, 50, "no band listed");
}

#[test]
fn what_changes_before_the_delta_link_exists_is_not_lost() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(200);
    let floor = graph.floor(None, 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();

    // NOTE: between the first chunk and the pass that makes the link.
    graph.remove("m0002");
    graph.mark_read("m0005");
    graph.add("arrived", Some(hours_back(-2)));
    graph.add("undated", None);

    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    let held = handles(dir.path());
    assert!(!held.contains("m0002"), "absent from the first pass");
    assert!(is_read(dir.path(), "m0005"));
    assert!(held.contains("arrived"));
    assert!(held.contains("undated"), "no date is in every scope");
    assert_eq!(graph.reads, 2, "the two new ones, read by id");
    assert_eq!(held.len(), 51);
}

#[test]
fn the_store_holds_the_size_graph_states() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(200);
    let floor = graph.floor(None, 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(size(dir.path(), "m0007"), Some(1007), "listed by band");

    // NOTE: the delta lists by id alone; the ones it names are read by
    // id, the size riding the read.
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    graph.add("sized", Some(hours_back(-1)));
    graph.folder.get_mut("sized").unwrap().size = Some(48213);
    graph.add("unsized", Some(hours_back(-2)));
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(graph.reads, 2);
    assert_eq!(size(dir.path(), "sized"), Some(48213), "read by id");
    assert_eq!(size(dir.path(), "unsized"), None, "Graph stated none");
}

#[test]
fn an_interrupted_first_pass_resumes_from_its_page() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(300);
    graph.page = 100;
    let floor = graph.floor(None, 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();

    graph.fail_delta_call = Some(2);
    let interrupted = sync(&mut store, &mut graph, dir.path(), Some(&floor));
    assert!(matches!(interrupted, Err(PimdirRunError::Remote(_))));
    assert!(round_open(dir.path()), "the round waits on its cursor");

    graph.fail_delta_call = None;
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert!(!round_open(dir.path()));
    assert_eq!(graph.delta_starts, 1, "resumed, not begun again");
    assert_eq!(graph.delta_calls, 4, "one page, a failure, then two pages");
    assert_eq!(graph.delta_rows, 300);
    assert_eq!(handles(dir.path()).len(), 50);

    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(graph.delta_calls, 5, "a delta from the link the pass made");
}

#[test]
fn an_expired_link_makes_another_without_relisting() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(200);
    let floor = graph.floor(None, 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();

    graph.expired = true;
    graph.remove("m0001");
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(graph.delta_starts, 2, "a new first pass");
    assert_eq!(graph.listed, 50, "no band relisted");
    assert!(!handles(dir.path()).contains("m0001"));
}

#[test]
fn a_band_pages_through_one_second_without_loss() {
    let mut graph = FakeGraph::new(0);
    graph.page = 4;
    for index in 0..12 {
        // NOTE: three messages of one second across the first page's end.
        let hours = match index {
            2..5 => 2,
            _ => index,
        };
        graph.add(&format!("t{index:02}"), Some(hours_back(hours)));
    }

    let scope = Scope::default();
    let mut cursor: Option<String> = None;
    let mut listed = Vec::new();
    loop {
        let request = MailRequest {
            listing: Listing::Round {
                cursor: cursor.clone(),
                band: false,
            },
            scope: scope.clone(),
            covered: false,
            received: None,
        };
        let page = list_folder(&mut graph, &request).unwrap();
        assert!(page.checkpoint.is_none(), "a band hands no checkpoint");
        listed.extend(page.items.into_iter().map(|item| item.handle));
        if listed.len() == 4 {
            // NOTE: a message of the first page goes before the second.
            graph.remove("t00");
        }
        match page.cursor {
            Some(next) => cursor = Some(next),
            None => break,
        }
    }

    listed.sort();
    let expected: Vec<String> = (0..12).map(|index| format!("t{index:02}")).collect();
    assert_eq!(listed, expected, "each message once, none skipped");
}

#[test]
fn a_link_or_cursor_from_before_is_refused() {
    let mut graph = FakeGraph::new(10);
    let filtered = "https://graph.microsoft.com/v1.0/me/mailFolders/x/messages/delta?$deltatoken=t";

    let delta = MailRequest {
        listing: Listing::Delta {
            checkpoint: filtered.into(),
        },
        scope: Scope::default(),
        covered: true,
        received: None,
    };
    assert!(list_folder(&mut graph, &delta).unwrap().cursor_rejected);

    let round = MailRequest {
        listing: Listing::Round {
            cursor: Some(filtered.into()),
            band: false,
        },
        scope: Scope::default(),
        covered: false,
        received: None,
    };
    assert!(list_folder(&mut graph, &round).unwrap().cursor_rejected);
    assert_eq!(
        graph.delta_calls + graph.listed,
        0,
        "nothing asked of Graph"
    );
}

// NOTE: a band listed by `sentDateTime` never lists an undated message, so
// its absence from a band round proves nothing; the round infers no
// deletion of it (io-pimdir ff28408, pimdir SYNC §5), and a delta or a
// round over a whole scope still answers for it.
#[test]
fn an_undated_message_survives_a_widening() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(200);
    graph.add("undated", None);

    let mut floor = graph.floor(None, 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert!(
        handles(dir.path()).contains("undated"),
        "named by the delta"
    );

    floor = graph.floor(Some(&floor), 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert!(
        handles(dir.path()).contains("undated"),
        "a band listed by date says nothing of an undated message"
    );
}

// NOTE: the owner's device, a Microsoft 365 account: a session opened to
// widen a mailbox, or by a step of the fill, had never read the roster, so
// it knew no folder id and refused every mailbox ("No mailbox named
// `Archive`"). The session reads the roster on its first lookup now.
#[test]
fn a_fresh_session_widens_and_fills_a_folder_it_never_listed() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = store(dir.path());
    let mut graph = FakeGraph::new(200);

    // NOTE: the first chunk, on the pass's own session.
    let mut floor = graph.floor(None, 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();

    let folders = || -> Result<Vec<(String, Mailbox)>, BridgeError> {
        Ok(vec![
            (
                "AAMk-inbox".into(),
                Mailbox {
                    name: INBOX.into(),
                    role: "inbox".into(),
                },
            ),
            (
                "AAMk-archive".into(),
                Mailbox {
                    name: "Archive".into(),
                    role: String::new(),
                },
            ),
        ])
    };
    let mut fresh = MailListing::default();
    let mut rosters = 0;

    // NOTE: a widening by a chunk of 50 on the fresh session.
    let id = fresh.resolve(INBOX, || {
        rosters += 1;
        folders()
    });
    assert_eq!(id.unwrap(), "AAMk-inbox");
    floor = graph.floor(Some(&floor), 50).unwrap();
    sync(&mut store, &mut graph, dir.path(), Some(&floor)).unwrap();
    assert_eq!(handles(dir.path()).len(), 100);

    // NOTE: a fill step of 500 on the same session: the rest, the folder
    // whole below its floor, and no second roster.
    let id = fresh.resolve(INBOX, || {
        rosters += 1;
        folders()
    });
    assert_eq!(id.unwrap(), "AAMk-inbox");
    assert_eq!(graph.floor(Some(&floor), 500), None);
    sync(&mut store, &mut graph, dir.path(), None).unwrap();
    assert_eq!(handles(dir.path()).len(), 200);

    let archive = fresh.resolve("Archive", || {
        rosters += 1;
        folders()
    });
    assert_eq!(archive.unwrap(), "AAMk-archive");
    assert_eq!(rosters, 1, "the roster is read once per session");
}

#[test]
fn a_second_holding_more_than_a_page_goes_on_below_it() {
    let mut graph = FakeGraph::new(0);
    graph.page = 4;
    for index in 0..10 {
        // NOTE: six messages of one second, more than a page.
        let hours = match index {
            0..6 => 1,
            _ => index as i64,
        };
        graph.add(&format!("t{index:02}"), Some(hours_back(hours)));
    }

    let mut cursor: Option<String> = None;
    let mut listed = BTreeSet::new();
    let mut pages = 0;
    loop {
        let request = MailRequest {
            listing: Listing::Round {
                cursor: cursor.clone(),
                band: false,
            },
            scope: Scope::default(),
            covered: false,
            received: None,
        };
        let page = list_folder(&mut graph, &request).unwrap();
        pages += 1;
        listed.extend(page.items.into_iter().map(|item| item.handle));
        match page.cursor {
            Some(next) => cursor = Some(next),
            None => break,
        }
    }

    assert!(pages < 10, "the band ends");
    for older in ["t06", "t07", "t08", "t09"] {
        assert!(listed.contains(older), "{older} below the crowded second");
    }
}

/// The step and the bodies' batches over a fake Graph account.
mod step {
    use std::{
        collections::{BTreeMap, HashMap, HashSet},
        fs,
        path::Path,
        time::Duration,
    };

    use io_msgraph::v1::rest::users::{
        contacts::MsgraphSingleValueExtendedProperty, messages::MsgraphMessage,
    };

    use super::hours_back;
    use crate::{
        client::graph_mail::{
            ALONE_BYTES, Band, GraphFetched, GraphMail, Inner, StepAsk, read_sources, step,
        },
        types::BridgeError,
    };

    /// One message of the fake account.
    #[derive(Clone)]
    struct Message {
        folder: String,
        date: String,
        received: String,
        size: Option<u64>,
    }

    /// A Graph account, and what was asked of it.
    #[derive(Default)]
    struct FakeMail {
        messages: BTreeMap<String, Message>,
        /// Whether the account's listing answers `InefficientFilter`.
        inefficient: bool,
        /// The listings asked for, folder (none for the account) and band.
        listings: Vec<(Option<String>, Band)>,
        /// The ids of each `$batch`.
        batches: Vec<Vec<String>>,
        /// The ids read alone.
        alone: Vec<String>,
        /// The pauses waited.
        pauses: Vec<Duration>,
        /// What the next `$batch` answers some ids, in place of their body,
        /// taken once each.
        inner: HashMap<String, Vec<Inner>>,
        /// Whether a `$batch` fails as an expired token.
        expired: bool,
        /// What the store holds, as a step asks it.
        held: HashSet<String>,
    }

    impl FakeMail {
        fn add(&mut self, id: &str, folder: &str, date: String, size: Option<u64>) {
            self.messages.insert(
                id.into(),
                Message {
                    folder: folder.into(),
                    received: date.clone(),
                    date,
                    size,
                },
            );
        }

        /// `count` messages of `folder`, an hour apart from `from` hours
        /// back, named `<prefix><index>`, 40 KB each.
        fn fill(&mut self, prefix: &str, folder: &str, from: i64, count: usize) {
            for index in 0..count {
                let hours = from + index as i64;
                self.add(
                    &format!("{prefix}{index:03}"),
                    folder,
                    hours_back(hours),
                    Some(40_000),
                );
            }
        }

        fn body(id: &str) -> Vec<u8> {
            format!("Subject: {id}\r\n\r\nBody\r\n").into_bytes()
        }
    }

    impl GraphMail for FakeMail {
        fn dated(
            &mut self,
            folder: Option<&str>,
            band: &Band,
            top: u32,
        ) -> Result<Vec<MsgraphMessage>, BridgeError> {
            self.listings.push((folder.map(String::from), band.clone()));
            if folder.is_none() && self.inefficient {
                return Err(BridgeError {
                    message: "Microsoft Graph API returned HTTP 400 (InefficientFilter): \
                              The restriction or sort order is too complex"
                        .into(),
                    status: Some(400),
                });
            }
            let mut matching: Vec<(&String, &Message)> = self
                .messages
                .iter()
                .filter(|(_, message)| folder.is_none_or(|folder| message.folder == folder))
                .filter(|(_, message)| {
                    band.since
                        .as_deref()
                        .is_none_or(|since| message.date.as_str() >= since)
                        && band
                            .until
                            .as_deref()
                            .is_none_or(|until| match band.inclusive {
                                true => message.date.as_str() <= until,
                                false => message.date.as_str() < until,
                            })
                })
                .collect();
            matching.sort_by(|a, b| b.1.date.cmp(&a.1.date).then(a.0.cmp(b.0)));
            Ok(matching
                .into_iter()
                .take(top as usize)
                .map(|(id, message)| MsgraphMessage {
                    id: id.clone(),
                    sent_date_time: Some(message.date.clone()),
                    received_date_time: Some(message.received.clone()),
                    parent_folder_id: Some(message.folder.clone()),
                    single_value_extended_properties: message
                        .size
                        .map(|size| MsgraphSingleValueExtendedProperty {
                            id: "Integer 0xe08".into(),
                            value: size.to_string(),
                        })
                        .into_iter()
                        .collect(),
                    ..Default::default()
                })
                .collect())
        }

        fn batch(&mut self, ids: &[String]) -> Result<Vec<(String, Inner)>, BridgeError> {
            if self.expired {
                return Err(BridgeError {
                    message: "expired".into(),
                    status: Some(401),
                });
            }
            self.batches.push(ids.to_vec());
            Ok(ids
                .iter()
                .map(|id| {
                    let scripted = self
                        .inner
                        .get_mut(id)
                        .filter(|answers| !answers.is_empty())
                        .map(|answers| answers.remove(0));
                    let answer = match scripted {
                        Some(answer) => answer,
                        None if self.messages.contains_key(id) => Inner::Body(Self::body(id)),
                        None => Inner::Gone,
                    };
                    (id.clone(), answer)
                })
                .collect())
        }

        fn source(&mut self, id: &str) -> Result<Vec<u8>, BridgeError> {
            self.alone.push(id.into());
            match self.messages.contains_key(id) {
                true => Ok(Self::body(id)),
                false => Err(BridgeError {
                    message: "gone".into(),
                    status: Some(404),
                }),
            }
        }

        fn sizes(&mut self, ids: &[String]) -> Result<HashMap<String, u64>, BridgeError> {
            Ok(ids
                .iter()
                .filter_map(|id| Some((id.clone(), self.messages.get(id)?.size?)))
                .collect())
        }

        fn pause(&mut self, delay: Duration) {
            self.pauses.push(delay);
        }

        fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError> {
            Ok(ids
                .iter()
                .filter(|id| self.held.contains(*id))
                .cloned()
                .collect())
        }
    }

    fn ask<'a>(folders: &'a [String], frontier: Option<&'a str>) -> StepAsk<'a> {
        StepAsk {
            folders,
            alone: false,
            frontier,
            bound: None,
            count: 50,
        }
    }

    fn folders(names: &[&str]) -> Vec<String> {
        names.iter().map(|name| name.to_string()).collect()
    }

    fn ids(fetched: &[GraphFetched]) -> Vec<String> {
        let mut ids: Vec<String> = fetched.iter().map(|fetched| fetched.id.clone()).collect();
        ids.sort();
        ids
    }

    #[test]
    fn one_listing_takes_fifty_across_folders_leaving_the_trash() {
        let mut graph = FakeMail::default();
        graph.fill("i", "inbox", 0, 40);
        graph.fill("s", "sent", 100, 20);
        // NOTE: the trash interleaved with the inbox, never stepped.
        for index in 0..30 {
            graph.add(
                &format!("t{index:03}"),
                "trash",
                hours_back(index * 2),
                Some(1),
            );
        }
        let stepped_folders = folders(&["inbox", "sent"]);

        let stepped = step(&mut graph, &ask(&stepped_folders, None)).unwrap();
        assert_eq!(stepped.messages.len(), 50);
        assert!(!stepped.exhausted);
        assert!(
            stepped
                .messages
                .iter()
                .all(|message| message.folder != "trash")
        );
        assert_eq!(
            stepped
                .messages
                .iter()
                .filter(|m| m.folder == "sent")
                .count(),
            10,
            "the inbox's 40, then the sent mail's newest 10"
        );
        assert!(
            graph.listings.iter().all(|(folder, _)| folder.is_none()),
            "the account's listing alone"
        );
        assert!(graph.listings.len() > 1, "a keyset past the trash");
        let (_, last) = graph.listings.last().unwrap();
        assert!(
            last.inclusive,
            "from the oldest Date reached, that second included"
        );
        assert_eq!(stepped.messages[0].size, Some(40_000));
        assert!(stepped.messages[0].received.is_some());
    }

    #[test]
    fn the_next_step_starts_at_the_frontier_dropping_what_is_held_there() {
        let mut graph = FakeMail::default();
        graph.fill("i", "inbox", 0, 120);
        let inbox = folders(&["inbox"]);

        let first = step(&mut graph, &ask(&inbox, None)).unwrap();
        graph
            .held
            .extend(first.messages.iter().map(|message| message.id.clone()));
        let frontier = first.messages.last().unwrap().date.clone();

        let next = step(&mut graph, &ask(&inbox, Some(&frontier))).unwrap();
        assert_eq!(next.messages.len(), 50);
        assert!(
            next.messages
                .iter()
                .all(|message| !graph.held.contains(&message.id))
        );
        assert_eq!(next.messages[0].id, "i050", "right below the first step");
        let (_, band) = &graph.listings[graph.listings.len() - 1];
        assert_eq!(band.until.as_deref(), Some(frontier.as_str()));
        assert!(band.inclusive, "le the frontier");
    }

    #[test]
    fn a_busy_second_comes_whole() {
        let mut graph = FakeMail::default();
        graph.fill("i", "inbox", 0, 49);
        // NOTE: the 50th shares its Date with four older ones.
        for index in 0..5 {
            graph.add(&format!("b{index}"), "inbox", hours_back(49), Some(10));
        }
        graph.fill("o", "inbox", 60, 10);
        let inbox = folders(&["inbox"]);

        let stepped = step(&mut graph, &ask(&inbox, None)).unwrap();
        assert_eq!(stepped.messages.len(), 54);
        assert!(!stepped.exhausted);
    }

    #[test]
    fn a_small_account_is_exhausted() {
        let mut graph = FakeMail::default();
        graph.fill("i", "inbox", 0, 30);
        let inbox = folders(&["inbox"]);

        let stepped = step(&mut graph, &ask(&inbox, None)).unwrap();
        assert_eq!(stepped.messages.len(), 30);
        assert!(stepped.exhausted);
        assert_eq!(graph.listings.len(), 1);
    }

    #[test]
    fn a_second_fuller_than_a_page_is_left_below() {
        let mut graph = FakeMail::default();
        // NOTE: a page is the count and its slack, 30 for 5.
        for index in 0..40 {
            graph.add(&format!("c{index:02}"), "inbox", hours_back(1), Some(10));
        }
        graph.fill("o", "inbox", 5, 10);
        let inbox = folders(&["inbox"]);
        let mut asked = ask(&inbox, None);
        asked.count = 5;

        let stepped = step(&mut graph, &asked).unwrap();
        assert_eq!(stepped.messages.len(), 30, "the page's share of the second");
        assert!(!stepped.exhausted);
        assert!(graph.listings.len() <= 3, "no loop");
    }

    #[test]
    fn an_inefficient_filter_merges_each_folders_listing() {
        let mut graph = FakeMail::default();
        graph.fill("i", "inbox", 0, 40);
        graph.fill("s", "sent", 20, 40);
        let both = folders(&["inbox", "sent"]);

        let listed = step(&mut graph, &ask(&both, None)).unwrap();
        graph.inefficient = true;
        graph.listings.clear();
        let merged = step(&mut graph, &ask(&both, None)).unwrap();

        assert_eq!(merged.messages, listed.messages);
        assert_eq!(merged.exhausted, listed.exhausted);
        let folders: Vec<Option<String>> = graph
            .listings
            .iter()
            .map(|(folder, _)| folder.clone())
            .collect();
        assert!(folders.contains(&Some("inbox".into())));
        assert!(folders.contains(&Some("sent".into())));
    }

    #[test]
    fn a_filtered_view_lists_its_folder() {
        let mut graph = FakeMail::default();
        graph.fill("i", "inbox", 0, 40);
        graph.fill("p", "projets", 0, 10);
        let projets = folders(&["projets"]);
        let mut asked = ask(&projets, None);
        asked.alone = true;

        let stepped = step(&mut graph, &asked).unwrap();
        assert_eq!(stepped.messages.len(), 10);
        assert!(stepped.exhausted);
        assert_eq!(graph.listings[0].0.as_deref(), Some("projets"));
    }

    #[test]
    fn bodies_go_twenty_and_four_megabytes_a_batch_the_large_alone() {
        let dir = tempfile::tempdir().unwrap();
        let mut graph = FakeMail::default();
        let mut wanted = Vec::new();
        for index in 0..50 {
            let id = format!("m{index:02}");
            graph.add(&id, "inbox", hours_back(index), Some(40_000));
            wanted.push((id, Some(40_000)));
        }
        graph.add("large", "inbox", hours_back(60), Some(3_000_000));
        wanted.push(("large".into(), Some(3_000_000)));
        graph.add("unsized", "inbox", hours_back(61), None);
        wanted.push(("unsized".into(), None));
        for index in 0..10 {
            let id = format!("h{index}");
            graph.add(&id, "inbox", hours_back(70), Some(ALONE_BYTES - 1));
            wanted.push((id, Some(ALONE_BYTES - 1)));
        }

        let fetched = read_sources(&mut graph, &wanted, dir.path())
            .unwrap()
            .fetched;
        assert_eq!(fetched.len(), 62);
        let sizes: Vec<usize> = graph.batches.iter().map(Vec::len).collect();
        assert_eq!(sizes, vec![20, 20, 13, 4, 3], "twenty, then four megabytes");
        assert_eq!(graph.alone, vec!["large", "unsized"]);
        for file in &fetched {
            assert_eq!(fs::read(&file.path).unwrap(), FakeMail::body(&file.id));
            assert_eq!(file.size, FakeMail::body(&file.id).len());
        }
    }

    #[test]
    fn a_throttled_item_waits_its_retry_after_once() {
        let dir = tempfile::tempdir().unwrap();
        let mut graph = FakeMail::default();
        let mut wanted = Vec::new();
        for id in ["a", "b", "c", "gone", "broken", "odd"] {
            graph.add(id, "inbox", hours_back(1), Some(10));
            wanted.push((id.to_string(), Some(10)));
        }
        graph.messages.remove("gone");
        graph
            .inner
            .insert("a".into(), vec![Inner::Throttled(Some(2))]);
        graph.inner.insert(
            "b".into(),
            vec![Inner::Throttled(Some(1)), Inner::Throttled(Some(1))],
        );
        graph
            .inner
            .insert("broken".into(), vec![Inner::Failed("HTTP 500".into())]);
        graph.inner.insert("odd".into(), vec![Inner::Undecoded]);

        let read = read_sources(&mut graph, &wanted, dir.path()).unwrap();
        assert_eq!(ids(&read.fetched), vec!["a", "c", "odd"]);
        assert_eq!(read.gone, vec!["gone"], "gone apart from the failed");
        assert_eq!(
            graph.pauses,
            vec![Duration::from_secs(2)],
            "the longest, once"
        );
        assert_eq!(graph.batches.len(), 2);
        assert_eq!(graph.batches[1], vec!["a", "b"], "the throttled, again");
        assert_eq!(graph.alone, vec!["odd"], "a body not base64 read alone");
    }

    #[test]
    fn an_expired_token_fails_the_read() {
        let dir = tempfile::tempdir().unwrap();
        let mut graph = FakeMail::default();
        graph.add("a", "inbox", hours_back(1), Some(10));
        graph.expired = true;

        let read = read_sources(&mut graph, &[("a".into(), Some(10))], Path::new(dir.path()));
        assert_eq!(read.unwrap_err().status, Some(401));
    }
}
