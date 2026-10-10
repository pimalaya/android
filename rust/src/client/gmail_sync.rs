//! Gmail as one account listing, its labels rebuilt locally.
//!
//! Gmail files a message once and names it under several labels; "All
//! Mail" is no label but every message, whatever its labels. So the
//! account is listed once (`messages.list` with no `labelIds`, spam and
//! trash included, 500 ids a page, newest first), each message's
//! metadata read once, and each label's mailbox answered from that one
//! listing: a message is a member of every mailbox its `labelIds` name
//! (pimdir places one item in several collections), and one in the trash
//! or the spam of that one alone ([`GmailEnvelope::belongs`]). A pass
//! after the first replays the account's one unfiltered history, once,
//! whatever number of labels it projects onto.
//!
//! The engine still reconciles one mailbox at a time, on the sessions of
//! a pool, so what is shared lives in a [`GmailRun`]: one per pool run
//! and account, joined by every session the run's workers hold, emptied
//! with the run. It keeps the listing pages, the envelopes, the history
//! replays and the floors the run read, each read by whichever worker
//! asks first while the others wait for it or read the next batch, so
//! the listing goes out on one session and the metadata over the pool.
//!
//! A listing is fresh only from the history id it is replayed from: the
//! run takes the account's `historyId` before it reads anything for a
//! round, every round it opens hands that id as its checkpoint, and an
//! envelope read before a replay is read again for it. What moves in
//! between is the next delta's to replay, never missed.
//!
//! One floor per account: a mailbox's first chunk is the newest 50
//! messages of the account, the inbox's the newest 50 of its own when the
//! account's leave it behind; widening below a floor takes the account's
//! next messages. A round over the account's newest chunk or a band below
//! a floor lists the account; a full round further down (a mailbox behind
//! the others, or one opened again after its history expired) lists its
//! label alone, which reads only its own messages.

use std::{
    collections::HashMap,
    sync::{Arc, Condvar, Mutex, MutexGuard, Weak},
};

use jiff::Timestamp;

use crate::{
    client::{
        Client,
        gmail::{BATCH, GmailEnvelope, GmailHistoryDelta, GmailIds, INBOX},
        listing::{Floor, FloorReply, Listing, MailPage, MailRequest, Scope},
    },
    types::BridgeError,
};

/// The messages an account's first chunk takes (`MailEngine.FIRST_CHUNK`
/// on the Java side): a full round whose floor lies below the account's
/// newest chunk lists its label rather than the account.
pub(crate) const FIRST_CHUNK: usize = 50;

/// Cursor prefix of a round listing the account.
const ACCOUNT_CURSOR: &str = "a:";

/// Cursor prefix of a round listing one label.
const LABEL_CURSOR: &str = "l:";

/// What the account listing asks of Gmail.
///
/// [`LiveGmail`] is the one source the app runs; the trait exists so a
/// run can be driven by io-pimdir's engine over a fake account, its
/// requests counted.
pub(crate) trait GmailSource {
    /// The account's current history id.
    fn history_id(&mut self) -> Result<String, BridgeError>;

    /// One page of ids, newest first: the account's when `label` is
    /// [`None`], spam and trash included, else the label's.
    fn list(
        &mut self,
        label: Option<&str>,
        scope: &Scope,
        page: Option<&str>,
    ) -> Result<GmailIds, BridgeError>;

    /// The envelopes of at most [`BATCH`] ids, one batch request, each
    /// answered once, [`None`] for a message gone.
    fn read(&mut self, ids: &[String])
    -> Result<Vec<(String, Option<GmailEnvelope>)>, BridgeError>;

    /// What moved in the account since `start`, unfiltered; [`None`] when
    /// Gmail holds no history that old.
    fn history(&mut self, start: &str) -> Result<Option<GmailHistoryDelta>, BridgeError>;
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
        scope: &Scope,
        page: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        self.client.list_gmail_page(self.token, label, scope, page)
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
}

/// What one pool run read of an account, shared by the sessions of its
/// workers.
///
/// Every entry is read by one worker while the others wait for it, and
/// an envelope carries the instant its read was claimed, on the run's
/// own clock, so a replay or a round can tell one read before it from
/// one read after.
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
    /// Listing pages, by label, scope and page token.
    pages: HashMap<String, Slot<GmailIds>>,
    /// History replays, by the history id they start from; [`None`] when
    /// Gmail held no history that old.
    histories: HashMap<String, Slot<Option<Arc<GmailHistoryDelta>>>>,
    /// Floor walks, by label, ceiling and count.
    walks: HashMap<String, Slot<Walk>>,
    /// Envelopes by message id.
    envelopes: HashMap<String, Read>,
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

/// One floor walk: the floor, and the messages it took, in order.
#[derive(Clone)]
struct Walk {
    reply: FloorReply,
    taken: Vec<String>,
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
    /// for a round, and the clock reading it was recorded at: every
    /// envelope a round projects is read after it.
    fn start<S: GmailSource>(&self, source: &mut S) -> Result<(String, u64), BridgeError> {
        self.once(|state| &mut state.starts, "", || source.history_id())
    }

    /// One listing page, read once per run.
    fn page<S: GmailSource>(
        &self,
        source: &mut S,
        label: Option<&str>,
        scope: &Scope,
        token: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        let key = format!(
            "{}\u{0}{}\u{0}{}",
            label.unwrap_or_default(),
            scope.key(),
            token.unwrap_or_default()
        );
        let (page, _) = self.once(
            |state| &mut state.pages,
            &key,
            || source.list(label, scope, token),
        )?;
        Ok(page)
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
    /// run share the batches of one page between them.
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

    /// The floor of the `count` newest messages below `before`, of the
    /// account when `label` is [`None`], else of the label, walked once
    /// per run, the envelopes read after `after`.
    fn walk<S: GmailSource>(
        &self,
        source: &mut S,
        label: Option<&str>,
        before: Option<&str>,
        count: usize,
        after: u64,
    ) -> Result<Walk, BridgeError> {
        let key = format!(
            "{}\u{0}{}\u{0}{count}",
            label.unwrap_or_default(),
            before.unwrap_or_default()
        );
        let (walk, _) = self.once(
            |state| &mut state.walks,
            &key,
            || {
                let mut floor = Floor::new(before, count);
                let scope = floor.scope().clone();
                let mut taken = Vec::new();
                let mut token: Option<String> = None;
                'pages: loop {
                    let page = self.page(source, label, &scope, token.as_deref())?;
                    for chunk in page.ids.chunks(BATCH) {
                        let read = self.envelopes(source, chunk, after)?;
                        for id in chunk {
                            let date = read
                                .get(id)
                                .and_then(Option::as_ref)
                                .and_then(|envelope| envelope.summary.date.as_deref());
                            let dated = floor.reply().dated;
                            let full = floor.take(date);
                            if floor.reply().dated > dated {
                                taken.push(id.clone());
                            }
                            if full {
                                break 'pages;
                            }
                        }
                    }
                    match page.next {
                        Some(next) => token = Some(next),
                        None => break,
                    }
                }
                Ok(Walk {
                    reply: floor.reply(),
                    taken,
                })
            },
        )?;
        Ok(walk)
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

/// The floor of a mailbox's next chunk, `count` messages below `before`.
///
/// One floor per account: the account's `count` newest below the
/// ceiling, whatever the mailbox. The inbox's first chunk is the one
/// exception: when fewer than `count` of the account's newest are in the
/// inbox, it reaches down to the inbox's own `count` newest, so the first
/// list never opens on an inbox left behind by the rest of the account.
pub(crate) fn floor<S: GmailSource>(
    source: &mut S,
    run: &GmailRun,
    label: &str,
    before: Option<&str>,
    count: usize,
) -> Result<FloorReply, BridgeError> {
    let (_, after) = run.start(source)?;
    let account = run.walk(source, None, before, count, after)?;
    if label != INBOX || before.is_some() || account.reply.floor.is_none() {
        return Ok(account.reply);
    }

    let read = run.envelopes(source, &account.taken, after)?;
    let filed = account
        .taken
        .iter()
        .filter(|id| {
            read.get(*id)
                .and_then(Option::as_ref)
                .is_some_and(|envelope| envelope.belongs(INBOX))
        })
        .count();
    if filed >= count {
        return Ok(account.reply);
    }

    let own = run.walk(source, Some(INBOX), None, count, after)?;
    Ok(lower(account.reply, own.reply))
}

/// The lower of two floors, no floor (the whole mailbox) lowest of all.
fn lower(one: FloorReply, other: FloorReply) -> FloorReply {
    match (&one.floor, &other.floor) {
        (Some(first), Some(second)) if above(first, second) => other,
        (Some(_), None) => other,
        _ => one,
    }
}

/// Whether `one` is a later instant than `other`.
fn above(one: &str, other: &str) -> bool {
    !Scope {
        since: Some(one.to_string()),
        until: None,
    }
    .contains(Some(other))
}

/// One page of a label's mailbox, answered from the account.
///
/// A delta projects the account's history, replayed once per run: a
/// message it moved is a member while it still belongs to the label,
/// gone from this mailbox otherwise, and one deleted for good is gone
/// with no read; history Gmail no longer holds is refused for the engine
/// to open a round. A round projects one listing page, the account's or
/// the label's ([`account_wide`]), its cursor the page token behind a
/// prefix naming which, its checkpoint the run's history id.
pub(crate) fn list_label<S: GmailSource>(
    source: &mut S,
    run: &GmailRun,
    label: &str,
    request: &MailRequest,
) -> Result<MailPage, BridgeError> {
    let scope = &request.scope;
    let (cursor, band) = match &request.listing {
        Listing::Delta { checkpoint } => return delta(source, run, label, scope, checkpoint),
        Listing::Round { cursor, band } => (cursor.as_deref(), *band),
    };

    let (start, after) = run.start(source)?;
    let (account, token) = match cursor {
        Some(cursor) => match (
            cursor.strip_prefix(ACCOUNT_CURSOR),
            cursor.strip_prefix(LABEL_CURSOR),
        ) {
            (Some(token), _) => (true, Some(token)),
            (_, Some(token)) => (false, Some(token)),
            // NOTE: a round begun before the account listing, its cursor a
            // label's bare page token.
            _ => (false, Some(cursor)),
        },
        None if band => (true, None),
        None => (account_wide(source, run, scope, after)?, None),
    };
    let checkpoint = (cursor.is_none() && !band).then_some(start);

    let listed = match account {
        true => None,
        false => Some(label),
    };
    let page = match run.page(source, listed, scope, token) {
        Ok(page) => page,
        // NOTE: a page token Gmail no longer honours restarts the round,
        // which relists and rereads nothing the run already holds.
        Err(err) if token.is_some() && err.status == Some(400) => {
            return Ok(MailPage::rejected());
        }
        Err(err) => return Err(err),
    };

    // NOTE: read a batch at a time, newest received first, and stopped
    // past the floor: the listing's `after:` takes two days of margin
    // below it, mail that is almost all dated below it too.
    let mut items = Vec::new();
    let mut past = false;
    for chunk in page.ids.chunks(BATCH) {
        let read = run.envelopes(source, chunk, after)?;
        items.extend(chunk.iter().filter_map(|id| {
            let envelope = read.get(id)?.as_ref()?;
            envelope
                .belongs(label)
                .then(|| envelope.named(id, scope))
                .flatten()
        }));
        if received_below(chunk, &read, scope) {
            past = true;
            break;
        }
    }

    let prefix = match account {
        true => ACCOUNT_CURSOR,
        false => LABEL_CURSOR,
    };
    let next = match past {
        true => None,
        false => page.next.map(|next| format!("{prefix}{next}")),
    };
    Ok(MailPage::round(items, next, checkpoint))
}

/// Whether a whole batch of a listing, in Gmail's order of reception,
/// was received before the scope's floor, so that what the listing
/// names after it is older still and dated below the floor but for a
/// sender's clock running ahead by more than a batch of mail.
///
/// A message dated in the scope and received before its floor, as an
/// import of old mail is not, comes first in its batch's reading anyway:
/// the batch that crosses the floor is read whole.
fn received_below(
    chunk: &[String],
    read: &HashMap<String, Option<GmailEnvelope>>,
    scope: &Scope,
) -> bool {
    let Some(floor) = scope
        .since
        .as_deref()
        .and_then(|since| since.parse::<Timestamp>().ok())
    else {
        return false;
    };
    let floor = floor.as_millisecond();
    let mut any = false;
    for id in chunk {
        let Some(envelope) = read.get(id).and_then(Option::as_ref) else {
            continue;
        };
        match envelope.received {
            Some(received) if received < floor => any = true,
            _ => return false,
        }
    }
    any
}

/// Whether a full round over `scope` lists the account rather than its
/// label: when the scope holds no more than the account's newest chunk,
/// the listing every mailbox's first chunk shares, or the account holds
/// less than a chunk in all. A round reaching further down lists its
/// label, which reads its own messages and no other mailbox's.
fn account_wide<S: GmailSource>(
    source: &mut S,
    run: &GmailRun,
    scope: &Scope,
    after: u64,
) -> Result<bool, BridgeError> {
    let top = run
        .walk(source, None, None, FIRST_CHUNK, after)?
        .reply
        .floor;
    Ok(match (top, scope.since.as_deref()) {
        (None, _) => true,
        (Some(_), None) => false,
        (Some(top), Some(since)) => !above(&top, since),
    })
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
