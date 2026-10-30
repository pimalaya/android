//! An entry's alarms as its page reads and writes them (RFC 5545 3.6.6).
//!
//! Every `VALARM` of the master component, whatever its action: its
//! trigger, its message, an email alarm's subject and recipients, and how
//! it repeats. A display or audio alarm is what the phone rings through
//! the mirror ([`super::phone`]); an email alarm is another client's to
//! send. An edit lists the alarms the page holds: one it kept as read
//! stays byte for byte, one it changed has only those properties
//! rewritten, one it left out goes, and a new one is built whole.

use ical::{
    param::IcalParam,
    prop::IcalPropKind,
    tree::{cst::IcalCst, cst::IcalItem},
    value::{
        IcalValue, cal_address::IcalCalAddress, datetime::IcalDateTime, duration::IcalDuration,
        integer::IcalInteger, text::IcalText,
    },
};
use serde::{Deserialize, Serialize};

use crate::{
    calendar::{
        child, child_mut, cst_of, decoded,
        edit::{EventEdit, prop, remove_named, touch},
        line, line_mut, raw_value,
        read::user_address,
        scheduled, text,
    },
    error::BridgeError,
};

/// One alarm of an entry, as read, or as an edit holds it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Alarm {
    /// Its place among the master's alarms; none for a new one.
    pub index: Option<usize>,
    /// `ACTION`, uppercased: `DISPLAY`, `AUDIO`, `EMAIL`, or another.
    pub action: String,
    /// Minutes before the start, after it when negative, for a trigger off
    /// the start.
    pub minutes: Option<i64>,
    /// Minutes before the end, after it when negative, for a trigger
    /// related to the end.
    pub minutes_before_end: Option<i64>,
    /// An absolute trigger, as written.
    pub absolute: Option<String>,
    /// `DESCRIPTION`: what a display alarm shows, an email alarm's body.
    pub description: String,
    /// `SUMMARY`: an email alarm's subject.
    pub summary: String,
    /// An email alarm's recipients, their addresses.
    pub attendees: Vec<String>,
    /// `REPEAT`: how many more times it rings.
    pub repeat: i64,
    /// `DURATION` between rings, in minutes.
    pub every_minutes: Option<i64>,
}

/// An edit of an entry's alarms.
#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AlarmEdit {
    /// Every alarm the entry keeps, in order, a new one carrying no index.
    pub alarms: Vec<Alarm>,
    /// Now, a UTC stamp, for `DTSTAMP` and `LAST-MODIFIED`.
    pub stamp: String,
}

/// Every alarm of an object's master component, in order.
pub fn alarms(ical: &str) -> Result<Vec<Alarm>, BridgeError> {
    let cst = cst_of(ical)?.into_static();
    let Some(master) = master(&cst) else {
        return Ok(Vec::new());
    };
    Ok(placed(child(&cst, master))
        .into_iter()
        .enumerate()
        .map(|(index, at)| Alarm {
            index: Some(index),
            ..alarm_of(alarm_at(child(&cst, master), at))
        })
        .collect())
}

/// Patches an edit of the alarms onto the object; unchanged when the edit
/// changes nothing.
pub fn apply(ical: &str, edit: &str) -> Result<String, BridgeError> {
    let edit: AlarmEdit = serde_json::from_str(edit).map_err(|err| err.to_string())?;
    let mut cst = cst_of(ical)?.into_static();
    let Some(master) = master(&cst) else {
        return Err("The object holds no entry to set alarms on".into());
    };

    let component = child_mut(&mut cst, master);
    let held = placed(component);
    let mut changed = false;

    // NOTE: the kept and changed ones first, by their place, so removing
    // the dropped ones afterwards, last first, keeps every place valid.
    for alarm in &edit.alarms {
        let Some(index) = alarm.index else {
            continue;
        };
        let Some(&at) = held.get(index) else {
            return Err(format!("The entry holds no alarm {index}").into());
        };
        changed |= patch(alarm_mut(component, at), alarm);
    }
    let kept: Vec<usize> = edit.alarms.iter().filter_map(|alarm| alarm.index).collect();
    for (index, at) in held.iter().enumerate().rev() {
        if !kept.contains(&index) {
            component.items.remove(*at);
            changed = true;
        }
    }
    for alarm in edit.alarms.iter().filter(|alarm| alarm.index.is_none()) {
        component.push_component(built(alarm));
        changed = true;
    }

    if !changed {
        return Ok(ical.to_string());
    }
    let stamp = EventEdit {
        stamp: Some(edit.stamp.clone()),
        ..Default::default()
    };
    touch(component, &stamp);
    text(&cst)
}

/// Where the master component sits: the first scheduled one that is no
/// override.
fn master(cst: &IcalCst<'static>) -> Option<usize> {
    scheduled(cst)
        .into_iter()
        .find(|index| line(child(cst, *index), "RECURRENCE-ID").is_none())
}

/// Where each `VALARM` sits among a component's items.
fn placed(component: &IcalCst<'static>) -> Vec<usize> {
    component
        .items
        .iter()
        .enumerate()
        .filter_map(|(index, item)| match item {
            IcalItem::Component(nested) if is_alarm(nested) => Some(index),
            _ => None,
        })
        .collect()
}

fn is_alarm(component: &IcalCst) -> bool {
    component
        .begin
        .as_ref()
        .is_some_and(|begin| begin.raw_value_str().eq_ignore_ascii_case("VALARM"))
}

fn alarm_at<'c>(component: &'c IcalCst<'static>, at: usize) -> &'c IcalCst<'static> {
    match &component.items[at] {
        IcalItem::Component(alarm) => alarm,
        _ => unreachable!("not an alarm"),
    }
}

fn alarm_mut<'c>(component: &'c mut IcalCst<'static>, at: usize) -> &'c mut IcalCst<'static> {
    match &mut component.items[at] {
        IcalItem::Component(alarm) => alarm,
        _ => unreachable!("not an alarm"),
    }
}

/// One alarm read.
fn alarm_of(alarm: &IcalCst<'static>) -> Alarm {
    let decoded = decoded(alarm);
    let mut read = Alarm::default();
    for prop in &decoded.props {
        match (&prop.name, &prop.value) {
            (ical::prop::IcalPropName::Kind(IcalPropKind::Action), _) => {
                read.action = raw_value(prop).unwrap_or_default().to_ascii_uppercase();
            }
            (ical::prop::IcalPropName::Kind(IcalPropKind::Trigger), IcalValue::Duration(value)) => {
                let Some(seconds) = value.seconds() else {
                    continue;
                };
                let on_end = prop.params.iter().any(|param| {
                    matches!(param, IcalParam::Related(related) if related.eq_ignore_ascii_case("END"))
                });
                match on_end {
                    true => read.minutes_before_end = Some(-seconds / 60),
                    false => read.minutes = Some(-seconds / 60),
                }
            }
            (ical::prop::IcalPropName::Kind(IcalPropKind::Trigger), IcalValue::DateTime(value)) => {
                read.absolute = Some(value.0.to_string());
            }
            (ical::prop::IcalPropName::Kind(IcalPropKind::Description), _) => {
                read.description = text_value(&prop.value);
            }
            (ical::prop::IcalPropName::Kind(IcalPropKind::Summary), _) => {
                read.summary = text_value(&prop.value);
            }
            (ical::prop::IcalPropName::Kind(IcalPropKind::Attendee), _) => {
                if let Some(address) = user_address(prop).filter(|address| !address.is_empty()) {
                    read.attendees.push(address);
                }
            }
            (ical::prop::IcalPropName::Kind(IcalPropKind::Repeat), IcalValue::Integer(value)) => {
                read.repeat = value.get().unwrap_or_default();
            }
            (
                ical::prop::IcalPropName::Kind(IcalPropKind::Duration),
                IcalValue::Duration(value),
            ) => {
                read.every_minutes = value.seconds().map(|seconds| seconds / 60);
            }
            _ => {}
        }
    }
    read
}

fn text_value(value: &IcalValue) -> String {
    match value {
        IcalValue::Text(text) => text.0.to_string(),
        _ => String::new(),
    }
}

/// Rewrites what differs between an alarm as read and as the edit holds
/// it; whether anything did.
fn patch(alarm: &mut IcalCst<'static>, wanted: &Alarm) -> bool {
    let read = alarm_of(alarm);
    let mut changed = false;

    if read.action != wanted.action {
        set(
            alarm,
            "ACTION",
            IcalPropKind::Action,
            plain(&wanted.action),
            Vec::new(),
        );
        changed = true;
    }
    let trigger_moved = read.minutes != wanted.minutes
        || read.minutes_before_end != wanted.minutes_before_end
        || read.absolute != wanted.absolute;
    if trigger_moved && let Some((value, params)) = trigger(wanted) {
        set(alarm, "TRIGGER", IcalPropKind::Trigger, value, params);
        changed = true;
    }
    if read.description != wanted.description {
        set_or_remove(
            alarm,
            "DESCRIPTION",
            IcalPropKind::Description,
            &wanted.description,
        );
        changed = true;
    }
    if read.summary != wanted.summary {
        set_or_remove(alarm, "SUMMARY", IcalPropKind::Summary, &wanted.summary);
        changed = true;
    }
    if read.attendees != wanted.attendees {
        remove_named(alarm, "ATTENDEE");
        for address in &wanted.attendees {
            alarm.push(prop(IcalPropKind::Attendee, Vec::new(), attendee(address)));
        }
        changed = true;
    }
    if read.repeat != wanted.repeat || read.every_minutes != wanted.every_minutes {
        remove_named(alarm, "REPEAT");
        remove_named(alarm, "DURATION");
        if let (true, Some(every)) = (wanted.repeat > 0, wanted.every_minutes) {
            alarm.push(prop(
                IcalPropKind::Repeat,
                Vec::new(),
                IcalValue::Integer(IcalInteger(wanted.repeat.to_string().into())),
            ));
            alarm.push(prop(
                IcalPropKind::Duration,
                Vec::new(),
                IcalValue::Duration(IcalDuration::from_seconds(every * 60)),
            ));
        }
        changed = true;
    }
    changed
}

/// A new alarm, as RFC 5545 3.6.6 asks of its action: a display one shows
/// its description, an email one carries a description, a subject and
/// its recipients.
fn built(wanted: &Alarm) -> IcalCst<'static> {
    let mut alarm = IcalCst::empty("VALARM");
    let action = match wanted.action.is_empty() {
        true => "DISPLAY",
        false => wanted.action.as_str(),
    };
    alarm.push(prop(IcalPropKind::Action, Vec::new(), plain(action)));
    if let Some((value, params)) = trigger(wanted) {
        alarm.push(prop(IcalPropKind::Trigger, params, value));
    }
    if !wanted.description.is_empty() {
        alarm.push(prop(
            IcalPropKind::Description,
            Vec::new(),
            plain(&wanted.description),
        ));
    }
    if !wanted.summary.is_empty() {
        alarm.push(prop(
            IcalPropKind::Summary,
            Vec::new(),
            plain(&wanted.summary),
        ));
    }
    for address in &wanted.attendees {
        alarm.push(prop(IcalPropKind::Attendee, Vec::new(), attendee(address)));
    }
    if let (true, Some(every)) = (wanted.repeat > 0, wanted.every_minutes) {
        alarm.push(prop(
            IcalPropKind::Repeat,
            Vec::new(),
            IcalValue::Integer(IcalInteger(wanted.repeat.to_string().into())),
        ));
        alarm.push(prop(
            IcalPropKind::Duration,
            Vec::new(),
            IcalValue::Duration(IcalDuration::from_seconds(every * 60)),
        ));
    }
    alarm
}

/// The trigger an alarm asks for: off the start, else off the end, else at
/// a moment, a UTC date-time as RFC 5545 3.8.6.3 requires.
fn trigger(wanted: &Alarm) -> Option<(IcalValue<'static>, Vec<IcalParam<'static>>)> {
    if let Some(minutes) = wanted.minutes {
        return Some((
            IcalValue::Duration(IcalDuration::from_seconds(-minutes * 60)),
            Vec::new(),
        ));
    }
    if let Some(minutes) = wanted.minutes_before_end {
        return Some((
            IcalValue::Duration(IcalDuration::from_seconds(-minutes * 60)),
            vec![IcalParam::Related("END".into())],
        ));
    }
    let absolute = wanted.absolute.as_ref()?;
    Some((
        IcalValue::DateTime(IcalDateTime(absolute.clone().into())),
        vec![IcalParam::Value("DATE-TIME".into())],
    ))
}

fn plain(value: &str) -> IcalValue<'static> {
    IcalValue::Text(IcalText(value.to_string().into()))
}

fn attendee(address: &str) -> IcalValue<'static> {
    IcalValue::CalAddress(IcalCalAddress(format!("mailto:{address}").into()))
}

/// Sets a property, its line replaced whole where it stands, else added.
fn set(
    alarm: &mut IcalCst<'static>,
    name: &str,
    kind: IcalPropKind,
    value: IcalValue<'static>,
    params: Vec<IcalParam<'static>>,
) {
    let encoded = prop(kind, params.clone(), value.clone())
        .encode(ical::tree::codec::mode::Escaper::default());
    match line_mut(alarm, name) {
        Some(line) => *line = encoded,
        None => {
            alarm.push(prop(kind, params, value));
        }
    }
}

fn set_or_remove(alarm: &mut IcalCst<'static>, name: &str, kind: IcalPropKind, value: &str) {
    match value.is_empty() {
        true => remove_named(alarm, name),
        false => set(alarm, name, kind, plain(value), Vec::new()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const EVENT: &str = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n\
        BEGIN:VEVENT\r\nUID:a\r\nDTSTAMP:20260101T000000Z\r\n\
        DTSTART:20260105T090000Z\r\nDTEND:20260105T100000Z\r\nSUMMARY:Review\r\n\
        BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nDESCRIPTION:Review\r\n\
        X-WR-ALARMUID:one\r\nEND:VALARM\r\n\
        BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER;RELATED=END:PT0S\r\nSUMMARY:Done?\r\n\
        DESCRIPTION:Write the notes\r\nATTENDEE:mailto:jane@example.org\r\nEND:VALARM\r\n\
        BEGIN:VALARM\r\nACTION:AUDIO\r\nTRIGGER:-PT1H\r\nREPEAT:2\r\nDURATION:PT5M\r\nEND:VALARM\r\n\
        END:VEVENT\r\nEND:VCALENDAR\r\n";

    fn edit(alarms: Vec<Alarm>) -> String {
        serde_json::json!({
            "alarms": alarms,
            "stamp": "20260301T120000Z",
        })
        .to_string()
    }

    #[test]
    fn every_alarm_reads_whatever_its_action() {
        let read = alarms(EVENT).unwrap();

        assert_eq!(3, read.len());
        assert_eq!("DISPLAY", read[0].action);
        assert_eq!(Some(10), read[0].minutes);
        assert_eq!("Review", read[0].description);
        assert_eq!("EMAIL", read[1].action);
        assert_eq!(Some(0), read[1].minutes_before_end);
        assert_eq!("Done?", read[1].summary);
        assert_eq!(vec!["jane@example.org".to_string()], read[1].attendees);
        assert_eq!("AUDIO", read[2].action);
        assert_eq!(2, read[2].repeat);
        assert_eq!(Some(5), read[2].every_minutes);
    }

    #[test]
    fn an_edit_keeping_everything_writes_nothing() {
        let written = apply(EVENT, &edit(alarms(EVENT).unwrap())).unwrap();

        assert_eq!(EVENT, written);
    }

    #[test]
    fn a_changed_alarm_rewrites_only_what_moved() {
        let mut read = alarms(EVENT).unwrap();
        read[0].minutes = Some(30);
        read[0].description = "Prepare the review".into();

        let written = apply(EVENT, &edit(read)).unwrap();

        assert!(written.contains(
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT30M\r\n\
             DESCRIPTION:Prepare the review\r\nX-WR-ALARMUID:one\r\nEND:VALARM\r\n"
        ));
        // NOTE: the others byte for byte.
        assert!(written.contains("TRIGGER;RELATED=END:PT0S\r\nSUMMARY:Done?\r\n"));
        assert!(written.contains("REPEAT:2\r\nDURATION:PT5M\r\n"));
        assert!(written.contains("DTSTAMP:20260301T120000Z\r\n"));
    }

    #[test]
    fn a_dropped_alarm_goes_and_a_new_one_is_built() {
        let mut read = alarms(EVENT).unwrap();
        read.remove(1);
        read.push(Alarm {
            action: "EMAIL".into(),
            minutes: Some(1440),
            description: "Tomorrow: Review".into(),
            summary: "Review".into(),
            attendees: vec!["jane@example.org".into()],
            ..Default::default()
        });

        let written = apply(EVENT, &edit(read)).unwrap();

        assert!(!written.contains("Write the notes"));
        assert!(written.contains(
            "BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER:-P1D\r\nDESCRIPTION:Tomorrow: Review\r\n\
             SUMMARY:Review\r\nATTENDEE:mailto:jane@example.org\r\nEND:VALARM\r\n"
        ));
        assert_eq!(3, alarms(&written).unwrap().len());
    }

    #[test]
    fn a_trigger_moves_to_a_moment_and_a_repeat_changes() {
        let mut read = alarms(EVENT).unwrap();
        read[0].minutes = None;
        read[0].absolute = Some("20260105T080000Z".into());
        read[2].repeat = 3;
        read[2].every_minutes = Some(10);

        let written = apply(EVENT, &edit(read)).unwrap();

        assert!(written.contains("TRIGGER;VALUE=DATE-TIME:20260105T080000Z\r\n"));
        assert!(written.contains("REPEAT:3\r\nDURATION:PT10M\r\n"));
        let again = alarms(&written).unwrap();
        assert_eq!(Some("20260105T080000Z".to_string()), again[0].absolute);
        assert_eq!(None, again[0].minutes);
        assert_eq!(3, again[2].repeat);
    }
}
