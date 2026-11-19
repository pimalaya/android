//! # JMAP reads and runs
//!
//! A JMAP account's messages read whole by their blobs (RFC 8620 §6.2),
//! one after another on the session's connection, into files
//! ([`JmapCalls::sources`]).
//!
//! A pass after the first replays `Email/changes` once per pool run and
//! start state, whatever number of mailboxes it projects onto
//! ([`JmapRun`]): the touched emails are read once, and each mailbox takes
//! the ones its id is in `mailboxIds` of.

use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    sync::{
        Arc, Condvar, Mutex, MutexGuard, Weak,
        atomic::{AtomicU64, Ordering},
    },
};

use io_jmap::rfc8621::email::JmapEmail;

use crate::{
    client::{Host, jmap::JmapCalls},
    error::BridgeError,
    mail::source_path,
};

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

/// A JMAP account's messages read, over the session its calls read once.
impl<H: Host> JmapCalls<'_, '_, H> {
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
/// Each replay is keyed by the state it starts from and the run's epoch,
/// read by whichever worker asks first while the others wait for it.
#[derive(Default)]
pub struct JmapRun {
    deltas: Mutex<HashMap<String, Slot>>,
    ready: Condvar,
    /// Moves once per move the run pushed, so a replay read before it is
    /// read again ([`JmapRun::refiled`]).
    epoch: AtomicU64,
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
        let key = format!("{}\u{0}{state}", self.epoch.load(Ordering::SeqCst));
        let state = key.as_str();
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

    /// Forgets every replay read, after the run filed a message elsewhere:
    /// a mailbox listed after it replays the move, so the target lands the
    /// message in this pass rather than the next.
    pub fn refiled(&self) {
        self.epoch.fetch_add(1, Ordering::SeqCst);
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
