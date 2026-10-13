//! # JMAP step tests
//!
//! A JMAP step over a fake account, anchored, paged by reception, past a
//! stretch held whole or a failed body; blob reads; a run's changes shared.
//!
//! The fake answers the real [`JmapCalls`] as a server does where it
//! matters: `Email/query` anchored (RFC 8620 §5.5, `anchorNotFound`), its
//! `Email/get` in no particular order, blob downloads (RFC 8620 §6.2). The
//! store behind the client answers the step's `held`.

use std::{
    cmp::Reverse,
    collections::{BTreeMap, HashSet},
    fs,
    ops::Range,
    sync::atomic::{AtomicUsize, Ordering},
};

use io_jmap::rfc8620::session::JmapSession;
use jiff::{SignedDuration, Timestamp};
use secrecy::SecretString;
use serde_json::{Map, Value, from_value, json};
use tempfile::TempDir;
use url::Url;

use crate::{
    client::{
        Auth, Client,
        fake::{BoxedServer, FakeRequest, FakeResponse, FakeServer, holding, jmap_session},
        jmap::{Jmap, JmapCalls},
    },
    mail::jmap_step::*,
};

/// The `n`th newest message's date, an hour apart, received a minute
/// after it was written.
fn dates(n: usize) -> (String, String) {
    let base: Timestamp = "2026-10-10T12:00:00Z".parse().unwrap();
    let sent = base - SignedDuration::from_hours(n as i64);
    let received = sent + SignedDuration::from_mins(1);
    let format = |at: Timestamp| at.strftime("%Y-%m-%dT%H:%M:%SZ").to_string();
    (format(sent), format(received))
}

fn email(n: usize, mailboxes: &[&str]) -> JmapEmail {
    let (sent, received) = dates(n);
    let mailbox_ids: BTreeMap<&str, bool> = mailboxes.iter().map(|id| (*id, true)).collect();
    from_value(json!({
        "id": format!("e{n:03}"),
        "blobId": format!("b{n:03}"),
        "mailboxIds": mailbox_ids,
        "keywords": {"$seen": true},
        "subject": format!("Message {n}"),
        "from": [{"email": "a@example.org"}],
        "sentAt": sent,
        "receivedAt": received,
        "size": 100,
    }))
    .unwrap()
}

/// A JMAP account, newest first, and what was asked of it.
struct FakeAccount {
    emails: Vec<JmapEmail>,
    /// Blobs whose download fails, with the status it fails with.
    failing: BTreeMap<String, u16>,
    queries: Vec<StepQuery>,
    downloads: Vec<String>,
    gets: Vec<Vec<String>>,
    /// What the store holds, as a step asks it.
    held: HashSet<String>,
}

fn account(size: usize) -> FakeAccount {
    FakeAccount {
        emails: (0..size).map(|n| email(n, &["inbox"])).collect(),
        failing: BTreeMap::new(),
        queries: Vec::new(),
        downloads: Vec::new(),
        gets: Vec::new(),
        held: HashSet::new(),
    }
}

/// The session of the account `a1`, downloading by an RFC 8620 section 6.2
/// template.
fn session() -> JmapSession {
    let mut session = jmap_session(json!({}), json!({ "urn:ietf:params:jmap:mail": "a1" }));
    session.download_url =
        "https://api.example.com/jmap/download/{accountId}/{blobId}/{name}?accept={type}".into();
    session
}

impl FakeAccount {
    fn remove(&mut self, id: &str) {
        self.emails.retain(|email| email.id.as_deref() != Some(id));
    }

    /// Runs `verb` over the calls of a session on this account, the store
    /// behind the client holding [`Self::held`].
    fn serve<T>(&mut self, verb: impl FnOnce(&mut JmapCalls<'_, '_, BoxedServer<'_>>) -> T) -> T {
        let held = self.held.clone();
        let mut server = FakeServer::boxed(|request| self.answer(request));
        server.store = Some(holding(held));
        let mut client = Client::new(server);
        let mut jmap = Jmap {
            client: &mut client,
            session_url: Url::parse("https://api.example.com/jmap/session").unwrap(),
            auth: Auth::Bearer("token"),
        };
        let mut calls = JmapCalls {
            jmap: &mut jmap,
            session: session(),
            auth: SecretString::from("Bearer token"),
        };
        verb(&mut calls)
    }

    /// Answers one request: a blob download, or one JMAP API request, its
    /// method calls in order (RFC 8620 section 3.4), the `#ids` of an
    /// `Email/get` reading the query before it.
    fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
        assert_eq!(request.header("Authorization"), Some("Bearer token"));
        if let Some(blob) = request.path.strip_prefix("/jmap/download/a1/") {
            assert_eq!(request.method, "GET");
            let (blob, name) = blob.split_once('/').unwrap();
            assert_eq!(name, "message.eml");
            assert_eq!(request.param("accept"), Some("message/rfc822"));
            return self.download(blob);
        }
        assert_eq!(
            (request.method.as_str(), request.path.as_str()),
            ("POST", "/jmap/")
        );

        let calls: Vec<(String, Map<String, Value>, String)> =
            from_value(request.json::<Value>()["methodCalls"].take()).unwrap();
        let mut queried: Option<Vec<JmapEmail>> = None;
        let mut responses = Vec::new();
        for (name, args, id) in calls {
            assert_eq!(args["accountId"], "a1");
            let (name, result) = match name.as_str() {
                "Email/query" => match self.query(&args) {
                    Some((position, window)) => {
                        let ids: Vec<&Option<String>> =
                            window.iter().map(|email| &email.id).collect();
                        let result = json!({
                            "accountId": "a1",
                            "queryState": "q1",
                            "canCalculateChanges": false,
                            "position": position,
                            "ids": ids,
                        });
                        queried = Some(window);
                        ("Email/query", result)
                    }
                    None => ("error", json!({ "type": "anchorNotFound" })),
                },
                "Email/get" if args.contains_key("#ids") => match queried.take() {
                    // NOTE: a server lists `Email/get` in any order; this
                    // one reverses it, so the step sorts the window itself.
                    Some(window) => {
                        let list: Vec<JmapEmail> = window.into_iter().rev().collect();
                        ("Email/get", self.got(list, Vec::new()))
                    }
                    None => ("error", json!({ "type": "invalidResultReference" })),
                },
                "Email/get" => {
                    let ids: Vec<String> = from_value(args["ids"].clone()).unwrap();
                    let properties = &args["properties"];
                    if properties == &json!(["id"]) {
                        assert!(ids.is_empty(), "the state alone");
                        ("Email/get", self.got(Vec::new(), Vec::new()))
                    } else {
                        assert_eq!(properties, &json!(["id", "blobId"]));
                        self.gets.push(ids.clone());
                        let list: Vec<JmapEmail> = self
                            .emails
                            .iter()
                            .filter(|email| ids.contains(email.id.as_ref().unwrap()))
                            .cloned()
                            .collect();
                        let gone = ids
                            .into_iter()
                            .filter(|id| !list.iter().any(|email| email.id.as_ref() == Some(id)))
                            .collect();
                        ("Email/get", self.got(list, gone))
                    }
                }
                other => panic!("unexpected method {other}"),
            };
            responses.push(json!([name, result, id]));
        }

        let body = json!({ "methodResponses": responses, "sessionState": "s0" });
        FakeResponse::json(200, &body)
    }

    /// One `Email/query` as the step asked it, and the window it answers
    /// from its position, newest first; [`None`] when its anchor is not
    /// in the results.
    fn query(&mut self, args: &Map<String, Value>) -> Option<(usize, Vec<JmapEmail>)> {
        let sort = args["sort"][0]["property"].as_str().unwrap();
        let start = match (args.get("anchor"), args.get("position")) {
            (Some(anchor), None) => {
                assert_eq!(args["anchorOffset"], 1, "what follows the anchor");
                Start::After(anchor.as_str().unwrap().to_string())
            }
            (None, Some(position)) => Start::At(position.as_u64().unwrap()),
            (None, None) => Start::Top,
            (Some(_), Some(_)) => panic!("an anchor and a position"),
        };
        let query = StepQuery {
            filter: from_value(args["filter"].clone()).unwrap(),
            received: sort == "receivedAt",
            start,
            limit: args["limit"].as_u64().unwrap(),
        };
        self.queries.push(query.clone());

        let filter = &query.filter;
        let mut matching: Vec<&JmapEmail> = self
            .emails
            .iter()
            .filter(|email| {
                let filed: Vec<&String> = email
                    .mailbox_ids
                    .iter()
                    .flatten()
                    .map(|(id, _)| id)
                    .collect();
                let inside = filter
                    .in_mailbox
                    .as_ref()
                    .is_none_or(|mailbox| filed.contains(&mailbox));
                let elsewhere = filter
                    .in_mailbox_other_than
                    .as_ref()
                    .is_none_or(|excluded| filed.iter().any(|id| !excluded.contains(id)));
                let before = filter.before.as_ref().is_none_or(|before| {
                    email.received_at.as_ref().unwrap().as_str() < before.as_str()
                });
                inside && elsewhere && before
            })
            .collect();
        let key = |email: &&JmapEmail| match query.received {
            true => email.received_at.clone(),
            false => email.sent_at.clone(),
        };
        matching.sort_by_key(|email| Reverse(key(email)));

        let from = match &query.start {
            Start::Top => 0,
            Start::At(position) => *position as usize,
            Start::After(anchor) => {
                matching
                    .iter()
                    .position(|email| email.id.as_deref() == Some(anchor))?
                    + 1
            }
        };
        let window: Vec<JmapEmail> = matching
            .into_iter()
            .skip(from)
            .take(query.limit as usize)
            .cloned()
            .collect();
        Some((from, window))
    }

    /// An `Email/get` response listing `list`, `gone` not found.
    fn got(&self, list: Vec<JmapEmail>, gone: Vec<String>) -> Value {
        json!({ "accountId": "a1", "state": "s1", "list": list, "notFound": gone })
    }

    /// One blob's download: its message, or the status it fails with.
    fn download(&mut self, blob: &str) -> FakeResponse {
        self.downloads.push(blob.to_string());
        if let Some(status) = self.failing.get(blob) {
            return FakeResponse::empty(*status);
        }
        FakeResponse {
            content_type: "message/rfc822",
            body: format!("Subject: {blob}\r\n\r\nBody\r\n").into_bytes(),
            ..FakeResponse::empty(200)
        }
    }
}

fn ids(range: Range<usize>) -> Vec<String> {
    range.map(|n| format!("e{n:03}")).collect()
}

fn brought(stepped: &Stepped) -> Vec<String> {
    stepped
        .brought
        .iter()
        .map(|brought| brought.email.id.clone().unwrap())
        .collect()
}

fn ask(anchors: &[String]) -> StepRequest<'_> {
    StepRequest {
        mailbox: None,
        excluded: vec!["trash".into(), "junk".into()],
        sent_at: true,
        anchors,
        frontier: None,
        count: 50,
    }
}

#[test]
fn a_first_step_takes_the_fifty_newest_from_the_top() {
    let mut account = account(120);
    let dir = TempDir::new().unwrap();

    let stepped = account
        .serve(|calls| calls.step(&ask(&[]), dir.path()))
        .unwrap();

    assert_eq!(stepped.state, "s1");
    assert_eq!(brought(&stepped), ids(0..50));
    assert!(!stepped.exhausted);
    assert_eq!(stepped.anchors, ["e049", "e048", "e047", "e046", "e045"]);
    assert_eq!(account.queries.len(), 1);
    let query = &account.queries[0];
    assert_eq!(query.start, Start::Top);
    assert!(!query.received, "sorted by sentAt");
    assert_eq!(query.limit, 50);
    assert_eq!(
        query.filter.in_mailbox_other_than.as_deref(),
        Some(&["trash".to_string(), "junk".to_string()][..])
    );
    let first = &stepped.brought[0];
    assert_eq!(
        fs::read(&first.path).unwrap(),
        b"Subject: b000\r\n\r\nBody\r\n"
    );
    assert_eq!(first.size, 23);
}

#[test]
fn a_second_step_starts_right_after_the_first_anchor() {
    let mut account = account(120);
    let dir = TempDir::new().unwrap();
    account.held = ids(0..50).into_iter().collect();
    let anchors = ["e049".to_string(), "e048".to_string()];

    let stepped = account
        .serve(|calls| calls.step(&ask(&anchors), dir.path()))
        .unwrap();

    assert_eq!(brought(&stepped), ids(50..100));
    assert_eq!(account.queries.len(), 1);
    assert_eq!(account.queries[0].start, Start::After("e049".into()));
    assert_eq!(stepped.anchors[0], "e099");
}

#[test]
fn an_anchor_gone_retries_with_the_next_and_skips_nothing() {
    let mut account = account(120);
    account.remove("e049");
    let dir = TempDir::new().unwrap();
    account.held = ids(0..49).into_iter().collect();
    let anchors = ["e049".to_string(), "e048".to_string()];

    let stepped = account
        .serve(|calls| calls.step(&ask(&anchors), dir.path()))
        .unwrap();

    let starts: Vec<&Start> = account.queries.iter().map(|query| &query.start).collect();
    assert_eq!(
        starts,
        [&Start::After("e049".into()), &Start::After("e048".into())]
    );
    assert_eq!(brought(&stepped), ids(50..100));
}

#[test]
fn no_anchor_left_pages_by_reception_below_the_frontier() {
    let mut account = account(120);
    account.remove("e049");
    account.remove("e048");
    let dir = TempDir::new().unwrap();
    account.held = ids(0..48).into_iter().collect();
    let anchors = ["e049".to_string(), "e048".to_string()];
    let (frontier, _) = dates(47);
    let mut request = ask(&anchors);
    request.frontier = Some(&frontier);

    let stepped = account
        .serve(|calls| calls.step(&request, dir.path()))
        .unwrap();

    let paged = &account.queries[2];
    assert!(paged.received);
    assert_eq!(paged.start, Start::At(0));
    // NOTE: two days above the frontier, 47 hours from the newest: the
    // margin reaches the top here, and what is held is passed over.
    assert!(paged.filter.before.is_some());
    assert_eq!(brought(&stepped), ids(50..100));
    assert!(account.queries[3..].iter().all(|query| query.received));
}

#[test]
fn a_stretch_held_whole_is_moved_past() {
    let mut account = account(200);
    let dir = TempDir::new().unwrap();
    account.held = ids(0..80).into_iter().collect();

    let stepped = account
        .serve(|calls| calls.step(&ask(&[]), dir.path()))
        .unwrap();

    assert_eq!(brought(&stepped), ids(80..130));
    let asked: Vec<(Start, u64)> = account
        .queries
        .iter()
        .map(|query| (query.start.clone(), query.limit))
        .collect();
    assert_eq!(
        asked,
        [
            (Start::Top, 50),
            (Start::After("e049".into()), 50),
            (Start::After("e099".into()), 30),
        ]
    );
    assert_eq!(stepped.anchors[0], "e129");
}

#[test]
fn a_held_account_is_walked_a_few_queries_at_most() {
    let mut account = account(1000);
    let dir = TempDir::new().unwrap();
    account.held = ids(0..1000).into_iter().collect();

    let stepped = account
        .serve(|calls| calls.step(&ask(&[]), dir.path()))
        .unwrap();

    assert!(stepped.brought.is_empty());
    assert!(!stepped.exhausted);
    assert_eq!(account.queries.len(), QUERIES);
    assert_eq!(stepped.anchors[0], format!("e{:03}", QUERIES * 50 - 1));
}

#[test]
fn a_small_account_is_exhausted() {
    let mut account = account(30);
    let dir = TempDir::new().unwrap();

    let stepped = account
        .serve(|calls| calls.step(&ask(&[]), dir.path()))
        .unwrap();

    assert_eq!(brought(&stepped), ids(0..30));
    assert!(stepped.exhausted);
}

#[test]
fn a_body_that_fails_leaves_the_anchors_before_it() {
    let mut account = account(120);
    account.failing.insert("b030".into(), 500);
    let dir = TempDir::new().unwrap();

    let stepped = account
        .serve(|calls| calls.step(&ask(&[]), dir.path()))
        .unwrap();

    assert_eq!(stepped.brought.len(), 49);
    assert!(!brought(&stepped).contains(&"e030".to_string()));
    assert_eq!(stepped.anchors, ["e029", "e028", "e027", "e026", "e025"]);
    assert!(!stepped.exhausted);
}

#[test]
fn a_first_body_that_fails_keeps_the_anchors_asked() {
    let mut account = account(120);
    account.failing.insert("b050".into(), 500);
    let dir = TempDir::new().unwrap();
    account.held = ids(0..50).into_iter().collect();
    let anchors = ["e049".to_string()];

    let stepped = account
        .serve(|calls| calls.step(&ask(&anchors), dir.path()))
        .unwrap();

    assert_eq!(stepped.anchors, ["e049"]);
}

#[test]
fn an_expired_token_fails_the_step() {
    let mut account = account(120);
    account.failing.insert("b003".into(), 401);
    let dir = TempDir::new().unwrap();

    let err = account
        .serve(|calls| calls.step(&ask(&[]), dir.path()))
        .unwrap_err();

    assert_eq!(err.status, Some(401));
    assert_eq!(account.downloads.len(), 4);
}

#[test]
fn the_trash_and_the_junk_are_left_out_and_a_message_lands_in_every_mailbox() {
    let mut account = account(0);
    account.emails = vec![
        email(0, &["inbox", "clients"]),
        email(1, &["trash"]),
        email(2, &["junk"]),
        email(3, &["archive"]),
    ];
    let dir = TempDir::new().unwrap();

    let stepped = account
        .serve(|calls| calls.step(&ask(&[]), dir.path()))
        .unwrap();

    assert_eq!(brought(&stepped), ["e000", "e003"]);
    let filed: Vec<&String> = stepped.brought[0]
        .email
        .mailbox_ids
        .iter()
        .flatten()
        .map(|(id, _)| id)
        .collect();
    assert_eq!(filed, ["clients", "inbox"]);
    assert_eq!(account.downloads, ["b000", "b003"]);
}

#[test]
fn a_view_of_one_mailbox_steps_that_mailbox() {
    let mut account = account(0);
    account.emails = (0..60)
        .map(|n| email(n, if n % 2 == 0 { &["inbox"] } else { &["clients"] }))
        .collect();
    let dir = TempDir::new().unwrap();
    let mut request = ask(&[]);
    request.mailbox = Some("clients");

    let stepped = account
        .serve(|calls| calls.step(&request, dir.path()))
        .unwrap();

    assert_eq!(stepped.brought.len(), 30);
    assert!(stepped.exhausted);
    assert_eq!(
        account.queries[0].filter.in_mailbox.as_deref(),
        Some("clients")
    );
    assert!(account.queries[0].filter.in_mailbox_other_than.is_none());
}

#[test]
fn a_server_without_sent_at_sorts_by_reception() {
    let mut account = account(10);
    let dir = TempDir::new().unwrap();
    let mut request = ask(&[]);
    request.sent_at = false;

    account
        .serve(|calls| calls.step(&request, dir.path()))
        .unwrap();

    assert!(account.queries[0].received);
}

#[test]
fn paged_by_reception_an_import_is_no_anchor() {
    let mut account = account(10);
    // NOTE: written in 2019, received with the others.
    let (_, received) = dates(9);
    account.emails[9].sent_at = Some("2019-03-01T00:00:00Z".into());
    account.emails[9].received_at = Some(received);
    let dir = TempDir::new().unwrap();
    let gone = ["e100".to_string()];
    let (frontier, _) = dates(0);
    let mut request = ask(&gone);
    request.frontier = Some(&frontier);

    let stepped = account
        .serve(|calls| calls.step(&request, dir.path()))
        .unwrap();

    assert!(account.queries[1].received);
    assert_eq!(stepped.anchors[0], "e008");
}

#[test]
fn sorted_by_reception_alone_an_import_anchors_where_it_was_received() {
    let mut account = account(10);
    account.emails[9].sent_at = Some("2019-03-01T00:00:00Z".into());
    let dir = TempDir::new().unwrap();
    let mut request = ask(&[]);
    request.sent_at = false;

    let stepped = account
        .serve(|calls| calls.step(&request, dir.path()))
        .unwrap();

    assert_eq!(stepped.anchors[0], "e009");
}

#[test]
fn blobs_are_named_in_one_get_then_downloaded_one_by_one() {
    let mut account = account(20);
    account.failing.insert("b004".into(), 500);
    let dir = TempDir::new().unwrap();
    let mut asked = ids(0..10);
    asked.push("gone".into());

    let read = account
        .serve(|calls| calls.sources(&asked, 50, dir.path()))
        .unwrap();

    assert_eq!(account.gets.len(), 1);
    assert_eq!(account.downloads.len(), 10);
    assert_eq!(read.gone, ["gone"], "gone, not failed");
    let read: Vec<&str> = read
        .fetched
        .iter()
        .map(|source| source.id.as_str())
        .collect();
    assert_eq!(read.len(), 9);
    assert!(!read.contains(&"e004"));
}

#[test]
fn blobs_are_named_a_chunk_at_a_time() {
    let mut account = account(20);
    let dir = TempDir::new().unwrap();

    let read = account
        .serve(|calls| calls.sources(&ids(0..10), 4, dir.path()))
        .unwrap()
        .fetched;

    assert_eq!(read.len(), 10);
    let chunks: Vec<usize> = account.gets.iter().map(Vec::len).collect();
    assert_eq!(chunks, [4, 4, 2]);
}

#[test]
fn an_expired_token_fails_the_sources() {
    let mut account = account(5);
    account.failing.insert("b001".into(), 401);
    let dir = TempDir::new().unwrap();

    let err = account
        .serve(|calls| calls.sources(&ids(0..5), 50, dir.path()))
        .unwrap_err();

    assert_eq!(err.status, Some(401));
}

#[test]
fn a_run_reads_the_changes_from_one_state_once() {
    let run = JmapRun::default();
    let reads = AtomicUsize::new(0);
    let read = || {
        reads.fetch_add(1, Ordering::SeqCst);
        Ok(Some(JmapDelta {
            next: "s2".into(),
            emails: vec![email(0, &["inbox"])],
            gone: vec!["e009".into()],
        }))
    };

    let first = run.delta("s1", read).unwrap().unwrap();
    let second = run.delta("s1", read).unwrap().unwrap();
    run.delta("s0", read).unwrap();

    assert_eq!(reads.load(Ordering::SeqCst), 2);
    assert!(Arc::ptr_eq(&first, &second));
}

#[test]
fn a_run_keeps_no_failure_and_remembers_an_expired_state() {
    let run = JmapRun::default();

    let failed = run.delta("s1", || Err("down".into()));
    assert!(failed.is_err());
    let read = run.delta("s1", || Ok(None)).unwrap();
    assert!(read.is_none());
    let kept = run
        .delta("s1", || panic!("an expired state is not read again"))
        .unwrap();
    assert!(kept.is_none());
}

#[test]
fn the_sessions_of_one_run_share_it() {
    let one = join("jmap://example.org\u{0}me", 7);
    let other = join("jmap://example.org\u{0}me", 7);
    let alone = join("jmap://example.org\u{0}me", 0);

    assert!(Arc::ptr_eq(&one, &other));
    assert!(!Arc::ptr_eq(&one, &alone));
}
