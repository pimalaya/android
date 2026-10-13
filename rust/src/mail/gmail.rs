//! # Gmail
//!
//! The [`Gmail`] backend: labels as mailboxes, messages read as the MIME
//! Gmail keeps of them.
//!
//! Gmail files a message once under several labels, so the account is
//! stepped through once (`messages.list`, no `labelIds`) and each message
//! placed in every mailbox its labels name ([`crate::mail::gmail_step`]).
//!
//! Reads go in batches (`POST /batch/gmail/v1`) of at most [`BATCH`], one
//! per account at a time, paced: metadata for what a history names, a
//! `minimal` pre-pass for the sizes, then raw bytes ([`Gmail::read_all`]).
//!
//! One unfiltered history per account replays what moved since a
//! `historyId`, which is what keeps a quiet pass one request.

use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    fs,
    path::{Path, PathBuf},
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
    client::{Client, Host, throttle},
    error::{BridgeError, converged, coroutine_error},
    mail::{
        Mailbox, derivable,
        listing::{GMAIL_PAGE, Named, Scope, flags},
        source_path,
    },
};

/// Gmail's documented units per method ("Usage limits") and pacing rates.
///
/// A batch costs the sum of its calls ([`throttle::pace_gmail`]). Gmail
/// billed a read 5 units of 15,000 a minute (2026-10-10); the documented
/// 20 of 6,000 are counted, the quota Google may enforce any day.
pub mod quota {
    pub const GET_PROFILE: u32 = 1;
    pub const LABELS_LIST: u32 = 1;
    pub const HISTORY_LIST: u32 = 2;
    pub const MESSAGES_LIST: u32 = 5;
    pub const MESSAGES_GET: u32 = 20;
    pub const MESSAGES_MODIFY: u32 = 5;
    pub const MESSAGES_TRASH: u32 = 5;
    pub const MESSAGES_UNTRASH: u32 = 5;
    pub const MESSAGES_DELETE: u32 = 10;
    pub const MESSAGES_SEND: u32 = 100;

    /// The project's quota per user per minute, as documented.
    pub const UNITS_PER_MINUTE_PER_USER: u32 = 6_000;

    /// Units per second an account is held near.
    ///
    /// The measured bucket of 250 billed units at 5 a read, 50 reads a
    /// second, in documented units. The minute's budget binds a long run.
    pub const UNITS_PER_SECOND: u32 = 1_000;

    /// Units let through at once after a pause, on top of a request's own.
    ///
    /// One batch of reads.
    pub const BURST_UNITS: u32 = 200;
}

/// The label Gmail files deleted mail under.
pub const TRASH: &str = "TRASH";

/// The label Gmail files junk under.
pub const SPAM: &str = "SPAM";

/// The label of the inbox.
pub const INBOX: &str = "INBOX";

/// The label marking a message unread.
pub const UNREAD: &str = "UNREAD";

/// The label marking a message starred.
pub const STARRED: &str = "STARRED";

/// Reads per batch.
///
/// A batch's calls run side by side, so Gmail's per-user concurrency
/// binds: on 2026-10-10, batches of 10 drew no refusal in 2,250 reads,
/// 25 drew 1.2 % and 50 drew 19 %.
pub const BATCH: usize = 10;

/// About how many message bytes one raw batch carries, by pre-pass sizes.
///
/// The answer is their base64, a third larger.
pub const RAW_BATCH_BYTES: u64 = 8 * 1024 * 1024;

/// The size past which a message is read alone rather than in a batch.
pub const ALONE_BYTES: u64 = 5 * 1024 * 1024;

/// How many times a refused inner read is sent again before the read fails.
const MAX_TRIES: u32 = 6;

/// History records per `history.list` page, the API's ceiling.
const HISTORY_PAGE: u32 = 500;

/// The headers a metadata read asks for: Annex A's summary and addresses.
///
/// `Date` is the header, never Gmail's reception time; `Content-Type`
/// gives the attachment mark without the body.
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

/// System labels that are markers or views, not places mail is filed.
const NOT_MAILBOXES: [&str; 5] = [UNREAD, STARRED, "IMPORTANT", "CHAT", "YELLOW_STAR"];

/// One message's metadata: its labels, its markers and its summary.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GmailEnvelope {
    pub labels: Vec<String>,
    pub flags: Vec<String>,
    pub summary: PimdirMailSummary,
}

impl GmailEnvelope {
    /// This message as a label listing's member, when its `Date` is in scope.
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

/// Whether a message carrying `labels` is a member of `label`'s mailbox.
///
/// It carries the label, and one in the trash or the spam is a member of
/// that one alone, as Gmail shows it nowhere else.
pub fn belongs(labels: &[String], label: &str) -> bool {
    let has = |id: &str| labels.iter().any(|carried| carried == id);
    has(label) && (label == SPAM || label == TRASH || !(has(SPAM) || has(TRASH)))
}

/// The IMAP markers a message carrying `labels` holds.
fn marks(labels: &[String]) -> Vec<String> {
    let has = |id: &str| labels.iter().any(|carried| carried == id);
    flags(!has(UNREAD), false, has(STARRED))
}

/// What a raw read of some messages brought ([`Gmail::sources`]).
#[derive(Clone, Debug, Default)]
pub struct Sources {
    /// The messages read, each written to its file.
    pub fetched: Vec<Fetched>,
    /// The ids gone since they were listed (404).
    pub gone: Vec<String>,
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
    /// When Gmail received it (`internalDate`, epoch ms): never its date.
    pub received: Option<i64>,
    /// The summary Annex A derives from its bytes.
    pub summary: PimdirMailSummary,
}

/// How a message is relocated between two labels.
#[derive(Debug, Eq, PartialEq)]
pub enum GmailRelocation {
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
pub fn relocation(from: &str, to: &str) -> GmailRelocation {
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

/// What an account's history holds past a `historyId`.
///
/// The messages that moved (added, labelled, unlabelled), those deleted
/// for good, and the history id the next replay starts from.
#[derive(Clone, Debug, Default)]
pub struct GmailHistoryDelta {
    pub changed: Vec<String>,
    pub deleted: Vec<String>,
    pub next: String,
}

/// What a message is read as.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Read {
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
pub enum Inner {
    /// Read.
    Read(Box<GmailMessage>),
    /// Gone since it was listed (404).
    Gone,
    /// A concurrency or rate refusal, a transient failure or no answer.
    ///
    /// Sent again in the next batch after a second or two.
    Busy,
    /// A per-minute quota refusal.
    ///
    /// Sent again after a few seconds, more each time, never the next
    /// wall-clock minute.
    Minute,
    /// A daily quota refusal: nothing within a sync lifts it.
    Daily,
    /// Any other refusal, a 401 included, failing the read with its status.
    ///
    /// So the token is refreshed as for one request.
    Failed(BridgeError),
}

/// How one inner answer of a batch is taken ([`Inner`]).
pub fn inner(result: Result<GmailMessage, GmailBatchResponseError>) -> Inner {
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

/// The wait after a concurrency refusal: a second or two, at random.
///
/// So the next batch does not land on the same instant.
fn busy_pause() -> Duration {
    Duration::from_millis(rand::random_range(1_000..=2_000))
}

/// The wait after a read's `count`th per-minute refusal: 2 s up to 5 s.
///
/// The quota is a bucket refilling by the second, measured clearing in
/// 2 s: waiting for the next minute would only stall the run.
pub fn minute_pause(count: u32) -> Duration {
    Duration::from_secs(u64::from(count.clamp(1, 4)) + 1)
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

/// The ids a history replay touched, in first-seen order, and deleted.
///
/// The ones deleted for good are not read again.
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

/// A Gmail account, signed in to with an OAuth 2.0 token.
///
/// The token also stands for the account its requests are paced under.
pub struct Gmail<'c, H> {
    pub client: &'c mut Client<H>,
    pub token: &'c str,
}

impl<H: Host> Gmail<'_, H> {
    /// The mailbox's current history id, also proving the token still good.
    pub fn history_id(&mut self) -> Result<String, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GmailProfileGet::new(&auth, "me").map_err(|err| err.to_string())?;
        let profile = self.run(quota::GET_PROFILE, coroutine)?;

        profile
            .history_id
            .ok_or_else(|| BridgeError::from("Gmail named no history id"))
    }

    /// The account's labels that file mail, as `(id, mailbox)` pairs.
    ///
    /// A user label's name is already its path, nested by `/`.
    pub fn list_mailboxes(&mut self) -> Result<Vec<(String, Mailbox)>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GmailLabelsList::new(&auth, "me").map_err(|err| err.to_string())?;
        let labels = self.run(quota::LABELS_LIST, coroutine)?.labels;

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

    /// One page of ids, newest received first, 500 a page.
    ///
    /// The whole account's, spam and trash out, when `label` is [`None`];
    /// else the label's (spam and trash in for those and marker labels).
    /// `since` keeps what Gmail received from that second (`after:`).
    pub fn list_page(
        &mut self,
        label: Option<&str>,
        since: Option<i64>,
        page_token: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
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
        let page = self.run(quota::MESSAGES_LIST, coroutine)?;

        Ok(GmailIds {
            ids: page
                .messages
                .into_iter()
                .map(|message| message.id)
                .collect(),
            next: page.next_page_token,
        })
    }

    /// What moved in the whole account since `start`, read unfiltered.
    ///
    /// 500 records a page; [`None`] when Gmail no longer holds history
    /// that old, which a full round recovers from.
    pub fn history(&mut self, start: &str) -> Result<Option<GmailHistoryDelta>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
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
            let page = match self.run_at(GMAIL_API_BASE, quota::HISTORY_LIST, coroutine)? {
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
    pub fn message(&mut self, id: &str, read: Read) -> Result<Option<GmailMessage>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GmailMessageGet::new(&auth, "me", id, read.format(), read.headers())
            .map_err(|err| err.to_string())?;
        match self.run_at(GMAIL_API_BASE, quota::MESSAGES_GET, coroutine)? {
            Ok(message) => Ok(Some(message)),
            Err(err) if err.is_not_found() => Ok(None),
            Err(err) => Err(coroutine_error(&err)),
        }
    }

    /// One message's labels and envelope, read alone; [`None`] when gone.
    pub fn envelope(&mut self, id: &str) -> Result<Option<GmailEnvelope>, BridgeError> {
        Ok(self.message(id, Read::Metadata)?.map(envelope))
    }

    /// Reads the metadata of `ids` ([`Self::read_all`]).
    ///
    /// Each id is answered once, with [`None`] for a message gone.
    pub fn envelopes(
        &mut self,
        ids: &[String],
    ) -> Result<Vec<(String, Option<GmailEnvelope>)>, BridgeError> {
        let mut read = Vec::with_capacity(ids.len());
        self.read_all(Read::Metadata, ids, &HashMap::new(), |id, message| {
            read.push((id, message.map(envelope)));
            Ok(())
        })?;
        Ok(read)
    }

    /// Reads `ids` whole into files of `dir`, one per message.
    ///
    /// A `minimal` pre-pass for sizes, then raw bytes ([`Self::read_all`]).
    /// One gone since listed (404) is named in [`Sources::gone`]; every
    /// other one is read or fails the call.
    pub fn sources(&mut self, ids: &[String], dir: &Path) -> Result<Sources, BridgeError> {
        let mut sizes = HashMap::new();
        let mut there = Vec::with_capacity(ids.len());
        let mut gone = Vec::new();
        self.read_all(Read::Minimal, ids, &HashMap::new(), |id, message| {
            match message {
                Some(message) => {
                    sizes.insert(id.clone(), message.size_estimate.unwrap_or_default());
                    there.push(id);
                }
                None => gone.push(id),
            }
            Ok(())
        })?;

        let mut fetched = Vec::with_capacity(there.len());
        self.read_all(Read::Raw, &there, &sizes, |id, message| {
            match message {
                Some(message) => fetched.push(written(id, message, dir)?),
                None => gone.push(id),
            }
            Ok(())
        })?;
        Ok(Sources { fetched, gone })
    }

    /// Reads `ids` as `read`, each handed to `each` once, [`None`] if gone.
    ///
    /// Batches of at most [`BATCH`], one after another; a raw batch holds
    /// about [`RAW_BATCH_BYTES`] by `sizes`, a message past [`ALONE_BYTES`]
    /// alone. A refused inner read heads the next batch ([`Inner`]).
    pub fn read_all(
        &mut self,
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
                self.client.host.sleep(pause);
                pause = Duration::ZERO;
            }
            if size(front) > ALONE_BYTES {
                let id = queue.pop_front().unwrap_or_default();
                let message = self.message(&id, read)?;
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

            let mut answers: HashMap<String, Result<GmailMessage, GmailBatchResponseError>> = self
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

    /// One batch of reads, answered in any order.
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

        // NOTE: every inner call is billed its own units, so the batch is
        // paced by their sum; one batch per account at a time, as its
        // calls run side by side on Gmail's side.
        let units = quota::MESSAGES_GET * ids.len() as u32;
        let gate = throttle::gmail_gate(self.token);
        let _alone = gate.lock().unwrap_or_else(|err| err.into_inner());
        self.run_at(GMAIL_BATCH_URL, units, coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// Adds or removes one marker, answering the labels after ([`converged`]).
    ///
    /// `\Seen` is the absence of `UNREAD` and `\Flagged` is `STARRED`.
    /// Gmail keeps no answered marker and deletes by label, so the other
    /// two are refused, as the JMAP side refuses what it cannot express.
    pub fn set_flag(
        &mut self,
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

        let auth = HttpAuthBearer::new(self.token);
        let coroutine =
            GmailMessageModify::new(&auth, "me", id, add, remove).map_err(|err| err.to_string())?;
        let modified = converged(self.run(quota::MESSAGES_MODIFY, coroutine))?;
        Ok(modified
            .map(|message| message.label_ids)
            .unwrap_or_default())
    }

    /// Relocates one message from label `from` to `to` ([`relocation`]).
    ///
    /// A message gone stays gone ([`converged`]).
    pub fn relocate_message(&mut self, id: &str, from: &str, to: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        match relocation(from, to) {
            GmailRelocation::Trash => {
                let coroutine =
                    GmailMessageTrash::new(&auth, "me", id).map_err(|err| err.to_string())?;
                converged(self.run(quota::MESSAGES_TRASH, coroutine))?;
            }
            GmailRelocation::Untrash { add } => {
                let coroutine =
                    GmailMessageUntrash::new(&auth, "me", id).map_err(|err| err.to_string())?;
                if converged(self.run(quota::MESSAGES_UNTRASH, coroutine))?.is_some() {
                    converged(self.modify_labels(id, &add, &[]))?;
                }
            }
            GmailRelocation::Modify { add, remove } => {
                converged(self.modify_labels(id, &add, &remove))?;
            }
        }
        Ok(())
    }

    /// Files one message under the label `to` too, the copy a label is.
    ///
    /// A message gone fails with its 404: a copy of nothing has no
    /// arrival to wait for, so the caller refuses it.
    pub fn copy_message(&mut self, id: &str, to: &str) -> Result<(), BridgeError> {
        self.modify_labels(id, &[to.to_string()], &[])
    }

    /// Deletes one message for good, bypassing the trash ([`converged`]).
    pub fn destroy_message(&mut self, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GmailMessageDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        converged(self.run(quota::MESSAGES_DELETE, coroutine))?;
        Ok(())
    }

    /// Adds and removes labels on one message.
    fn modify_labels(
        &mut self,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine =
            GmailMessageModify::new(&auth, "me", id, add, remove).map_err(|err| err.to_string())?;
        self.run(quota::MESSAGES_MODIFY, coroutine)?;
        Ok(())
    }

    /// Sends one RFC 5322 message, returning the id of its copy.
    ///
    /// Gmail files the copy under `SENT` itself.
    pub fn send_message(&mut self, raw: &[u8]) -> Result<String, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let message = GmailMessage {
            raw: Some(encode_raw(raw)),
            ..Default::default()
        };
        let coroutine =
            GmailMessageSend::new(&auth, "me", &message).map_err(|err| err.to_string())?;
        let sent = self.run(quota::MESSAGES_SEND, coroutine)?;
        Ok(sent.id)
    }

    /// Runs one Gmail API coroutine costing `units` ([`Self::run_at`]).
    fn run<C, T>(&mut self, units: u32, coroutine: C) -> Result<T, BridgeError>
    where
        C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
    {
        self.run_at(GMAIL_API_BASE, units, coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// Runs one Gmail coroutine costing `units` over the stream for `url`.
    ///
    /// Paced under the token's account quota ([`throttle::pace_gmail`]);
    /// `url` is the API's or the batch host's. The inner error is Gmail's,
    /// kept whole so its reasons are matched rather than its text.
    pub fn run_at<C, T>(
        &mut self,
        url: &str,
        units: u32,
        mut coroutine: C,
    ) -> Result<Result<T, GmailSendError>, BridgeError>
    where
        C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
    {
        let paced = self.client.pace_gmail(self.token, units);
        let started = Instant::now();
        let mut arg: Option<Vec<u8>> = None;

        let answer = loop {
            match coroutine.resume(arg.as_deref()) {
                GmailCoroutineState::Complete(Ok(output)) => break Ok(output.response),
                GmailCoroutineState::Complete(Err(err)) => break Err(err),
                GmailCoroutineState::Yielded(GmailYield::WantsRead) => {
                    arg = Some(self.client.http_read(url)?);
                }
                GmailCoroutineState::Yielded(GmailYield::WantsWrite(bytes)) => {
                    self.client.http_write(url, &bytes)?;
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

/// A coroutine's type as a call's name for the log (`GmailBatch`).
fn call_name<C>() -> &'static str {
    let name = std::any::type_name::<C>();
    let bare = name.split('<').next().unwrap_or(name);
    bare.rsplit("::").next().unwrap_or(bare)
}

/// One metadata read to the labels, markers and summary it carries.
///
/// Header values are raw, encoded words included, so they go through the
/// stored-message derivation: the date is `Date`, never Gmail's reception
/// time, and the attachment mark the top-level `Content-Type`.
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
pub mod tests;
