//! # Graph mail tests
//!
//! The Graph listing over a fake Graph folder, driven by io-pimdir's own
//! sync engine and store: first chunk, delta link, widenings, deltas,
//! interruptions.
//!
//! The fake answers as Graph does where it matters: `/messages` filtered
//! and ordered on `sentDateTime`, a delta's first pass naming the folder as
//! it began (one gone since skipped), its link then reporting every change
//! whatever the date, and messages read by id in a `$batch`.

use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};

use io_msgraph::v1::{
    field::MsgraphField,
    rest::{
        batch::{MsgraphBatchRequest, MsgraphBatchResponse, MsgraphBatchResponses},
        users::{
            contacts::{MsgraphSingleValueExtendedProperty, delta::MsgraphRemoved},
            messages::{
                MsgraphEmailAddress, MsgraphFlagStatus, MsgraphFollowupFlag, MsgraphMessage,
                MsgraphRecipient,
                delta::{MsgraphMessageDelta, MsgraphMessagesDeltaResponse},
                list::MsgraphMessagesListResponse,
            },
        },
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
use serde_json::{Value, json};
use url::Url;

use crate::{
    client::{
        Client,
        fake::{BoxedServer, FakeRequest, FakeResponse, FakeServer},
        msgraph::{Msgraph, relative},
    },
    error::BridgeError,
    mail::{
        Mailbox,
        listing::{Floor, Listing, MailRequest, Named, Scope},
        msgraph::{
            Band, DELTA_SELECT, MESSAGE_EXPAND, MESSAGE_SELECT, graph_summary, message_url,
            permanent_delete_url,
        },
        session::MailListing,
    },
    offline::wire::tests::enumerated,
};

const SOURCE: &str = "graph";
const INBOX: &str = "Inbox";

/// The id of the inbox's folder.
const FOLDER: &str = "inbox";

/// The folder's message delta, which its links extend.
const DELTA: &str = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta";

/// The folder's messages, which a next link pages.
const MESSAGES: &str = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages";

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
    /// Requests listing the folder's ids alone, a narrowed round's
    /// enumeration (SYNC §4).
    id_lists: usize,
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

/// The `sentDateTime` band a `/messages` `$filter` names.
fn band(filter: Option<&str>) -> Band {
    let mut band = Band::default();
    for clause in filter.into_iter().flat_map(|filter| filter.split(" and ")) {
        let clause: Vec<&str> = clause.split(' ').collect();
        let [property, operator, date] = clause[..] else {
            panic!("unexpected filter {filter:?}");
        };
        assert_eq!(property, "sentDateTime");
        match operator {
            "ge" => band.since = Some(date.into()),
            "lt" => band.until = Some(date.into()),
            "le" => {
                band.until = Some(date.into());
                band.inclusive = true;
            }
            other => panic!("unexpected operator {other}"),
        }
    }
    band
}

/// A Graph error response (Graph v1.0 error envelope).
fn error(status: u16, code: &str) -> FakeResponse {
    let body = json!({ "error": { "code": code, "message": code } });
    FakeResponse::json(status, &body)
}

impl FakeGraph {
    /// Runs `verb` over a Graph API this folder is in.
    fn serve<T>(&mut self, verb: impl FnOnce(&mut Msgraph<'_, BoxedServer<'_>>) -> T) -> T {
        let mut client = Client::new(FakeServer::boxed(|request| self.answer(request)));
        verb(&mut Msgraph {
            client: &mut client,
            token: "token",
        })
    }

    /// Answers one Graph request: a `/messages` page, a delta page, or a
    /// `$batch` of messages read by id.
    fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
        assert_eq!(request.header("Authorization"), Some("Bearer token"));
        match (request.method.as_str(), request.path.as_str()) {
            ("GET", "/v1.0/me/mailFolders/inbox/messages") => self.messages(request),
            ("GET", "/v1.0/me/mailFolders/inbox/messages/delta") => self.delta(request),
            ("POST", "/v1.0/$batch") => self.batch(request),
            other => panic!("unexpected request {other:?}"),
        }
    }

    /// One page of the band the `$filter` names, newest first, `$top` at
    /// most, a next link when Graph holds more.
    fn messages(&mut self, request: &FakeRequest) -> FakeResponse {
        if request.param("$select") == Some("id") {
            return self.ids(request);
        }
        assert_eq!(request.param("$select"), Some(MESSAGE_SELECT));
        assert_eq!(request.param("$expand"), Some(MESSAGE_EXPAND));
        assert_eq!(request.param("$orderby"), Some("sentDateTime desc"));
        let band = band(request.param("$filter"));
        let top: usize = request.param("$top").unwrap().parse().unwrap();

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

        let take = top.min(self.page);
        let ids: Vec<String> = matching
            .iter()
            .take(take)
            .map(|(id, _)| (*id).clone())
            .collect();
        self.listed += ids.len();
        let page = MsgraphMessagesListResponse {
            value: ids.iter().map(|id| self.message(id)).collect(),
            next_link: (matching.len() > take).then(|| format!("{MESSAGES}?$skip={take}")),
            ..Default::default()
        };
        FakeResponse::json(200, &page)
    }

    /// One page of the folder's ids alone, in the fake's order, from
    /// `$skip`: what a narrowed round enumerates.
    fn ids(&mut self, request: &FakeRequest) -> FakeResponse {
        assert_eq!(request.param("$filter"), None, "no date filter");
        self.id_lists += 1;
        let top: usize = request.param("$top").unwrap().parse().unwrap();
        let skip: usize = request
            .param("$skip")
            .map_or(0, |skip| skip.parse().unwrap());
        let take = top.min(self.page);
        let ids: Vec<&String> = self.folder.keys().skip(skip).take(take).collect();
        let more = skip + ids.len() < self.folder.len();
        let page = json!({
            "value": ids.iter().map(|id| json!({ "id": id })).collect::<Vec<_>>(),
            "@odata.nextLink": more.then(|| format!("{MESSAGES}?$skip={}", skip + take)),
        });
        FakeResponse::json(200, &page)
    }

    /// One delta page: a first pass begun, a page of one by its next link
    /// (`$skiptoken` `pass-<pass>-<mark>-<offset>`), or what changed since
    /// a delta link's mark (`$deltatoken`).
    fn delta(&mut self, request: &FakeRequest) -> FakeResponse {
        assert_eq!(request.header("Prefer"), Some("odata.maxpagesize=1000"));
        self.delta_calls += 1;
        if self.fail_delta_call == Some(self.delta_calls) {
            return FakeResponse::lost();
        }

        let (pass, mark, offset) = match (request.param("$skiptoken"), request.param("$deltatoken"))
        {
            (None, None) => {
                assert_eq!(request.param("$select"), Some(DELTA_SELECT));
                self.delta_starts += 1;
                let mut ids: Vec<String> = self.folder.keys().cloned().collect();
                ids.sort_by(|a, b| self.folder[b].date.cmp(&self.folder[a].date));
                self.passes.push(ids);
                (self.passes.len() - 1, self.log.len(), 0)
            }
            (None, Some(mark)) => {
                if self.expired {
                    return error(410, "SyncStateNotFound");
                }
                let mark: usize = mark.parse().unwrap();
                let touched: BTreeSet<String> = self.log[mark..].iter().cloned().collect();
                let value: Vec<MsgraphMessageDelta> =
                    touched.iter().map(|id| self.row(id)).collect();
                self.delta_rows += value.len();
                let page = MsgraphMessagesDeltaResponse {
                    value,
                    next_link: None,
                    delta_link: Some(format!("{DELTA}?$deltatoken={}", self.log.len())),
                    count: None,
                };
                return FakeResponse::json(200, &page);
            }
            (Some(token), None) => {
                let parts: Vec<usize> = token["pass-".len()..]
                    .split('-')
                    .map(|part| part.parse().unwrap())
                    .collect();
                (parts[0], parts[1], parts[2])
            }
            (Some(_), Some(_)) => panic!("a next link and a delta link"),
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
            true => (
                Some(format!("{DELTA}?$skiptoken=pass-{pass}-{mark}-{end}")),
                None,
            ),
            false => (None, Some(format!("{DELTA}?$deltatoken={mark}"))),
        };
        let page = MsgraphMessagesDeltaResponse {
            value,
            next_link,
            delta_link,
            count: None,
        };
        FakeResponse::json(200, &page)
    }

    /// One `$batch` of messages read by id, with the summary `$select`, a
    /// message gone answering 404.
    fn batch(&mut self, request: &FakeRequest) -> FakeResponse {
        let requests: Vec<MsgraphBatchRequest> =
            serde_json::from_value(request.json::<Value>()["requests"].take()).unwrap();
        let responses = requests
            .iter()
            .map(|inner| {
                assert_eq!(inner.method, "GET");
                let url = Url::parse(&format!("https://graph.microsoft.com/v1.0{}", inner.url));
                let url = url.unwrap();
                let query: BTreeMap<String, String> = url.query_pairs().into_owned().collect();
                assert_eq!(query["$select"], MESSAGE_SELECT);
                assert_eq!(query["$expand"], MESSAGE_EXPAND);
                let id = url.path().strip_prefix("/v1.0/me/messages/").unwrap();
                let (status, body) = match self.folder.contains_key(id) {
                    true => {
                        self.reads += 1;
                        (200, serde_json::to_value(self.message(id)).unwrap())
                    }
                    false => (404, json!({ "error": { "code": "ErrorItemNotFound" } })),
                };
                MsgraphBatchResponse {
                    id: inner.id.clone(),
                    status,
                    body: Some(body),
                    ..Default::default()
                }
            })
            .collect();
        FakeResponse::json(200, &MsgraphBatchResponses { responses })
    }
}

/// The engine's remote over the fake: the request as Java forwards it,
/// `covered` read off the store's coverage, the members a delta lists by
/// id and the store binds not named by id, as `MailEngine` does.
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
            PimdirListing::Head(head) => panic!("no head asked: {head:?}"),
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
        let mut page = self
            .graph
            .serve(|graph| graph.list_page(FOLDER, &request))
            .map_err(|err| err.message)?;

        let bound = handles(&self.dir);
        let unbound: Vec<String> = page
            .items
            .iter()
            .filter(|item| item.summary.is_none() && !bound.contains(&item.handle))
            .map(|item| item.handle.clone())
            .collect();
        let mut read: BTreeMap<String, Named> = self
            .graph
            .serve(|graph| graph.name_messages(&unbound))
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

        Ok(enumerated(&serde_json::to_string(&page).unwrap()))
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

/// The message ids the store holds in the inbox: the handles it binds.
fn handles(dir: &Path) -> BTreeSet<String> {
    let reader = PimdirReader::open(dir).unwrap();
    reader
        .list_items(INBOX, None, 100_000)
        .unwrap()
        .into_iter()
        .filter_map(|item| handle_of(&reader, item.link_id.as_str()))
        .collect()
}

/// The handle the source binds an inbox item by.
fn handle_of(reader: &PimdirReader, link_id: &str) -> Option<String> {
    reader
        .item_bindings(INBOX, link_id)
        .unwrap()
        .into_iter()
        .find(|(source, _)| source.as_str() == SOURCE)
        .map(|(_, binding)| binding.handle.as_str().to_string())
}

/// Whether the store holds the message as read.
fn is_read(dir: &Path, id: &str) -> bool {
    let reader = PimdirReader::open(dir).unwrap();
    reader
        .list_items(INBOX, None, 100_000)
        .unwrap()
        .into_iter()
        .find(|item| handle_of(&reader, item.link_id.as_str()).as_deref() == Some(id))
        .is_some_and(|item| item.flags.contains("\\Seen"))
}

/// The size the store's summary holds for the message.
fn size(dir: &Path, id: &str) -> Option<u64> {
    let reader = PimdirReader::open(dir).unwrap();
    let item = reader
        .list_summaries(INBOX, None, 100_000)
        .unwrap()
        .into_iter()
        .find(|item| handle_of(&reader, item.link_id.as_str()).as_deref() == Some(id))
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
        let page = graph
            .serve(|graph| graph.list_page(FOLDER, &request))
            .unwrap();
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
    assert!(
        graph
            .serve(|graph| graph.list_page(FOLDER, &delta))
            .unwrap()
            .cursor_rejected
    );

    let round = MailRequest {
        listing: Listing::Round {
            cursor: Some(filtered.into()),
            band: false,
        },
        scope: Scope::default(),
        covered: false,
        received: None,
    };
    assert!(
        graph
            .serve(|graph| graph.list_page(FOLDER, &round))
            .unwrap()
            .cursor_rejected
    );
    assert_eq!(
        graph.delta_calls + graph.listed,
        0,
        "nothing asked of Graph"
    );
}

/// A band listed by `sentDateTime` never lists an undated message, so its
/// round infers no deletion of it (io-pimdir ff28408, pimdir SYNC §5); a
/// delta or a round over a whole scope still answers for it.
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

/// A session opened to widen a mailbox, or by a fill step, reads the folder
/// roster on its first lookup, knowing no folder id before.
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
        let page = graph
            .serve(|graph| graph.list_page(FOLDER, &request))
            .unwrap();
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

#[test]
fn a_permanent_delete_posts_the_action() {
    assert_eq!(
        permanent_delete_url("AAMk-1=").unwrap().as_str(),
        "https://graph.microsoft.com/v1.0/me/messages/AAMk-1=/permanentDelete"
    );
}

#[test]
fn a_read_by_id_expands_the_size_percent_encoded() {
    let url = message_url("AAMk-1=").unwrap();
    let expand = url
        .query_pairs()
        .find(|(key, _)| key == "$expand")
        .map(|(_, value)| value.into_owned());
    assert_eq!(
        expand.as_deref(),
        Some("singleValueExtendedProperties($filter=id eq 'Integer 0x0E08')")
    );

    // NOTE: a batch names it relative to the API version, no raw
    // space left in it.
    let batched = relative(&url);
    assert!(batched.starts_with("/me/messages/AAMk-1=?%24select="));
    assert!(!batched.contains(' '), "got: {batched}");
}

#[test]
fn the_size_is_the_expanded_mapi_size() {
    let sized = |value: &str| MsgraphMessage {
        id: "m1".into(),
        single_value_extended_properties: vec![MsgraphSingleValueExtendedProperty {
            id: "Integer 0xe08".into(),
            value: value.into(),
        }],
        ..Default::default()
    };
    assert_eq!(graph_summary(&sized("48213")).size, Some(48213));
    assert_eq!(graph_summary(&sized("large")).size, None, "unparsable");
    assert_eq!(graph_summary(&sized("-1")).size, None, "negative");

    let bare = MsgraphMessage {
        id: "m2".into(),
        ..Default::default()
    };
    assert_eq!(graph_summary(&bare).size, None, "not expanded");
}

#[test]
fn the_date_is_the_sent_date_not_the_reception() {
    let message = MsgraphMessage {
        id: "m1".into(),
        sent_date_time: Some("2026-10-07T08:00:00Z".into()),
        received_date_time: Some("2026-10-07T08:05:00Z".into()),
        ..Default::default()
    };
    assert_eq!(
        graph_summary(&message).date.as_deref(),
        Some("2026-10-07T08:00:00Z")
    );

    // NOTE: a message with no `Date` has none, never the reception time
    // (Annex A.1: `NULL`).
    let undated = MsgraphMessage {
        id: "m2".into(),
        received_date_time: Some("2026-10-07T08:05:00Z".into()),
        ..Default::default()
    };
    assert_eq!(graph_summary(&undated).date, None);
}

#[test]
fn the_summary_reads_the_select() {
    let recipient = |name: &str, address: &str| MsgraphRecipient {
        email_address: MsgraphEmailAddress {
            name: Some(name.into()),
            address: Some(address.into()),
        },
    };
    let message = MsgraphMessage {
        id: "m1".into(),
        subject: Some("Hello".into()),
        from: Some(recipient("Ana", "Ana@Example.org")),
        to_recipients: MsgraphField::Set(vec![recipient("Bo", "bo@example.org")]),
        internet_message_id: Some("<m1@example.org>".into()),
        has_attachments: Some(true),
        ..Default::default()
    };

    let summary = graph_summary(&message);
    assert_eq!(summary.message_id.as_deref(), Some("m1@example.org"));
    assert_eq!(summary.sender.as_deref(), Some("ana@example.org"));
    assert_eq!(summary.sender_name.as_deref(), Some("Ana"));
    assert_eq!(summary.to.len(), 1);
    assert_eq!(summary.attachment, Some(true));
}

/// pimdir's mail fixtures whose raw reading a decoded one may miss
/// (vectors/summaries.json).
const FIXTURES: [&str; 10] = [
    "fixtures/mail-id-comment.eml",
    "fixtures/mail-bracketless-id.eml",
    "fixtures/mail-repeated-fields.eml",
    "fixtures/mail-folded-subject.eml",
    "fixtures/mail-military-zone.eml",
    "fixtures/mail-charset-windows-1252.eml",
    "fixtures/mail-charset-iso-8859-2.eml",
    "fixtures/mail-charset-iso-2022-jp.eml",
    "fixtures/mail-charset-gbk.eml",
    "fixtures/mail-charset-replacement.eml",
];

/// One fixture of [`FIXTURES`]: its header fields as Graph lists them,
/// and the case vectors/summaries.json states for it.
struct Vector {
    fixture: &'static str,
    fields: Vec<(String, String)>,
    case: Value,
}

impl Vector {
    /// Every fixture of [`FIXTURES`], from io-pimdir's vendored spec.
    fn all() -> Vec<Self> {
        let spec = crate::offline::tests::io_pimdir_spec_dir().join("vectors");
        let text = fs::read_to_string(spec.join("summaries.json")).unwrap();
        let vectors: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(vectors["v"], 3, "summaries.json at another version");

        let cases = vectors["cases"].as_array().unwrap();
        FIXTURES
            .into_iter()
            .map(|fixture| {
                let case = cases
                    .iter()
                    .find(|case| case["fixture"] == fixture)
                    .unwrap_or_else(|| panic!("no case for {fixture}"));
                let raw = fs::read(spec.join(fixture)).unwrap();
                Self {
                    fixture,
                    fields: header_fields(&raw),
                    case: case.clone(),
                }
            })
            .collect()
    }

    /// The first value the fixture states for the field `name`.
    fn field(&self, name: &str) -> Option<&str> {
        self.fields
            .iter()
            .find(|(field, _)| field.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }

    /// What `named` misses of the case, the size and the mark aside (Graph's
    /// own on a row); none when it names the fixture as the case states.
    fn misses(&self, named: &Named) -> Vec<String> {
        let named = serde_json::to_value(named).unwrap();
        let mail = &named["summary"]["mail"];
        let stated = &self.case["summary"];
        let mut misses = Vec::new();
        let mut check = |key: &str, got: &Value, want: &Value| {
            if got != want {
                misses.push(format!("{}: {key} {got}, stated {want}", self.fixture));
            }
        };

        check("link_id", &named["linkId"], &self.case["link_id"]);
        check("sort_key", &named["sortKey"], &self.case["sort_key"]);
        for key in [
            "message_id",
            "in_reply_to",
            "subject",
            "sender",
            "sender_name",
            "date",
        ] {
            check(key, &mail[key], &stated[key]);
        }
        let named_name = |address: &Value| {
            let mut address = address.clone();
            if address.get("name").is_none() {
                address["name"] = Value::Null;
            }
            address
        };
        let addresses = |rows: &Value| -> Value {
            rows.as_array()
                .map(|rows| rows.iter().map(named_name).collect())
                .unwrap_or_default()
        };
        check(
            "addresses",
            &addresses(&mail["addresses"]),
            &addresses(&self.case["addresses"]),
        );
        misses
    }
}

/// Each header field of a message's header block as name and value in
/// document order, as a source listing header fields in JSON serves them:
/// the value trimmed at both ends, the line breaks of a folded one kept.
fn header_fields(raw: &[u8]) -> Vec<(String, String)> {
    let end = raw
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .expect("a header block");
    let header = std::str::from_utf8(&raw[..end + 2]).expect("an ASCII header block");

    let mut fields: Vec<(String, String)> = Vec::new();
    for line in header.split_inclusive("\r\n") {
        match fields.last_mut() {
            Some((_, value)) if line.starts_with([' ', '\t']) => value.push_str(line),
            _ => {
                if let Some((name, value)) = line.split_once(':') {
                    fields.push((name.into(), value.into()));
                }
            }
        }
    }
    for (_, value) in &mut fields {
        *value = value.trim().into();
    }
    fields
}

/// A row as a read selecting `internetMessageHeaders` answers it: `fields`
/// in their order, repeated names kept, beside the members Graph decodes,
/// which here state another message.
fn headed_row(id: &str, fields: &[(String, String)]) -> Value {
    let headers: Vec<Value> = fields
        .iter()
        .map(|(name, value)| json!({ "name": name, "value": value }))
        .collect();
    json!({
        "id": id,
        "subject": "As Graph decodes it",
        "from": { "emailAddress": { "name": "Decoded", "address": "decoded@example.net" } },
        "toRecipients": [],
        "sentDateTime": "2001-01-01T00:00:00Z",
        "internetMessageId": "<decoded@example.net>",
        "internetMessageHeaders": headers,
    })
}

/// A row as the listing names it.
fn named_row(row: Value) -> Named {
    let message: MsgraphMessage = serde_json::from_value(row).unwrap();
    Named::new(message.id.clone(), Vec::new(), graph_summary(&message))
}

/// A row listing its header fields is named off them as its body would
/// name it (pimdir STORAGE Annex A.1), each from its first entry, whatever
/// Graph decodes beside them; Graph stating no mark, the top-level
/// `Content-Type` makes it, as on the `Meta` tier of a body.
#[test]
fn a_row_listing_its_header_fields_is_named_as_the_vectors_state() {
    let mut misses = Vec::new();
    for vector in Vector::all() {
        let named = named_row(headed_row("m1", &vector.fields));
        misses.extend(vector.misses(&named));

        let mail = &serde_json::to_value(&named).unwrap()["summary"]["mail"];
        let mark = vector.case["meta_attachment"] == 1;
        if mail["attachment"] != mark {
            let got = &mail["attachment"];
            misses.push(format!("{}: attachment {got}", vector.fixture));
        }
    }
    assert!(misses.is_empty(), "{}", misses.join("\n"));
}

/// On a row listing its header fields the size and the attachment mark
/// stay Graph's own, the top-level `Content-Type` making the mark only
/// where Graph states none.
#[test]
fn a_row_listing_its_header_fields_keeps_the_size_and_the_mark_graph_states() {
    let fields = [
        ("In-Reply-To".into(), "<p-1@example.org>".into()),
        ("Content-Type".into(), "multipart/mixed; boundary=b".into()),
    ];
    let row = |mark: Option<bool>| {
        let mut row = headed_row("m1", &fields);
        row["singleValueExtendedProperties"] = json!([{ "id": "Integer 0xe08", "value": "48213" }]);
        if let Some(mark) = mark {
            row["hasAttachments"] = json!(mark);
        }
        graph_summary(&serde_json::from_value(row).unwrap())
    };

    let stated = row(Some(false));
    assert_eq!(stated.in_reply_to, ["p-1@example.org"]);
    assert_eq!(stated.size, Some(48213));
    assert_eq!(stated.attachment, Some(false), "Graph's own mark");
    assert_eq!(row(None).attachment, Some(true), "the Content-Type's");
}

/// A row listing no header field, the member left out, answered empty or
/// `null`, keeps the reading of what Graph decodes, its
/// `internetMessageId` read as a `Message-ID` field is (Annex A.1).
#[test]
fn a_row_listing_no_header_field_is_keyed_by_its_internet_message_id() {
    let mut misses = Vec::new();
    let mut run = 0;
    for vector in Vector::all() {
        let Some(id) = vector.field("Message-ID") else {
            continue;
        };
        for headers in [None, Some(json!([])), Some(Value::Null)] {
            let mut row = headed_row("m1", &[]);
            row["internetMessageId"] = json!(id);
            match headers {
                Some(headers) => row["internetMessageHeaders"] = headers,
                None => {
                    row.as_object_mut()
                        .unwrap()
                        .remove("internetMessageHeaders");
                }
            }
            run += 1;

            let named = serde_json::to_value(named_row(row)).unwrap();
            let mail = &named["summary"]["mail"];
            for (key, got) in [
                ("link_id", &named["linkId"]),
                ("message_id", &mail["message_id"]),
            ] {
                let want = &vector.case["summary"]["message_id"];
                if got != want {
                    misses.push(format!("{}: {key} {got}, stated {want}", vector.fixture));
                }
            }
            assert_eq!(mail["subject"], "As Graph decodes it", "{}", vector.fixture);
            assert_eq!(mail["sender"], "decoded@example.net", "{}", vector.fixture);
            assert_eq!(mail["date"], "2001-01-01T00:00:00Z", "{}", vector.fixture);
        }
    }
    assert_eq!(run, 21, "seven fixtures state a Message-ID");
    assert!(misses.is_empty(), "{}", misses.join("\n"));
}

/// Every read naming a member selects its header fields (pimdir STORAGE
/// Annex A.1): a band's `/messages` page, a `$batch` of reads by id and a
/// read by id alone, each answered as Graph does, the header fields only
/// to a `$select` naming them, and each member named off them.
#[test]
fn every_read_naming_a_member_selects_its_header_fields() {
    let vectors = Vector::all();
    let vector = &vectors[0];
    let row = |id: &str, select: Option<&str>| {
        let mut row = headed_row(id, &vector.fields);
        let selected = select
            .into_iter()
            .flat_map(|select| select.split(','))
            .any(|name| name == "internetMessageHeaders");
        if !selected {
            row.as_object_mut()
                .unwrap()
                .remove("internetMessageHeaders");
        }
        row
    };
    let mut selects = Vec::new();
    let mut answer = |request: &FakeRequest| match (request.method.as_str(), request.path.as_str())
    {
        ("GET", "/v1.0/me/mailFolders/inbox/messages")
            if request.param("$select") == Some("id") =>
        {
            FakeResponse::json(200, &json!({ "value": [{ "id": "m1" }] }))
        }
        ("GET", "/v1.0/me/mailFolders/inbox/messages") => {
            selects.push(("band", request.param("$select").map(String::from)));
            let rows = vec![row("m1", request.param("$select"))];
            FakeResponse::json(200, &json!({ "value": rows }))
        }
        ("POST", "/v1.0/$batch") => {
            let requests: Vec<MsgraphBatchRequest> =
                serde_json::from_value(request.json::<Value>()["requests"].take()).unwrap();
            let responses: Vec<MsgraphBatchResponse> = requests
                .iter()
                .map(|inner| {
                    let url = format!("https://graph.microsoft.com/v1.0{}", inner.url);
                    let url = Url::parse(&url).unwrap();
                    let select = url
                        .query_pairs()
                        .find(|(key, _)| key == "$select")
                        .map(|(_, value)| value.into_owned());
                    selects.push(("batch", select.clone()));
                    let id = url.path().strip_prefix("/v1.0/me/messages/").unwrap();
                    // NOTE: the second fails in the batch, read alone.
                    let (status, body) = match id {
                        "m1" => (200, row(id, select.as_deref())),
                        _ => (503, json!({ "error": { "code": "ServiceUnavailable" } })),
                    };
                    MsgraphBatchResponse {
                        id: inner.id.clone(),
                        status,
                        body: Some(body),
                        ..Default::default()
                    }
                })
                .collect();
            FakeResponse::json(200, &MsgraphBatchResponses { responses })
        }
        ("GET", "/v1.0/me/messages/m2") => {
            selects.push(("alone", request.param("$select").map(String::from)));
            FakeResponse::json(200, &row("m2", request.param("$select")))
        }
        other => panic!("unexpected request {other:?}"),
    };

    let request = MailRequest {
        listing: Listing::Round {
            cursor: None,
            band: true,
        },
        scope: Scope::default(),
        covered: false,
        received: None,
    };
    let (page, read) = {
        let mut client = Client::new(FakeServer::boxed(&mut answer));
        let mut graph = Msgraph {
            client: &mut client,
            token: "token",
        };
        let page = graph.list_page(FOLDER, &request).unwrap();
        let read = graph.name_messages(&["m1".into(), "m2".into()]).unwrap();
        (page, read)
    };
    drop(answer);

    let reads: Vec<&str> = selects.iter().map(|(read, _)| *read).collect();
    assert_eq!(reads, ["band", "batch", "batch", "alone"]);
    let named: Vec<&Named> = page.items.iter().chain(&read).collect();
    assert_eq!(named.len(), 3);
    let misses: Vec<String> = named
        .iter()
        .flat_map(|named| vector.misses(named))
        .collect();
    assert!(misses.is_empty(), "{}\n{selects:?}", misses.join("\n"));
}

/// The step and the bodies' batches over a fake Graph account.
///
/// The fake answers the account's or a folder's `/messages` on
/// `sentDateTime`, `$batch` calls of `$value` or size GETs as scripted, a
/// body read alone by a plain GET; the store behind answers `held`.
mod step {
    use std::{
        collections::{BTreeMap, HashMap, HashSet},
        fs,
        path::Path,
        time::Duration,
    };

    use io_msgraph::v1::rest::{
        batch::{MsgraphBatchRequest, MsgraphBatchResponse, MsgraphBatchResponses},
        users::{
            contacts::MsgraphSingleValueExtendedProperty,
            messages::{MsgraphMessage, list::MsgraphMessagesListResponse},
        },
    };
    use serde_json::{Value, json};
    use url::Url;

    use crate::{
        client::{
            Client,
            fake::{BoxedServer, FakeRequest, FakeResponse, FakeServer, holding},
            msgraph::Msgraph,
        },
        mail::{
            base64,
            msgraph::{
                ALONE_BYTES, Band, GraphFetched, Inner, MESSAGE_EXPAND, STEP_SELECT, StepAsk,
                tests::{band, error, hours_back},
            },
        },
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

    impl FakeMail {
        /// Runs `verb` over a Graph API this account is in, the store behind
        /// the client holding [`Self::held`], the waits it took kept in
        /// [`Self::pauses`].
        fn serve<T>(&mut self, verb: impl FnOnce(&mut Msgraph<'_, BoxedServer<'_>>) -> T) -> T {
            let held = self.held.clone();
            let mut server = FakeServer::boxed(|request| self.answer(request));
            server.store = Some(holding(held));
            let mut client = Client::new(server);
            let out = verb(&mut Msgraph {
                client: &mut client,
                token: "token",
            });
            let slept = std::mem::take(&mut client.host.slept);
            drop(client);
            self.pauses.extend(slept);
            out
        }

        /// Answers one Graph request: a listing, a `$batch`, or a body alone.
        fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
            assert_eq!(request.header("Authorization"), Some("Bearer token"));
            let segments: Vec<&str> = request.path.split('/').skip(1).collect();
            match (request.method.as_str(), segments.as_slice()) {
                ("GET", ["v1.0", "me", "messages"]) => self.dated(None, request),
                ("GET", ["v1.0", "me", "mailFolders", folder, "messages"]) => {
                    self.dated(Some(folder), request)
                }
                ("GET", ["v1.0", "me", "messages", id, "$value"]) => self.source(id),
                ("POST", ["v1.0", "$batch"]) => self.batch(request),
                other => panic!("unexpected request {other:?}"),
            }
        }

        /// One page of the account's messages, or of one folder's, newest
        /// `Date` first, within the band the `$filter` names, `$top` at most.
        fn dated(&mut self, folder: Option<&str>, request: &FakeRequest) -> FakeResponse {
            assert_eq!(request.param("$select"), Some(STEP_SELECT));
            assert_eq!(request.param("$expand"), Some(MESSAGE_EXPAND));
            assert_eq!(request.param("$orderby"), Some("sentDateTime desc"));
            let band = band(request.param("$filter"));
            let top: usize = request.param("$top").unwrap().parse().unwrap();
            self.listings.push((folder.map(String::from), band.clone()));
            if folder.is_none() && self.inefficient {
                let body = json!({ "error": {
                    "code": "InefficientFilter",
                    "message": "The restriction or sort order is too complex",
                } });
                return FakeResponse::json(400, &body);
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
            let value = matching
                .into_iter()
                .take(top)
                .map(|(id, message)| MsgraphMessage {
                    id: id.clone(),
                    sent_date_time: Some(message.date.clone()),
                    received_date_time: Some(message.received.clone()),
                    parent_folder_id: Some(message.folder.clone()),
                    single_value_extended_properties: sized(message.size),
                    ..Default::default()
                })
                .collect();
            let page = MsgraphMessagesListResponse {
                value,
                next_link: None,
                ..Default::default()
            };
            FakeResponse::json(200, &page)
        }

        /// One `$batch` of GETs: of `$value` bodies, each answered as
        /// scripted or by its body, or of sizes.
        fn batch(&mut self, request: &FakeRequest) -> FakeResponse {
            let requests: Vec<MsgraphBatchRequest> =
                serde_json::from_value(request.json::<Value>()["requests"].take()).unwrap();
            let gets: Vec<(String, Url)> = requests
                .into_iter()
                .map(|inner| {
                    assert_eq!(inner.method, "GET");
                    let url = format!("https://graph.microsoft.com/v1.0{}", inner.url);
                    (inner.id, Url::parse(&url).unwrap())
                })
                .collect();
            let bodies = gets.iter().all(|(_, url)| url.path().ends_with("/$value"));
            let responses = match bodies {
                true => {
                    if self.expired {
                        return error(401, "InvalidAuthenticationToken");
                    }
                    let ids: Vec<String> = gets.iter().map(|(_, url)| id(url)).collect();
                    self.batches.push(ids);
                    gets.iter()
                        .map(|(at, url)| self.body_item(at, &id(url)))
                        .collect()
                }
                false => gets
                    .iter()
                    .map(|(at, url)| self.size_item(at, url))
                    .collect(),
            };
            FakeResponse::json(200, &MsgraphBatchResponses { responses })
        }

        /// One `$value` GET of a `$batch`, as scripted, else the message's
        /// body as base64 or 404 when gone.
        fn body_item(&mut self, at: &str, id: &str) -> MsgraphBatchResponse {
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
            let (status, headers, body) = match answer {
                Inner::Body(bytes) => (200, BTreeMap::new(), json!(base64(&bytes))),
                Inner::Undecoded => (200, BTreeMap::new(), json!({ "id": id })),
                Inner::Gone => (
                    404,
                    BTreeMap::new(),
                    json!({ "error": { "code": "ErrorItemNotFound" } }),
                ),
                Inner::Throttled(after) => (
                    429,
                    after
                        .map(|after| ("Retry-After".to_string(), after.to_string()))
                        .into_iter()
                        .collect(),
                    json!({ "error": { "code": "TooManyRequests" } }),
                ),
                Inner::Failed(_) => (
                    500,
                    BTreeMap::new(),
                    json!({ "error": { "code": "InternalServerError" } }),
                ),
            };
            MsgraphBatchResponse {
                id: at.to_string(),
                status,
                headers,
                body: Some(body),
            }
        }

        /// One size GET of a `$batch`: the MAPI size Graph states, if any.
        fn size_item(&self, at: &str, url: &Url) -> MsgraphBatchResponse {
            let query: BTreeMap<String, String> = url.query_pairs().into_owned().collect();
            assert_eq!(query["$select"], "id");
            assert_eq!(query["$expand"], MESSAGE_EXPAND);
            let id = id(url);
            let (status, body) = match self.messages.get(&id) {
                Some(message) => {
                    let message = MsgraphMessage {
                        id,
                        single_value_extended_properties: sized(message.size),
                        ..Default::default()
                    };
                    (200, serde_json::to_value(message).unwrap())
                }
                None => (404, json!({ "error": { "code": "ErrorItemNotFound" } })),
            };
            MsgraphBatchResponse {
                id: at.to_string(),
                status,
                body: Some(body),
                ..Default::default()
            }
        }

        /// One body read alone, by a plain `$value` GET.
        fn source(&mut self, id: &str) -> FakeResponse {
            self.alone.push(id.into());
            match self.messages.contains_key(id) {
                true => FakeResponse {
                    content_type: "message/rfc822",
                    body: Self::body(id),
                    ..FakeResponse::empty(200)
                },
                false => error(404, "ErrorItemNotFound"),
            }
        }
    }

    /// The message id a GET of a `$batch` names.
    fn id(url: &Url) -> String {
        url.path()
            .strip_prefix("/v1.0/me/messages/")
            .unwrap()
            .trim_end_matches("/$value")
            .to_string()
    }

    /// The MAPI size the `$expand` reads, none when Graph states none.
    fn sized(size: Option<u64>) -> Vec<MsgraphSingleValueExtendedProperty> {
        size.map(|size| MsgraphSingleValueExtendedProperty {
            id: "Integer 0xe08".into(),
            value: size.to_string(),
        })
        .into_iter()
        .collect()
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

        let stepped = graph
            .serve(|graph| graph.list_step(&ask(&stepped_folders, None)))
            .unwrap();
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

        let first = graph
            .serve(|graph| graph.list_step(&ask(&inbox, None)))
            .unwrap();
        graph
            .held
            .extend(first.messages.iter().map(|message| message.id.clone()));
        let frontier = first.messages.last().unwrap().date.clone();

        let next = graph
            .serve(|graph| graph.list_step(&ask(&inbox, Some(&frontier))))
            .unwrap();
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

        let stepped = graph
            .serve(|graph| graph.list_step(&ask(&inbox, None)))
            .unwrap();
        assert_eq!(stepped.messages.len(), 54);
        assert!(!stepped.exhausted);
    }

    #[test]
    fn a_small_account_is_exhausted() {
        let mut graph = FakeMail::default();
        graph.fill("i", "inbox", 0, 30);
        let inbox = folders(&["inbox"]);

        let stepped = graph
            .serve(|graph| graph.list_step(&ask(&inbox, None)))
            .unwrap();
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

        let stepped = graph.serve(|graph| graph.list_step(&asked)).unwrap();
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

        let listed = graph
            .serve(|graph| graph.list_step(&ask(&both, None)))
            .unwrap();
        graph.inefficient = true;
        graph.listings.clear();
        let merged = graph
            .serve(|graph| graph.list_step(&ask(&both, None)))
            .unwrap();

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

        let stepped = graph.serve(|graph| graph.list_step(&asked)).unwrap();
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

        let fetched = graph
            .serve(|graph| graph.read_sources(&wanted, dir.path()))
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

        let read = graph
            .serve(|graph| graph.read_sources(&wanted, dir.path()))
            .unwrap();
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

        let read = graph
            .serve(|graph| graph.read_sources(&[("a".into(), Some(10))], Path::new(dir.path())));
        assert_eq!(read.unwrap_err().status, Some(401));
    }
}

/// The `sentDateTime` filter misses an undated message, so a band round's
/// last page hands the folder's every id, listed with no filter, 1,000 a
/// request (SYNC §4).
#[test]
fn a_band_rounds_last_page_hands_the_folders_every_id() {
    let mut graph = FakeGraph::new(0);
    graph.page = 4;
    for index in 0..9 {
        graph.add(&format!("t{index:02}"), Some(hours_back(index)));
    }
    graph.add("undated", None);

    let mut cursor: Option<String> = None;
    let mut pages = Vec::new();
    loop {
        let request = MailRequest {
            listing: Listing::Round {
                cursor: cursor.clone(),
                band: true,
            },
            scope: Scope {
                since: Some(hours_back(100)),
                until: None,
            },
            covered: false,
            received: None,
        };
        let page = graph
            .serve(|graph| graph.list_page(FOLDER, &request))
            .unwrap();
        assert!(page.narrowed);
        cursor = page.cursor.clone();
        pages.push(page);
        if cursor.is_none() {
            break;
        }
    }

    let (last, before) = pages.split_last().unwrap();
    assert!(before.iter().all(|page| page.enumerated.is_none()));
    let mut enumerated = last.enumerated.clone().expect("the folder's every id");
    enumerated.sort();
    assert_eq!(enumerated.len(), 10, "the undated one too: {enumerated:?}");
    assert!(enumerated.contains(&"undated".to_string()));
    assert_eq!(graph.id_lists, 3, "ten ids, four a page");
}

/// A copy whose answer was lost is run again: the target holding its
/// `internetMessageId`, it is not copied twice.
#[test]
fn a_copy_run_again_after_a_lost_answer_files_one_copy() {
    let mut archive: Vec<String> = Vec::new();
    let mut copies = 0;
    let mut answer = |request: &FakeRequest| match (request.method.as_str(), request.path.as_str())
    {
        ("GET", "/v1.0/me/messages/m1") => {
            assert_eq!(request.param("$select"), Some("internetMessageId"));
            FakeResponse::json(
                200,
                &json!({ "id": "m1", "internetMessageId": "<m1@example.org>" }),
            )
        }
        ("GET", "/v1.0/me/mailFolders/archive/messages") => {
            let filter = request.param("$filter").expect("a filter");
            let held: Vec<_> = archive
                .iter()
                .filter(|id| filter == format!("internetMessageId eq '{id}'"))
                .map(|_| json!({ "id": "c1" }))
                .collect();
            FakeResponse::json(200, &json!({ "value": held }))
        }
        ("POST", "/v1.0/me/messages/m1/copy") => {
            copies += 1;
            archive.push("<m1@example.org>".into());
            if copies == 1 {
                FakeResponse::lost()
            } else {
                FakeResponse::json(201, &json!({ "id": "c2" }))
            }
        }
        other => panic!("unexpected request {other:?}"),
    };

    {
        let mut client = Client::new(FakeServer::boxed(&mut answer));
        let mut graph = Msgraph {
            client: &mut client,
            token: "token",
        };
        assert!(
            graph.copy_message("m1", "archive").is_err(),
            "the answer lost"
        );
        graph.copy_message("m1", "archive").unwrap();
    }
    drop(answer);
    assert_eq!(copies, 1, "copied once");
}
