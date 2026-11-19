//! The mail list's coverage over a store on the floor: mailboxes filling
//! in, below a floor, whole, holding messages without their body, and
//! several accounts short, each read beside a search's hits.

use std::path::Path;

use io_pimdir::{
    change::PimdirWriteOp,
    client::PimdirStore,
    collection::{PimdirCollectionId, PimdirScope},
    object::PimdirObject,
    placement::PimdirFlags,
    summary,
};

use crate::search::{
    coverage::{self, MailCollection, MailCoverage, MailHits, MailScope},
    find::Answer,
    floor::Floor,
    tests::{index, placement, refreshed, unix},
};

/// The source the mailboxes' rounds are written by.
const SERVER: &str = "server";

/// The instant the searches are asked at.
const NOW: &str = "2026-11-19T12:00:00Z";

/// A store at a temporary directory and its index, built by the owner.
struct Store {
    dir: tempfile::TempDir,
    _held: PimdirStore,
}

impl Store {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let held = PimdirStore::open(dir.path()).unwrap();
        Self { dir, _held: held }
    }

    fn path(&self) -> &Path {
        self.dir.path()
    }

    /// A mailbox of `account`, `account/INBOX`.
    fn mailbox(&self, account: &str) -> String {
        let id = format!("{account}/INBOX");
        PimdirStore::open(self.path())
            .unwrap()
            .for_account(account)
            .ensure_collection(&id, "message/rfc822")
            .unwrap();
        id
    }

    /// A message dated `date` (RFC 5322), its body held or not.
    fn message(&self, account: &str, id: &str, subject: &str, date: &str, body: bool) {
        let collection = format!("{account}/INBOX");
        let raw = format!(
            "Message-ID: <{id}@x>\r\nSubject: {subject}\r\nFrom: <a@example.org>\r\n\
             Date: {date}\r\n\r\nthe roadmap for the quarter\r\n"
        );
        let raw = raw.into_bytes();
        let derived = summary::derive("message/rfc822", &raw).unwrap();
        let mut owner = PimdirStore::open(self.path())
            .unwrap()
            .for_account(account)
            .for_source(SERVER);
        let hash = owner.hash(&raw);
        let mut ops = Vec::new();
        if body {
            ops.push(PimdirWriteOp::StoreObject {
                object: PimdirObject {
                    hash: hash.clone(),
                    size: raw.len(),
                },
                body: Some(raw),
            });
        }
        ops.push(PimdirWriteOp::UpsertPlacement(placement(
            &collection,
            id,
            id,
            body.then_some(hash),
            derived.summary.unwrap(),
            derived.sort_key.as_str(),
            PimdirFlags::default(),
        )));
        owner.write(ops).unwrap();
    }

    /// A round over a mailbox from `since`, closed or still under way.
    fn round(&self, account: &str, since: Option<&str>, closed: bool) {
        let collection = PimdirCollectionId(format!("{account}/INBOX"));
        let scope = PimdirScope {
            since: since.map(Into::into),
            until: None,
        };
        let mut ops = vec![PimdirWriteOp::OpenRound {
            collection: collection.clone(),
            scope: scope.clone(),
            band: false,
        }];
        if closed {
            ops.push(PimdirWriteOp::CloseRound {
                collection,
                coverage: scope,
                checkpoint: None,
            });
        }
        PimdirStore::open(self.path())
            .unwrap()
            .for_account(account)
            .for_source(SERVER)
            .write(ops)
            .unwrap();
    }

    /// The index, refreshed whole.
    fn index(&self) -> Floor {
        let mut floor = index(self.path());
        refreshed(&mut floor, self.path(), unix(NOW), 100);
        floor
    }
}

/// The mail list's scope over `accounts`' inboxes, as the app sends it.
fn scope(accounts: &[(&str, Option<&str>, Option<&str>)]) -> MailScope {
    MailScope {
        source: SERVER.into(),
        collections: accounts
            .iter()
            .map(|(account, reach, bound)| MailCollection {
                id: format!("{account}/INBOX"),
                reach: reach.map(Into::into),
                bound: bound.map(Into::into),
            })
            .collect(),
    }
}

/// The search of `query` over `scope`, found.
fn search(floor: &mut Floor, query: &str, scope: &MailScope) -> MailHits {
    match coverage::mail(floor, query, scope, unix(NOW)).unwrap() {
        Answer::Found(found) => found,
        Answer::Refused(refused) => panic!("{query:?} refused: {}", refused.message),
    }
}

const MARCH: &str = "2026-03-01T00:00:00Z";
const MAY: &str = "2026-05-01T00:00:00Z";

#[test]
fn a_mailbox_listed_whole_is_complete() {
    let store = Store::new();
    store.mailbox("work");
    store.message(
        "work",
        "a",
        "Weekly",
        "Mon, 5 Jan 2026 09:00:00 +0000",
        true,
    );
    store.round("work", None, true);
    let mut floor = store.index();

    let found = search(&mut floor, "roadmap", &scope(&[("work", None, None)]));
    assert_eq!(found.hits.len(), 1);
    let coverage = found.coverage;
    assert!(coverage.complete, "{coverage:?}");
    assert_eq!(coverage.short, 0);
    assert_eq!(coverage.worst, None);
    assert_eq!(coverage.since, None);
    assert!(!coverage.filling);
    assert_eq!(coverage.unread, 0);
    assert_eq!(coverage.accounts.len(), 1);
    assert_eq!(coverage.accounts[0].account.as_deref(), Some("work"));
}

#[test]
fn a_mailbox_still_filling_is_still_syncing() {
    let store = Store::new();
    store.mailbox("work");
    store.message(
        "work",
        "a",
        "Weekly",
        "Mon, 5 Jan 2026 09:00:00 +0000",
        true,
    );
    store.round("work", None, false);
    let mut floor = store.index();

    let coverage = search(&mut floor, "roadmap", &scope(&[("work", None, None)])).coverage;
    assert!(!coverage.complete);
    assert!(coverage.filling, "a round never closed");
    assert_eq!(coverage.short, 1);
    assert_eq!(coverage.worst.as_deref(), Some("work"));
    assert_eq!(coverage.since, None, "no floor known while it fills");
}

#[test]
fn a_mailbox_never_listed_is_still_syncing() {
    let store = Store::new();
    store.mailbox("work");
    let mut floor = store.index();

    let coverage = search(&mut floor, "roadmap", &scope(&[("work", None, None)])).coverage;
    assert!(!coverage.complete, "no round of the server's yet");
    assert!(coverage.filling);
}

#[test]
fn a_search_below_a_floor_names_the_account_and_the_date() {
    let store = Store::new();
    store.mailbox("work");
    store.message(
        "work",
        "a",
        "Weekly",
        "Mon, 6 Apr 2026 09:00:00 +0000",
        true,
    );
    store.round("work", Some(MARCH), true);
    let mut floor = store.index();

    let coverage = search(&mut floor, "roadmap", &scope(&[("work", None, None)])).coverage;
    assert!(!coverage.complete);
    assert!(!coverage.filling);
    assert_eq!(coverage.short, 1);
    assert_eq!(coverage.worst.as_deref(), Some("work"));
    assert_eq!(coverage.since.as_deref(), Some(MARCH));
    assert!(!coverage.bounded, "more may come below 1 March");

    let inside = search(
        &mut floor,
        "roadmap date:2026-04..",
        &scope(&[("work", None, None)]),
    );
    assert!(inside.coverage.complete, "a range above the floor is whole");

    let kept = search(
        &mut floor,
        "roadmap",
        &scope(&[("work", None, Some(MARCH))]),
    );
    assert!(!kept.coverage.complete);
    assert!(kept.coverage.bounded, "the floor is the account's bound");
}

#[test]
fn an_account_stepped_through_is_short_down_to_its_reach() {
    let store = Store::new();
    store.mailbox("gmail");
    store.message(
        "gmail",
        "a",
        "Weekly",
        "Mon, 6 Jul 2026 09:00:00 +0000",
        true,
    );
    store.round("gmail", None, true);
    let mut floor = store.index();

    let whole = search(&mut floor, "roadmap", &scope(&[("gmail", None, None)]));
    assert!(whole.coverage.complete, "its round names the whole bound");

    let reached = search(&mut floor, "roadmap", &scope(&[("gmail", Some(MAY), None)]));
    assert!(!reached.coverage.complete);
    assert_eq!(reached.coverage.worst.as_deref(), Some("gmail"));
    assert_eq!(reached.coverage.since.as_deref(), Some(MAY));
}

#[test]
fn bodies_not_on_the_phone_are_counted_against_a_text_search() {
    let store = Store::new();
    store.mailbox("work");
    store.message(
        "work",
        "a",
        "Weekly",
        "Mon, 5 Jan 2026 09:00:00 +0000",
        true,
    );
    store.message(
        "work",
        "b",
        "Roadmap",
        "Tue, 6 Jan 2026 09:00:00 +0000",
        false,
    );
    store.message(
        "work",
        "c",
        "Lunch",
        "Wed, 7 Jan 2026 09:00:00 +0000",
        false,
    );
    store.round("work", None, true);
    let mut floor = store.index();
    let shown = scope(&[("work", None, None)]);

    let found = search(&mut floor, "roadmap", &shown);
    assert_eq!(found.hits.len(), 2, "a body and a subject");
    let coverage = found.coverage;
    assert!(!coverage.complete);
    assert_eq!(coverage.short, 0, "the range is whole");
    assert_eq!(coverage.unread, 2, "every message in scope held bodiless");

    let structured = search(&mut floor, "from:a@example.org", &shown).coverage;
    assert!(structured.complete, "no term reads a body");
    assert_eq!(structured.unread, 0);
}

#[test]
fn two_accounts_short_roll_up_to_the_latest_floor() {
    let store = Store::new();
    for account in ["work", "home", "club"] {
        store.mailbox(account);
        store.message(
            account,
            "a",
            "Weekly",
            "Mon, 6 Jul 2026 09:00:00 +0000",
            true,
        );
    }
    store.round("work", Some(MARCH), true);
    store.round("home", Some(MAY), true);
    store.round("club", None, true);
    let mut floor = store.index();

    let shown = scope(&[
        ("work", None, None),
        ("home", None, None),
        ("club", None, None),
    ]);
    let coverage: MailCoverage = search(&mut floor, "roadmap", &shown).coverage;
    assert!(!coverage.complete);
    assert_eq!(coverage.short, 2);
    assert_eq!(coverage.worst.as_deref(), Some("home"));
    assert_eq!(coverage.since.as_deref(), Some(MAY));
    assert_eq!(coverage.accounts.len(), 3);
    assert!(coverage.accounts[2].complete, "club is whole");

    let one = search(&mut floor, "roadmap account:club", &shown).coverage;
    assert!(one.complete, "the query's own account alone");
    assert_eq!(one.accounts.len(), 1);
}
