//! The mail listing every mail connector answers an `enumerate` yield
//! with (pimdir SYNC §4, §5): a page of members, each named by its meta.
//!
//! Java forwards the engine's request as it came ([`MailRequest`]): a
//! delta from the checkpoint, or a round over a scope from its first page
//! or resumed from a cursor. The connector answers one [`MailPage`],
//! newest first in the source's own recency order, every member carrying
//! the summary and sort key of STORAGE Annex A read in the listing itself,
//! so nothing reaches the store unnamed and no probe is left to upgrade.
//!
//! The scope is a floor on the `Date` header. A provider's received-date
//! filter only narrows the listing to a superset of it, taken with a
//! margin ([`Scope::received_since`]); what decides is the connector's own
//! check of the summary's date ([`Scope::contains`]), a message with no
//! usable date being in scope.

use io_pimdir::summary::{PimdirSummary, mail::PimdirMailSummary};
use jiff::{SignedDuration, Timestamp};
use serde::{Deserialize, Serialize};

use crate::summary::SummaryJson;

/// UIDs per IMAP `UID FETCH`: `FLAGS`, `RFC822.SIZE` and a few header
/// fields are under 1 KB a message, half a megabyte a response.
pub const IMAP_PAGE: usize = 500;

/// Messages per Graph message delta page (`Prefer: odata.maxpagesize`),
/// honoured with the summary `$select`.
pub const GRAPH_PAGE: u32 = 1000;

/// Message ids per Gmail `messages.list`, each read for its metadata
/// under the account's pacing.
pub const GMAIL_PAGE: u32 = 100;

/// Emails per JMAP `Email/query`, capped by the server's own
/// `maxObjectsInGet`.
pub const JMAP_PAGE: u64 = 500;

/// The margin a received-date filter is widened by below the scope's
/// floor: clock skew, time zones, a `Date` written before the message was
/// received, Gmail's whole PST days.
pub const RECEIVED_MARGIN_DAYS: i64 = 2;

/// The margin IMAP's `SENTSINCE` and `SENTBEFORE` take, which compare
/// whole days in the sender's zone.
pub const SENT_MARGIN_DAYS: i64 = 1;

/// One `enumerate` yield, as the engine asked it.
#[derive(Debug, Deserialize)]
pub struct MailRequest {
    pub listing: Listing,
    #[serde(default)]
    pub scope: Scope,
}

/// What is listed: a delta from the checkpoint, or a round over the
/// scope.
#[derive(Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Listing {
    /// What changed since the checkpoint, one page.
    Delta { checkpoint: String },
    /// A round over the scope, from its first page when `cursor` is
    /// absent; `band` when it lists only the band a coverage lacks.
    Round {
        #[serde(default)]
        cursor: Option<String>,
        #[serde(default)]
        band: bool,
    },
}

/// A scope `[since, until)` on the `Date` header, as RFC 3339 instants
/// in UTC; an absent bound is open.
#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq)]
pub struct Scope {
    #[serde(default)]
    pub since: Option<String>,
    #[serde(default)]
    pub until: Option<String>,
}

impl Scope {
    /// Whether a message dated `date` (Annex A's `date`, RFC 3339 `Z`)
    /// falls in the scope. A message with no usable date is in every
    /// scope: rare, small, and never lost (SYNC §5).
    pub fn contains(&self, date: Option<&str>) -> bool {
        let Some(date) = date.filter(|date| !date.is_empty()) else {
            return true;
        };
        let after = self
            .since
            .as_deref()
            .is_none_or(|since| instant(date) >= instant(since));
        let before = self
            .until
            .as_deref()
            .is_none_or(|until| instant(date) < instant(until));
        after && before
    }

    /// The floor a received-date filter narrows a listing by, `days`
    /// below the scope's, or [`None`] for no floor.
    pub fn received_since(&self, days: i64) -> Option<Timestamp> {
        let since = self.since.as_deref().and_then(parse)?;
        since
            .checked_sub(SignedDuration::from_hours(24 * days))
            .ok()
    }

    /// The ceiling a received-date filter narrows a listing by, `days`
    /// above the scope's, or [`None`] for no ceiling.
    pub fn received_until(&self, days: i64) -> Option<Timestamp> {
        let until = self.until.as_deref().and_then(parse)?;
        until
            .checked_add(SignedDuration::from_hours(24 * days))
            .ok()
    }

    /// The scope as one string, telling one listing's cached state from
    /// another's.
    pub fn key(&self) -> String {
        format!(
            "{}..{}",
            self.since.as_deref().unwrap_or_default(),
            self.until.as_deref().unwrap_or_default()
        )
    }
}

/// An RFC 3339 instant, or [`None`] when it does not parse.
fn parse(raw: &str) -> Option<Timestamp> {
    raw.parse().ok()
}

/// An instant compared as one, falling back to its text: two dates
/// written the same way compare the same either way.
fn instant(raw: &str) -> Result<Timestamp, &str> {
    parse(raw).ok_or(raw)
}

/// An RFC 3339 instant of any offset as Annex A writes `date`: UTC, at
/// seconds, with the `Z` designator. [`None`] when it does not parse.
pub fn utc(raw: &str) -> Option<String> {
    parse(raw).map(|timestamp| timestamp.strftime("%Y-%m-%dT%H:%M:%SZ").to_string())
}

/// The floor of a mailbox's next chunk: the `count` newest messages dated
/// before a ceiling, and the oldest `Date` among them.
///
/// A chunk is a number of messages, never a span of time: the floor a
/// chunk leaves is all that is kept, and the next chunk is the `count`
/// newest below it. A message with no usable date counts for nothing,
/// being in every scope already. Fewer than `count` dated messages below
/// the ceiling leaves no floor: the mailbox is whole below it.
///
/// "Newest" is the source's own recency order (UIDs, Gmail's list, JMAP's
/// `receivedAt`, Graph's `sentDateTime`), the floor the oldest `Date`
/// among what that order names, so the scope from it holds at least those.
#[derive(Debug)]
pub struct Floor {
    until: Scope,
    count: usize,
    taken: usize,
    oldest: Option<String>,
}

impl Floor {
    /// A floor below `before` (RFC 3339 `Z`), or below nothing.
    pub fn new(before: Option<&str>, count: usize) -> Self {
        Self {
            until: Scope {
                since: None,
                until: before.filter(|before| !before.is_empty()).map(String::from),
            },
            count: count.max(1),
            taken: 0,
            oldest: None,
        }
    }

    /// The scope the chunk is read in: everything below the ceiling.
    pub fn scope(&self) -> &Scope {
        &self.until
    }

    /// How many messages the chunk takes.
    pub fn count(&self) -> usize {
        self.count
    }

    /// Takes the next message's date in the source's recency order,
    /// answering whether the chunk is full.
    pub fn take(&mut self, date: Option<&str>) -> bool {
        if self.full() {
            return true;
        }
        let Some(date) = date.filter(|date| !date.is_empty()) else {
            return false;
        };
        if !self.until.contains(Some(date)) {
            return false;
        }
        self.taken += 1;
        let older = self
            .oldest
            .as_deref()
            .is_none_or(|oldest| instant(date) < instant(oldest));
        if older {
            self.oldest = Some(date.to_string());
        }
        self.full()
    }

    /// Whether `count` dated messages were taken.
    pub fn full(&self) -> bool {
        self.taken >= self.count
    }

    /// The reply on the JSON wire: the floor once full, none otherwise.
    pub fn reply(&self) -> FloorReply {
        FloorReply {
            floor: self.full().then(|| self.oldest.clone()).flatten(),
            dated: self.taken,
        }
    }
}

/// A chunk's floor on the JSON wire: `floor` null when the mailbox is
/// whole below the ceiling, `dated` how many dated messages were taken.
#[derive(Debug, Serialize)]
pub struct FloorReply {
    pub floor: Option<String>,
    pub dated: usize,
}

/// One page of a mail listing on the JSON wire.
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MailPage {
    /// The source refused the cursor (or a delta's checkpoint): the
    /// engine restarts the round rather than this answering as if it
    /// resumed.
    #[serde(skip_serializing_if = "core::ops::Not::not")]
    pub cursor_rejected: bool,
    pub items: Vec<Named>,
    pub vanished: Vec<String>,
    /// Whether the page belongs to a round (true) or a delta (false).
    pub complete: bool,
    /// Whether the page closes its listing.
    pub last: bool,
    /// Where the next page of the round resumes, absent on the last.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    /// The checkpoint, on the page where the source gives one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub checkpoint: Option<String>,
}

impl MailPage {
    /// The answer to a cursor the source refused.
    pub fn rejected() -> Self {
        Self {
            cursor_rejected: true,
            ..Default::default()
        }
    }

    /// One page of a round, the last when `cursor` is [`None`].
    pub fn round(items: Vec<Named>, cursor: Option<String>, checkpoint: Option<String>) -> Self {
        Self {
            items,
            vanished: Vec::new(),
            complete: true,
            last: cursor.is_none(),
            cursor,
            checkpoint,
            ..Default::default()
        }
    }

    /// A delta: what changed since the checkpoint, and what went.
    pub fn delta(items: Vec<Named>, vanished: Vec<String>, checkpoint: String) -> Self {
        Self {
            items,
            vanished,
            complete: false,
            last: true,
            cursor: None,
            checkpoint: Some(checkpoint),
            ..Default::default()
        }
    }
}

/// One listed message, named by its meta.
///
/// The link id is the handle: a message's UID within its mailbox, or the
/// provider's message id, is what this store has always filed it under,
/// and what a reader opens and a push addresses it by.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Named {
    pub handle: String,
    pub flags: Vec<String>,
    pub link_id: String,
    pub summary: SummaryJson,
    pub sort_key: String,
}

impl Named {
    /// A message named by the summary its listing read.
    pub fn new(handle: String, flags: Vec<String>, summary: PimdirMailSummary) -> Self {
        let sort_key = summary.sort_key().0;
        Self {
            link_id: handle.clone(),
            handle,
            flags,
            summary: SummaryJson::from(&PimdirSummary::Mail(summary)),
            sort_key,
        }
    }
}

/// The IMAP markers a provider's message carries, named the IMAP way.
pub fn flags(seen: bool, answered: bool, flagged: bool) -> Vec<String> {
    let mut flags = Vec::new();
    if seen {
        flags.push(String::from("\\Seen"));
    }
    if answered {
        flags.push(String::from("\\Answered"));
    }
    if flagged {
        flags.push(String::from("\\Flagged"));
    }
    flags
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scope(since: Option<&str>, until: Option<&str>) -> Scope {
        Scope {
            since: since.map(String::from),
            until: until.map(String::from),
        }
    }

    #[test]
    fn the_date_header_decides_and_none_is_in_scope() {
        let bounded = scope(Some("2026-04-01T00:00:00Z"), None);

        assert!(bounded.contains(Some("2026-04-01T00:00:00Z")));
        assert!(bounded.contains(Some("2026-10-07T08:00:00Z")));
        assert!(!bounded.contains(Some("2026-03-31T23:59:59Z")));
        assert!(bounded.contains(None), "no usable date is never lost");
        assert!(bounded.contains(Some("")));

        let band = scope(Some("2026-01-01T00:00:00Z"), Some("2026-04-01T00:00:00Z"));
        assert!(band.contains(Some("2026-02-01T00:00:00Z")));
        assert!(
            !band.contains(Some("2026-04-01T00:00:00Z")),
            "the ceiling is exclusive"
        );
        assert!(Scope::default().contains(Some("1970-01-01T00:00:00Z")));
    }

    #[test]
    fn a_received_filter_takes_its_margin() {
        let bounded = scope(Some("2026-04-03T00:00:00Z"), Some("2026-05-01T00:00:00Z"));

        assert_eq!(
            bounded
                .received_since(RECEIVED_MARGIN_DAYS)
                .unwrap()
                .to_string(),
            "2026-04-01T00:00:00Z"
        );
        assert_eq!(
            bounded
                .received_until(SENT_MARGIN_DAYS)
                .unwrap()
                .to_string(),
            "2026-05-02T00:00:00Z"
        );
        assert!(Scope::default().received_since(2).is_none());
    }

    #[test]
    fn an_offset_instant_is_written_in_utc() {
        assert_eq!(
            utc("2026-10-07T10:00:00+02:00").as_deref(),
            Some("2026-10-07T08:00:00Z")
        );
        assert_eq!(
            utc("2026-10-07T08:00:00.123Z").as_deref(),
            Some("2026-10-07T08:00:00Z")
        );
        assert_eq!(utc("yesterday"), None);
    }

    #[test]
    fn the_floor_is_the_date_of_the_fiftieth_newest() {
        let top: Timestamp = "2026-10-01T08:00:00Z".parse().unwrap();
        let day = |back: i64| {
            top.checked_sub(SignedDuration::from_hours(24 * back))
                .unwrap()
                .to_string()
        };

        // NOTE: 60 messages newest first, one a day, and an undated one
        // among them that counts for nothing.
        let mut floor = Floor::new(None, 50);
        for back in 0..60 {
            if back == 10 {
                assert!(!floor.take(None));
            }
            if floor.take(Some(&day(back))) {
                break;
            }
        }
        let reply = floor.reply();
        assert_eq!(reply.dated, 50);
        assert_eq!(reply.floor, Some(day(49)));
    }

    #[test]
    fn a_date_out_of_order_still_lowers_the_floor() {
        let mut floor = Floor::new(Some("2026-10-01T00:00:00Z"), 3);
        assert!(
            !floor.take(Some("2026-10-05T00:00:00Z")),
            "above the ceiling"
        );
        assert!(!floor.take(Some("2026-09-20T00:00:00Z")));
        assert!(!floor.take(Some("2026-09-01T00:00:00Z")));
        assert!(floor.take(Some("2026-09-10T00:00:00Z")));
        assert_eq!(
            floor.reply().floor.as_deref(),
            Some("2026-09-01T00:00:00Z"),
            "the oldest, not the last taken"
        );
    }

    #[test]
    fn fewer_than_the_chunk_leaves_no_floor() {
        let mut floor = Floor::new(Some("2026-10-01T00:00:00Z"), 50);
        for day in 1..=20 {
            floor.take(Some(&format!("2026-09-{day:02}T00:00:00Z")));
        }
        let reply = floor.reply();
        assert_eq!(reply.floor, None, "the mailbox is whole below the ceiling");
        assert_eq!(reply.dated, 20);
        assert_eq!(
            serde_json::to_string(&reply).unwrap(),
            r#"{"floor":null,"dated":20}"#
        );
    }

    #[test]
    fn a_request_reads_both_listings() {
        let delta: MailRequest = serde_json::from_str(
            r#"{"listing":{"kind":"delta","checkpoint":"1:42"},"scope":{"since":"2026-04-01T00:00:00Z"}}"#,
        )
        .unwrap();
        assert!(matches!(delta.listing, Listing::Delta { ref checkpoint } if checkpoint == "1:42"));
        assert_eq!(delta.scope.since.as_deref(), Some("2026-04-01T00:00:00Z"));

        let round: MailRequest =
            serde_json::from_str(r#"{"listing":{"kind":"round","cursor":null,"band":false}}"#)
                .unwrap();
        assert!(matches!(
            round.listing,
            Listing::Round {
                cursor: None,
                band: false
            }
        ));
        assert_eq!(round.scope, Scope::default());
    }
}
