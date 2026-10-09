//! Gmail API mail: labels as mailboxes and messages read as the MIME
//! Gmail keeps of them, the Gmail half of what `syncMail` does over
//! IMAP, JMAP and Graph.
//!
//! Gmail files a message once and names it under several labels, so the
//! account is listed once (`messages.list` with no `labelIds`, spam and
//! trash included, 500 ids a page) and each message placed in every
//! mailbox its labels name ([`super::gmail_sync`]). A listing names ids
//! and nothing else, so a message's summary, markers and labels cost one
//! metadata read, sent 50 to a batch (`POST /batch/gmail/v1`) under the
//! account's pacing, an inner answer that was throttled sent again on its
//! own. One unfiltered history per account replays what moved since a
//! `historyId`, which is what keeps a quiet pass one request.

use std::collections::BTreeSet;

use io_gmail::{
    coroutine::*,
    v1::{
        batch::{GMAIL_BATCH_URL, GmailBatch, GmailBatchRequest, GmailBatchResponse},
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
    PimdirSummary,
    mail::{PimdirMailSummary, derive_meta},
};

use crate::{
    client::{
        Client,
        convert::coroutine_error,
        listing::{GMAIL_PAGE, Named, RECEIVED_MARGIN_DAYS, Scope, flags},
        throttle,
    },
    types::{BridgeError, Mailbox},
};

/// The quota units each Gmail method costs, as Google documents them
/// (Gmail API, "Usage limits"), which [`throttle::pace_gmail`] spends
/// against the per-minute budget. A batch costs the sum of its calls.
pub(crate) mod units {
    pub(crate) const GET_PROFILE: u32 = 1;
    pub(crate) const LABELS_LIST: u32 = 1;
    pub(crate) const HISTORY_LIST: u32 = 2;
    pub(crate) const MESSAGES_LIST: u32 = 5;
    pub(crate) const MESSAGES_GET: u32 = 5;
    pub(crate) const MESSAGES_MODIFY: u32 = 5;
    pub(crate) const MESSAGES_TRASH: u32 = 5;
    pub(crate) const MESSAGES_UNTRASH: u32 = 5;
    pub(crate) const MESSAGES_DELETE: u32 = 10;
    pub(crate) const MESSAGES_SEND: u32 = 100;
}

/// The label Gmail files deleted mail under.
pub(crate) const TRASH: &str = "TRASH";

/// The label Gmail files junk under.
pub(crate) const SPAM: &str = "SPAM";

/// The label of the inbox.
pub(crate) const INBOX: &str = "INBOX";

/// Metadata reads per batch: the most Google advises for Gmail, whose
/// larger batches draw rate-limited parts.
pub(crate) const BATCH: usize = 50;

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
const NOT_MAILBOXES: [&str; 5] = ["UNREAD", "STARRED", "IMPORTANT", "CHAT", "YELLOW_STAR"];

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

/// How Gmail relocates one message between two labels.
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

/// What one batch of metadata reads came to: the messages read, each
/// with its envelope or [`None`] when it is gone, and the ids whose inner
/// answer was throttled or missing, to read again alone.
#[derive(Debug, Default)]
pub(crate) struct Settled {
    pub read: Vec<(String, Option<GmailEnvelope>)>,
    pub again: Vec<String>,
}

/// Sorts a batch's answers into read, gone and to read again: a 404 is a
/// message gone since it was listed, a throttled or missing answer is
/// sent again on its own, and any other refusal fails the read with its
/// status, a 401 included, so the token is refreshed as for one request.
pub(crate) fn settle(
    responses: Vec<GmailBatchResponse<GmailMessage>>,
) -> Result<Settled, BridgeError> {
    let mut settled = Settled::default();
    for response in responses {
        match response.result {
            Ok(message) => settled.read.push((response.id, Some(envelope(message)))),
            Err(err) if err.is_not_found() => settled.read.push((response.id, None)),
            Err(err) if err.is_retryable() => settled.again.push(response.id),
            Err(err) => {
                return Err(BridgeError {
                    message: err.to_string(),
                    status: err.status(),
                });
            }
        }
    }
    Ok(settled)
}

/// How metadata reads are sent: a batch, and one read alone for an inner
/// answer that has to be sent again.
///
/// [`Client`] is the one sender the app runs; the trait exists so the
/// reads can be counted and throttled without a JVM.
pub(crate) trait GmailBatcher {
    /// One batch of metadata reads, answered in the order of `ids`.
    fn batch(
        &mut self,
        ids: &[String],
    ) -> Result<Vec<GmailBatchResponse<GmailMessage>>, BridgeError>;

    /// One metadata read alone, through the throttled path: [`None`] when
    /// the message is gone.
    fn single(&mut self, id: &str) -> Result<Option<GmailEnvelope>, BridgeError>;
}

/// Reads the metadata of `ids`, [`BATCH`] to a batch, an inner answer
/// that was throttled or missing read again on its own; each id answered
/// once, with [`None`] for a message gone.
pub(crate) fn read_envelopes<B: GmailBatcher>(
    batcher: &mut B,
    ids: &[String],
) -> Result<Vec<(String, Option<GmailEnvelope>)>, BridgeError> {
    let mut read = Vec::with_capacity(ids.len());
    for chunk in ids.chunks(BATCH) {
        let settled = settle(batcher.batch(chunk)?)?;
        read.extend(settled.read);
        for id in settled.again {
            let envelope = batcher.single(&id)?;
            read.push((id, envelope));
        }
    }
    Ok(read)
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
    ) -> Result<Vec<GmailBatchResponse<GmailMessage>>, BridgeError> {
        let requests = ids
            .iter()
            .map(|id| {
                GmailBatchRequest::message_get(
                    id,
                    "me",
                    id,
                    GmailMessageFormat::Metadata,
                    &ENVELOPE_HEADERS,
                )
            })
            .collect::<Result<Vec<_>, _>>()
            .map_err(|err| err.to_string())?;
        let auth = HttpAuthBearer::new(self.token);
        let coroutine =
            GmailBatch::<GmailMessage>::new(&auth, &requests).map_err(|err| err.to_string())?;

        // NOTE: one request, one per-second slot, but every inner call is
        // billed its own units, so the batch spends their sum.
        let units = units::MESSAGES_GET * ids.len() as u32;
        self.client
            .run_gmail_at(GMAIL_BATCH_URL, units, coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    fn single(&mut self, id: &str) -> Result<Option<GmailEnvelope>, BridgeError> {
        self.client.gmail_envelope(self.token, id)
    }
}

impl<'a, 'local> Client<'a, 'local> {
    /// The mailbox's current history id, which is also what proves the
    /// token is still good.
    pub fn gmail_history_id(&mut self, token: &str) -> Result<String, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailProfileGet::new(&auth, "me").map_err(|err| err.to_string())?;
        let profile = self.run_gmail(units::GET_PROFILE, coroutine)?;

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
        let labels = self.run_gmail(units::LABELS_LIST, coroutine)?.labels;

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

    /// One page of ids, newest first, 500 a page: the whole account's,
    /// spam and trash included, when `label` is [`None`], else the ones
    /// filed under the label. Narrowed to what Gmail received from two
    /// days below the scope's floor (`after:`, whole days in Gmail's own
    /// zone, hence the margin) and up to two days above its ceiling.
    pub fn list_gmail_page(
        &mut self,
        token: &str,
        label: Option<&str>,
        scope: &Scope,
        page_token: Option<&str>,
    ) -> Result<GmailIds, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let label_ids: Vec<String> = label.map(String::from).into_iter().collect();
        let mut query = Vec::new();
        if let Some(since) = scope.received_since(RECEIVED_MARGIN_DAYS) {
            query.push(format!("after:{}", since.as_second()));
        }
        if let Some(until) = scope.received_until(RECEIVED_MARGIN_DAYS) {
            query.push(format!("before:{}", until.as_second()));
        }
        let query = query.join(" ");
        let params = GmailMessagesListParams {
            q: Some(query.as_str()).filter(|query| !query.is_empty()),
            label_ids: &label_ids,
            max_results: Some(GMAIL_PAGE),
            page_token,
            include_spam_trash: label.is_none_or(|label| label == TRASH || label == SPAM),
        };

        let coroutine =
            GmailMessagesList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
        let page = self.run_gmail(units::MESSAGES_LIST, coroutine)?;

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
            let page = match self.run_gmail_at(GMAIL_API_BASE, units::HISTORY_LIST, coroutine)? {
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

    /// One message's labels and envelope, read alone; [`None`] when it is
    /// gone.
    pub fn gmail_envelope(
        &mut self,
        token: &str,
        id: &str,
    ) -> Result<Option<GmailEnvelope>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailMessageGet::new(
            &auth,
            "me",
            id,
            GmailMessageFormat::Metadata,
            &ENVELOPE_HEADERS,
        )
        .map_err(|err| err.to_string())?;
        match self.run_gmail_at(GMAIL_API_BASE, units::MESSAGES_GET, coroutine)? {
            Ok(message) => Ok(Some(envelope(message))),
            Err(err) if err.is_not_found() => Ok(None),
            Err(err) => Err(coroutine_error(&err)),
        }
    }

    /// The labels and envelopes of `ids`, [`BATCH`] to a batch
    /// ([`read_envelopes`]); [`None`] for a message gone.
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

    /// Reads one message whole, as the RFC 5322 bytes Gmail serves.
    pub fn fetch_gmail_source(&mut self, token: &str, id: &str) -> Result<Vec<u8>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailMessageGet::new(&auth, "me", id, GmailMessageFormat::Raw, &[])
            .map_err(|err| err.to_string())?;
        let message = self.run_gmail(units::MESSAGES_GET, coroutine)?;

        let raw = message
            .raw
            .ok_or_else(|| BridgeError::from(format!("Gmail sent no source for `{id}`")))?;
        decode_raw(&raw).map_err(|err| format!("Invalid Gmail source: {err}").into())
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
            "\\Seen" => ("UNREAD", !add),
            "\\Flagged" => ("STARRED", add),
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
        Ok(self.run_gmail(units::MESSAGES_MODIFY, coroutine)?.label_ids)
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
                self.run_gmail(units::MESSAGES_TRASH, coroutine)?;
            }
            GmailRelocation::Untrash { add } => {
                let coroutine =
                    GmailMessageUntrash::new(&auth, "me", id).map_err(|err| err.to_string())?;
                self.run_gmail(units::MESSAGES_UNTRASH, coroutine)?;
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
        self.run_gmail(units::MESSAGES_DELETE, coroutine)?;
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
        self.run_gmail(units::MESSAGES_MODIFY, coroutine)?;
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
        let sent = self.run_gmail(units::MESSAGES_SEND, coroutine)?;
        Ok(sent.id)
    }

    /// Runs one Gmail API coroutine costing `units` quota units to
    /// completion ([`Self::run_gmail_at`]), its failure as the bridge's.
    fn run_gmail<C, T>(&mut self, units: u32, coroutine: C) -> Result<T, BridgeError>
    where
        C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
    {
        self.run_gmail_at(GMAIL_API_BASE, units, coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// Runs one Gmail coroutine costing `units` quota units to completion
    /// over the transport stream for `url` (the API's, or the batch
    /// endpoint's on another host), paced under Gmail's per-user quota
    /// ([`throttle::pace_gmail`]). The outer error is the transport's, the
    /// inner one Gmail's, kept whole so its reasons are matched rather
    /// than its text.
    pub(crate) fn run_gmail_at<C, T>(
        &mut self,
        url: &str,
        units: u32,
        mut coroutine: C,
    ) -> Result<Result<T, GmailSendError>, BridgeError>
    where
        C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
    {
        throttle::pace_gmail(units);
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                GmailCoroutineState::Complete(Ok(output)) => return Ok(Ok(output.response)),
                GmailCoroutineState::Complete(Err(err)) => return Ok(Err(err)),
                GmailCoroutineState::Yielded(GmailYield::WantsRead) => {
                    arg = Some(self.http_read(url)?);
                }
                GmailCoroutineState::Yielded(GmailYield::WantsWrite(bytes)) => {
                    self.http_write(url, &bytes)?;
                    arg = None;
                }
            }
        }
    }
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
    let labels = message.label_ids;

    GmailEnvelope {
        flags: flags(
            !labels.iter().any(|label| label == "UNREAD"),
            false,
            labels.iter().any(|label| label == "STARRED"),
        ),
        summary,
        labels,
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use io_gmail::v1::rest::messages::{GmailMessageHeader, GmailMessagePayload};

    use super::*;

    fn header(name: &str, value: &str) -> GmailMessageHeader {
        GmailMessageHeader {
            name: name.into(),
            value: value.into(),
        }
    }

    #[test]
    fn a_relocation_into_the_trash_is_a_trash() {
        assert_eq!(relocation("INBOX", TRASH), GmailRelocation::Trash);
    }

    #[test]
    fn a_relocation_out_of_the_trash_untrashes_first() {
        assert_eq!(
            relocation(TRASH, "Label_7"),
            GmailRelocation::Untrash {
                add: vec!["Label_7".into()]
            }
        );
    }

    #[test]
    fn a_relocation_between_labels_swaps_them() {
        assert_eq!(
            relocation(INBOX, "Label_7"),
            GmailRelocation::Modify {
                add: vec!["Label_7".into()],
                remove: vec![INBOX.into()],
            }
        );
    }

    #[test]
    fn a_metadata_read_becomes_a_decoded_envelope() {
        let message = GmailMessage {
            id: "m1".into(),
            label_ids: vec!["INBOX".into(), "UNREAD".into(), "STARRED".into()],
            // NOTE: the reception, a day after the `Date`: never the date.
            internal_date: Some("1700086400000".into()),
            payload: Some(GmailMessagePayload {
                headers: vec![
                    header("Subject", "=?UTF-8?Q?Caf=C3=A9?="),
                    header("From", "=?UTF-8?Q?Ren=C3=A9?= <rene@example.org>"),
                    header("Date", "Tue, 14 Nov 2023 22:13:20 +0000"),
                    header("Content-Type", "multipart/mixed; boundary=x"),
                ],
                ..Default::default()
            }),
            ..Default::default()
        };

        let envelope = envelope(message);

        assert_eq!(envelope.summary.subject, "Café");
        assert_eq!(envelope.summary.sender_name.as_deref(), Some("René"));
        assert_eq!(envelope.summary.sender.as_deref(), Some("rene@example.org"));
        assert_eq!(
            envelope.summary.date.as_deref(),
            Some("2023-11-14T22:13:20Z")
        );
        assert_eq!(envelope.summary.attachment, Some(true));
        assert!(!envelope.flags.iter().any(|flag| flag == "\\Seen"));
        assert!(envelope.flags.iter().any(|flag| flag == "\\Flagged"));
    }

    #[test]
    fn trash_and_spam_take_a_message_out_of_its_other_labels() {
        let labels = |ids: &[&str]| ids.iter().map(|id| id.to_string()).collect::<Vec<_>>();

        let filed = labels(&["INBOX", "Label_7", "IMPORTANT"]);
        assert!(belongs(&filed, "INBOX"));
        assert!(belongs(&filed, "Label_7"));
        assert!(!belongs(&filed, "SENT"));

        let trashed = labels(&["TRASH", "Label_7"]);
        assert!(belongs(&trashed, "TRASH"));
        assert!(
            !belongs(&trashed, "Label_7"),
            "a trashed message is in the trash alone"
        );

        let junk = labels(&["SPAM", "INBOX"]);
        assert!(belongs(&junk, "SPAM"));
        assert!(!belongs(&junk, "INBOX"));
    }

    #[test]
    fn a_history_names_each_move_once_and_reads_no_deleted_message() {
        let message = |id: &str| serde_json::json!({ "message": { "id": id } });
        let labelled =
            |id: &str| serde_json::json!({ "message": { "id": id }, "labelIds": ["INBOX"] });
        let records: Vec<GmailHistory> = serde_json::from_value(serde_json::json!([
            { "id": "1", "messagesAdded": [message("a"), message("b")] },
            { "id": "2", "labelsRemoved": [labelled("a")], "labelsAdded": [labelled("c")] },
            { "id": "3", "messagesDeleted": [message("b"), message("d")] },
        ]))
        .unwrap();

        let (changed, deleted) = history_changes(&records);
        assert_eq!(changed, ["a", "c"], "b was deleted after its arrival");
        assert_eq!(deleted, ["b", "d"]);
    }

    /// A metadata answer as Gmail writes it, for one message.
    fn metadata_json(id: &str) -> String {
        serde_json::json!({
            "id": id,
            "threadId": "t1",
            "labelIds": ["INBOX", "Label_7", "UNREAD"],
            "sizeEstimate": 2048,
            "internalDate": "1700086400000",
            "payload": {
                "mimeType": "multipart/alternative",
                "headers": [
                    {"name": "Date", "value": "Tue, 14 Nov 2023 22:13:20 +0100"},
                    {"name": "From", "value": "Ren\u{e9} <rene@example.org>"},
                    {"name": "To", "value": "jane@example.com"},
                    {"name": "Subject", "value": "=?UTF-8?Q?R=C3=A9union?="},
                    {"name": "Message-ID", "value": "<m1@example.org>"},
                    {"name": "Content-Type", "value": "multipart/alternative; boundary=b"}
                ]
            }
        })
        .to_string()
    }

    /// Runs a Gmail coroutine against one canned answer.
    fn drive<C, T>(mut coroutine: C, answer: &[u8]) -> Result<GmailSendOutput<T>, GmailSendError>
    where
        C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
    {
        let mut answered = false;
        let mut arg: Option<&[u8]> = None;
        loop {
            match coroutine.resume(arg.take()) {
                GmailCoroutineState::Complete(result) => return result,
                GmailCoroutineState::Yielded(GmailYield::WantsWrite(_)) => {}
                GmailCoroutineState::Yielded(GmailYield::WantsRead) => {
                    arg = Some(match answered {
                        false => answer,
                        true => &[],
                    });
                    answered = true;
                }
            }
        }
    }

    fn http(status: &str, content_type: &str, body: &str) -> Vec<u8> {
        format!(
            "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\n\r\n{body}",
            body.len()
        )
        .into_bytes()
    }

    /// A batch answer: one part per `(id, status, body)`, in the order
    /// given, which Google does not promise to keep.
    fn batch_answer(parts: &[(&str, &str, String)]) -> Vec<u8> {
        let mut body = String::new();
        for (id, status, json) in parts {
            body.push_str("--batch_x\r\nContent-Type: application/http\r\n");
            body.push_str(&format!("Content-ID: <response-{id}>\r\n\r\n"));
            body.push_str(&format!(
                "HTTP/1.1 {status}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{json}\r\n"
            ));
        }
        body.push_str("--batch_x--\r\n");
        http("200 OK", "multipart/mixed; boundary=batch_x", &body)
    }

    fn batch_of(ids: &[&str]) -> GmailBatch<GmailMessage> {
        let requests: Vec<GmailBatchRequest> = ids
            .iter()
            .map(|id| {
                GmailBatchRequest::message_get(
                    id,
                    "me",
                    id,
                    GmailMessageFormat::Metadata,
                    &ENVELOPE_HEADERS,
                )
                .unwrap()
            })
            .collect();
        GmailBatch::new(&HttpAuthBearer::new("token"), &requests).unwrap()
    }

    #[test]
    fn a_batched_read_equals_a_single_read() {
        let json = metadata_json("18b0a1");

        let auth = HttpAuthBearer::new("token");
        let get = GmailMessageGet::new(
            &auth,
            "me",
            "18b0a1",
            GmailMessageFormat::Metadata,
            &ENVELOPE_HEADERS,
        )
        .unwrap();
        let single = drive(get, &http("200 OK", "application/json", &json)).unwrap();
        let single = envelope(single.response);

        let answer = batch_answer(&[("18b0a1", "200 OK", json.clone())]);
        let batched = drive(batch_of(&["18b0a1"]), &answer).unwrap();
        let settled = settle(batched.response).unwrap();

        assert!(settled.again.is_empty());
        assert_eq!(settled.read, [("18b0a1".to_string(), Some(single.clone()))]);
        assert_eq!(single.summary.subject, "Réunion");
        assert_eq!(single.summary.date.as_deref(), Some("2023-11-14T21:13:20Z"));
        assert!(single.belongs("Label_7"));
    }

    /// The minute quota refusal Gmail answered on 2026-10-07.
    const MINUTE_QUOTA: &str = r#"{"error":{"code":403,"message":"Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user'","errors":[{"message":"Quota exceeded","domain":"usageLimits","reason":"rateLimitExceeded"}],"status":"PERMISSION_DENIED"}}"#;

    #[test]
    fn a_batch_sorts_its_answers_into_read_gone_and_again() {
        let answer = batch_answer(&[
            ("a1", "200 OK", metadata_json("a1")),
            ("b2", "404 Not Found", r#"{"error":{"code":404}}"#.into()),
            ("c3", "429 Too Many Requests", "{}".into()),
            ("d4", "403 Forbidden", MINUTE_QUOTA.into()),
        ]);
        // NOTE: e5 has no part in the answer at all.
        let batched = drive(batch_of(&["a1", "b2", "c3", "d4", "e5"]), &answer).unwrap();
        let settled = settle(batched.response).unwrap();

        let read: BTreeMap<String, bool> = settled
            .read
            .iter()
            .map(|(id, envelope)| (id.clone(), envelope.is_some()))
            .collect();
        assert_eq!(
            read,
            BTreeMap::from([("a1".into(), true), ("b2".into(), false)])
        );
        assert_eq!(settled.again, ["c3", "d4", "e5"]);

        // NOTE: a refusal no wait lifts fails the read with its status.
        let answer = batch_answer(&[("a1", "401 Unauthorized", "{}".into())]);
        let batched = drive(batch_of(&["a1"]), &answer).unwrap();
        assert_eq!(settle(batched.response).unwrap_err().status, Some(401));
    }

    /// A Gmail answering batches from a script: the inner status of each
    /// message, the throttled ones served on their own.
    #[derive(Default)]
    struct FakeBatcher {
        /// Messages Gmail throttles inside a batch, served alone.
        throttled: BTreeSet<String>,
        /// Messages gone.
        gone: BTreeSet<String>,
        batches: Vec<usize>,
        singles: Vec<String>,
    }

    impl GmailBatcher for FakeBatcher {
        fn batch(
            &mut self,
            ids: &[String],
        ) -> Result<Vec<GmailBatchResponse<GmailMessage>>, BridgeError> {
            self.batches.push(ids.len());
            let parts: Vec<(&str, &str, String)> = ids
                .iter()
                .map(|id| match () {
                    _ if self.throttled.contains(id) => {
                        (id.as_str(), "429 Too Many Requests", "{}".to_string())
                    }
                    _ if self.gone.contains(id) => (id.as_str(), "404 Not Found", "{}".to_string()),
                    _ => (id.as_str(), "200 OK", metadata_json(id)),
                })
                .collect();
            let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
            Ok(drive(batch_of(&ids), &batch_answer(&parts))
                .unwrap()
                .response)
        }

        fn single(&mut self, id: &str) -> Result<Option<GmailEnvelope>, BridgeError> {
            self.singles.push(id.into());
            let message: GmailMessage = serde_json::from_str(&metadata_json(id)).unwrap();
            Ok(Some(envelope(message)))
        }
    }

    #[test]
    fn a_hundred_and_twenty_reads_ride_three_batches_the_throttled_alone() {
        let ids: Vec<String> = (0..120).map(|n| format!("m{n:03}")).collect();
        let mut batcher = FakeBatcher {
            throttled: ["m007", "m061"].map(String::from).into(),
            gone: ["m100"].map(String::from).into(),
            ..Default::default()
        };

        let read = read_envelopes(&mut batcher, &ids).unwrap();

        assert_eq!(batcher.batches, [50, 50, 20], "three batch requests");
        assert_eq!(batcher.singles, ["m007", "m061"], "the throttled two alone");
        assert_eq!(read.len(), 120, "every id answered once");
        let gone: Vec<&str> = read
            .iter()
            .filter(|(_, envelope)| envelope.is_none())
            .map(|(id, _)| id.as_str())
            .collect();
        assert_eq!(gone, ["m100"]);
    }
}
