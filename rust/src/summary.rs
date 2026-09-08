//! The item summary on the JSON wire, both directions.
//!
//! pimdir STORAGE Annex A fixes what a reader lists an item from: one
//! typed row per kind, plus the people the item names. io-pimdir carries
//! it as [`PimdirSummary`], the store holds it in the kind's summary
//! table and in `item_address`, and this module is the translation
//! between the two.
//!
//! The keys are the column names rather than the app's usual camelCase:
//! the object *is* the row, so the Java side binds it straight into the
//! canonical statement by name and holds no column list of its own.
//! `addresses` is the exception, being rows rather than columns.

use io_pimdir::summary::{
    PimdirAddress, PimdirAddressRole, PimdirSummary,
    calendar::{PimdirEventSummary, PimdirJournalSummary, PimdirTaskSummary, PimdirTime},
    contact::PimdirContactSummary,
    mail::PimdirMailSummary,
};
use serde::{Deserialize, Serialize};

/// One item's summary row with the addresses beside it, tagged by the
/// kind whose table holds it.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SummaryJson {
    /// A `message/rfc822` item (Annex A.1).
    Mail(MailJson),
    /// A `text/vcard` item (Annex A.2).
    Contact(ContactJson),
    /// A `text/calendar` `VEVENT` resource (Annex A.3).
    Event(EventJson),
    /// A `text/calendar` `VTODO` resource (Annex A.4).
    Task(TaskJson),
    /// A `text/calendar` `VJOURNAL` resource (Annex A.5).
    Journal(JournalJson),
}

impl From<&PimdirSummary> for SummaryJson {
    fn from(summary: &PimdirSummary) -> Self {
        let addresses = addresses_json(summary);

        match summary {
            PimdirSummary::Mail(mail) => Self::Mail(MailJson {
                message_id: mail.message_id.clone(),
                in_reply_to: mail.in_reply_to.clone(),
                subject: mail.subject.clone(),
                sender: mail.sender.clone(),
                sender_name: mail.sender_name.clone(),
                date: mail.date.clone(),
                size: mail.size,
                attachment: mail.attachment,
                addresses,
            }),
            PimdirSummary::Contact(contact) => Self::Contact(ContactJson {
                uid: contact.uid.clone(),
                full_name: contact.full_name.clone(),
                kind: contact.kind.clone(),
                org: contact.org.clone(),
                addresses,
            }),
            PimdirSummary::Event(event) => Self::Event(EventJson {
                uid: event.uid.clone(),
                summary: event.summary.clone(),
                location: event.location.clone(),
                time: TimeJson::of(event.dtstart.as_ref()),
                dtend: event.dtend.clone(),
                recurring: event.recurring,
                until: event.until.clone(),
                addresses,
            }),
            PimdirSummary::Task(task) => Self::Task(TaskJson {
                uid: task.uid.clone(),
                summary: task.summary.clone(),
                time: TimeJson::of(task.dtstart.as_ref()),
                due: DueJson::of(task.due.as_ref()),
                status: task.status.clone(),
                completed: task.completed.clone(),
                percent: task.percent,
                recurring: task.recurring,
                until: task.until.clone(),
                addresses,
            }),
            PimdirSummary::Journal(journal) => Self::Journal(JournalJson {
                uid: journal.uid.clone(),
                summary: journal.summary.clone(),
                time: TimeJson::of(journal.dtstart.as_ref()),
                addresses,
            }),
        }
    }
}

impl From<SummaryJson> for PimdirSummary {
    fn from(wire: SummaryJson) -> Self {
        match wire {
            SummaryJson::Mail(mail) => Self::Mail(PimdirMailSummary {
                message_id: mail.message_id,
                in_reply_to: mail.in_reply_to,
                subject: mail.subject,
                sender: mail.sender,
                sender_name: mail.sender_name,
                date: mail.date,
                size: mail.size,
                attachment: mail.attachment,
                from: role_of(&mail.addresses, PimdirAddressRole::From),
                to: role_of(&mail.addresses, PimdirAddressRole::To),
                cc: role_of(&mail.addresses, PimdirAddressRole::Cc),
                bcc: role_of(&mail.addresses, PimdirAddressRole::Bcc),
            }),
            SummaryJson::Contact(contact) => Self::Contact(PimdirContactSummary {
                uid: contact.uid,
                full_name: contact.full_name,
                kind: contact.kind,
                org: contact.org,
                emails: role_of(&contact.addresses, PimdirAddressRole::Email),
            }),
            SummaryJson::Event(event) => Self::Event(PimdirEventSummary {
                uid: event.uid,
                summary: event.summary,
                location: event.location,
                dtstart: event.time.time(),
                dtend: event.dtend,
                recurring: event.recurring,
                until: event.until,
                organizer: organizer_of(&event.addresses),
                attendees: role_of(&event.addresses, PimdirAddressRole::Attendee),
            }),
            SummaryJson::Task(task) => Self::Task(PimdirTaskSummary {
                uid: task.uid,
                summary: task.summary,
                dtstart: task.time.time(),
                due: task.due.time(),
                status: task.status,
                completed: task.completed,
                percent: task.percent,
                recurring: task.recurring,
                until: task.until,
                organizer: organizer_of(&task.addresses),
                attendees: role_of(&task.addresses, PimdirAddressRole::Attendee),
            }),
            SummaryJson::Journal(journal) => Self::Journal(PimdirJournalSummary {
                uid: journal.uid,
                summary: journal.summary,
                dtstart: journal.time.time(),
                organizer: organizer_of(&journal.addresses),
                attendees: role_of(&journal.addresses, PimdirAddressRole::Attendee),
            }),
        }
    }
}

/// The `mail_summary` row (Annex A.1).
#[derive(Deserialize, Serialize)]
pub struct MailJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    message_id: Option<String>,
    #[serde(default)]
    in_reply_to: Vec<String>,
    #[serde(default)]
    subject: String,
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
    #[serde(default)]
    addresses: Vec<AddressJson>,
}

/// The `contact_summary` row (Annex A.2).
#[derive(Deserialize, Serialize)]
pub struct ContactJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    uid: Option<String>,
    /// The `FN`, under the column's name, which is a Rust keyword.
    #[serde(rename = "fn", default)]
    full_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    org: Option<String>,
    #[serde(default)]
    addresses: Vec<AddressJson>,
}

/// The `event_summary` row (Annex A.3).
#[derive(Deserialize, Serialize)]
pub struct EventJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    uid: Option<String>,
    #[serde(default)]
    summary: String,
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
    #[serde(default)]
    addresses: Vec<AddressJson>,
}

/// The `task_summary` row (Annex A.4).
#[derive(Deserialize, Serialize)]
pub struct TaskJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    uid: Option<String>,
    #[serde(default)]
    summary: String,
    #[serde(flatten)]
    time: TimeJson,
    #[serde(flatten)]
    due: DueJson,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    status: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    completed: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    percent: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    recurring: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    until: Option<String>,
    #[serde(default)]
    addresses: Vec<AddressJson>,
}

/// The `journal_summary` row (Annex A.5).
#[derive(Deserialize, Serialize)]
pub struct JournalJson {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    uid: Option<String>,
    #[serde(default)]
    summary: String,
    #[serde(flatten)]
    time: TimeJson,
    #[serde(default)]
    addresses: Vec<AddressJson>,
}

/// A `DTSTART` as its three columns: the value verbatim, its `TZID` and
/// whether it is a date or a date-time.
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

/// One row of `item_address` (Annex A.6): who the item names, under
/// which role, at which place in the document order of that role.
#[derive(Deserialize, Serialize)]
pub struct AddressJson {
    role: String,
    position: usize,
    address: String,
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

/// Every address the summary names, as the rows `item_address` holds:
/// the role's spelling and the position within it, both the columns'.
fn addresses_json(summary: &PimdirSummary) -> Vec<AddressJson> {
    let mut rows = Vec::new();
    let mut position = 0;
    let mut current = None;

    for (role, address) in summary.addresses() {
        if current != Some(role) {
            current = Some(role);
            position = 0;
        }

        rows.push(AddressJson {
            role: role.as_str().into(),
            position,
            address: address.address.clone(),
            name: address.name.clone(),
        });
        position += 1;
    }

    rows
}

/// The addresses of one role, in the order the rows carry them.
fn role_of(rows: &[AddressJson], role: PimdirAddressRole) -> Vec<PimdirAddress> {
    rows.iter()
        .filter(|row| PimdirAddressRole::parse(&row.role) == Some(role))
        .map(|row| PimdirAddress {
            address: row.address.clone(),
            name: row.name.clone(),
        })
        .collect()
}

/// The one organizer a calendar component names, if any.
fn organizer_of(rows: &[AddressJson]) -> Option<PimdirAddress> {
    role_of(rows, PimdirAddressRole::Organizer)
        .into_iter()
        .next()
}

#[cfg(test)]
mod tests {
    use io_pimdir::summary::derive;
    use serde_json::{from_str, from_value, json, to_value};

    use super::*;

    /// A derived card round-trips through the wire unchanged, and its
    /// keys are the columns the store binds by name.
    #[test]
    fn a_contact_summary_round_trips_as_its_row() {
        let vcard = b"BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u1\r\nFN:Jane Doe\r\nORG:ACME;Sales\r\n\
                      EMAIL:Jane@Example.ORG\r\nEMAIL:jane@home.example\r\nEND:VCARD\r\n";
        let summary = derive("text/vcard", vcard).unwrap().summary.unwrap();

        let wire = to_value(SummaryJson::from(&summary)).unwrap();
        assert_eq!(wire["contact"]["fn"], "Jane Doe");
        assert_eq!(wire["contact"]["uid"], "u1");
        assert_eq!(wire["contact"]["org"], "ACME");
        assert_eq!(wire["contact"]["addresses"][0]["role"], "email");
        assert_eq!(wire["contact"]["addresses"][1]["position"], 1);
        assert_eq!(
            wire["contact"]["addresses"][0]["address"],
            "jane@example.org"
        );

        let back: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();
        assert_eq!(back, summary);
    }

    /// A message's four address roles each keep their own positions.
    #[test]
    fn a_mail_summary_positions_each_role_on_its_own() {
        let mail = b"Message-ID: <m1@x>\r\nSubject: Hi\r\nFrom: A <a@x>\r\n\
                     To: b@x, c@x\r\nCc: d@x\r\n\r\nbody\r\n";
        let summary = derive("message/rfc822", mail).unwrap().summary.unwrap();

        let wire = to_value(SummaryJson::from(&summary)).unwrap();
        let rows = wire["mail"]["addresses"].as_array().unwrap();
        assert_eq!(rows[0]["role"], "from");
        assert_eq!(rows[0]["position"], 0);
        assert_eq!(rows[1]["role"], "to");
        assert_eq!(rows[1]["position"], 0);
        assert_eq!(rows[2]["position"], 1);
        assert_eq!(rows[3]["role"], "cc");
        assert_eq!(rows[3]["position"], 0);

        let back: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();
        assert_eq!(back, summary);
    }

    /// A start is three columns, and an absent one is none of them.
    #[test]
    fn a_calendar_start_flattens_into_its_three_columns() {
        let ics = b"BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:e1\r\nSUMMARY:Lunch\r\n\
                    DTSTART;TZID=Europe/Paris:20260908T120000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let summary = derive("text/calendar", ics).unwrap().summary.unwrap();

        let wire = to_value(SummaryJson::from(&summary)).unwrap();
        assert_eq!(wire["event"]["dtstart"], "20260908T120000");
        assert_eq!(wire["event"]["dtstart_tzid"], "Europe/Paris");
        assert_eq!(wire["event"]["dtstart_value"], "date-time");

        let back: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();
        assert_eq!(back, summary);

        let bare: SummaryJson = from_str(r#"{"event":{"summary":"x"}}"#).unwrap();
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
    fn a_row_with_no_addresses_reads_as_one() {
        let wire = json!({ "contact": { "fn": "Nobody" } });
        let summary: PimdirSummary = from_value::<SummaryJson>(wire).unwrap().into();

        assert_eq!(summary.title(), "Nobody");
        assert!(summary.addresses().is_empty());
    }
}
