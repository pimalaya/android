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

        let skip = band.skip as usize;
        let take = (top as usize).min(self.page);
        let ids: Vec<String> = matching
            .iter()
            .skip(skip)
            .take(take)
            .map(|(id, _)| (*id).clone())
            .collect();
        self.listed += ids.len();
        Ok(MessagesPage {
            more: matching.len() > skip + take,
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
        skip: 0,
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
        // NOTE: six messages of one second across the first page's end.
        let hours = match index {
            2..8 => 2,
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
    };
    assert!(list_folder(&mut graph, &delta).unwrap().cursor_rejected);

    let round = MailRequest {
        listing: Listing::Round {
            cursor: Some(filtered.into()),
            band: false,
        },
        scope: Scope::default(),
        covered: false,
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
