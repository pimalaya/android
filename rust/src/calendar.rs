//! Calendar reading: one calendar object's occurrences inside a civil
//! window.
//!
//! The parsing half is ical-rs's decoded model, the recurrence half its
//! `recur` feature. Both work in civil (wall-clock) time, so nothing
//! here resolves a UTC offset: an event at 09:00 recurs at 09:00, and
//! the agenda compares those stamps against a civil window. Time zones
//! become the caller's concern only when an absolute instant is needed,
//! which an agenda never needs.

use ical::{
    component::{IcalComponent, IcalComponentKind, IcalComponentName},
    param::IcalParam,
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

/// The components an agenda places on a day: everything RFC 5545 dates,
/// minus the two that describe rather than schedule (VFREEBUSY reports
/// busy time, VTIMEZONE defines offsets).
const SCHEDULED: [IcalComponentKind; 3] = [
    IcalComponentKind::VEvent,
    IcalComponentKind::VTodo,
    IcalComponentKind::VJournal,
];

/// One rendered occurrence of a calendar component.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Occurrence {
    /// The component this came out of, as its wire name: `VEVENT`,
    /// `VTODO` or `VJOURNAL`. An agenda row shows all three the same
    /// way apart from its glyph, so the kind crosses rather than three
    /// separate lists.
    pub component: String,
    /// Civil start, `YYYYMMDDTHHMMSS`.
    pub start: String,
    /// Civil end, `YYYYMMDDTHHMMSS`; equals the start on a zero-length
    /// component, which is every journal entry and most to-dos.
    pub end: String,
    /// `SUMMARY`, empty when the component carries none.
    pub summary: String,
    /// `LOCATION`, empty when the component carries none.
    pub location: String,
    /// Whether the start is a DATE rather than a DATE-TIME.
    pub all_day: bool,
}

/// One calendar component read whole, for the page that shows it.
///
/// A projection rather than the object: everything RFC 5545 lets a
/// VEVENT, VTODO or VJOURNAL carry that a reader would look for, in the
/// one shape all three share, with the properties only one of them has
/// left empty by the other two. One struct and not three, because the
/// page differs by which sections it draws and not by what it can be
/// handed.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventDetail {
    /// The component's wire name: `VEVENT`, `VTODO` or `VJOURNAL`.
    pub component: String,
    /// `UID`, the identity every replica of this component shares.
    pub uid: String,
    /// `SUMMARY`, empty when the component carries none.
    pub summary: String,
    /// `DESCRIPTION`, empty when the component carries none.
    pub description: String,
    /// `LOCATION`, empty when the component carries none.
    pub location: String,
    /// `URL`, empty when the component carries none.
    pub url: String,
    /// `STATUS`, as written: the vocabulary differs per component.
    pub status: String,
    /// `CATEGORIES`, comma separated as the property spells them.
    pub categories: String,
    /// `DTSTART`, raw; empty on a to-do that carries only a `DUE`.
    pub start: String,
    /// `DTEND`, raw; only a VEVENT has one.
    pub end: String,
    /// `DUE`, raw; only a VTODO has one.
    pub due: String,
    /// `COMPLETED`, raw; only a VTODO has one.
    pub completed: String,
    /// Whether the placing date is a DATE rather than a DATE-TIME.
    pub all_day: bool,
    /// `RRULE`, raw, empty when the component does not repeat.
    pub recurrence: String,
    /// `PRIORITY`, as written.
    pub priority: String,
    /// `PERCENT-COMPLETE`, as written; only a VTODO has one.
    pub percent_complete: String,
    /// `ORGANIZER`, the address alone.
    pub organizer: String,
    /// Every `ATTENDEE`, in the order the object lists them.
    pub attendees: Vec<EventAttendee>,
    /// `CREATED`, raw.
    pub created: String,
    /// `LAST-MODIFIED`, raw.
    pub last_modified: String,
}

/// One `ATTENDEE` of a component: who, and where they stand.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventAttendee {
    /// The `CN` parameter, empty when the property carries none.
    pub name: String,
    /// The calendar user address, `mailto:` stripped.
    pub address: String,
    /// The `PARTSTAT` parameter, empty when the property carries none.
    pub status: String,
}

/// Reads the first scheduled component of one calendar object whole.
///
/// The first rather than a chosen one: a stored object is one component
/// plus whatever overrides its recurrence, and the overrides describe
/// single instances of the same thing. Which instance a reader opened
/// is the caller's to say, and it says it with the occurrence it
/// already has.
pub fn read(ical: &str) -> Result<EventDetail, BridgeError> {
    let cst = IcalCst::parse(ical).map_err(|err| err.to_string())?;
    let decoded = cst.decode();

    let mut found = Vec::new();
    for component in &decoded.components {
        collect_scheduled(component, &mut found);
    }

    let (component, kind) = found
        .first()
        .copied()
        .ok_or_else(|| BridgeError::from("The object holds nothing to show"))?;

    let start = start_of(component, kind).unwrap_or_default();

    Ok(EventDetail {
        component: kind.to_string(),
        uid: text_of(component, IcalPropKind::Uid).unwrap_or_default(),
        summary: text_of(component, IcalPropKind::Summary).unwrap_or_default(),
        description: text_of(component, IcalPropKind::Description).unwrap_or_default(),
        location: text_of(component, IcalPropKind::Location).unwrap_or_default(),
        url: text_of(component, IcalPropKind::Url).unwrap_or_default(),
        status: text_of(component, IcalPropKind::Status).unwrap_or_default(),
        categories: text_of(component, IcalPropKind::Categories).unwrap_or_default(),
        all_day: start.len() == 8,
        start,
        end: text_of(component, IcalPropKind::DtEnd).unwrap_or_default(),
        due: text_of(component, IcalPropKind::Due).unwrap_or_default(),
        completed: text_of(component, IcalPropKind::Completed).unwrap_or_default(),
        recurrence: text_of(component, IcalPropKind::RRule).unwrap_or_default(),
        priority: text_of(component, IcalPropKind::Priority).unwrap_or_default(),
        percent_complete: text_of(component, IcalPropKind::PercentComplete).unwrap_or_default(),
        organizer: address_of(text_of(component, IcalPropKind::Organizer).unwrap_or_default()),
        attendees: attendees_of(component),
        created: text_of(component, IcalPropKind::Created).unwrap_or_default(),
        last_modified: text_of(component, IcalPropKind::LastModified).unwrap_or_default(),
    })
}

/// Every scheduled component of a tree, outermost first.
fn collect_scheduled<'a>(
    component: &'a IcalComponent<'a>,
    out: &mut Vec<(&'a IcalComponent<'a>, IcalComponentKind)>,
) {
    for kind in SCHEDULED {
        if is_kind(&component.name, kind) {
            out.push((component, kind));
        }
    }

    for nested in &component.components {
        collect_scheduled(nested, out);
    }
}

/// Every `ATTENDEE` of a component, name and standing included.
fn attendees_of(component: &IcalComponent) -> Vec<EventAttendee> {
    component
        .props
        .iter()
        .filter(|prop| matches!(&prop.name, IcalPropName::Kind(IcalPropKind::Attendee)))
        .map(|prop| EventAttendee {
            name: param_of(prop, |param| match param {
                IcalParam::Cn(value) => Some(value.to_string()),
                _ => None,
            }),
            address: address_of(raw_value(prop).unwrap_or_default()),
            status: param_of(prop, |param| match param {
                IcalParam::PartStat(value) => Some(value.to_string()),
                _ => None,
            }),
        })
        .collect()
}

/// The first parameter a picker matches, empty when none does.
fn param_of(prop: &IcalProp, pick: impl Fn(&IcalParam) -> Option<String>) -> String {
    prop.params.iter().find_map(pick).unwrap_or_default()
}

/// A calendar user address without its `mailto:` scheme, which is a
/// wire detail and not something a reader is asking to see.
fn address_of(value: String) -> String {
    match value.split_once(':') {
        Some((scheme, address)) if scheme.eq_ignore_ascii_case("mailto") => address.to_string(),
        _ => value,
    }
}

/// Expands every scheduled component of one calendar object into the
/// occurrences falling inside `[from, until)`, both civil `YYYYMMDD` or
/// `YYYYMMDDTHHMMSS` stamps.
///
/// A component with no RRULE yields at most its single instance.
/// Overrides (`RECURRENCE-ID`) and exceptions (`EXDATE`) are not
/// composed yet, so an exception date still renders; that gap is
/// tracked in the plan.
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

/// Walks a component tree, expanding every scheduled component it holds.
fn collect(
    component: &IcalComponent,
    from: IcalRecurDateTime,
    until: IcalRecurDateTime,
    out: &mut Vec<Occurrence>,
) {
    for kind in SCHEDULED {
        if is_kind(&component.name, kind) {
            expand_scheduled(component, kind, from, until, out);
        }
    }

    // NOTE: a VEVENT nests only VALARMs, but a VCALENDAR arriving inside
    // another component (some servers wrap) would otherwise be missed.
    for nested in &component.components {
        collect(nested, from, until, out);
    }
}

fn expand_scheduled(
    component: &IcalComponent,
    kind: IcalComponentKind,
    from: IcalRecurDateTime,
    until: IcalRecurDateTime,
    out: &mut Vec<Occurrence>,
) {
    let Some(raw_start) = start_of(component, kind) else {
        return;
    };
    let Ok(start) = IcalRecurDateTime::parse(&raw_start) else {
        return;
    };

    let all_day = raw_start.len() == 8;
    let summary = text_of(component, IcalPropKind::Summary).unwrap_or_default();
    let location = text_of(component, IcalPropKind::Location).unwrap_or_default();

    // The length, carried forward onto every occurrence: RFC 5545 recurs
    // the start and keeps the duration, so only the start needs
    // expanding.
    let length = end_of(component, kind)
        .and_then(|raw| IcalRecurDateTime::parse(&raw).ok())
        .map(|end| seconds_between(start, end))
        .unwrap_or(0);

    let rendered = |moment| Occurrence {
        component: kind.to_string(),
        start: stamp(moment),
        end: stamp(shift(moment, length)),
        summary: summary.clone(),
        location: location.clone(),
        all_day,
    };

    match text_of(component, IcalPropKind::RRule) {
        None => {
            if start >= from && start < until {
                out.push(rendered(start));
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
                out.push(rendered(moment));
            }
        }
    }
}

/// The property a component's row is placed at.
///
/// A to-do need not carry a DTSTART (RFC 5545 3.6.2 makes both dates
/// optional), and one that carries only a DUE belongs on the day it is
/// due: that is the date a person looks for, and dropping the to-do
/// because the other property is absent would hide it entirely.
fn start_of(component: &IcalComponent, kind: IcalComponentKind) -> Option<String> {
    match kind {
        IcalComponentKind::VTodo => text_of(component, IcalPropKind::DtStart)
            .or_else(|| text_of(component, IcalPropKind::Due)),
        _ => text_of(component, IcalPropKind::DtStart),
    }
}

/// The property a component's row ends at, when it has one.
///
/// A journal entry never does (RFC 5545 3.6.3 gives it no end), and a
/// to-do's DUE is an end only when a DTSTART placed the row somewhere
/// else; otherwise DUE is the start and the row has no length.
fn end_of(component: &IcalComponent, kind: IcalComponentKind) -> Option<String> {
    match kind {
        IcalComponentKind::VEvent => text_of(component, IcalPropKind::DtEnd),
        IcalComponentKind::VTodo => text_of(component, IcalPropKind::DtStart)
            .and_then(|_| text_of(component, IcalPropKind::Due)),
        _ => None,
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
    // CATEGORIES is the one value the reader's page asks for that is a
    // list rather than a string, and it reads as the line it was
    // written on.
    if let IcalValue::TextList(values) = &prop.value {
        let joined = values.0.join(", ");
        return match joined.is_empty() {
            true => None,
            false => Some(joined),
        };
    }

    let raw = match &prop.value {
        IcalValue::Text(value) => value.0.as_ref(),
        IcalValue::DateTime(value) => value.0.as_ref(),
        IcalValue::Date(value) => value.0.as_ref(),
        IcalValue::Recur(value) => value.0.as_ref(),
        IcalValue::Duration(value) => value.0.as_ref(),
        // PRIORITY and PERCENT-COMPLETE are numbers, URL and the two
        // calendar-user addresses are URIs: none of them is a value an
        // agenda row ever needed, and all of them are on the page.
        IcalValue::Integer(value) => value.0.as_ref(),
        IcalValue::Uri(value) => value.0.as_ref(),
        IcalValue::CalAddress(value) => value.0.as_ref(),
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
        assert_eq!(found[0].component, "VEVENT");
        assert_eq!(found[0].summary, "Standup");
        assert_eq!(found[0].end, "20260105T100000");
        assert!(!found[0].all_day);
    }

    #[test]
    fn places_a_todo_at_its_due_when_it_has_no_start() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:7\r\nDUE:20260115T170000\r\nSUMMARY:File taxes\r\nEND:VTODO\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].component, "VTODO");
        assert_eq!(found[0].start, "20260115T170000");
        // The due date placed the row, so it cannot also end it: a
        // zero-length to-do is one moment, not one that runs to itself.
        assert_eq!(found[0].end, "20260115T170000");
    }

    #[test]
    fn runs_a_todo_from_its_start_to_its_due() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:8\r\nDTSTART:20260115T090000\r\nDUE:20260115T170000\r\n\
             END:VTODO\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(found[0].start, "20260115T090000");
        assert_eq!(found[0].end, "20260115T170000");
    }

    #[test]
    fn renders_a_journal_entry_on_its_day() {
        let ical = object(
            "BEGIN:VJOURNAL\r\nUID:9\r\nDTSTART;VALUE=DATE:20260115\r\nSUMMARY:Notes\r\n\
             END:VJOURNAL\r\n",
        );

        let found = expand(&ical, "20260101", "20260201").unwrap();

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].component, "VJOURNAL");
        assert!(found[0].all_day);
    }

    #[test]
    fn leaves_out_what_an_agenda_cannot_place() {
        // A time zone definition carries DTSTARTs of its own (the rules
        // its offsets take effect on), and expanding those would put a
        // row on the agenda for every zone change since 1970.
        let ical = object(
            "BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\nBEGIN:STANDARD\r\n\
             DTSTART:20260105T030000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\n\
             END:STANDARD\r\nEND:VTIMEZONE\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert!(found.is_empty());
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

    #[test]
    fn reads_a_whole_event_including_its_attendees() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:42\r\nDTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n\
             SUMMARY:Standup\r\nDESCRIPTION:Daily sync\r\nLOCATION:Room 3\r\n\
             CATEGORIES:work,team\r\nSTATUS:CONFIRMED\r\nPRIORITY:5\r\n\
             RRULE:FREQ=WEEKLY;BYDAY=MO\r\nORGANIZER:mailto:alice@example.org\r\n\
             ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.org\r\n\
             ATTENDEE:mailto:carol@example.org\r\nEND:VEVENT\r\n",
        );

        let detail = read(&ical).unwrap();

        assert_eq!(detail.component, "VEVENT");
        assert_eq!(detail.uid, "42");
        assert_eq!(detail.summary, "Standup");
        assert_eq!(detail.description, "Daily sync");
        assert_eq!(detail.location, "Room 3");
        assert_eq!(detail.categories, "work, team");
        assert_eq!(detail.status, "CONFIRMED");
        assert_eq!(detail.priority, "5");
        assert_eq!(detail.recurrence, "FREQ=WEEKLY;BYDAY=MO");
        // The scheme is a wire detail rather than something a reader
        // asked to see, so it goes on both sides.
        assert_eq!(detail.organizer, "alice@example.org");
        assert_eq!(detail.attendees.len(), 2);
        assert_eq!(detail.attendees[0].name, "Bob");
        assert_eq!(detail.attendees[0].address, "bob@example.org");
        assert_eq!(detail.attendees[0].status, "ACCEPTED");
        assert_eq!(detail.attendees[1].name, "");
        assert!(!detail.all_day);
    }

    #[test]
    fn reads_a_todo_placed_at_its_due_date() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:7\r\nDUE;VALUE=DATE:20260115\r\nSUMMARY:File taxes\r\n\
             PERCENT-COMPLETE:40\r\nEND:VTODO\r\n",
        );

        let detail = read(&ical).unwrap();

        assert_eq!(detail.component, "VTODO");
        assert_eq!(detail.due, "20260115");
        assert_eq!(detail.percent_complete, "40");
        // A to-do carrying no DTSTART is placed at its DUE, and a
        // date-only DUE makes the page an all-day one.
        assert_eq!(detail.start, "20260115");
        assert!(detail.all_day);
    }

    #[test]
    fn reading_an_object_with_nothing_scheduled_fails() {
        let ical = object("BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\nEND:VTIMEZONE\r\n");

        assert!(read(&ical).is_err());
    }
}
