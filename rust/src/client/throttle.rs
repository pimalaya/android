//! Throttling, in one place for every HTTP backend: Graph, Gmail,
//! Google Calendar and People, CalDAV, CardDAV and JMAP.
//!
//! A server that throttles answers 429 or 503, often with a
//! `Retry-After`; Google answers its per-user rate limits with a 403
//! whose reason is `rateLimitExceeded` or `userRateLimitExceeded`.
//! Each of those means the request was not served, so it is sent
//! again after a wait rather than failing the sync pass.
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
    sync::Mutex,
    time::{Duration, Instant},
};

use jiff::{Timestamp, fmt::rfc2822::DateTimeParser};

use crate::{client::Client, types::BridgeError};

/// How many times one request is sent again after a throttled answer.
const MAX_RETRIES: u32 = 4;

/// How long one native call waits on throttling servers in total.
/// Past it, the throttled answer is the call's answer.
pub(crate) const WAIT_BUDGET: Duration = Duration::from_secs(30);

/// The first back-off when the server names no `Retry-After`, doubled
/// on each retry up to [`BACKOFF_MAX`], each wait jittered down to half
/// of it so workers throttled together do not come back together.
const BACKOFF_BASE: Duration = Duration::from_secs(1);

/// The longest single back-off when the server names no `Retry-After`.
const BACKOFF_MAX: Duration = Duration::from_secs(16);

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

/// The next free Gmail slot, shared by every worker.
static GMAIL_PACE: Mutex<Option<Instant>> = Mutex::new(None);

/// The request in flight on a [`Client`]: what was written, to which
/// URL, and whether its answer started.
#[derive(Default)]
pub(crate) struct Exchange {
    url: String,
    request: Vec<u8>,
    answering: bool,
}

/// HTTP exchange with throttling, used by every HTTP runner in place of
/// the bare transport `read` and `write`.
impl<'a, 'local> Client<'a, 'local> {
    /// Writes request bytes to `url`, recording them so a throttled
    /// answer can be retried. A write after the answer started, or to
    /// another URL, opens a new request.
    pub(crate) fn http_write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        if self.exchange.answering || self.exchange.url != url {
            self.exchange.url.clear();
            self.exchange.url.push_str(url);
            self.exchange.request.clear();
            self.exchange.answering = false;
        }
        self.exchange.request.extend_from_slice(bytes);
        self.write(url, bytes)
    }

    /// Reads the next chunk of the answer from `url`. The first read of
    /// an answer reads its head, and the whole of it when it may be a
    /// throttled one, retrying that request within the bounds.
    pub(crate) fn http_read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        let fresh = !self.exchange.answering
            && self.exchange.url == url
            && !self.exchange.request.is_empty()
            && !self.exchange.request.starts_with(b"HEAD ");
        if !fresh {
            return self.read(url);
        }
        self.exchange.answering = true;

        let mut retries = 0;
        loop {
            let (bytes, retry_after) = match self.read_answer(url)? {
                Answered::Passed(bytes) => return Ok(bytes),
                Answered::Throttled { bytes, retry_after } => (bytes, retry_after),
            };

            let delay = match retry_after {
                Some(delay) => delay,
                None => backoff(retries, rand::random_range(0.5..=1.0)),
            };
            let left = WAIT_BUDGET.saturating_sub(self.waited);
            if retries >= MAX_RETRIES || delay > left {
                log::warn!("{url} still throttled, giving up after {retries} retries");
                return Ok(bytes);
            }

            log::warn!("{url} throttled, retrying in {}ms", delay.as_millis());
            std::thread::sleep(delay);
            self.waited += delay;
            retries += 1;

            let request = std::mem::take(&mut self.exchange.request);
            let written = self.write(url, &request);
            self.exchange.request = request;
            written?;
        }
    }

    /// Reads one answer's head, and its body when the head says it may
    /// be throttled, deciding whether it is.
    fn read_answer(&mut self, url: &str) -> Result<Answered, BridgeError> {
        let mut bytes = Vec::new();

        let head_end = loop {
            if let Some(end) = head_end(&bytes) {
                break end;
            }
            if bytes.len() > HEAD_CAP {
                return Ok(Answered::Passed(bytes));
            }
            let chunk = self.read(url)?;
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

        if !throttled(head.status, &body) {
            return Ok(Answered::Passed(bytes));
        }

        let retry_after = head
            .retry_after
            .as_deref()
            .and_then(|value| retry_after(value, Timestamp::now()));
        Ok(Answered::Throttled { bytes, retry_after })
    }
}

/// Holds one Gmail request until its slot under
/// [`GMAIL_REQUESTS_PER_SECOND`] comes.
pub(crate) fn pace_gmail() {
    let wait = {
        let mut next = GMAIL_PACE.lock().unwrap_or_else(|err| err.into_inner());
        let (wait, after) = pace(
            *next,
            Instant::now(),
            GMAIL_REQUESTS_PER_SECOND,
            GMAIL_BURST,
        );
        *next = Some(after);
        wait
    };

    if !wait.is_zero() {
        std::thread::sleep(wait);
    }
}

/// One step of a paced stream (the generic cell rate algorithm): given
/// the next free slot and now, how long this request waits and the
/// slot after it. Up to `burst` requests go out at once after a pause.
fn pace(next: Option<Instant>, now: Instant, rate: u32, burst: u32) -> (Duration, Instant) {
    let interval = Duration::from_secs(1) / rate;
    let earliest = now.checked_sub(interval * burst).unwrap_or(now);
    let slot = next.map_or(now, |next| next.max(earliest));

    (slot.saturating_duration_since(now), slot + interval)
}

/// What the first read of an answer found.
enum Answered {
    /// Anything but a throttled answer: handed to the coroutine as is.
    Passed(Vec<u8>),
    /// A throttled answer, read to its end, and the wait it asked for.
    Throttled {
        bytes: Vec<u8>,
        retry_after: Option<Duration>,
    },
}

/// Whether a complete answer says the request was throttled: a 429 or
/// a 503, or one of Google's rate-limit 403s (a 403 for anything else,
/// a daily quota included, is no reason to wait).
fn throttled(status: u16, body: &[u8]) -> bool {
    match status {
        429 | 503 => true,
        403 => contains(body, b"rateLimitExceeded") || contains(body, b"userRateLimitExceeded"),
        _ => false,
    }
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
        assert!(throttled(429, b""));
        assert!(throttled(503, b""));
        assert!(throttled(
            403,
            br#"{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"#
        ));
        assert!(throttled(403, br#"{"reason":"rateLimitExceeded"}"#));
        assert!(!throttled(403, br#"{"reason":"dailyLimitExceeded"}"#));
        assert!(!throttled(403, b"Forbidden"));
        assert!(!throttled(500, b""));
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
            let (wait, after) = pace(next, start, GMAIL_REQUESTS_PER_SECOND, GMAIL_BURST);
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
            let (wait, after) = pace(next, later, GMAIL_REQUESTS_PER_SECOND, GMAIL_BURST);
            free += u32::from(wait.is_zero());
            next = Some(after);
        }
        assert_eq!(free, GMAIL_BURST + 1);
    }
}
