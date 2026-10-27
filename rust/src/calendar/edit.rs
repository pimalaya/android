//! # Editing
//!
//! A new calendar object, and the edits its entry page makes to one,
//! patched onto its syntax tree so what the form does not manage survives.

use ical::{
    param::IcalParam,
    prop::{
        IcalProp, IcalPropKind, IcalPropName, categories::CATEGORIES, contact::CONTACT,
        description::DESCRIPTION, location::LOCATION, percent_complete::PERCENT_COMPLETE,
        priority::PRIORITY, status::STATUS, summary::SUMMARY, url::URL,
    },
    tree::{
        cst::{IcalCst, IcalItem},
        prop::lens::IcalPropLens,
        value::cursor::IcalValueCursor,
    },
    value::{
        IcalValue,
        binary::IcalBinary,
        datetime::{IcalDate, IcalDateTime},
        integer::IcalInteger,
        text::{IcalText, IcalTextList},
        uri::IcalUri,
    },
};
use serde::Deserialize;

use crate::{
    calendar::{
        PRODID, child, child_mut, cst_of, line, line_mut, scheduled, series,
        task::{self, TaskContact},
        text,
        zone::{EventTime, EventTimeKind, Zones},
    },
    error::BridgeError,
};

/// What one edit changes on a component.
///
/// Every field is optional, an absent one left alone: the form sends what
/// it changed. A present field that is empty <em>removes</em> the
/// property, which is how a form clears a value.
#[derive(Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EventEdit {
    pub summary: Option<String>,
    pub description: Option<String>,
    pub location: Option<String>,
    pub url: Option<String>,
    pub status: Option<String>,
    pub categories: Option<String>,
    pub priority: Option<String>,
    pub start: Option<DateEdit>,
    pub end: Option<DateEdit>,
    pub due: Option<DateEdit>,
    /// Civil UTC, whatever the rest of the component is: `COMPLETED` is
    /// a UTC DATE-TIME by RFC 5545 3.8.2.1.
    pub completed: Option<DateEdit>,
    pub percent_complete: Option<String>,
    /// The GTD list (`X-PIMALAYA-GTD`), one of [`task::LISTS`]; empty
    /// makes the to-do a next action.
    pub gtd: Option<String>,
    /// The `UID` of the project, a `RELATED-TO` of type `PARENT`; empty
    /// detaches it, other relations kept.
    pub parent: Option<String>,
    /// Who a Waiting For item waits on (`CONTACT`); empty name and
    /// address remove it.
    pub contact: Option<TaskContact>,
    /// The `Message-ID` of the mail it came from, an `ATTACH` of its
    /// `mid:` URI; empty removes that link, other attachments kept.
    pub source: Option<String>,
    /// Now, a UTC `YYYYMMDDTHHMMSSZ` stamp for `DTSTAMP` and
    /// `LAST-MODIFIED`, passed in so this stays a pure function.
    pub stamp: Option<String>,
    /// Which occurrences of a series the edit applies to.
    pub scope: EventScope,
    /// The occurrence the page was opened on, as
    /// [`crate::calendar::expand::Occurrence::recurrence_id`] gave it.
    ///
    /// With it, an edit's dates are that occurrence's, and a whole-series
    /// edit moves the series by as much as the occurrence moved.
    pub recurrence_id: Option<String>,
    /// That identity's instant, a UTC stamp, which the Java side reads
    /// off the platform's database: what a split's `UNTIL` is computed
    /// from when the zone is not one the object defines.
    pub recurrence_instant: Option<String>,
    /// The `UID` of the series a split starts, minted by the caller.
    pub uid: Option<String>,
    /// The `VTIMEZONE` of a zone the edit writes a date in, inserted when
    /// the object does not define that zone yet (RFC 5545 3.2.19).
    pub vtimezone: Option<String>,
}

/// One date an edit writes.
#[derive(Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DateEdit {
    /// The new value, civil, `YYYYMMDD` or `YYYYMMDDTHHMMSS`; empty
    /// removes the property.
    pub time: String,
    /// What the value is relative to. Absent, it is relative to what the
    /// replaced property already was, `TZID` and `VALUE` kept.
    pub kind: Option<EventTimeKind>,
    /// The `TZID` of a zoned value.
    pub tzid: String,
}

/// Which occurrences of a series an edit applies to.
#[derive(Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EventScope {
    /// The series itself, which is all an entry that does not recur has.
    #[default]
    All,
    /// The occurrence named, through an override or an `EXDATE`.
    This,
    /// The occurrence named and every later one, by splitting the series.
    Following,
}

/// What [`create`] composes a new entry from, every value the caller's.
pub struct EventDraft<'a> {
    /// The component, `VEVENT`, `VTODO` or `VJOURNAL`.
    pub component: &'a str,
    /// The `UID` identifying the entry.
    pub uid: &'a str,
    /// The `DTSTAMP` saying when it was composed.
    pub stamp: &'a str,
    /// The `DTSTART`: a date, a floating time or a time in [`Self::tzid`].
    pub start: &'a str,
    /// The zone of the start, empty for none.
    pub tzid: &'a str,
    /// The `VTIMEZONE` defining [`Self::tzid`].
    pub vtimezone: &'a str,
}

/// The object a new entry starts from: `UID`, `DTSTAMP`, `DTSTART`, its zone.
///
/// RFC 5545 3.8.4.7 and 3.8.7.2. The start places it on a day, as a page
/// opening on no date has nowhere to put it. A `TZID` owes a `VTIMEZONE`
/// (3.2.19), the caller's: no database, clock read or id minted here.
pub fn create(draft: &EventDraft) -> Result<String, BridgeError> {
    let EventDraft {
        component,
        uid,
        stamp,
        start,
        tzid,
        vtimezone,
    } = *draft;
    let kind = match component {
        "VEVENT" | "VTODO" | "VJOURNAL" => component,
        other => return Err(format!("Cannot create a `{other}`").into()),
    };

    // NOTE: a to-do needs no date (RFC 5545 3.6.2): a capture is not
    // placed on a day until it is clarified into one.
    if start.is_empty() && kind != "VTODO" {
        return Err(format!("A `{kind}` needs a start").into());
    }
    let (dtstart, zone) = match (start.len(), tzid.is_empty()) {
        (0, _) => (String::new(), ""),
        (8, _) => (format!("DTSTART;VALUE=DATE:{start}\r\n"), ""),
        (_, true) => (format!("DTSTART:{start}\r\n"), ""),
        _ => (
            format!("DTSTART;TZID={tzid}:{start}\r\n"),
            vtimezone.trim_end(),
        ),
    };
    let zone = match zone.is_empty() {
        true => String::new(),
        false => format!("{zone}\r\n"),
    };

    let object = format!(
        "BEGIN:VCALENDAR\r\n\
         VERSION:2.0\r\n\
         PRODID:{PRODID}\r\n\
         {zone}\
         BEGIN:{kind}\r\n\
         UID:{uid}\r\n\
         DTSTAMP:{stamp}\r\n\
         {dtstart}\
         END:{kind}\r\n\
         END:VCALENDAR\r\n"
    );

    // NOTE: parsed back rather than returned as built. The values come
    // from the caller, and an object this refused to read would be one
    // the page then opens on nothing.
    let cst = cst_of(object.as_str())?;
    if !tzid.is_empty() && start.len() > 8 && Zones::of_cst(&cst).find(tzid).is_none() {
        return Err(format!("No definition of the zone `{tzid}`").into());
    }

    Ok(object)
}

/// Applies one edit to a calendar object, returning the new iCalendar.
///
/// A syntax-tree patch, never a rebuild: a client rewriting what it does
/// not manage (alarms, X- properties, attendees) loses other clients'
/// data. [`series::split`] splits a series, as it writes two objects.
pub fn write(ical: &str, edit: &str) -> Result<String, BridgeError> {
    let edit: EventEdit = serde_json::from_str(edit).map_err(|err| err.to_string())?;

    let cst = cst_of(ical)?;
    let mut cst = cst.into_static();

    // NOTE: a to-do with no date is no series (RFC 5545 3.6.2): nothing
    // to rebase or recur, so its one component is patched as it is.
    let undated = scheduled(&cst).first().copied().filter(|index| {
        let component = child(&cst, *index);
        ["DTSTART", "DUE", "RRULE"]
            .iter()
            .all(|name| line(component, name).is_none())
    });
    match (edit.scope, undated) {
        (EventScope::All, Some(index)) => patch(child_mut(&mut cst, index), &edit),
        (EventScope::All, None) => series::all(&mut cst, &edit)?,
        (EventScope::This, _) => series::this(&mut cst, &edit)?,
        (EventScope::Following, _) => return Err("Splitting a series writes two objects".into()),
    }
    define_zone(&mut cst, &edit)?;

    text(&cst)
}

/// Replaces every edited property of one component, in place.
pub fn patch(component: &mut IcalCst<'static>, edit: &EventEdit) {
    replace_text::<SUMMARY>(component, IcalPropKind::Summary, &edit.summary);
    replace_text::<DESCRIPTION>(component, IcalPropKind::Description, &edit.description);
    replace_text::<LOCATION>(component, IcalPropKind::Location, &edit.location);
    replace_text::<STATUS>(component, IcalPropKind::Status, &edit.status);

    if let Some(value) = &edit.url {
        component.remove::<URL>();
        if !value.is_empty() {
            component.push(prop(
                IcalPropKind::Url,
                Vec::new(),
                IcalValue::Uri(IcalUri(value.clone().into())),
            ));
        }
    }

    if let Some(value) = &edit.categories {
        component.remove::<CATEGORIES>();
        let items: Vec<_> = value
            .split(',')
            .map(str::trim)
            .filter(|item| !item.is_empty())
            .map(|item| item.to_string().into())
            .collect();
        if !items.is_empty() {
            component.push(prop(
                IcalPropKind::Categories,
                Vec::new(),
                IcalValue::TextList(IcalTextList(items)),
            ));
        }
    }

    replace_number::<PRIORITY>(component, IcalPropKind::Priority, &edit.priority);
    replace_number::<PERCENT_COMPLETE>(
        component,
        IcalPropKind::PercentComplete,
        &edit.percent_complete,
    );

    if let Some(date) = &edit.start {
        replace_date(component, IcalPropKind::DtStart, date);
    }
    if let Some(date) = &edit.end {
        replace_date(component, IcalPropKind::DtEnd, date);
    }
    if let Some(date) = &edit.due {
        replace_date(component, IcalPropKind::Due, date);
    }
    if let Some(date) = &edit.completed {
        let utc = DateEdit {
            kind: Some(EventTimeKind::Utc),
            ..date.clone()
        };
        replace_date(component, IcalPropKind::Completed, &utc);
    }

    patch_task(component, edit);
    touch(component, edit);
}

/// Replaces the GTD properties of a to-do (cairn/changes/gtd), each on
/// its own so what another client relates or attaches stays.
fn patch_task(component: &mut IcalCst<'static>, edit: &EventEdit) {
    if let Some(list) = &edit.gtd {
        remove_named(component, task::GTD);
        let list = list.trim().to_ascii_uppercase();
        if task::LISTS.contains(&list.as_str()) {
            component.push(IcalProp {
                name: IcalPropName::Unknown(task::GTD.into()),
                params: Vec::new(),
                value: IcalValue::Text(IcalText(list.into())),
            });
        }
    }

    if let Some(parent) = &edit.parent {
        component.items.retain(|item| match item {
            IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case("RELATED-TO") => {
                let decoded = line.decode(ical::version::IcalVersion::V2_0);
                !decoded.params.iter().all(|param| match param {
                    IcalParam::RelType(kind) => kind.eq_ignore_ascii_case("PARENT"),
                    _ => true,
                })
            }
            _ => true,
        });
        if !parent.trim().is_empty() {
            component.push(prop(
                IcalPropKind::RelatedTo,
                vec![IcalParam::RelType("PARENT".into())],
                IcalValue::Text(IcalText(parent.trim().to_string().into())),
            ));
        }
    }

    if let Some(contact) = &edit.contact {
        component.remove::<CONTACT>();
        let name = contact.name.trim();
        let address = contact.address.trim();
        if !name.is_empty() || !address.is_empty() {
            let params = match address.is_empty() {
                true => Vec::new(),
                false => vec![IcalParam::AltRep(format!("mailto:{address}").into())],
            };
            let text = match name.is_empty() {
                true => address,
                false => name,
            };
            component.push(prop(
                IcalPropKind::Contact,
                params,
                IcalValue::Text(IcalText(text.to_string().into())),
            ));
        }
    }

    if let Some(source) = &edit.source {
        component.items.retain(|item| match item {
            IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case("ATTACH") => {
                task::message_id_of(&line.value.decode()).is_none()
            }
            _ => true,
        });
        if !source.trim().is_empty() {
            component.push(prop(
                IcalPropKind::Attach,
                Vec::new(),
                IcalValue::Binary(IcalBinary::Uri(task::mid_uri(source).into())),
            ));
        }
    }
}

/// Stamps a component as edited now.
///
/// RFC 5545 3.8.7.2 and 3.8.7.3: an edit is when the object was last
/// built, and when it last changed. Both, because a server and another
/// client read different ones.
pub fn touch(component: &mut IcalCst<'static>, edit: &EventEdit) {
    let Some(stamp) = &edit.stamp else {
        return;
    };

    let utc = DateEdit {
        time: stamp.trim_end_matches(['Z', 'z']).to_string(),
        kind: Some(EventTimeKind::Utc),
        tzid: String::new(),
    };
    replace_date(component, IcalPropKind::DtStamp, &utc);
    replace_date(component, IcalPropKind::LastModified, &utc);
}

/// Replaces a text property, removing it when the edit clears it.
fn replace_text<L: IcalPropLens>(
    component: &mut IcalCst<'static>,
    kind: IcalPropKind,
    value: &Option<String>,
) {
    let Some(value) = value else {
        return;
    };

    component.remove::<L>();
    if !value.is_empty() {
        component.push(prop(
            kind,
            Vec::new(),
            IcalValue::Text(IcalText(value.clone().into())),
        ));
    }
}

/// The same for an integer property, ignoring anything unreadable
/// rather than writing a number the property cannot hold.
fn replace_number<L: IcalPropLens>(
    component: &mut IcalCst<'static>,
    kind: IcalPropKind,
    value: &Option<String>,
) {
    let Some(value) = value else {
        return;
    };

    component.remove::<L>();
    if value.parse::<i64>().is_ok() {
        component.push(prop(
            kind,
            Vec::new(),
            IcalValue::Integer(IcalInteger(value.clone().into())),
        ));
    }
}

/// Replaces a date property.
///
/// A value relative to nothing new changes only its line's digits,
/// `TZID`, `VALUE` and position kept. Another is written fresh with its
/// zone's parameters: servers reject a bare `YYYYMMDD` lacking `VALUE=DATE`.
fn replace_date(component: &mut IcalCst<'static>, kind: IcalPropKind, date: &DateEdit) {
    let name = kind.to_string();
    if date.time.is_empty() {
        remove_named(component, &name);
        return;
    }

    if date.kind.is_none()
        && let Some(line) = line_mut(component, &name)
        && let Some(old) = EventTime::of_line(line)
        && (old.kind == EventTimeKind::Date) == (date.time.len() == 8)
    {
        let value = EventTime {
            time: date.time.clone(),
            ..old
        };
        IcalValueCursor { line }.set_bytes(value.wire());
        return;
    }

    let time = EventTime {
        time: date.time.clone(),
        kind: date.kind.unwrap_or(match date.time.len() {
            8 => EventTimeKind::Date,
            _ => EventTimeKind::Floating,
        }),
        tzid: date.tzid.clone(),
        offset: None,
    };
    remove_named(component, &name);
    component.push(date_prop(kind, &time));
}

/// A date property carrying one time, with the parameters its zone
/// needs.
pub fn date_prop(kind: IcalPropKind, time: &EventTime) -> IcalProp<'static> {
    let (params, value) = match time.kind {
        EventTimeKind::Date => (
            vec![IcalParam::Value("DATE".into())],
            IcalValue::Date(IcalDate(time.time.clone().into())),
        ),
        EventTimeKind::Zoned => (
            vec![IcalParam::TzId(time.tzid.clone().into())],
            IcalValue::DateTime(IcalDateTime(time.time.clone().into())),
        ),
        _ => (
            Vec::new(),
            IcalValue::DateTime(IcalDateTime(time.wire().into())),
        ),
    };

    prop(kind, params, value)
}

/// Inserts the `VTIMEZONE` an edit carries when a date it writes is in
/// that zone and the object does not define it yet.
pub fn define_zone(cst: &mut IcalCst<'static>, edit: &EventEdit) -> Result<(), BridgeError> {
    let Some(definition) = edit.vtimezone.as_deref().filter(|text| !text.is_empty()) else {
        return Ok(());
    };

    let zone = cst_of(definition)?.into_static();
    let tzid = line(&zone, "TZID")
        .map(|line| line.value.decode().into_owned())
        .ok_or("The zone definition carries no TZID")?;

    let named = [&edit.start, &edit.end, &edit.due]
        .into_iter()
        .flatten()
        .any(|date| date.kind == Some(EventTimeKind::Zoned) && date.tzid == tzid);
    if !named || Zones::of_cst(cst).find(&tzid).is_some() {
        return Ok(());
    }

    // NOTE: before the components that use it, where every producer
    // puts it, though RFC 5545 does not require the order.
    let at = scheduled(cst).first().copied().unwrap_or(cst.items.len());
    cst.items.insert(at, IcalItem::Component(Box::new(zone)));
    Ok(())
}

pub fn prop(
    kind: IcalPropKind,
    params: Vec<IcalParam<'static>>,
    value: IcalValue<'static>,
) -> IcalProp<'static> {
    IcalProp {
        name: IcalPropName::Kind(kind),
        params,
        value,
    }
}

/// Drops every line of one property from a component.
pub fn remove_named(component: &mut IcalCst<'static>, name: &str) {
    component.items.retain(
        |item| !matches!(item, IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case(name)),
    );
}

#[cfg(test)]
mod tests {
    use crate::calendar::{
        edit::*,
        expand::expand,
        read::read,
        tests::{ROMANCE, object},
    };

    #[test]
    fn an_edit_patches_and_leaves_everything_else_alone() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:42\r\nDTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n\
             SUMMARY:Standup\r\nLOCATION:Room 3\r\nX-VENDOR-FLAG:keep me\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\n\
             END:VEVENT\r\n",
        );

        let written = write(
            &ical,
            r#"{"summary":"Daily standup","location":"","stamp":"20260809T150000Z"}"#,
        )
        .unwrap();

        assert!(written.contains("SUMMARY:Daily standup"));
        assert!(!written.contains("Standup\r\n"));
        // NOTE: cleared, so the property goes rather than being written
        // empty.
        assert!(!written.contains("LOCATION"));
        // NOTE: a client that rewrites what it does not understand loses
        // other clients' data, so none of this may move.
        assert!(written.contains("X-VENDOR-FLAG:keep me"));
        assert!(written.contains("BEGIN:VALARM"));
        assert!(written.contains("TRIGGER:-PT10M"));
        assert!(written.contains("UID:42"));
        assert!(written.contains("DTSTART:20260105T090000"));
        assert!(written.contains("LAST-MODIFIED:20260809T150000Z"));
        // NOTE: RFC 5545 3.6.1 lists an event's properties before its
        // alarms.
        assert!(written.find("SUMMARY").unwrap() < written.find("BEGIN:VALARM").unwrap());
        assert!(written.find("DTSTAMP").unwrap() < written.find("BEGIN:VALARM").unwrap());

        let detail = read(&written, "").unwrap();
        assert_eq!(detail.summary, "Daily standup");
        assert_eq!(detail.location, "");
    }

    #[test]
    fn a_written_line_is_folded_and_an_untouched_one_kept() {
        let long = "x".repeat(90);
        let ical = object(&format!(
            "BEGIN:VEVENT\r\nUID:42\r\nDTSTART:20260105T090000\r\nX-VENDOR:{long}\r\nEND:VEVENT\r\n"
        ));
        let edit = serde_json::json!({"summary": long});

        let written = write(&ical, &edit.to_string()).unwrap();

        // NOTE: RFC 5545 3.1: 75 octets a line, the rest on a line of its
        // own after a space.
        let summary = format!("SUMMARY:{long}");
        assert!(written.contains(&format!("{}\r\n {}\r\n", &summary[..75], &summary[75..])));
        assert!(written.contains(&format!("X-VENDOR:{long}\r\n")));
        assert_eq!(read(&written, "").unwrap().summary, long);
    }

    #[test]
    fn a_replaced_date_keeps_its_zone_and_its_value_type() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\n\
             DTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             DTEND;TZID=/example.org/Romance:20260706T100000\r\nEND:VEVENT\r\n\
             BEGIN:VTODO\r\nUID:t\r\nDUE;VALUE=DATE:20260710\r\nEND:VTODO\r\n"
        ));

        let written = write(
            &ical,
            r#"{"start":{"time":"20260706T100000"},"end":{"time":"20260706T110000"}}"#,
        )
        .unwrap();

        assert!(written.contains("DTSTART;TZID=/example.org/Romance:20260706T100000\r\n"));
        assert!(written.contains("DTEND;TZID=/example.org/Romance:20260706T110000\r\n"));

        let utc = object("BEGIN:VEVENT\r\nUID:u\r\nDTSTART:20260706T070000Z\r\nEND:VEVENT\r\n");
        let written = write(&utc, r#"{"start":{"time":"20260706T080000"}}"#).unwrap();
        assert!(written.contains("DTSTART:20260706T080000Z\r\n"));

        let date = object(
            "BEGIN:VEVENT\r\nUID:d\r\nDTSTART;VALUE=DATE:20260706\r\n\
             X-KEEP;VALUE=TEXT:me\r\nEND:VEVENT\r\n",
        );
        let written = write(&date, r#"{"start":{"time":"20260707"}}"#).unwrap();
        assert!(written.contains("DTSTART;VALUE=DATE:20260707\r\n"));
    }

    #[test]
    fn a_date_naming_its_own_zone_is_written_in_it() {
        let ical = object("BEGIN:VEVENT\r\nUID:9\r\nDTSTART:20260105T090000\r\nEND:VEVENT\r\n");

        let written = write(&ical, r#"{"start":{"time":"20260105","kind":"date"}}"#).unwrap();
        // NOTE: without VALUE=DATE a bare YYYYMMDD is not a legal DATE-TIME
        // and servers reject the object.
        assert!(written.contains("DTSTART;VALUE=DATE:20260105"));
        assert!(read(&written, "").unwrap().all_day);

        let edit = serde_json::json!({
            "start": {"time": "20260105T090000", "kind": "zoned", "tzid": "/example.org/Romance"},
            "vtimezone": ROMANCE,
        });
        let zoned = write(&written, &edit.to_string()).unwrap();
        assert!(zoned.contains("DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"));
        // NOTE: RFC 5545 3.2.19: a TZID owes its definition, which the edit
        // brought, written once and before the event.
        assert_eq!(zoned.matches("BEGIN:VTIMEZONE").count(), 1);
        assert!(zoned.find("BEGIN:VTIMEZONE").unwrap() < zoned.find("BEGIN:VEVENT").unwrap());
        let again = write(&zoned, &edit.to_string()).unwrap();
        assert_eq!(again.matches("BEGIN:VTIMEZONE").count(), 1);
    }

    #[test]
    fn an_edit_reaches_a_todo_and_a_journal_entry_too() {
        let todo = object("BEGIN:VTODO\r\nUID:7\r\nDUE:20260115T170000\r\nEND:VTODO\r\n");
        let written = write(&todo, r#"{"summary":"File taxes","percentComplete":"40"}"#).unwrap();
        assert!(written.contains("SUMMARY:File taxes"));
        assert!(written.contains("PERCENT-COMPLETE:40"));

        let journal = object("BEGIN:VJOURNAL\r\nUID:3\r\nDTSTART:20260105\r\nEND:VJOURNAL\r\n");
        let written = write(&journal, r#"{"description":"Notes, and more"}"#).unwrap();
        // NOTE: the comma is data, not a separator, so it escapes on the
        // wire.
        assert!(written.contains("DESCRIPTION:Notes\\, and more"));
        assert_eq!(read(&written, "").unwrap().description, "Notes, and more");
    }

    #[test]
    fn a_completion_is_written_in_utc() {
        let todo = object("BEGIN:VTODO\r\nUID:7\r\nDUE:20260115T170000\r\nEND:VTODO\r\n");

        let written = write(&todo, r#"{"completed":{"time":"20260115T160000"}}"#).unwrap();

        assert!(written.contains("COMPLETED:20260115T160000Z\r\n"));
    }

    #[test]
    fn a_new_object_is_one_the_page_can_open_and_edit() {
        let created = create(&EventDraft {
            component: "VEVENT",
            uid: "abc-123",
            stamp: "20260105T080000Z",
            start: "20260105T090000",
            tzid: "",
            vtimezone: "",
        })
        .unwrap();

        // NOTE: a new entry must open on the page and land on an agenda
        // day, the whole reason it carries a DTSTART it did not have to.
        let detail = read(&created, "").unwrap();
        assert_eq!(detail.component, "VEVENT");
        assert_eq!(detail.uid, "abc-123");
        assert_eq!(detail.start.unwrap().time, "20260105T090000");
        assert!(detail.summary.is_empty());
        assert_eq!(
            1,
            expand(&created, "20260101T000000", "20260201T000000")
                .unwrap()
                .len()
        );

        let written = write(&created, r#"{"summary":"Dentist"}"#).unwrap();
        assert_eq!(read(&written, "").unwrap().summary, "Dentist");
    }

    #[test]
    fn a_new_object_starts_in_its_zone_with_the_definition_beside_it() {
        let created = create(&EventDraft {
            component: "VEVENT",
            uid: "abc-123",
            stamp: "20260105T080000Z",
            start: "20260706T090000",
            tzid: "/example.org/Romance",
            vtimezone: ROMANCE,
        })
        .unwrap();

        assert!(created.contains("DTSTART;TZID=/example.org/Romance:20260706T090000\r\n"));
        let found = expand(&created, "20260701T000000", "20260801T000000").unwrap();
        assert_eq!(found[0].start.offset, Some(7200));

        // NOTE: a zone nobody defined is one no reader can place.
        assert!(
            create(&EventDraft {
                component: "VEVENT",
                uid: "1",
                stamp: "20260105T080000Z",
                start: "20260706T090000",
                tzid: "Europe/Paris",
                vtimezone: "",
            })
            .is_err()
        );
        // NOTE: a date is in no zone, so it needs no definition.
        let day = create(&EventDraft {
            component: "VEVENT",
            uid: "1",
            stamp: "20260105T080000Z",
            start: "20260706",
            tzid: "Europe/Paris",
            vtimezone: ROMANCE,
        })
        .unwrap();
        assert!(day.contains("DTSTART;VALUE=DATE:20260706\r\n"));
        assert!(!day.contains("VTIMEZONE"));
    }

    #[test]
    fn only_the_three_scheduled_components_can_be_created() {
        let new = |component, start| {
            create(&EventDraft {
                component,
                uid: "1",
                stamp: "20260105T080000Z",
                start,
                tzid: "",
                vtimezone: "",
            })
        };

        assert!(new("VTODO", "20260105T090000").is_ok());
        assert!(new("VJOURNAL", "20260105").is_ok());
        assert!(new("VTIMEZONE", "20260105T090000").is_err());
    }
}
