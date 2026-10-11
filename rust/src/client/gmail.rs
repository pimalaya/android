//! Gmail API mail: labels as mailboxes and messages read as the MIME
//! Gmail keeps of them, the Gmail half of what `syncMail` does over
//! IMAP, JMAP and Graph.
//!
//! Gmail files a message once and names it under several labels, so the
//! account is stepped through once (`messages.list` with no `labelIds`)
//! and each message placed in every mailbox its labels name
//! ([`super::gmail_sync`]). Messages are read in batches
//! (`POST /batch/gmail/v1`) of at most [`BATCH`], one batch of an account
//! at a time on one connection, under the account's pacing: metadata for
//! what a history names, a `minimal` pre-pass for the sizes, then the raw
//! bytes, a batch holding about [`RAW_BATCH_BYTES`] and a message past
//! [`ALONE_BYTES`] read alone ([`read_all`]). One unfiltered history per
//! account replays what moved since a `historyId`, which is what keeps a
//! quiet pass one request.

use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    fs,
    path::{Path, PathBuf},
    thread,
    time::{Duration, Instant},
};

use io_gmail::{
    coroutine::*,
    v1::{
        batch::{
            GMAIL_BATCH_URL, GmailBatch, GmailBatchRequest, GmailBatchResponse,
            GmailBatchResponseError,
        },
        rest::{
            history::{
                GmailHistory,
                list::{GmailHistoryList, GmailHistoryListParams},
            },
            labels::{GmailLabelType, list::GmailLabelsList},
            messages::{
                GmailMessage, GmailMessageFormat, decode_raw,
                delete::GmailMessageDelete,
                encode_raw,
                get::GmailMessageGet,
                list::{GmailMessagesList, GmailMessagesListParams},
                modify::GmailMessageModify,
                send::GmailMessageSend,
                trash::GmailMessageTrash,
                untrash::GmailMessageUntrash,
            },
            users::get_profile::GmailProfileGet,
        },
        send::{GMAIL_API_BASE, GmailSendError, GmailSendOutput},
    },
};
use io_http::rfc6750::bearer::HttpAuthBearer;
use io_pimdir::summary::{
    PimdirSummary, derive,
    mail::{PimdirMailSummary, derive_meta},
};

use crate::{
    client::{
        Client,
        convert::coroutine_error,
        listing::{GMAIL_PAGE, Named, Scope, flags},
        throttle,
    },
    mail::derivable,
    types::{BridgeError, Mailbox},
};

/// Gmail's quota, in one place: the units each method costs, as Google
/// documents them (Gmail API, "Usage limits"), and the rates an account
/// is paced under ([`throttle::pace_gmail`]). A batch costs the sum of
/// its calls.
///
/// Measured on 2026-10-10 against the test account, Gmail billed
/// `messages.get` 5 units against a per-minute quota of 15,000 (the
/// "previous quota" its refusals name), a bucket of about 250 units a
/// second clearing a refusal within 2 s. The documented 20 units against
/// 6,000 a minute are what is counted here, so the app stays under the
/// quota Google may enforce any day.
pub(crate) mod quota {
    pub(crate) const GET_PROFILE: u32 = 1;
    pub(crate) const LABELS_LIST: u32 = 1;
    pub(crate) const HISTORY_LIST: u32 = 2;
    pub(crate) const MESSAGES_LIST: u32 = 5;
    pub(crate) const MESSAGES_GET: u32 = 20;
    pub(crate) const MESSAGES_MODIFY: u32 = 5;
    pub(crate) const MESSAGES_TRASH: u32 = 5;
    pub(crate) const MESSAGES_UNTRASH: u32 = 5;
    pub(crate) const MESSAGES_DELETE: u32 = 10;
    pub(crate) const MESSAGES_SEND: u32 = 100;

    /// The project's quota per user per minute, as documented.
    pub(crate) const UNITS_PER_MINUTE_PER_USER: u32 = 6_000;

    /// Units per second an account is held near: the measured bucket of
    /// 250 billed units at 5 a read, 50 reads a second, in documented
    /// units. The minute's budget is what binds a long run.
    pub(crate) const UNITS_PER_SECOND: u32 = 1_000;

    /// Units let through at once after a pause on top of a request's own:
    /// one batch of reads.
    pub(crate) const BURST_UNITS: u32 = 200;
}

/// The label Gmail files deleted mail under.
pub(crate) const TRASH: &str = "TRASH";

/// The label Gmail files junk under.
pub(crate) const SPAM: &str = "SPAM";

/// The label of the inbox.
pub(crate) const INBOX: &str = "INBOX";

/// The label marking a message unread.
pub(crate) const UNREAD: &str = "UNREAD";

/// The label marking a message starred.
pub(crate) const STARRED: &str = "STARRED";

/// Reads per batch. The calls of a batch run side by side on Gmail's
/// side, and its per-user concurrency limit is what binds: measured on
/// 2026-10-10, batches of 10 sent one after another drew no refusal in
/// 2,250 reads, 25 drew 1.2 % and 50 drew 19 %.
pub(crate) const BATCH: usize = 10;

/// About how many bytes of messages one raw batch carries, by the sizes
/// the pre-pass read: the answer is their base64, a third larger.
pub(crate) const RAW_BATCH_BYTES: u64 = 8 * 1024 * 1024;

/// The size past which a message is read alone rather than in a batch.
pub(crate) const ALONE_BYTES: u64 = 5 * 1024 * 1024;

/// How many times one message is sent again after its inner answer was
/// refused, before the read fails.
const MAX_TRIES: u32 = 6;

/// History records per `history.list` page, the API's ceiling.
const HISTORY_PAGE: u32 = 500;

/// The headers a metadata read asks for: what Annex A's summary and
/// addresses are derived from, `Date` the `Date` header and never Gmail's
/// reception time, `Content-Type` for the attachment mark read without
/// the body.
const ENVELOPE_HEADERS: [&str; 8] = [
    "Date",
    "From",
    "To",
    "Cc",
    "Subject",
    "Message-ID",
    "In-Reply-To",
    "Content-Type",
];

/// System labels that are markers or views rather than places mail is
/// filed, and so no mailbox.
const NOT_MAILBOXES: [&str; 5] = [UNREAD, STARRED, "IMPORTANT", "CHAT", "YELLOW_STAR"];

/// One message's metadata: its labels, its markers and its summary.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GmailEnvelope {
    pub labels: Vec<String>,
    pub flags: Vec<String>,
    pub summary: PimdirMailSummary,
}

impl GmailEnvelope {
    /// The member this message is in a label's listing, when its `Date`
    /// falls in the scope.
    pub fn named(&self, id: &str, scope: &Scope) -> Option<Named> {
        scope
            .contains(self.summary.date.as_deref())
            .then(|| Named::new(id.to_string(), self.flags.clone(), self.summary.clone()))
    }

    /// Whether this message is a member of `label`'s mailbox ([`belongs`]).
    pub fn belongs(&self, label: &str) -> bool {
        belongs(&self.labels, label)
    }
}

/// Whether a message carrying `labels` is a member of `label`'s mailbox:
/// it carries the label, and a message in the trash or the spam is a
/// member of that one alone, as Gmail shows it nowhere else.
pub(crate) fn belongs(labels: &[String], label: &str) -> bool {
    let has = |id: &str| labels.iter().any(|carried| carried == id);
    has(label) && (label == SPAM || label == TRASH || !(has(SPAM) || has(TRASH)))
}

/// The IMAP markers a message carrying `labels` holds.
fn marks(labels: &[String]) -> Vec<String> {
    let has = |id: &str| labels.iter().any(|carried| carried == id);
    flags(!has(UNREAD), false, has(STARRED))
}

/// One message read whole and written to a file.
#[derive(Clone, Debug)]
pub struct Fetched {
    pub id: String,
    pub path: PathBuf,
    pub size: usize,
    /// Its labels, from the read's `labelIds`.
    pub labels: Vec<String>,
    /// Its markers, from its labels.
    pub flags: Vec<String>,
    /// When Gmail received it (`internalDate`), in milliseconds since the
    /// epoch: never its date.
    pub received: Option<i64>,
    /// The summary Annex A derives from its bytes.
    pub summary: PimdirMailSummary,
}

/// How a message is relocated between two labels.
#[derive(Debug, Eq, PartialEq)]
pub(crate) enum GmailRelocation {
    /// `messages.trash`, which takes it out of every other label's view.
    Trash,
    /// `messages.untrash`, then the target label added.
    Untrash { add: Vec<String> },
    /// The target label added and the source one removed.
    Modify {
        add: Vec<String>,
        remove: Vec<String>,
    },
}

/// How a message labelled `from` is relocated under `to`.
pub(crate) fn relocation(from: &str, to: &str) -> GmailRelocation {
    if to == TRASH {
        return GmailRelocation::Trash;
    }
    if from == TRASH {
        return GmailRelocation::Untrash {
            add: vec![to.to_string()],
        };
    }
    GmailRelocation::Modify {
        add: vec![to.to_string()],
        remove: vec![from.to_string()],
    }
}

/// One page of a listing's ids, and the token of the next.
#[derive(Clone, Debug, Default)]
pub struct GmailIds {
    pub ids: Vec<String>,
    pub next: Option<String>,
}

/// What an account's history holds past a `historyId`: the messages that
/// moved (added, labelled, unlabelled), those deleted for good, and the
/// history id the next replay starts from.
#[derive(Clone, Debug, Default)]
pub struct GmailHistoryDelta {
    pub changed: Vec<String>,
    pub deleted: Vec<String>,
    pub next: String,
}

/// What a message is read as.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum Read {
    /// Its labels and the headers a summary takes.
    Metadata,
    /// Its labels, size and reception alone: the pre-pass of a raw read.
    Minimal,
    /// Its bytes, with its labels and reception.
    Raw,
}

impl Read {
    fn format(self) -> GmailMessageFormat {
        match self {
            Self::Metadata => GmailMessageFormat::Metadata,
            Self::Minimal => GmailMessageFormat::Minimal,
            Self::Raw => GmailMessageFormat::Raw,
        }
    }

    fn headers(self) -> &'static [&'static str] {
        match self {
            Self::Metadata => &ENVELOPE_HEADERS,
            Self::Minimal | Self::Raw => &[],
        }
    }
}

/// How one inner answer of a batch is taken.
#[derive(Debug)]
pub(crate) enum Inner {
    /// Read.
    Read(Box<GmailMessage>),
    /// Gone since it was listed (404).
    Gone,
    /// A concurrency or rate refusal, a transient failure or no answer:
    /// sent again in the next batch after a second or two.
    Busy,
    /// A per-minute quota refusal: sent again after a few seconds, more
    /// each time, never the next wall-clock minute.
    Minute,
    /// A daily quota refusal: nothing within a sync lifts it.
    Daily,
    /// Any other refusal, a 401 included, which fails the read with its
    /// status so the token is refreshed as for one request.
    Failed(BridgeError),
}

/// How one inner answer of a batch is taken ([`Inner`]).
pub(crate) fn inner(result: Result<GmailMessage, GmailBatchResponseError>) -> Inner {
    let err = match result {
        Ok(message) => return Inner::Read(Box::new(message)),
        Err(err) => err,
    };
    if err.is_not_found() {
        return Inner::Gone;
    }
    if let Some(api) = err.api() {
        let named = format!("{} {}", api.message, api.detail_reasons.join(" "));
        if api.has_reason("dailyLimitExceeded") || throttle::names_daily_quota(named.as_bytes()) {
            return Inner::Daily;
        }
        if api.is_rate_limited() && throttle::names_minute_quota(named.as_bytes()) {
            return Inner::Minute;
        }
    }
    if err.is_retryable() {
        return Inner::Busy;
    }
    Inner::Failed(BridgeError {
        message: err.to_string(),
        status: err.status(),
    })
}

/// The wait after a concurrency refusal: a second or two, at random, so
/// the next batch does not land on the same instant.
fn busy_pause() -> Duration {
    Duration::from_millis(rand::random_range(1_000..=2_000))
}

/// The wait after the `count`th per-minute refusal of a read: 2 s, then a
/// second more each time up to 5 s. The quota is a bucket refilling by
/// the second, measured clearing in 2 s, so waiting for the next minute
/// or spending the account's budget would only stall the run.
pub(crate) fn minute_pause(count: u32) -> Duration {
    Duration::from_secs(u64::from(count.clamp(1, 4)) + 1)
}

/// How reads are sent: a batch, one read alone, and a pause between two
/// batches.
///
/// [`Client`] is the one sender the app runs; the trait exists so the
/// reads can be counted and refused without a JVM.
pub(crate) trait GmailBatcher {
    /// One batch of reads, answered in any order.
    fn batch(
        &mut self,
        ids: &[String],
        read: Read,
    ) -> Result<Vec<GmailBatchResponse<GmailMessage>>, BridgeError>;

    /// One read alone, through the throttled path: [`None`] when the
    /// message is gone.
    fn single(&mut self, id: &str, read: Read) -> Result<Option<GmailMessage>, BridgeError>;

    /// Waits `delay` before the next batch.
    fn pause(&mut self, delay: Duration) {
        thread::sleep(delay);
    }
}

/// Reads every message of `ids` as `read`, handing each to `each` once,
/// with [`None`] for one gone: batches of at most [`BATCH`] sent one after
/// another; for a raw read, a batch holds about [`RAW_BATCH_BYTES`] by the
/// sizes `sizes` names and a message past [`ALONE_BYTES`] is read alone.
///
/// An inner answer refused for concurrency is put back at the head of
/// the next batch, sent after a second or two; one refused a per-minute
/// quota likewise after [`minute_pause`]; a daily quota stops the read.
pub(crate) fn read_all<B: GmailBatcher>(
    batcher: &mut B,
    read: Read,
    ids: &[String],
    sizes: &HashMap<String, u64>,
    mut each: impl FnMut(String, Option<GmailMessage>) -> Result<(), BridgeError>,
) -> Result<(), BridgeError> {
    let size = |id: &str| match read {
        Read::Raw => sizes.get(id).copied().unwrap_or_default(),
        _ => 0,
    };
    let mut queue: VecDeque<String> = ids.iter().cloned().collect();
    let mut tries: HashMap<String, u32> = HashMap::new();
    let mut minutes = 0;
    let mut pause = Duration::ZERO;

    while let Some(front) = queue.front() {
        if !pause.is_zero() {
            batcher.pause(pause);
            pause = Duration::ZERO;
        }
        if size(front) > ALONE_BYTES {
            let id = queue.pop_front().unwrap_or_default();
            let message = batcher.single(&id, read)?;
            each(id, message)?;
            continue;
        }

        let mut batch = Vec::new();
        let mut bytes = 0;
        while let Some(id) = queue.front() {
            let next = size(id);
            if batch.len() == BATCH
                || next > ALONE_BYTES
                || (!batch.is_empty() && bytes + next > RAW_BATCH_BYTES)
            {
                break;
            }
            bytes += next;
            batch.extend(queue.pop_front());
        }

        let mut answers: HashMap<String, Result<GmailMessage, GmailBatchResponseError>> = batcher
            .batch(&batch, read)?
            .into_iter()
            .map(|answer| (answer.id, answer.result))
            .collect();
        let mut again = Vec::new();
        for id in batch {
            let answer = answers
                .remove(&id)
                .unwrap_or(Err(GmailBatchResponseError::Missing));
            match inner(answer) {
                Inner::Read(message) => each(id, Some(*message))?,
                Inner::Gone => each(id, None)?,
                Inner::Busy => {
                    pause = pause.max(busy_pause());
                    again.push(id);
                }
                Inner::Minute => {
                    minutes += 1;
                    pause = pause.max(minute_pause(minutes));
                    again.push(id);
                }
                Inner::Daily => {
                    return Err(BridgeError {
                        message: "Gmail's daily quota is spent".into(),
                        status: Some(403),
                    });
                }
                Inner::Failed(err) => return Err(err),
            }
        }
        if !again.is_empty() {
            log::debug!(
                "gmail batch: {} refused, sent again in {} ms",
                again.len(),
                pause.as_millis()
            );
        }
        for id in again.into_iter().rev() {
            let tried = tries.entry(id.clone()).or_default();
            *tried += 1;
            if *tried >= MAX_TRIES {
                return Err(format!("Gmail kept refusing `{id}`").into());
            }
            queue.push_front(id);
        }
    }
    Ok(())
}

/// Reads the metadata of `ids` ([`read_all`]); each id answered once,
/// with [`None`] for a message gone.
pub(crate) fn read_envelopes<B: GmailBatcher>(
    batcher: &mut B,
    ids: &[String],
) -> Result<Vec<(String, Option<GmailEnvelope>)>, BridgeError> {
    let mut read = Vec::with_capacity(ids.len());
    read_all(
        batcher,
        Read::Metadata,
        ids,
        &HashMap::new(),
        |id, message| {
            read.push((id, message.map(envelope)));
            Ok(())
        },
    )?;
    Ok(read)
}

/// Reads `ids` whole into files of `dir`, one per message: a `minimal`
/// pre-pass for their sizes, then their raw bytes ([`read_all`]). A
/// message gone since it was listed is left out.
pub(crate) fn read_sources<B: GmailBatcher>(
    batcher: &mut B,
    ids: &[String],
    dir: &Path,
) -> Result<Vec<Fetched>, BridgeError> {
    let mut sizes = HashMap::new();
    let mut there = Vec::with_capacity(ids.len());
    read_all(
        batcher,
        Read::Minimal,
        ids,
        &HashMap::new(),
        |id, message| {
            if let Some(message) = message {
                sizes.insert(id.clone(), message.size_estimate.unwrap_or_default());
                there.push(id);
            }
            Ok(())
        },
    )?;

    let mut fetched = Vec::with_capacity(there.len());
    read_all(batcher, Read::Raw, &there, &sizes, |id, message| {
        if let Some(message) = message {
            fetched.push(written(id, message, dir)?);
        }
        Ok(())
    })?;
    Ok(fetched)
}

/// One raw read written to its file in `dir`.
fn written(id: String, message: GmailMessage, dir: &Path) -> Result<Fetched, BridgeError> {
    let raw = message
        .raw
        .ok_or_else(|| BridgeError::from(format!("Gmail sent no source for `{id}`")))?;
    let bytes = decode_raw(&raw).map_err(|err| format!("Invalid Gmail source: {err}"))?;
    let path = source_path(dir, &id);
    fs::write(&path, &bytes).map_err(|err| format!("Could not write `{id}`: {err}"))?;

    let derived = derive("message/rfc822", derivable(&bytes));
    let summary = match derived.and_then(|derived| derived.summary) {
        Some(PimdirSummary::Mail(summary)) => summary,
        _ => PimdirMailSummary::default(),
    };
    Ok(Fetched {
        flags: marks(&message.label_ids),
        received: message
            .internal_date
            .as_deref()
            .and_then(|millis| millis.parse().ok()),
        labels: message.label_ids,
        size: bytes.len(),
        summary,
        path,
        id,
    })
}

/// The file a message is written to in `dir`, named by its id kept to
/// characters every filesystem takes.
pub(crate) fn source_path(dir: &Path, id: &str) -> PathBuf {
    let name: String = id
        .chars()
        .map(|char| match char {
            'a'..='z' | 'A'..='Z' | '0'..='9' | '-' | '_' => char,
            _ => '_',
        })
        .collect();
    dir.join(format!("{name}.eml"))
}

/// The ids a history replay touched, in first-seen order, against the
/// ones it deleted for good, which are not read again.
fn history_changes(records: &[GmailHistory]) -> (Vec<String>, Vec<String>) {
    let mut deleted = Vec::new();
    let mut gone = BTreeSet::new();
    for record in records {
        for entry in &record.messages_deleted {
            if !entry.message.id.is_empty() && gone.insert(entry.message.id.clone()) {
                deleted.push(entry.message.id.clone());
            }
        }
    }

    let mut seen = BTreeSet::new();
    let changed = records
        .iter()
        .flat_map(|record| {
            let added = record.messages_added.iter().map(|entry| &entry.message);
            let labelled = record.labels_added.iter().map(|entry| &entry.message);
            let unlabelled = record.labels_removed.iter().map(|entry| &entry.message);
            added.chain(labelled).chain(unlabelled)
        })
        .map(|message| message.id.clone())
        .filter(|id| !id.is_empty() && !gone.contains(id) && seen.insert(id.clone()))
        .collect();

    (changed, deleted)
}

/// The batch sender over the JNI transport.
struct LiveBatcher<'c, 'a, 'local> {
    client: &'c mut Client<'a, 'local>,
    token: &'c str,
}

impl GmailBatcher for LiveBatcher<'_, '_, '_> {
    fn batch(
        &mut self,
        ids: &[String],
        read: Read,
    ) -> Result<Vec<GmailBatchResponse<GmailMessage>>, BridgeError> {
        let requests = ids
            .iter()
            .map(|id| GmailBatchRequest::message_get(id, "me", id, read.format(), read.headers()))
            .collect::<Result<Vec<_>, _>>()
            .map_err(|err| err.to_string())?;
        let auth = HttpAuthBearer::new(self.token);
        let coroutine =
            GmailBatch::<GmailMessage>::new(&auth, &requests).map_err(|err| err.to_string())?;

        // NOTE: one request, but every inner call is billed its own units,
        // so the batch is paced by their sum; and one batch of the account
        // at a time, its inner calls running side by side on Gmail's side.
        let units = quota::MESSAGES_GET * ids.len() as u32;
        let gate = throttle::gmail_gate(self.token);
        let _alone = gate.lock().unwrap_or_else(|err| err.into_inner());
        self.client
            .run_gmail_at(GMAIL_BATCH_URL, self.token, units, coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    fn single(&mut self, id: &str, read: Read) -> Result<Option<GmailMessage>, BridgeError> {
        self.client.gmail_message(self.token, id, read)
    }
}

impl<'a, 'local> Client<'a, 'local> {
    /// The mailbox's current history id, which is also what proves the
    /// token is still good.
    pub fn gmail_history_id(&mut self, token: &str) -> Result<String, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailProfileGet::new(&auth, "me").map_err(|err| err.to_string())?;
        let profile = self.run_gmail(token, quota::GET_PROFILE, coroutine)?;

        profile
            .history_id
            .ok_or_else(|| BridgeError::from("Gmail named no history id"))
    }

    /// The account's labels that file mail, as `(id, mailbox)` pairs.
    ///
    /// A user label's name is already its path, nested by `/`.
    pub fn list_gmail_mailboxes(
        &mut self,
        token: &str,
    ) -> Result<Vec<(String, Mailbox)>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailLabelsList::new(&auth, "me").map_err(|err| err.to_string())?;
        let labels = self.run_gmail(token, quota::LABELS_LIST, coroutine)?.labels;

        Ok(labels
            .into_iter()
            .filter(|label| {
                label.label_type != Some(GmailLabelType::System)
                    || !(NOT_MAILBOXES.contains(&label.id.as_str())
                        || label.id.starts_with("CATEGORY_"))
            })
            .map(|label| {
                let role = match label.id.as_str() {
                    INBOX => "inbox",
                    "SENT" => "sent",
                    "DRAFT" => "drafts",
                    SPAM => "junk",
                    TRASH => "trash",
                    _ => "",
                };
                let mailbox = Mailbox {
                    name: label.name,
                    role: role.into(),
                };
                (label.id, mailbox)
            })
            .collect())
    }

    /// One page of ids, newest received first, 500 a page: the whole
    /// account's when `label` is [`None`], spam and trash left out, else
    /// the ones filed under the label (spam and trash included for those
    /// two and for the marker labels). `since` narrows it to what Gmail
    /// received from that second on (`after:`, inclusive).
    pub fn list_gmail_page(
        &mut self,
        token: &str,
        label: Option<&str>,
        since: Option<i64>,
        page_token: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let label_ids: Vec<String> = label.map(String::from).into_iter().collect();
        let query = since.map(|since| format!("after:{since}"));
        let params = GmailMessagesListParams {
            q: query.as_deref(),
            label_ids: &label_ids,
            max_results: Some(GMAIL_PAGE),
            page_token,
            include_spam_trash: label
                .is_some_and(|label| matches!(label, TRASH | SPAM | UNREAD | STARRED)),
        };

        let coroutine =
            GmailMessagesList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
        let page = self.run_gmail(token, quota::MESSAGES_LIST, coroutine)?;

        Ok(GmailIds {
            ids: page
                .messages
                .into_iter()
                .map(|message| message.id)
                .collect(),
            next: page.next_page_token,
        })
    }

    /// What moved in the whole account since `start`, read unfiltered,
    /// 500 records a page; [`None`] when Gmail no longer holds history
    /// that old, which a full round recovers from.
    pub fn gmail_history(
        &mut self,
        token: &str,
        start: &str,
    ) -> Result<Option<GmailHistoryDelta>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let mut records = Vec::new();
        let mut next = start.to_string();
        let mut page_token: Option<String> = None;

        loop {
            let params = GmailHistoryListParams {
                start_history_id: start,
                max_results: Some(HISTORY_PAGE),
                page_token: page_token.as_deref(),
                ..Default::default()
            };
            let coroutine =
                GmailHistoryList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
            let page =
                match self.run_gmail_at(GMAIL_API_BASE, token, quota::HISTORY_LIST, coroutine)? {
                    Ok(page) => page,
                    Err(err) if err.is_history_expired() => return Ok(None),
                    Err(err) => return Err(coroutine_error(&err)),
                };

            records.extend(page.history);
            if let Some(id) = page.history_id {
                next = id;
            }

            match page.next_page_token {
                Some(token) => page_token = Some(token),
                None => break,
            }
        }

        let (changed, deleted) = history_changes(&records);
        Ok(Some(GmailHistoryDelta {
            changed,
            deleted,
            next,
        }))
    }

    /// One message read alone as `read`; [`None`] when it is gone.
    pub(crate) fn gmail_message(
        &mut self,
        token: &str,
        id: &str,
        read: Read,
    ) -> Result<Option<GmailMessage>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailMessageGet::new(&auth, "me", id, read.format(), read.headers())
            .map_err(|err| err.to_string())?;
        match self.run_gmail_at(GMAIL_API_BASE, token, quota::MESSAGES_GET, coroutine)? {
            Ok(message) => Ok(Some(message)),
            Err(err) if err.is_not_found() => Ok(None),
            Err(err) => Err(coroutine_error(&err)),
        }
    }

    /// One message's labels and envelope, read alone; [`None`] when it is
    /// gone.
    pub fn gmail_envelope(
        &mut self,
        token: &str,
        id: &str,
    ) -> Result<Option<GmailEnvelope>, BridgeError> {
        Ok(self.gmail_message(token, id, Read::Metadata)?.map(envelope))
    }

    /// The labels and envelopes of `ids` ([`read_envelopes`]); [`None`]
    /// for a message gone.
    pub fn gmail_envelopes(
        &mut self,
        token: &str,
        ids: &[String],
    ) -> Result<Vec<(String, Option<GmailEnvelope>)>, BridgeError> {
        read_envelopes(
            &mut LiveBatcher {
                client: self,
                token,
            },
            ids,
        )
    }

    /// Reads `ids` whole into files of `dir` ([`read_sources`]).
    pub fn gmail_sources(
        &mut self,
        token: &str,
        ids: &[String],
        dir: &Path,
    ) -> Result<Vec<Fetched>, BridgeError> {
        read_sources(
            &mut LiveBatcher {
                client: self,
                token,
            },
            ids,
            dir,
        )
    }

    /// Adds or removes one marker on one message, answering its labels
    /// after the change.
    ///
    /// `\Seen` is the absence of `UNREAD` and `\Flagged` is `STARRED`.
    /// Gmail keeps no answered marker and deletes by label, so the other
    /// two are refused the way the JMAP side refuses what it cannot
    /// express.
    pub fn set_gmail_flag(
        &mut self,
        token: &str,
        id: &str,
        flag: &str,
        add: bool,
    ) -> Result<Vec<String>, BridgeError> {
        let (label, labelled) = match flag {
            "\\Seen" => (UNREAD, !add),
            "\\Flagged" => (STARRED, add),
            other => return Err(format!("No Gmail label for the marker `{other}`").into()),
        };
        let label = [label.to_string()];
        let (add, remove): (&[String], &[String]) = match labelled {
            true => (&label, &[]),
            false => (&[], &label),
        };

        let auth = HttpAuthBearer::new(token);
        let coroutine =
            GmailMessageModify::new(&auth, "me", id, add, remove).map_err(|err| err.to_string())?;
        Ok(self
            .run_gmail(token, quota::MESSAGES_MODIFY, coroutine)?
            .label_ids)
    }

    /// Relocates one message from the label `from` to the label `to`, the
    /// way [`relocation`] says Gmail does it.
    pub fn relocate_gmail_message(
        &mut self,
        token: &str,
        id: &str,
        from: &str,
        to: &str,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        match relocation(from, to) {
            GmailRelocation::Trash => {
                let coroutine =
                    GmailMessageTrash::new(&auth, "me", id).map_err(|err| err.to_string())?;
                self.run_gmail(token, quota::MESSAGES_TRASH, coroutine)?;
            }
            GmailRelocation::Untrash { add } => {
                let coroutine =
                    GmailMessageUntrash::new(&auth, "me", id).map_err(|err| err.to_string())?;
                self.run_gmail(token, quota::MESSAGES_UNTRASH, coroutine)?;
                self.modify_gmail_labels(token, id, &add, &[])?;
            }
            GmailRelocation::Modify { add, remove } => {
                self.modify_gmail_labels(token, id, &add, &remove)?;
            }
        }
        Ok(())
    }

    /// Files one message under the label `to` too, the copy a label is.
    pub fn copy_gmail_message(
        &mut self,
        token: &str,
        id: &str,
        to: &str,
    ) -> Result<(), BridgeError> {
        self.modify_gmail_labels(token, id, &[to.to_string()], &[])
    }

    /// Deletes one message for good, bypassing the trash.
    pub fn destroy_gmail_message(&mut self, token: &str, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailMessageDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run_gmail(token, quota::MESSAGES_DELETE, coroutine)?;
        Ok(())
    }

    /// Adds and removes labels on one message.
    fn modify_gmail_labels(
        &mut self,
        token: &str,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            GmailMessageModify::new(&auth, "me", id, add, remove).map_err(|err| err.to_string())?;
        self.run_gmail(token, quota::MESSAGES_MODIFY, coroutine)?;
        Ok(())
    }

    /// Sends one RFC 5322 message, Gmail filing the copy under `SENT`
    /// itself. Returns the id of that copy.
    pub fn send_gmail_message(&mut self, token: &str, raw: &[u8]) -> Result<String, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let message = GmailMessage {
            raw: Some(encode_raw(raw)),
            ..Default::default()
        };
        let coroutine =
            GmailMessageSend::new(&auth, "me", &message).map_err(|err| err.to_string())?;
        let sent = self.run_gmail(token, quota::MESSAGES_SEND, coroutine)?;
        Ok(sent.id)
    }

    /// Runs one Gmail API coroutine costing `units` quota units of the
    /// account `token` signs in to completion ([`Self::run_gmail_at`]),
    /// its failure as the bridge's.
    fn run_gmail<C, T>(&mut self, token: &str, units: u32, coroutine: C) -> Result<T, BridgeError>
    where
        C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
    {
        self.run_gmail_at(GMAIL_API_BASE, token, units, coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// Runs one Gmail coroutine costing `units` quota units to completion
    /// over the transport stream for `url` (the API's, or the batch
    /// endpoint's on another host), paced under the per-user quota of the
    /// account `token` signs in ([`throttle::pace_gmail`], the token
    /// standing for its account). The outer error is the transport's, the
    /// inner one Gmail's, kept whole so its reasons are matched rather
    /// than its text. Logged at debug: the call, its units, the pacing
    /// and the time its answer took.
    pub(crate) fn run_gmail_at<C, T>(
        &mut self,
        url: &str,
        token: &str,
        units: u32,
        mut coroutine: C,
    ) -> Result<Result<T, GmailSendError>, BridgeError>
    where
        C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
    {
        let paced = self.pace_gmail(token, units);
        let started = Instant::now();
        let mut arg: Option<Vec<u8>> = None;

        let answer = loop {
            match coroutine.resume(arg.as_deref()) {
                GmailCoroutineState::Complete(Ok(output)) => break Ok(output.response),
                GmailCoroutineState::Complete(Err(err)) => break Err(err),
                GmailCoroutineState::Yielded(GmailYield::WantsRead) => {
                    arg = Some(self.http_read(url)?);
                }
                GmailCoroutineState::Yielded(GmailYield::WantsWrite(bytes)) => {
                    self.http_write(url, &bytes)?;
                    arg = None;
                }
            }
        };

        log::debug!(
            "gmail {}: {units} units, paced {} ms, answered in {} ms{}",
            call_name::<C>(),
            paced.as_millis(),
            started.elapsed().as_millis(),
            match &answer {
                Ok(_) => String::new(),
                Err(err) => format!(", {err}"),
            }
        );
        Ok(answer)
    }
}

/// A coroutine's type as a call's name for the log: `GmailMessagesList`,
/// `GmailBatch`.
fn call_name<C>() -> &'static str {
    let name = std::any::type_name::<C>();
    let bare = name.split('<').next().unwrap_or(name);
    bare.rsplit("::").next().unwrap_or(bare)
}

/// One metadata read to the labels, markers and summary it carries.
///
/// The header values are what the sender wrote, encoded words included,
/// so they are read as a header block through the derivation a stored
/// message goes through: the date is the `Date` header, never Gmail's
/// reception time, and the attachment mark the top-level `Content-Type`.
fn envelope(message: GmailMessage) -> GmailEnvelope {
    let payload = message.payload.unwrap_or_default();

    let mut headers = String::new();
    for header in &payload.headers {
        if ENVELOPE_HEADERS
            .iter()
            .any(|name| name.eq_ignore_ascii_case(&header.name))
        {
            headers.push_str(&format!("{}: {}\r\n", header.name, header.value));
        }
    }
    headers.push_str("\r\n");

    let derivation = derive_meta(headers.as_bytes(), message.size_estimate, None);
    let summary = match derivation.summary {
        Some(PimdirSummary::Mail(summary)) => summary,
        _ => PimdirMailSummary::default(),
    };

    GmailEnvelope {
        flags: marks(&message.label_ids),
        summary,
        labels: message.label_ids,
    }
}

#[cfg(test)]
#[path = "gmail_tests.rs"]
mod tests;
