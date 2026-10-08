//! IMAP operations: the mailbox list, the envelope spine, one message
//! whole, and the verbs a push carries out on one, run over
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
        search::{ImapMessageSearch, ImapMessageSearchOptions},
        select::{ImapMailboxSelect, ImapMailboxSelectOptions},
        store::{ImapMessageStoreOptions, ImapMessageStoreSilent},
    },
    rfc4315::expunge_uid::ImapMessageExpungeUid,
    rfc5161::enable::ImapExtensionEnable,
    rfc6851::r#move::{ImapMessageMove, ImapMessageMoveOptions},
    sasl::{
        auth_plain::{ImapAuthPlain, ImapAuthPlainOptions},
        auth_xoauth2::{ImapAuthXoauth2, ImapAuthXoauth2Options},
    },
    types::{
        IntoStatic,
        command::SelectParameter,
        core::{AString, Atom, Vec1},
        datetime::NaiveDate,
        extensions::enable::CapabilityEnable,
        fetch::{MacroOrMessageDataItemNames, MessageDataItem, MessageDataItemName, Section},
        flag::{Flag, FlagFetch, FlagNameAttribute, StoreType},
        mailbox::{ListMailbox, Mailbox},
        response::Capability,
        search::SearchKey,
        sequence::SequenceSet,
    },
};
use io_pimdir::summary::{
    PimdirSummary,
    mail::{PimdirMailSummary, derive_meta},
};
use url::Url;

use crate::{
    client::{
        Client,
        listing::{
            Floor, IMAP_PAGE, Listing, MailPage, MailRequest, Named, SENT_MARGIN_DAYS, Scope,
        },
        url_user,
    },
    types::{BridgeError, Credentials, Mailbox as MailboxEntry},
};

/// io-imap's own fragmentizer ceiling, 100 MiB per message.
const MAX_MESSAGE_SIZE: u32 = 100 * 1024 * 1024;

/// The header fields a listing reads off every message: what Annex A's
/// summary and addresses are derived from, `Content-Type` for the
/// attachment mark read without the body.
const HEADER_FIELDS: [&str; 9] = [
    "DATE",
    "FROM",
    "TO",
    "CC",
    "BCC",
    "SUBJECT",
    "MESSAGE-ID",
    "IN-REPLY-TO",
    "CONTENT-TYPE",
];

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
    /// The UIDs the last `UID SEARCH` found in a scope, newest first,
    /// keyed by the mailbox, its UIDVALIDITY and the scope: a round's
    /// pages walk them down without searching again.
    searched: Option<((String, u32, String), Vec<u32>)>,
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
            searched: None,
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
    /// AUTHENTICATE PLAIN for a password, and LOGIN would be a second
    /// code path for no provider this app onboards. A token (empty login)
    /// takes XOAUTH2 as the user the URL names, which is what Google and
    /// Microsoft accept, the latter nothing else.
    pub fn connect(&mut self, credentials: &Credentials) -> Result<(), BridgeError> {
        self.run(ImapGreetingGet::new(ImapGreetingGetOptions {
            ensure_capabilities: true,
        }))?;

        self.state.capabilities = if credentials.login.is_empty() {
            let user = url_user(&self.state.url)?;
            // NOTE: a refused token is the 401 of the HTTP backends, which
            // is what the caller renews an access token on, an hour being
            // all a provider's token lasts.
            self.run(ImapAuthXoauth2::new(
                user,
                credentials.password,
                ImapAuthXoauth2Options {
                    initial_request: false,
                    ensure_capabilities: true,
                    auto_id: None,
                },
            ))
            .map_err(|err| BridgeError {
                message: err.message,
                status: Some(401),
            })?
        } else {
            self.run(ImapAuthPlain::new(
                None::<&str>,
                credentials.login,
                credentials.password,
                ImapAuthPlainOptions {
                    initial_request: false,
                    ensure_capabilities: true,
                    auto_id: None,
                },
            ))?
        };

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

    /// One page of a mailbox's listing (pimdir SYNC §4, §5), every member
    /// named by the header fields Annex A reads.
    ///
    /// A delta is a QRESYNC `SELECT` from the `(UIDVALIDITY,
    /// HIGHESTMODSEQ)` checkpoint: the server streams the messages whose
    /// markers moved and the UIDs that went, and the ones that moved are
    /// read for their headers in the same pass. A server without QRESYNC,
    /// or a checkpoint the handle space has outgrown, gets a round's first
    /// page instead, which the engine takes as the round it is.
    ///
    /// A round lists the scope newest first, 500 UIDs a page: the UIDs
    /// `UID SEARCH` finds in the scope (`SENTSINCE` a day below its floor,
    /// `SENTBEFORE` a day above its ceiling, `ALL` when it has neither),
    /// read once per session, then each page's headers by `UID FETCH`.
    /// The cursor is the lowest UID a page reached under its UIDVALIDITY,
    /// so a round cut off resumes below it, and one whose UIDVALIDITY
    /// moved is refused for the engine to restart. The checkpoint is taken
    /// at the first page's EXAMINE, so what moves during the round is the
    /// next delta's.
    pub fn list_page(
        &mut self,
        mailbox: &str,
        request: &MailRequest,
    ) -> Result<MailPage, BridgeError> {
        match &request.listing {
            Listing::Delta { checkpoint } => {
                if let Some((validity, modseq)) = decode_cursor(checkpoint)
                    && modseq > 0
                    && self.supports_qresync()
                    && let Some(validity) = NonZeroU32::new(validity)
                    && let Some(delta) = self.select_qresync(mailbox, validity, modseq)?
                {
                    let mut items = Vec::with_capacity(delta.changed.len());
                    for chunk in delta.changed.chunks(IMAP_PAGE) {
                        items.extend(self.fetch_named(mailbox, chunk, &request.scope)?);
                    }
                    return Ok(MailPage::delta(items, delta.vanished, delta.checkpoint));
                }
                self.round_page(mailbox, None, &request.scope, false)
            }
            Listing::Round { cursor, band } => {
                self.round_page(mailbox, cursor.as_deref(), &request.scope, *band)
            }
        }
    }

    /// One page of a round over `scope`, below `cursor` when resumed.
    fn round_page(
        &mut self,
        mailbox: &str,
        cursor: Option<&str>,
        scope: &Scope,
        band: bool,
    ) -> Result<MailPage, BridgeError> {
        // NOTE: examined on every page, cache or no cache: the UIDVALIDITY
        // is what says whether the cursor still names these UIDs.
        let data = self.examine(mailbox)?;
        let validity = data.uid_validity.map(NonZeroU32::get).unwrap_or(0);

        let below = match cursor {
            None => None,
            Some(cursor) => match decode_cursor(cursor) {
                Some((held, uid)) if held == validity => Some(uid as u32),
                _ => return Ok(MailPage::rejected()),
            },
        };

        let uids = match data.exists.unwrap_or(0) {
            0 => Vec::new(),
            _ => self.scoped_uids(mailbox, validity, scope)?,
        };
        let mut remaining = uids
            .iter()
            .copied()
            .filter(|uid| below.is_none_or(|below| *uid < below));
        let page: Vec<u32> = remaining.by_ref().take(IMAP_PAGE).collect();
        let more = remaining.next().is_some();

        let items = self.fetch_named(mailbox, &page, scope)?;
        let next = match (more, page.last()) {
            (true, Some(lowest)) => Some(encode_cursor(validity, u64::from(*lowest))),
            _ => None,
        };
        // NOTE: up front, on the first page: a band round keeps the
        // checkpoint it has and hands none.
        let checkpoint = (cursor.is_none() && !band)
            .then(|| encode_cursor(validity, data.highest_mod_seq.unwrap_or(0)));

        Ok(MailPage::round(items, next, checkpoint))
    }

    /// Takes the newest messages below the floor's ceiling until its chunk
    /// is full: the UIDs `UID SEARCH` finds below it (`SENTBEFORE` a day
    /// above it, `ALL` without one), read for their headers a chunk at a
    /// time, newest first, until the chunk is full or the mailbox runs out.
    pub fn floor(&mut self, mailbox: &str, floor: &mut Floor) -> Result<(), BridgeError> {
        let data = self.examine(mailbox)?;
        if data.exists.unwrap_or(0) == 0 {
            return Ok(());
        }
        let validity = data.uid_validity.map(NonZeroU32::get).unwrap_or(0);
        let scope = floor.scope().clone();
        let uids = self.scoped_uids(mailbox, validity, &scope)?;

        for chunk in uids.chunks(floor.count().min(IMAP_PAGE)) {
            let mut listed = self.fetch_listed(mailbox, chunk)?;
            listed.sort_by_key(|(named, _)| {
                core::cmp::Reverse(named.handle.parse::<u32>().unwrap_or(0))
            });
            for (_, summary) in listed {
                if floor.take(summary.date.as_deref()) {
                    return Ok(());
                }
            }
        }
        Ok(())
    }

    /// The UIDs of the scope, newest first, searched once per session.
    fn scoped_uids(
        &mut self,
        mailbox: &str,
        validity: u32,
        scope: &Scope,
    ) -> Result<Vec<u32>, BridgeError> {
        let key = (mailbox.to_string(), validity, scope.key());
        if let Some((held, uids)) = &self.state.searched
            && *held == key
        {
            return Ok(uids.clone());
        }

        let mut criteria = Vec::new();
        if let Some(since) = scope.received_since(SENT_MARGIN_DAYS) {
            criteria.push(SearchKey::SentSince(imap_date(since)?));
        }
        if let Some(until) = scope.received_until(SENT_MARGIN_DAYS) {
            criteria.push(SearchKey::SentBefore(imap_date(until)?));
        }
        if criteria.is_empty() {
            criteria.push(SearchKey::All);
        }
        let criteria = Vec1::try_from(criteria).expect("one criterion at least");

        let mut uids: Vec<u32> = self
            .run(ImapMessageSearch::new(
                criteria,
                ImapMessageSearchOptions { uid: true },
            ))?
            .into_iter()
            .map(NonZeroU32::get)
            .collect();
        uids.sort_unstable_by(|a, b| b.cmp(a));
        uids.dedup();

        self.state.searched = Some((key, uids.clone()));
        Ok(uids)
    }

    /// The named members behind some UIDs of the open mailbox: their
    /// markers, their size and the header fields Annex A reads, the
    /// attachment mark from the top-level `Content-Type`. No
    /// `BODYSTRUCTURE`, by far the heaviest item of a bulk fetch: the mark
    /// is corrected from the parts when the message is opened. A member
    /// whose `Date` falls out of the scope is left out.
    fn fetch_named(
        &mut self,
        mailbox: &str,
        uids: &[u32],
        scope: &Scope,
    ) -> Result<Vec<Named>, BridgeError> {
        Ok(self
            .fetch_listed(mailbox, uids)?
            .into_iter()
            .filter(|(_, summary)| scope.contains(summary.date.as_deref()))
            .map(|(named, _)| named)
            .collect())
    }

    /// The members behind some UIDs of the open mailbox, each with the
    /// summary it was named by, whatever its date.
    fn fetch_listed(
        &mut self,
        mailbox: &str,
        uids: &[u32],
    ) -> Result<Vec<(Named, PimdirMailSummary)>, BridgeError> {
        if uids.is_empty() {
            return Ok(Vec::new());
        }
        if !self.opened(mailbox) {
            self.examine(mailbox)?;
        }

        let set = uids
            .iter()
            .map(u32::to_string)
            .collect::<Vec<_>>()
            .join(",");
        let sequence_set: SequenceSet = set
            .as_str()
            .try_into()
            .map_err(|_| format!("Invalid sequence set `{set}`"))?;
        let fields = HEADER_FIELDS
            .iter()
            .map(|field| AString::try_from(*field).expect("a header name is an IMAP atom"))
            .collect::<Vec<_>>();
        let items = MacroOrMessageDataItemNames::MessageDataItemNames(vec![
            MessageDataItemName::Uid,
            MessageDataItemName::Flags,
            MessageDataItemName::Rfc822Size,
            MessageDataItemName::BodyExt {
                section: Some(Section::HeaderFields(
                    None,
                    Vec1::try_from(fields).expect("header fields are not none"),
                )),
                partial: None,
                peek: true,
            },
        ]);

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
            .filter_map(|items| named(items.into_inner()))
            .collect())
    }

    /// A QRESYNC `SELECT (QRESYNC (uidvalidity modseq))`, or [`None`]
    /// when the mailbox's handle space was rebuilt under the cursor and
    /// a full round is owed instead.
    fn select_qresync(
        &mut self,
        mailbox: &str,
        validity: NonZeroU32,
        modseq: u64,
    ) -> Result<Option<Delta>, BridgeError> {
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

        Ok(Some(Delta {
            changed: data
                .changed
                .iter()
                .filter_map(|fetch| uid_of(&fetch.items.clone().into_inner()))
                .collect(),
            vanished: data
                .vanished_earlier
                .iter()
                .map(|uid| uid.get().to_string())
                .collect(),
            checkpoint: encode_cursor(validity_now, data.highest_mod_seq.unwrap_or(modseq)),
        }))
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

/// The write verbs: what a push carries out on a message (pimdir SYNC §4).
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

    /// Relocates one message into `target` (pimdir SYNC §4): MOVE (RFC
    /// 6851) where the server has it, else COPY then the disposal of the
    /// original [`Self::destroy`] makes.
    pub fn relocate(&mut self, mailbox: &str, uid: &str, target: &str) -> Result<(), BridgeError> {
        self.select(mailbox)?;

        match relocation(&self.state.capabilities) {
            Relocation::Move => {
                self.run(ImapMessageMove::new(
                    uids(uid)?,
                    mailbox_name(target)?,
                    ImapMessageMoveOptions { uid: true },
                ))?;
            }
            Relocation::CopyThenDispose => {
                self.run(ImapMessageCopy::new(
                    uids(uid)?,
                    mailbox_name(target)?,
                    ImapMessageCopyOptions { uid: true },
                ))?;
                self.dispose(uid)?;
            }
        }

        Ok(())
    }

    /// Copies one message into `target`, the original kept.
    pub fn copy(&mut self, mailbox: &str, uid: &str, target: &str) -> Result<(), BridgeError> {
        self.select(mailbox)?;
        self.run(ImapMessageCopy::new(
            uids(uid)?,
            mailbox_name(target)?,
            ImapMessageCopyOptions { uid: true },
        ))?;

        Ok(())
    }

    /// Deletes one message for good: `\Deleted`, then `UID EXPUNGE` of
    /// that one message where the server announces UIDPLUS (RFC 4315).
    ///
    /// The marker alone otherwise: a plain EXPUNGE is mailbox-wide, so it
    /// would take every message another client had marked.
    pub fn destroy(&mut self, mailbox: &str, uid: &str) -> Result<(), BridgeError> {
        self.select(mailbox)?;
        self.dispose(uid)
    }

    /// Marks one message of the selected mailbox `\Deleted`, then expunges
    /// it where [`disposal`] says the server can do so for that one alone.
    fn dispose(&mut self, uid: &str) -> Result<(), BridgeError> {
        self.run(ImapMessageStoreSilent::new(
            uids(uid)?,
            StoreType::Add,
            vec![Flag::Deleted],
            ImapMessageStoreOptions { uid: true },
        ))?;

        if disposal(&self.state.capabilities) == Disposal::Expunge {
            self.run(ImapMessageExpungeUid::new(uids(uid)?))?;
        }

        Ok(())
    }

    /// Appends one message to `mailbox` with `flags`, the push of a create
    /// staged with no origin: the sent copy of a submitted message.
    pub fn append(
        &mut self,
        mailbox: &str,
        message: Vec<u8>,
        flags: &[String],
    ) -> Result<(), BridgeError> {
        let mut owned = Vec::with_capacity(flags.len());
        // NOTE: `\Recent` is the server's alone (RFC 3501 section 2.3.2),
        // refused on an APPEND.
        for flag in flags
            .iter()
            .filter(|flag| !flag.eq_ignore_ascii_case("\\Recent"))
        {
            let parsed = Flag::try_from(flag.as_str())
                .map(|flag| flag.into_static())
                .map_err(|_| format!("Invalid message marker `{flag}`"))?;
            owned.push(parsed);
        }

        self.run(ImapMessageAppend::new(
            mailbox_name(mailbox)?,
            message,
            ImapMessageAppendOptions {
                flags: owned,
                ..Default::default()
            },
        ))?;

        Ok(())
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

/// One mailbox name as a command takes it.
fn mailbox_name(name: &str) -> Result<Mailbox<'static>, BridgeError> {
    name.to_string()
        .try_into()
        .map_err(|_| BridgeError::from(format!("Invalid mailbox name `{name}`")))
}

/// How a server relocates one message.
#[derive(Debug, Eq, PartialEq)]
enum Relocation {
    /// One MOVE (RFC 6851).
    Move,
    /// A COPY, then the original disposed of as [`disposal`] says.
    CopyThenDispose,
}

/// How a server disposes of one message marked `\Deleted`.
#[derive(Debug, Eq, PartialEq)]
enum Disposal {
    /// `UID EXPUNGE` of that one message (RFC 4315).
    Expunge,
    /// The marker alone: an EXPUNGE without UIDPLUS is mailbox-wide.
    Mark,
}

/// How a server announcing `capabilities` relocates one message.
fn relocation(capabilities: &[Capability<'static>]) -> Relocation {
    match capabilities.contains(&Capability::Move) {
        true => Relocation::Move,
        false => Relocation::CopyThenDispose,
    }
}

/// How a server announcing `capabilities` disposes of one message.
fn disposal(capabilities: &[Capability<'static>]) -> Disposal {
    match capabilities.contains(&Capability::UidPlus) {
        true => Disposal::Expunge,
        false => Disposal::Mark,
    }
}

/// One FETCH response's items as the member they name, with the
/// summary its header fields derive; [`None`] when it carried no UID,
/// which is nothing this can address.
fn named(items: Vec<MessageDataItem<'static>>) -> Option<(Named, PimdirMailSummary)> {
    let mut uid = None;
    let mut flags = Vec::new();
    let mut size = None;
    let mut header = Vec::new();

    for item in items {
        match item {
            MessageDataItem::Uid(value) => uid = Some(value.get()),
            MessageDataItem::Flags(fetched) => flags = flag_names(&fetched),
            MessageDataItem::Rfc822Size(value) => size = Some(u64::from(value)),
            MessageDataItem::BodyExt { data, .. } => {
                if let Some(bytes) = data.0 {
                    header = bytes.into_inner().into_owned();
                }
            }
            _ => {}
        }
    }

    let derivation = derive_meta(&header, size, None);
    let Some(PimdirSummary::Mail(summary)) = derivation.summary else {
        return None;
    };
    Some((
        Named::new(uid?.to_string(), flags, summary.clone()),
        summary,
    ))
}

/// The markers of a FETCH response, as the store spells them.
fn flag_names(fetched: &[FlagFetch<'static>]) -> Vec<String> {
    fetched
        .iter()
        .filter_map(|flag| match flag {
            FlagFetch::Flag(flag) => Some(flag.to_string()),
            _ => None,
        })
        .collect()
}

/// The UID of a FETCH response, when it carried one.
fn uid_of(items: &[MessageDataItem<'static>]) -> Option<u32> {
    items.iter().find_map(|item| match item {
        MessageDataItem::Uid(uid) => Some(uid.get()),
        _ => None,
    })
}

/// A day as `SENTSINCE` and `SENTBEFORE` compare it.
fn imap_date(instant: jiff::Timestamp) -> Result<NaiveDate, BridgeError> {
    let date = instant.to_zoned(jiff::tz::TimeZone::UTC).date();
    chrono::NaiveDate::from_ymd_opt(
        i32::from(date.year()),
        date.month() as u32,
        date.day() as u32,
    )
    .and_then(|date| NaiveDate::try_from(date).ok())
    .ok_or_else(|| BridgeError::from(format!("No IMAP date for {instant}")))
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
            role: role_of(&name, &attributes),
            name,
        })
        .collect())
}

/// The role a mailbox's RFC 6154 attributes give it, in pimdir's role
/// vocabulary (the attribute lowercased), `inbox` for the mailbox RFC 3501
/// names `INBOX` whatever its case, empty where it carries none.
fn role_of(name: &str, attributes: &[FlagNameAttribute<'static>]) -> String {
    if name.eq_ignore_ascii_case("INBOX") {
        return String::from("inbox");
    }
    const ROLES: [&str; 8] = [
        "trash",
        "sent",
        "drafts",
        "junk",
        "archive",
        "all",
        "flagged",
        "important",
    ];
    attributes
        .iter()
        .find_map(|held| {
            let held = held.to_string();
            let named = held.strip_prefix('\\')?.to_string();
            ROLES
                .iter()
                .find(|role| role.eq_ignore_ascii_case(&named))
                .map(|role| String::from(*role))
        })
        .unwrap_or_default()
}

/// What a QRESYNC `SELECT` reported moved since the checkpoint.
struct Delta {
    /// The UIDs whose markers moved, or that arrived.
    changed: Vec<u32>,
    /// The UIDs the server reported expunged.
    vanished: Vec<String>,
    /// The `(UIDVALIDITY, HIGHESTMODSEQ)` pair the next delta lists from.
    checkpoint: String,
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

#[cfg(test)]
mod tests {
    use core::num::NonZeroU32;

    use io_imap::types::{
        core::NString,
        fetch::{MessageDataItem, Section},
        flag::{Flag, FlagFetch},
    };

    use super::{Capability, Disposal, Relocation, disposal, named, relocation};

    #[test]
    fn a_server_with_move_relocates_in_one_command() {
        let capabilities = [Capability::Move, Capability::UidPlus];
        assert_eq!(relocation(&capabilities), Relocation::Move);
    }

    #[test]
    fn a_server_without_move_copies_then_disposes() {
        assert_eq!(
            relocation(&[Capability::UidPlus]),
            Relocation::CopyThenDispose
        );
        assert_eq!(relocation(&[]), Relocation::CopyThenDispose);
    }

    #[test]
    fn only_uidplus_expunges_one_message() {
        assert_eq!(disposal(&[Capability::UidPlus]), Disposal::Expunge);
        assert_eq!(
            disposal(&[Capability::Move]),
            Disposal::Mark,
            "a plain EXPUNGE would take every message marked"
        );
    }

    #[test]
    fn a_fetched_header_block_names_the_member() {
        let header = b"Date: Wed, 07 Oct 2026 10:00:00 +0200\r\n\
From: =?UTF-8?Q?Ren=C3=A9?= <Rene@Example.org>\r\n\
Subject: Hello\r\n\
Message-ID: <m1@example.org>\r\n\
Content-Type: multipart/mixed; boundary=x\r\n\r\n"
            .to_vec();
        let items = vec![
            MessageDataItem::Uid(NonZeroU32::new(42).unwrap()),
            MessageDataItem::Flags(vec![FlagFetch::Flag(Flag::Seen)]),
            MessageDataItem::Rfc822Size(1234),
            MessageDataItem::BodyExt {
                section: Some(Section::Header(None)),
                origin: None,
                data: NString::try_from(header).unwrap(),
            },
        ];

        let (named, summary) = named(items).expect("a UID names a member");
        assert_eq!(named.handle, "42");
        assert_eq!(named.link_id, "42", "the UID is the identity, as ever");
        assert_eq!(named.flags, vec![String::from("\\Seen")]);
        assert_eq!(named.sort_key, "2026-10-07T08:00:00Z");
        assert_eq!(summary.subject, "Hello");
        assert_eq!(summary.sender.as_deref(), Some("rene@example.org"));
        assert_eq!(summary.sender_name.as_deref(), Some("René"));
        assert_eq!(summary.size, Some(1234));
        assert_eq!(
            summary.attachment,
            Some(true),
            "multipart/mixed, without the body"
        );
    }
}
