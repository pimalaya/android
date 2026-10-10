//! The Gmail account listing over a fake Gmail account, driven by
//! io-pimdir's own sync engine and store: one listing projected onto
//! every label, each message read once, the history replayed once a pass.
//!
//! The fake answers the four requests of [`GmailSource`] the way Gmail
//! does where it matters here: `messages.list` newest first, a page of
//! 500 ids, narrowed by received date, a label's listing leaving spam and
//! trash out; metadata read by batch; a history naming every message
//! moved since a history id, the ones deleted for good apart, until Gmail
//! no longer holds it.

use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{Arc, Mutex, MutexGuard},
    thread,
};

use io_pimdir::{
    change::PimdirChange,
    client::{PimdirSourceStore, PimdirStore, reader::PimdirReader},
    collection::{PimdirCollectionId, PimdirScope},
    placement::PimdirHandle,
    remote::{
        PimdirEnumerate, PimdirEnumerated, PimdirFetchedItem, PimdirListing, PimdirPushResult,
        PimdirRemote, PimdirTier,
    },
    summary::mail::PimdirMailSummary,
    sync::PimdirSyncOptions,
};
use jiff::{SignedDuration, Timestamp};
use tempfile::TempDir;

use super::{FIRST_CHUNK, GmailRun, GmailSource, floor, list_label};
use crate::{
    client::{
        gmail::{BATCH, GmailEnvelope, GmailHistoryDelta, GmailIds, INBOX, SPAM, TRASH},
        listing::{Listing, MailPage, MailRequest, RECEIVED_MARGIN_DAYS, Scope, flags},
    },
    offline::enumerated,
    types::BridgeError,
};

const SOURCE: &str = "gmail";
const SENT: &str = "SENT";
const CLIENTS: &str = "Label_1";
const SEED: &str = "Label_2";
const MOA: &str = "Label_3";

/// One message of the fake account.
#[derive(Clone, Debug)]
struct Mail {
    date: String,
    /// When Gmail received it, its `date` unless imported apart.
    received: String,
    labels: Vec<String>,
}

/// One entry of the account's history.
enum Event {
    /// Added, labelled or unlabelled.
    Moved(String),
    /// Deleted for good.
    Deleted(String),
}

/// What was asked of the account.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
struct Counts {
    /// `users.getProfile` requests.
    profiles: usize,
    /// `messages.list` requests with no label.
    account_lists: usize,
    /// `messages.list` requests with a label.
    label_lists: usize,
    /// Batch requests.
    batches: usize,
    /// Metadata reads, all batches counted.
    reads: usize,
    /// `history.list` replays.
    histories: usize,
}

/// A Gmail account, and what was asked of it.
#[derive(Default)]
struct Account {
    mails: BTreeMap<String, Mail>,
    /// The history: a history id is the number of entries it follows.
    log: Vec<Event>,
    /// The oldest history id Gmail still replays from.
    oldest: usize,
    /// The ids `messages.list` answers a page.
    page: usize,
    counts: Counts,
    /// Metadata reads by message.
    read: BTreeMap<String, usize>,
}

/// One session's view of the account, which every session shares.
#[derive(Clone)]
struct FakeGmail(Arc<Mutex<Account>>);

/// The date of the message `days` before the account's newest.
fn days_back(days: i64) -> String {
    let top: Timestamp = "2026-10-01T12:00:00Z".parse().unwrap();
    top.checked_sub(SignedDuration::from_hours(24 * days))
        .unwrap()
        .strftime("%Y-%m-%dT%H:%M:%SZ")
        .to_string()
}

/// The id of the message `index` days before the newest.
fn id(index: usize) -> String {
    format!("m{index:04}")
}

impl FakeGmail {
    /// An account of `size` messages, one a day, `m0000` the newest, each
    /// under the labels `labels` names for its index.
    fn new(size: usize, labels: impl Fn(usize) -> Vec<&'static str>) -> Self {
        let mut account = Account {
            page: 500,
            ..Default::default()
        };
        for index in 0..size {
            account.mails.insert(
                id(index),
                Mail {
                    date: days_back(index as i64),
                    received: days_back(index as i64),
                    labels: labels(index).into_iter().map(String::from).collect(),
                },
            );
        }
        Self(Arc::new(Mutex::new(account)))
    }

    /// The account of the seeded test account: every message under
    /// [`MOA`], beside the inbox (one in two), the sent mail or [`SEED`].
    fn seeded(size: usize) -> Self {
        Self::new(size, |index| match index % 4 {
            0 | 1 => vec![INBOX, MOA],
            2 => vec![SENT, MOA],
            _ => vec![SEED, MOA],
        })
    }

    fn lock(&self) -> MutexGuard<'_, Account> {
        self.0.lock().unwrap()
    }

    fn counts(&self) -> Counts {
        self.lock().counts.clone()
    }

    /// Whether every message of the account was read exactly once.
    fn read_once(&self) -> bool {
        let account = self.lock();
        account
            .mails
            .keys()
            .all(|id| account.read.get(id) == Some(&1))
    }

    fn reads_of(&self, id: &str) -> usize {
        self.lock().read.get(id).copied().unwrap_or_default()
    }

    fn add(&mut self, id: &str, days: i64, labels: &[&str]) {
        let mut account = self.lock();
        account.mails.insert(
            id.into(),
            Mail {
                date: days_back(days),
                received: days_back(days),
                labels: labels.iter().map(|label| label.to_string()).collect(),
            },
        );
        account.log.push(Event::Moved(id.into()));
    }

    fn relabel(&mut self, id: &str, add: &[&str], remove: &[&str]) {
        let mut account = self.lock();
        let mail = account.mails.get_mut(id).unwrap();
        mail.labels
            .retain(|label| !remove.contains(&label.as_str()));
        mail.labels
            .extend(add.iter().map(|label| label.to_string()));
        account.log.push(Event::Moved(id.into()));
    }

    fn delete(&mut self, id: &str) {
        let mut account = self.lock();
        account.mails.remove(id);
        account.log.push(Event::Deleted(id.into()));
    }

    /// Gmail forgets the history up to now.
    fn expire(&mut self) {
        let mut account = self.lock();
        account.oldest = account.log.len();
    }
}

impl GmailSource for FakeGmail {
    fn history_id(&mut self) -> Result<String, BridgeError> {
        let mut account = self.lock();
        account.counts.profiles += 1;
        Ok(account.log.len().to_string())
    }

    fn list(
        &mut self,
        label: Option<&str>,
        scope: &Scope,
        page: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        let mut account = self.lock();
        match label {
            None => account.counts.account_lists += 1,
            Some(_) => account.counts.label_lists += 1,
        }

        let since = scope.received_since(RECEIVED_MARGIN_DAYS);
        let until = scope.received_until(RECEIVED_MARGIN_DAYS);
        let mut matching: Vec<(&String, &Mail)> = account
            .mails
            .iter()
            .filter(|(_, mail)| {
                let has = |id: &str| mail.labels.iter().any(|carried| carried == id);
                label.is_none_or(|label| {
                    has(label) && (label == TRASH || label == SPAM || !(has(TRASH) || has(SPAM)))
                })
            })
            .filter(|(_, mail)| {
                let received: Timestamp = mail.received.parse().unwrap();
                since.is_none_or(|since| received >= since)
                    && until.is_none_or(|until| received < until)
            })
            .collect();
        matching.sort_by(|a, b| b.1.received.cmp(&a.1.received).then(a.0.cmp(b.0)));

        let offset: usize = page.map_or(0, |page| page.parse().unwrap());
        let end = (offset + account.page).min(matching.len());
        Ok(GmailIds {
            ids: matching[offset..end]
                .iter()
                .map(|(id, _)| (*id).clone())
                .collect(),
            next: (end < matching.len()).then(|| end.to_string()),
        })
    }

    fn read(
        &mut self,
        ids: &[String],
    ) -> Result<Vec<(String, Option<GmailEnvelope>)>, BridgeError> {
        assert!(ids.len() <= BATCH, "{} reads in one batch", ids.len());
        let mut account = self.lock();
        account.counts.batches += 1;
        account.counts.reads += ids.len();

        let mut read = Vec::new();
        for id in ids {
            *account.read.entry(id.clone()).or_default() += 1;
            let envelope = account.mails.get(id).map(|mail| {
                let has = |label: &str| mail.labels.iter().any(|carried| carried == label);
                GmailEnvelope {
                    labels: mail.labels.clone(),
                    flags: flags(!has("UNREAD"), false, has("STARRED")),
                    summary: PimdirMailSummary {
                        message_id: Some(format!("{id}@example.org")),
                        subject: format!("Subject {id}"),
                        date: Some(mail.date.clone()),
                        ..Default::default()
                    },
                    received: mail
                        .received
                        .parse::<Timestamp>()
                        .ok()
                        .map(|at| at.as_millisecond()),
                }
            });
            read.push((id.clone(), envelope));
        }
        Ok(read)
    }

    fn history(&mut self, start: &str) -> Result<Option<GmailHistoryDelta>, BridgeError> {
        let mut account = self.lock();
        account.counts.histories += 1;

        let start: usize = start.parse().unwrap();
        if start < account.oldest {
            return Ok(None);
        }
        let mut deleted = Vec::new();
        for event in &account.log[start..] {
            if let Event::Deleted(id) = event
                && !deleted.contains(id)
            {
                deleted.push(id.clone());
            }
        }
        let mut changed = Vec::new();
        for event in &account.log[start..] {
            if let Event::Moved(id) = event
                && !deleted.contains(id)
                && !changed.contains(id)
            {
                changed.push(id.clone());
            }
        }
        Ok(Some(GmailHistoryDelta {
            changed,
            deleted,
            next: account.log.len().to_string(),
        }))
    }
}

/// The engine's remote over the fake: a label's page, as Java forwards
/// the request, answered from the run's account listing.
struct Remote<'r> {
    gmail: FakeGmail,
    run: &'r GmailRun,
}

impl PimdirRemote for Remote<'_> {
    type Error = String;

    fn enumerate(
        &mut self,
        collection: &PimdirCollectionId,
        request: PimdirEnumerate,
    ) -> Result<PimdirEnumerated, String> {
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
            covered: false,
        };
        let page = list_label(&mut self.gmail, self.run, collection.as_str(), &request)
            .map_err(|err| err.message)?;
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

/// A store holding one mailbox per label, synced the way `MailEngine`
/// syncs a Gmail account.
struct Harness {
    dir: TempDir,
    store: PimdirSourceStore,
    gmail: FakeGmail,
    labels: Vec<&'static str>,
}

impl Harness {
    fn new(gmail: &FakeGmail, labels: &[&'static str]) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let store = PimdirStore::open(dir.path()).unwrap().for_source(SOURCE);
        for label in labels {
            store.ensure_collection(*label, "message/rfc822").unwrap();
        }
        Self {
            dir,
            store,
            gmail: gmail.clone(),
            labels: labels.to_vec(),
        }
    }

    /// One pass over every mailbox on one run: a mailbox never listed
    /// takes its first chunk, one listed lists what it covers.
    fn pass(&mut self) {
        let run = GmailRun::default();
        for label in self.labels.clone() {
            let since = match self.since(label) {
                Some(since) => since,
                None => {
                    floor(&mut self.gmail.clone(), &run, label, None, FIRST_CHUNK)
                        .unwrap()
                        .floor
                }
            };
            self.sync(&run, label, since);
        }
    }

    /// Every mailbox widened by `count` below its floor on one run, as
    /// the fill does.
    fn fill(&mut self, count: usize) {
        let run = GmailRun::default();
        for label in self.labels.clone() {
            let before = self.since(label).unwrap();
            let since = floor(
                &mut self.gmail.clone(),
                &run,
                label,
                before.as_deref(),
                count,
            )
            .unwrap()
            .floor;
            self.sync(&run, label, since);
        }
    }

    fn sync(&mut self, run: &GmailRun, label: &str, since: Option<String>) {
        let opts = PimdirSyncOptions {
            scope: PimdirScope { since, until: None },
            ..Default::default()
        };
        let mut remote = Remote {
            gmail: self.gmail.clone(),
            run,
        };
        self.store.sync(label, opts, &mut remote).unwrap();
    }

    /// Where a mailbox's listing starts, the way `MailEngine` reads it:
    /// its round under way, else its last closed round; [`None`] for a
    /// mailbox never listed.
    fn since(&self, label: &str) -> Option<Option<String>> {
        let reader = PimdirReader::open(self.dir.path()).unwrap();
        let coverage = reader
            .list_coverage(label)
            .unwrap()
            .into_iter()
            .find(|coverage| coverage.source == SOURCE)?;
        match (coverage.round, coverage.coverage) {
            (Some(round), _) => Some(round.scope.since),
            (None, Some(covered)) => Some(covered.scope.since),
            (None, None) => None,
        }
    }

    /// The message ids a mailbox holds.
    fn held(&self, label: &str) -> BTreeSet<String> {
        let reader = PimdirReader::open(self.dir.path()).unwrap();
        reader
            .list_items(label, None, 100_000)
            .unwrap()
            .into_iter()
            .map(|item| item.link_id.as_str().to_string())
            .collect()
    }
}

/// What was asked between two readings.
fn since(before: &Counts, after: &Counts) -> Counts {
    Counts {
        profiles: after.profiles - before.profiles,
        account_lists: after.account_lists - before.account_lists,
        label_lists: after.label_lists - before.label_lists,
        batches: after.batches - before.batches,
        reads: after.reads - before.reads,
        histories: after.histories - before.histories,
    }
}

/// A round's request from `cursor`, over `scope`.
fn round(cursor: Option<&str>, scope: Scope) -> MailRequest {
    MailRequest {
        listing: Listing::Round {
            cursor: cursor.map(String::from),
            band: false,
        },
        scope,
        covered: false,
    }
}

#[test]
fn a_mail_under_three_labels_is_read_once_and_placed_three_times() {
    let gmail = FakeGmail::new(10, |index| match index {
        3 => vec![INBOX, CLIENTS, SEED],
        _ => vec![INBOX],
    });
    let mut harness = Harness::new(&gmail, &[INBOX, CLIENTS, SEED]);

    harness.pass();

    for label in [INBOX, CLIENTS, SEED] {
        assert!(harness.held(label).contains("m0003"), "placed in {label}");
    }
    assert_eq!(harness.held(INBOX).len(), 10);
    assert_eq!(harness.held(CLIENTS).len(), 1);
    assert!(gmail.read_once());
    assert_eq!(
        gmail.counts(),
        Counts {
            profiles: 1,
            account_lists: 1,
            batches: 1,
            reads: 10,
            ..Default::default()
        },
        "one listing and one batch for the three mailboxes"
    );
}

#[test]
fn archiving_and_labelling_move_placements() {
    let mut gmail = FakeGmail::new(10, |_| vec![INBOX]);
    let mut harness = Harness::new(&gmail, &[INBOX, CLIENTS]);
    harness.pass();
    let before = gmail.counts();

    gmail.relabel("m0001", &[], &[INBOX]);
    gmail.relabel("m0002", &[CLIENTS], &[]);
    gmail.relabel("m0004", &[CLIENTS], &[INBOX]);
    harness.pass();

    let inbox = harness.held(INBOX);
    assert!(!inbox.contains("m0001"), "archived");
    assert!(inbox.contains("m0002"), "labelled, still in the inbox");
    assert!(!inbox.contains("m0004"), "moved");
    assert_eq!(inbox.len(), 8);
    assert_eq!(
        harness.held(CLIENTS),
        BTreeSet::from(["m0002".to_string(), "m0004".to_string()])
    );
    assert_eq!(
        since(&before, &gmail.counts()),
        Counts {
            batches: 1,
            reads: 3,
            histories: 1,
            ..Default::default()
        },
        "one history and the three moved read once"
    );
}

#[test]
fn a_trashed_mail_leaves_its_labels_and_a_deleted_one_goes_unread() {
    let mut gmail = FakeGmail::new(10, |index| match index {
        1 | 2 => vec![INBOX, CLIENTS],
        _ => vec![INBOX],
    });
    let mut harness = Harness::new(&gmail, &[INBOX, CLIENTS, TRASH]);
    harness.pass();
    assert!(harness.held(TRASH).is_empty());
    let before = gmail.counts();

    gmail.relabel("m0001", &[TRASH], &[]);
    gmail.delete("m0002");
    harness.pass();

    assert!(!harness.held(INBOX).contains("m0001"));
    assert!(!harness.held(CLIENTS).contains("m0001"));
    assert_eq!(
        harness.held(TRASH),
        BTreeSet::from(["m0001".to_string()]),
        "in the trash alone"
    );
    for label in [INBOX, CLIENTS, TRASH] {
        assert!(!harness.held(label).contains("m0002"), "deleted");
    }
    assert_eq!(gmail.reads_of("m0002"), 1, "never read after its deletion");
    assert_eq!(since(&before, &gmail.counts()).reads, 1, "the trashed one");
}

#[test]
fn a_quiet_pass_is_one_history_request() {
    let gmail = FakeGmail::seeded(400);
    let mut harness = Harness::new(&gmail, &[INBOX, SENT, SEED, MOA]);
    harness.pass();
    let before = gmail.counts();

    harness.pass();

    assert_eq!(
        since(&before, &gmail.counts()),
        Counts {
            histories: 1,
            ..Default::default()
        },
        "four mailboxes, one history"
    );
}

// NOTE: the seeded test account of 2026-10-07: 400 messages, every one
// under `moa-seed` beside the inbox, the sent mail or a `Seed/…` label.
// Per label, every message is read once per label holding it and each
// label listed on its own; the account listing reads each once a run.
#[test]
fn an_account_listing_reads_each_message_once_a_run() {
    let gmail = FakeGmail::seeded(400);
    let labels = [INBOX, SENT, SEED, MOA];
    let mut harness = Harness::new(&gmail, &labels);

    // NOTE: the first sync: the account's 50 newest, and the inbox's own
    // 50 since only 25 of those are in it.
    harness.pass();
    let first = gmail.counts();
    assert_eq!(harness.held(INBOX).len(), 50);
    assert_eq!(harness.held(MOA).len(), 50);
    assert_eq!(
        first,
        Counts {
            profiles: 1,
            account_lists: 2,
            label_lists: 2,
            batches: 4,
            reads: 76,
            histories: 0,
        }
    );

    // NOTE: the fill takes the whole account on one run, which reads
    // again the 28 messages of the first run its band reaches: each run
    // empties its envelopes.
    harness.fill(500);
    let whole = gmail.counts();
    assert_eq!(harness.held(INBOX).len(), 200);
    assert_eq!(harness.held(SENT).len(), 100);
    assert_eq!(harness.held(SEED).len(), 100);
    assert_eq!(harness.held(MOA).len(), 400);
    assert_eq!(
        since(&first, &whole),
        Counts {
            profiles: 1,
            account_lists: 2,
            label_lists: 0,
            batches: 15,
            reads: 352,
            histories: 0,
        }
    );

    harness.pass();
    assert_eq!(
        since(&whole, &gmail.counts()),
        Counts {
            histories: 1,
            ..Default::default()
        }
    );

    let per_label: usize = labels.iter().map(|label| harness.held(label).len()).sum();
    assert_eq!(per_label, 800, "what a sync per label reads");
    assert_eq!(gmail.counts().reads, 428, "400 and the 28 read again");
}

#[test]
fn an_inbox_behind_the_account_reaches_its_own_fifty() {
    let gmail = FakeGmail::new(200, |index| match index % 4 {
        0 => vec![INBOX],
        _ => vec![SENT],
    });
    let run = GmailRun::default();

    let inbox = floor(&mut gmail.clone(), &run, INBOX, None, FIRST_CHUNK).unwrap();
    let sent = floor(&mut gmail.clone(), &run, SENT, None, FIRST_CHUNK).unwrap();
    assert_eq!(inbox.floor, Some(days_back(196)), "the 50th inbox mail");
    assert_eq!(sent.floor, Some(days_back(49)), "the account's 50th");

    let mut harness = Harness::new(&gmail, &[INBOX, SENT]);
    harness.pass();
    assert_eq!(harness.held(INBOX).len(), 50);
    assert_eq!(harness.held(SENT).len(), 37);
}

#[test]
fn two_threads_on_one_run_read_each_mail_once() {
    for _ in 0..8 {
        let gmail = FakeGmail::seeded(400);
        let run = Arc::new(GmailRun::default());

        let workers: Vec<_> = [INBOX, SENT, SEED, MOA]
            .into_iter()
            .map(|label| {
                let mut gmail = gmail.clone();
                let run = Arc::clone(&run);
                thread::spawn(move || {
                    let reply = floor(&mut gmail, &run, label, None, 500).unwrap();
                    let scope = Scope {
                        since: reply.floor,
                        until: None,
                    };
                    let mut cursor: Option<String> = None;
                    let mut items = 0;
                    loop {
                        let page: MailPage = list_label(
                            &mut gmail,
                            &run,
                            label,
                            &round(cursor.as_deref(), scope.clone()),
                        )
                        .unwrap();
                        items += page.items.len();
                        match page.cursor {
                            Some(next) => cursor = Some(next),
                            None => break items,
                        }
                    }
                })
            })
            .collect();
        let items: Vec<usize> = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .collect();

        assert_eq!(items, [200, 100, 100, 400]);
        assert!(gmail.read_once());
        assert_eq!(gmail.counts().reads, 400);
    }
}

#[test]
fn an_expired_history_falls_back_to_a_round() {
    let mut gmail = FakeGmail::new(20, |_| vec![INBOX]);
    let mut harness = Harness::new(&gmail, &[INBOX]);
    harness.pass();
    let before = gmail.counts();

    gmail.delete("m0001");
    gmail.add("new", -1, &[INBOX]);
    gmail.expire();
    harness.pass();

    let inbox = harness.held(INBOX);
    assert!(!inbox.contains("m0001"));
    assert!(inbox.contains("new"));
    assert_eq!(inbox.len(), 20);
    assert_eq!(
        since(&before, &gmail.counts()),
        Counts {
            profiles: 1,
            account_lists: 1,
            batches: 1,
            reads: 20,
            histories: 1,
            ..Default::default()
        },
        "a history refused, then the account listed again"
    );

    let before = gmail.counts();
    harness.pass();
    assert_eq!(
        since(&before, &gmail.counts()),
        Counts {
            histories: 1,
            ..Default::default()
        },
        "the round's checkpoint replays"
    );
}

// NOTE: a round begun before the account listing left a label's bare page
// token as its cursor; it resumes on the label.
#[test]
fn a_bare_old_cursor_is_read_as_a_labels() {
    let mut gmail = FakeGmail::new(30, |index| match index % 2 {
        1 => vec![INBOX, CLIENTS],
        _ => vec![INBOX],
    });
    gmail.lock().page = 5;
    let run = GmailRun::default();

    let page = list_label(
        &mut gmail,
        &run,
        CLIENTS,
        &round(Some("5"), Scope::default()),
    )
    .unwrap();

    let handles: Vec<String> = page.items.into_iter().map(|item| item.handle).collect();
    let expected: Vec<String> = [11, 13, 15, 17, 19].into_iter().map(id).collect();
    assert_eq!(handles, expected, "the label's second page");
    assert_eq!(page.cursor.as_deref(), Some("l:10"));
    assert_eq!(page.checkpoint, None, "a resumed round hands none");
    let counts = gmail.counts();
    assert_eq!((counts.account_lists, counts.label_lists), (0, 1));
}

/// The instant `hours` before the account's newest message.
fn hours_back(hours: i64) -> String {
    let top: Timestamp = "2026-10-01T12:00:00Z".parse().unwrap();
    top.checked_sub(SignedDuration::from_hours(hours))
        .unwrap()
        .strftime("%Y-%m-%dT%H:%M:%SZ")
        .to_string()
}

// NOTE: a busy account, a message an hour: the two days of margin the
// listing's `after:` takes below the first chunk's floor hold 48 more
// messages, all dated below it. The listing stops a batch past the floor
// rather than reading them, but reads that batch whole, so a message
// dated in the scope and received below the floor still lands.
#[test]
fn a_first_chunk_stops_reading_a_batch_past_its_floor() {
    let gmail = FakeGmail::new(200, |_| vec![INBOX]);
    {
        let mut account = gmail.lock();
        for (index, mail) in account.mails.values_mut().enumerate() {
            mail.date = hours_back(index as i64);
            mail.received = hours_back(index as i64);
        }
        // NOTE: a sender's clock six hours ahead.
        account.mails.get_mut(&id(55)).unwrap().date = hours_back(49);
    }
    let mut harness = Harness::new(&gmail, &[INBOX]);

    harness.pass();

    let held = harness.held(INBOX);
    assert_eq!(held.len(), 51, "the 50 newest and the one dated among them");
    assert!(held.contains(&id(55)));
    assert_eq!(harness.since(INBOX), Some(Some(hours_back(49))));
    let counts = gmail.counts();
    assert_eq!(
        (counts.batches, counts.reads),
        (3, 75),
        "the floor's two batches and one past it, not the 98 the margin lists"
    );
}
