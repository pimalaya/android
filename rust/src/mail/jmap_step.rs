//! # JMAP steps
//!
//! A JMAP account stepped through as one account (RFC 8621).
//!
//! A message filed in several mailboxes is one `Email` with one id, as a
//! Gmail message under several labels is one message: so the account is
//! one query, each `Email` read once and placed in every mailbox its
//! `mailboxIds` names, rather than a listing per mailbox.
//!
//! A step ([`JmapCalls::step`]) is one `Email/query` over the account less
//! the trash and the junk (`inMailboxOtherThan`), or over the one mailbox a
//! filtered view shows, sorted by `sentAt` (the `Date` header) where the
//! server sorts by it, its `Email/get` back-referenced.
//!
//! A step starts where the last stopped: the ids that query reached last are
//! its anchors, and it asks for what follows the first (RFC 8620 §5.5,
//! `anchorOffset` 1). An anchor gone is `anchorNotFound`: the next is tried,
//! then a received-date paging below the frontier.
//!
//! Every `Email` the store does not hold is downloaded by its blob (RFC
//! 8620 §6.2), one after another on the session's connection, into a file.
//!
//! A pass after the first replays `Email/changes` once per pool run and
//! start state, whatever number of mailboxes it projects onto
//! ([`JmapRun`]): the touched emails are read once, and each mailbox takes
//! the ones its id is in `mailboxIds` of.

use std::{
    cmp::Reverse,
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    sync::{Arc, Condvar, Mutex, MutexGuard, Weak},
};

use io_jmap::rfc8621::email::{JmapEmail, query::JmapEmailFilter};
use jiff::{SignedDuration, Timestamp};

use crate::{
    client::{Host, jmap::JmapCalls},
    error::BridgeError,
    mail::{
        listing::{Named, RECEIVED_MARGIN_DAYS},
        source_path,
    },
};

/// How many anchors a step answers: the ids its query reached last.
pub const ANCHORS: usize = 5;

/// The most queries one step sends, so a stretch the store holds whole
/// cannot hold it.
pub const QUERIES: usize = 8;

/// How much older than its reception a message's `Date` may be and still
/// anchor a step paged by reception.
///
/// Past it, an import or a wrong clock, far from its neighbours in the
/// `sentAt` order.
const SKEW_SECONDS: i64 = 24 * 60 * 60;

/// Where one query starts.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Start {
    /// From the newest.
    Top,
    /// Right after this id.
    After(String),
    /// From this position, paging by reception.
    At(u64),
}

/// One `Email/query` of a step.
#[derive(Clone, Debug)]
pub struct StepQuery {
    pub filter: JmapEmailFilter,
    /// Sorted by `receivedAt` rather than `sentAt`.
    pub received: bool,
    pub start: Start,
    pub limit: u64,
}

/// What one step is asked.
#[derive(Debug)]
pub struct StepRequest<'r> {
    /// The one mailbox a view filtered to it steps, by id.
    pub mailbox: Option<&'r str>,
    /// The mailboxes the account's step leaves out: the trash, the junk.
    pub excluded: Vec<String>,
    /// Whether the server sorts by `sentAt` (`emailQuerySortOptions`).
    pub sent_at: bool,
    /// The ids the last step reached last, oldest first.
    pub anchors: &'r [String],
    /// The account's frontier (RFC 3339), where a step with no anchor
    /// left pages from; [`None`] for the first step.
    pub frontier: Option<&'r str>,
    pub count: usize,
}

/// One message a step brought, its source in a file.
#[derive(Debug)]
pub struct Brought {
    pub email: JmapEmail,
    pub path: PathBuf,
    pub size: usize,
}

/// What one step brought.
///
/// The `Email` state read before it, the messages downloaded, whether
/// nothing older is left, and where the next step starts.
#[derive(Debug)]
pub struct Stepped {
    pub state: String,
    pub brought: Vec<Brought>,
    pub exhausted: bool,
    pub anchors: Vec<String>,
    /// Whether the step walked by `receivedAt` rather than `sentAt`.
    pub by_reception: bool,
}

/// One message of a step as the bridge answers it.
///
/// Named as a listing names it, with the ids of the mailboxes it is filed
/// in, its file, and when it was received (milliseconds since the epoch).
pub struct StepItem {
    pub named: Named,
    pub mailboxes: Vec<String>,
    pub path: PathBuf,
    pub size: usize,
    pub received: Option<i64>,
}

/// A step's answer to the bridge ([`Stepped`], its messages named).
pub struct JmapStepped {
    pub state: String,
    pub items: Vec<StepItem>,
    pub exhausted: bool,
    pub anchors: Vec<String>,
    pub by_reception: bool,
}

/// What a read of some messages brought ([`JmapCalls::sources`]).
#[derive(Debug, Default, Eq, PartialEq)]
pub struct Sources {
    /// The messages read, each written to its file.
    pub fetched: Vec<Source>,
    /// The ids `Email/get` found no email under (`notFound`).
    pub gone: Vec<String>,
}

/// One message read whole into a file.
#[derive(Debug, Eq, PartialEq)]
pub struct Source {
    pub id: String,
    pub path: PathBuf,
    pub size: usize,
}

/// A JMAP account stepped through, over the session its calls read once.
impl<H: Host> JmapCalls<'_, '_, H> {
    /// About `count` unheld messages older than the anchors, whole, into `dir`.
    ///
    /// A window held whole moves on to the next, at most [`QUERIES`] a step,
    /// the store asked once per window. A body that fails ends the anchors
    /// before it, for the next step to read; an expired token fails the step.
    pub fn step(&mut self, request: &StepRequest, dir: &Path) -> Result<Stepped, BridgeError> {
        let state = self.email_state()?;

        let mut filter = JmapEmailFilter::default();
        match request.mailbox {
            Some(mailbox) => filter.in_mailbox = Some(mailbox.to_string()),
            None if !request.excluded.is_empty() => {
                filter.in_mailbox_other_than = Some(request.excluded.clone());
            }
            None => {}
        }
        let mut query = StepQuery {
            filter,
            received: !request.sent_at,
            start: Start::Top,
            limit: request.count as u64,
        };

        let mut window = None;
        for anchor in request.anchors {
            query.start = Start::After(anchor.clone());
            window = self.step_query(&query)?;
            if window.is_some() {
                break;
            }
            log::debug!("jmap step anchor {anchor} not found");
        }
        let paged = window.is_none() && (!request.anchors.is_empty() || request.frontier.is_some());
        if paged {
            query.received = true;
            query.start = Start::At(0);
            query.filter.before = request.frontier.and_then(above);
        }
        let mut window = match window {
            Some(window) => window,
            None => self.step_query(&query)?.unwrap_or_default(),
        };

        let mut trail: Vec<(String, bool)> = Vec::new();
        let mut fresh: Vec<JmapEmail> = Vec::new();
        let mut queries = 1;
        let exhausted = loop {
            sort(&mut window, query.received);
            let full = window.len() as u64 >= query.limit;
            let listed = window.len() as u64;
            let ids: Vec<String> = window.iter().filter_map(|email| email.id.clone()).collect();
            let held = self.jmap.client.held(&ids)?;
            for email in window {
                let Some(id) = email.id.clone() else {
                    continue;
                };
                trail.push((id.clone(), timely(&email)));
                if !held.contains(&id) && !fresh.iter().any(|kept| kept.id == email.id) {
                    fresh.push(email);
                }
            }
            if !full {
                break true;
            }
            if fresh.len() >= request.count || queries >= QUERIES {
                break false;
            }

            query.limit = (request.count - fresh.len()) as u64;
            query.start = match &query.start {
                Start::At(position) => Start::At(position + listed),
                _ => match trail.last() {
                    Some((last, _)) => Start::After(last.clone()),
                    None => break false,
                },
            };
            queries += 1;
            window = match self.step_query(&query)? {
                Some(window) => window,
                None => break false,
            };
        };

        let mut brought = Vec::with_capacity(fresh.len());
        let mut failed: Option<String> = None;
        for email in fresh {
            let id = email.id.clone().unwrap_or_default();
            let read = match email.blob_id.as_deref() {
                Some(blob) => self.download_blob(blob),
                None => Err(format!("No blob for `{id}`").into()),
            };
            let bytes = match read {
                Ok(bytes) => bytes,
                Err(err) if err.status == Some(401) => return Err(err),
                Err(err) => {
                    log::warn!("jmap step: source of {id} not read: {err}");
                    failed.get_or_insert(id);
                    continue;
                }
            };
            let path = source_path(dir, &id);
            fs::write(&path, &bytes).map_err(|err| format!("Could not write `{id}`: {err}"))?;
            brought.push(Brought {
                email,
                path,
                size: bytes.len(),
            });
        }

        // NOTE: the anchors stop before a body that failed, so the next
        // step reaches it again. Paging a `sentAt` step by reception, an
        // import is no anchor: its `Date` is far from where `sentAt` files it.
        let reached = match &failed {
            Some(failed) => trail
                .iter()
                .position(|(id, _)| id == failed)
                .unwrap_or(trail.len()),
            None => trail.len(),
        };
        let mut anchors: Vec<String> = trail[..reached]
            .iter()
            .rev()
            .filter(|(_, timely)| *timely || !(paged && request.sent_at))
            .take(ANCHORS)
            .map(|(id, _)| id.clone())
            .collect();
        if anchors.is_empty() {
            anchors = request.anchors.to_vec();
        }

        Ok(Stepped {
            state,
            brought,
            exhausted: exhausted && failed.is_none(),
            anchors,
            by_reception: query.received,
        })
    }

    /// Some messages read whole into files of `dir`, `chunk` ids an
    /// `Email/get`, their blobs downloaded one after another.
    ///
    /// A message whose blob fails is left out, one gone too (named in
    /// [`Sources::gone`]); an expired token fails the read.
    pub fn sources(
        &mut self,
        ids: &[String],
        chunk: usize,
        dir: &Path,
    ) -> Result<Sources, BridgeError> {
        let mut read = Vec::with_capacity(ids.len());
        let mut gone = Vec::new();
        for ids in ids.chunks(chunk.max(1)) {
            let emails = self.email_blobs(ids.to_vec())?;
            gone.extend(
                ids.iter()
                    .filter(|id| !emails.iter().any(|email| email.id.as_ref() == Some(*id)))
                    .cloned(),
            );
            for email in emails {
                let (Some(id), Some(blob)) = (email.id, email.blob_id) else {
                    continue;
                };
                let bytes = match self.download_blob(&blob) {
                    Ok(bytes) => bytes,
                    Err(err) if err.status == Some(401) => return Err(err),
                    Err(err) => {
                        log::warn!("source of {id} not read: {err}");
                        continue;
                    }
                };
                let path = source_path(dir, &id);
                fs::write(&path, &bytes).map_err(|err| format!("Could not write `{id}`: {err}"))?;
                read.push(Source {
                    id,
                    path,
                    size: bytes.len(),
                });
            }
        }
        Ok(Sources {
            fetched: read,
            gone,
        })
    }
}

/// The `receivedAt` bound a step paged by reception lists below: the
/// frontier with [`RECEIVED_MARGIN_DAYS`] of margin above it.
fn above(frontier: &str) -> Option<String> {
    let frontier: Timestamp = frontier.parse().ok()?;
    let margin = SignedDuration::from_hours(24 * RECEIVED_MARGIN_DAYS);
    let until = frontier.checked_add(margin).ok()?;
    Some(until.strftime("%Y-%m-%dT%H:%M:%SZ").to_string())
}

/// Sorts a window newest first by the query's own order, whatever order
/// `Email/get` listed it in; an email without the date last.
fn sort(window: &mut [JmapEmail], received: bool) {
    window.sort_by_key(|email| {
        let date = match received {
            true => email.received_at.as_deref(),
            false => email.sent_at.as_deref(),
        };
        Reverse(date.and_then(|date| date.parse::<Timestamp>().ok()))
    });
}

/// Whether an email's `Date` lies within a day of its reception, or
/// either is unknown.
fn timely(email: &JmapEmail) -> bool {
    let instant = |raw: Option<&str>| raw.and_then(|raw| raw.parse::<Timestamp>().ok());
    match (
        instant(email.sent_at.as_deref()),
        instant(email.received_at.as_deref()),
    ) {
        (Some(sent), Some(received)) => received.as_second() - sent.as_second() <= SKEW_SECONDS,
        _ => true,
    }
}

/// When an email was received, in milliseconds since the epoch.
pub fn received(email: &JmapEmail) -> Option<i64> {
    let received: Timestamp = email.received_at.as_deref()?.parse().ok()?;
    Some(received.as_millisecond())
}

/// What `Email/changes` answered from one state.
///
/// The state it reached, the emails created or updated as read since, and
/// the ids gone for good (destroyed, or no longer found when read).
#[derive(Debug)]
pub struct JmapDelta {
    pub next: String,
    pub emails: Vec<JmapEmail>,
    pub gone: Vec<String>,
}

/// What one pool run read of a JMAP account's changes, shared by its
/// workers' sessions.
///
/// Each replay is keyed by the state it starts from, read by whichever
/// worker asks first while the others wait for it.
#[derive(Default)]
pub struct JmapRun {
    deltas: Mutex<HashMap<String, Slot>>,
    ready: Condvar,
}

/// One replay, being read or read; [`None`] when the server can no longer
/// compute changes from its state.
enum Slot {
    Pending,
    Done(Option<Arc<JmapDelta>>),
}

impl JmapRun {
    fn lock(&self) -> MutexGuard<'_, HashMap<String, Slot>> {
        self.deltas.lock().unwrap_or_else(|err| err.into_inner())
    }

    /// The changes since `state`, read by `read` once per run. A failure
    /// is not kept: the next asker reads again.
    pub fn delta(
        &self,
        state: &str,
        read: impl FnOnce() -> Result<Option<JmapDelta>, BridgeError>,
    ) -> Result<Option<Arc<JmapDelta>>, BridgeError> {
        let mut deltas = self.lock();
        loop {
            match deltas.get(state) {
                Some(Slot::Done(delta)) => return Ok(delta.clone()),
                Some(Slot::Pending) => {
                    deltas = self
                        .ready
                        .wait(deltas)
                        .unwrap_or_else(|err| err.into_inner());
                }
                None => break,
            }
        }
        deltas.insert(state.to_string(), Slot::Pending);
        drop(deltas);

        let read = read();

        let mut deltas = self.lock();
        let answer = match read {
            Ok(delta) => {
                let delta = delta.map(Arc::new);
                deltas.insert(state.to_string(), Slot::Done(delta.clone()));
                Ok(delta)
            }
            Err(err) => {
                deltas.remove(state);
                Err(err)
            }
        };
        drop(deltas);
        self.ready.notify_all();
        answer
    }
}

/// The run every session of one pool run of an account shares.
///
/// Keyed by the account (its base URL and login) and the run's number; a
/// run of 0 is a session's own, shared with nobody.
pub fn join(account: &str, run: i64) -> Arc<JmapRun> {
    static RUNS: Mutex<Vec<(String, i64, Weak<JmapRun>)>> = Mutex::new(Vec::new());

    if run == 0 {
        return Arc::default();
    }
    let mut runs = RUNS.lock().unwrap_or_else(|err| err.into_inner());
    runs.retain(|(_, _, held)| held.strong_count() > 0);
    let found = runs
        .iter()
        .find(|(joined, number, _)| joined == account && *number == run)
        .and_then(|(_, _, held)| held.upgrade());
    if let Some(found) = found {
        return found;
    }
    let fresh = Arc::new(JmapRun::default());
    runs.push((account.to_string(), run, Arc::downgrade(&fresh)));
    fresh
}

#[cfg(test)]
mod tests;
