//! Gmail stepped through over a fake Gmail account, driven by io-pimdir's
//! own sync engine and store: a step's messages read once and landed in
//! every label they carry, the history replayed once a pass, a round over
//! a label naming its ids by their markers alone.
//!
//! The fake answers the requests of [`GmailSource`] the way Gmail does
//! where it matters here: `messages.list` newest received first, a page
//! of 500 ids, the account's leaving spam and trash out, narrowed by
//! `after:`; metadata read by batch; whole messages written to files; a
//! history naming every message moved since a history id, the ones
//! deleted for good apart, until Gmail no longer holds it.

use std::{
    collections::{BTreeMap, BTreeSet, HashSet},
    fs,
    path::Path,
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

use super::{GmailRun, GmailSource, Stepped, list_label, step};
use crate::{
    client::{
        gmail::{
            BATCH, Fetched, GmailEnvelope, GmailHistoryDelta, GmailIds, INBOX, SPAM, STARRED,
            TRASH, UNREAD, belongs, source_path,
        },
        listing::{Listing, MailPage, MailRequest, Named, Scope, flags},
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
    /// Metadata batches.
    batches: usize,
    /// Metadata reads, all batches counted.
    reads: usize,
    /// Messages read whole.
    fetched: usize,
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
    /// Whole reads by message.
    fetches: BTreeMap<String, usize>,
    /// The `after:` of every label listing.
    afters: Vec<Option<i64>>,
    /// What the store holds, as a step asks it ([`GmailSource::held`]).
    held: HashSet<String>,
}

/// One session's view of the account, which every session shares.
#[derive(Clone)]
struct FakeGmail(Arc<Mutex<Account>>);

/// The instant `days` before the account's newest message.
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

fn millis(instant: &str) -> i64 {
    instant.parse::<Timestamp>().unwrap().as_millisecond()
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

    /// The seeded test account: every message under [`MOA`], beside the
    /// inbox (one in two), the sent mail or [`SEED`].
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

    /// Whether no message was read whole twice.
    fn fetched_once(&self) -> bool {
        self.lock().fetches.values().all(|count| *count == 1)
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

    fn summary(id: &str, mail: &Mail) -> PimdirMailSummary {
        PimdirMailSummary {
            message_id: Some(format!("{id}@example.org")),
            subject: format!("Subject {id}"),
            date: Some(mail.date.clone()),
            ..Default::default()
        }
    }
}

impl GmailSource for FakeGmail {
    fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError> {
        let account = self.lock();
        Ok(ids
            .iter()
            .filter(|id| account.held.contains(*id))
            .cloned()
            .collect())
    }

    fn history_id(&mut self) -> Result<String, BridgeError> {
        let mut account = self.lock();
        account.counts.profiles += 1;
        Ok(account.log.len().to_string())
    }

    fn list(
        &mut self,
        label: Option<&str>,
        since: Option<i64>,
        page: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        let mut account = self.lock();
        match label {
            None => account.counts.account_lists += 1,
            Some(_) => {
                account.counts.label_lists += 1;
                account.afters.push(since);
            }
        }

        let spam_trash = label.is_some_and(|label| [TRASH, SPAM, UNREAD, STARRED].contains(&label));
        let mut matching: Vec<(&String, &Mail)> = account
            .mails
            .iter()
            .filter(|(_, mail)| {
                let has = |id: &str| mail.labels.iter().any(|carried| carried == id);
                label.is_none_or(&has) && (spam_trash || !(has(TRASH) || has(SPAM)))
            })
            .filter(|(_, mail)| since.is_none_or(|since| millis(&mail.received) / 1000 >= since))
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

        Ok(ids
            .iter()
            .map(|id| {
                let envelope = account.mails.get(id).map(|mail| {
                    let has = |label: &str| mail.labels.iter().any(|carried| carried == label);
                    GmailEnvelope {
                        labels: mail.labels.clone(),
                        flags: flags(!has(UNREAD), false, has(STARRED)),
                        summary: Self::summary(id, mail),
                    }
                });
                (id.clone(), envelope)
            })
            .collect())
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

    fn fetch(&mut self, ids: &[String], dir: &Path) -> Result<Vec<Fetched>, BridgeError> {
        let mut account = self.lock();
        let mut fetched = Vec::new();
        for id in ids {
            account.counts.fetched += 1;
            *account.fetches.entry(id.clone()).or_default() += 1;
            let Some(mail) = account.mails.get(id) else {
                continue;
            };
            let path = source_path(dir, id);
            let bytes = format!("Subject: Subject {id}\r\n\r\n{id}\r\n");
            fs::write(&path, &bytes).unwrap();
            let has = |label: &str| mail.labels.iter().any(|carried| carried == label);
            fetched.push(Fetched {
                id: id.clone(),
                path,
                size: bytes.len(),
                labels: mail.labels.clone(),
                flags: flags(!has(UNREAD), false, has(STARRED)),
                received: Some(millis(&mail.received)),
                summary: Self::summary(id, mail),
            });
        }
        Ok(fetched)
    }
}

/// What the engine's remote answers: a label's page as Java forwards the
/// request, or a step's messages landed as `MailEngine.land` lands them.
enum Answering<'r> {
    /// A pass: the label's delta or round, from the run.
    Pass(&'r GmailRun),
    /// A step's members of the label: the round that opens the mailbox
    /// when it holds nothing, else a delta adding them, its checkpoint
    /// left where it is.
    Land {
        items: Vec<Named>,
        checkpoint: String,
        empty: bool,
    },
}

struct Remote<'r> {
    gmail: FakeGmail,
    answering: Answering<'r>,
    /// The handles the label holds, so a round's members it holds not are
    /// dropped as Java's naming drops them (no arrival here).
    held: BTreeSet<String>,
}

impl PimdirRemote for Remote<'_> {
    type Error = String;

    fn enumerate(
        &mut self,
        collection: &PimdirCollectionId,
        request: PimdirEnumerate,
    ) -> Result<PimdirEnumerated, String> {
        let round = matches!(request.listing, PimdirListing::Round { .. });
        let page = match &mut self.answering {
            Answering::Land {
                items,
                checkpoint,
                empty,
            } => match round && *empty {
                true => MailPage::round(std::mem::take(items), None, Some(checkpoint.clone())),
                false => MailPage {
                    items: std::mem::take(items),
                    last: true,
                    ..Default::default()
                },
            },
            Answering::Pass(run) => {
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
                    received: None,
                };
                let mut page = list_label(&mut self.gmail, run, collection.as_str(), &request)
                    .map_err(|err| err.message)?;
                if page.complete {
                    page.items.retain(|item| self.held.contains(&item.handle));
                }
                page
            }
        };
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

/// A store holding one mailbox per label, stepped and synced the way
/// `GmailStep` and `MailEngine` do.
struct Harness {
    dir: TempDir,
    files: TempDir,
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
            files: tempfile::tempdir().unwrap(),
            store,
            gmail: gmail.clone(),
            labels: labels.to_vec(),
        }
    }

    /// One step of `count`, of the account or of one label, landed in
    /// every mailbox its messages belong to.
    fn step(&mut self, label: Option<&str>, count: usize) -> Stepped {
        self.gmail.lock().held = self.held_anywhere();
        let stepped = step(&mut self.gmail, label, count, self.files.path()).unwrap();
        for label in self.labels.clone() {
            let items: Vec<Named> = stepped
                .messages
                .iter()
                .filter(|fetched| belongs(&fetched.labels, label))
                .map(|fetched| {
                    Named::new(
                        fetched.id.clone(),
                        fetched.flags.clone(),
                        fetched.summary.clone(),
                    )
                })
                .collect();
            let empty = self.held(label).is_empty();
            let answering = Answering::Land {
                items,
                checkpoint: stepped.history_id.clone(),
                empty,
            };
            self.sync(label, answering);
        }
        stepped
    }

    /// One pass over every mailbox on one run.
    fn pass(&mut self) {
        let run = GmailRun::default();
        for label in self.labels.clone() {
            self.sync(label, Answering::Pass(&run));
        }
    }

    fn sync(&mut self, label: &str, answering: Answering<'_>) {
        let opts = PimdirSyncOptions {
            scope: PimdirScope::unbounded(),
            ..Default::default()
        };
        let mut remote = Remote {
            gmail: self.gmail.clone(),
            answering,
            held: self.held(label),
        };
        self.store.sync(label, opts, &mut remote).unwrap();
    }

    /// Whether a mailbox's last round closed over everything.
    fn covered(&self, label: &str) -> bool {
        let reader = PimdirReader::open(self.dir.path()).unwrap();
        reader
            .list_coverage(label)
            .unwrap()
            .into_iter()
            .find(|coverage| coverage.source == SOURCE)
            .and_then(|coverage| coverage.coverage)
            .is_some_and(|covered| covered.scope.since.is_none())
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

    /// What the account holds in any mailbox: what a step skips.
    fn held_anywhere(&self) -> HashSet<String> {
        self.labels
            .iter()
            .flat_map(|label| self.held(label))
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
        fetched: after.fetched - before.fetched,
        histories: after.histories - before.histories,
    }
}

#[test]
fn a_first_step_reads_fifty_once_and_lands_them_in_every_label() {
    let gmail = FakeGmail::seeded(400);
    let labels = [INBOX, SENT, SEED, MOA, TRASH];
    let mut harness = Harness::new(&gmail, &labels);

    let stepped = harness.step(None, 50);

    assert!(!stepped.exhausted);
    assert_eq!(harness.held(MOA).len(), 50, "every message is under it");
    assert_eq!(harness.held(INBOX).len(), 26);
    assert_eq!(harness.held(SENT).len(), 12);
    assert_eq!(harness.held(SEED).len(), 12);
    assert!(gmail.fetched_once());
    assert_eq!(
        gmail.counts(),
        Counts {
            profiles: 1,
            account_lists: 1,
            fetched: 50,
            ..Default::default()
        },
        "one listing, the fifty read whole, no metadata read"
    );
    for label in labels {
        assert!(harness.covered(label), "{label} opened, the trash included");
    }

    // NOTE: every mailbox holds its checkpoint: the next pass replays the
    // history, once for the five.
    let before = gmail.counts();
    harness.pass();
    assert_eq!(
        since(&before, &gmail.counts()),
        Counts {
            histories: 1,
            ..Default::default()
        }
    );
}

#[test]
fn a_step_takes_the_ids_not_held_whatever_their_dates() {
    // NOTE: an imported account: received newest first one a day, dated
    // anywhere in the last ten years.
    let gmail = FakeGmail::new(150, |_| vec![INBOX]);
    {
        let mut account = gmail.lock();
        for (index, mail) in account.mails.values_mut().enumerate() {
            mail.date = days_back(((index * 7919) % 3650) as i64);
        }
    }
    let mut harness = Harness::new(&gmail, &[INBOX]);
    harness.step(None, 50);
    // NOTE: a message stored from a search, further down the listing.
    harness
        .store
        .sync(
            INBOX,
            PimdirSyncOptions::default(),
            &mut Remote {
                gmail: gmail.clone(),
                answering: Answering::Land {
                    items: vec![Named::new(id(60), Vec::new(), PimdirMailSummary::default())],
                    checkpoint: String::new(),
                    empty: false,
                },
                held: BTreeSet::new(),
            },
        )
        .unwrap();

    let stepped = harness.step(None, 50);

    let taken: Vec<String> = stepped.messages.iter().map(|m| m.id.clone()).collect();
    let expected: Vec<String> = (50..=100).filter(|index| *index != 60).map(id).collect();
    assert_eq!(taken, expected);
    assert_eq!(harness.held(INBOX).len(), 101);
    assert!(gmail.fetched_once());
}

#[test]
fn the_last_step_says_the_account_is_exhausted() {
    let gmail = FakeGmail::new(70, |_| vec![INBOX]);
    let mut harness = Harness::new(&gmail, &[INBOX]);

    assert!(!harness.step(None, 50).exhausted);
    let last = harness.step(None, 50);
    assert_eq!(last.messages.len(), 20);
    assert!(last.exhausted);
    let empty = harness.step(None, 50);
    assert!(empty.messages.is_empty() && empty.exhausted);
    assert_eq!(gmail.counts().fetched, 70, "nothing read twice");
}

#[test]
fn spam_and_trash_are_never_stepped_and_a_label_view_steps_its_label() {
    let gmail = FakeGmail::new(40, |index| match index % 4 {
        0 => vec![INBOX, CLIENTS],
        1 => vec![SPAM],
        2 => vec![TRASH],
        _ => vec![INBOX],
    });
    let mut harness = Harness::new(&gmail, &[INBOX, CLIENTS, SPAM, TRASH]);

    let stepped = harness.step(Some(CLIENTS), 5);
    let taken: Vec<String> = stepped.messages.iter().map(|m| m.id.clone()).collect();
    assert_eq!(taken, [id(0), id(4), id(8), id(12), id(16)]);
    assert_eq!(harness.held(INBOX).len(), 5, "placed beside the label");

    harness.step(None, 100);
    assert_eq!(harness.held(INBOX).len(), 20);
    assert!(harness.held(SPAM).is_empty());
    assert!(harness.held(TRASH).is_empty());
}

#[test]
fn archiving_and_labelling_move_placements() {
    let mut gmail = FakeGmail::new(10, |_| vec![INBOX]);
    let mut harness = Harness::new(&gmail, &[INBOX, CLIENTS]);
    harness.step(None, 50);
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
        "one history and the three moved read once, in one batch"
    );
}

#[test]
fn a_trashed_mail_leaves_its_labels_and_a_deleted_one_goes_unread() {
    let mut gmail = FakeGmail::new(10, |index| match index {
        1 | 2 => vec![INBOX, CLIENTS],
        _ => vec![INBOX],
    });
    let mut harness = Harness::new(&gmail, &[INBOX, CLIENTS, TRASH]);
    harness.step(None, 50);
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
    assert_eq!(since(&before, &gmail.counts()).reads, 1, "the trashed one");
}

#[test]
fn an_expired_history_lists_a_labels_ids_by_their_markers_alone() {
    let mut gmail = FakeGmail::new(20, |index| match index {
        3 => vec![INBOX, UNREAD],
        4 => vec![INBOX, STARRED],
        _ => vec![INBOX],
    });
    let mut harness = Harness::new(&gmail, &[INBOX]);
    harness.step(None, 50);
    let before = gmail.counts();

    gmail.delete("m0001");
    gmail.expire();
    harness.pass();

    let inbox = harness.held(INBOX);
    assert!(!inbox.contains("m0001"), "found absent");
    assert_eq!(inbox.len(), 19);
    assert_eq!(
        since(&before, &gmail.counts()),
        Counts {
            profiles: 1,
            label_lists: 3,
            histories: 1,
            ..Default::default()
        },
        "a history refused, then the label and the two markers listed, nothing read"
    );

    // NOTE: the page as the engine reads it: each id named by its markers.
    let run = GmailRun::default();
    let request = MailRequest {
        listing: Listing::Round {
            cursor: None,
            band: false,
        },
        scope: Scope::default(),
        covered: true,
        received: Some(millis(&days_back(19))),
    };
    let page = list_label(&mut gmail.clone(), &run, INBOX, &request).unwrap();
    let marked: BTreeMap<String, Vec<String>> = page
        .items
        .iter()
        .filter(|item| ["m0003", "m0004", "m0005"].contains(&item.handle.as_str()))
        .map(|item| (item.handle.clone(), item.flags.clone()))
        .collect();
    assert_eq!(marked["m0003"], Vec::<String>::new(), "unread");
    assert_eq!(marked["m0004"], ["\\Seen", "\\Flagged"]);
    assert_eq!(marked["m0005"], ["\\Seen"]);
    assert!(page.items.iter().all(|item| item.summary.is_none()));
    assert_eq!(page.checkpoint.as_deref(), Some("1"));
    assert_eq!(
        gmail.lock().afters.last().copied().flatten(),
        Some(millis(&days_back(19)) / 1000 - 1),
        "from the oldest reception held, a second earlier"
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

#[test]
fn two_threads_on_one_run_read_each_moved_mail_once() {
    for _ in 0..8 {
        let mut gmail = FakeGmail::seeded(100);
        let mut harness = Harness::new(&gmail, &[INBOX, SENT, SEED, MOA]);
        harness.step(None, 100);
        let checkpoint = gmail.lock().log.len().to_string();
        for index in 0..40 {
            gmail.relabel(&id(index), &[CLIENTS], &[]);
        }
        let before = gmail.counts();
        let run = Arc::new(GmailRun::default());

        let workers: Vec<_> = [INBOX, SENT, SEED, MOA]
            .into_iter()
            .map(|label| {
                let mut gmail = gmail.clone();
                let run = Arc::clone(&run);
                let checkpoint = checkpoint.clone();
                thread::spawn(move || {
                    let request = MailRequest {
                        listing: Listing::Delta { checkpoint },
                        scope: Scope::default(),
                        covered: true,
                        received: None,
                    };
                    list_label(&mut gmail, &run, label, &request)
                        .unwrap()
                        .items
                        .len()
                })
            })
            .collect();
        let items: Vec<usize> = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .collect();

        assert_eq!(items, [20, 10, 10, 40]);
        let asked = since(&before, &gmail.counts());
        assert_eq!((asked.reads, asked.histories), (40, 1));
    }
}
