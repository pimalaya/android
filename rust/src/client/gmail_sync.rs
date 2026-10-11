//! Gmail as one account stepped through, its labels rebuilt locally.
//!
//! Gmail files a message once and names it under several labels; "All
//! Mail" is no label but every message, whatever its labels. So the
//! account is stepped through once and each message placed in every
//! mailbox its `labelIds` name (pimdir places one item in several
//! collections), one in the trash or the spam in that one alone
//! ([`super::gmail::belongs`]).
//!
//! A step ([`step`]) walks the account's listing from the top, newest
//! received first, and keeps the ids the store does not hold until it has
//! a step's worth: a set difference, robust to any date, which also fills
//! the gaps a failure or a message stored from a search left. Never a
//! `before:` on a date, `internalDate` or `Date`: an imported message sits
//! far from both. The ids it keeps are read whole ([`Client::gmail_sources`]),
//! labels and reception riding along, and the engine stores them as it
//! stores a listing.
//!
//! A pass after the first replays the account's one unfiltered history,
//! once, whatever number of labels it projects onto ([`list_label`]). A
//! round over a label, which a history Gmail no longer holds or a label
//! new to the store asks for, lists the label's ids alone, named by their
//! markers, for the store to keep what it holds and name the rest
//! ([`round`]): never one read per message it holds.
//!
//! The engine reconciles one mailbox at a time, on the sessions of a
//! pool, so what a pass shares lives in a [`GmailRun`]: one per pool run
//! and account, joined by every session the run's workers hold, emptied
//! with the run. It keeps the history replays, the listing pages and the
//! envelopes the run read, each read by whichever worker asks first while
//! the others wait for it.
//!
//! A listing is fresh only from the history id it is replayed from: the
//! run takes the account's `historyId` before it reads anything for a
//! round, every round it opens hands that id as its checkpoint, and an
//! envelope read before a replay is read again for it. What moves in
//! between is the next delta's to replay, never missed.

use std::{
    collections::{HashMap, HashSet},
    path::Path,
    sync::{Arc, Condvar, Mutex, MutexGuard, Weak},
};

use crate::{
    client::{
        Client,
        gmail::{BATCH, Fetched, GmailEnvelope, GmailHistoryDelta, GmailIds, STARRED, UNREAD},
        listing::{Listing, MailPage, MailRequest, Named, RECEIVED_MARGIN_DAYS, Scope, flags},
    },
    types::BridgeError,
};

/// Cursor prefix of a round listing one label.
const LABEL_CURSOR: &str = "l:";

/// What the account asks of Gmail.
///
/// [`LiveGmail`] is the one source the app runs; the trait exists so a
/// run can be driven by io-pimdir's engine over a fake account, its
/// requests counted.
pub(crate) trait GmailSource {
    /// The account's current history id.
    fn history_id(&mut self) -> Result<String, BridgeError>;

    /// One page of ids, newest received first: the account's when `label`
    /// is [`None`], spam and trash left out, else the label's; received
    /// from the second `since` on, when named.
    fn list(
        &mut self,
        label: Option<&str>,
        since: Option<i64>,
        page: Option<&str>,
    ) -> Result<GmailIds, BridgeError>;

    /// The envelopes of at most [`BATCH`] ids, each answered once,
    /// [`None`] for a message gone.
    fn read(&mut self, ids: &[String])
    -> Result<Vec<(String, Option<GmailEnvelope>)>, BridgeError>;

    /// What moved in the account since `start`, unfiltered; [`None`] when
    /// Gmail holds no history that old.
    fn history(&mut self, start: &str) -> Result<Option<GmailHistoryDelta>, BridgeError>;

    /// `ids` read whole into files of `dir`, a message gone left out.
    fn fetch(&mut self, ids: &[String], dir: &Path) -> Result<Vec<Fetched>, BridgeError>;

    /// Which of `ids` the store holds, asked once per page a step lists.
    fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError>;
}

/// The account behind a session, over the JNI transport.
pub(crate) struct LiveGmail<'c, 'a, 'local> {
    pub client: &'c mut Client<'a, 'local>,
    pub token: &'c str,
}

impl GmailSource for LiveGmail<'_, '_, '_> {
    fn history_id(&mut self) -> Result<String, BridgeError> {
        self.client.gmail_history_id(self.token)
    }

    fn list(
        &mut self,
        label: Option<&str>,
        since: Option<i64>,
        page: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        self.client.list_gmail_page(self.token, label, since, page)
    }

    fn read(
        &mut self,
        ids: &[String],
    ) -> Result<Vec<(String, Option<GmailEnvelope>)>, BridgeError> {
        self.client.gmail_envelopes(self.token, ids)
    }

    fn history(&mut self, start: &str) -> Result<Option<GmailHistoryDelta>, BridgeError> {
        self.client.gmail_history(self.token, start)
    }

    fn fetch(&mut self, ids: &[String], dir: &Path) -> Result<Vec<Fetched>, BridgeError> {
        self.client.gmail_sources(self.token, ids, dir)
    }

    fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError> {
        self.client.held(ids)
    }
}

/// What one step brought: the account's history id taken before it
/// listed anything, the messages read whole, and whether the listing
/// ran out before a step's worth.
#[derive(Debug)]
pub(crate) struct Stepped {
    pub history_id: String,
    pub messages: Vec<Fetched>,
    pub exhausted: bool,
}

/// One step: about `count` messages the store does not hold (asked of
/// [`GmailSource::held`] a listed page at a time), newest received first,
/// read whole into files of `dir`. The account's listing, spam and trash left
/// out, or one label's for a view filtered to it.
///
/// The history id is taken first, so what arrives while the step runs is
/// the next replay's. The walk lists from the top every time: 5 units per
/// 500 ids, the price of a set difference no date can fool.
pub(crate) fn step<S: GmailSource>(
    source: &mut S,
    label: Option<&str>,
    count: usize,
    dir: &Path,
) -> Result<Stepped, BridgeError> {
    let history_id = source.history_id()?;

    let mut fresh: Vec<String> = Vec::new();
    let mut token: Option<String> = None;
    let exhausted = loop {
        let page = source.list(label, None, token.as_deref())?;
        let held = source.held(&page.ids)?;
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
        false => source.fetch(&fresh, dir)?,
    };
    Ok(Stepped {
        history_id,
        messages,
        exhausted,
    })
}

/// What one pool run read of an account, shared by the sessions of its
/// workers.
///
/// Every entry is read by one worker while the others wait for it, and
/// an envelope carries the instant its read was claimed, on the run's
/// own clock, so a replay can tell one read before it from one read
/// after.
#[derive(Default)]
pub struct GmailRun {
    state: Mutex<State>,
    ready: Condvar,
}

#[derive(Default)]
struct State {
    /// Ticks once per claim and per recorded answer.
    clock: u64,
    /// The account's history id taken before the run's first round, under
    /// the key `""`.
    starts: HashMap<String, Slot<String>>,
    /// Listing pages, by label, reception floor and page token.
    pages: HashMap<String, Slot<GmailIds>>,
    /// The unread and starred ids, by reception floor.
    marks: HashMap<String, Slot<Arc<Marks>>>,
    /// History replays, by the history id they start from; [`None`] when
    /// Gmail held no history that old.
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

/// One shared answer, being read or read, with the clock reading of the
/// moment it was recorded.
enum Slot<T> {
    Pending,
    Done(T, u64),
}

/// One message's envelope: claimed at `at`, being read while `envelope`
/// is [`None`], gone when it holds [`None`].
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

    /// The answer under `key` in the map `slots` names, read by `fetch`
    /// when no worker of the run has read it, waited for when one is
    /// reading it. A failure is not kept: the next asker reads again.
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

    /// The account's history id, taken once before the run reads anything
    /// for a round, and the clock reading it was recorded at.
    fn start<S: GmailSource>(&self, source: &mut S) -> Result<(String, u64), BridgeError> {
        self.once(|state| &mut state.starts, "", || source.history_id())
    }

    /// One listing page, read once per run.
    fn page<S: GmailSource>(
        &self,
        source: &mut S,
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
            || source.list(label, since, token),
        )?;
        Ok(page)
    }

    /// The unread and the starred ids received from `since` on, every
    /// page of the two marker labels read once per run.
    fn marks<S: GmailSource>(
        &self,
        source: &mut S,
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
                        let page = source.list(Some(label), since, token.as_deref())?;
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

    /// The account's history from `start`, replayed once per run, and the
    /// clock reading it was recorded at. The messages it deleted for good
    /// are recorded gone, never read.
    fn history<S: GmailSource>(
        &self,
        source: &mut S,
        start: &str,
    ) -> Result<Option<(Arc<GmailHistoryDelta>, u64)>, BridgeError> {
        let (history, at) = self.once(
            |state| &mut state.histories,
            start,
            || Ok(source.history(start)?.map(Arc::new)),
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

    /// The envelopes of `ids`, every one read after the clock reading
    /// `after`: what the run holds from before it is read again. The ids
    /// no worker has claimed are claimed [`BATCH`] at a time and read,
    /// the ones another worker is reading waited for, so the workers of a
    /// run share the batches between them.
    fn envelopes<S: GmailSource>(
        &self,
        source: &mut S,
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

            let read = source.read(&claimed);

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

/// The run every session of one pool run of an account shares, by the
/// account (its base URL and login) and the run's number; a run of 0 is
/// a session's own, shared with nobody.
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

/// One page of a label's mailbox.
///
/// A delta projects the account's history, replayed once per run: a
/// message it moved is a member while it still belongs to the label,
/// gone from this mailbox otherwise, and one deleted for good is gone
/// with no read; history Gmail no longer holds is refused for the engine
/// to open a round ([`round`]).
pub(crate) fn list_label<S: GmailSource>(
    source: &mut S,
    run: &GmailRun,
    label: &str,
    request: &MailRequest,
) -> Result<MailPage, BridgeError> {
    match &request.listing {
        Listing::Delta { checkpoint } => delta(source, run, label, &request.scope, checkpoint),
        Listing::Round { cursor, .. } => round(source, run, label, request, cursor.as_deref()),
    }
}

/// One page of a round over a label: its ids alone, a page of 500 at a
/// time, each named by its markers (the run's unread and starred ids),
/// for the store to keep the summary it holds and name the rest. Its
/// cursor is the page token, its checkpoint the run's history id.
///
/// Received from the oldest reception the account holds (`received` in
/// the request) on, a second earlier for `after:` taking whole seconds,
/// or two days below the scope's floor when that is later: what the
/// store holds is listed, so what the round finds absent was deleted.
fn round<S: GmailSource>(
    source: &mut S,
    run: &GmailRun,
    label: &str,
    request: &MailRequest,
    cursor: Option<&str>,
) -> Result<MailPage, BridgeError> {
    let (start, _) = run.start(source)?;
    let held = request.received.map(|millis| millis.div_euclid(1000) - 1);
    let scoped = request
        .scope
        .received_since(RECEIVED_MARGIN_DAYS)
        .map(|since| since.as_second());
    let since = held.max(scoped);

    // NOTE: a round begun before steps left a label's bare page token, or
    // one behind the prefix of the account listing it no longer reads.
    let token = cursor.map(|cursor| match cursor.split_once(':') {
        Some((_, token)) => token,
        None => cursor,
    });
    let page = match run.page(source, Some(label), since, token) {
        Ok(page) => page,
        // NOTE: a page token Gmail no longer honours restarts the round.
        Err(err) if token.is_some() && err.status == Some(400) => {
            return Ok(MailPage::rejected());
        }
        Err(err) => return Err(err),
    };
    let marks = run.marks(source, since)?;

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
fn delta<S: GmailSource>(
    source: &mut S,
    run: &GmailRun,
    label: &str,
    scope: &Scope,
    checkpoint: &str,
) -> Result<MailPage, BridgeError> {
    let Some((history, at)) = run.history(source, checkpoint)? else {
        return Ok(MailPage::rejected());
    };

    let read = run.envelopes(source, &history.changed, at)?;
    let mut items = Vec::new();
    let mut vanished = Vec::new();
    for id in &history.changed {
        match read.get(id).and_then(Option::as_ref) {
            Some(envelope) if envelope.belongs(label) => items.extend(envelope.named(id, scope)),
            _ => vanished.push(id.clone()),
        }
    }
    vanished.extend(history.deleted.iter().cloned());

    Ok(MailPage::delta(items, vanished, history.next.clone()))
}

#[cfg(test)]
#[path = "gmail_sync_tests.rs"]
mod tests;
