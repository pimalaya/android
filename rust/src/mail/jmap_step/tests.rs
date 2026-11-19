//! # JMAP read tests
//!
//! Blob reads over a fake account; a run's changes shared.
//!
//! The fake answers the real [`JmapCalls`] as a server does where it
//! matters: `Email/get` by ids, blob downloads (RFC 8620 §6.2).

use std::{
    collections::BTreeMap,
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
        fake::{BoxedServer, FakeRequest, FakeResponse, FakeServer, jmap_session},
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
    downloads: Vec<String>,
    gets: Vec<Vec<String>>,
}

fn account(size: usize) -> FakeAccount {
    FakeAccount {
        emails: (0..size).map(|n| email(n, &["inbox"])).collect(),
        failing: BTreeMap::new(),
        downloads: Vec::new(),
        gets: Vec::new(),
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
    /// Runs `verb` over the calls of a session on this account.
    fn serve<T>(&mut self, verb: impl FnOnce(&mut JmapCalls<'_, '_, BoxedServer<'_>>) -> T) -> T {
        let server = FakeServer::boxed(|request| self.answer(request));
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
    /// method calls in order (RFC 8620 section 3.4).
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
        let mut responses = Vec::new();
        for (name, args, id) in calls {
            assert_eq!(args["accountId"], "a1");
            let (name, result) = match name.as_str() {
                "Email/get" => {
                    let ids: Vec<String> = from_value(args["ids"].clone()).unwrap();
                    let properties = &args["properties"];
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
                other => panic!("unexpected method {other}"),
            };
            responses.push(json!([name, result, id]));
        }

        let body = json!({ "methodResponses": responses, "sessionState": "s0" });
        FakeResponse::json(200, &body)
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

/// A move the run pushed is replayed by a mailbox listed after it, so its
/// target lands it in the same pass, as `GmailRun::refiled` has it.
#[test]
fn a_move_the_run_pushed_reads_the_changes_again() {
    let run = JmapRun::default();
    let reads = AtomicUsize::new(0);
    let read = || {
        reads.fetch_add(1, Ordering::SeqCst);
        Ok(Some(JmapDelta {
            next: "s2".into(),
            emails: vec![email(0, &["trash"])],
            gone: Vec::new(),
        }))
    };

    run.delta("s1", read).unwrap();
    run.delta("s1", read).unwrap();
    run.refiled();
    run.delta("s1", read).unwrap();

    assert_eq!(
        reads.load(Ordering::SeqCst),
        2,
        "read again after the move alone"
    );
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
