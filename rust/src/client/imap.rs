//! IMAP operations: the mailbox list, the envelope spine, one message
//! whole, and the three verbs a reader applies to one, run over
//! io-imap's sans-io coroutines and the same Java transport the WebDAV
//! side uses.
//!
//! The session outlives the call that opened it ([`ImapState`]), so a
//! pass connects once and every verb after it is a command rather than a
//! login. That is what makes a per-mailbox enumerate affordable, and the
//! enumerate is where the work went: a mailbox with a
//! `(UIDVALIDITY, HIGHESTMODSEQ)` cursor on a QRESYNC server answers
//! what moved, and envelopes are read for the UIDs a caller names rather
//! than for a window of every mailbox, every pass.

use core::num::{NonZeroU32, NonZeroU64};

use io_imap::{
    codec::fragmentizer::Fragmentizer,
    coroutine::{ImapCoroutine, ImapCoroutineState, ImapYield},
    rfc3501::{
        append::{ImapMessageAppend, ImapMessageAppendOptions},
        copy::{ImapMessageCopy, ImapMessageCopyOptions},
        examine::{ExamineData, ImapMailboxExamine, ImapMailboxExamineOptions},
        fetch::{ImapMessageFetch, ImapMessageFetchOptions},
        greeting::{ImapGreetingGet, ImapGreetingGetOptions},
        list::ImapMailboxList,
        select::{ImapMailboxSelect, ImapMailboxSelectOptions},
        store::{ImapMessageStoreOptions, ImapMessageStoreSilent},
    },
    rfc5161::enable::ImapExtensionEnable,
    rfc6851::r#move::{ImapMessageMove, ImapMessageMoveOptions},
    sasl::auth_plain::{ImapAuthPlain, ImapAuthPlainOptions},
    types::{
        IntoStatic,
        body::{Body, BodyStructure, Disposition},
        command::SelectParameter,
        core::{Atom, IString, Vec1},
        extensions::enable::CapabilityEnable,
        fetch::{MacroOrMessageDataItemNames, MessageDataItem, MessageDataItemName},
        flag::{Flag, FlagFetch, FlagNameAttribute, StoreType},
        mailbox::{ListMailbox, Mailbox},
        response::Capability,
        sequence::SequenceSet,
    },
};
use url::Url;

use crate::{
    client::Client,
    mail,
    types::{BridgeError, Credentials, Mailbox as MailboxEntry, Message},
};

/// io-imap's own fragmentizer ceiling, 100 MiB per message.
const MAX_MESSAGE_SIZE: u32 = 100 * 1024 * 1024;

/// Everything about an IMAP session that outlives one native call.
///
/// Held by the caller across calls and handed back with a fresh
/// [`Client`] each time, which is what makes a connection last a whole
/// pass instead of a command: a session is a conversation, so reusing
/// the socket without reusing this would replay a greeting the server
/// has already answered and block until the read timed out.
///
/// It deliberately holds no JNI reference. The environment and the
/// transport belong to one call, so keeping either here would be keeping
/// a pointer the JVM invalidates on return; the caller owns the transport
/// and passes it back in, and nothing has to be registered or freed.
pub struct ImapState {
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
    /// The mailbox currently selected on this connection.
    ///
    /// A run of commands on one mailbox then selects once rather than
    /// per command, which is most of what holding the session buys on
    /// top of the handshake it saves. Every path that selects records it
    /// here, so a skipped select is always a select that already
    /// happened; a read and a write select the mailbox differently, so
    /// which of the two it was is recorded with it.
    selected: Option<(String, Access)>,
}

/// How a mailbox was opened, since the two are not interchangeable.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Access {
    /// EXAMINE: read-only, and it must not clear anyone's `\Recent`.
    Read,
    /// SELECT: what a STORE or a MOVE needs.
    Write,
}

impl ImapState {
    /// The state of a connection nothing has authenticated yet.
    fn new(url: &Url) -> Self {
        Self {
            fragmentizer: Fragmentizer::new(MAX_MESSAGE_SIZE),
            url: url.to_string(),
            capabilities: Vec::new(),
            selected: None,
        }
    }
}

/// One session bound to one native call: the state that outlives the
/// call, and the JNI client that does not.
pub struct ImapSession<'a, 'b, 'local> {
    client: &'a mut Client<'b, 'local>,
    state: &'a mut ImapState,
}

impl<'a, 'b, 'local> ImapSession<'a, 'b, 'local> {
    /// Binds a held session to the call that is about to use it.
    pub fn bind(client: &'a mut Client<'b, 'local>, state: &'a mut ImapState) -> Self {
        Self { client, state }
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
            match coroutine.resume(&mut self.state.fragmentizer, arg.as_deref()) {
                ImapCoroutineState::Complete(Ok(value)) => return Ok(value),
                ImapCoroutineState::Complete(Err(err)) => return Err(err.to_string().into()),
                ImapCoroutineState::Yielded(ImapYield::WantsRead) => {
                    arg = Some(self.client.read(&self.state.url)?);
                }
                ImapCoroutineState::Yielded(ImapYield::WantsWrite(bytes)) => {
                    self.client.write(&self.state.url, &bytes)?;
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

        self.state.capabilities = self.run(ImapAuthPlain::new(
            None::<&str>,
            credentials.login,
            credentials.password,
            ImapAuthPlainOptions {
                initial_request: false,
                ensure_capabilities: true,
                auto_id: None,
            },
        ))?;

        // NOTE: RFC 7162 section 3.1 requires an ENABLE before the
        // QRESYNC parameter may be used on a SELECT, and a server that
        // never saw one answers the parameter `BAD invalid select
        // modifier`. Sent once, here, because ENABLE is a property of the
        // connection and the connection now lasts the pass.
        if self.supports_qresync() {
            let enabled = Vec1::try_from(vec![
                CapabilityEnable::CondStore,
                CapabilityEnable::from(
                    Atom::try_from("QRESYNC").expect("`QRESYNC` is a valid IMAP atom"),
                ),
            ])
            .expect("two capabilities are not none");

            // A refused ENABLE costs the incremental round and nothing
            // else, so it forgets the capability rather than failing the
            // connection: an account that syncs slowly beats one that
            // does not open. Every enumerate after this then takes the
            // full round directly instead of asking once per mailbox.
            if let Err(err) = self.run(ImapExtensionEnable::new(enabled)) {
                log::warn!("QRESYNC not enabled, enumerating whole: {err}");
                self.state
                    .capabilities
                    .retain(|capability| capability != &Capability::QResync);
            }
        }

        Ok(())
    }

    /// Whether the server advertises QRESYNC (RFC 7162).
    fn supports_qresync(&self) -> bool {
        self.state.capabilities.contains(&Capability::QResync)
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
    ///
    /// Incrementally where the server and the cursor allow it: a
    /// `(UIDVALIDITY, HIGHESTMODSEQ)` pair against a QRESYNC server is a
    /// `SELECT (QRESYNC ..)`, and the server streams the messages whose
    /// flags moved and the UIDs that went. Anything else is a full round
    /// over the window, which is what a first pass, a rebuilt handle
    /// space and a server without the extension all get.
    pub fn enumerate(
        &mut self,
        mailbox: &str,
        cursor: Option<&str>,
        limit: u32,
    ) -> Result<Enumeration, BridgeError> {
        if let Some((validity, modseq)) = cursor.and_then(decode_cursor)
            && modseq > 0
            && self.supports_qresync()
            && let Some(validity) = NonZeroU32::new(validity)
            && let Some(delta) = self.select_qresync(mailbox, validity, modseq)?
        {
            return Ok(delta);
        }

        // NOTE: always examined, cache or no cache: what this needs from
        // the command is how many messages the mailbox holds, which is
        // the one thing a skipped select cannot tell it.
        let data = self.examine(mailbox)?;
        let checkpoint = encode_cursor(
            data.uid_validity.map(NonZeroU32::get).unwrap_or(0),
            data.highest_mod_seq.unwrap_or(0),
        );

        let exists = data.exists.unwrap_or(0);
        if exists == 0 {
            return Ok(Enumeration {
                items: Vec::new(),
                vanished: Vec::new(),
                complete: true,
                checkpoint,
            });
        }

        let first = exists.saturating_sub(limit).max(1);
        let items = self.fetch_spine(&format!("{first}:{exists}"), false)?;

        Ok(Enumeration {
            items,
            vanished: Vec::new(),
            complete: true,
            checkpoint,
        })
    }

    /// A QRESYNC `SELECT (QRESYNC (uidvalidity modseq))`, or [`None`]
    /// when the mailbox's handle space was rebuilt under the cursor and
    /// a full round is owed instead.
    fn select_qresync(
        &mut self,
        mailbox: &str,
        validity: NonZeroU32,
        modseq: u64,
    ) -> Result<Option<Enumeration>, BridgeError> {
        let Some(mod_sequence_value) = NonZeroU64::new(modseq) else {
            return Ok(None);
        };
        let name: Mailbox<'static> = mailbox
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox name `{mailbox}`"))?;

        self.state.selected = None;
        let selected = self.run(ImapMailboxSelect::new(
            name,
            ImapMailboxSelectOptions {
                parameters: vec![SelectParameter::QResync {
                    uid_validity: validity,
                    mod_sequence_value,
                    known_uids: None,
                    seq_match_data: None,
                }],
            },
        ));

        // NOTE: a server that advertised QRESYNC and then refuses the
        // parameter owes the caller a full round, not a failed pass. The
        // capability and the ENABLE are what should make this
        // unreachable, so it is logged rather than swallowed: reaching it
        // means a server said one thing and did another.
        let data = match selected {
            Ok(data) => data,
            Err(err) => {
                log::warn!("QRESYNC select refused for {mailbox}, enumerating whole: {err}");
                return Ok(None);
            }
        };
        self.state.selected = Some((mailbox.into(), Access::Write));

        // A new UIDVALIDITY means every UID this side holds names another
        // message now (RFC 3501 2.3.1.1), so the delta describes a
        // mailbox that no longer exists and the caller owes a full round.
        let validity_now = data.uid_validity.map(NonZeroU32::get).unwrap_or(0);
        if validity_now != validity.get() {
            return Ok(None);
        }

        Ok(Some(Enumeration {
            items: data
                .changed
                .iter()
                .filter_map(|fetch| spine_entry(&fetch.items.clone().into_inner()))
                .collect(),
            vanished: data
                .vanished_earlier
                .iter()
                .map(|uid| uid.get().to_string())
                .collect(),
            complete: false,
            checkpoint: encode_cursor(validity_now, data.highest_mod_seq.unwrap_or(modseq)),
        }))
    }

    /// The envelopes of the named UIDs, and of nothing else.
    ///
    /// What the engine's fetch yield asks for, rather than a window: a
    /// pass that found one new message reads one envelope. `BODYSTRUCTURE`
    /// rides along because IMAP has no attachment flag, unlike JMAP's
    /// `hasAttachment`, and it is affordable here for the same reason the
    /// fetch is: it is asked for a handful of messages rather than for
    /// every message of every mailbox, every pass.
    pub fn fetch_envelopes(
        &mut self,
        mailbox: &str,
        uids: &[&str],
    ) -> Result<Vec<Message>, BridgeError> {
        if uids.is_empty() {
            return Ok(Vec::new());
        }
        if !self.opened(mailbox) {
            self.examine(mailbox)?;
        }

        let set = uids.join(",");
        let items = MacroOrMessageDataItemNames::MessageDataItemNames(vec![
            MessageDataItemName::Uid,
            MessageDataItemName::Envelope,
            MessageDataItemName::Flags,
            MessageDataItemName::BodyStructure,
        ]);
        let sequence_set: SequenceSet = set
            .as_str()
            .try_into()
            .map_err(|_| format!("Invalid sequence set `{set}`"))?;

        let fetched = self.run(ImapMessageFetch::new(
            sequence_set,
            items,
            ImapMessageFetchOptions {
                uid: true,
                ..Default::default()
            },
        ))?;

        Ok(fetched
            .into_values()
            .map(|items| message(mailbox, items.into_inner()))
            .collect())
    }

    /// A UID-and-flags spine over one sequence set: what an enumerate
    /// needs and nothing more.
    fn fetch_spine(&mut self, set: &str, uid: bool) -> Result<Vec<SpineEntry>, BridgeError> {
        let sequence_set: SequenceSet = set
            .try_into()
            .map_err(|_| format!("Invalid sequence set `{set}`"))?;
        let items = MacroOrMessageDataItemNames::MessageDataItemNames(vec![
            MessageDataItemName::Uid,
            MessageDataItemName::Flags,
        ]);

        let fetched = self.run(ImapMessageFetch::new(
            sequence_set,
            items,
            ImapMessageFetchOptions {
                uid,
                ..Default::default()
            },
        ))?;

        Ok(fetched
            .into_values()
            .filter_map(|items| spine_entry(&items.into_inner()))
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
        // Whichever way the mailbox is already open: both a SELECT and an
        // EXAMINE allow a FETCH, so reading one message after another in
        // the same mailbox opens it once.
        if !self.opened(mailbox) {
            self.examine(mailbox)?;
        }

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

        if self.state.capabilities.contains(&Capability::Move) {
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

    /// Whether the mailbox is already open, either way: both a SELECT and
    /// an EXAMINE allow a read.
    fn opened(&self, mailbox: &str) -> bool {
        matches!(&self.state.selected, Some((open, _)) if open == mailbox)
    }

    /// EXAMINE one mailbox, whether or not it is already open, and answer
    /// what the server said about it.
    ///
    /// Read-only, and deliberately: a listing must not clear anyone's
    /// `\Recent`, which is what a SELECT of the same mailbox would do.
    fn examine(&mut self, mailbox: &str) -> Result<ExamineData, BridgeError> {
        // NOTE: owned, so the coroutine outlives the borrowed name.
        let name: Mailbox<'static> = mailbox
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox name `{mailbox}`"))?;

        self.state.selected = None;
        let data = self.run(ImapMailboxExamine::new(
            name,
            ImapMailboxExamineOptions::default(),
        ))?;
        self.state.selected = Some((mailbox.into(), Access::Read));

        Ok(data)
    }

    /// SELECT one mailbox for writing, unless it already is.
    fn select(&mut self, mailbox: &str) -> Result<(), BridgeError> {
        if self.state.selected.as_ref() == Some(&(mailbox.into(), Access::Write)) {
            return Ok(());
        }

        let name: Mailbox<'static> = mailbox
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox name `{mailbox}`"))?;

        // NOTE: recorded before the command rather than after it. A
        // select that failed part-way leaves the connection on a mailbox
        // nobody can name, and claiming the old one is still open would
        // send the next command somewhere it was never meant to go.
        self.state.selected = None;
        self.run(ImapMailboxSelect::new(
            name,
            ImapMailboxSelectOptions::default(),
        ))?;
        self.state.selected = Some((mailbox.into(), Access::Write));

        Ok(())
    }
}

/// One message's UID as the sequence set a UID command takes.
fn uids(uid: &str) -> Result<SequenceSet, BridgeError> {
    uid.try_into()
        .map_err(|_| BridgeError::from(format!("Invalid message id `{uid}`")))
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
                    // NOTE: the ENVELOPE is the header text itself, so what
                    // arrives here is what was written on the wire. A list
                    // drawing it raw shows the encoded words a reader
                    // expects to have been decoded for it.
                    subject = mail::decode_header(&String::from_utf8_lossy(value.as_ref()));
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

    // Decoded on the same terms as the subject: a sender writing their
    // own name in their own script reaches the envelope as encoded words,
    // and a row is where that name is read.
    let name = mail::decode_header(&String::from_utf8_lossy(name.as_ref()));
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

/// Reads one message whole: connect, EXAMINE its mailbox and fetch it,
/// as the bytes the server holds.
///
/// The MIME tree is nobody's business here. What a reader draws is
/// derived from these bytes by [`crate::mail::parse`], and derived again
/// from the same bytes once the store holds them, which is what makes
/// opening a message a second time cost nothing.
pub fn fetch_source(
    session: &mut ImapSession<'_, '_, '_>,
    mailbox: &str,
    id: &str,
) -> Result<Vec<u8>, BridgeError> {
    session.fetch_raw(mailbox, id)
}

/// Opens a session on `url` and authenticates it, for a caller that will
/// hold it across calls.
pub fn open(
    client: &mut Client<'_, '_>,
    url: &Url,
    credentials: &Credentials,
) -> Result<ImapState, BridgeError> {
    let mut state = ImapState::new(url);
    ImapSession::bind(client, &mut state).connect(credentials)?;

    Ok(state)
}

/// The account's mailboxes, with the roles their attributes mark.
///
/// The roster and nothing else. It used to take a window of every
/// mailbox with it, because every native call was a connection and a
/// per-mailbox verb would have been a per-mailbox login; a session lasts
/// the pass now, so each mailbox is enumerated when its turn comes and
/// only what changed is read.
pub fn list_mailboxes(
    session: &mut ImapSession<'_, '_, '_>,
) -> Result<Vec<MailboxEntry>, BridgeError> {
    Ok(session
        .list()?
        .into_iter()
        .map(|(name, attributes)| MailboxEntry {
            role: role_of(&attributes),
            name,
        })
        .collect())
}

/// The role a mailbox's RFC 6154 attributes give it, of the one a write
/// needs, empty where they give it another.
fn role_of(attributes: &[FlagNameAttribute<'static>]) -> String {
    let trash = attributes
        .iter()
        .any(|held| held.to_string().eq_ignore_ascii_case("\\Trash"));

    if trash {
        String::from("trash")
    } else {
        String::new()
    }
}

#[cfg(test)]
mod tests {
    use io_imap::types::{
        body::{BasicFields, Body, BodyStructure, Disposition, SinglePartExtensionData},
        core::{IString, NString},
    };

    use super::attaches;

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
}

/// One enumerated mailbox: what moved, what went, and where the next
/// round resumes.
pub struct Enumeration {
    pub items: Vec<SpineEntry>,
    /// UIDs the server reported expunged since the cursor.
    pub vanished: Vec<String>,
    /// True when the round listed the whole window rather than a delta,
    /// so the caller may retire what it did not mention.
    pub complete: bool,
    /// The `(UIDVALIDITY, HIGHESTMODSEQ)` pair the next round resumes
    /// from, opaque to everyone above.
    pub checkpoint: String,
}

/// One member of an enumerated mailbox: its UID and its markers.
pub struct SpineEntry {
    pub id: String,
    pub flags: Vec<String>,
}

/// One FETCH response as the spine entry it stands for; [`None`] when it
/// carried no UID, which is nothing this can address.
fn spine_entry(items: &[MessageDataItem<'static>]) -> Option<SpineEntry> {
    let mut id = None;
    let mut flags = Vec::new();

    for item in items {
        match item {
            MessageDataItem::Uid(uid) => id = Some(uid.get().to_string()),
            MessageDataItem::Flags(fetched) => {
                flags = fetched
                    .iter()
                    .filter_map(|flag| match flag {
                        FlagFetch::Flag(flag) => Some(flag.to_string()),
                        _ => None,
                    })
                    .collect();
            }
            _ => {}
        }
    }

    Some(SpineEntry { id: id?, flags })
}

/// The cursor a mailbox resumes from, as the two numbers it is.
///
/// Text rather than bytes because the store keeps a checkpoint as text,
/// and two decimal numbers behind a colon read the same in a log as they
/// do in the column.
fn encode_cursor(uid_validity: u32, highest_mod_seq: u64) -> String {
    format!("{uid_validity}:{highest_mod_seq}")
}

/// The inverse of [`encode_cursor`]; [`None`] for anything it did not
/// write, which is what a store written by an earlier version holds.
fn decode_cursor(cursor: &str) -> Option<(u32, u64)> {
    let (validity, modseq) = cursor.split_once(':')?;

    Some((validity.parse().ok()?, modseq.parse().ok()?))
}
