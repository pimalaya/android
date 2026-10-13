//! # Reading
//!
//! One component of a calendar object read whole, for the page that shows
//! it, and whether the user organizes the object.

use ical::{
    component::IcalComponent,
    param::IcalParam,
    prop::{IcalProp, IcalPropKind, IcalPropName},
    recur::IcalRecurDateTime,
};
use serde::Serialize;

use crate::{
    calendar::{
        child, collect_scheduled, cst_of, decoded, has, raw_value, scheduled, start_of, text_of,
        time_of,
        zone::{EventTime, EventTimeKind, Zones},
    },
    error::BridgeError,
};

/// One calendar component read whole, for the page that shows it.
///
/// A projection in the one shape VEVENT, VTODO and VJOURNAL share, what
/// only one carries left empty on the others: the page differs by the
/// sections it draws, not by what it can be handed.
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
    /// `DTSTART`; a to-do need not carry one.
    pub start: Option<EventTime>,
    /// `DTEND`; only a VEVENT has one.
    pub end: Option<EventTime>,
    /// `DUE`; only a VTODO has one.
    pub due: Option<EventTime>,
    /// `COMPLETED`; only a VTODO has one.
    pub completed: Option<EventTime>,
    /// Whether the placing date, the start or a to-do's DUE, is a DATE
    /// rather than a DATE-TIME.
    pub all_day: bool,
    /// The series' `RRULE`, raw, empty when it does not repeat.
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
    /// The `RECURRENCE-ID` of the override read, absent when what was
    /// read is the series or an entry that does not recur.
    pub recurrence_id: Option<EventTime>,
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

/// Reads one component of a calendar object whole.
///
/// The series' component, or the override a `recurrence_id` names when
/// the object holds one: what the opened occurrence looks like. The rule
/// is the series' either way, as an override repeats nothing.
pub fn read(ical: &str, recurrence_id: &str) -> Result<EventDetail, BridgeError> {
    let cst = cst_of(ical)?;
    let decoded = cst.decode();
    let zones = Zones::of(&decoded);

    let mut found = Vec::new();
    for component in &decoded.components {
        collect_scheduled(component, &mut found);
    }

    let (master, kind) = found
        .iter()
        .find(|(component, _)| !has(component, IcalPropKind::RecurrenceId))
        .or(found.first())
        .copied()
        .ok_or_else(|| BridgeError::from("The object holds nothing to show"))?;

    let mut component = master;
    if let Ok(id) = IcalRecurDateTime::parse(recurrence_id)
        && let Some(start) = start_of(master, kind)
    {
        let uid = text_of(master, IcalPropKind::Uid);
        let replaced = found.iter().find(|(other, _)| {
            text_of(other, IcalPropKind::Uid) == uid
                && time_of(other, IcalPropKind::RecurrenceId)
                    .and_then(|raw| Some(zones.convert(raw.civil()?, &raw, &start)))
                    == Some(id)
        });
        if let Some((over, _)) = replaced {
            component = over;
        }
    }

    let placing = start_of(component, kind);

    Ok(EventDetail {
        component: kind.to_string(),
        uid: text_of(component, IcalPropKind::Uid).unwrap_or_default(),
        summary: text_of(component, IcalPropKind::Summary).unwrap_or_default(),
        description: text_of(component, IcalPropKind::Description).unwrap_or_default(),
        location: text_of(component, IcalPropKind::Location).unwrap_or_default(),
        url: text_of(component, IcalPropKind::Url).unwrap_or_default(),
        status: text_of(component, IcalPropKind::Status).unwrap_or_default(),
        categories: text_of(component, IcalPropKind::Categories).unwrap_or_default(),
        all_day: placing.is_some_and(|start| start.kind == EventTimeKind::Date),
        start: time_of(component, IcalPropKind::DtStart).map(|time| zones.resolve(time)),
        end: time_of(component, IcalPropKind::DtEnd).map(|time| zones.resolve(time)),
        due: time_of(component, IcalPropKind::Due).map(|time| zones.resolve(time)),
        completed: time_of(component, IcalPropKind::Completed),
        recurrence: text_of(master, IcalPropKind::RRule).unwrap_or_default(),
        priority: text_of(component, IcalPropKind::Priority).unwrap_or_default(),
        percent_complete: text_of(component, IcalPropKind::PercentComplete).unwrap_or_default(),
        organizer: address_of(text_of(component, IcalPropKind::Organizer).unwrap_or_default()),
        attendees: attendees_of(component),
        created: text_of(component, IcalPropKind::Created).unwrap_or_default(),
        last_modified: text_of(component, IcalPropKind::LastModified).unwrap_or_default(),
        recurrence_id: time_of(component, IcalPropKind::RecurrenceId)
            .map(|time| zones.resolve(time)),
    })
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

/// The user a calendar-user address names: a `mailto:` address, or the
/// `EMAIL` parameter of any other URI.
pub fn user_address(prop: &IcalProp) -> Option<String> {
    let value = raw_value(prop)?;
    match value.split_once(':') {
        Some((scheme, _)) if !scheme.eq_ignore_ascii_case("mailto") => {
            prop.params.iter().find_map(|param| match param {
                IcalParam::Email(email) => Some(email.to_string()),
                _ => None,
            })
        }
        _ => Some(address_of(value)),
    }
}

/// Whether the user organizes an event with attendees, which is when a
/// change of it is theirs to announce (RFC 5546).
///
/// The `organizer` is one of their `addresses`, compared
/// case-insensitively, or nobody, the server then filling the owner.
pub fn organizes(organizer: &str, attendees: bool, addresses: &[String]) -> bool {
    attendees
        && (organizer.is_empty()
            || addresses
                .iter()
                .any(|address| address.eq_ignore_ascii_case(organizer)))
}

/// Whether the user organizes one of an object's scheduled components
/// with attendees ([`organizes`]), which is when a write of it is
/// announced to them; an object that does not parse is not.
pub fn organized(ical: &str, addresses: &[String]) -> bool {
    let Ok(cst) = cst_of(ical) else {
        return false;
    };
    let cst = cst.into_static();

    scheduled(&cst).into_iter().any(|index| {
        let component = decoded(child(&cst, index));
        let named = |kind: IcalPropKind| {
            component
                .props
                .iter()
                .filter(move |prop| prop.name == IcalPropName::Kind(kind))
                .filter_map(user_address)
                .filter(|address| !address.is_empty())
        };
        let organizer = named(IcalPropKind::Organizer).next().unwrap_or_default();

        organizes(
            &organizer,
            named(IcalPropKind::Attendee).next().is_some(),
            addresses,
        )
    })
}

#[cfg(test)]
mod tests {
    use crate::calendar::{read::*, tests::object};

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

        let detail = read(&ical, "").unwrap();

        assert_eq!(detail.component, "VEVENT");
        assert_eq!(detail.uid, "42");
        assert_eq!(detail.summary, "Standup");
        assert_eq!(detail.description, "Daily sync");
        assert_eq!(detail.location, "Room 3");
        assert_eq!(detail.categories, "work, team");
        assert_eq!(detail.status, "CONFIRMED");
        assert_eq!(detail.priority, "5");
        assert_eq!(detail.recurrence, "FREQ=WEEKLY;BYDAY=MO");
        // NOTE: the scheme is a wire detail rather than something a reader
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
    fn reads_the_override_of_the_occurrence_opened() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260105T090000\r\nSUMMARY:Standup\r\n\
             RRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260112T090000\r\n\
             DTSTART:20260112T100000\r\nSUMMARY:Standup, later\r\nEND:VEVENT\r\n",
        );

        let moved = read(&ical, "20260112T090000").unwrap();
        assert_eq!(moved.summary, "Standup, later");
        assert_eq!(moved.start.unwrap().time, "20260112T100000");
        // NOTE: the rule is the series', since an override repeats nothing.
        assert_eq!(moved.recurrence, "FREQ=WEEKLY");
        // NOTE: and it says it is the override, which a conflict on it is
        // shown on.
        assert_eq!(moved.recurrence_id.unwrap().time, "20260112T090000");

        let series = read(&ical, "20260119T090000").unwrap();
        assert_eq!(series.summary, "Standup");
        assert!(series.recurrence_id.is_none());
    }

    #[test]
    fn reads_a_todo_placed_at_its_due_date() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:7\r\nDUE;VALUE=DATE:20260115\r\nSUMMARY:File taxes\r\n\
             PERCENT-COMPLETE:40\r\nEND:VTODO\r\n",
        );

        let detail = read(&ical, "").unwrap();

        assert_eq!(detail.component, "VTODO");
        assert_eq!(detail.due.unwrap().time, "20260115");
        assert_eq!(detail.percent_complete, "40");
        // NOTE: a to-do carrying no DTSTART is placed at its DUE, and a
        // date-only DUE makes the page an all-day one; it still has no
        // start, or an edit would write one.
        assert!(detail.start.is_none());
        assert!(detail.all_day);
    }

    #[test]
    fn reading_an_object_with_nothing_scheduled_fails() {
        let ical = object("BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\nEND:VTIMEZONE\r\n");

        assert!(read(&ical, "").is_err());
    }

    #[test]
    fn an_object_is_organized_by_one_of_the_users_addresses_or_nobody() {
        let addresses = ["jane@example.com".to_string()];
        let organized = |people: &str| {
            let event = format!(
                "BEGIN:VEVENT\r\nUID:1\r\nDTSTART:20261012T090000Z\r\n{people}END:VEVENT\r\n"
            );
            crate::calendar::read::organized(&object(&event), &addresses)
        };
        let ada = "ATTENDEE:mailto:ada@example.com\r\n";

        assert!(organized(&format!(
            "ORGANIZER:mailto:JANE@example.COM\r\n{ada}"
        )));
        assert!(organized(ada), "the server fills the owner");
        assert!(organized(&format!(
            "ORGANIZER;EMAIL=jane@example.com:urn:uuid:jane\r\n{ada}"
        )));
        assert!(!organized("ORGANIZER:mailto:jane@example.com\r\n"));
        assert!(!organized(
            "ORGANIZER:mailto:jane@example.com\r\nATTENDEE:urn:uuid:nobody\r\n"
        ));
        assert!(!organized(&format!(
            "ORGANIZER:mailto:boss@example.com\r\n{ada}"
        )));
        assert!(!crate::calendar::read::organized(
            "not a calendar",
            &addresses
        ));
    }
}
