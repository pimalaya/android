//! # Throttling
//!
//! Retries throttled HTTP answers and paces Gmail, in one place for every
//! HTTP backend.
//!
//! A 429, a 503 or a Google rate-limit 403 means the request was not
//! served: it goes again after the `Retry-After` (or Gmail's `Retry after`
//! instant), else a jittered back-off. A per-minute quota waits for the
//! next minute, except on Gmail, whose quota refills by the second.
//!
//! Gmail is also paced per account below the project's quota
//! ([`pace_gmail`], [`quota`]), every worker sharing it, so a long pass
//! waits for its quota rather than drawing refusals. A request someone
//! waits on skips the queue the background work forms.
//!
//! It sits at the transport because a coroutine cannot be rewound and the
//! runners number in dozens, but a request is fixed bytes:
//! [`Client::http_write`] records them, and [`Client::http_read`] reads a
//! throttled answer to its end and writes them again.
//!
//! Bounded so a sync never hangs ([`MAX_RETRIES`] per request,
//! [`WAIT_BUDGET`] per native call): past it, the coroutine gets the
//! throttled answer. An answer whose end is uncertain is never retried,
//! as writing on a connection the server still talks on would desync it.
//!
//! An answer is settled ([`Host::settle`]) once read to its end, and an
//! unsettled connection dropped before the next request ([`Host::begin`]),
//! so no answer reads as another's. An idempotent request a reused
//! connection dropped unanswered goes again once (RFC 9112 section 9.3.1).

use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, LazyLock, Mutex},
    time::{Duration, Instant},
};

use io_gmail::v1::send::GmailApiError;
use jiff::{Timestamp, fmt::rfc2822::DateTimeParser};

use crate::{
    client::{Client, Host},
    error::BridgeError,
    mail::gmail::{minute_pause, quota},
};

/// How many times one request is sent again after a throttled answer.
const MAX_RETRIES: u32 = 4;

/// How long one native call waits on throttling servers in total.
///
/// Past it, the throttled answer is the call's answer.
pub const WAIT_BUDGET: Duration = Duration::from_secs(30);

/// [`WAIT_BUDGET`] once a server refused a per-minute quota.
///
/// Room for one wait to the next minute and a short one after it: a
/// minute's quota cannot come back sooner than the minute does.
const QUOTA_WAIT_BUDGET: Duration = Duration::from_secs(75);

/// The first back-off when the server names no `Retry-After`.
///
/// Doubled on each retry up to [`BACKOFF_MAX`], each wait jittered down to
/// half so workers throttled together do not come back together.
const BACKOFF_BASE: Duration = Duration::from_secs(1);

/// The longest single back-off when the server names no `Retry-After`.
const BACKOFF_MAX: Duration = Duration::from_secs(16);

/// The most a wait to the next minute is pushed past it, at random.
///
/// Workers refused together then do not come back together.
const MINUTE_JITTER: Duration = Duration::from_secs(1);

/// The longest response head read before giving up on parsing it.
const HEAD_CAP: usize = 64 * 1024;

/// The longest throttled body read to its end; a longer one passes.
const BODY_CAP: usize = 256 * 1024;

/// The largest chunk a chunked body is followed through.
///
/// A larger size is broken framing: the answer passes through untouched
/// and its connection is not reused.
const CHUNK_CAP: usize = 64 * 1024 * 1024;

/// The longest reason a throttled answer is logged with.
const REASON_CAP: usize = 160;

/// Gmail quota units per second an account is held near.
///
/// A batch is paced by the units of every call it carries.
const GMAIL_UNITS_PER_SECOND: u32 = quota::UNITS_PER_SECOND;

/// The units let through at once after a pause, on top of the request's.
const GMAIL_BURST_UNITS: u32 = quota::BURST_UNITS;

/// The units background Gmail requests may spend in any sixty seconds.
///
/// The project's quota less a fifth, so the two sides' clocks and the
/// requests still in flight never carry a pass over it.
const GMAIL_UNITS_PER_MINUTE: u32 = quota::UNITS_PER_MINUTE_PER_USER / 5 * 4;

/// The units urgent Gmail requests may spend in any sixty seconds.
const GMAIL_URGENT_UNITS_PER_MINUTE: u32 =
    quota::UNITS_PER_MINUTE_PER_USER - GMAIL_UNITS_PER_MINUTE;

/// How long an unused pacing is kept: past a minute it bears on nothing.
const GMAIL_PACE_IDLE: Duration = Duration::from_secs(120);

/// The Gmail pacing of every account, by the key its requests carry.
static GMAIL_PACES: LazyLock<Mutex<HashMap<String, GmailPace>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// The request in flight on a [`Client`] and how far its answer came.
#[derive(Default)]
pub struct Exchange {
    url: String,
    request: Vec<u8>,
    answering: bool,
    /// Whether it went out on a connection the server may have idled out.
    reused: bool,
    /// Whether it was sent again on a new connection already.
    resent: bool,
    /// Whether any byte of its answer came.
    heard: bool,
    /// How far its answer was read, to settle it at its end.
    follow: Follow,
}

impl Exchange {
    /// Whether the request was written whole and no answer byte came.
    fn unanswered(&self) -> bool {
        self.answering && !self.heard
    }
}

/// HTTP exchange with throttling, every HTTP runner's `read` and `write`.
impl<H: Host> Client<H> {
    /// Whether the last request was written whole and no answer byte came.
    ///
    /// The server may have carried it out, so a failed request that is not
    /// idempotent (a send) may have happened.
    pub fn unanswered(&self) -> bool {
        self.exchange.unanswered()
    }

    /// Writes request bytes to `url`, recorded for a throttled retry.
    ///
    /// A write after the answer started, or to another URL, begins a new
    /// request ([`Host::begin`]). An idempotent request failing on a reused
    /// connection is sent again whole on a new one, once.
    pub fn http_write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        let exchange = &mut self.exchange;
        if exchange.answering || exchange.url != url || exchange.request.is_empty() {
            exchange.url.clear();
            exchange.url.push_str(url);
            exchange.request.clear();
            exchange.answering = false;
            exchange.resent = false;
            exchange.follow = Follow::Unknown;
            exchange.heard = false;
            let reused = self.host.begin(url);
            self.exchange.reused = reused;
        }
        self.exchange.request.extend_from_slice(bytes);

        match self.write(url, bytes) {
            Err(err) if !resendable(&self.exchange) => Err(err),
            Err(err) => {
                log::debug!("{} write failed ({err}), sending again", short(url));
                self.resend(url)
            }
            Ok(()) => Ok(()),
        }
    }

    /// Reads the next chunk of the answer from `url`.
    ///
    /// The first read reads its head, and the whole answer when it may be
    /// throttled, retrying the request within the bounds.
    pub fn http_read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        let exchange = &mut self.exchange;
        let fresh = !exchange.answering
            && exchange.url == url
            && !exchange.request.is_empty()
            && !exchange.request.starts_with(b"HEAD ");
        if !fresh {
            let following = exchange.answering && exchange.url == url;
            let chunk = self.read(url)?;
            if following {
                self.follow(url, &chunk);
            }
            return Ok(chunk);
        }
        self.exchange.answering = true;

        let mut retries = 0;
        loop {
            let refusal = match self.read_answer(url)? {
                Answered::Passed(bytes) => {
                    self.exchange.heard = !bytes.is_empty();
                    self.begin_follow(url, &bytes);
                    return Ok(bytes);
                }
                // NOTE: a timed-out read may be a server still at work, not
                // an idle close: sending again could apply it twice, or meet
                // its own 412.
                Answered::Lost(lost)
                    if !resendable(&self.exchange) || lost.as_ref().is_some_and(timed_out) =>
                {
                    return match lost {
                        Some(err) => Err(err),
                        None => Ok(Vec::new()),
                    };
                }
                Answered::Lost(lost) => {
                    let why = lost.map_or_else(|| "end of stream".to_string(), |err| err.message);
                    log::debug!(
                        "{} connection lost before its answer ({why}), sending again",
                        short(url)
                    );
                    self.resend(url)?;
                    continue;
                }
                Answered::Throttled(refusal) => refusal,
            };
            self.exchange.heard = true;

            // NOTE: a 503 may come from a gateway after the server behind it
            // did the work: only a request that may be applied twice is sent
            // again; a 429 or a quota 403 refuses before any work.
            if refusal.status == 503 && !idempotent(&self.exchange.request) {
                log::debug!(
                    "{} unavailable (503), not sent again: not idempotent",
                    short(url)
                );
                self.begin_follow(url, &refusal.bytes);
                return Ok(refusal.bytes);
            }

            let (delay, budget) = match (refusal.retry_after, refusal.throttle) {
                (Some(delay), Throttle::Minute) => (delay, QUOTA_WAIT_BUDGET),
                (Some(delay), Throttle::Burst) => (delay, WAIT_BUDGET),
                // NOTE: Gmail's minute quota refills by the second, measured
                // clearing within 2 s: a few seconds more each retry, never
                // the next minute, and no quota budget spent for it.
                (None, Throttle::Minute) if self.gmail_pace.is_some() => {
                    (minute_pause(retries + 1), WAIT_BUDGET)
                }
                (None, Throttle::Minute) => {
                    let jitter = MINUTE_JITTER.mul_f64(rand::random_range(0.0..=1.0));
                    (
                        until_next_minute(self.host.now()) + jitter,
                        QUOTA_WAIT_BUDGET,
                    )
                }
                (None, Throttle::Burst) => {
                    (backoff(retries, rand::random_range(0.5..=1.0)), WAIT_BUDGET)
                }
            };
            let left = budget.saturating_sub(self.waited);
            if retries >= MAX_RETRIES || delay > left {
                log::warn!(
                    "{} still throttled ({} {}), giving up after {retries} retries",
                    short(url),
                    refusal.status,
                    refusal.reason
                );
                self.begin_follow(url, &refusal.bytes);
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
            self.host.sleep(delay);
            self.waited += delay;
            retries += 1;
            self.exchange.heard = false;

            let request = std::mem::take(&mut self.exchange.request);
            let written = self.write(url, &request);
            self.exchange.request = request;
            written?;
        }
    }

    /// Waits out the pacing of a Gmail request of `units` under `key`.
    ///
    /// Its throttled answers then hold that account's pacing; answers the
    /// wait ([`pace_gmail`]).
    pub fn pace_gmail(&mut self, key: &str, units: u32) -> Duration {
        self.gmail_pace = Some(key.to_string());
        let wait = pace_gmail(key, units, self.urgent);
        if !wait.is_zero() {
            self.host.sleep(wait);
        }
        wait
    }
}

/// Whether the request in flight may be sent again on a new connection.
///
/// It went out on a reused one, was not sent again already, and its method
/// is idempotent (RFC 9112 section 9.3.1).
fn resendable(exchange: &Exchange) -> bool {
    exchange.reused && !exchange.resent && idempotent(&exchange.request)
}

/// Whether a request's method is idempotent (RFC 9110 section 9.2.2).
///
/// WebDAV's PROPFIND and REPORT are safe, so they count.
fn idempotent(request: &[u8]) -> bool {
    const IDEMPOTENT: [&[u8]; 8] = [
        b"GET ",
        b"HEAD ",
        b"OPTIONS ",
        b"TRACE ",
        b"PUT ",
        b"DELETE ",
        b"PROPFIND ",
        b"REPORT ",
    ];
    IDEMPOTENT.iter().any(|method| request.starts_with(method))
}

/// Whether a failed read timed out rather than the connection ending.
///
/// The Java transport's `SocketTimeoutException`, as the bridge words it.
fn timed_out(err: &BridgeError) -> bool {
    err.message.contains("SocketTimeoutException") || err.message.contains("timed out")
}

/// The steps of a throttled exchange.
impl<H: Host> Client<H> {
    /// Writes the whole request again on a new connection.
    fn resend(&mut self, url: &str) -> Result<(), BridgeError> {
        self.reset(url);
        let exchange = &mut self.exchange;
        exchange.resent = true;
        exchange.reused = false;
        let request = std::mem::take(&mut exchange.request);
        let written = self.write(url, &request);
        self.exchange.request = request;
        written
    }

    /// Starts following an answer, settling it if its first bytes are all.
    fn begin_follow(&mut self, url: &str, bytes: &[u8]) {
        self.exchange.follow = Follow::of(bytes);
        self.follow(url, &[]);
    }

    /// Follows more bytes of the answer, settling it at its end.
    ///
    /// Bytes past that end drop the connection.
    fn follow(&mut self, url: &str, bytes: &[u8]) {
        let was = std::mem::take(&mut self.exchange.follow);
        match was.feed(bytes) {
            Follow::Ended => {
                self.exchange.follow = Follow::Settled;
                self.host.settle(url);
            }
            Follow::Excess => self.reset(url),
            next => self.exchange.follow = next,
        }
    }

    /// Reads an answer's head, and its body when it may be throttled.
    fn read_answer(&mut self, url: &str) -> Result<Answered, BridgeError> {
        let mut bytes = Vec::new();

        let head_end = loop {
            if let Some(end) = head_end(&bytes) {
                break end;
            }
            if bytes.len() > HEAD_CAP {
                return Ok(Answered::Passed(bytes));
            }
            let chunk = match self.read(url) {
                Err(err) if bytes.is_empty() => return Ok(Answered::Lost(Some(err))),
                read => read?,
            };
            if chunk.is_empty() && bytes.is_empty() {
                return Ok(Answered::Lost(None));
            }
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
                    let chunk = self.read(url)?;
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

        let now = self.host.now();
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
}

/// Books a Gmail request of `units` under `key`, answering its wait.
///
/// It waits for its slot under [`GMAIL_UNITS_PER_SECOND`] and room in the
/// last minute's [`GMAIL_UNITS_PER_MINUTE`]; an `urgent` one queues behind
/// none and draws on the fifth left. A batch is paced once, by its sum.
pub fn pace_gmail(key: &str, units: u32, urgent: bool) -> Duration {
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
    }
    wait
}

/// The gate an account's metadata batches go through one at a time.
///
/// Gmail runs a batch's calls side by side, so two batches at once draw
/// its per-user concurrency 429s.
pub fn gmail_gate(key: &str) -> Arc<Mutex<()>> {
    let mut paces = GMAIL_PACES.lock().unwrap_or_else(|err| err.into_inner());
    let pace = paces.entry(key.to_string()).or_insert_with(GmailPace::new);
    pace.used = Instant::now();
    Arc::clone(&pace.gate)
}

/// One account's Gmail pacing: per-second slot, minute windows, gate.
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

/// The quota units spent over the last minute, as a sliding window.
///
/// No sixty seconds then carry more than the budget, whichever minute the
/// server counts by.
#[derive(Debug)]
struct MinuteBudget {
    /// What was spent and when, oldest first, instants never decreasing.
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

    /// Books `units` once the minute has room under `limit`; answers the wait.
    ///
    /// Spends are booked in order. One larger than the whole budget counts
    /// as the budget, waiting for an empty minute rather than forever.
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
}

/// The wait of a request of `units` and the next free slot after it.
///
/// The generic cell rate algorithm over quota units, at `rate` a second:
/// after a pause, up to `burst` units more than the request's go at once.
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
    /// A per-minute quota: until the next minute ([`minute_pause`] on Gmail).
    Minute,
}

/// What the first read of an answer found.
enum Answered {
    /// Anything but a throttled answer: handed to the coroutine as is.
    Passed(Vec<u8>),
    /// No answer byte: the connection ended, or failed with the error.
    Lost(Option<BridgeError>),
    /// A throttled answer, read to its end.
    Throttled(Refusal),
}

/// A throttled answer, with its status, reason and asked wait.
struct Refusal {
    status: u16,
    reason: String,
    bytes: Vec<u8>,
    retry_after: Option<Duration>,
    throttle: Throttle,
}

/// How a complete answer is waited out, if it says it was throttled.
///
/// A 503, a 429, or a Google rate-limit 403; never a daily quota. Only a
/// named per-minute quota is a [`Throttle::Minute`]: any other, Gmail's
/// concurrency and per-second limits included, is gone within seconds.
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

/// Whether a body names one of Google's per-minute quotas.
///
/// In its message (Gmail's `limit 'Units per minute per user'`) or its
/// `ErrorInfo` metadata (`…PerMinutePerUser`); the older APIs' hundred
/// seconds count alike.
pub fn names_minute_quota(body: &[u8]) -> bool {
    contains(body, b"per minute")
        || contains(body, b"PerMinute")
        || contains(body, b"per 100 seconds")
        || contains(body, b"Per100Seconds")
}

/// Whether a body names a daily quota, which no wait in a sync brings back.
pub fn names_daily_quota(body: &[u8]) -> bool {
    contains(body, b"dailyLimitExceeded") || contains(body, b"per day") || contains(body, b"PerDay")
}

/// The reason a throttled answer gives, for the log.
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

/// The wait from `now` to the next minute, at least a second.
fn until_next_minute(now: Timestamp) -> Duration {
    let millis = now.as_millisecond().rem_euclid(60_000) as u64;
    Duration::from_millis(60_000 - millis).max(Duration::from_secs(1))
}

/// The back-off before retry `retries` (0-based), scaled by `jitter`.
fn backoff(retries: u32, jitter: f64) -> Duration {
    let full = BACKOFF_BASE
        .saturating_mul(1 << retries.min(16))
        .min(BACKOFF_MAX);
    full.mul_f64(jitter)
}

/// A `Retry-After` value as a wait from `now` (RFC 9110 section 10.2.3).
fn retry_after(value: &str, now: Timestamp) -> Option<Duration> {
    let value = value.trim();
    if let Ok(seconds) = value.parse::<u64>() {
        return Some(Duration::from_secs(seconds));
    }

    static PARSER: DateTimeParser = DateTimeParser::new();
    let at = PARSER.parse_timestamp(value).ok()?;
    Some(until(at, now))
}

/// The wait a Google error message names after `Retry after`.
///
/// Gmail's per-user rate limit names an RFC 3339 instant there.
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

/// The wait from `now` to `at`, nothing for a past instant.
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
    /// Neither: the body ends at the server's close, which a stall mimics.
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

/// How far an answer was read, to settle it once its end is.
#[derive(Default)]
enum Follow {
    /// No answer, or no end to tell: its connection is dropped before reuse.
    #[default]
    Unknown,
    /// This many octets of a `Content-Length` body still to come.
    Length(usize),
    /// A chunked body under way.
    Chunked(Chunks),
    /// Read to its end, not settled yet.
    Ended,
    /// Read to its end and settled.
    Settled,
    /// Bytes came past its end: the connection is out of step.
    Excess,
}

impl Follow {
    /// From an answer's first bytes, its head among them.
    fn of(bytes: &[u8]) -> Self {
        let Some(end) = head_end(bytes) else {
            return Self::Unknown;
        };
        let Some(head) = Head::parse(&bytes[..end]) else {
            return Self::Unknown;
        };
        if head.close || head.status < 200 {
            return Self::Unknown;
        }
        let body = match (head.status, head.framing) {
            (204 | 304, _) => Self::Length(0),
            (_, Framing::Length(length)) => Self::Length(length),
            (_, Framing::Chunked) => Self::Chunked(Chunks::default()),
            (_, Framing::None) => return Self::Unknown,
        };
        body.feed(&bytes[end..])
    }

    /// The state after more bytes of the answer.
    fn feed(self, bytes: &[u8]) -> Self {
        match self {
            Self::Length(left) if bytes.len() > left => Self::Excess,
            Self::Length(left) if bytes.len() == left => Self::Ended,
            Self::Length(left) => Self::Length(left - bytes.len()),
            Self::Chunked(mut chunks) => match chunks.feed(bytes) {
                ChunkFeed::Pending => Self::Chunked(chunks),
                ChunkFeed::Ended(read) if read == bytes.len() => Self::Ended,
                ChunkFeed::Ended(_) => Self::Excess,
                ChunkFeed::Broken => Self::Unknown,
            },
            Self::Ended | Self::Settled if !bytes.is_empty() => Self::Excess,
            other => other,
        }
    }
}

/// A chunked body followed as it streams (RFC 9112 section 7.1).
#[derive(Default)]
struct Chunks {
    /// Whether the last chunk was read and its trailers are coming.
    trailer: bool,
    /// The octets of the chunk under way still to come, its CRLF included.
    data: usize,
    /// The size or trailer line under way.
    line: Vec<u8>,
}

/// What a chunked body's next bytes did.
enum ChunkFeed {
    /// More to come.
    Pending,
    /// It ended after this many of the bytes.
    Ended(usize),
    /// A line that is no chunk size, or past the caps.
    Broken,
}

impl Chunks {
    fn feed(&mut self, bytes: &[u8]) -> ChunkFeed {
        let mut at = 0;
        while at < bytes.len() {
            if self.data > 0 {
                let take = self.data.min(bytes.len() - at);
                self.data -= take;
                at += take;
                continue;
            }

            let rest = &bytes[at..];
            let Some(lf) = rest.iter().position(|byte| *byte == b'\n') else {
                self.line.extend_from_slice(rest);
                return match self.line.len() > HEAD_CAP {
                    true => ChunkFeed::Broken,
                    false => ChunkFeed::Pending,
                };
            };
            self.line.extend_from_slice(&rest[..lf]);
            at += lf + 1;
            let taken = std::mem::take(&mut self.line);
            let line = taken.strip_suffix(b"\r").unwrap_or(&taken);

            if self.trailer {
                if line.is_empty() {
                    return ChunkFeed::Ended(at);
                }
                continue;
            }
            let Some(size) = chunk_size(line) else {
                return ChunkFeed::Broken;
            };
            match size {
                0 => self.trailer = true,
                // NOTE: in range, the CRLF included, under CHUNK_CAP.
                size => self.data = size + 2,
            }
        }
        ChunkFeed::Pending
    }
}

/// Decodes a chunked body (RFC 9112 section 7.1), skipping extensions.
fn dechunk(bytes: &[u8]) -> Body {
    let mut body = Vec::new();
    let mut at = 0;

    loop {
        let Some(line) = line_end(&bytes[at..]) else {
            return Body::Partial;
        };
        let Some(size) = chunk_size(&bytes[at..at + line]) else {
            return Body::Unframed;
        };
        at += line + 2;

        if size == 0 {
            // NOTE: trailers run until the blank line ending the body.
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

        if bytes.len() - at < size + 2 {
            return Body::Partial;
        }
        body.extend_from_slice(&bytes[at..at + size]);
        at += size + 2;
    }
}

/// The size a chunk-size line names (RFC 9112 section 7.1).
///
/// None for anything but hex digits before an extension, or past
/// [`CHUNK_CAP`].
fn chunk_size(line: &[u8]) -> Option<usize> {
    let size = line.split(|byte| *byte == b';').next()?.trim_ascii();
    if size.is_empty() || !size.iter().all(u8::is_ascii_hexdigit) {
        return None;
    }
    let size = usize::from_str_radix(std::str::from_utf8(size).ok()?, 16).ok()?;
    (size <= CHUNK_CAP).then_some(size)
}

/// Where the line at the start of `bytes` ends, before its CRLF.
fn line_end(bytes: &[u8]) -> Option<usize> {
    bytes.windows(2).position(|window| window == b"\r\n")
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use jiff::Timestamp;

    use crate::client::throttle::*;

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

    /// A hostile chunk size is broken framing, never an overflow or panic.
    #[test]
    fn a_chunk_size_out_of_range_or_garbage_is_broken_framing() {
        let sizes: [&[u8]; 8] = [
            b"FFFFFFFFFFFFFFFF",
            b"FFFFFFFFFFFFFFFFF",
            b"4000001",
            b"-1",
            b"+5",
            b"",
            b"5 5",
            b"\xff",
        ];
        for size in sizes {
            let body = [size, b"\r\nhello\r\n0\r\n\r\n"].concat();
            assert!(matches!(dechunk(&body), Body::Unframed), "{size:?}");
            let fed = Chunks::default().feed(&body);
            assert!(matches!(fed, ChunkFeed::Broken), "{size:?}");
        }

        assert_eq!(chunk_size(b"4000000"), Some(CHUNK_CAP));
        assert_eq!(chunk_size(b" a ;ext"), Some(10));
    }

    /// A chunk larger than the bytes read so far waits for more.
    #[test]
    fn a_chunk_past_the_bytes_read_is_partial() {
        assert!(matches!(dechunk(b"4000000\r\nhello"), Body::Partial));
        assert!(matches!(dechunk(b"6\r\nhello\r\n"), Body::Partial));

        let mut chunks = Chunks::default();
        let fed = chunks.feed(b"4000000\r\nhello");
        assert!(matches!(fed, ChunkFeed::Pending));
        assert_eq!(chunks.data, CHUNK_CAP + 2 - 5);
    }

    /// An overflowing chunk size passes through, its connection never reused.
    #[test]
    fn an_answer_with_an_overflowing_chunk_passes_through_unsettled() {
        for status in ["200 OK", "429 Too Many Requests"] {
            let answer = format!(
                "HTTP/1.1 {status}\r\nTransfer-Encoding: chunked\r\n\r\nFFFFFFFFFFFFFFFF\r\nhi"
            );
            let mut client =
                Client::new(Scripted::new(&[answer.as_bytes()], "2026-10-11T08:00:00Z"));
            client.http_write(GRAPH, GRAPH_GET).unwrap();
            let read = client.http_read(GRAPH).unwrap();
            assert_eq!(read, answer.as_bytes());
            assert!(client.host.slept.is_empty());
            assert_eq!(client.host.settled, 0);
        }
    }

    /// Gmail's per-minute quota refusal, its project number made up.
    const GMAIL_MINUTE_QUOTA: &[u8] = br#"{"error":{"code":403,"message":"Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user' of service 'gmail.googleapis.com' for consumer 'project_number:123'.","errors":[{"message":"Quota exceeded","domain":"usageLimits","reason":"rateLimitExceeded"}],"status":"PERMISSION_DENIED"}}"#;

    /// Gmail's per-user concurrency limit, on a request or a batch's call.
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
        // NOTE: a `RESOURCE_EXHAUSTED` 403 naming no quota is a rate limit.
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
        slept: Vec<Duration>,
        now: Timestamp,
        /// Whether a request goes out on a connection that served one.
        reused: bool,
        /// How many reads fail before the script answers again.
        failing: u32,
        /// What a failing read fails with.
        failure: &'static str,
        begun: u32,
        settled: u32,
        resets: u32,
    }

    impl Scripted {
        fn new(answers: &[&[u8]], now: &str) -> Self {
            Self {
                answers: answers.iter().map(|answer| answer.to_vec()).collect(),
                writes: Vec::new(),
                slept: Vec::new(),
                now: now.parse().unwrap(),
                reused: false,
                failing: 0,
                failure: "Transport read failed: SocketException: Connection reset",
                begun: 0,
                settled: 0,
                resets: 0,
            }
        }
    }

    impl Host for Scripted {
        fn read(&mut self, _url: &str) -> Result<Vec<u8>, BridgeError> {
            if self.failing > 0 {
                self.failing -= 1;
                return Err(self.failure.into());
            }
            Ok(self.answers.pop_front().unwrap_or_default())
        }

        fn write(&mut self, _url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
            self.writes.push(bytes.to_vec());
            Ok(())
        }

        fn begin(&mut self, _url: &str) -> bool {
            self.begun += 1;
            self.reused
        }

        fn settle(&mut self, _url: &str) {
            self.settled += 1;
        }

        fn reset(&mut self, _url: &str) {
            self.resets += 1;
        }

        fn starttls(&mut self, _url: &str) -> Result<(), BridgeError> {
            Err("Unscripted".into())
        }

        fn serve(&mut self, _request: &str) -> Result<String, BridgeError> {
            Err("Unscripted".into())
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

    // NOTE: a scripted wire paces no account, leaving the process-wide
    // pacing alone; a refusal reads the same on any API.
    const URL: &str = "https://gmail.googleapis.com/gmail/v1/users/me/messages";
    const REQUEST: &[u8] =
        b"GET /gmail/v1/users/me/messages HTTP/1.1\r\nHost: gmail.googleapis.com\r\n\r\n";

    #[test]
    fn a_minute_quota_refusal_waits_for_the_next_minute_then_succeeds() {
        let refused = answer("403 Forbidden", GMAIL_MINUTE_QUOTA);
        let served = answer("200 OK", b"{}");
        let mut client = Client::new(Scripted::new(&[&refused, &served], "2026-10-07T08:49:20Z"));

        client.http_write(URL, REQUEST).unwrap();
        let read = client.http_read(URL).unwrap();

        assert_eq!(read, served);
        assert_eq!(client.host.writes, [REQUEST, REQUEST]);
        assert_eq!(client.host.slept.len(), 1);
        let slept = client.host.slept[0];
        assert!(slept >= Duration::from_secs(40), "{slept:?}");
        assert!(
            slept <= Duration::from_secs(40) + MINUTE_JITTER,
            "{slept:?}"
        );
    }

    #[test]
    fn a_gmail_minute_quota_refusal_backs_off_seconds_more_each_time() {
        let refused = answer("403 Forbidden", GMAIL_MINUTE_QUOTA);
        let served = answer("200 OK", b"{}");
        let mut client = Client::new(Scripted::new(
            &[&refused, &refused, &served],
            "2026-10-07T08:49:20Z",
        ));
        client.gmail_pace = Some("token".into());

        client.http_write(URL, REQUEST).unwrap();
        assert_eq!(client.http_read(URL).unwrap(), served);

        // NOTE: two, then three seconds, never the forty to the minute.
        assert_eq!(
            client.host.slept,
            [Duration::from_secs(2), Duration::from_secs(3)]
        );
    }

    #[test]
    fn a_minute_quota_refusal_past_its_budget_is_the_answer() {
        let refused = answer("403 Forbidden", GMAIL_MINUTE_QUOTA);
        let mut client = Client::new(Scripted::new(
            &[&refused, &refused, &refused],
            "2026-10-07T08:49:20Z",
        ));

        client.http_write(URL, REQUEST).unwrap();
        let read = client.http_read(URL).unwrap();

        // NOTE: one wait to the next minute fits the budget, a second
        // does not.
        assert_eq!(read, refused);
        assert_eq!(client.host.slept.len(), 1);
        assert!(client.waited <= QUOTA_WAIT_BUDGET);
    }

    #[test]
    fn a_concurrency_refusal_is_retried_within_a_second() {
        let refused = answer("429 Too Many Requests", GMAIL_CONCURRENCY);
        let served = answer("200 OK", b"{}");
        let mut client = Client::new(Scripted::new(
            &[&refused, &refused, &served],
            "2026-10-07T08:49:20Z",
        ));

        client.http_write(URL, REQUEST).unwrap();
        assert_eq!(client.http_read(URL).unwrap(), served);

        // NOTE: two short back-offs, never the forty to the next minute.
        assert_eq!(client.host.slept.len(), 2);
        assert!(
            client.host.slept[0] <= Duration::from_secs(1),
            "{:?}",
            client.host.slept
        );
        assert!(
            client.host.slept[1] <= Duration::from_secs(2),
            "{:?}",
            client.host.slept
        );
    }

    #[test]
    fn a_user_rate_refusal_waits_until_the_instant_it_names() {
        let refused = answer("429 Too Many Requests", GMAIL_USER_RATE);
        let served = answer("200 OK", b"{}");
        let mut client = Client::new(Scripted::new(&[&refused, &served], "2026-10-10T20:21:40Z"));

        client.http_write(URL, REQUEST).unwrap();
        assert_eq!(client.http_read(URL).unwrap(), served);
        assert_eq!(client.host.slept, [Duration::from_millis(1500)]);
    }

    #[test]
    fn a_retry_after_on_a_429_is_taken_as_named() {
        let refused =
            b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 3\r\nContent-Length: 2\r\n\r\n{}";
        let served = answer("200 OK", b"{}");
        let mut client = Client::new(Scripted::new(&[refused, &served], "2026-10-07T08:49:20Z"));

        client.http_write(URL, REQUEST).unwrap();
        assert_eq!(client.http_read(URL).unwrap(), served);
        assert_eq!(client.host.slept, [Duration::from_secs(3)]);
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

        // NOTE: a server that never names a wait still gets every retry.
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

        for (at, (first, _)) in booked.iter().enumerate() {
            let within: u32 = booked[at..]
                .iter()
                .take_while(|(later, _)| *later < *first + Duration::from_secs(60))
                .map(|(_, units)| units)
                .sum();
            assert!(within <= limit, "{within} units within a minute");
        }

        let mut full = MinuteBudget::new();
        for _ in 0..limit / 5 {
            assert_eq!(full.reserve(start, 5, limit), Duration::ZERO);
        }
        assert_eq!(full.reserve(start, 100, limit), Duration::from_secs(60));
    }

    #[test]
    fn a_spend_past_the_whole_budget_waits_for_an_empty_minute() {
        let start = Instant::now();
        // NOTE: not forever.
        let mut empty = MinuteBudget::new();
        assert_eq!(empty.reserve(start, 500, 100), Duration::ZERO);
        assert_eq!(empty.reserve(start, 1, 100), Duration::from_secs(60));
    }

    #[test]
    fn gmail_pacing_holds_quota_units_per_second() {
        let start = Instant::now();
        let mut pace = GmailPace::new();

        // NOTE: a batch of 10 reads is 200 units: it goes at once, the
        // next waits for its units to be paid at the rate.
        assert_eq!(pace.book(start, 200, false), Duration::ZERO);
        let second = pace.book(start, 200, false);
        let per_unit = Duration::from_secs(1) / GMAIL_UNITS_PER_SECOND;
        assert_eq!(second, per_unit * 200);
        let third = pace.book(start, 200, false);
        assert_eq!(third, second + per_unit * 200);

        let later = start + Duration::from_secs(10);
        let mut free = 0;
        for _ in 0..60 {
            free += u32::from(pace.book(later, 5, false).is_zero());
        }
        assert_eq!(free, GMAIL_BURST_UNITS / 5 + 1);
    }

    #[test]
    fn an_urgent_request_skips_the_queue_and_the_spent_minute() {
        let start = Instant::now();
        let mut pace = GmailPace::new();

        for _ in 0..GMAIL_UNITS_PER_MINUTE / 125 {
            pace.book(start, 125, false);
        }
        assert!(pace.book(start, 125, false) >= Duration::from_secs(30));

        let before = pace.next.unwrap();
        assert_eq!(pace.book(start, 5, true), Duration::ZERO);
        assert!(pace.next.unwrap() > before);
    }

    const GRAPH: &str = "https://graph.microsoft.com/v1.0";
    const GRAPH_GET: &[u8] =
        b"GET /v1.0/me/messages/m1/$value HTTP/1.1\r\nHost: graph.microsoft.com\r\n\r\n";
    const GRAPH_POST: &[u8] =
        b"POST /v1.0/$batch HTTP/1.1\r\nHost: graph.microsoft.com\r\nContent-Length: 2\r\n\r\n{}";
    const GRAPH_PUT: &[u8] =
        b"PUT /v1.0/me/events/e1 HTTP/1.1\r\nHost: graph.microsoft.com\r\nContent-Length: 2\r\n\r\n{}";

    /// A connection Graph closed while idle is resent on a new one.
    #[test]
    fn a_reused_connection_ended_before_its_answer_sends_the_request_again() {
        let served = answer("200 OK", b"From: a\r\n\r\nbody");
        let mut client = Client::new(Scripted::new(&[b"", &served], "2026-10-11T08:00:00Z"));
        client.host.reused = true;

        client.http_write(GRAPH, GRAPH_GET).unwrap();
        assert_eq!(client.http_read(GRAPH).unwrap(), served);

        assert_eq!(client.host.writes, [GRAPH_GET, GRAPH_GET]);
        assert_eq!(client.host.resets, 1);
        assert_eq!(client.host.settled, 1, "the answer read whole is settled");
    }

    #[test]
    fn a_reused_connection_reset_before_its_answer_sends_the_request_again() {
        let served = answer("200 OK", b"{}");
        let mut client = Client::new(Scripted::new(&[&served], "2026-10-11T08:00:00Z"));
        client.host.reused = true;
        client.host.failing = 1;

        client.http_write(GRAPH, GRAPH_GET).unwrap();
        assert_eq!(client.http_read(GRAPH).unwrap(), served);
        assert_eq!(client.host.writes, [GRAPH_GET, GRAPH_GET]);
    }

    #[test]
    fn a_request_is_sent_again_once_only_idempotent_and_reused() {
        // NOTE: a POST may have been served before the connection went.
        let mut post = Client::new(Scripted::new(&[b""], "2026-10-11T08:00:00Z"));
        post.host.reused = true;
        post.http_write(GRAPH, GRAPH_POST).unwrap();
        assert!(post.http_read(GRAPH).unwrap().is_empty());
        assert_eq!(post.host.writes.len(), 1);

        // NOTE: a fresh connection ending is an answer, not an idle close.
        let mut fresh = Client::new(Scripted::new(&[b""], "2026-10-11T08:00:00Z"));
        fresh.http_write(GRAPH, GRAPH_GET).unwrap();
        assert!(fresh.http_read(GRAPH).unwrap().is_empty());
        assert_eq!(fresh.host.writes.len(), 1);

        let mut failing = Client::new(Scripted::new(&[], "2026-10-11T08:00:00Z"));
        failing.host.reused = true;
        failing.host.failing = 2;
        failing.http_write(GRAPH, GRAPH_GET).unwrap();
        assert!(failing.http_read(GRAPH).is_err());
        assert_eq!(failing.host.writes.len(), 2, "sent again once");
    }

    /// A PUT that timed out may still apply: nothing is sent again.
    #[test]
    fn a_read_timeout_is_not_a_lost_connection() {
        let mut client = Client::new(Scripted::new(&[], "2026-10-11T08:00:00Z"));
        client.host.reused = true;
        client.host.failing = 1;
        client.host.failure =
            "Transport read failed: java.net.SocketTimeoutException: Read timed out";

        client.http_write(GRAPH, GRAPH_PUT).unwrap();
        assert!(client.http_read(GRAPH).is_err());
        assert_eq!(client.host.writes, [GRAPH_PUT]);
    }

    /// A send left unanswered may have happened; one refused mid-write not.
    #[test]
    fn a_request_without_an_answer_is_unanswered() {
        let mut lost = Client::new(Scripted::new(&[b""], "2026-10-11T08:00:00Z"));
        lost.http_write(GRAPH, GRAPH_POST).unwrap();
        assert!(!lost.unanswered(), "nothing read yet");
        assert!(lost.http_read(GRAPH).unwrap().is_empty());
        assert!(lost.unanswered());

        let served = answer("500 Internal Server Error", b"{}");
        let mut answered = Client::new(Scripted::new(&[&served], "2026-10-11T08:00:00Z"));
        answered.http_write(GRAPH, GRAPH_POST).unwrap();
        answered.http_read(GRAPH).unwrap();
        assert!(!answered.unanswered());

        answered.http_write(GRAPH, GRAPH_POST).unwrap();
        assert!(!answered.unanswered(), "a new request resets it");
    }

    /// A gateway's 503 may follow the work: a POST is not resent, a GET is.
    #[test]
    fn only_an_idempotent_request_is_sent_again_after_a_503() {
        let unavailable =
            b"HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nContent-Length: 2\r\n\r\n{}";
        let served = answer("200 OK", b"{}");

        let mut post = Client::new(Scripted::new(
            &[unavailable, &served],
            "2026-10-11T08:00:00Z",
        ));
        post.http_write(GRAPH, GRAPH_POST).unwrap();
        assert_eq!(post.http_read(GRAPH).unwrap(), unavailable);
        assert_eq!(post.host.writes, [GRAPH_POST]);
        assert!(post.host.slept.is_empty());

        let mut get = Client::new(Scripted::new(
            &[unavailable, &served],
            "2026-10-11T08:00:00Z",
        ));
        get.http_write(GRAPH, GRAPH_GET).unwrap();
        assert_eq!(get.http_read(GRAPH).unwrap(), served);
        assert_eq!(get.host.writes, [GRAPH_GET, GRAPH_GET]);
    }

    /// A 429 refuses before any work: a POST waits and goes again.
    #[test]
    fn a_post_refused_by_a_429_is_sent_again() {
        let refused = answer("429 Too Many Requests", b"{}");
        let served = answer("200 OK", b"{}");
        let mut post = Client::new(Scripted::new(&[&refused, &served], "2026-10-11T08:00:00Z"));

        post.http_write(GRAPH, GRAPH_POST).unwrap();
        assert_eq!(post.http_read(GRAPH).unwrap(), served);
        assert_eq!(post.host.writes, [GRAPH_POST, GRAPH_POST]);
    }

    #[test]
    fn an_answer_is_settled_once_read_to_its_end() {
        let served = answer("200 OK", b"0123456789");
        let (head, tail) = served.split_at(served.len() - 4);
        let mut client = Client::new(Scripted::new(&[head, tail], "2026-10-11T08:00:00Z"));

        client.http_write(GRAPH, GRAPH_GET).unwrap();
        assert_eq!(client.host.begun, 1, "a new request begins on its origin");
        client.http_read(GRAPH).unwrap();
        assert_eq!(client.host.settled, 0, "four octets still to come");
        client.http_read(GRAPH).unwrap();
        assert_eq!(client.host.settled, 1);

        client.http_write(GRAPH, GRAPH_GET).unwrap();
        assert_eq!(client.host.begun, 2);
    }

    #[test]
    fn a_chunked_answer_is_settled_at_its_last_chunk() {
        let mut client = Client::new(Scripted::new(
            &[
                b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhel",
                b"lo\r\n6;x=y\r\n world\r",
                b"\n0\r\nX-T: 1\r\n",
                b"\r\n",
            ],
            "2026-10-11T08:00:00Z",
        ));
        client.http_write(GRAPH, GRAPH_GET).unwrap();
        for _ in 0..3 {
            client.http_read(GRAPH).unwrap();
            assert_eq!(client.host.settled, 0);
        }
        client.http_read(GRAPH).unwrap();
        assert_eq!(client.host.settled, 1);
    }

    #[test]
    fn an_answer_whose_end_cannot_be_told_is_never_settled() {
        let closing = b"HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 2\r\n\r\n{}";
        let unframed = b"HTTP/1.1 200 OK\r\n\r\n{}";
        for answer in [&closing[..], &unframed[..]] {
            let mut client = Client::new(Scripted::new(&[answer], "2026-10-11T08:00:00Z"));
            client.http_write(GRAPH, GRAPH_GET).unwrap();
            client.http_read(GRAPH).unwrap();
            assert_eq!(client.host.settled, 0);
        }

        // NOTE: bytes past the end put the connection out of step.
        let mut excess = Client::new(Scripted::new(
            &[b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}HTTP/1.1"],
            "2026-10-11T08:00:00Z",
        ));
        excess.http_write(GRAPH, GRAPH_GET).unwrap();
        excess.http_read(GRAPH).unwrap();
        assert_eq!(excess.host.settled, 0);
        assert_eq!(excess.host.resets, 1);
    }

    #[test]
    fn a_throttled_answer_given_up_on_is_settled() {
        let refused = answer("503 Service Unavailable", b"");
        let answers: Vec<&[u8]> = vec![&refused; MAX_RETRIES as usize + 1];
        let mut client = Client::new(Scripted::new(&answers, "2026-10-11T08:00:00Z"));
        client.http_write(GRAPH, GRAPH_GET).unwrap();
        assert_eq!(client.http_read(GRAPH).unwrap(), refused);
        assert_eq!(client.host.settled, 1);
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
