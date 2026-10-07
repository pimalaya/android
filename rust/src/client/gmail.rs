//! Gmail API mail: labels as mailboxes and messages read as the MIME
//! Gmail keeps of them, the Gmail half of what `syncMail` does over
//! IMAP, JMAP and Graph.
//!
//! A label lists message ids and nothing else, so a message's envelope
//! and markers cost one metadata read each. The first round of a label
//! pays that for its window; every round after replays the mailbox's
//! history from the `historyId` it stopped at and reads again only the
//! messages that moved, which is what keeps a quiet mailbox one request.

use io_gmail::{
    coroutine::*,
    v1::{
        rest::{
            history::list::{GmailHistoryList, GmailHistoryListParams},
            labels::{GmailLabelType, list::GmailLabelsList},
            messages::{
                GmailMessage, GmailMessageFormat, decode_raw, encode_raw,
                get::GmailMessageGet,
                list::{GmailMessagesList, GmailMessagesListParams},
                modify::GmailMessageModify,
                send::GmailMessageSend,
                trash::GmailMessageTrash,
            },
            users::get_profile::GmailProfileGet,
        },
        send::{GMAIL_API_BASE, GmailSendError, GmailSendOutput},
    },
};
use io_http::rfc6750::bearer::HttpAuthBearer;
use jiff::Timestamp;

use crate::{
    client::{Client, convert::coroutine_error, throttle},
    mail,
    types::{BridgeError, Mailbox, Message},
};

/// The label Gmail files deleted mail under.
const TRASH: &str = "TRASH";

/// The headers a metadata read asks for: what a row shows.
const ENVELOPE_HEADERS: [&str; 3] = ["Subject", "From", "Date"];

/// System labels that are markers or views rather than places mail is
/// filed, and so no mailbox.
const NOT_MAILBOXES: [&str; 5] = ["UNREAD", "STARRED", "IMPORTANT", "CHAT", "YELLOW_STAR"];

/// One message's metadata: its labels and its envelope, the mailbox
/// left for the caller to fill.
#[derive(Clone)]
pub struct GmailEnvelope {
    pub labels: Vec<String>,
    pub message: Message,
}

impl<'a, 'local> Client<'a, 'local> {
    /// The mailbox's current history id, which is also what proves the
    /// token is still good.
    pub fn gmail_history_id(&mut self, token: &str) -> Result<String, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailProfileGet::new(&auth, "me").map_err(|err| err.to_string())?;
        let profile = self.run_gmail(coroutine)?;

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
        let labels = self.run_gmail(coroutine)?.labels;

        Ok(labels
            .into_iter()
            .filter(|label| {
                label.label_type != Some(GmailLabelType::System)
                    || !(NOT_MAILBOXES.contains(&label.id.as_str())
                        || label.id.starts_with("CATEGORY_"))
            })
            .map(|label| {
                let role = if label.id == TRASH { "trash" } else { "" };
                let mailbox = Mailbox {
                    name: label.name,
                    role: role.into(),
                };
                (label.id, mailbox)
            })
            .collect())
    }

    /// The newest `limit` message ids filed under a label.
    pub fn list_gmail_label(
        &mut self,
        token: &str,
        label: &str,
        limit: u32,
    ) -> Result<Vec<String>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let label_ids = [label.to_string()];
        let params = GmailMessagesListParams {
            label_ids: &label_ids,
            max_results: Some(limit),
            include_spam_trash: label == TRASH || label == "SPAM",
            ..Default::default()
        };

        let coroutine =
            GmailMessagesList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
        let page = self.run_gmail(coroutine)?;

        Ok(page
            .messages
            .into_iter()
            .map(|message| message.id)
            .collect())
    }

    /// The messages that moved since `start`, and the history id the next
    /// round resumes from; [`None`] when Gmail no longer holds history
    /// that old, which a full round recovers from.
    pub fn gmail_history(
        &mut self,
        token: &str,
        start: &str,
    ) -> Result<Option<(Vec<String>, String)>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let mut touched = Vec::new();
        let mut next = start.to_string();
        let mut page_token: Option<String> = None;

        loop {
            let params = GmailHistoryListParams {
                start_history_id: start,
                page_token: page_token.as_deref(),
                ..Default::default()
            };
            let coroutine =
                GmailHistoryList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
            let page = match self.run_gmail(coroutine) {
                Ok(page) => page,
                Err(err) if err.status == Some(404) => return Ok(None),
                Err(err) => return Err(err),
            };

            for record in page.history {
                let added = record.messages_added.into_iter().map(|entry| entry.message);
                let deleted = record
                    .messages_deleted
                    .into_iter()
                    .map(|entry| entry.message);
                let labelled = record.labels_added.into_iter().map(|entry| entry.message);
                let unlabelled = record.labels_removed.into_iter().map(|entry| entry.message);
                for message in added.chain(deleted).chain(labelled).chain(unlabelled) {
                    if !touched.contains(&message.id) {
                        touched.push(message.id);
                    }
                }
            }
            if let Some(id) = page.history_id {
                next = id;
            }

            match page.next_page_token {
                Some(token) => page_token = Some(token),
                None => return Ok(Some((touched, next))),
            }
        }
    }

    /// One message's labels and envelope; [`None`] when it is gone.
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
        match self.run_gmail(coroutine) {
            Ok(message) => Ok(Some(envelope(message))),
            Err(err) if err.status == Some(404) => Ok(None),
            Err(err) => Err(err),
        }
    }

    /// Reads one message whole, as the RFC 5322 bytes Gmail serves.
    pub fn fetch_gmail_source(&mut self, token: &str, id: &str) -> Result<Vec<u8>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailMessageGet::new(&auth, "me", id, GmailMessageFormat::Raw, &[])
            .map_err(|err| err.to_string())?;
        let message = self.run_gmail(coroutine)?;

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
        Ok(self.run_gmail(coroutine)?.label_ids)
    }

    /// Moves one message into the trash, naming the mailbox it landed in.
    pub fn delete_gmail_message(&mut self, token: &str, id: &str) -> Result<String, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GmailMessageTrash::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run_gmail(coroutine)?;
        Ok(TRASH.into())
    }

    /// Sends one RFC 5322 message, Gmail filing the copy under `SENT`
    /// itself.
    pub fn send_gmail_message(&mut self, token: &str, raw: &[u8]) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let message = GmailMessage {
            raw: Some(encode_raw(raw)),
            ..Default::default()
        };
        let coroutine =
            GmailMessageSend::new(&auth, "me", &message).map_err(|err| err.to_string())?;
        self.run_gmail(coroutine)?;
        Ok(())
    }

    /// Runs one Gmail coroutine to completion over the transport, paced
    /// under Gmail's per-user quota ([`throttle::pace_gmail`]).
    fn run_gmail<C, T>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
    {
        throttle::pace_gmail();
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                GmailCoroutineState::Complete(Ok(output)) => return Ok(output.response),
                GmailCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                GmailCoroutineState::Yielded(GmailYield::WantsRead) => {
                    arg = Some(self.http_read(GMAIL_API_BASE)?);
                }
                GmailCoroutineState::Yielded(GmailYield::WantsWrite(bytes)) => {
                    self.http_write(GMAIL_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// One metadata read to the labels and envelope it carries.
///
/// The header values are what the sender wrote, encoded words included,
/// so they go through the parser a stored message goes through rather
/// than being taken as text. The date is Gmail's own reception time,
/// as JMAP's is.
fn envelope(message: GmailMessage) -> GmailEnvelope {
    let payload = message.payload.unwrap_or_default();

    let mut headers = String::new();
    for name in ENVELOPE_HEADERS {
        if let Some(value) = payload.header(name) {
            headers.push_str(&format!("{name}: {value}\r\n"));
        }
    }
    headers.push_str("\r\n");
    let parsed = mail::parse(headers.as_bytes()).ok();

    let date = message
        .internal_date
        .as_deref()
        .and_then(|millis| millis.parse::<i64>().ok())
        .and_then(|millis| Timestamp::from_millisecond(millis).ok())
        .map(|timestamp| timestamp.to_string())
        .unwrap_or_default();
    let labels = message.label_ids;

    GmailEnvelope {
        message: Message {
            mailbox: String::new(),
            id: message.id,
            subject: parsed
                .as_ref()
                .map(|p| p.subject.clone())
                .unwrap_or_default(),
            from: parsed.as_ref().map(|p| p.from.clone()).unwrap_or_default(),
            from_address: parsed.map(|p| p.from_address).unwrap_or_default(),
            date,
            seen: !labels.iter().any(|label| label == "UNREAD"),
            answered: false,
            flagged: labels.iter().any(|label| label == "STARRED"),
            has_attachment: payload.mime_type.as_deref() == Some("multipart/mixed"),
        },
        labels,
    }
}

#[cfg(test)]
mod tests {
    use io_gmail::v1::rest::messages::{GmailMessageHeader, GmailMessagePayload};

    use super::*;

    #[test]
    fn a_metadata_read_becomes_a_decoded_envelope() {
        let header = |name: &str, value: &str| GmailMessageHeader {
            name: name.into(),
            value: value.into(),
        };
        let message = GmailMessage {
            id: "m1".into(),
            label_ids: vec!["INBOX".into(), "UNREAD".into(), "STARRED".into()],
            internal_date: Some("1700000000000".into()),
            payload: Some(GmailMessagePayload {
                headers: vec![
                    header("Subject", "=?UTF-8?Q?Caf=C3=A9?="),
                    header("From", "=?UTF-8?Q?Ren=C3=A9?= <rene@example.org>"),
                ],
                ..Default::default()
            }),
            ..Default::default()
        };

        let envelope = envelope(message);

        assert_eq!(envelope.message.subject, "Café");
        assert_eq!(envelope.message.from, "René");
        assert_eq!(envelope.message.from_address, "rene@example.org");
        assert_eq!(envelope.message.date, "2023-11-14T22:13:20Z");
        assert!(!envelope.message.seen);
        assert!(envelope.message.flagged);
    }
}
