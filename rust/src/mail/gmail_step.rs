//! # Gmail steps
//!
//! Gmail as one account stepped through, its labels rebuilt locally.
//!
//! "All Mail" is no label but every message, each filed once under several
//! labels: the account is stepped through once, each message placed in
//! every mailbox its `labelIds` name (pimdir places one item in several
//! collections), one in trash or spam there alone ([`gmail::belongs`]).
//!
//! A step ([`Gmail::step`]) walks the listing from the top, keeping the ids
//! the store does not hold: a set difference, robust to any date, filling
//! the gaps a failure or a search left. Never a `before:` on `internalDate`
//! or `Date`: an imported message sits far from both.
//!
//! Later passes replay the account's one unfiltered history once, whatever
//! labels it projects onto ([`Gmail::list_label`]). A round over a label
//! (history expired, label new to the store) lists its ids alone, named by
//! their markers ([`Gmail::round`]): never one read per message held.
//!
//! The engine reconciles one mailbox at a time on a pool's sessions, so a
//! pass shares a [`GmailRun`], one per pool run and account, emptied with
//! it: history replays, listing pages and envelopes, each read by the
//! first worker to ask while the others wait.
//!
//! A listing is fresh only from the history id it is replayed from: the
//! run takes `historyId` before reading anything, every round hands it as
//! its checkpoint, and an envelope read before a replay is read again for
//! it. What moves in between is the next delta's, never missed.
//!
//! [`gmail::belongs`]: crate::mail::gmail::belongs

use std::{
    collections::{HashMap, HashSet},
    path::Path,
    sync::{Arc, Condvar, Mutex, MutexGuard, Weak},
};

use crate::{
    client::Host,
    error::BridgeError,
    mail::{
        gmail::{
            BATCH, Fetched, Gmail, GmailEnvelope, GmailHistoryDelta, GmailIds, STARRED, UNREAD,
        },
        listing::{Listing, MailPage, MailRequest, Named, RECEIVED_MARGIN_DAYS, Scope, flags},
    },
};

/// Cursor prefix of a round listing one label.
const LABEL_CURSOR: &str = "l:";

/// What one step brought.
///
/// The history id taken before it listed anything, the messages read
/// whole, and whether the listing ran out before a step's worth.
#[derive(Debug)]
pub struct Stepped {
    pub history_id: String,
    pub messages: Vec<Fetched>,
    pub exhausted: bool,
}

impl<H: Host> Gmail<'_, H> {
    /// About `count` messages the store does not hold, read whole to `dir`.
    ///
    /// Newest first from the top of the account's listing (spam and trash
    /// out) or a label's, asking [`Client::held`] per page: 5 units per 500
    /// ids, the price of a set difference no date can fool.
    ///
    /// [`Client::held`]: crate::client::Client::held
    pub fn step(
        &mut self,
        label: Option<&str>,
        count: usize,
        dir: &Path,
    ) -> Result<Stepped, BridgeError> {
        // NOTE: taken first, so what arrives during the step is the next
        // replay's.
        let history_id = self.history_id()?;

        let mut fresh: Vec<String> = Vec::new();
        let mut token: Option<String> = None;
        let exhausted = loop {
            let page = self.list_page(label, None, token.as_deref())?;
            let held = self.client.held(&page.ids)?;
            for id in page.ids {
                if fresh.len() == count {
                    break;
                }
                if !held.contains(&id) && !fresh.contains(&id) {
                    fresh.push(id);
                }
            }
            if fresh.len() == count {
                break false;
            }
            match page.next {
                Some(next) => token = Some(next),
                None => break true,
            }
        };

        let messages = match fresh.is_empty() {
            true => Vec::new(),
            false => self.sources(&fresh, dir)?.fetched,
        };
        Ok(Stepped {
            history_id,
            messages,
            exhausted,
        })
    }
}

/// What one pool run read of an account, shared by its workers' sessions.
///
/// Each entry is read by one worker while the others wait; an envelope
/// carries the instant its read was claimed, on the run's own clock, so a
/// replay tells one read before it from one read after.
#[derive(Default)]
pub struct GmailRun {
    state: Mutex<State>,
    ready: Condvar,
}

#[derive(Default)]
struct State {
    /// Ticks once per claim and per recorded answer.
    clock: u64,
    /// The history id taken before the run's first round, under key `""`.
    starts: HashMap<String, Slot<String>>,
    /// Listing pages, by label, reception floor and page token.
    pages: HashMap<String, Slot<GmailIds>>,
    /// The unread and starred ids, by reception floor.
    marks: HashMap<String, Slot<Arc<Marks>>>,
    /// History replays by start id; [`None`] when Gmail held none that old.
    histories: HashMap<String, Slot<Option<Arc<GmailHistoryDelta>>>>,
    /// Envelopes by message id.
    envelopes: HashMap<String, Read>,
}

/// The ids carrying the two labels that are markers.
#[derive(Debug, Default)]
struct Marks {
    unread: HashSet<String>,
    starred: HashSet<String>,
}

/// One shared answer, being read or read, with its recording's clock.
enum Slot<T> {
    Pending,
    Done(T, u64),
}

/// One message's envelope, claimed at `at`.
///
/// Being read while `envelope` is [`None`], gone when it holds [`None`].
struct Read {
    at: u64,
    envelope: Option<Option<GmailEnvelope>>,
}

impl GmailRun {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|err| err.into_inner())
    }

    fn wait<'g>(&self, state: MutexGuard<'g, State>) -> MutexGuard<'g, State> {
        self.ready
            .wait(state)
            .unwrap_or_else(|err| err.into_inner())
    }

    /// The answer under `key` in `slots`, read by `fetch` once per run.
    ///
    /// Waited for while another worker reads it. A failure is not kept:
    /// the next asker reads again.
    fn once<T: Clone>(
        &self,
        slots: fn(&mut State) -> &mut HashMap<String, Slot<T>>,
        key: &str,
        fetch: impl FnOnce() -> Result<T, BridgeError>,
    ) -> Result<(T, u64), BridgeError> {
        let mut state = self.lock();
        loop {
            match slots(&mut state).get(key) {
                Some(Slot::Done(value, at)) => return Ok((value.clone(), *at)),
                Some(Slot::Pending) => state = self.wait(state),
                None => break,
            }
        }
        slots(&mut state).insert(key.to_string(), Slot::Pending);
        drop(state);

        let fetched = fetch();

        let mut state = self.lock();
        let answer = match fetched {
            Ok(value) => {
                state.clock += 1;
                let at = state.clock;
                slots(&mut state).insert(key.to_string(), Slot::Done(value.clone(), at));
                Ok((value, at))
            }
            Err(err) => {
                slots(&mut state).remove(key);
                Err(err)
            }
        };
        drop(state);
        self.ready.notify_all();
        answer
    }

    /// The history id, taken once before the run reads anything for a round.
    ///
    /// With the clock reading it was recorded at.
    fn start<H: Host>(&self, gmail: &mut Gmail<'_, H>) -> Result<(String, u64), BridgeError> {
        self.once(|state| &mut state.starts, "", || gmail.history_id())
    }

    /// One listing page, read once per run.
    fn page<H: Host>(
        &self,
        gmail: &mut Gmail<'_, H>,
        label: Option<&str>,
        since: Option<i64>,
        token: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        let key = format!(
            "{}\u{0}{}\u{0}{}",
            label.unwrap_or_default(),
            since.unwrap_or_default(),
            token.unwrap_or_default()
        );
        let (page, _) = self.once(
            |state| &mut state.pages,
            &key,
            || gmail.list_page(label, since, token),
        )?;
        Ok(page)
    }

    /// The unread and starred ids received from `since` on.
    ///
    /// Every page of the two marker labels is read once per run.
    fn marks<H: Host>(
        &self,
        gmail: &mut Gmail<'_, H>,
        since: Option<i64>,
    ) -> Result<Arc<Marks>, BridgeError> {
        let key = since.unwrap_or_default().to_string();
        let (marks, _) = self.once(
            |state| &mut state.marks,
            &key,
            || {
                let mut marks = Marks::default();
                for (label, into) in [(UNREAD, &mut marks.unread), (STARRED, &mut marks.starred)] {
                    let mut token: Option<String> = None;
                    loop {
                        let page = gmail.list_page(Some(label), since, token.as_deref())?;
                        into.extend(page.ids);
                        match page.next {
                            Some(next) => token = Some(next),
                            None => break,
                        }
                    }
                }
                Ok(Arc::new(marks))
            },
        )?;
        Ok(marks)
    }

    /// The account's history from `start`, replayed once per run.
    ///
    /// With the clock reading it was recorded at. The messages it deleted
    /// for good are recorded gone, never read.
    fn history<H: Host>(
        &self,
        gmail: &mut Gmail<'_, H>,
        start: &str,
    ) -> Result<Option<(Arc<GmailHistoryDelta>, u64)>, BridgeError> {
        let (history, at) = self.once(
            |state| &mut state.histories,
            start,
            || Ok(gmail.history(start)?.map(Arc::new)),
        )?;
        let Some(history) = history else {
            return Ok(None);
        };

        let mut state = self.lock();
        for id in &history.deleted {
            state.envelopes.insert(
                id.clone(),
                Read {
                    at,
                    envelope: Some(None),
                },
            );
        }
        Ok(Some((history, at)))
    }

    /// The envelopes of `ids`, each read after the clock reading `after`.
    ///
    /// What the run holds from before is read again. Unclaimed ids are
    /// claimed [`BATCH`] at a time and read, the ones another worker reads
    /// waited for, so a run's workers share the batches.
    fn envelopes<H: Host>(
        &self,
        gmail: &mut Gmail<'_, H>,
        ids: &[String],
        after: u64,
    ) -> Result<HashMap<String, Option<GmailEnvelope>>, BridgeError> {
        loop {
            let mut state = self.lock();
            let mut claimed: Vec<String> = Vec::new();
            let mut waiting = false;
            for id in ids {
                match state.envelopes.get(id) {
                    Some(read) if read.envelope.is_none() => waiting = true,
                    Some(read) if read.at > after => {}
                    _ if claimed.len() < BATCH && !claimed.contains(id) => claimed.push(id.clone()),
                    _ => {}
                }
            }

            if claimed.is_empty() {
                if waiting {
                    drop(self.wait(state));
                    continue;
                }
                return Ok(ids
                    .iter()
                    .filter_map(|id| {
                        let read = state.envelopes.get(id)?;
                        Some((id.clone(), read.envelope.clone()?))
                    })
                    .collect());
            }

            state.clock += 1;
            let at = state.clock;
            for id in &claimed {
                state
                    .envelopes
                    .insert(id.clone(), Read { at, envelope: None });
            }
            drop(state);

            let read = gmail.envelopes(&claimed);

            let mut state = self.lock();
            let mine = |state: &State, id: &str| {
                state
                    .envelopes
                    .get(id)
                    .is_some_and(|read| read.at == at && read.envelope.is_none())
            };
            let failed = match read {
                Ok(read) => {
                    for (id, envelope) in read {
                        if mine(&state, &id) {
                            state.envelopes.insert(
                                id,
                                Read {
                                    at,
                                    envelope: Some(envelope),
                                },
                            );
                        }
                    }
                    None
                }
                Err(err) => Some(err),
            };
            // NOTE: a claim left unanswered, by a failure or a source that
            // skipped it, is let go for the next asker.
            for id in &claimed {
                if mine(&state, id) {
                    state.envelopes.remove(id);
                }
            }
            drop(state);
            self.ready.notify_all();
            if let Some(err) = failed {
                return Err(err);
            }
        }
    }

    /// Forgets one message's envelope, after the app changed it.
    pub fn forget(&self, id: &str) {
        let mut state = self.lock();
        if state
            .envelopes
            .get(id)
            .is_some_and(|read| read.envelope.is_some())
        {
            state.envelopes.remove(id);
        }
    }
}

/// The run shared by every session of one pool run of an account.
///
/// Keyed by the account (base URL and login) and the run's number; a run
/// of 0 is a session's own, shared with nobody.
pub fn join(account: &str, run: i64) -> Arc<GmailRun> {
    static RUNS: Mutex<Vec<(String, i64, Weak<GmailRun>)>> = Mutex::new(Vec::new());

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
    let fresh = Arc::new(GmailRun::default());
    runs.push((account.to_string(), run, Arc::downgrade(&fresh)));
    fresh
}

impl<H: Host> Gmail<'_, H> {
    /// One page of a label's mailbox.
    ///
    /// A delta projects the history replayed once per run: a moved message
    /// is a member while it belongs to the label, else gone, as is one
    /// deleted for good; expired history is refused for a [`Self::round`].
    pub fn list_label(
        &mut self,
        run: &GmailRun,
        label: &str,
        request: &MailRequest,
    ) -> Result<MailPage, BridgeError> {
        match &request.listing {
            Listing::Delta { checkpoint } => self.delta(run, label, &request.scope, checkpoint),
            Listing::Round { cursor, .. } => self.round(run, label, request, cursor.as_deref()),
        }
    }

    /// One page of a round over a label: its ids alone, 500 a page.
    ///
    /// Each named by the run's markers, for the store to keep the summary it
    /// holds and name the rest. Its cursor is the page token, its checkpoint
    /// the run's history id.
    fn round(
        &mut self,
        run: &GmailRun,
        label: &str,
        request: &MailRequest,
        cursor: Option<&str>,
    ) -> Result<MailPage, BridgeError> {
        let (start, _) = run.start(self)?;
        // NOTE: from the oldest reception held (a second early, `after:`
        // taking whole seconds), or two days below the scope's floor if
        // later: all the store holds is listed, so what is absent was
        // deleted.
        let held = request.received.map(|millis| millis.div_euclid(1000) - 1);
        let scoped = request
            .scope
            .received_since(RECEIVED_MARGIN_DAYS)
            .map(|since| since.as_second());
        let since = held.max(scoped);

        // NOTE: a round begun before steps left a bare page token, or one
        // behind the account listing's prefix, which it no longer reads.
        let token = cursor.map(|cursor| match cursor.split_once(':') {
            Some((_, token)) => token,
            None => cursor,
        });
        let page = match run.page(self, Some(label), since, token) {
            Ok(page) => page,
            // NOTE: a page token Gmail no longer honours restarts the round.
            Err(err) if token.is_some() && err.status == Some(400) => {
                return Ok(MailPage::rejected());
            }
            Err(err) => return Err(err),
        };
        let marks = run.marks(self, since)?;

        let items = page
            .ids
            .into_iter()
            .map(|id| {
                let marked = flags(
                    !marks.unread.contains(&id),
                    false,
                    marks.starred.contains(&id),
                );
                Named::unnamed(id, marked)
            })
            .collect();
        let next = page.next.map(|next| format!("{LABEL_CURSOR}{next}"));
        let checkpoint = cursor.is_none().then_some(start);
        Ok(MailPage::round(items, next, checkpoint))
    }

    /// One label's delta, projected from the account's history.
    fn delta(
        &mut self,
        run: &GmailRun,
        label: &str,
        scope: &Scope,
        checkpoint: &str,
    ) -> Result<MailPage, BridgeError> {
        let Some((history, at)) = run.history(self, checkpoint)? else {
            return Ok(MailPage::rejected());
        };

        let read = run.envelopes(self, &history.changed, at)?;
        let mut items = Vec::new();
        let mut vanished = Vec::new();
        for id in &history.changed {
            match read.get(id).and_then(Option::as_ref) {
                Some(envelope) if envelope.belongs(label) => {
                    items.extend(envelope.named(id, scope))
                }
                _ => vanished.push(id.clone()),
            }
        }
        vanished.extend(history.deleted.iter().cloned());

        Ok(MailPage::delta(items, vanished, history.next.clone()))
    }
}

#[cfg(test)]
mod tests;
