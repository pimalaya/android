//! IMAP operations: the mailbox list, the envelope spine, one message
//! whole, and the three verbs a reader applies to one, run over
//! io-imap's sans-io coroutines and the same Java transport the WebDAV
//! side uses.
//!
//! Stateless per call, like the CalDAV side: one native call opens a
//! connection, authenticates, does its work and drops it.
//! What differs from WebDAV is that IMAP is a session rather than a
//! request, so the whole account is walked in one call (connect once,
//! list, then fetch every mailbox) instead of one call per collection.

use io_imap::{
    codec::fragmentizer::Fragmentizer,
    coroutine::{ImapCoroutine, ImapCoroutineState, ImapYield},
    rfc3501::{
        append::{ImapMessageAppend, ImapMessageAppendOptions},
        copy::{ImapMessageCopy, ImapMessageCopyOptions},
        examine::{ImapMailboxExamine, ImapMailboxExamineOptions},
        fetch::{ImapMessageFetch, ImapMessageFetchOptions},
        greeting::{ImapGreetingGet, ImapGreetingGetOptions},
        list::ImapMailboxList,
        select::{ImapMailboxSelect, ImapMailboxSelectOptions},
        store::{ImapMessageStoreOptions, ImapMessageStoreSilent},
    },
    rfc6851::r#move::{ImapMessageMove, ImapMessageMoveOptions},
    sasl::auth_plain::{ImapAuthPlain, ImapAuthPlainOptions},
    types::{
        IntoStatic,
        body::{Body, BodyStructure, Disposition},
        core::IString,
        fetch::{MacroOrMessageDataItemNames, MessageDataItem, MessageDataItemName},
        flag::{Flag, FlagFetch, FlagNameAttribute, StoreType},
        mailbox::{ListMailbox, Mailbox},
        response::Capability,
        sequence::SequenceSet,
    },
};
use mail_parser::{Address, MessageParser, MimeHeaders, PartType};
use url::Url;

use crate::{
    client::Client,
    types::{BridgeError, Credentials, Message, MessageAttachment, MessageBody},
};

/// io-imap's own fragmentizer ceiling, 100 MiB per message.
const MAX_MESSAGE_SIZE: u32 = 100 * 1024 * 1024;

/// One IMAP session: the shared JNI client plus the per-connection
/// fragmentizer and the URL its socket is keyed by.
pub struct ImapSession<'a, 'b, 'local> {
    client: &'a mut Client<'b, 'local>,
    fragmentizer: Fragmentizer,
    url: String,
    /// What the server says it can do, read once at authentication.
    ///
    /// Kept because the write verbs are extensions and their fallbacks
    /// are not equivalent: MOVE (RFC 6851) is one command where COPY
    /// plus a marker is two and a half, and a mailbox-wide EXPUNGE
    /// without UIDPLUS (RFC 4315) would take messages nobody here
    /// deleted. Asking beats guessing, and CAPABILITY already came back
    /// with the authentication.
    capabilities: Vec<Capability<'static>>,
}

impl<'a, 'b, 'local> ImapSession<'a, 'b, 'local> {
    pub fn new(client: &'a mut Client<'b, 'local>, url: &Url) -> Self {
        Self {
            client,
            fragmentizer: Fragmentizer::new(MAX_MESSAGE_SIZE),
            url: url.to_string(),
            capabilities: Vec::new(),
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

        self.capabilities = self.run(ImapAuthPlain::new(
            None::<&str>,
            credentials.login,
            credentials.password,
            ImapAuthPlainOptions {
                initial_request: false,
                ensure_capabilities: true,
                auto_id: None,
            },
        ))?;

        Ok(())
    }

    /// LIST, keeping the mailboxes that can actually hold messages.
    pub fn list_mailboxes(&mut self) -> Result<Vec<String>, BridgeError> {
        Ok(self.list()?.into_iter().map(|(name, _)| name).collect())
    }

    /// LIST, as `(name, attributes)` pairs, the unselectable ones out.
    ///
    /// The attributes ride along because RFC 6154 puts the special-use
    /// markers there: which mailbox is the trash is the server's answer
    /// to give, and matching names against a list of words in a handful
    /// of languages is the alternative.
    fn list(&mut self) -> Result<Vec<(String, Vec<FlagNameAttribute<'static>>)>, BridgeError> {
        let reference: Mailbox = "".try_into().expect("empty LIST reference is valid");
        let pattern: ListMailbox = "*".try_into().expect("`*` LIST pattern is valid");

        let listed = self.run(ImapMailboxList::new(reference, pattern))?;

        // NOTE: LIST yields (mailbox, delimiter, attributes) triples.
        Ok(listed
            .into_iter()
            .filter(|(_, _, attributes)| !attributes.contains(&FlagNameAttribute::Noselect))
            .map(|(mailbox, _, attributes)| {
                let name = match mailbox {
                    Mailbox::Inbox => String::from("INBOX"),
                    Mailbox::Other(other) => String::from_utf8_lossy(other.as_ref()).into_owned(),
                };
                (name, attributes)
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

        // NOTE: BODYSTRUCTURE is the one structural item a listing asks
        // for, and only because there is no other way to know a message
        // carries an attachment: IMAP has no flag for it, unlike JMAP's
        // hasAttachment. It costs a MIME tree per message rather than a
        // body, which is what keeps this a spine fetch.
        let items = MacroOrMessageDataItemNames::MessageDataItemNames(vec![
            MessageDataItemName::Uid,
            MessageDataItemName::Envelope,
            MessageDataItemName::Flags,
            MessageDataItemName::BodyStructure,
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

/// EXAMINE a mailbox and FETCH one message whole, by UID.
///
/// `BODY.PEEK[]` rather than `BODY[]`, so opening a message from the
/// merged list does not mark it read behind the reader's back: what
/// marks a message read is a decision the app has not made yet, and a
/// fetch is the wrong place to make it silently.
impl ImapSession<'_, '_, '_> {
    pub fn fetch_raw(&mut self, mailbox: &str, uid: &str) -> Result<Vec<u8>, BridgeError> {
        let name: Mailbox<'static> = mailbox
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox name `{mailbox}`"))?;

        self.run(ImapMailboxExamine::new(
            name,
            ImapMailboxExamineOptions::default(),
        ))?;

        let sequence_set: SequenceSet = uid
            .try_into()
            .map_err(|_| format!("Invalid message id `{uid}`"))?;

        let items =
            MacroOrMessageDataItemNames::MessageDataItemNames(vec![MessageDataItemName::BodyExt {
                section: None,
                partial: None,
                peek: true,
            }]);

        let fetched = self.run(ImapMessageFetch::new(
            sequence_set,
            items,
            ImapMessageFetchOptions {
                uid: true,
                ..Default::default()
            },
        ))?;

        fetched
            .into_values()
            .flat_map(|items| items.into_inner())
            .find_map(|item| match item {
                MessageDataItem::BodyExt { data, .. } => {
                    data.0.map(|body| body.into_inner().into_owned())
                }
                _ => None,
            })
            .ok_or_else(|| BridgeError::from(format!("No message `{uid}` in `{mailbox}`")))
    }
}

/// The write verbs: what a reader does to a message once it is open.
///
/// SELECT rather than the EXAMINE the reads use, since a read-only
/// mailbox refuses both a STORE and a MOVE. Silent variants throughout:
/// the app already knows what it asked for, and the untagged FETCH the
/// echoing form brings back is a response to parse for nothing.
impl ImapSession<'_, '_, '_> {
    /// SELECT a mailbox, then add or remove one marker on one message,
    /// addressed by UID.
    pub fn store_flag(
        &mut self,
        mailbox: &str,
        uid: &str,
        flag: &str,
        add: bool,
    ) -> Result<(), BridgeError> {
        self.select(mailbox)?;

        // NOTE: owned, so the coroutine outlives the borrowed marker.
        let owned = flag.to_string();
        let flag: Flag<'static> = Flag::try_from(owned.as_str())
            .map(|flag| flag.into_static())
            .map_err(|_| format!("Invalid message marker `{flag}`"))?;

        self.run(ImapMessageStoreSilent::new(
            uids(uid)?,
            if add {
                StoreType::Add
            } else {
                StoreType::Remove
            },
            vec![flag],
            ImapMessageStoreOptions { uid: true },
        ))
    }

    /// Deletes one message into the account's trash, naming the mailbox
    /// it landed in, or [`None`] when the account names no trash and the
    /// message was marked `\Deleted` where it is instead.
    ///
    /// Nothing is expunged either way. With no UIDPLUS (RFC 4315) an
    /// expunge is mailbox-wide, so it would take every message another
    /// client had marked; with it, the message is already out of the
    /// mailbox and expunging its copy in the trash is not what deleting
    /// asked for.
    pub fn delete_message(
        &mut self,
        mailbox: &str,
        uid: &str,
    ) -> Result<Option<String>, BridgeError> {
        // NOTE: `None` says the message stayed where it is, whether
        // because the account names no trash or because it already is
        // in it. Both are the same answer to the caller, which uses it
        // to decide whether the row leaves its mailbox.
        let trash = match self.special_use("\\Trash")? {
            Some(trash) if trash != mailbox => trash,
            _ => {
                self.store_flag(mailbox, uid, "\\Deleted", true)?;
                return Ok(None);
            }
        };

        self.select(mailbox)?;
        self.move_or_copy(uid, &trash)?;

        Ok(Some(trash))
    }

    /// The mailbox the server marks with one RFC 6154 special-use
    /// attribute, or [`None`] when it marks none with it.
    fn special_use(&mut self, attribute: &str) -> Result<Option<String>, BridgeError> {
        Ok(self
            .list()?
            .into_iter()
            .find(|(_, attributes)| {
                attributes
                    .iter()
                    .any(|held| held.to_string().eq_ignore_ascii_case(attribute))
            })
            .map(|(name, _)| name))
    }

    /// Relocates one message of the selected mailbox into `target`:
    /// MOVE where the server has it, else the COPY the extension folds
    /// into one command, with the original left marked `\Deleted`.
    fn move_or_copy(&mut self, uid: &str, target: &str) -> Result<(), BridgeError> {
        let name: Mailbox<'static> = target
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox name `{target}`"))?;

        if self.capabilities.contains(&Capability::Move) {
            self.run(ImapMessageMove::new(
                uids(uid)?,
                name,
                ImapMessageMoveOptions { uid: true },
            ))?;

            return Ok(());
        }

        self.run(ImapMessageCopy::new(
            uids(uid)?,
            name,
            ImapMessageCopyOptions { uid: true },
        ))?;

        self.run(ImapMessageStoreSilent::new(
            uids(uid)?,
            StoreType::Add,
            vec![Flag::Deleted],
            ImapMessageStoreOptions { uid: true },
        ))
    }

    /// Files a copy of a sent message in the account's sent mailbox
    /// (RFC 6154 `\Sent`), naming it, or [`None`] when the account names
    /// none and nothing was filed.
    ///
    /// The copy is the sender's own record, so it is appended already
    /// `\Seen`: it is not new mail and an unread count that climbs every
    /// time the user writes something is wrong about what it counts.
    pub fn append_sent(&mut self, message: Vec<u8>) -> Result<Option<String>, BridgeError> {
        let Some(sent) = self.special_use("\\Sent")? else {
            return Ok(None);
        };

        let name: Mailbox<'static> = sent
            .clone()
            .try_into()
            .map_err(|_| format!("Invalid mailbox name `{sent}`"))?;

        self.run(ImapMessageAppend::new(
            name,
            message,
            ImapMessageAppendOptions {
                flags: vec![Flag::Seen],
                ..Default::default()
            },
        ))?;

        Ok(Some(sent))
    }

    /// SELECT one mailbox for writing.
    fn select(&mut self, mailbox: &str) -> Result<(), BridgeError> {
        let name: Mailbox<'static> = mailbox
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox name `{mailbox}`"))?;

        self.run(ImapMailboxSelect::new(
            name,
            ImapMailboxSelectOptions::default(),
        ))
        .map(|_| ())
    }
}

/// One message's UID as the sequence set a UID command takes.
fn uids(uid: &str) -> Result<SequenceSet, BridgeError> {
    uid.try_into()
        .map_err(|_| BridgeError::from(format!("Invalid message id `{uid}`")))
}

/// One raw RFC 5322 message as the reader shows it.
///
/// HTML wins over text when the message carries both, because that is
/// the alternative the sender laid out; the reader sandboxes it, which
/// is what makes preferring it safe.
fn parse_message(raw: &[u8]) -> Result<MessageBody, BridgeError> {
    let parsed = MessageParser::default()
        .parse(raw)
        .ok_or_else(|| BridgeError::from("Could not read the message"))?;

    let sender = parsed.from().and_then(|address| address.first());

    // NOTE: the part itself rather than `body_html`, which renders a
    // text-only message into HTML rather than saying it has none: a
    // message with nothing but text would otherwise reach the reader as
    // markup and be sandboxed in a web view for no reason.
    let html = parsed.html_part(0).and_then(|part| match &part.body {
        PartType::Html(html) => Some(html.to_string()),
        _ => None,
    });
    let (kind, body) = match html {
        Some(html) => ("html", html),
        None => match parsed.body_text(0) {
            Some(text) => ("plain", text.into_owned()),
            None => ("", String::new()),
        },
    };

    Ok(MessageBody {
        subject: parsed.subject().unwrap_or_default().to_string(),
        from: sender
            .and_then(|address| address.name.as_deref())
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .unwrap_or_default()
            .to_string(),
        from_address: sender
            .and_then(|address| address.address.as_deref())
            .unwrap_or_default()
            .to_string(),
        to: addresses(parsed.to()),
        cc: addresses(parsed.cc()),
        date: parsed
            .date()
            .map(|date| date.to_rfc3339())
            .unwrap_or_default(),
        kind: kind.to_string(),
        body,
        attachments: parsed
            .attachments()
            .map(|part| MessageAttachment {
                name: part.attachment_name().unwrap_or_default().to_string(),
                mime: part
                    .content_type()
                    .map(|content| match content.subtype() {
                        Some(subtype) => format!("{}/{subtype}", content.ctype()),
                        None => content.ctype().to_string(),
                    })
                    .unwrap_or_else(|| String::from("application/octet-stream"))
                    .to_lowercase(),
                size: part.contents().len() as u64,
            })
            .collect(),
    })
}

/// A header's addresses as one line, the way a header reads them.
fn addresses(header: Option<&Address>) -> String {
    let Some(header) = header else {
        return String::new();
    };

    header
        .clone()
        .into_list()
        .iter()
        .map(|address| match address.name.as_deref().map(str::trim) {
            Some(name) if !name.is_empty() => {
                format!(
                    "{name} <{}>",
                    address.address.as_deref().unwrap_or_default()
                )
            }
            _ => address.address.as_deref().unwrap_or_default().to_string(),
        })
        .collect::<Vec<_>>()
        .join(", ")
}

/// One FETCH response's data items to the JNI-facing shape.
fn message(mailbox: &str, items: Vec<MessageDataItem<'static>>) -> Message {
    let mut uid: u32 = 0;
    let mut subject = String::new();
    let mut from = String::new();
    let mut from_address = String::new();
    let mut date = String::new();
    let mut seen = false;
    let mut answered = false;
    let mut flagged = false;
    let mut has_attachment = false;

    for item in items {
        match item {
            MessageDataItem::Uid(value) => uid = value.get(),
            MessageDataItem::Flags(flags) => {
                seen = flags.contains(&FlagFetch::Flag(Flag::Seen));
                answered = flags.contains(&FlagFetch::Flag(Flag::Answered));
                flagged = flags.contains(&FlagFetch::Flag(Flag::Flagged));
            }
            MessageDataItem::BodyStructure(structure) => {
                has_attachment = attaches(&structure);
            }
            MessageDataItem::Envelope(envelope) => {
                if let Some(value) = envelope.subject.into_option() {
                    subject = String::from_utf8_lossy(value.as_ref()).into_owned();
                }
                if let Some(value) = envelope.date.into_option() {
                    date = String::from_utf8_lossy(value.as_ref()).into_owned();
                }
                if let Some(address) = envelope.from.first() {
                    from = display_name(address);
                    from_address = mailbox_address(address);
                }
            }
            _ => {}
        }
    }

    Message {
        mailbox: mailbox.to_string(),
        id: uid.to_string(),
        subject,
        from,
        from_address,
        date,
        seen,
        answered,
        flagged,
        has_attachment,
    }
}

/// An envelope address's display name, empty when it carries none.
fn display_name(address: &io_imap::types::envelope::Address) -> String {
    let Some(name) = address.name.clone().into_option() else {
        return String::new();
    };

    let name = String::from_utf8_lossy(name.as_ref()).into_owned();
    match name.trim().is_empty() {
        true => String::new(),
        false => name,
    }
}

/// An envelope address's `local@host`, empty when it carries neither.
fn mailbox_address(address: &io_imap::types::envelope::Address) -> String {
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

/// Whether any part of a MIME tree is something to detach.
///
/// The disposition (RFC 2183) decides when the sender stated one: an
/// inline image is a part a listing must not flag, because what the
/// paperclip promises is something to detach and not something to
/// render. **When no disposition was stated at all**, a part that names
/// a file counts, which is the case this used to miss: `Content-Type:
/// application/pdf; name="invoice.pdf"` with no `Content-Disposition`
/// is a perfectly ordinary attachment, and mail in the wild is full of
/// them.
fn attaches(structure: &BodyStructure) -> bool {
    match structure {
        BodyStructure::Single {
            body,
            extension_data,
        } => {
            let disposition = extension_data
                .as_ref()
                .and_then(|data| data.tail.as_ref())
                .and_then(kind_of);

            match disposition {
                Some(kind) => kind.eq_ignore_ascii_case("attachment"),
                None => names_a_file(body),
            }
        }
        BodyStructure::Multi {
            bodies,
            extension_data,
            ..
        } => {
            let own = extension_data
                .as_ref()
                .and_then(|data| data.tail.as_ref())
                .and_then(kind_of)
                .is_some_and(|kind| kind.eq_ignore_ascii_case("attachment"));
            own || bodies.as_ref().iter().any(attaches)
        }
    }
}

/// A `Content-Disposition`'s type, when the part carries one.
fn kind_of(disposition: &Disposition) -> Option<String> {
    disposition
        .disposition
        .as_ref()
        .map(|(kind, _)| text_of(kind))
}

/// Whether a part's `Content-Type` carries a non-empty `name`, the
/// pre-RFC-2183 way of saying a part is a file.
fn names_a_file(body: &Body) -> bool {
    body.basic
        .parameter_list
        .iter()
        .any(|(key, value)| text_of(key).eq_ignore_ascii_case("name") && !text_of(value).is_empty())
}

/// An `IString`'s bytes as text, however the server encoded them.
fn text_of(value: &IString) -> String {
    String::from_utf8_lossy(&value.clone().into_inner()).into_owned()
}

/// Reads one message whole: connect, EXAMINE its mailbox, fetch it and
/// resolve its MIME tree into the one body a reader sees.
pub fn fetch_message(
    client: &mut Client<'_, '_>,
    url: &Url,
    credentials: &Credentials,
    mailbox: &str,
    id: &str,
) -> Result<MessageBody, BridgeError> {
    let mut session = ImapSession::new(client, url);
    session.connect(credentials)?;

    let raw = session.fetch_raw(mailbox, id)?;
    parse_message(&raw)
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

#[cfg(test)]
mod tests {
    use io_imap::types::{
        body::{BasicFields, Body, BodyStructure, Disposition, SinglePartExtensionData},
        core::{IString, NString},
    };

    use super::{attaches, parse_message};

    fn text(value: &str) -> IString<'static> {
        value.to_string().try_into().expect("printable ASCII")
    }

    /// One leaf part: what it names itself, and what it says it is for.
    fn part(name: Option<&str>, disposition: Option<&str>) -> BodyStructure<'static> {
        BodyStructure::Single {
            body: Body {
                basic: BasicFields {
                    parameter_list: name
                        .map(|name| vec![(text("name"), text(name))])
                        .unwrap_or_default(),
                    id: NString(None),
                    description: NString(None),
                    content_transfer_encoding: text("base64"),
                    size: 42,
                },
                specific: io_imap::types::body::SpecificFields::Basic {
                    r#type: text("application"),
                    subtype: text("pdf"),
                },
            },
            extension_data: Some(SinglePartExtensionData {
                md5: NString(None),
                tail: disposition.map(|kind| Disposition {
                    disposition: Some((text(kind), Vec::new())),
                    tail: None,
                }),
            }),
        }
    }

    #[test]
    fn a_named_part_with_no_disposition_is_an_attachment() {
        // The case the paperclip used to miss: plenty of senders write
        // `Content-Type: application/pdf; name="invoice.pdf"` and no
        // `Content-Disposition` at all.
        assert!(attaches(&part(Some("invoice.pdf"), None)));
        assert!(!attaches(&part(None, None)));
    }

    #[test]
    fn a_stated_disposition_decides_on_its_own() {
        // An inline image names a file too, and flagging it would
        // promise something to detach where there is only something to
        // render.
        assert!(!attaches(&part(Some("logo.png"), Some("inline"))));
        assert!(attaches(&part(None, Some("attachment"))));
        assert!(attaches(&part(Some("invoice.pdf"), Some("ATTACHMENT"))));
    }

    #[test]
    fn html_wins_over_text_and_attachments_are_listed() {
        let raw = b"From: Alice <alice@example.org>\r\n\
                    To: Bob <bob@example.org>\r\n\
                    Subject: Hello\r\n\
                    Date: Sun, 9 Aug 2026 12:14:00 +0200\r\n\
                    MIME-Version: 1.0\r\n\
                    Content-Type: multipart/mixed; boundary=\"sep\"\r\n\
                    \r\n\
                    --sep\r\n\
                    Content-Type: multipart/alternative; boundary=\"alt\"\r\n\
                    \r\n\
                    --alt\r\n\
                    Content-Type: text/plain\r\n\
                    \r\n\
                    plain body\r\n\
                    --alt\r\n\
                    Content-Type: text/html\r\n\
                    \r\n\
                    <p>rich body</p>\r\n\
                    --alt--\r\n\
                    --sep\r\n\
                    Content-Type: application/pdf; name=\"invoice.pdf\"\r\n\
                    Content-Disposition: attachment; filename=\"invoice.pdf\"\r\n\
                    \r\n\
                    %PDF\r\n\
                    --sep--\r\n";

        let message = parse_message(raw).unwrap();

        assert_eq!(message.subject, "Hello");
        assert_eq!(message.from, "Alice");
        assert_eq!(message.from_address, "alice@example.org");
        assert_eq!(message.to, "Bob <bob@example.org>");
        assert_eq!(message.kind, "html");
        assert!(message.body.contains("rich body"));
        assert!(message.date.starts_with("2026-08-09T12:14:00"));

        assert_eq!(message.attachments.len(), 1);
        assert_eq!(message.attachments[0].name, "invoice.pdf");
        assert_eq!(message.attachments[0].mime, "application/pdf");
    }

    #[test]
    fn a_message_with_no_html_falls_back_to_its_text() {
        let raw = b"From: alice@example.org\r\n\
                    Subject: Plain\r\n\
                    \r\n\
                    just text\r\n";

        let message = parse_message(raw).unwrap();

        assert_eq!(message.kind, "plain");
        assert_eq!(message.body.trim(), "just text");
        // No display name in the header, so the row falls back to the
        // address rather than showing an empty sender.
        assert_eq!(message.from, "");
        assert_eq!(message.from_address, "alice@example.org");
    }
}
