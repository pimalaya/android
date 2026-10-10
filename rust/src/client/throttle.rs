//! Throttling, in one place for every HTTP backend: Graph, Gmail,
//! Google Calendar and People, CalDAV, CardDAV and JMAP.
//!
//! A server that throttles answers 429 or 503, often with a
//! `Retry-After`; Google answers its rate limits with a 429, or a 403
//! whose reason is `rateLimitExceeded` or `userRateLimitExceeded`. Each
//! of those means the request was not served, so it is sent again after
//! a wait rather than failing the sync pass: the server's `Retry-After`
//! (or the instant Gmail's message names, `Retry after …`), else a short
//! exponential back-off. Almost every such refusal is a per-second or a
//! concurrency limit, gone within a second or two; only an answer that
//! names a per-minute quota (`Quota exceeded for quota metric … Units
//! per minute per user`) waits for the next minute.
//!
//! Gmail is also paced before it is asked ([`pace_gmail`]), per account:
//! a rate of quota units per second, under Gmail's 250, and a per-minute
//! ceiling below the project's quota, shared by every worker, the body
//! downloads and the reader, so a long pass waits for its quota rather
//! than drawing refusals. A request someone is waiting on (a message
//! being opened) skips the queue the background work forms.
//!
//! **Why at the transport.** A coroutine is consumed by its run and
//! cannot be rewound, and the HTTP runners number in the dozens. An
//! HTTP request is a fixed sequence of bytes, though, so
//! [`Client::http_write`] records the bytes of the round in flight and
//! [`Client::http_read`] reads the head of its answer before the
//! coroutine sees any of it. A throttled answer is read to its end, so
//! the connection stays in step, the wait is taken, and the same bytes
//! are written again; the coroutine only ever sees the answer that was
//! not throttled, or the last throttled one once the budget is spent.
//!
//! **Bounded.** A phone sync never hangs on a throttling server: at
//! most [`MAX_RETRIES`] retries per request and [`WAIT_BUDGET`] of
//! waiting per native call, and a `Retry-After` longer than what is
//! left of it is not waited at all. Past either bound the throttled
//! answer reaches the coroutine, which reports the error as it would
//! have without any of this.
//!
//! An answer that cannot be read to its end with certainty (no
//! `Content-Length` nor chunked framing, a `Connection: close`, a head
//! or a body past the caps) is never retried, since writing again on a
//! connection the server is closing or still talking on would desync
//! it: it passes through untouched, bytes already read included.
//!
//! Every wait, a pacing one or a throttled one, is logged at debug.

use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, LazyLock, Mutex},
    time::{Duration, Instant},
};

use io_gmail::v1::send::GmailApiError;
use jiff::{Timestamp, fmt::rfc2822::DateTimeParser};

use crate::{client::Client, types::BridgeError};

/// How many times one request is sent again after a throttled answer.
const MAX_RETRIES: u32 = 4;

/// How long one native call waits on throttling servers in total.
/// Past it, the throttled answer is the call's answer.
pub(crate) const WAIT_BUDGET: Duration = Duration::from_secs(30);

/// How long one native call waits in total once a server refused it a
/// per-minute quota: room for one wait to the next minute and a short
/// one after it. Larger than [`WAIT_BUDGET`] because a minute's quota
/// cannot come back sooner than the minute does.
pub(crate) const QUOTA_WAIT_BUDGET: Duration = Duration::from_secs(75);

/// The first back-off when the server names no `Retry-After`, doubled
/// on each retry up to [`BACKOFF_MAX`], each wait jittered down to half
/// of it so workers throttled together do not come back together.
const BACKOFF_BASE: Duration = Duration::from_secs(1);

/// The longest single back-off when the server names no `Retry-After`.
const BACKOFF_MAX: Duration = Duration::from_secs(16);

/// The most a wait to the next minute is pushed past it, at random, so
/// workers refused together do not come back together.
const MINUTE_JITTER: Duration = Duration::from_secs(1);

/// The longest response head read before giving up on parsing it.
const HEAD_CAP: usize = 64 * 1024;

/// The longest throttled body read to its end; a longer one passes
/// through rather than being buffered.
const BODY_CAP: usize = 256 * 1024;

/// The longest reason a throttled answer is logged with.
const REASON_CAP: usize = 160;

/// Gmail quota units per second an account is held near. Gmail grants
/// 250 per user per second, a moving average, and a metadata read costs
/// 5, so a batch of 50 reads already spends a whole second; pacing at a
/// fifth below keeps clear of the limit rather than drawing its 429s.
/// A batch is paced by the units of every call it carries.
pub(crate) const GMAIL_UNITS_PER_SECOND: u32 = 200;

/// The units an account's pacing lets through at once after a pause, on
/// top of the request's own: a handful of small calls.
const GMAIL_BURST_UNITS: u32 = 50;

/// Gmail's per-minute quota per user, in quota units, as the Cloud
/// console of the app's project 991810147220 states it (2026-10-09):
/// 6,000 units per user per minute, below the 15,000 Gmail's "Usage
/// limits" page still documents, which Google calls the previous quota.
const GMAIL_UNITS_PER_MINUTE_PER_USER: u32 = 6_000;

/// The quota units background Gmail requests may spend in any sixty
/// seconds: the project's quota less a fifth, so the two sides' clocks
/// and the requests still in flight never carry a pass over it.
pub(crate) const GMAIL_UNITS_PER_MINUTE: u32 = GMAIL_UNITS_PER_MINUTE_PER_USER / 5 * 4;

/// The quota units urgent Gmail requests may spend in any sixty seconds:
/// the fifth the background ceiling leaves.
const GMAIL_URGENT_UNITS_PER_MINUTE: u32 = GMAIL_UNITS_PER_MINUTE_PER_USER - GMAIL_UNITS_PER_MINUTE;

/// How long an account's pacing is kept unused before it is dropped:
/// past a minute, nothing it holds bears on the next request.
const GMAIL_PACE_IDLE: Duration = Duration::from_secs(120);

/// The Gmail pacing of every account, by the key its requests carry.
static GMAIL_PACES: LazyLock<Mutex<HashMap<String, GmailPace>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// The request in flight on a [`Client`]: what was written, to which
/// URL, and whether its answer started.
#[derive(Default)]
pub(crate) struct Exchange {
    url: String,
    request: Vec<u8>,
    answering: bool,
}

/// What a throttled exchange needs of a connection: the bare transport,
/// the request in flight, the waiting already done, and the clock.
///
/// [`Client`] is the one connection the app runs; the trait exists so
/// the retry can be driven without a JVM, by a scripted server and a
/// clock that does not sleep.
pub(crate) trait Wire {
    /// Reads the next chunk from `url`'s stream, empty at its end.
    fn wire_read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError>;

    /// Writes bytes to `url`'s stream.
    fn wire_write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError>;

    /// The request in flight.
    fn exchange(&mut self) -> &mut Exchange;

    /// The time this native call has spent waiting on throttling.
    fn waited(&mut self) -> &mut Duration;

    /// The Gmail account the request in flight is paced under, none for
    /// any other request.
    fn paced(&self) -> Option<String> {
        None
    }

    /// Waits `delay`.
    fn sleep(&mut self, delay: Duration) {
        std::thread::sleep(delay);
    }

    /// The time now, for `Retry-After` dates and waits to the next minute.
    fn now(&self) -> Timestamp {
        Timestamp::now()
    }
}

impl Wire for Client<'_, '_> {
    fn wire_read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        self.read(url)
    }

    fn wire_write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        self.write(url, bytes)
    }

    fn exchange(&mut self) -> &mut Exchange {
        &mut self.exchange
    }

    fn waited(&mut self) -> &mut Duration {
        &mut self.waited
    }

    fn paced(&self) -> Option<String> {
        self.gmail_pace.clone()
    }
}

/// HTTP exchange with throttling, used by every HTTP runner in place of
/// the bare transport `read` and `write`.
impl<'a, 'local> Client<'a, 'local> {
    /// Writes request bytes to `url`, recording them so a throttled
    /// answer can be retried. A write after the answer started, or to
    /// another URL, opens a new request.
    pub(crate) fn http_write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        throttled_write(self, url, bytes)
    }

    /// Reads the next chunk of the answer from `url`. The first read of
    /// an answer reads its head, and the whole of it when it may be a
    /// throttled one, retrying that request within the bounds.
    pub(crate) fn http_read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        throttled_read(self, url)
    }

    /// Paces the Gmail request about to go out under the account `key`
    /// names, costing `units` ([`pace_gmail`]), and has its throttled
    /// answers hold that account's pacing; answers the wait.
    pub(crate) fn pace_gmail(&mut self, key: &str, units: u32) -> Duration {
        self.gmail_pace = Some(key.to_string());
        pace_gmail(key, units, self.urgent)
    }
}

/// [`Client::http_write`] over any [`Wire`].
fn throttled_write<W: Wire>(wire: &mut W, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
    let exchange = wire.exchange();
    if exchange.answering || exchange.url != url {
        exchange.url.clear();
        exchange.url.push_str(url);
        exchange.request.clear();
        exchange.answering = false;
    }
    exchange.request.extend_from_slice(bytes);
    wire.wire_write(url, bytes)
}

/// [`Client::http_read`] over any [`Wire`].
fn throttled_read<W: Wire>(wire: &mut W, url: &str) -> Result<Vec<u8>, BridgeError> {
    let exchange = wire.exchange();
    let fresh = !exchange.answering
        && exchange.url == url
        && !exchange.request.is_empty()
        && !exchange.request.starts_with(b"HEAD ");
    if !fresh {
        return wire.wire_read(url);
    }
    exchange.answering = true;

    let mut retries = 0;
    loop {
        let refusal = match read_answer(wire, url)? {
            Answered::Passed(bytes) => return Ok(bytes),
            Answered::Throttled(refusal) => refusal,
        };

        // NOTE: the account's other workers would draw the same refusal,
        // so its pacing holds them a minute too.
        if refusal.throttle == Throttle::Minute
            && let Some(key) = wire.paced()
        {
            gmail_quota_refused(&key);
        }

        let (delay, budget) = match (refusal.retry_after, refusal.throttle) {
            (Some(delay), Throttle::Minute) => (delay, QUOTA_WAIT_BUDGET),
            (Some(delay), Throttle::Burst) => (delay, WAIT_BUDGET),
            (None, Throttle::Minute) => {
                let jitter = MINUTE_JITTER.mul_f64(rand::random_range(0.0..=1.0));
                (until_next_minute(wire.now()) + jitter, QUOTA_WAIT_BUDGET)
            }
            (None, Throttle::Burst) => {
                (backoff(retries, rand::random_range(0.5..=1.0)), WAIT_BUDGET)
            }
        };
        let left = budget.saturating_sub(*wire.waited());
        if retries >= MAX_RETRIES || delay > left {
            log::warn!(
                "{} still throttled ({} {}), giving up after {retries} retries",
                short(url),
                refusal.status,
                refusal.reason
            );
            return Ok(refusal.bytes);
        }

        log::debug!(
            "{} throttled ({} {}), {:?} wait, retry {} in {} ms",
            short(url),
            refusal.status,
            refusal.reason,
            refusal.throttle,
            retries + 1,
            delay.as_millis()
        );
        wire.sleep(delay);
        *wire.waited() += delay;
        retries += 1;

        let request = std::mem::take(&mut wire.exchange().request);
        let written = wire.wire_write(url, &request);
        wire.exchange().request = request;
        written?;
    }
}

/// Reads one answer's head, and its body when the head says it may
/// be throttled, deciding whether it is.
fn read_answer<W: Wire>(wire: &mut W, url: &str) -> Result<Answered, BridgeError> {
    let mut bytes = Vec::new();

    let head_end = loop {
        if let Some(end) = head_end(&bytes) {
            break end;
        }
        if bytes.len() > HEAD_CAP {
            return Ok(Answered::Passed(bytes));
        }
        let chunk = wire.wire_read(url)?;
        if chunk.is_empty() {
            return Ok(Answered::Passed(bytes));
        }
        bytes.extend_from_slice(&chunk);
    };

    let Some(head) = Head::parse(&bytes[..head_end]) else {
        return Ok(Answered::Passed(bytes));
    };
    if !matches!(head.status, 403 | 429 | 503) || head.close {
        return Ok(Answered::Passed(bytes));
    }

    let body = loop {
        match head.framing.body(&bytes[head_end..]) {
            Body::Complete(body) => break body,
            Body::Unframed => return Ok(Answered::Passed(bytes)),
            Body::Partial if bytes.len() - head_end > BODY_CAP => {
                return Ok(Answered::Passed(bytes));
            }
            Body::Partial => {
                let chunk = wire.wire_read(url)?;
                if chunk.is_empty() {
                    return Ok(Answered::Passed(bytes));
                }
                bytes.extend_from_slice(&chunk);
            }
        }
    };

    let Some(throttle) = throttled(head.status, &body) else {
        return Ok(Answered::Passed(bytes));
    };

    let now = wire.now();
    let retry_after = head
        .retry_after
        .as_deref()
        .and_then(|value| retry_after(value, now))
        .or_else(|| retry_after_named(&body, now));
    Ok(Answered::Throttled(Refusal {
        status: head.status,
        reason: reason(head.status, &body),
        bytes,
        retry_after,
        throttle,
    }))
}

/// Holds one Gmail request of the account `key` names, costing `units`
/// quota units, until both its slot under [`GMAIL_UNITS_PER_SECOND`] and
/// room in the last minute's [`GMAIL_UNITS_PER_MINUTE`] come, and
/// answers how long it waited. An `urgent` request takes no slot behind
/// the others, though they wait for it, and draws on the fifth of the
/// minute's quota the background leaves.
///
/// A batch is one request but costs the units of every call it carries,
/// so it is paced once with their sum.
pub(crate) fn pace_gmail(key: &str, units: u32, urgent: bool) -> Duration {
    let wait = {
        let mut paces = GMAIL_PACES.lock().unwrap_or_else(|err| err.into_inner());
        let now = Instant::now();
        paces.retain(|_, pace| now.saturating_duration_since(pace.used) < GMAIL_PACE_IDLE);
        paces
            .entry(key.to_string())
            .or_insert_with(GmailPace::new)
            .book(now, units, urgent)
    };

    if !wait.is_zero() {
        log::debug!(
            "gmail paced {} ms for {units} units{}",
            wait.as_millis(),
            if urgent { ", urgent" } else { "" }
        );
        std::thread::sleep(wait);
    }
    wait
}

/// The gate an account's metadata batches go through one at a time:
/// each call of a batch runs side by side on Gmail's side, and two
/// batches at once draw its per-user concurrency 429s.
pub(crate) fn gmail_gate(key: &str) -> Arc<Mutex<()>> {
    let mut paces = GMAIL_PACES.lock().unwrap_or_else(|err| err.into_inner());
    let pace = paces.entry(key.to_string()).or_insert_with(GmailPace::new);
    pace.used = Instant::now();
    Arc::clone(&pace.gate)
}

/// Spends what is left of the minute's Gmail budget of the account `key`
/// names: Gmail just refused a per-minute quota, so every worker holds
/// off a minute rather than drawing the same refusal.
fn gmail_quota_refused(key: &str) {
    let mut paces = GMAIL_PACES.lock().unwrap_or_else(|err| err.into_inner());
    if let Some(pace) = paces.get_mut(key) {
        let now = Instant::now();
        pace.minute.exhaust(now, GMAIL_UNITS_PER_MINUTE);
        pace.urgent.exhaust(now, GMAIL_URGENT_UNITS_PER_MINUTE);
    }
}

/// One account's Gmail pacing: the next per-second slot, the minute's
/// units, background and urgent, and the gate of its batches.
struct GmailPace {
    next: Option<Instant>,
    minute: MinuteBudget,
    urgent: MinuteBudget,
    gate: Arc<Mutex<()>>,
    used: Instant,
}

impl GmailPace {
    fn new() -> Self {
        Self {
            next: None,
            minute: MinuteBudget::new(),
            urgent: MinuteBudget::new(),
            gate: Arc::default(),
            used: Instant::now(),
        }
    }

    /// Books one request of `units` at `now`, answering how long it waits.
    fn book(&mut self, now: Instant, units: u32, urgent: bool) -> Duration {
        self.used = now;
        let (slot, after) = pace_slot(
            self.next,
            now,
            units,
            GMAIL_UNITS_PER_SECOND,
            GMAIL_BURST_UNITS,
        );
        self.next = Some(after);
        if urgent {
            return self
                .urgent
                .reserve(now, units, GMAIL_URGENT_UNITS_PER_MINUTE);
        }
        slot + self
            .minute
            .reserve(now + slot, units, GMAIL_UNITS_PER_MINUTE)
    }
}

/// The quota units spent over the last minute, as a sliding window, so
/// no sixty seconds ever carry more than the budget, whichever minute
/// the server counts by.
#[derive(Debug)]
struct MinuteBudget {
    /// What was spent and when, oldest first, each instant at or after
    /// the one before it.
    spent: VecDeque<(Instant, u32)>,
    /// The sum of `spent`'s units.
    total: u32,
}

impl MinuteBudget {
    const WINDOW: Duration = Duration::from_secs(60);

    const fn new() -> Self {
        Self {
            spent: VecDeque::new(),
            total: 0,
        }
    }

    /// Books `units` at the earliest instant from `now` at which the
    /// last sixty seconds hold room for them under `limit`, and returns
    /// how long from `now` that is. Spends are booked in order, never
    /// one before the one booked ahead of it. A spend larger than the
    /// whole budget counts as the whole budget, waiting for an empty
    /// minute rather than forever.
    fn reserve(&mut self, now: Instant, units: u32, limit: u32) -> Duration {
        let units = units.min(limit);
        let mut at = self.spent.back().map_or(now, |(last, _)| now.max(*last));

        while let Some(&(oldest, spent)) = self.spent.front() {
            let aged = oldest + Self::WINDOW <= at;
            if !aged && self.total + units <= limit {
                break;
            }
            at = at.max(oldest + Self::WINDOW);
            self.spent.pop_front();
            self.total -= spent;
        }

        self.spent.push_back((at, units));
        self.total += units;
        at.saturating_duration_since(now)
    }

    /// Fills the budget at `now`, so the next spend waits sixty seconds
    /// from it.
    fn exhaust(&mut self, now: Instant, limit: u32) {
        let at = self.spent.back().map_or(now, |(last, _)| now.max(*last));
        self.spent.clear();
        self.spent.push_back((at, limit));
        self.total = limit;
    }
}

/// One step of a paced stream of costs (the generic cell rate algorithm
/// over quota units): given the next free slot and now, how long a
/// request of `units` waits and the slot after it, `rate` units a
/// second. Up to `burst` units more than the request's own go out at
/// once after a pause.
fn pace_slot(
    next: Option<Instant>,
    now: Instant,
    units: u32,
    rate: u32,
    burst: u32,
) -> (Duration, Instant) {
    let per_unit = Duration::from_secs(1) / rate;
    let earliest = now.checked_sub(per_unit * burst).unwrap_or(now);
    let slot = next.map_or(now, |next| next.max(earliest));

    (slot.saturating_duration_since(now), slot + per_unit * units)
}

/// How a throttled answer is waited out when it names no `Retry-After`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Throttle {
    /// A rate or concurrency limit: a short exponential back-off.
    Burst,
    /// A per-minute quota the answer names: until the next minute.
    Minute,
}

/// What the first read of an answer found.
enum Answered {
    /// Anything but a throttled answer: handed to the coroutine as is.
    Passed(Vec<u8>),
    /// A throttled answer, read to its end.
    Throttled(Refusal),
}

/// A throttled answer: its bytes, its status and reason for the log,
/// and the wait it asked for.
struct Refusal {
    status: u16,
    reason: String,
    bytes: Vec<u8>,
    retry_after: Option<Duration>,
    throttle: Throttle,
}

/// Whether a complete answer says the request was throttled, and how it
/// is waited out: a 503, a 429, or one of Google's rate-limit 403s (a
/// 403 for anything else, and any answer naming a daily quota, is no
/// reason to wait). Only an answer naming a per-minute quota waits for
/// the next minute: a bare 429, Gmail's concurrency one
/// (`Too many concurrent requests for user`) and its per-second one
/// (`User-rate limit exceeded`) included, is gone within seconds.
fn throttled(status: u16, body: &[u8]) -> Option<Throttle> {
    match status {
        503 => Some(Throttle::Burst),
        429 | 403 if names_daily_quota(body) => None,
        429 | 403 if names_minute_quota(body) => Some(Throttle::Minute),
        429 => Some(Throttle::Burst),
        403 if GmailApiError::parse(status, body).is_rate_limited()
            || contains(body, b"rateLimitExceeded")
            || contains(body, b"userRateLimitExceeded") =>
        {
            Some(Throttle::Burst)
        }
        _ => None,
    }
}

/// Whether a body names one of Google's per-minute quotas, as Gmail's
/// `Quota exceeded for quota metric 'Total Query Cost' and limit 'Units
/// per minute per user'` does, in its message or in its `ErrorInfo`
/// metadata (`…PerMinutePerUser`); the older APIs' hundred seconds
/// count alike.
fn names_minute_quota(body: &[u8]) -> bool {
    contains(body, b"per minute")
        || contains(body, b"PerMinute")
        || contains(body, b"per 100 seconds")
        || contains(body, b"Per100Seconds")
}

/// Whether a body names a daily quota, which no wait within a sync
/// brings back.
fn names_daily_quota(body: &[u8]) -> bool {
    contains(body, b"dailyLimitExceeded") || contains(body, b"per day") || contains(body, b"PerDay")
}

/// The reason a throttled answer gives, for the log: Google's reasons
/// and message, or the start of any other body.
fn reason(status: u16, body: &[u8]) -> String {
    let error = GmailApiError::parse(status, body);
    let mut reason = error.reasons.join(",");
    if !error.message.is_empty() {
        if !reason.is_empty() {
            reason.push_str(": ");
        }
        reason.push_str(error.message.trim());
    }
    if reason.len() > REASON_CAP {
        let mut end = REASON_CAP;
        while !reason.is_char_boundary(end) {
            end -= 1;
        }
        reason.truncate(end);
        reason.push('…');
    }
    reason
}

/// A URL without its query, for the log.
fn short(url: &str) -> &str {
    url.split('?').next().unwrap_or(url)
}

/// The wait from `now` to the start of the next minute, at least a
/// second, so a refusal in a minute's last instant still pauses.
fn until_next_minute(now: Timestamp) -> Duration {
    let millis = now.as_millisecond().rem_euclid(60_000) as u64;
    Duration::from_millis(60_000 - millis).max(Duration::from_secs(1))
}

/// The wait before retry `retries` (0-based) with no `Retry-After`:
/// exponential from [`BACKOFF_BASE`], capped at [`BACKOFF_MAX`], scaled
/// by `jitter` in `0.5..=1.0`.
fn backoff(retries: u32, jitter: f64) -> Duration {
    let full = BACKOFF_BASE
        .saturating_mul(1 << retries.min(16))
        .min(BACKOFF_MAX);
    full.mul_f64(jitter)
}

/// A `Retry-After` value as a wait from `now` (RFC 9110 section 10.2.3):
/// delay seconds, or an HTTP date, a date already past waiting nothing.
fn retry_after(value: &str, now: Timestamp) -> Option<Duration> {
    let value = value.trim();
    if let Ok(seconds) = value.parse::<u64>() {
        return Some(Duration::from_secs(seconds));
    }

    static PARSER: DateTimeParser = DateTimeParser::new();
    let at = PARSER.parse_timestamp(value).ok()?;
    Some(until(at, now))
}

/// The wait a Google error message names, as Gmail's per-user rate limit
/// does (`User-rate limit exceeded.  Retry after 2026-10-10T20:21:41.123Z`):
/// an RFC 3339 instant after `Retry after`, a past one waiting nothing.
fn retry_after_named(body: &[u8], now: Timestamp) -> Option<Duration> {
    const NEEDLE: &[u8] = b"Retry after ";
    let at = body
        .windows(NEEDLE.len())
        .position(|window| window == NEEDLE)?;
    let rest = &body[at + NEEDLE.len()..];
    let end = rest
        .iter()
        .position(|byte| !(byte.is_ascii_alphanumeric() || b":.-+".contains(byte)))
        .unwrap_or(rest.len());
    let named = std::str::from_utf8(&rest[..end]).ok()?;
    let instant: Timestamp = named.trim_end_matches('.').parse().ok()?;
    Some(until(instant, now))
}

/// The wait from `now` to `at`, nothing for an instant already past.
fn until(at: Timestamp, now: Timestamp) -> Duration {
    let millis = at.as_millisecond().saturating_sub(now.as_millisecond());
    Duration::from_millis(millis.max(0) as u64)
}

/// Where the head of an answer ends, past its blank line.
fn head_end(bytes: &[u8]) -> Option<usize> {
    bytes
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map(|at| at + 4)
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

/// The parts of an answer's head throttling reads.
struct Head {
    status: u16,
    framing: Framing,
    close: bool,
    retry_after: Option<String>,
}

impl Head {
    fn parse(head: &[u8]) -> Option<Self> {
        let head = std::str::from_utf8(head).ok()?;
        let mut lines = head.split("\r\n");

        let status = lines.next()?;
        let mut parts = status.split(' ');
        if !parts.next()?.starts_with("HTTP/1.") {
            return None;
        }
        let status = parts.next()?.parse().ok()?;

        let mut framing = Framing::None;
        let mut close = false;
        let mut retry_after = None;
        for line in lines {
            let Some((name, value)) = line.split_once(':') else {
                continue;
            };
            let value = value.trim();
            if name.eq_ignore_ascii_case("transfer-encoding") {
                if value.to_ascii_lowercase().contains("chunked") {
                    framing = Framing::Chunked;
                }
            } else if name.eq_ignore_ascii_case("content-length") {
                if !matches!(framing, Framing::Chunked) {
                    framing = Framing::Length(value.parse().ok()?);
                }
            } else if name.eq_ignore_ascii_case("connection") {
                close |= value.to_ascii_lowercase().contains("close");
            } else if name.eq_ignore_ascii_case("retry-after") {
                retry_after = Some(value.to_string());
            }
        }

        Some(Self {
            status,
            framing,
            close,
            retry_after,
        })
    }
}

/// How an answer's body ends.
enum Framing {
    /// `Content-Length` octets.
    Length(usize),
    /// Chunked transfer coding, ending at its zero chunk.
    Chunked,
    /// Neither: the body ends when the server closes, which cannot be
    /// told apart from a stall without closing too.
    None,
}

/// Whether the bytes after a head hold the whole body.
enum Body {
    /// The body, decoded.
    Complete(Vec<u8>),
    /// More to read.
    Partial,
    /// No framing to tell its end by, or framing that does not parse.
    Unframed,
}

impl Framing {
    fn body(&self, bytes: &[u8]) -> Body {
        match self {
            Self::Length(length) if bytes.len() >= *length => {
                Body::Complete(bytes[..*length].to_vec())
            }
            Self::Length(_) => Body::Partial,
            Self::Chunked => dechunk(bytes),
            Self::None => Body::Unframed,
        }
    }
}

/// Decodes a chunked body (RFC 9112 section 7.1), extensions and
/// trailers skipped.
fn dechunk(bytes: &[u8]) -> Body {
    let mut body = Vec::new();
    let mut at = 0;

    loop {
        let Some(line) = line_end(&bytes[at..]) else {
            return Body::Partial;
        };
        let size = &bytes[at..at + line];
        let size = size.split(|byte| *byte == b';').next().unwrap_or_default();
        let Some(size) = std::str::from_utf8(size)
            .ok()
            .and_then(|size| usize::from_str_radix(size.trim(), 16).ok())
        else {
            return Body::Unframed;
        };
        at += line + 2;

        if size == 0 {
            // NOTE: trailers until the blank line that ends the body.
            loop {
                let Some(line) = line_end(&bytes[at..]) else {
                    return Body::Partial;
                };
                at += line + 2;
                if line == 0 {
                    return Body::Complete(body);
                }
            }
        }

        if bytes.len() < at + size + 2 {
            return Body::Partial;
        }
        body.extend_from_slice(&bytes[at..at + size]);
        at += size + 2;
    }
}

/// Where the line at the start of `bytes` ends, before its CRLF.
fn line_end(bytes: &[u8]) -> Option<usize> {
    bytes.windows(2).position(|window| window == b"\r\n")
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use jiff::Timestamp;

    use super::*;

    fn head(text: &str) -> Head {
        Head::parse(text.as_bytes()).unwrap()
    }

    #[test]
    fn a_throttled_head_names_its_status_framing_and_wait() {
        let parsed =
            head("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 7\r\ncontent-length: 12\r\n\r\n");
        assert_eq!(parsed.status, 429);
        assert!(matches!(parsed.framing, Framing::Length(12)));
        assert!(!parsed.close);
        assert_eq!(parsed.retry_after.as_deref(), Some("7"));

        let chunked = head(
            "HTTP/1.1 503 Service Unavailable\r\nTransfer-Encoding: chunked\r\nContent-Length: 3\r\nConnection: close\r\n\r\n",
        );
        assert!(matches!(chunked.framing, Framing::Chunked));
        assert!(chunked.close);

        assert!(Head::parse(b"* OK IMAP ready\r\n\r\n").is_none());
    }

    #[test]
    fn a_body_is_complete_only_once_its_framing_says_so() {
        assert!(matches!(Framing::Length(4).body(b"ab"), Body::Partial));
        assert!(matches!(Framing::Length(2).body(b"ab"), Body::Complete(body) if body == b"ab"));
        assert!(matches!(Framing::None.body(b"ab"), Body::Unframed));

        assert!(matches!(dechunk(b"5\r\nhel"), Body::Partial));
        assert!(matches!(dechunk(b"5\r\nhello\r\n0\r\n"), Body::Partial));
        assert!(matches!(
            dechunk(b"5;x=y\r\nhello\r\n6\r\n world\r\n0\r\nX-T: 1\r\n\r\n"),
            Body::Complete(body) if body == b"hello world"
        ));
        assert!(matches!(dechunk(b"zz\r\n"), Body::Unframed));
    }

    /// The answer Gmail gave on 2026-10-07, its project number made up.
    const GMAIL_MINUTE_QUOTA: &[u8] = br#"{"error":{"code":403,"message":"Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user' of service 'gmail.googleapis.com' for consumer 'project_number:123'.","errors":[{"message":"Quota exceeded","domain":"usageLimits","reason":"rateLimitExceeded"}],"status":"PERMISSION_DENIED"}}"#;

    /// Gmail's per-user concurrency limit, as it answers a request (or a
    /// batch's inner call) sent while too many others run.
    const GMAIL_CONCURRENCY: &[u8] = br#"{"error":{"code":429,"message":"Too many concurrent requests for user","errors":[{"message":"Too many concurrent requests for user","domain":"global","reason":"rateLimitExceeded"}],"status":"RESOURCE_EXHAUSTED"}}"#;

    /// Gmail's per-user rate limit, naming when to come back.
    const GMAIL_USER_RATE: &[u8] = br#"{"error":{"code":429,"message":"User-rate limit exceeded.  Retry after 2026-10-10T20:21:41.500Z","errors":[{"message":"User-rate limit exceeded.  Retry after 2026-10-10T20:21:41.500Z","domain":"usageLimits","reason":"rateLimitExceeded"}],"status":"RESOURCE_EXHAUSTED"}}"#;

    #[test]
    fn rate_and_concurrency_limits_back_off_briefly() {
        assert_eq!(throttled(429, b""), Some(Throttle::Burst));
        assert_eq!(throttled(503, b""), Some(Throttle::Burst));
        assert_eq!(throttled(429, GMAIL_CONCURRENCY), Some(Throttle::Burst));
        assert_eq!(throttled(429, GMAIL_USER_RATE), Some(Throttle::Burst));
        assert_eq!(
            throttled(429, br#"{"status":"RESOURCE_EXHAUSTED"}"#),
            Some(Throttle::Burst)
        );
        assert_eq!(
            throttled(
                403,
                br#"{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"#
            ),
            Some(Throttle::Burst)
        );
        assert_eq!(
            throttled(403, br#"{"reason":"rateLimitExceeded"}"#),
            Some(Throttle::Burst)
        );
        // NOTE: Google's envelope read as reasons: a `RESOURCE_EXHAUSTED`
        // 403 whose message names no quota at all.
        assert_eq!(
            throttled(
                403,
                br#"{"error":{"code":403,"message":"Too many","errors":[{"reason":"quotaExceeded"}],"status":"RESOURCE_EXHAUSTED"}}"#
            ),
            Some(Throttle::Burst)
        );
    }

    #[test]
    fn only_an_answer_naming_a_minute_quota_waits_the_minute() {
        assert_eq!(throttled(403, GMAIL_MINUTE_QUOTA), Some(Throttle::Minute));
        assert_eq!(
            throttled(
                429,
                b"Quota exceeded for quota metric 'Queries' and limit 'Queries per minute per user'"
            ),
            Some(Throttle::Minute)
        );
        assert_eq!(
            throttled(
                429,
                br#"{"error":{"code":429,"details":[{"reason":"RATE_LIMIT_EXCEEDED","metadata":{"quota_limit":"ReadRequestsPerMinutePerUser"}}]}}"#
            ),
            Some(Throttle::Minute)
        );
    }

    #[test]
    fn a_daily_quota_or_another_403_is_no_reason_to_wait() {
        assert_eq!(throttled(403, br#"{"reason":"dailyLimitExceeded"}"#), None);
        assert_eq!(
            throttled(
                403,
                b"Quota exceeded for quota metric 'Queries' and limit 'Queries per day'"
            ),
            None
        );
        assert_eq!(throttled(429, b"limit 'Queries per day'"), None);
        assert_eq!(throttled(403, b"Forbidden"), None);
        assert_eq!(
            throttled(
                403,
                br#"{"error":{"code":403,"message":"No","errors":[{"reason":"insufficientPermissions"}]}}"#
            ),
            None
        );
        assert_eq!(throttled(500, b""), None);
    }

    #[test]
    fn a_refusal_is_logged_with_its_reasons_and_message() {
        assert_eq!(
            reason(429, GMAIL_CONCURRENCY),
            "rateLimitExceeded: Too many concurrent requests for user"
        );
        let long = reason(403, &[b'x'; 400]);
        assert!(long.len() <= REASON_CAP + '…'.len_utf8(), "{long}");
        assert_eq!(
            short("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=after"),
            "https://gmail.googleapis.com/gmail/v1/users/me/messages"
        );
    }

    #[test]
    fn the_next_minute_is_waited_at_least_a_second() {
        let at = |text: &str| text.parse::<Timestamp>().unwrap();
        assert_eq!(
            until_next_minute(at("2026-10-07T08:49:30Z")),
            Duration::from_secs(30)
        );
        assert_eq!(
            until_next_minute(at("2026-10-07T08:49:00Z")),
            Duration::from_secs(60)
        );
        assert_eq!(
            until_next_minute(at("2026-10-07T08:49:59.750Z")),
            Duration::from_secs(1)
        );
    }

    /// A server answering from a script, on a clock that never sleeps.
    struct Scripted {
        answers: VecDeque<Vec<u8>>,
        writes: Vec<Vec<u8>>,
        exchange: Exchange,
        waited: Duration,
        slept: Vec<Duration>,
        now: Timestamp,
    }

    impl Scripted {
        fn new(answers: &[&[u8]], now: &str) -> Self {
            Self {
                answers: answers.iter().map(|answer| answer.to_vec()).collect(),
                writes: Vec::new(),
                exchange: Exchange::default(),
                waited: Duration::ZERO,
                slept: Vec::new(),
                now: now.parse().unwrap(),
            }
        }
    }

    impl Wire for Scripted {
        fn wire_read(&mut self, _url: &str) -> Result<Vec<u8>, BridgeError> {
            Ok(self.answers.pop_front().unwrap_or_default())
        }

        fn wire_write(&mut self, _url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
            self.writes.push(bytes.to_vec());
            Ok(())
        }

        fn exchange(&mut self) -> &mut Exchange {
            &mut self.exchange
        }

        fn waited(&mut self) -> &mut Duration {
            &mut self.waited
        }

        fn sleep(&mut self, delay: Duration) {
            self.slept.push(delay);
            self.now += delay;
        }

        fn now(&self) -> Timestamp {
            self.now
        }
    }

    fn answer(status: &str, body: &[u8]) -> Vec<u8> {
        let mut bytes = format!(
            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n",
            body.len()
        )
        .into_bytes();
        bytes.extend_from_slice(body);
        bytes
    }

    // NOTE: a scripted wire paces no account, so these tests leave the
    // process-wide pacing alone; a refusal reads the same on any API.
    const URL: &str = "https://gmail.googleapis.com/gmail/v1/users/me/messages";
    const REQUEST: &[u8] =
        b"GET /gmail/v1/users/me/messages HTTP/1.1\r\nHost: gmail.googleapis.com\r\n\r\n";

    #[test]
    fn a_minute_quota_refusal_waits_for_the_next_minute_then_succeeds() {
        let refused = answer("403 Forbidden", GMAIL_MINUTE_QUOTA);
        let served = answer("200 OK", b"{}");
        let mut wire = Scripted::new(&[&refused, &served], "2026-10-07T08:49:20Z");

        throttled_write(&mut wire, URL, REQUEST).unwrap();
        let read = throttled_read(&mut wire, URL).unwrap();

        assert_eq!(read, served);
        assert_eq!(wire.writes, [REQUEST, REQUEST]);
        assert_eq!(wire.slept.len(), 1);
        let slept = wire.slept[0];
        assert!(slept >= Duration::from_secs(40), "{slept:?}");
        assert!(
            slept <= Duration::from_secs(40) + MINUTE_JITTER,
            "{slept:?}"
        );
    }

    #[test]
    fn a_minute_quota_refusal_past_its_budget_is_the_answer() {
        let refused = answer("403 Forbidden", GMAIL_MINUTE_QUOTA);
        let mut wire = Scripted::new(&[&refused, &refused, &refused], "2026-10-07T08:49:20Z");

        throttled_write(&mut wire, URL, REQUEST).unwrap();
        let read = throttled_read(&mut wire, URL).unwrap();

        // One wait to the next minute fits the budget, a second whole
        // minute does not: the refusal reaches the coroutine.
        assert_eq!(read, refused);
        assert_eq!(wire.slept.len(), 1);
        assert!(wire.waited <= QUOTA_WAIT_BUDGET);
    }

    #[test]
    fn a_concurrency_refusal_is_retried_within_a_second() {
        let refused = answer("429 Too Many Requests", GMAIL_CONCURRENCY);
        let served = answer("200 OK", b"{}");
        let mut wire = Scripted::new(&[&refused, &refused, &served], "2026-10-07T08:49:20Z");

        throttled_write(&mut wire, URL, REQUEST).unwrap();
        assert_eq!(throttled_read(&mut wire, URL).unwrap(), served);

        // Two short back-offs, the second at most two seconds: never the
        // forty seconds to the next minute.
        assert_eq!(wire.slept.len(), 2);
        assert!(wire.slept[0] <= Duration::from_secs(1), "{:?}", wire.slept);
        assert!(wire.slept[1] <= Duration::from_secs(2), "{:?}", wire.slept);
    }

    #[test]
    fn a_user_rate_refusal_waits_until_the_instant_it_names() {
        let refused = answer("429 Too Many Requests", GMAIL_USER_RATE);
        let served = answer("200 OK", b"{}");
        let mut wire = Scripted::new(&[&refused, &served], "2026-10-10T20:21:40Z");

        throttled_write(&mut wire, URL, REQUEST).unwrap();
        assert_eq!(throttled_read(&mut wire, URL).unwrap(), served);
        assert_eq!(wire.slept, [Duration::from_millis(1500)]);
    }

    #[test]
    fn a_retry_after_on_a_429_is_taken_as_named() {
        let refused =
            b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 3\r\nContent-Length: 2\r\n\r\n{}";
        let served = answer("200 OK", b"{}");
        let mut wire = Scripted::new(&[refused, &served], "2026-10-07T08:49:20Z");

        throttled_write(&mut wire, URL, REQUEST).unwrap();
        assert_eq!(throttled_read(&mut wire, URL).unwrap(), served);
        assert_eq!(wire.slept, [Duration::from_secs(3)]);
    }

    #[test]
    fn retry_after_reads_seconds_dates_and_named_instants() {
        let now: Timestamp = "2026-10-07T08:49:30Z".parse().unwrap();
        assert_eq!(retry_after(" 120 ", now), Some(Duration::from_secs(120)));
        assert_eq!(
            retry_after("Wed, 07 Oct 2026 08:49:37 GMT", now),
            Some(Duration::from_secs(7))
        );
        assert_eq!(
            retry_after("Wed, 07 Oct 2026 08:00:00 GMT", now),
            Some(Duration::ZERO)
        );
        assert_eq!(retry_after("soon", now), None);

        assert_eq!(
            retry_after_named(b"\"Retry after 2026-10-07T08:49:32.250Z.\"", now),
            Some(Duration::from_millis(2250))
        );
        assert_eq!(
            retry_after_named(b"Retry after 2026-10-07T08:00:00Z", now),
            Some(Duration::ZERO)
        );
        assert_eq!(retry_after_named(GMAIL_CONCURRENCY, now), None);
        assert_eq!(retry_after_named(b"Retry after lunch", now), None);
    }

    #[test]
    fn backoff_doubles_up_to_its_cap_and_jitters_down() {
        assert_eq!(backoff(0, 1.0), Duration::from_secs(1));
        assert_eq!(backoff(3, 1.0), Duration::from_secs(8));
        assert_eq!(backoff(9, 1.0), Duration::from_secs(16));
        assert_eq!(backoff(2, 0.5), Duration::from_secs(2));

        // The worst case fits the budget, so a server that never names
        // a wait still gets every retry.
        let worst: Duration = (0..MAX_RETRIES).map(|n| backoff(n, 1.0)).sum();
        assert!(worst <= WAIT_BUDGET);
    }

    #[test]
    fn the_minute_budget_caps_a_long_pass() {
        let start = Instant::now();
        let limit = GMAIL_UNITS_PER_MINUTE;
        let mut budget = MinuteBudget::new();
        let mut next = None;
        let mut booked = Vec::new();

        // Three minutes of batches at the paced rate, a send of a hundred
        // units every tenth request, the clock moving with each wait as
        // a worker's would.
        let mut now = start;
        while now < start + Duration::from_secs(180) {
            let units = if booked.len() % 10 == 9 { 100 } else { 125 };
            let (slot, after) =
                pace_slot(next, now, units, GMAIL_UNITS_PER_SECOND, GMAIL_BURST_UNITS);
            next = Some(after);
            let wait = budget.reserve(now + slot, units, limit);
            now = now + slot + wait;
            booked.push((now, units));
        }

        // No sixty seconds ever carry more than the budget.
        for (at, (first, _)) in booked.iter().enumerate() {
            let within: u32 = booked[at..]
                .iter()
                .take_while(|(later, _)| *later < *first + Duration::from_secs(60))
                .map(|(_, units)| units)
                .sum();
            assert!(within <= limit, "{within} units within a minute");
        }

        // Past a full minute, a send's hundred units waits for room
        // rather than going out.
        let mut full = MinuteBudget::new();
        for _ in 0..limit / 5 {
            assert_eq!(full.reserve(start, 5, limit), Duration::ZERO);
        }
        assert_eq!(full.reserve(start, 100, limit), Duration::from_secs(60));
    }

    #[test]
    fn a_quota_refusal_holds_the_budget_a_minute() {
        let start = Instant::now();
        let mut budget = MinuteBudget::new();
        assert_eq!(budget.reserve(start, 5, 100), Duration::ZERO);

        let refused = start + Duration::from_secs(10);
        budget.exhaust(refused, 100);
        assert_eq!(budget.reserve(refused, 5, 100), Duration::from_secs(60));

        // More than the whole budget waits for an empty minute, not forever.
        let mut empty = MinuteBudget::new();
        assert_eq!(empty.reserve(start, 500, 100), Duration::ZERO);
        assert_eq!(empty.reserve(start, 1, 100), Duration::from_secs(60));
    }

    #[test]
    fn gmail_pacing_holds_quota_units_per_second() {
        let start = Instant::now();
        let mut pace = GmailPace::new();

        // A batch of 25 metadata reads (125 units) goes at once, the next
        // one waits for its units to be paid at the rate, and two batches
        // never go out in the same second's worth of units.
        assert_eq!(pace.book(start, 125, false), Duration::ZERO);
        let second = pace.book(start, 125, false);
        let per_unit = Duration::from_secs(1) / GMAIL_UNITS_PER_SECOND;
        assert_eq!(second, per_unit * 125);
        let third = pace.book(start, 125, false);
        assert_eq!(third, second + per_unit * 125);

        // After a pause, a few small calls go out at once, then the rate.
        let later = start + Duration::from_secs(10);
        let mut free = 0;
        for _ in 0..20 {
            free += u32::from(pace.book(later, 5, false).is_zero());
        }
        assert_eq!(free, GMAIL_BURST_UNITS / 5 + 1);
    }

    #[test]
    fn an_urgent_request_skips_the_queue_and_the_spent_minute() {
        let start = Instant::now();
        let mut pace = GmailPace::new();

        // The background spent its whole minute and queued behind it.
        for _ in 0..GMAIL_UNITS_PER_MINUTE / 125 {
            pace.book(start, 125, false);
        }
        assert!(pace.book(start, 125, false) >= Duration::from_secs(30));

        // A message opened now goes at once, and the background yields
        // its units to it.
        let before = pace.next.unwrap();
        assert_eq!(pace.book(start, 5, true), Duration::ZERO);
        assert!(pace.next.unwrap() > before);
    }

    #[test]
    fn accounts_are_paced_apart() {
        let first = "test-pacing-first";
        let second = "test-pacing-second";
        // NOTE: a large spend fills an account's minute at once; the
        // other account's next request does not wait for it.
        assert_eq!(
            pace_gmail(first, GMAIL_UNITS_PER_MINUTE, false),
            Duration::ZERO
        );
        assert_eq!(pace_gmail(second, 5, false), Duration::ZERO);
        assert!(Arc::ptr_eq(&gmail_gate(first), &gmail_gate(first)));
        assert!(!Arc::ptr_eq(&gmail_gate(first), &gmail_gate(second)));
    }
}
