//! # Summaries
//!
//! The item summary on the JSON wire, both directions.
//!
//! STORAGE Annex A fixes one row per item whatever its kind, `component`
//! naming the kind, and the lists it carries as `summary_value` rows:
//! io-pimdir carries it as [`PimdirSummary`], the store as one `summary` row
//! and its values; this module translates.
//!
//! Keys are column names, not the app's camelCase: the object *is* the row,
//! which Java binds by name into `upsert_summary` with no column list of
//! its own, a column another component owns being absent and so bound
//! NULL. `values` is the exception, being rows.

use io_pimdir::summary::{
    PimdirAddress, PimdirAddressRole, PimdirSummary,
    calendar::{PimdirEventSummary, PimdirJournalSummary, PimdirTaskSummary, PimdirTime},
    contact::PimdirContactSummary,
    file::PimdirFileSummary,
    mail::{PimdirMailSummary, usable_date},
};
use serde::{Deserialize, Serialize};

/// The `summary_value` role of a message's In-Reply-To ids (Annex A.1).
const IN_REPLY_TO: &str = "in-reply-to";

/// The `summary_value` role of a task's categories (Annex A.4).
const CATEGORY: &str = "category";

/// One item's `summary` row and its `summary_value` rows.
///
/// A message's `date` crosses in the one shape the column takes (STORAGE
/// §13), any other as none, as io-pimdir stores it. A row of a component
/// Annex A does not name reads as a message's.
#[derive(Default, Deserialize, Serialize)]
pub struct SummaryJson {
    /// The Annex A section the row follows: `mail`, `contact`, `event`,
    /// `task`, `journal` or `file`.
    component: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    uid: Option<String>,
    /// Always stated, the column being NOT NULL; it may be empty.
    #[serde(default)]
    title: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    sender: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    sender_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    date: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    size: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    attachment: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    invitation: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    card_kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    org: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    location: Option<String>,
    #[serde(flatten)]
    time: TimeJson,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    dtend: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    recurring: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    until: Option<String>,
    #[serde(flatten)]
    due: DueJson,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    status: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    completed: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    percent: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    priority: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    parent: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    mid: Option<String>,
    /// When a task was captured (A.4): `CREATED`, else `DTSTAMP`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    created: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    media_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    part: Option<String>,
    /// Always stated, `[]` for none: the item's `summary_value` rows, each
    /// role in document order.
    #[serde(default)]
    values: Vec<ValueJson>,
}

impl From<&PimdirSummary> for SummaryJson {
    fn from(summary: &PimdirSummary) -> Self {
        let mut values = Vec::new();
        match summary {
            PimdirSummary::Mail(mail) => list(&mut values, IN_REPLY_TO, &mail.in_reply_to),
            PimdirSummary::Task(task) => list(&mut values, CATEGORY, &task.categories),
            _ => {}
        }
        addresses(&mut values, summary);

        match summary {
            PimdirSummary::Mail(mail) => Self {
                component: "mail".into(),
                uid: mail.message_id.clone(),
                title: mail.subject.clone(),
                sender: mail.sender.clone(),
                sender_name: mail.sender_name.clone(),
                date: usable_date(mail.date.as_deref()).map(str::to_owned),
                size: mail.size,
                attachment: mail.attachment,
                invitation: mail.invitation.clone(),
                values,
                ..Default::default()
            },
            PimdirSummary::Contact(contact) => Self {
                component: "contact".into(),
                uid: contact.uid.clone(),
                title: contact.full_name.clone(),
                card_kind: contact.kind.clone(),
                org: contact.org.clone(),
                values,
                ..Default::default()
            },
            PimdirSummary::Event(event) => Self {
                component: "event".into(),
                uid: event.uid.clone(),
                title: event.summary.clone(),
                location: event.location.clone(),
                time: TimeJson::of(event.dtstart.as_ref()),
                dtend: event.dtend.clone(),
                recurring: event.recurring,
                until: event.until.clone(),
                values,
                ..Default::default()
            },
            PimdirSummary::Task(task) => Self {
                component: "task".into(),
                uid: task.uid.clone(),
                title: task.summary.clone(),
                time: TimeJson::of(task.dtstart.as_ref()),
                due: DueJson::of(task.due.as_ref()),
                status: task.status.clone(),
                completed: task.completed.clone(),
                percent: task.percent,
                recurring: task.recurring,
                until: task.until.clone(),
                priority: task.priority,
                parent: task.parent.clone(),
                mid: task.mid.clone(),
                created: task.created.clone(),
                values,
                ..Default::default()
            },
            PimdirSummary::Journal(journal) => Self {
                component: "journal".into(),
                uid: journal.uid.clone(),
                title: journal.summary.clone(),
                time: TimeJson::of(journal.dtstart.as_ref()),
                values,
                ..Default::default()
            },
            PimdirSummary::File(file) => Self {
                component: "file".into(),
                title: file.name.clone(),
                media_type: file.media_type.clone(),
                size: file.size,
                part: file.part.clone(),
                values,
                ..Default::default()
            },
        }
    }
}

impl From<SummaryJson> for PimdirSummary {
    fn from(wire: SummaryJson) -> Self {
        match wire.component.as_str() {
            "contact" => Self::Contact(PimdirContactSummary {
                emails: role_of(&wire.values, PimdirAddressRole::Email),
                uid: wire.uid,
                full_name: wire.title,
                kind: wire.card_kind,
                org: wire.org,
            }),
            "event" => Self::Event(PimdirEventSummary {
                organizer: organizer_of(&wire.values),
                attendees: role_of(&wire.values, PimdirAddressRole::Attendee),
                uid: wire.uid,
                summary: wire.title,
                location: wire.location,
                dtstart: wire.time.time(),
                dtend: wire.dtend,
                recurring: wire.recurring,
                until: wire.until,
            }),
            "task" => Self::Task(PimdirTaskSummary {
                organizer: organizer_of(&wire.values),
                attendees: role_of(&wire.values, PimdirAddressRole::Attendee),
                contacts: role_of(&wire.values, PimdirAddressRole::Contact),
                categories: listed(&wire.values, CATEGORY),
                uid: wire.uid,
                summary: wire.title,
                dtstart: wire.time.time(),
                due: wire.due.time(),
                status: wire.status,
                completed: wire.completed,
                percent: wire.percent,
                recurring: wire.recurring,
                until: wire.until,
                priority: wire.priority,
                parent: wire.parent,
                mid: wire.mid,
                created: wire.created,
            }),
            "journal" => Self::Journal(PimdirJournalSummary {
                organizer: organizer_of(&wire.values),
                attendees: role_of(&wire.values, PimdirAddressRole::Attendee),
                uid: wire.uid,
                summary: wire.title,
                dtstart: wire.time.time(),
            }),
            "file" => Self::File(PimdirFileSummary {
                name: wire.title,
                media_type: wire.media_type,
                size: wire.size,
                part: wire.part,
            }),
            _ => Self::Mail(PimdirMailSummary {
                in_reply_to: listed(&wire.values, IN_REPLY_TO),
                from: role_of(&wire.values, PimdirAddressRole::From),
                to: role_of(&wire.values, PimdirAddressRole::To),
                cc: role_of(&wire.values, PimdirAddressRole::Cc),
                bcc: role_of(&wire.values, PimdirAddressRole::Bcc),
                message_id: wire.uid,
                subject: wire.title,
                sender: wire.sender,
                sender_name: wire.sender_name,
                date: wire.date,
                size: wire.size,
                attachment: wire.attachment,
                invitation: wire.invitation,
            }),
        }
    }
}

/// A file's summary as a scripted remote states it (SYNC §11), the four
/// columns of Annex A.7 by their own names.
#[derive(Deserialize, Serialize)]
pub struct FileJson {
    #[serde(default)]
    name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    media_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    size: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    part: Option<String>,
}

impl From<FileJson> for PimdirFileSummary {
    fn from(wire: FileJson) -> Self {
        Self {
            name: wire.name,
            media_type: wire.media_type,
            size: wire.size,
            part: wire.part,
        }
    }
}

/// A `DTSTART` as three columns: value verbatim, `TZID`, date or date-time.
#[derive(Default, Deserialize, Serialize)]
pub struct TimeJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    dtstart: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    dtstart_tzid: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    dtstart_value: Option<String>,
}

/// A `DUE` as its own three columns, on [`TimeJson`]'s terms.
#[derive(Default, Deserialize, Serialize)]
pub struct DueJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    due: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    due_tzid: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    due_value: Option<String>,
}

/// One `summary_value` row (Annex A): a person under an address role, an
/// In-Reply-To id or a category, at its position in that role's document
/// order.
#[derive(Deserialize, Serialize)]
pub struct ValueJson {
    role: String,
    position: usize,
    value: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    name: Option<String>,
}

impl TimeJson {
    /// The three columns a `DTSTART` fills, empty for an absent one.
    fn of(time: Option<&PimdirTime>) -> Self {
        let Some(time) = time else {
            return Self::default();
        };

        Self {
            dtstart: Some(time.value.clone()),
            dtstart_tzid: time.tzid.clone(),
            dtstart_value: Some(time.value_kind().into()),
        }
    }

    /// The time the three columns name, `None` when the value is absent.
    fn time(self) -> Option<PimdirTime> {
        Some(PimdirTime {
            value: self.dtstart?,
            tzid: self.dtstart_tzid,
            date: self.dtstart_value.as_deref() == Some("date"),
        })
    }
}

impl DueJson {
    /// The three columns a `DUE` fills, empty for an absent one.
    fn of(time: Option<&PimdirTime>) -> Self {
        let Some(time) = time else {
            return Self::default();
        };

        Self {
            due: Some(time.value.clone()),
            due_tzid: time.tzid.clone(),
            due_value: Some(time.value_kind().into()),
        }
    }

    /// The time the three columns name, `None` when the value is absent.
    fn time(self) -> Option<PimdirTime> {
        Some(PimdirTime {
            value: self.due?,
            tzid: self.due_tzid,
            date: self.due_value.as_deref() == Some("date"),
        })
    }
}

/// Appends a list's values under one role, in order.
fn list(values: &mut Vec<ValueJson>, role: &str, list: &[String]) {
    for (position, value) in list.iter().enumerate() {
        values.push(ValueJson {
            role: role.into(),
            position,
            value: value.clone(),
            name: None,
        });
    }
}

/// Appends every address the summary names, each role from position 0.
fn addresses(values: &mut Vec<ValueJson>, summary: &PimdirSummary) {
    let mut position = 0;
    let mut current = None;

    for (role, address) in summary.addresses() {
        if current != Some(role) {
            current = Some(role);
            position = 0;
        }

        values.push(ValueJson {
            role: role.as_str().into(),
            position,
            value: address.address.clone(),
            name: address.name.clone(),
        });
        position += 1;
    }
}

/// The values of one list role, by position.
fn listed(rows: &[ValueJson], role: &str) -> Vec<String> {
    let mut rows: Vec<&ValueJson> = rows.iter().filter(|row| row.role == role).collect();
    rows.sort_by_key(|row| row.position);
    rows.into_iter().map(|row| row.value.clone()).collect()
}

/// The addresses of one role, in the order the rows carry them.
fn role_of(rows: &[ValueJson], role: PimdirAddressRole) -> Vec<PimdirAddress> {
    rows.iter()
        .filter(|row| PimdirAddressRole::parse(&row.role) == Some(role))
        .map(|row| PimdirAddress {
            address: row.value.clone(),
            name: row.name.clone(),
        })
        .collect()
}

/// The one organizer a calendar component names, if any.
fn organizer_of(rows: &[ValueJson]) -> Option<PimdirAddress> {
    role_of(rows, PimdirAddressRole::Organizer)
        .into_iter()
        .next()
}

#[cfg(test)]
mod tests {
    use io_pimdir::summary::derive;
    use serde_json::{from_str, from_value, json, to_value};

    use crate::offline::summary::*;

    /// A derived card round-trips unchanged, keyed by the store's columns.
    #[test]
    fn a_contact_summary_round_trips_as_its_row() {
        let vcard = b"BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u1\r\nFN:Jane Doe\r\nORG:ACME;Sales\r\n\
                      EMAIL:Jane@Example.ORG\r\nEMAIL:jane@home.example\r\nEND:VCARD\r\n";
        let summary = derive("text/vcard", vcard).unwrap().summary.unwrap();

        let wire = to_value(SummaryJson::from(&summary)).unwrap();
        assert_eq!(wire["component"], "contact");
        assert_eq!(wire["title"], "Jane Doe");
        assert_eq!(wire["uid"], "u1");
        assert_eq!(wire["org"], "ACME");
        assert_eq!(wire["values"][0]["role"], "email");
        assert_eq!(wire["values"][1]["position"], 1);
        assert_eq!(wire["values"][0]["value"], "jane@example.org");

        let back: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();
        assert_eq!(back, summary);
    }

    /// A message's four address roles each keep their own positions, and
    /// its In-Reply-To ids are values of their own role.
    #[test]
    fn a_mail_summary_positions_each_role_on_its_own() {
        let mail = b"Message-ID: <m1@x>\r\nIn-Reply-To: <p1@x> <p2@x>\r\nSubject: Hi\r\n\
                     From: A <a@x>\r\nTo: b@x, c@x\r\nCc: d@x\r\n\r\nbody\r\n";
        let summary = derive("message/rfc822", mail).unwrap().summary.unwrap();

        let wire = to_value(SummaryJson::from(&summary)).unwrap();
        assert_eq!(wire["uid"], "m1@x");
        let rows = wire["values"].as_array().unwrap();
        assert_eq!(rows[0]["role"], "in-reply-to");
        assert_eq!(rows[1]["value"], "p2@x");
        assert_eq!(rows[1]["position"], 1);
        assert_eq!(rows[2]["role"], "from");
        assert_eq!(rows[2]["position"], 0);
        assert_eq!(rows[3]["role"], "to");
        assert_eq!(rows[3]["position"], 0);
        assert_eq!(rows[4]["position"], 1);
        assert_eq!(rows[5]["role"], "cc");
        assert_eq!(rows[5]["position"], 0);

        let back: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();
        assert_eq!(back, summary);
    }

    /// A task's categories are values of their own role, in order.
    #[test]
    fn a_task_summary_carries_its_categories_as_values() {
        let ics = b"BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:t1\r\nSUMMARY:Call\r\n\
                    CATEGORIES:@phone,next\r\nEND:VTODO\r\nEND:VCALENDAR\r\n";
        let summary = derive("text/calendar", ics).unwrap().summary.unwrap();

        let wire = to_value(SummaryJson::from(&summary)).unwrap();
        assert_eq!(wire["component"], "task");
        assert_eq!(wire["values"][0]["role"], "category");
        assert_eq!(wire["values"][1]["value"], "next");

        let back: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();
        assert_eq!(back, summary);
    }

    /// The store's write is Java's, bound from this row, and the schema
    /// takes a message's date as an instant in UTC at seconds precision
    /// alone (STORAGE §13): a date stated otherwise crosses as none, as
    /// io-pimdir stores it, never as a text the write would be refused on.
    #[test]
    fn a_mail_date_of_another_shape_crosses_as_none() {
        let dated = |date: &str| {
            let summary = PimdirSummary::Mail(PimdirMailSummary {
                subject: "Dated".into(),
                date: Some(date.into()),
                ..Default::default()
            });
            to_value(SummaryJson::from(&summary)).unwrap()["date"].clone()
        };

        assert_eq!(dated("2026-08-01T10:00:00Z"), "2026-08-01T10:00:00Z");
        for stated in [
            "2026-08-01T10:00:00.5Z",
            "2026-08-01T12:00:00+02:00",
            "2026-08-01t10:00:00z",
            "12026-08-01T10:00:00Z",
            "2026-08-01",
            "Sat, 1 Aug 2026 10:00:00 +0000",
            "",
        ] {
            assert!(dated(stated).is_null(), "{stated:?}");
        }
    }

    /// A start is three columns, and an absent one is none of them.
    #[test]
    fn a_calendar_start_flattens_into_its_three_columns() {
        let ics = b"BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:e1\r\nSUMMARY:Lunch\r\n\
                    DTSTART;TZID=Europe/Paris:20260908T120000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let summary = derive("text/calendar", ics).unwrap().summary.unwrap();

        let wire = to_value(SummaryJson::from(&summary)).unwrap();
        assert_eq!(wire["dtstart"], "20260908T120000");
        assert_eq!(wire["dtstart_tzid"], "Europe/Paris");
        assert_eq!(wire["dtstart_value"], "date-time");

        let back: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();
        assert_eq!(back, summary);

        let bare: SummaryJson = from_str(r#"{"component":"event","title":"x"}"#).unwrap();
        let bare: PimdirSummary = bare.into();
        assert_eq!(
            bare,
            PimdirSummary::Event(PimdirEventSummary {
                summary: "x".into(),
                ..Default::default()
            })
        );
    }

    /// The store may hand back a row it holds nothing else for.
    #[test]
    fn a_row_with_no_values_reads_as_one() {
        let wire = json!({ "component": "contact", "title": "Nobody" });
        let summary: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();

        assert_eq!(summary.title(), "Nobody");
        assert!(summary.addresses().is_empty());
    }
}
