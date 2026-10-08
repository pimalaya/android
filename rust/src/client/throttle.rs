//! Throttling, in one place for every HTTP backend: Graph, Gmail,
//! Google Calendar and People, CalDAV, CardDAV and JMAP.
//!
//! A server that throttles answers 429 or 503, often with a
//! `Retry-After`; Google answers its per-user rate limits with a 429,
//! or a 403 whose reason is `rateLimitExceeded` or
//! `userRateLimitExceeded` or whose message names a per-minute quota
//! (`Quota exceeded for quota metric … Units per minute per user`).
//! Each of those means the request was not served, so it is sent
//! again after a wait rather than failing the sync pass: the server's
//! `Retry-After`, else a short back-off, or for Google's per-minute
//! quotas the next minute.
//!
//! Gmail is also paced before it is asked ([`pace_gmail`]): a per-second
//! rate and a per-minute budget of quota units, shared by every worker,
//! so a long pass waits for its quota rather than drawing refusals.
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

use std::{
    collections::VecDeque,
    sync::Mutex,
    time::{Duration, Instant},
};

use io_gmail::v1::{batch::GMAIL_BATCH_URL, send::GmailApiError};
use jiff::{Timestamp, fmt::rfc2822::DateTimeParser};
use url::Url;

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

/// Gmail API requests per second the account is held near. Gmail grants
/// 250 quota units per user per second, and `messages.list` and
/// `messages.get` cost 5 each, so about 50 reads a second (each
/// sub-request of a batch counting on its own); pacing at 40 keeps
/// below the limit rather than waiting for its 429s. Process-wide, so
/// every worker of a sync pass shares it.
pub(crate) const GMAIL_REQUESTS_PER_SECOND: u32 = 40;

/// How many Gmail requests may go out at once after a pause, on top of
/// the one the pacing lets through.
const GMAIL_BURST: u32 = 10;

/// Gmail's per-minute quota per user, in quota units, as Google
/// documents it by default (Gmail API, "Usage limits": 15,000 units per
/// user per minute). The documented default, not a measurement: a
/// project's own quota is read in its Cloud console, and the project
/// the app runs under refused a pass that spent far fewer (2026-10-07).
const GMAIL_UNITS_PER_MINUTE_DOCUMENTED: u32 = 15_000;

/// The quota units Gmail requests may spend in any sixty seconds: the
/// documented default less a fifth, so the two sides' clocks and the
/// requests still in flight never carry a pass over it.
pub(crate) const GMAIL_UNITS_PER_MINUTE: u32 = GMAIL_UNITS_PER_MINUTE_DOCUMENTED / 5 * 4;

/// Where Gmail API requests go, to tell its quota refusals apart; its
/// batches go to [`GMAIL_BATCH_URL`], on another host.
const GMAIL_HOST: &str = "gmail.googleapis.com";

/// The Gmail pacing, shared by every worker.
static GMAIL_PACE: Mutex<GmailPace> = Mutex::new(GmailPace {
    next: None,
    minute: MinuteBudget::new(),
});

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
        let (bytes, retry_after, throttle) = match read_answer(wire, url)? {
            Answered::Passed(bytes) => return Ok(bytes),
            Answered::Throttled {
                bytes,
                retry_after,
                throttle,
            } => (bytes, retry_after, throttle),
        };

        // NOTE: the other workers would draw the same refusal, so the
        // shared pacing holds them a minute too.
        if throttle == Throttle::Minute && gmail(url) {
            gmail_quota_refused();
        }

        let (delay, budget) = match (retry_after, throttle) {
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
            log::warn!("{url} still throttled, giving up after {retries} retries");
            return Ok(bytes);
        }

        log::warn!("{url} throttled, retrying in {}ms", delay.as_millis());
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

    let Some(throttle) = throttled(head.status, &body, google(url)) else {
        return Ok(Answered::Passed(bytes));
    };

    let retry_after = head
        .retry_after
        .as_deref()
        .and_then(|value| retry_after(value, wire.now()));
    Ok(Answered::Throttled {
        bytes,
        retry_after,
        throttle,
    })
}

/// Holds one Gmail request costing `units` quota units until both its
/// slot under [`GMAIL_REQUESTS_PER_SECOND`] and room in the last
/// minute's [`GMAIL_UNITS_PER_MINUTE`] come.
///
/// A batch is one request but costs the units of every call it carries,
/// so it is paced once with their sum.
pub(crate) fn pace_gmail(units: u32) {
    let wait = {
        let mut pace = GMAIL_PACE.lock().unwrap_or_else(|err| err.into_inner());
        let now = Instant::now();
        let (slot, after) = pace_slot(pace.next, now, GMAIL_REQUESTS_PER_SECOND, GMAIL_BURST);
        pace.next = Some(after);
        let budget = pace
            .minute
            .reserve(now + slot, units, GMAIL_UNITS_PER_MINUTE);
        slot + budget
    };

    if !wait.is_zero() {
        std::thread::sleep(wait);
    }
}

/// Spends what is left of the minute's Gmail budget: Gmail just refused
/// a per-minute quota, so every worker holds off a minute rather than
/// drawing the same refusal.
fn gmail_quota_refused() {
    let mut pace = GMAIL_PACE.lock().unwrap_or_else(|err| err.into_inner());
    pace.minute.exhaust(Instant::now(), GMAIL_UNITS_PER_MINUTE);
}

/// The Gmail pacing: the next per-second slot and the minute's units.
struct GmailPace {
    next: Option<Instant>,
    minute: MinuteBudget,
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

/// One step of a paced stream (the generic cell rate algorithm): given
/// the next free slot and now, how long this request waits and the
/// slot after it. Up to `burst` requests go out at once after a pause.
fn pace_slot(next: Option<Instant>, now: Instant, rate: u32, burst: u32) -> (Duration, Instant) {
    let interval = Duration::from_secs(1) / rate;
    let earliest = now.checked_sub(interval * burst).unwrap_or(now);
    let slot = next.map_or(now, |next| next.max(earliest));

    (slot.saturating_duration_since(now), slot + interval)
}

/// How a throttled answer is waited out when it names no `Retry-After`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Throttle {
    /// A burst limit: a short exponential back-off.
    Burst,
    /// A per-minute quota, Google's: until the next minute.
    Minute,
}

/// What the first read of an answer found.
enum Answered {
    /// Anything but a throttled answer: handed to the coroutine as is.
    Passed(Vec<u8>),
    /// A throttled answer, read to its end, and the wait it asked for.
    Throttled {
        bytes: Vec<u8>,
        retry_after: Option<Duration>,
        throttle: Throttle,
    },
}

/// Whether a complete answer says the request was throttled, and how it
/// is waited out: a 503, a 429, or one of Google's rate-limit 403s (a
/// 403 for anything else, a daily quota included, is no reason to
/// wait). Google's are per-minute quotas; `google` says the request
/// went to a Google API, whose 429s count by the minute too, and whose
/// error envelope names its reasons, matched before the text is.
fn throttled(status: u16, body: &[u8], google: bool) -> Option<Throttle> {
    match status {
        503 => Some(Throttle::Burst),
        429 if google || names_minute_quota(body) => Some(Throttle::Minute),
        429 => Some(Throttle::Burst),
        403 if names_daily_quota(body) => None,
        403 if google && GmailApiError::parse(status, body).is_rate_limited() => {
            Some(Throttle::Minute)
        }
        403 if names_minute_quota(body)
            || contains(body, b"rateLimitExceeded")
            || contains(body, b"userRateLimitExceeded") =>
        {
            Some(Throttle::Minute)
        }
        _ => None,
    }
}

/// Whether a body names one of Google's per-minute quotas, as Gmail's
/// `Quota exceeded for quota metric 'Total Query Cost' and limit 'Units
/// per minute per user'` does.
fn names_minute_quota(body: &[u8]) -> bool {
    contains(body, b"per minute")
        || contains(body, b"RATE_LIMIT_EXCEEDED")
        || (contains(body, b"Quota exceeded for quota metric") && !names_daily_quota(body))
}

/// Whether a body names a daily quota, which no wait within a sync
/// brings back.
fn names_daily_quota(body: &[u8]) -> bool {
    contains(body, b"dailyLimitExceeded") || contains(body, b"per day")
}

/// Whether a URL is Gmail's, its API or its batch endpoint, whose quota
/// refusals hold the shared pacing.
fn gmail(url: &str) -> bool {
    url.starts_with(GMAIL_BATCH_URL)
        || Url::parse(url)
            .ok()
            .is_some_and(|url| url.host_str() == Some(GMAIL_HOST))
}

/// Whether a URL is one of Google's APIs.
fn google(url: &str) -> bool {
    Url::parse(url)
        .ok()
        .and_then(|url| url.host_str().map(|host| host.ends_with(".googleapis.com")))
        .unwrap_or(false)
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
    let millis = at.as_millisecond().saturating_sub(now.as_millisecond());
    Some(Duration::from_millis(millis.max(0) as u64))
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

    #[test]
    fn only_google_rate_limits_make_a_403_throttled() {
        assert_eq!(throttled(429, b"", false), Some(Throttle::Burst));
        assert_eq!(throttled(503, b"", false), Some(Throttle::Burst));
        assert_eq!(
            throttled(
                403,
                br#"{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"#,
                true
            ),
            Some(Throttle::Minute)
        );
        assert_eq!(
            throttled(403, br#"{"reason":"rateLimitExceeded"}"#, true),
            Some(Throttle::Minute)
        );
        assert_eq!(
            throttled(403, br#"{"reason":"dailyLimitExceeded"}"#, true),
            None
        );
        assert_eq!(throttled(403, b"Forbidden", true), None);
        assert_eq!(throttled(500, b"", true), None);
    }

    /// The answer Gmail gave on 2026-10-07, its project number made up.
    const GMAIL_MINUTE_QUOTA: &[u8] = br#"{"error":{"code":403,"message":"Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user' of service 'gmail.googleapis.com' for consumer 'project_number:123'.","errors":[{"message":"Quota exceeded","domain":"usageLimits","reason":"rateLimitExceeded"}],"status":"PERMISSION_DENIED"}}"#;

    #[test]
    fn google_quotas_count_by_the_minute_and_a_daily_one_not_at_all() {
        assert_eq!(
            throttled(403, GMAIL_MINUTE_QUOTA, true),
            Some(Throttle::Minute)
        );
        // The message alone names the quota, whatever reasons ride along.
        assert_eq!(
            throttled(
                403,
                b"Quota exceeded for quota metric 'Queries' and limit 'Queries per minute per user'",
                false
            ),
            Some(Throttle::Minute)
        );
        assert_eq!(
            throttled(
                403,
                b"Quota exceeded for quota metric 'Queries' and limit 'Queries per day'",
                true
            ),
            None
        );
        // A Google 429 counts by the minute; another server's is a burst.
        assert_eq!(throttled(429, b"", true), Some(Throttle::Minute));
        assert_eq!(
            throttled(429, br#"{"status":"RESOURCE_EXHAUSTED"}"#, false),
            Some(Throttle::Burst)
        );
        // NOTE: the reasons Google's envelope names, read as reasons: a
        // `RESOURCE_EXHAUSTED` 403 whose message names no quota at all.
        assert_eq!(
            throttled(
                403,
                br#"{"error":{"code":403,"message":"Too many","errors":[{"reason":"quotaExceeded"}],"status":"RESOURCE_EXHAUSTED"}}"#,
                true
            ),
            Some(Throttle::Minute)
        );
        assert_eq!(
            throttled(
                403,
                br#"{"error":{"code":403,"message":"No","errors":[{"reason":"insufficientPermissions"}]}}"#,
                true
            ),
            None
        );
        assert!(gmail("https://gmail.googleapis.com/gmail/v1/"));
        assert!(gmail(GMAIL_BATCH_URL), "the batch endpoint counts as Gmail");
        assert!(!gmail("https://people.googleapis.com/v1/"));
        assert!(!gmail("https://www.googleapis.com/calendar/v3/"));
        assert!(google(GMAIL_BATCH_URL));
        assert!(google("https://gmail.googleapis.com/gmail/v1/"));
        assert!(google("https://people.googleapis.com/v1/"));
        assert!(!google("https://graph.microsoft.com/v1.0/"));
        assert!(!google("https://googleapis.com.example/"));
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

    // NOTE: not Gmail's host, so these tests leave the process-wide
    // pacing alone; the quota refusal reads the same on any Google API.
    const PEOPLE: &str = "https://people.googleapis.com/v1/";
    const REQUEST: &[u8] = b"GET /v1/people/me HTTP/1.1\r\nHost: people.googleapis.com\r\n\r\n";

    #[test]
    fn a_minute_quota_refusal_waits_for_the_next_minute_then_succeeds() {
        let refused = answer("403 Forbidden", GMAIL_MINUTE_QUOTA);
        let served = answer("200 OK", b"{}");
        let mut wire = Scripted::new(&[&refused, &served], "2026-10-07T08:49:20Z");

        throttled_write(&mut wire, PEOPLE, REQUEST).unwrap();
        let read = throttled_read(&mut wire, PEOPLE).unwrap();

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

        throttled_write(&mut wire, PEOPLE, REQUEST).unwrap();
        let read = throttled_read(&mut wire, PEOPLE).unwrap();

        // One wait to the next minute fits the budget, a second whole
        // minute does not: the refusal reaches the coroutine.
        assert_eq!(read, refused);
        assert_eq!(wire.slept.len(), 1);
        assert!(wire.waited <= QUOTA_WAIT_BUDGET);
    }

    #[test]
    fn a_retry_after_on_a_google_429_is_taken_as_named() {
        let refused =
            b"HTTP/1.1 429 Too Many Requests\r\nRetry-After: 3\r\nContent-Length: 2\r\n\r\n{}";
        let served = answer("200 OK", b"{}");
        let mut wire = Scripted::new(&[refused, &served], "2026-10-07T08:49:20Z");

        throttled_write(&mut wire, PEOPLE, REQUEST).unwrap();
        assert_eq!(throttled_read(&mut wire, PEOPLE).unwrap(), served);
        assert_eq!(wire.slept, [Duration::from_secs(3)]);
    }

    #[test]
    fn the_minute_budget_caps_a_long_pass() {
        let start = Instant::now();
        let limit = GMAIL_UNITS_PER_MINUTE;
        let mut budget = MinuteBudget::new();
        let mut next = None;
        let mut booked = Vec::new();

        // Three minutes of metadata reads at the paced rate, a send of a
        // hundred units every fifty reads, the clock moving with each
        // wait as a worker's would.
        let mut now = start;
        while now < start + Duration::from_secs(180) {
            let units = if booked.len() % 50 == 49 { 100 } else { 5 };
            let (slot, after) = pace_slot(next, now, GMAIL_REQUESTS_PER_SECOND, GMAIL_BURST);
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
    fn retry_after_reads_seconds_and_dates() {
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
    fn gmail_pacing_lets_a_burst_through_then_holds_the_rate() {
        let start = Instant::now();
        let interval = Duration::from_secs(1) / GMAIL_REQUESTS_PER_SECOND;

        let mut next = None;
        let mut waits = Vec::new();
        for _ in 0..GMAIL_BURST + 3 {
            let (wait, after) = pace_slot(next, start, GMAIL_REQUESTS_PER_SECOND, GMAIL_BURST);
            waits.push(wait);
            next = Some(after);
        }

        // The first request goes at once, and the ones after it each
        // wait one more interval: a stream at the rate from the start.
        assert_eq!(waits[0], Duration::ZERO);
        assert_eq!(waits[3], interval * 3);

        // After a pause, a burst goes out at once, and no more than it.
        let later = start + Duration::from_secs(10);
        let mut free = 0;
        for _ in 0..GMAIL_BURST * 2 {
            let (wait, after) = pace_slot(next, later, GMAIL_REQUESTS_PER_SECOND, GMAIL_BURST);
            free += u32::from(wait.is_zero());
            next = Some(after);
        }
        assert_eq!(free, GMAIL_BURST + 1);
    }
}
