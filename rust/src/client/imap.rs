//! IMAP operations: the mailbox list and the envelope spine, run over
//! io-imap's sans-io coroutines and the same Java transport the WebDAV
//! side uses.
//!
//! Read-only and stateless per call, like the CalDAV side: one native
//! call opens a connection, authenticates, does its work and drops it.
//! What differs from WebDAV is that IMAP is a session rather than a
//! request, so the whole account is walked in one call (connect once,
//! list, then fetch every mailbox) instead of one call per collection.

use io_imap::{
    codec::fragmentizer::Fragmentizer,
    coroutine::{ImapCoroutine, ImapCoroutineState, ImapYield},
    rfc3501::{
        examine::{ImapMailboxExamine, ImapMailboxExamineOptions},
        fetch::{ImapMessageFetch, ImapMessageFetchOptions},
        greeting::{ImapGreetingGet, ImapGreetingGetOptions},
        list::ImapMailboxList,
    },
    sasl::auth_plain::{ImapAuthPlain, ImapAuthPlainOptions},
    types::{
        fetch::{MacroOrMessageDataItemNames, MessageDataItem, MessageDataItemName},
        flag::{Flag, FlagFetch, FlagNameAttribute},
        mailbox::{ListMailbox, Mailbox},
        sequence::SequenceSet,
    },
};
use url::Url;

use crate::{
    client::Client,
    types::{BridgeError, Credentials, Message},
};

/// io-imap's own fragmentizer ceiling, 100 MiB per message.
const MAX_MESSAGE_SIZE: u32 = 100 * 1024 * 1024;

/// One IMAP session: the shared JNI client plus the per-connection
/// fragmentizer and the URL its socket is keyed by.
pub struct ImapSession<'a, 'b, 'local> {
    client: &'a mut Client<'b, 'local>,
    fragmentizer: Fragmentizer,
    url: String,
}

impl<'a, 'b, 'local> ImapSession<'a, 'b, 'local> {
    pub fn new(client: &'a mut Client<'b, 'local>, url: &Url) -> Self {
        Self {
            client,
            fragmentizer: Fragmentizer::new(MAX_MESSAGE_SIZE),
            url: url.to_string(),
        }
    }

    /// Drives a coroutine to completion, servicing every read and write
    /// yield through the Java transport's stream for this URL.
    fn run<C, T, E>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: ImapCoroutine<Yield = ImapYield, Return = Result<T, E>>,
        E: core::fmt::Display,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(&mut self.fragmentizer, arg.as_deref()) {
                ImapCoroutineState::Complete(Ok(value)) => return Ok(value),
                ImapCoroutineState::Complete(Err(err)) => return Err(err.to_string().into()),
                ImapCoroutineState::Yielded(ImapYield::WantsRead) => {
                    arg = Some(self.client.read(&self.url)?);
                }
                ImapCoroutineState::Yielded(ImapYield::WantsWrite(bytes)) => {
                    self.client.write(&self.url, &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Consumes the greeting, then authenticates.
    ///
    /// AUTHENTICATE PLAIN only: every provider this app onboards
    /// supports it, and LOGIN would be a second code path for none of
    /// them. A bearer-token account would need SASL XOAUTH2, which is
    /// not wired here.
    pub fn connect(&mut self, credentials: &Credentials) -> Result<(), BridgeError> {
        self.run(ImapGreetingGet::new(ImapGreetingGetOptions {
            ensure_capabilities: true,
        }))?;

        self.run(ImapAuthPlain::new(
            None::<&str>,
            credentials.login,
            credentials.password,
            ImapAuthPlainOptions {
                initial_request: false,
                ensure_capabilities: true,
                auto_id: None,
            },
        ))
        .map(|_| ())
    }

    /// LIST, keeping the mailboxes that can actually hold messages.
    pub fn list_mailboxes(&mut self) -> Result<Vec<String>, BridgeError> {
        let reference: Mailbox = "".try_into().expect("empty LIST reference is valid");
        let pattern: ListMailbox = "*".try_into().expect("`*` LIST pattern is valid");

        let listed = self.run(ImapMailboxList::new(reference, pattern))?;

        // NOTE: LIST yields (mailbox, delimiter, attributes) triples.
        Ok(listed
            .into_iter()
            .filter(|(_, _, attributes)| {
                !attributes
                    .iter()
                    .any(|attribute| *attribute == FlagNameAttribute::Noselect)
            })
            .map(|(mailbox, _, _)| match mailbox {
                Mailbox::Inbox => String::from("INBOX"),
                Mailbox::Other(other) => {
                    String::from_utf8_lossy(other.as_ref().as_ref()).into_owned()
                }
            })
            .collect())
    }

    /// EXAMINE a mailbox, then FETCH the envelope spine of its most
    /// recent `limit` messages.
    ///
    /// EXAMINE rather than SELECT: the list is read-only and must not
    /// clear anyone's `\Recent`. The window is taken off the end of the
    /// sequence set, since a merged inbox wants the newest mail and
    /// fetching a 200000-message mailbox whole is not an option.
    pub fn fetch_envelopes(
        &mut self,
        mailbox: &str,
        limit: u32,
    ) -> Result<Vec<Message>, BridgeError> {
        // NOTE: owned, so the coroutine outlives the borrowed name.
        let name: Mailbox<'static> = mailbox
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox name `{mailbox}`"))?;

        let data = self.run(ImapMailboxExamine::new(
            name,
            ImapMailboxExamineOptions::default(),
        ))?;

        let exists = data.exists.unwrap_or(0);
        if exists == 0 {
            return Ok(Vec::new());
        }

        let first = exists.saturating_sub(limit).max(1);
        let range = format!("{first}:{exists}");
        let sequence_set: SequenceSet = range
            .as_str()
            .try_into()
            .map_err(|_| format!("Invalid sequence set `{range}`"))?;

        let items = MacroOrMessageDataItemNames::MessageDataItemNames(vec![
            MessageDataItemName::Uid,
            MessageDataItemName::Envelope,
            MessageDataItemName::Flags,
        ]);

        let fetched = self.run(ImapMessageFetch::new(
            sequence_set,
            items,
            ImapMessageFetchOptions::default(),
        ))?;

        // NOTE: returned in sequence order, which is roughly arrival
        // order. Ordering the merged list is the store's job, since it
        // sorts across accounts and needs the parsed date to do it; a
        // sort here would be on the raw RFC 5322 text, which is not
        // chronological.
        Ok(fetched
            .into_values()
            .map(|items| message(mailbox, items.into_inner()))
            .collect())
    }
}

/// One FETCH response's data items to the JNI-facing shape.
fn message(mailbox: &str, items: Vec<MessageDataItem<'static>>) -> Message {
    let mut uid: u32 = 0;
    let mut subject = String::new();
    let mut from = String::new();
    let mut date = String::new();
    let mut seen = false;

    for item in items {
        match item {
            MessageDataItem::Uid(value) => uid = value.get(),
            MessageDataItem::Flags(flags) => {
                seen = flags
                    .iter()
                    .any(|flag| *flag == FlagFetch::Flag(Flag::Seen));
            }
            MessageDataItem::Envelope(envelope) => {
                if let Some(value) = envelope.subject.into_option() {
                    subject = String::from_utf8_lossy(value.as_ref()).into_owned();
                }
                if let Some(value) = envelope.date.into_option() {
                    date = String::from_utf8_lossy(value.as_ref()).into_owned();
                }
                from = envelope.from.first().map(address_label).unwrap_or_default();
            }
            _ => {}
        }
    }

    Message {
        mailbox: mailbox.to_string(),
        uid,
        subject,
        from,
        date,
        seen,
    }
}

/// An envelope address as a person reads it: the display name when the
/// server sent one, the mailbox address otherwise.
fn address_label(address: &io_imap::types::envelope::Address) -> String {
    if let Some(name) = address.name.clone().into_option() {
        let name = String::from_utf8_lossy(name.as_ref()).into_owned();
        if !name.trim().is_empty() {
            return name;
        }
    }

    let local = address
        .mailbox
        .clone()
        .into_option()
        .map(|raw| String::from_utf8_lossy(raw.as_ref()).into_owned())
        .unwrap_or_default();
    let host = address
        .host
        .clone()
        .into_option()
        .map(|raw| String::from_utf8_lossy(raw.as_ref()).into_owned())
        .unwrap_or_default();

    match host.is_empty() {
        true => local,
        false => format!("{local}@{host}"),
    }
}

/// Walks a whole account: connect once, list the mailboxes, then take
/// the newest `limit` messages of each.
pub fn sync_account(
    client: &mut Client<'_, '_>,
    url: &Url,
    credentials: &Credentials,
    limit: u32,
) -> Result<Vec<Message>, BridgeError> {
    let mut session = ImapSession::new(client, url);
    session.connect(credentials)?;

    let mailboxes = session.list_mailboxes()?;
    let mut messages = Vec::new();
    for mailbox in mailboxes {
        // NOTE: one unreadable mailbox (a shared folder the account
        // cannot EXAMINE) must not fail the whole account.
        match session.fetch_envelopes(&mailbox, limit) {
            Ok(found) => messages.extend(found),
            Err(err) => log::warn!("skip mailbox {mailbox}: {err}"),
        }
    }

    Ok(messages)
}
