//! A JMAP step over a fake account: from the top, anchored, an anchor
//! gone, paged by reception once none is left, a stretch held whole
//! moved past, a body that fails, mailboxes left out and placed; blobs
//! named in one `Email/get`; a pool run's changes read once.

use std::{
    cmp::Reverse,
    collections::{BTreeMap, HashSet},
    fs,
    ops::Range,
    sync::atomic::{AtomicUsize, Ordering},
};

use jiff::{SignedDuration, Timestamp};
use serde_json::{from_value, json};
use tempfile::TempDir;

use super::*;

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

impl FakeAccount {
    fn remove(&mut self, id: &str) {
        self.emails.retain(|email| email.id.as_deref() != Some(id));
    }
}

impl JmapStepReads for FakeAccount {
    fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError> {
        Ok(ids
            .iter()
            .filter(|id| self.held.contains(*id))
            .cloned()
            .collect())
    }

    fn state(&mut self) -> Result<String, BridgeError> {
        Ok("s1".into())
    }

    fn query(&mut self, query: &StepQuery) -> Result<Option<Vec<JmapEmail>>, BridgeError> {
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
                match matching
                    .iter()
                    .position(|email| email.id.as_deref() == Some(anchor))
                {
                    Some(at) => at + 1,
                    None => return Ok(None),
                }
            }
        };
        let window: Vec<JmapEmail> = matching
            .into_iter()
            .skip(from)
            .take(query.limit as usize)
            .cloned()
            .collect();
        // NOTE: a server lists `Email/get` in any order; this one reverses
        // it, so the step sorts the window itself.
        Ok(Some(window.into_iter().rev().collect()))
    }

    fn blobs(&mut self, ids: Vec<String>) -> Result<Vec<JmapEmail>, BridgeError> {
        self.gets.push(ids.clone());
        Ok(self
            .emails
            .iter()
            .filter(|email| ids.contains(email.id.as_ref().unwrap()))
            .cloned()
            .collect())
    }

    fn download(&mut self, blob: &str) -> Result<Vec<u8>, BridgeError> {
        self.downloads.push(blob.to_string());
        if let Some(status) = self.failing.get(blob) {
            return Err(BridgeError {
                message: format!("{status}"),
                status: Some(*status),
            });
        }
        Ok(format!("Subject: {blob}\r\n\r\nBody\r\n").into_bytes())
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

    let stepped = step(&mut account, &ask(&[]), dir.path()).unwrap();

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

    let stepped = step(&mut account, &ask(&anchors), dir.path()).unwrap();

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

    let stepped = step(&mut account, &ask(&anchors), dir.path()).unwrap();

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

    let stepped = step(&mut account, &request, dir.path()).unwrap();

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

    let stepped = step(&mut account, &ask(&[]), dir.path()).unwrap();

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

    let stepped = step(&mut account, &ask(&[]), dir.path()).unwrap();

    assert!(stepped.brought.is_empty());
    assert!(!stepped.exhausted);
    assert_eq!(account.queries.len(), QUERIES);
    assert_eq!(stepped.anchors[0], format!("e{:03}", QUERIES * 50 - 1));
}

#[test]
fn a_small_account_is_exhausted() {
    let mut account = account(30);
    let dir = TempDir::new().unwrap();

    let stepped = step(&mut account, &ask(&[]), dir.path()).unwrap();

    assert_eq!(brought(&stepped), ids(0..30));
    assert!(stepped.exhausted);
}

#[test]
fn a_body_that_fails_leaves_the_anchors_before_it() {
    let mut account = account(120);
    account.failing.insert("b030".into(), 500);
    let dir = TempDir::new().unwrap();

    let stepped = step(&mut account, &ask(&[]), dir.path()).unwrap();

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

    let stepped = step(&mut account, &ask(&anchors), dir.path()).unwrap();

    assert_eq!(stepped.anchors, ["e049"]);
}

#[test]
fn an_expired_token_fails_the_step() {
    let mut account = account(120);
    account.failing.insert("b003".into(), 401);
    let dir = TempDir::new().unwrap();

    let err = step(&mut account, &ask(&[]), dir.path()).unwrap_err();

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

    let stepped = step(&mut account, &ask(&[]), dir.path()).unwrap();

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

    let stepped = step(&mut account, &request, dir.path()).unwrap();

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

    step(&mut account, &request, dir.path()).unwrap();

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

    let stepped = step(&mut account, &request, dir.path()).unwrap();

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

    let stepped = step(&mut account, &request, dir.path()).unwrap();

    assert_eq!(stepped.anchors[0], "e009");
}

#[test]
fn blobs_are_named_in_one_get_then_downloaded_one_by_one() {
    let mut account = account(20);
    account.failing.insert("b004".into(), 500);
    let dir = TempDir::new().unwrap();
    let mut asked = ids(0..10);
    asked.push("gone".into());

    let read = sources(&mut account, &asked, 50, dir.path()).unwrap();

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

    let read = sources(&mut account, &ids(0..10), 4, dir.path())
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

    let err = sources(&mut account, &ids(0..5), 50, dir.path()).unwrap_err();

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
