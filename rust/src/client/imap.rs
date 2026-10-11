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
use std::{
    collections::{BTreeMap, HashMap},
    fs::{self, File},
    io::Write,
    path::{Path, PathBuf},
};

use io_imap::{
    codec::fragmentizer::Fragmentizer,
    coroutine::{ImapCoroutine, ImapCoroutineState, ImapYield},
    rfc3501::{
        append::{ImapMessageAppend, ImapMessageAppendOptions},
        copy::{ImapMessageCopy, ImapMessageCopyOptions},
        examine::{
            ExamineData, ImapMailboxExamine, ImapMailboxExamineError, ImapMailboxExamineOptions,
        },
        fetch::{ImapMessageFetch, ImapMessageFetchError, ImapMessageFetchOptions},
        fetch_stream_batch::{
            ImapMessageFetchStreamBatch, ImapMessageFetchStreamBatchError,
            ImapMessageFetchStreamBatchOptions, ImapMessageFetchStreamBatchYield,
        },
        greeting::{ImapGreetingGet, ImapGreetingGetOptions},
        list::ImapMailboxList,
        search::{ImapMessageSearch, ImapMessageSearchOptions},
        select::{ImapMailboxSelect, ImapMailboxSelectError, ImapMailboxSelectOptions},
        store::{ImapMessageStoreOptions, ImapMessageStoreSilent},
    },
    rfc4315::expunge_uid::ImapMessageExpungeUid,
    rfc5161::enable::ImapExtensionEnable,
    rfc5256::sort::{ImapMessageSort, ImapMessageSortOptions},
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
        extensions::{
            enable::CapabilityEnable,
            sort::{SortCriterion, SortKey},
        },
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
use jiff::{SignedDuration, Timestamp};
use url::Url;

use crate::{
    client::{
        Client,
        gmail::source_path,
        listing::{
            Floor, IMAP_PAGE, Listing, MailPage, MailRequest, Named, SENT_MARGIN_DAYS, Scope,
        },
        trust::require_tls,
        url_user,
    },
    mail,
    types::{BridgeError, Credentials, Mailbox as MailboxEntry},
};

/// io-imap's own fragmentizer ceiling, 100 MiB per message.
const MAX_MESSAGE_SIZE: u32 = 100 * 1024 * 1024;

/// How far below its ceiling a sorted floor looks, widened until its chunk
/// is full: 90 days, a year, then all mail.
const SORT_WINDOWS_DAYS: [Option<i64>; 3] = [Some(90), Some(365), None];

/// How many times an unsorted floor doubles its sample of the newest UIDs
/// past the first chunk: 50, 100, 200, 400 then 800 header reads at most.
const SAMPLE_WIDENINGS: usize = 4;

/// The status a mailbox the server refused to open crosses with, as an
/// HTTP backend's missing resource would: a `NO` to `EXAMINE` or `SELECT`,
/// which RFC 3501 6.3.1 answers for a mailbox that does not exist or
/// cannot be accessed (Gmail's `Unknown Mailbox`, Exchange's `doesn't
/// exist`, RFC 5530's `[NONEXISTENT]`), the connection standing.
const MAILBOX_REFUSED: u16 = 404;

/// The most messages one streamed body fetch reads.
const BODY_BATCH: usize = 50;

/// About how many bytes one streamed body fetch reads, by the sizes the
/// server declares; a message past it is read alone.
const BODY_BATCH_BYTES: u64 = 12 * 1024 * 1024;

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
    /// What was read of the mailbox last examined, while it has not
    /// moved ([`Cache`]).
    cache: Option<Cache>,
}

/// What was read of one mailbox, kept while its UIDVALIDITY and stamp
/// hold: the UIDs a `UID SEARCH` or `UID SORT` found per scope, so a
/// round's pages walk them down without searching again, and the header
/// fields a floor read, so the round after it reads them no more.
struct Cache {
    mailbox: String,
    validity: u32,
    /// UIDNEXT, HIGHESTMODSEQ and EXISTS at its EXAMINE: a message in,
    /// out or remarked moves one of them.
    stamp: (Option<u32>, Option<u64>, Option<u32>),
    searched: HashMap<String, Vec<u32>>,
    /// Kept only where the server reports a HIGHESTMODSEQ: without one,
    /// a marker moving moves nothing in the stamp.
    headers: HashMap<u32, Header>,
}

/// What a listing reads of one message: its markers, the summary its
/// header fields derive, and when the server received it.
#[derive(Clone)]
struct Header {
    flags: Vec<String>,
    summary: PimdirMailSummary,
    received: Option<Timestamp>,
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
            cache: None,
        }
    }

    /// Whether the server expunges one message alone ([`disposal`]).
    pub fn expunges_one(&self) -> bool {
        disposal(&self.capabilities) == Disposal::Expunge
    }

    /// Keeps what was read of `mailbox` while its EXAMINE says it has not
    /// moved since, and starts afresh otherwise: another mailbox, another
    /// UIDVALIDITY, a message in, out or remarked.
    fn observe(&mut self, mailbox: &str, data: &ExamineData) {
        let validity = data.uid_validity.map(NonZeroU32::get).unwrap_or(0);
        let stamp = (
            data.uid_next.map(NonZeroU32::get),
            data.highest_mod_seq.filter(|modseq| *modseq > 0),
            data.exists,
        );
        let holds = self.cache.as_ref().is_some_and(|cache| {
            cache.mailbox == mailbox
                && cache.validity == validity
                && cache.stamp == stamp
                && cache.stamp.1.is_some()
        });
        if holds {
            return;
        }
        // NOTE: without a HIGHESTMODSEQ, the searches stand while the
        // UIDs do, and the header fields go with every EXAMINE.
        let same = self.cache.as_ref().is_some_and(|cache| {
            cache.mailbox == mailbox && cache.validity == validity && cache.stamp == stamp
        });
        match (same, self.cache.as_mut()) {
            (true, Some(cache)) => cache.headers.clear(),
            _ => {
                self.cache = Some(Cache {
                    mailbox: mailbox.to_string(),
                    validity,
                    stamp,
                    searched: HashMap::new(),
                    headers: HashMap::new(),
                })
            }
        }
    }

    /// The cache of `mailbox` under `validity`, when it holds one.
    fn cache_of(&mut self, mailbox: &str, validity: u32) -> Option<&mut Cache> {
        self.cache
            .as_mut()
            .filter(|cache| cache.mailbox == mailbox && cache.validity == validity)
    }

    /// Whether the server advertises SORT (RFC 5256).
    fn sorts(&self) -> bool {
        self.capabilities
            .iter()
            .any(|capability| matches!(capability, Capability::Sort(_)))
    }
}

/// The transport an IMAP session runs over: the Java one a native call
/// hands in ([`Client`]), or a scripted server in tests.
pub trait ImapWire {
    /// Reads the next chunk from `url`'s stream, empty at its end.
    fn imap_read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError>;

    /// Writes bytes to `url`'s stream.
    fn imap_write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError>;
}

impl ImapWire for Client<'_, '_> {
    fn imap_read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        self.read(url)
    }

    fn imap_write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        self.write(url, bytes)
    }
}

/// One session bound to one native call: the state that outlives the
/// call, and the transport that does not.
pub struct ImapSession<'a> {
    wire: &'a mut dyn ImapWire,
    state: &'a mut ImapState,
}

impl<'a> ImapSession<'a> {
    /// Binds a held session to the call that is about to use it.
    pub fn bind(wire: &'a mut dyn ImapWire, state: &'a mut ImapState) -> Self {
        Self { wire, state }
    }

    /// Drives a coroutine to completion, servicing every read and write
    /// yield through the Java transport's stream for this URL.
    fn run<C, T, E>(&mut self, coroutine: C) -> Result<T, BridgeError>
    where
        C: ImapCoroutine<Yield = ImapYield, Return = Result<T, E>>,
        E: core::fmt::Display,
    {
        self.attempt(coroutine)?
            .map_err(|err| err.to_string().into())
    }

    /// [`Self::run`], the server's answer kept apart from the wire's
    /// failure, so a refusal can be told from a dead connection.
    fn attempt<C, T, E>(&mut self, mut coroutine: C) -> Result<Result<T, E>, BridgeError>
    where
        C: ImapCoroutine<Yield = ImapYield, Return = Result<T, E>>,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(&mut self.state.fragmentizer, arg.as_deref()) {
                ImapCoroutineState::Complete(result) => return Ok(result),
                ImapCoroutineState::Yielded(ImapYield::WantsRead) => {
                    arg = Some(self.wire.imap_read(&self.state.url)?);
                }
                ImapCoroutineState::Yielded(ImapYield::WantsWrite(bytes)) => {
                    self.wire.imap_write(&self.state.url, &bytes)?;
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
        // NOTE: the bridge speaks no IMAP STARTTLS, so a plain imap:// URL
        // could only ever sign in in clear.
        require_tls(&self.state.url)?;

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
            // NOTE: past the greeting, a failed login is the server refusing
            // the password, reported as the 401 of the HTTP backends so the
            // setup can say which domain refused it.
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
            .map_err(|err| BridgeError {
                message: err.message,
                status: Some(401),
            })?
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
                        items.extend(self.fetch_named(
                            mailbox,
                            validity.get(),
                            chunk,
                            &request.scope,
                        )?);
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

        let items = self.fetch_named(mailbox, validity, &page, scope)?;
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

    /// The floor of the mailbox's next chunk below the floor's ceiling: the
    /// `Date` of its `count`th newest message by `Date`, an order
    /// statistic rather than the oldest date among the newest UIDs, which
    /// one imported message dated years back would drag down.
    ///
    /// Where the server sorts (RFC 5256), `UID SORT (REVERSE DATE)` below
    /// the ceiling within a window widened until the chunk is full
    /// ([`SORT_WINDOWS_DAYS`]); otherwise a sample of the newest UIDs
    /// ([`Self::sampled_floor`]).
    pub fn floor(&mut self, mailbox: &str, floor: &mut Floor) -> Result<(), BridgeError> {
        let data = self.examine(mailbox)?;
        if data.exists.unwrap_or(0) == 0 {
            return Ok(());
        }
        let validity = data.uid_validity.map(NonZeroU32::get).unwrap_or(0);
        if self.state.sorts() {
            return self.sorted_floor(mailbox, validity, floor);
        }
        self.sampled_floor(mailbox, validity, floor)
    }

    /// [`Self::floor`] without `SORT`: the newest UIDs below the ceiling
    /// read for their header fields, `count` of them then a sample doubled
    /// at most [`SAMPLE_WIDENINGS`] times, every one offered to the floor
    /// ([`Floor::offer`]). The sample is settled once the last part read
    /// holds no message dated or received at or above the `count`th newest
    /// `Date` so far: UIDs grow with arrival, so below it lies older mail,
    /// whatever the imports among the newest UIDs.
    fn sampled_floor(
        &mut self,
        mailbox: &str,
        validity: u32,
        floor: &mut Floor,
    ) -> Result<(), BridgeError> {
        let scope = floor.scope().clone();
        let uids = self.scoped_uids(mailbox, validity, &scope)?;
        let mut read = 0;
        let mut part = floor.count().min(IMAP_PAGE);
        for _ in 0..=SAMPLE_WIDENINGS {
            let chunk = &uids[read..uids.len().min(read + part)];
            if chunk.is_empty() {
                return Ok(());
            }
            let mut newer = false;
            let mut received = Vec::with_capacity(chunk.len());
            for (_, header) in self.fetch_headers(mailbox, validity, chunk)? {
                newer |= floor.offer(header.summary.date.as_deref());
                received.extend(header.received);
            }
            read += chunk.len();
            part = read.min(IMAP_PAGE);

            let at = floor.floor().and_then(|at| at.parse::<Timestamp>().ok());
            let settled = at.is_some_and(|at| !newer && received.iter().all(|r| *r < at));
            if settled {
                return Ok(());
            }
        }
        if read < uids.len() {
            log::debug!(
                "{mailbox}: floor sampled from the newest {read} of {} UIDs",
                uids.len()
            );
        }
        Ok(())
    }

    /// [`Self::floor`] by `UID SORT`: the messages below the ceiling in
    /// `Date` order, newest first, within 90 days of it, then a year, then
    /// all of them, until `count` dated ones are found.
    fn sorted_floor(
        &mut self,
        mailbox: &str,
        validity: u32,
        floor: &mut Floor,
    ) -> Result<(), BridgeError> {
        let ceiling = floor.scope().until.clone();
        let count = floor.count();
        for window in SORT_WINDOWS_DAYS {
            let mut attempt = Floor::new(ceiling.as_deref(), count);
            let uids = self.sorted_uids(mailbox, validity, ceiling.as_deref(), window)?;
            'read: for chunk in uids.chunks(count.min(IMAP_PAGE)) {
                for (_, header) in self.fetch_headers(mailbox, validity, chunk)? {
                    if attempt.take(header.summary.date.as_deref()) {
                        break 'read;
                    }
                }
            }
            if attempt.full() || window.is_none() {
                *floor = attempt;
                return Ok(());
            }
        }
        Ok(())
    }

    /// The UIDs below `ceiling` (RFC 3339, none for no ceiling) in `Date`
    /// order, newest first: `UID SORT (REVERSE DATE) UTF-8 SENTBEFORE` a
    /// day above the ceiling, `SENTSINCE` `window` days below it (or below
    /// today), sorted once per session and scope.
    fn sorted_uids(
        &mut self,
        mailbox: &str,
        validity: u32,
        ceiling: Option<&str>,
        window: Option<i64>,
    ) -> Result<Vec<u32>, BridgeError> {
        let key = format!(
            "sort:{}:{}",
            ceiling.unwrap_or_default(),
            window.unwrap_or_default()
        );
        if let Some(uids) = self
            .state
            .cache_of(mailbox, validity)
            .and_then(|cache| cache.searched.get(&key))
        {
            return Ok(uids.clone());
        }

        let top = ceiling.and_then(|ceiling| ceiling.parse::<Timestamp>().ok());
        let mut criteria = Vec::new();
        if let Some(top) = top {
            let before = top
                .checked_add(SignedDuration::from_hours(24 * SENT_MARGIN_DAYS))
                .map_err(|err| err.to_string())?;
            criteria.push(SearchKey::SentBefore(imap_date(before)?));
        }
        if let Some(days) = window {
            let since = top
                .unwrap_or_else(Timestamp::now)
                .checked_sub(SignedDuration::from_hours(24 * days))
                .map_err(|err| err.to_string())?;
            criteria.push(SearchKey::SentSince(imap_date(since)?));
        }
        if criteria.is_empty() {
            criteria.push(SearchKey::All);
        }
        let criteria = Vec1::try_from(criteria).expect("one criterion at least");
        let order = Vec1::try_from(vec![SortCriterion {
            reverse: true,
            key: SortKey::Date,
        }])
        .expect("one sort criterion");

        let uids: Vec<u32> = self
            .run(ImapMessageSort::new(
                order,
                criteria,
                ImapMessageSortOptions {
                    uid: true,
                    fallback: false,
                },
            ))?
            .into_iter()
            .map(NonZeroU32::get)
            .collect();

        if let Some(cache) = self.state.cache_of(mailbox, validity) {
            cache.searched.insert(key, uids.clone());
        }
        Ok(uids)
    }

    /// The UIDs of the scope, newest first, searched once per session.
    fn scoped_uids(
        &mut self,
        mailbox: &str,
        validity: u32,
        scope: &Scope,
    ) -> Result<Vec<u32>, BridgeError> {
        let key = scope.key();
        if let Some(uids) = self
            .state
            .cache_of(mailbox, validity)
            .and_then(|cache| cache.searched.get(&key))
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

        if let Some(cache) = self.state.cache_of(mailbox, validity) {
            cache.searched.insert(key, uids.clone());
        }
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
        validity: u32,
        uids: &[u32],
        scope: &Scope,
    ) -> Result<Vec<Named>, BridgeError> {
        Ok(self
            .fetch_headers(mailbox, validity, uids)?
            .into_iter()
            .filter(|(_, header)| scope.contains(header.summary.date.as_deref()))
            .map(|(uid, header)| Named::new(uid.to_string(), header.flags, header.summary))
            .collect())
    }

    /// What a listing reads of some UIDs of the mailbox, in their order,
    /// whatever their date: the header fields a floor read already taken
    /// from the cache, the others fetched. A UID gone is left out.
    fn fetch_headers(
        &mut self,
        mailbox: &str,
        validity: u32,
        uids: &[u32],
    ) -> Result<Vec<(u32, Header)>, BridgeError> {
        let mut known: HashMap<u32, Header> = HashMap::new();
        if let Some(cache) = self.state.cache_of(mailbox, validity) {
            for uid in uids {
                if let Some(header) = cache.headers.get(uid) {
                    known.insert(*uid, header.clone());
                }
            }
        }
        let missing: Vec<u32> = uids
            .iter()
            .copied()
            .filter(|uid| !known.contains_key(uid))
            .collect();
        if !missing.is_empty() {
            let fetched = self.fetch_header_fields(mailbox, &missing)?;
            let keeps = self
                .state
                .cache
                .as_ref()
                .is_some_and(|cache| cache.stamp.1.is_some());
            if keeps && let Some(cache) = self.state.cache_of(mailbox, validity) {
                cache.headers.extend(fetched.clone());
            }
            known.extend(fetched);
        }
        Ok(uids
            .iter()
            .filter_map(|uid| known.remove(uid).map(|header| (*uid, header)))
            .collect())
    }

    /// `UID FETCH` of the markers, size, `INTERNALDATE` and header fields
    /// of some UIDs of the mailbox, opened if it is not.
    fn fetch_header_fields(
        &mut self,
        mailbox: &str,
        uids: &[u32],
    ) -> Result<Vec<(u32, Header)>, BridgeError> {
        if !self.opened(mailbox) {
            self.examine(mailbox)?;
        }

        let fields = HEADER_FIELDS
            .iter()
            .map(|field| AString::try_from(*field).expect("a header name is an IMAP atom"))
            .collect::<Vec<_>>();
        let items = MacroOrMessageDataItemNames::MessageDataItemNames(vec![
            MessageDataItemName::Uid,
            MessageDataItemName::Flags,
            MessageDataItemName::Rfc822Size,
            MessageDataItemName::InternalDate,
            MessageDataItemName::BodyExt {
                section: Some(Section::HeaderFields(
                    None,
                    Vec1::try_from(fields).expect("header fields are not none"),
                )),
                partial: None,
                peek: true,
            },
        ]);

        let fetched = match self.attempt(ImapMessageFetch::new(
            sequence_set(uids)?,
            items,
            ImapMessageFetchOptions {
                uid: true,
                ..Default::default()
            },
        ))? {
            Ok(fetched) => fetched,
            // NOTE: Gmail answers NO when a message of the set went
            // meanwhile, naming none: the set is halved until it does.
            Err(ImapMessageFetchError::No(text)) if uids.len() > 1 => {
                log::debug!("{mailbox}: {} headers refused: {text}", uids.len());
                let (newer, older) = uids.split_at(uids.len() / 2);
                let mut headers = self.fetch_header_fields(mailbox, newer)?;
                headers.extend(self.fetch_header_fields(mailbox, older)?);
                return Ok(headers);
            }
            Err(ImapMessageFetchError::No(text)) => {
                log::debug!("{mailbox}: header of {uids:?} refused: {text}");
                return Ok(Vec::new());
            }
            Err(err) => return Err(err.to_string().into()),
        };

        Ok(fetched
            .into_values()
            .filter_map(|items| header(items.into_inner()))
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
        self.state.observe(mailbox, &data);

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
impl ImapSession<'_> {
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

/// Some messages read whole into files, a batch at a time: what the
/// body step and a step store (`fetchMessageSources`).
impl ImapSession<'_> {
    /// Reads the messages `ids` names of `mailbox` whole, one file each in
    /// `dir`: their sizes first (`UID FETCH <set> (UID RFC822.SIZE)`), then
    /// one streamed `UID FETCH <set> (UID BODY.PEEK[])` per batch of at most
    /// [`BODY_BATCH`] messages and about [`BODY_BATCH_BYTES`], a message past
    /// it read alone, each body written to its file as it streams. A message
    /// gone is left out; a batch the server refuses is read one message
    /// after another, one it fails to hand over left out.
    pub fn fetch_sources(
        &mut self,
        mailbox: &str,
        ids: &[String],
        dir: &Path,
    ) -> Result<Vec<ImapSource>, BridgeError> {
        let uids: Vec<u32> = ids.iter().filter_map(|id| id.parse().ok()).collect();
        if uids.is_empty() {
            return Ok(Vec::new());
        }
        if !self.opened(mailbox) {
            self.examine(mailbox)?;
        }

        let sizes = self.sizes(&uids)?;
        let mut written = Vec::with_capacity(sizes.len());
        for batch in batches(&uids, &sizes) {
            match self.stream_batch(&batch, &sizes, dir, &mut written) {
                Ok(()) => {}
                Err(Refusal::Refused(err)) => {
                    log::warn!("batch of {} refused in {mailbox}: {err}", batch.len());
                    for uid in batch {
                        if written.iter().any(|source| source.id == uid.to_string()) {
                            continue;
                        }
                        match self.fetch_raw(mailbox, &uid.to_string()) {
                            Ok(bytes) => written.push(write_source(dir, uid, &bytes)?),
                            Err(err) => log::warn!("source of {uid} not read: {err}"),
                        }
                    }
                }
                Err(Refusal::Failed(err)) => return Err(err),
            }
        }
        Ok(written)
    }

    /// The sizes the server declares for some UIDs of the open mailbox,
    /// a UID gone left out.
    fn sizes(&mut self, uids: &[u32]) -> Result<HashMap<u32, u64>, BridgeError> {
        let items = MacroOrMessageDataItemNames::MessageDataItemNames(vec![
            MessageDataItemName::Uid,
            MessageDataItemName::Rfc822Size,
        ]);
        let fetched = self.run(ImapMessageFetch::new(
            sequence_set(uids)?,
            items,
            ImapMessageFetchOptions {
                uid: true,
                ..Default::default()
            },
        ))?;

        let mut sizes = HashMap::new();
        for items in fetched.into_values() {
            let mut uid = None;
            let mut size = 0;
            for item in items.into_inner() {
                match item {
                    MessageDataItem::Uid(value) => uid = Some(value.get()),
                    MessageDataItem::Rfc822Size(value) => size = u64::from(value),
                    _ => {}
                }
            }
            if let Some(uid) = uid {
                sizes.insert(uid, size);
            }
        }
        Ok(sizes)
    }

    /// One streamed `UID FETCH <set> (UID BODY.PEEK[])`, each body written
    /// to its file in `dir` as its octets arrive, never held whole, and
    /// refused past its declared size ([`literal_bound`]).
    fn stream_batch(
        &mut self,
        uids: &[u32],
        sizes: &HashMap<u32, u64>,
        dir: &Path,
        written: &mut Vec<ImapSource>,
    ) -> Result<(), Refusal> {
        let opts = ImapMessageFetchStreamBatchOptions {
            uid: true,
            max_literal_len: MAX_MESSAGE_SIZE,
            sizes: uids
                .iter()
                .filter_map(|uid| Some((*uid, literal_bound(*sizes.get(uid)?))))
                .collect::<BTreeMap<_, _>>(),
        };
        let mut coroutine = ImapMessageFetchStreamBatch::new(sequence_set(uids)?, opts);
        let mut arg: Option<Vec<u8>> = None;
        let mut current: Option<(u32, File, u64)> = None;
        // NOTE: the octets a stream read past a body's end, handed back to
        // the coroutine once that body closes.
        let mut past = Vec::new();

        loop {
            let state = coroutine.resume(&mut self.state.fragmentizer, arg.take().as_deref());
            match state {
                ImapCoroutineState::Complete(Ok(())) => return Ok(()),
                ImapCoroutineState::Complete(Err(err)) => {
                    if let Some((uid, _, _)) = current {
                        let _ = fs::remove_file(source_path(dir, &uid.to_string()));
                    }
                    return Err(match err {
                        ImapMessageFetchStreamBatchError::No(_)
                        | ImapMessageFetchStreamBatchError::Bad(_) => {
                            Refusal::Refused(err.to_string().into())
                        }
                        _ => Refusal::Failed(err.to_string().into()),
                    });
                }
                ImapCoroutineState::Yielded(ImapMessageFetchStreamBatchYield::WantsRead) => {
                    arg = Some(self.wire.imap_read(&self.state.url)?);
                }
                ImapCoroutineState::Yielded(ImapMessageFetchStreamBatchYield::WantsWrite(
                    bytes,
                )) => {
                    self.wire.imap_write(&self.state.url, &bytes)?;
                }
                ImapCoroutineState::Yielded(ImapMessageFetchStreamBatchYield::MessageStart {
                    uid,
                }) => {
                    let path = source_path(dir, &uid.to_string());
                    let file = File::create(&path)
                        .map_err(|err| format!("Could not write `{uid}`: {err}"))?;
                    current = Some((uid, file, 0));
                }
                ImapCoroutineState::Yielded(ImapMessageFetchStreamBatchYield::BodyChunk(bytes)) => {
                    append(&mut current, &bytes)?;
                }
                ImapCoroutineState::Yielded(ImapMessageFetchStreamBatchYield::WantsStream {
                    len,
                }) => {
                    let mut left = len as usize;
                    while left > 0 {
                        let chunk = self.wire.imap_read(&self.state.url)?;
                        if chunk.is_empty() {
                            break;
                        }
                        let take = left.min(chunk.len());
                        append(&mut current, &chunk[..take])?;
                        past.extend_from_slice(&chunk[take..]);
                        left -= take;
                    }
                    // NOTE: an empty slice tells the coroutine the stream
                    // ran short of the body.
                    arg = (left > 0).then(Vec::new);
                }
                ImapCoroutineState::Yielded(ImapMessageFetchStreamBatchYield::MessageEnd) => {
                    if let Some((uid, file, size)) = current.take() {
                        file.sync_all()
                            .map_err(|err| format!("Could not write `{uid}`: {err}"))?;
                        written.push(ImapSource {
                            id: uid.to_string(),
                            path: source_path(dir, &uid.to_string()),
                            size,
                        });
                    }
                    if !past.is_empty() {
                        arg = Some(core::mem::take(&mut past));
                    }
                }
            }
        }
    }
}

/// One message read whole into a file.
#[derive(Debug)]
pub struct ImapSource {
    pub id: String,
    pub path: PathBuf,
    pub size: u64,
}

/// Why a streamed batch stopped short.
enum Refusal {
    /// The server answered NO or BAD: the connection stands, and the batch
    /// can be read one message after another.
    Refused(BridgeError),
    /// The connection or the disk failed: nothing more is read.
    Failed(BridgeError),
}

impl From<BridgeError> for Refusal {
    fn from(err: BridgeError) -> Self {
        Self::Failed(err)
    }
}

impl From<String> for Refusal {
    fn from(err: String) -> Self {
        Self::Failed(err.into())
    }
}

/// The most octets a body declared `size` octets long may stream: twice
/// its `RFC822.SIZE` and 64 KiB more, since some servers (Exchange) state an
/// estimate, within [`MAX_MESSAGE_SIZE`].
fn literal_bound(size: u64) -> u32 {
    let bound = size.saturating_mul(2).saturating_add(64 * 1024);
    u32::try_from(bound)
        .unwrap_or(u32::MAX)
        .min(MAX_MESSAGE_SIZE)
}

/// Some UIDs cut into the batches a streamed fetch reads, in their order:
/// at most [`BODY_BATCH`] messages and about [`BODY_BATCH_BYTES`] by the
/// sizes the server declared, a message past it alone, a UID it declared
/// no size for (gone) left out.
fn batches(uids: &[u32], sizes: &HashMap<u32, u64>) -> Vec<Vec<u32>> {
    let mut batches = Vec::new();
    let mut batch: Vec<u32> = Vec::new();
    let mut bytes = 0;
    for uid in uids {
        let Some(size) = sizes.get(uid).copied() else {
            continue;
        };
        if !batch.is_empty() && (batch.len() >= BODY_BATCH || bytes + size > BODY_BATCH_BYTES) {
            batches.push(core::mem::take(&mut batch));
            bytes = 0;
        }
        batch.push(*uid);
        bytes += size;
    }
    if !batch.is_empty() {
        batches.push(batch);
    }
    batches
}

/// Appends a stretch of body to the message streaming.
fn append(current: &mut Option<(u32, File, u64)>, bytes: &[u8]) -> Result<(), BridgeError> {
    let Some((uid, file, size)) = current.as_mut() else {
        return Err("Body octets outside a message".into());
    };
    file.write_all(bytes)
        .map_err(|err| format!("Could not write `{uid}`: {err}"))?;
    *size += bytes.len() as u64;
    Ok(())
}

/// Writes one message read alone to its file in `dir`.
fn write_source(dir: &Path, uid: u32, bytes: &[u8]) -> Result<ImapSource, BridgeError> {
    let path = source_path(dir, &uid.to_string());
    fs::write(&path, bytes).map_err(|err| format!("Could not write `{uid}`: {err}"))?;
    Ok(ImapSource {
        id: uid.to_string(),
        path,
        size: bytes.len() as u64,
    })
}

/// The write verbs: what a push carries out on a message (pimdir SYNC §4).
///
/// SELECT rather than the EXAMINE the reads use, since a read-only
/// mailbox refuses both a STORE and a MOVE. Silent variants throughout:
/// the app already knows what it asked for, and the untagged FETCH the
/// echoing form brings back is a response to parse for nothing.
impl ImapSession<'_> {
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
    ///
    /// The COPY is skipped when `target` already holds the message's
    /// `Message-ID`: the caller runs a relocation again on a new connection
    /// when the last one died, and a COPY whose answer was lost with it has
    /// already filed the copy.
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
                if !self.copied(mailbox, uid, target)? {
                    self.select(mailbox)?;
                    self.run(ImapMessageCopy::new(
                        uids(uid)?,
                        mailbox_name(target)?,
                        ImapMessageCopyOptions { uid: true },
                    ))?;
                }
                self.select(mailbox)?;
                self.dispose(uid)?;
            }
        }

        Ok(())
    }

    /// Whether `target` holds a message with the `Message-ID` of message
    /// `uid` of `mailbox`; false when it carries none, which nothing could
    /// tell apart.
    fn copied(&mut self, mailbox: &str, uid: &str, target: &str) -> Result<bool, BridgeError> {
        let number: u32 = uid
            .parse()
            .map_err(|_| format!("Invalid message id `{uid}`"))?;
        let id = self
            .fetch_header_fields(mailbox, &[number])?
            .into_iter()
            .next()
            .and_then(|(_, header)| header.summary.message_id);
        match id {
            Some(id) => self.holds(target, &id),
            None => Ok(false),
        }
    }

    /// Whether `mailbox` holds a message whose `Message-ID` is `id` (its
    /// angle brackets off), by `UID SEARCH HEADER`.
    fn holds(&mut self, mailbox: &str, id: &str) -> Result<bool, BridgeError> {
        self.examine(mailbox)?;

        let field = AString::try_from("MESSAGE-ID").expect("a header name is an IMAP atom");
        let value = AString::try_from(format!("<{id}>").as_str())
            .map_err(|_| format!("Invalid Message-ID `{id}`"))?
            .into_static();
        let criteria =
            Vec1::try_from(vec![SearchKey::Header(field, value)]).expect("one criterion");

        let found = self.run(ImapMessageSearch::new(
            criteria,
            ImapMessageSearchOptions { uid: true },
        ))?;
        Ok(!found.is_empty())
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
    ///
    /// Skipped when `mailbox` already holds the message's `Message-ID`: an
    /// append whose answer was lost stays staged and comes back on the next
    /// pass, and a server filing sent mail itself holds the copy already.
    pub fn append(
        &mut self,
        mailbox: &str,
        message: Vec<u8>,
        flags: &[String],
    ) -> Result<(), BridgeError> {
        if let Some(id) = mail::message_id(&message)
            && self.holds(mailbox, &id)?
        {
            log::debug!("{mailbox}: holds <{id}> already, not appended");
            return Ok(());
        }

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
        let examined = self.attempt(ImapMailboxExamine::new(
            name,
            ImapMailboxExamineOptions::default(),
        ))?;
        let data = match examined {
            Ok(data) => data,
            Err(err @ ImapMailboxExamineError::No(_)) => return Err(refused(&err)),
            Err(err) => return Err(err.to_string().into()),
        };
        self.state.selected = Some((mailbox.into(), Access::Read));
        self.state.observe(mailbox, &data);

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
        // NOTE: a write moves what a read cached, and no EXAMINE says so
        // before the next read.
        self.state.cache = None;
        match self.attempt(ImapMailboxSelect::new(
            name,
            ImapMailboxSelectOptions::default(),
        ))? {
            Ok(_) => {}
            Err(err @ ImapMailboxSelectError::No(_)) => return Err(refused(&err)),
            Err(err) => return Err(err.to_string().into()),
        }
        self.state.selected = Some((mailbox.into(), Access::Write));

        Ok(())
    }
}

/// A mailbox the server refused to open ([`MAILBOX_REFUSED`]).
fn refused(err: &impl core::fmt::Display) -> BridgeError {
    BridgeError {
        message: err.to_string(),
        status: Some(MAILBOX_REFUSED),
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

/// One FETCH response's items as the UID it names and what a listing
/// reads of it, the summary derived from its header fields; [`None`] when
/// it carried no UID, which is nothing this can address.
fn header(items: Vec<MessageDataItem<'static>>) -> Option<(u32, Header)> {
    let mut uid = None;
    let mut flags = Vec::new();
    let mut size = None;
    let mut received = None;
    let mut fields = Vec::new();

    for item in items {
        match item {
            MessageDataItem::Uid(value) => uid = Some(value.get()),
            MessageDataItem::Flags(fetched) => flags = flag_names(&fetched),
            MessageDataItem::Rfc822Size(value) => size = Some(u64::from(value)),
            MessageDataItem::InternalDate(date) => {
                received = Timestamp::from_second(date.as_ref().timestamp()).ok();
            }
            MessageDataItem::BodyExt { data, .. } => {
                if let Some(bytes) = data.0 {
                    fields = bytes.into_inner().into_owned();
                }
            }
            _ => {}
        }
    }

    let derivation = derive_meta(&fields, size, None);
    let Some(PimdirSummary::Mail(summary)) = derivation.summary else {
        return None;
    };
    Some((
        uid?,
        Header {
            flags,
            summary,
            received,
        },
    ))
}

/// Some UIDs as the sequence set a UID command takes.
fn sequence_set(uids: &[u32]) -> Result<SequenceSet, BridgeError> {
    let set = uids
        .iter()
        .map(u32::to_string)
        .collect::<Vec<_>>()
        .join(",");
    set.as_str()
        .try_into()
        .map_err(|_| BridgeError::from(format!("Invalid sequence set `{set}`")))
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
pub fn list_mailboxes(session: &mut ImapSession<'_>) -> Result<Vec<MailboxEntry>, BridgeError> {
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
#[path = "imap_tests.rs"]
mod tests;
