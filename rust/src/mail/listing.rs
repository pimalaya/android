//! # Listing
//!
//! The mail listing every mail backend answers an `enumerate` yield with
//! (pimdir SYNC §4, §5): a page of members, each named by its meta.
//!
//! Java hands the engine's request over typed ([`MailRequest`]), with what
//! only the store knows: a delta from the checkpoint, or a round over a
//! scope. The answer is one [`MailPage`], newest first, each member with
//! the Annex A summary and sort key read in the listing, so nothing
//! reaches the store unnamed.
//!
//! The scope is a floor on the `Date` header. A provider's received-date
//! filter only narrows the listing to a superset of it, taken with a
//! margin ([`Scope::received_since`]); what decides is the backend's own
//! check of the summary's date ([`Scope::contains`]), undated being in.

use io_pimdir::summary::{PimdirSummary, mail::PimdirMailSummary};
use jiff::{SignedDuration, Timestamp};
use serde::{Deserialize, Serialize};

use crate::offline::{summary::SummaryJson, wire::sorted};

/// UIDs per IMAP `UID FETCH`: `FLAGS`, `RFC822.SIZE` and a few header
/// fields are under 1 KB a message, half a megabyte a response.
pub const IMAP_PAGE: usize = 500;

/// Messages per Graph page: a message delta page (`Prefer:
/// odata.maxpagesize`, a ceiling Graph may cut shorter) and a band's
/// `/messages` page (`$top`, at most 1,000).
pub const GRAPH_PAGE: u32 = 1000;

/// Message ids per Gmail `messages.list`, the API's ceiling: the
/// account's listing a step walks and a label's alike.
pub const GMAIL_PAGE: u32 = 500;

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

/// One `enumerate` yield, as the app writes it (its `MailRequest` mirror).
#[derive(Debug, Deserialize)]
pub struct MailRequest {
    /// What is listed.
    pub listing: Listing,
    /// The `Date` range listed.
    #[serde(default)]
    pub scope: Scope,
    /// Whether the store's coverage holds the scope already.
    ///
    /// Only Graph reads it, to tell a mailbox's first chunk (listed by band)
    /// from the round that makes its delta link.
    #[serde(default)]
    pub covered: bool,
    /// The oldest reception the account holds, in epoch milliseconds.
    ///
    /// Where a Gmail round over a label lists from, so it names every
    /// message held and few others.
    #[serde(default)]
    pub received: Option<i64>,
}

/// What is listed: a delta from the checkpoint, or a round over the scope.
#[derive(Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Listing {
    /// What changed since the checkpoint, one page.
    Delta { checkpoint: String },
    /// A round over the scope.
    Round {
        /// Where the round resumes, absent for its first page.
        #[serde(default)]
        cursor: Option<String>,
        /// Whether it lists only the band a coverage lacks.
        #[serde(default)]
        band: bool,
    },
}

/// A scope `[since, until)` on the `Date` header, as RFC 3339 instants
/// in UTC; an absent bound is open.
#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq)]
pub struct Scope {
    /// The inclusive floor.
    #[serde(default)]
    pub since: Option<String>,
    /// The exclusive ceiling.
    #[serde(default)]
    pub until: Option<String>,
}

impl Scope {
    /// Whether a message dated `date` (Annex A's `date`, RFC 3339 `Z`)
    /// falls in the scope.
    ///
    /// One with no usable date is in every scope: rare, small, and never
    /// lost (SYNC §5).
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

/// The floor of a mailbox's next chunk: the `Date` of its oldest message.
///
/// A chunk is the `count` newest messages dated below a ceiling, not a span:
/// only its floor is kept, fewer than `count` dated leaving none. A source in
/// recency order [`Floor::take`]s, a sample [`Floor::offer`]s.
#[derive(Debug)]
pub struct Floor {
    until: Scope,
    count: usize,
    /// The `count` newest dates kept, newest first.
    dates: Vec<String>,
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
            dates: Vec::new(),
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
        if !self.full() {
            self.offer(date);
        }
        self.full()
    }

    /// Offers a date in no order, keeping the `count` newest below the ceiling.
    ///
    /// Answers whether it is among them. A sample (IMAP's newest UIDs) offers
    /// all, so one old message cannot drag the floor down.
    pub fn offer(&mut self, date: Option<&str>) -> bool {
        let Some(date) = date.filter(|date| !date.is_empty()) else {
            return false;
        };
        if !self.until.contains(Some(date)) {
            return false;
        }
        let at = self
            .dates
            .iter()
            .position(|held| instant(date) > instant(held))
            .unwrap_or(self.dates.len());
        if at >= self.count {
            return false;
        }
        self.dates.insert(at, date.to_string());
        self.dates.truncate(self.count);
        true
    }

    /// Whether `count` dated messages were taken.
    pub fn full(&self) -> bool {
        self.dates.len() >= self.count
    }

    /// The floor once full: the `Date` of the `count`th newest.
    pub fn floor(&self) -> Option<&str> {
        self.dates.get(self.count - 1).map(String::as_str)
    }

    /// The reply on the JSON wire: the floor once full, none otherwise.
    pub fn reply(&self) -> FloorReply {
        FloorReply {
            floor: self.floor().map(String::from),
            dated: self.dates.len(),
            dates: self.dates.clone(),
        }
    }
}

/// A chunk's floor on the JSON wire.
#[derive(Clone, Debug, Serialize)]
pub struct FloorReply {
    /// The floor, null when the mailbox is whole below the ceiling.
    pub floor: Option<String>,
    /// How many dated messages were taken.
    pub dated: usize,
    /// Their dates, newest first.
    pub dates: Vec<String>,
}

/// One page of a mail listing on the JSON wire, keys alphabetical as the
/// app's `MailPage` mirror reads them.
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MailPage {
    /// The checkpoint, on the page where the source gives one.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub checkpoint: Option<String>,
    /// Whether the page belongs to a round (true) or a delta (false).
    pub complete: bool,
    /// Where the next page of the round resumes, absent on the last.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    /// The source refused the cursor (or a delta's checkpoint): the
    /// engine restarts the round rather than this answering as if resumed.
    #[serde(skip_serializing_if = "core::ops::Not::not")]
    pub cursor_rejected: bool,
    /// The members listed.
    pub items: Vec<Named>,
    /// Whether the page closes its listing.
    pub last: bool,
    /// The handles a delta reports gone.
    pub vanished: Vec<String>,
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

/// One listed message, named by its meta, keys alphabetical as the app's
/// `Named` mirror reads them.
///
/// A member listed by id and markers alone (a Graph delta) has no summary:
/// the store keeps the one it holds where it binds the message, and the
/// driver names the others before the page reaches the engine.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Named {
    /// Its IMAP markers ([`flags`]).
    pub flags: Vec<String>,
    /// The UID within its mailbox, or the provider's message id.
    pub handle: String,
    /// The handle again: what the store files it under, a reader opens and
    /// a push addresses it by.
    pub link_id: String,
    /// The summary's sort key, empty when listed unnamed.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub sort_key: String,
    /// Its Annex A summary, absent when listed unnamed.
    #[serde(serialize_with = "sorted", skip_serializing_if = "Option::is_none")]
    pub summary: Option<SummaryJson>,
}

impl Named {
    /// A message named by the summary its listing read.
    pub fn new(handle: String, flags: Vec<String>, summary: PimdirMailSummary) -> Self {
        let sort_key = summary.sort_key().0;
        Self {
            link_id: handle.clone(),
            handle,
            flags,
            summary: Some(SummaryJson::from(&PimdirSummary::Mail(summary))),
            sort_key,
        }
    }

    /// A message listed by id and markers alone, to be named by its
    /// summary where the store does not bind it yet.
    pub fn unnamed(handle: String, flags: Vec<String>) -> Self {
        Self {
            link_id: handle.clone(),
            handle,
            flags,
            summary: None,
            sort_key: String::new(),
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
    use crate::mail::listing::*;

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

    /// Sixty daily messages, newest first, an undated one counting for none.
    #[test]
    fn the_floor_is_the_date_of_the_fiftieth_newest() {
        let top: Timestamp = "2026-10-01T08:00:00Z".parse().unwrap();
        let day = |back: i64| {
            top.checked_sub(SignedDuration::from_hours(24 * back))
                .unwrap()
                .to_string()
        };

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
    fn a_sample_keeps_the_newest_by_date_whatever_its_order() {
        let mut floor = Floor::new(Some("2026-10-01T00:00:00Z"), 3);
        assert!(floor.offer(Some("2019-03-01T00:00:00Z")), "kept while room");
        assert!(floor.offer(Some("2026-09-10T08:00:00Z")));
        assert!(floor.offer(Some("2026-09-20T00:00:00Z")));
        assert!(floor.offer(Some("2026-09-09T20:00:00Z")), "the import goes");
        assert!(
            !floor.offer(Some("2018-01-01T00:00:00Z")),
            "below the third"
        );
        assert!(
            !floor.offer(Some("2026-10-05T00:00:00Z")),
            "above the ceiling"
        );
        assert!(!floor.offer(None));

        let reply = floor.reply();
        assert_eq!(reply.floor.as_deref(), Some("2026-09-09T20:00:00Z"));
        assert_eq!(
            reply.dates,
            [
                "2026-09-20T00:00:00Z",
                "2026-09-10T08:00:00Z",
                "2026-09-09T20:00:00Z"
            ]
        );
    }

    #[test]
    fn fewer_than_the_chunk_leaves_no_floor() {
        let mut floor = Floor::new(Some("2026-10-01T00:00:00Z"), 50);
        for day in 1..=3 {
            floor.take(Some(&format!("2026-09-{day:02}T00:00:00Z")));
        }
        let reply = floor.reply();
        assert_eq!(reply.floor, None, "the mailbox is whole below the ceiling");
        assert_eq!(reply.dated, 3);
        assert_eq!(
            serde_json::to_string(&reply).unwrap(),
            r#"{"floor":null,"dated":3,"dates":["2026-09-03T00:00:00Z","2026-09-02T00:00:00Z","2026-09-01T00:00:00Z"]}"#
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

    #[test]
    fn a_page_is_written_as_the_app_mirrors_it() {
        let summary = PimdirMailSummary {
            message_id: Some("m1@example.org".into()),
            subject: "Hi".into(),
            date: Some("2026-10-01T08:00:00Z".into()),
            ..Default::default()
        };
        let items = vec![
            Named::new("7".into(), flags(true, false, false), summary),
            Named::unnamed("8".into(), Vec::new()),
        ];
        let round = MailPage::round(items, Some("7:8".into()), None);
        assert_eq!(
            serde_json::to_string(&round).unwrap(),
            concat!(
                r#"{"complete":true,"cursor":"7:8","items":[{"flags":["\\Seen"],"handle":"7","#,
                r#""linkId":"7","sortKey":"2026-10-01T08:00:00Z","summary":{"mail":{"addresses":[],"#,
                r#""date":"2026-10-01T08:00:00Z","in_reply_to":[],"message_id":"m1@example.org","#,
                r#""subject":"Hi"}}},{"flags":[],"handle":"8","linkId":"8"}],"last":false,"#,
                r#""vanished":[]}"#
            )
        );

        let delta = MailPage::delta(Vec::new(), vec!["6".into()], "1:42".into());
        assert_eq!(
            serde_json::to_string(&delta).unwrap(),
            r#"{"checkpoint":"1:42","complete":false,"items":[],"last":true,"vanished":["6"]}"#
        );
        assert_eq!(
            serde_json::to_string(&MailPage::rejected()).unwrap(),
            r#"{"complete":false,"cursorRejected":true,"items":[],"last":false,"vanished":[]}"#
        );
    }
}
