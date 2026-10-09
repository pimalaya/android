//! The occurrences one object holds otherwise than another, for the
//! servers that take a series' master and each of its occurrences as
//! writes of their own (Graph, Google), where CalDAV takes the object
//! whole.
//!
//! An occurrence is told by its identity: the civil time of its original
//! start in the series' zone, the instance its override or its `EXDATE`
//! names (RFC 5545 3.8.4.4, 3.8.5.1).

use ical::{
    recur::IcalRecurDateTime,
    tree::cst::{IcalCst, IcalItem},
};

use crate::types::BridgeError;

use super::{child, line, scheduled, series::Series, text, zone::stamp};

/// How far either side of an occurrence its instance is looked for, in
/// seconds: two days, past the widest UTC offset there is.
const WINDOW: i64 = 2 * 86_400;

/// One occurrence the staged object holds otherwise than the server.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OccurrenceChange {
    /// Its identity, in the staged series' zone.
    pub id: IcalRecurDateTime,
    pub write: OccurrenceWrite,
}

/// What reaches the instance of one occurrence.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum OccurrenceWrite {
    /// The occurrence as staged, over the occurrence as the server holds
    /// it, each a document holding it alone: an override, or the series'
    /// own component carried to it.
    Update { staged: String, server: String },
    /// The occurrence removed: a new `EXDATE`, or an override cancelling
    /// it.
    Delete,
}

/// Every occurrence `staged` holds otherwise than `server`, in identity
/// order.
///
/// An override added or edited is an update from the server's state of
/// that occurrence, and one removed is an update back to the series', so
/// an exception the edit reverted is reverted on the server too. A new
/// `EXDATE`, or an override turned `CANCELLED`, removes the occurrence.
/// An `EXDATE` dropped brings nothing back, neither server restoring an
/// occurrence it removed. An override the server already holds as staged,
/// byte for byte, is no change.
pub fn occurrence_changes(
    staged: &str,
    server: &str,
) -> Result<Vec<OccurrenceChange>, BridgeError> {
    let staged = parse(staged)?;
    let server = parse(server)?;
    let ours = Series::of(&staged)?;
    let theirs = Series::of(&server)?;

    let our_exdates = ours.exdates(&staged);
    let their_exdates = theirs.exdates(&server);

    let mut ids: Vec<IcalRecurDateTime> = ours
        .overrides
        .iter()
        .chain(&theirs.overrides)
        .map(|(_, id)| *id)
        .chain(our_exdates.iter().copied())
        .collect();
    ids.sort_unstable();
    ids.dedup();

    let mut changes = Vec::new();
    for id in ids {
        let excluded = our_exdates.contains(&id);
        let ours_over = ours.replaced(id).map(|index| child(&staged, index));
        let theirs_over = theirs.replaced(id).map(|index| child(&server, index));
        let gone = their_exdates.contains(&id) || theirs_over.is_some_and(cancelled);

        let write = match ours_over {
            _ if excluded || ours_over.is_some_and(cancelled) => {
                (!gone).then_some(OccurrenceWrite::Delete)
            }
            // NOTE: an occurrence the server removed is not edited back
            // into existence; the next pull brings its removal over.
            Some(_) if their_exdates.contains(&id) => None,
            Some(over) => {
                let held = match theirs_over {
                    Some(held) => held.clone(),
                    None => theirs.instance(&server, id),
                };
                match over.to_string() == held.to_string() {
                    true => None,
                    false => Some(OccurrenceWrite::Update {
                        staged: alone(&staged, over.clone())?,
                        server: alone(&server, held)?,
                    }),
                }
            }
            None => match theirs_over {
                Some(held) => Some(OccurrenceWrite::Update {
                    staged: alone(&staged, ours.instance(&staged, id))?,
                    server: alone(&server, held.clone())?,
                }),
                None => None,
            },
        };

        if let Some(write) = write {
            changes.push(OccurrenceChange { id, write });
        }
    }

    Ok(changes)
}

/// The identities of the occurrences an object overrides, in its series'
/// zone: how an instance the server lists is told, once projected with
/// its series.
pub fn occurrences(ical: &str) -> Result<Vec<IcalRecurDateTime>, BridgeError> {
    let cst = parse(ical)?;
    let series = Series::of(&cst)?;
    Ok(series.overrides.iter().map(|(_, id)| *id).collect())
}

fn parse(ical: &str) -> Result<IcalCst<'static>, BridgeError> {
    Ok(IcalCst::parse(ical)
        .map_err(|err| err.to_string())?
        .into_static())
}

/// Whether an override cancels its instance (RFC 5545 3.8.1.11).
fn cancelled(component: &IcalCst<'static>) -> bool {
    line(component, "STATUS").is_some_and(|line| {
        line.raw_value_str()
            .trim()
            .eq_ignore_ascii_case("CANCELLED")
    })
}

/// A document holding one component of an object alone, beside the
/// object's own properties and zones.
fn alone(cst: &IcalCst<'static>, component: IcalCst<'static>) -> Result<String, BridgeError> {
    let mut one = cst.clone();
    for index in scheduled(cst).into_iter().rev() {
        one.items.remove(index);
    }
    one.items.push(IcalItem::Component(Box::new(component)));
    text(&one)
}

impl OccurrenceChange {
    /// Its identity as a stamp, `YYYYMMDDTHHMMSS`.
    pub fn stamp(&self) -> String {
        stamp(self.id, false)
    }
}

/// Two UTC instants around an occurrence's original start, as RFC 3339
/// stamps: wider than any zone's offset either side, so the instance of
/// the occurrence, where it was not moved, is listed between them.
pub fn occurrence_window(id: IcalRecurDateTime) -> (String, String) {
    let at = |seconds: i64| {
        let moment = IcalRecurDateTime::from_seconds(seconds);
        format!(
            "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
            moment.year, moment.month, moment.day, moment.hour, moment.minute, moment.second
        )
    };

    (at(id.seconds() - WINDOW), at(id.seconds() + WINDOW))
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};

    use super::{
        super::{
            remove,
            tests::{ROMANCE, object},
            write,
        },
        *,
    };

    const ZONE: &str = "/example.org/Romance";

    /// Five days at 09:00 in Paris rules, the second moved to 15:00.
    fn server() -> String {
        object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\n\
             DTSTART;TZID={ZONE}:20260706T090000\r\nDTEND;TZID={ZONE}:20260706T100000\r\n\
             SUMMARY:Standup\r\nRRULE:FREQ=DAILY;COUNT=5\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:z\r\nRECURRENCE-ID;TZID={ZONE}:20260707T090000\r\n\
             DTSTART;TZID={ZONE}:20260707T150000\r\nDTEND;TZID={ZONE}:20260707T160000\r\n\
             SUMMARY:Standup, late\r\nEND:VEVENT\r\n"
        ))
    }

    fn this(id: &str, edit: Value) -> String {
        let mut edit = edit;
        edit["scope"] = json!("this");
        edit["recurrenceId"] = json!(id);
        edit.to_string()
    }

    fn update(change: &OccurrenceChange) -> (&str, &str) {
        match &change.write {
            OccurrenceWrite::Update { staged, server } => (staged, server),
            OccurrenceWrite::Delete => panic!("a removal of {}", change.stamp()),
        }
    }

    #[test]
    fn an_untouched_object_changes_no_occurrence() {
        assert_eq!(occurrence_changes(&server(), &server()).unwrap(), []);
    }

    #[test]
    fn an_occurrence_edited_alone_updates_it_from_the_series() {
        let staged = write(
            &server(),
            &this("20260708T090000", json!({"summary": "Moved"})),
        )
        .unwrap();

        let changes = occurrence_changes(&staged, &server()).unwrap();

        assert_eq!(changes.len(), 1);
        assert_eq!(changes[0].stamp(), "20260708T090000");
        let (ours, theirs) = update(&changes[0]);
        for document in [ours, theirs] {
            assert_eq!(document.matches("BEGIN:VEVENT").count(), 1, "{document}");
            assert!(
                document.contains("TZID:/example.org/Romance\r\n"),
                "{document}"
            );
            assert!(!document.contains("RRULE:FREQ=DAILY"), "{document}");
        }
        assert!(ours.contains("SUMMARY:Moved\r\n"));
        assert!(theirs.contains("SUMMARY:Standup\r\n"));
        assert!(theirs.contains(&format!("DTSTART;TZID={ZONE}:20260708T090000\r\n")));
        assert!(theirs.contains(&format!("RECURRENCE-ID;TZID={ZONE}:20260708T090000\r\n")));
    }

    #[test]
    fn an_exception_edited_again_updates_it_from_the_server_copy() {
        let staged = write(
            &server(),
            &this("20260707T090000", json!({"location": "Room 3"})),
        )
        .unwrap();

        let changes = occurrence_changes(&staged, &server()).unwrap();

        assert_eq!(changes.len(), 1);
        let (ours, theirs) = update(&changes[0]);
        assert!(ours.contains("LOCATION:Room 3\r\n"));
        assert!(theirs.contains("SUMMARY:Standup, late\r\n"));
        assert!(theirs.contains(&format!("DTSTART;TZID={ZONE}:20260707T150000\r\n")));
    }

    #[test]
    fn an_override_dropped_reverts_its_occurrence_to_the_series() {
        let server = server();
        let at = server
            .find("BEGIN:VEVENT\r\nUID:z\r\nRECURRENCE-ID")
            .unwrap();
        let end = server.rfind("END:VEVENT\r\n").unwrap() + "END:VEVENT\r\n".len();
        let staged = format!("{}{}", &server[..at], &server[end..]);

        let changes = occurrence_changes(&staged, &server).unwrap();

        assert_eq!(changes.len(), 1);
        assert_eq!(changes[0].stamp(), "20260707T090000");
        let (ours, theirs) = update(&changes[0]);
        assert!(ours.contains(&format!("DTSTART;TZID={ZONE}:20260707T090000\r\n")));
        assert!(ours.contains("SUMMARY:Standup\r\n"));
        assert!(theirs.contains("SUMMARY:Standup, late\r\n"));
    }

    /// The `EXDATE` takes the exception with it, which is one removal and
    /// not a revert besides.
    #[test]
    fn an_occurrence_deleted_alone_is_one_removal() {
        let staged = remove(&server(), &this("20260707T090000", json!({})))
            .unwrap()
            .unwrap();

        let changes = occurrence_changes(&staged, &server()).unwrap();

        assert_eq!(
            changes,
            [OccurrenceChange {
                id: IcalRecurDateTime::parse("20260707T090000").unwrap(),
                write: OccurrenceWrite::Delete,
            }]
        );
    }

    #[test]
    fn a_cancelled_override_removes_its_occurrence_once() {
        let staged = write(
            &server(),
            &this("20260709T090000", json!({"status": "CANCELLED"})),
        )
        .unwrap();

        let changes = occurrence_changes(&staged, &server()).unwrap();

        assert_eq!(changes.len(), 1);
        assert_eq!(changes[0].write, OccurrenceWrite::Delete);
        assert_eq!(occurrence_changes(&staged, &staged).unwrap(), []);
    }

    /// 07:00 UTC is 09:00 in Paris in July: one occurrence either way.
    #[test]
    fn an_exdate_in_another_zone_names_the_same_occurrence() {
        let excluded = |exdate: &str| {
            server().replace(
                "RRULE:FREQ=DAILY;COUNT=5\r\n",
                &format!("RRULE:FREQ=DAILY;COUNT=5\r\n{exdate}\r\n"),
            )
        };
        let staged = excluded(&format!("EXDATE;TZID={ZONE}:20260708T090000"));
        let held = excluded("EXDATE:20260708T070000Z");

        assert_eq!(occurrence_changes(&staged, &held).unwrap(), []);
        assert_eq!(occurrence_changes(&staged, &server()).unwrap().len(), 1);
    }

    /// Neither Graph nor Google brings a removed occurrence back.
    #[test]
    fn an_exdate_dropped_is_left_to_the_server() {
        let held = server().replace(
            "RRULE:FREQ=DAILY;COUNT=5\r\n",
            &format!("RRULE:FREQ=DAILY;COUNT=5\r\nEXDATE;TZID={ZONE}:20260710T090000\r\n"),
        );

        assert_eq!(occurrence_changes(&server(), &held).unwrap(), []);
    }

    #[test]
    fn an_instance_is_told_by_its_original_start_in_the_series_zone() {
        assert_eq!(
            occurrences(&server()).unwrap(),
            [IcalRecurDateTime::parse("20260707T090000").unwrap()]
        );
        assert_eq!(
            occurrence_window(IcalRecurDateTime::parse("20260707T090000").unwrap()),
            ("2026-07-05T09:00:00Z".into(), "2026-07-09T09:00:00Z".into())
        );
    }
}
