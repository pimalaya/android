//! Calendar reading: one VEVENT's occurrences inside a civil window.
//!
//! The parsing half is ical-rs's decoded model, the recurrence half its
//! `recur` feature. Both work in civil (wall-clock) time, so nothing
//! here resolves a UTC offset: an event at 09:00 recurs at 09:00, and
//! the agenda compares those stamps against a civil window. Time zones
//! become the caller's concern only when an absolute instant is needed,
//! which an agenda never needs.

use ical::{
    component::{IcalComponent, IcalComponentKind, IcalComponentName},
    prop::{IcalProp, IcalPropKind, IcalPropName},
    recur::{IcalRecurDateTime, IcalRecurRule, expand::IcalRecurExpand},
    tree::cst::IcalCst,
    value::IcalValue,
};
use serde::Serialize;

use crate::types::BridgeError;

/// How many occurrences one event may contribute to a window.
///
/// A window is a month at most, so a legitimate rule cannot exceed this
/// even at `FREQ=HOURLY`; the cap is what keeps a `FREQ=SECONDLY` event
/// from filling the agenda with a million rows.
const MAX_OCCURRENCES: usize = 1024;

/// One rendered occurrence of an event.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Occurrence {
    /// Civil start, `YYYYMMDDTHHMMSS`.
    pub start: String,
    /// Civil end, `YYYYMMDDTHHMMSS`; equals the start on a zero-length event.
    pub end: String,
    /// `SUMMARY`, empty when the event carries none.
    pub summary: String,
    /// `LOCATION`, empty when the event carries none.
    pub location: String,
    /// Whether `DTSTART` is a DATE rather than a DATE-TIME.
    pub all_day: bool,
}

/// Expands every VEVENT of one calendar object into the occurrences
/// falling inside `[from, until)`, both civil `YYYYMMDD` or
/// `YYYYMMDDTHHMMSS` stamps.
///
/// An event with no RRULE yields at most its single instance. Overrides
/// (`RECURRENCE-ID`) and exceptions (`EXDATE`) are not composed yet, so
/// an exception date still renders; that gap is tracked in the plan.
pub fn expand(ical: &str, from: &str, until: &str) -> Result<Vec<Occurrence>, BridgeError> {
    let from = IcalRecurDateTime::parse(from).map_err(|err| err.to_string())?;
    let until = IcalRecurDateTime::parse(until).map_err(|err| err.to_string())?;

    let cst = IcalCst::parse(ical).map_err(|err| err.to_string())?;
    let decoded = cst.decode();

    let mut occurrences = Vec::new();
    for component in &decoded.components {
        collect(component, from, until, &mut occurrences);
    }

    occurrences.sort_by(|left, right| left.start.cmp(&right.start));
    Ok(occurrences)
}

/// Walks a component tree, expanding every VEVENT it holds.
fn collect(
    component: &IcalComponent,
    from: IcalRecurDateTime,
    until: IcalRecurDateTime,
    out: &mut Vec<Occurrence>,
) {
    if is_kind(&component.name, IcalComponentKind::VEvent) {
        expand_event(component, from, until, out);
    }

    // NOTE: VEVENTs nest only VALARMs, but a VCALENDAR arriving inside
    // another component (some servers wrap) would otherwise be missed.
    for nested in &component.components {
        collect(nested, from, until, out);
    }
}

fn expand_event(
    event: &IcalComponent,
    from: IcalRecurDateTime,
    until: IcalRecurDateTime,
    out: &mut Vec<Occurrence>,
) {
    let Some(raw_start) = text_of(event, IcalPropKind::DtStart) else {
        return;
    };
    let Ok(start) = IcalRecurDateTime::parse(&raw_start) else {
        return;
    };

    let all_day = raw_start.len() == 8;
    let summary = text_of(event, IcalPropKind::Summary).unwrap_or_default();
    let location = text_of(event, IcalPropKind::Location).unwrap_or_default();

    // The event's length, carried forward onto every occurrence: RFC
    // 5545 recurs the start and keeps the duration, so only the start
    // needs expanding.
    let length = text_of(event, IcalPropKind::DtEnd)
        .and_then(|raw| IcalRecurDateTime::parse(&raw).ok())
        .map(|end| seconds_between(start, end))
        .unwrap_or(0);

    match text_of(event, IcalPropKind::RRule) {
        None => {
            if start >= from && start < until {
                out.push(occurrence(start, length, &summary, &location, all_day));
            }
        }
        Some(raw_rule) => {
            let Ok(rule) = IcalRecurRule::parse(&raw_rule) else {
                return;
            };
            for moment in IcalRecurExpand::new(rule, start)
                .take_while(|moment| *moment < until)
                .filter(|moment| *moment >= from)
                .take(MAX_OCCURRENCES)
            {
                out.push(occurrence(moment, length, &summary, &location, all_day));
            }
        }
    }
}

fn occurrence(
    start: IcalRecurDateTime,
    length: i64,
    summary: &str,
    location: &str,
    all_day: bool,
) -> Occurrence {
    Occurrence {
        start: stamp(start),
        end: stamp(shift(start, length)),
        summary: summary.to_string(),
        location: location.to_string(),
        all_day,
    }
}

/// A civil stamp, the shape the Java side sorts and slices on.
fn stamp(moment: IcalRecurDateTime) -> String {
    format!(
        "{:04}{:02}{:02}T{:02}{:02}{:02}",
        moment.year, moment.month, moment.day, moment.hour, moment.minute, moment.second
    )
}

/// Days since an arbitrary civil epoch, for the two date arithmetic
/// helpers below. Hinnant's algorithm, the same one ical-rs uses
/// internally; it is private there, and duplicating six lines beats
/// widening a library's public surface for one caller.
fn days_from_civil(year: i32, month: u8, day: u8) -> i64 {
    let month = month as i64;
    let year = year as i64 - (month <= 2) as i64;
    let era = year.div_euclid(400);
    let year_of_era = year.rem_euclid(400);
    let march_month = month + if month > 2 { -3 } else { 9 };
    let day_of_year = (153 * march_month + 2) / 5 + day as i64 - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;

    era * 146097 + day_of_era - 719468
}

fn civil_from_days(days: i64) -> (i32, u8, u8) {
    let days = days + 719468;
    let era = days.div_euclid(146097);
    let day_of_era = days.rem_euclid(146097);
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36524 - day_of_era / 146096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let march_month = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * march_month + 2) / 5 + 1;
    let month = march_month + if march_month < 10 { 3 } else { -9 };
    let year = year_of_era + era * 400 + (month <= 2) as i64;

    (year as i32, month as u8, day as u8)
}

fn to_seconds(moment: IcalRecurDateTime) -> i64 {
    days_from_civil(moment.year, moment.month, moment.day) * 86_400
        + moment.hour as i64 * 3600
        + moment.minute as i64 * 60
        + moment.second as i64
}

fn seconds_between(start: IcalRecurDateTime, end: IcalRecurDateTime) -> i64 {
    (to_seconds(end) - to_seconds(start)).max(0)
}

fn shift(moment: IcalRecurDateTime, seconds: i64) -> IcalRecurDateTime {
    let total = to_seconds(moment) + seconds;
    let rest = total.rem_euclid(86_400);
    let (year, month, day) = civil_from_days(total.div_euclid(86_400));

    IcalRecurDateTime {
        year,
        month,
        day,
        hour: (rest / 3600) as u8,
        minute: (rest / 60 % 60) as u8,
        second: (rest % 60) as u8,
    }
}

fn is_kind(name: &IcalComponentName, kind: IcalComponentKind) -> bool {
    matches!(name, IcalComponentName::Kind(found) if *found == kind)
}

/// The raw text of a property's value, whatever value type it decoded to.
fn text_of(component: &IcalComponent, kind: IcalPropKind) -> Option<String> {
    component
        .props
        .iter()
        .find(|prop| matches!(&prop.name, IcalPropName::Kind(found) if *found == kind))
        .and_then(raw_value)
}

fn raw_value(prop: &IcalProp) -> Option<String> {
    let raw = match &prop.value {
        IcalValue::Text(value) => value.0.as_ref(),
        IcalValue::DateTime(value) => value.0.as_ref(),
        IcalValue::Date(value) => value.0.as_ref(),
        IcalValue::Recur(value) => value.0.as_ref(),
        IcalValue::Duration(value) => value.0.as_ref(),
        _ => return None,
    };

    match raw.is_empty() {
        true => None,
        false => Some(raw.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn object(body: &str) -> String {
        format!("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n{body}END:VCALENDAR\r\n")
    }

    #[test]
    fn expands_a_weekly_event_inside_the_window() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:1\r\nDTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n\
             SUMMARY:Standup\r\nRRULE:FREQ=WEEKLY;BYDAY=MO\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();
        let starts: Vec<&str> = found.iter().map(|one| one.start.as_str()).collect();

        assert_eq!(
            starts,
            [
                "20260105T090000",
                "20260112T090000",
                "20260119T090000",
                "20260126T090000"
            ]
        );
        assert_eq!(found[0].summary, "Standup");
        assert_eq!(found[0].end, "20260105T100000");
        assert!(!found[0].all_day);
    }

    #[test]
    fn keeps_the_length_of_every_occurrence() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:2\r\nDTSTART:20260131T230000\r\nDTEND:20260201T003000\r\n\
             RRULE:FREQ=DAILY;COUNT=2\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260301T000000").unwrap();

        // The second occurrence crosses midnight exactly as the first does.
        assert_eq!(found[0].end, "20260201T003000");
        assert_eq!(found[1].start, "20260201T230000");
        assert_eq!(found[1].end, "20260202T003000");
    }

    #[test]
    fn windows_out_what_falls_outside() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:3\r\nDTSTART:20260105T090000\r\n\
             RRULE:FREQ=WEEKLY;BYDAY=MO\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260113T000000", "20260121T000000").unwrap();
        let starts: Vec<&str> = found.iter().map(|one| one.start.as_str()).collect();

        assert_eq!(starts, ["20260119T090000"]);
    }

    #[test]
    fn reads_a_single_all_day_event() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:4\r\nDTSTART;VALUE=DATE:20260214\r\n\
             SUMMARY:Holiday\r\nLOCATION:Home\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260201", "20260301").unwrap();

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].start, "20260214T000000");
        assert_eq!(found[0].location, "Home");
        assert!(found[0].all_day);
    }

    #[test]
    fn sorts_across_the_events_of_one_object() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:5\r\nDTSTART:20260120T090000\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:6\r\nDTSTART:20260110T090000\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();
        let starts: Vec<&str> = found.iter().map(|one| one.start.as_str()).collect();

        assert_eq!(starts, ["20260110T090000", "20260120T090000"]);
    }
}
