//! The phone's view of a calendar object, both ways: what the calendar
//! provider's rows carry of it, and that view, edited in a calendar app,
//! patched back onto the object (docs/calendar-mapping.md).
//!
//! The view is the object's and not the provider's: civil times with
//! what they are relative to, rules as written, alarms as minutes before
//! the start. Rows and instants are the Java side's, which alone has the
//! time-zone database; the object is this side's, so a patch touches
//! the properties the phone changed and every other byte stays.

use std::collections::VecDeque;

use ical::{
    component::IcalComponent,
    param::IcalParam,
    prop::{IcalProp, IcalPropKind, IcalPropName},
    recur::{IcalRecurDateTime, IcalRecurFreq, IcalRecurRule, set::IcalRecurSet},
    tree::{
        codec::mode::Escaper,
        cst::{IcalCst, IcalItem},
        leaf::IcalLeaf,
        line::IcalLine,
        param::node::IcalParamNode,
        value::cursor::IcalValueCursor,
    },
    value::{
        IcalValue, cal_address::IcalCalAddress, datetime::IcalDateTimeList, duration::IcalDuration,
        recur::IcalRecur, text::IcalText, uri::IcalUri,
    },
    version::IcalVersion,
};
use serde::{Deserialize, Serialize};

use crate::types::BridgeError;

use super::{
    EventTime, EventTimeKind, PRODID, Zones, child, child_mut, date_prop, decoded, line, line_mut,
    organizes, prop, raw_value, remove_named, scheduled,
    series::{Series, set_of, set_rule},
    text, user_address,
    zone::stamp,
};

/// Seconds in a day.
const DAY: i64 = 86_400;

/// How far back and ahead of now a series the provider cannot show is
/// listed instance by instance.
const LISTED_BACK: i64 = 365 * DAY;
const LISTED_AHEAD: i64 = 2 * 365 * DAY;

/// The most instances such a list holds, those nearest to now kept.
const LISTED_MAX: usize = 1000;

/// How many periods of its frequency the provider's expansion walks
/// before it gives up.
const PERIODS_MAX: i64 = 2000;

/// How many instances a list walks at most, so a `FREQ=SECONDLY` rule
/// started years ago costs a bounded walk.
const WALK_MAX: usize = 200_000;

/// One date or date-time of the view: civil, with what it is relative
/// to, as [`EventTime`] carries it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PhoneTime {
    pub time: String,
    pub kind: EventTimeKind,
    #[serde(default)]
    pub tzid: String,
    /// The offset the object's own `VTIMEZONE` puts in force, when it
    /// defines the zone.
    #[serde(default)]
    pub offset: Option<i32>,
    /// The instant it names, a UTC stamp, which the Java side sends with
    /// a time it read off the phone in a zone only the object defines:
    /// that zone's offset at the new time is this side's to find.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub instant: Option<String>,
}

impl PhoneTime {
    fn of(time: EventTime) -> Self {
        Self {
            time: time.time,
            kind: time.kind,
            tzid: time.tzid,
            offset: time.offset,
            instant: None,
        }
    }

    fn event(&self) -> EventTime {
        EventTime {
            time: self.time.clone(),
            kind: self.kind,
            tzid: self.tzid.clone(),
            offset: None,
        }
    }

    /// Whether two times are spelled alike, whatever offset either
    /// carries.
    fn same(&self, other: &Self) -> bool {
        self.time == other.time && self.kind == other.kind && self.tzid == other.tzid
    }
}

/// One alarm the phone carries, as minutes before the start, or the
/// absolute time it was written at when the Java side has to place it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PhoneAlarm {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub minutes: Option<i64>,
    /// A UTC stamp, on a component that does not recur.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub absolute: Option<String>,
}

/// One `ATTENDEE`, its parameters as written.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PhoneAttendee {
    /// The address without `mailto:`.
    pub email: String,
    pub name: String,
    pub role: String,
    pub cutype: String,
    pub partstat: String,
}

/// One component of the view: the master or an override.
///
/// Every field is optional: the projection fills them all, and an edit
/// leaves out what it does not change.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PhoneComponent {
    /// The instance an override replaces; none on a master.
    pub recurrence_id: Option<PhoneTime>,
    pub summary: Option<String>,
    pub description: Option<String>,
    pub location: Option<String>,
    pub url: Option<String>,
    pub start: Option<PhoneTime>,
    /// `DTEND`, or the end `DURATION` gives, or the one RFC 5545 3.6.1
    /// implies.
    pub end: Option<PhoneTime>,
    pub rrules: Option<Vec<String>>,
    pub exrules: Option<Vec<String>>,
    pub rdates: Option<Vec<PhoneTime>>,
    pub exdates: Option<Vec<PhoneTime>>,
    pub status: Option<String>,
    pub transp: Option<String>,
    pub class: Option<String>,
    pub color: Option<String>,
    /// The organizer's address without `mailto:`.
    pub organizer: Option<String>,
    pub alarms: Option<Vec<PhoneAlarm>>,
    pub attendees: Option<Vec<PhoneAttendee>>,
}

/// One calendar object as the phone sees it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PhoneEvent {
    pub uid: String,
    /// None for an object holding no event, or overrides alone.
    pub master: Option<PhoneComponent>,
    /// None in an edit leaves them as they are.
    pub overrides: Option<Vec<PhoneComponent>>,
    /// Whether the master's recurrence is a list of its instances around
    /// now, the series being one the provider cannot show.
    pub listed: bool,
}

/// An edit of the view, and what patching it needs besides.
#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PhoneEdit {
    pub event: PhoneEvent,
    /// Now, a UTC stamp, for `DTSTAMP` and `LAST-MODIFIED`.
    pub stamp: String,
    /// The user's addresses, which say whether they organize the event.
    pub addresses: Vec<String>,
    /// The `VTIMEZONE` of each zone the edit may write a time in.
    pub vtimezones: Vec<String>,
}

/// The view of a calendar object, `now` a UTC stamp placing the window
/// a series the provider cannot show is listed over.
pub fn project(ical: &str, now: &str) -> Result<PhoneEvent, BridgeError> {
    let cst = IcalCst::parse(ical)
        .map_err(|err| err.to_string())?
        .into_static();
    Ok(View::of(&cst, moment(now)?).event())
}

/// Patches an edited view onto the object: each field the edit carries
/// that differs from what the object projects, and nothing else.
///
/// An empty object is a skeleton carrying the edit's `UID`, which is how
/// an event created in a calendar app becomes one.
pub fn apply(ical: &str, edit: &str) -> Result<String, BridgeError> {
    let edit: PhoneEdit = serde_json::from_str(edit).map_err(|err| err.to_string())?;
    let source = match ical.trim().is_empty() {
        true => skeleton(&edit)?,
        false => ical.to_string(),
    };
    let mut cst = IcalCst::parse(source.as_str())
        .map_err(|err| err.to_string())?
        .into_static();

    let now = moment(&edit.stamp).unwrap_or(IcalRecurDateTime::from_seconds(0));
    let view = View::of(&cst, now);
    let mut patcher = Patcher {
        edit: &edit,
        zones: Zones::of_cst(&cst),
        organizes: announces(&view, &edit),
        written: Vec::new(),
        used: zones_used(&cst),
    };

    if let (Some(model), Some(base)) = (&edit.event.master, &view.master) {
        let dates = view.listed.is_none();
        patcher.patch(child_mut(&mut cst, base.item), base, model, dates);
    }
    if let Some(overrides) = &edit.event.overrides {
        patcher.overrides(&mut cst, &view, overrides)?;
    }
    patcher.define_zones(&mut cst)?;

    text(&cst)
}

/// The object an event created on the phone starts from.
fn skeleton(edit: &PhoneEdit) -> Result<String, BridgeError> {
    let uid = &edit.event.uid;
    if uid.is_empty() {
        return Err("A new event needs its UID".into());
    }
    Ok(format!(
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:{PRODID}\r\nBEGIN:VEVENT\r\nUID:{uid}\r\n\
         DTSTAMP:{}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        edit.stamp
    ))
}

/// A UTC stamp as a civil moment.
fn moment(stamp: &str) -> Result<IcalRecurDateTime, BridgeError> {
    IcalRecurDateTime::parse(stamp.trim_end_matches(['Z', 'z']))
        .map_err(|err| err.to_string().into())
}

/// Whether an edit is the user's to announce: they organize the event
/// with attendees ([`organizes`]), as the edit leaves it or else as the
/// object holds it.
fn announces(view: &View, edit: &PhoneEdit) -> bool {
    let base = view.master.as_ref().map(|master| &master.component);
    let model = edit.event.master.as_ref();
    let pick = |field: fn(&PhoneComponent) -> Option<&Vec<PhoneAttendee>>| {
        model
            .and_then(field)
            .or_else(|| base.and_then(field))
            .is_some_and(|attendees| !attendees.is_empty())
    };
    let organizer = model
        .and_then(|model| model.organizer.as_deref())
        .or_else(|| base.and_then(|base| base.organizer.as_deref()))
        .unwrap_or_default();

    organizes(
        organizer,
        pick(|component| component.attendees.as_ref()),
        &edit.addresses,
    )
}

/// One component of the object, where it sits and how it projects.
struct Located {
    /// Its place among the calendar's items.
    item: usize,
    component: PhoneComponent,
    /// The place of each projected alarm among the component's items.
    alarms: Vec<usize>,
    /// The place of each projected attendee among the component's items.
    attendees: Vec<usize>,
}

/// The object's events as the phone sees them, each where it sits.
struct View {
    uid: String,
    master: Option<Located>,
    /// The overrides projected: those of a recurring master, or every
    /// one when the object holds no master.
    overrides: Vec<Located>,
    /// For a master listed instance by instance, each listed time and
    /// the identity of the instance it is.
    listed: Option<Vec<(IcalRecurDateTime, IcalRecurDateTime)>>,
}

impl View {
    fn of(cst: &IcalCst<'static>, now: IcalRecurDateTime) -> Self {
        let zones = Zones::of_cst(cst);
        let events: Vec<usize> = cst
            .items
            .iter()
            .enumerate()
            .filter_map(|(index, item)| match item {
                IcalItem::Component(child) if named(child, "VEVENT") => Some(index),
                _ => None,
            })
            .collect();

        let master = events
            .iter()
            .copied()
            .find(|index| line(child(cst, *index), "RECURRENCE-ID").is_none());
        let uid = master
            .or(events.first().copied())
            .and_then(|index| line(child(cst, index), "UID"))
            .map(|line| line.value.decode().into_owned())
            .unwrap_or_default();
        let overrides: Vec<usize> = events
            .iter()
            .copied()
            .filter(|index| Some(*index) != master)
            .filter(|index| {
                line(child(cst, *index), "UID").map(|line| line.value.decode().into_owned())
                    == Some(uid.clone())
            })
            .collect();

        let Some(index) = master else {
            return Self {
                uid,
                master: None,
                overrides: overrides
                    .into_iter()
                    .map(|index| locate(cst, index, &zones))
                    .collect(),
                listed: None,
            };
        };

        let mut located = locate(cst, index, &zones);
        let recurring = located
            .component
            .rrules
            .as_ref()
            .is_some_and(|r| !r.is_empty())
            || located
                .component
                .rdates
                .as_ref()
                .is_some_and(|d| !d.is_empty());
        if !recurring {
            return Self {
                uid,
                master: Some(located),
                overrides: Vec::new(),
                listed: None,
            };
        }

        let listed = located
            .component
            .start
            .as_ref()
            .and_then(|start| instances(cst, index, &overrides, &zones, &start.event(), now));
        if let (Some(list), Some(start)) = (&listed, &located.component.start) {
            let start = start.event();
            let component = &mut located.component;
            component.rrules = Some(Vec::new());
            component.exrules = Some(Vec::new());
            component.exdates = Some(Vec::new());
            component.rdates = Some(
                list.iter()
                    .map(|(time, _)| PhoneTime::of(zones.resolve(start.at(*time))))
                    .collect(),
            );
        }

        Self {
            uid,
            master: Some(located),
            overrides: overrides
                .into_iter()
                .map(|index| locate(cst, index, &zones))
                .collect(),
            listed,
        }
    }

    fn event(self) -> PhoneEvent {
        PhoneEvent {
            uid: self.uid,
            master: self.master.map(|master| master.component),
            overrides: Some(
                self.overrides
                    .into_iter()
                    .map(|over| over.component)
                    .collect(),
            ),
            listed: self.listed.is_some(),
        }
    }
}

/// Whether a component is of one kind, by its `BEGIN` name.
fn named(component: &IcalCst, name: &str) -> bool {
    component
        .begin
        .as_ref()
        .is_some_and(|begin| begin.raw_value_str().eq_ignore_ascii_case(name))
}

/// One component's projection, from the component at `index` of the
/// calendar.
fn locate(cst: &IcalCst<'static>, index: usize, zones: &Zones) -> Located {
    Located {
        item: index,
        ..project_component(child(cst, index), zones)
    }
}

/// What a component projects, its alarms and attendees located among
/// its own items.
fn project_component(component: &IcalCst<'static>, zones: &Zones) -> Located {
    let mut out = PhoneComponent {
        summary: Some(String::new()),
        description: Some(String::new()),
        location: Some(String::new()),
        url: Some(String::new()),
        rrules: Some(Vec::new()),
        exrules: Some(Vec::new()),
        rdates: Some(Vec::new()),
        exdates: Some(Vec::new()),
        status: Some(String::new()),
        transp: Some(String::new()),
        class: Some(String::new()),
        color: Some(String::new()),
        organizer: Some(String::new()),
        alarms: Some(Vec::new()),
        attendees: Some(Vec::new()),
        ..Default::default()
    };
    let mut located = Located {
        item: 0,
        component: PhoneComponent::default(),
        alarms: Vec::new(),
        attendees: Vec::new(),
    };
    let mut start: Option<EventTime> = None;
    let mut rid: Option<EventTime> = None;
    let mut dtend = None;
    let mut duration = None;
    let mut alarms = Vec::new();

    let first = |slot: &mut Option<String>, value: Option<String>| {
        if slot.as_deref() == Some("") {
            *slot = Some(value.unwrap_or_default());
        }
    };

    for (index, item) in component.items.iter().enumerate() {
        let line = match item {
            IcalItem::Prop(line) => line,
            IcalItem::Component(nested) if named(nested, "VALARM") => {
                alarms.push((index, decoded(nested)));
                continue;
            }
            _ => continue,
        };
        let prop = line.decode(IcalVersion::V2_0);
        let IcalPropName::Kind(kind) = prop.name else {
            continue;
        };

        match kind {
            IcalPropKind::Summary => first(&mut out.summary, raw_value(&prop)),
            IcalPropKind::Description => first(&mut out.description, raw_value(&prop)),
            IcalPropKind::Location => first(&mut out.location, raw_value(&prop)),
            IcalPropKind::Url => first(&mut out.url, raw_value(&prop)),
            IcalPropKind::Status => first(&mut out.status, raw_value(&prop)),
            IcalPropKind::Transp => first(&mut out.transp, raw_value(&prop)),
            IcalPropKind::Class => first(&mut out.class, raw_value(&prop)),
            IcalPropKind::Color => first(&mut out.color, raw_value(&prop)),
            IcalPropKind::Organizer => first(&mut out.organizer, user_address(&prop)),
            IcalPropKind::DtStart if start.is_none() => {
                start = EventTime::of_prop(&prop).map(|time| zones.resolve(time));
            }
            IcalPropKind::RecurrenceId if rid.is_none() => {
                rid = EventTime::of_prop(&prop).map(|time| zones.resolve(time));
            }
            IcalPropKind::DtEnd if dtend.is_none() => {
                dtend = EventTime::of_prop(&prop).map(|time| zones.resolve(time));
            }
            IcalPropKind::Duration => {
                if let IcalValue::Duration(value) = &prop.value {
                    duration = value.seconds();
                }
            }
            IcalPropKind::RRule | IcalPropKind::ExRule => {
                if let IcalValue::Recur(value) = &prop.value {
                    let rules = match kind {
                        IcalPropKind::RRule => &mut out.rrules,
                        _ => &mut out.exrules,
                    };
                    rules.get_or_insert_default().push(value.0.to_string());
                }
            }
            IcalPropKind::RDate => out
                .rdates
                .get_or_insert_default()
                .extend(times(line, zones)),
            IcalPropKind::ExDate => out
                .exdates
                .get_or_insert_default()
                .extend(times(line, zones)),
            IcalPropKind::Attendee => {
                if let Some(attendee) = attendee_of(&prop) {
                    out.attendees.get_or_insert_default().push(attendee);
                    located.attendees.push(index);
                }
            }
            _ => {}
        }
    }

    let end = start.as_ref().map(|start| {
        dtend.clone().unwrap_or_else(|| {
            let length = match (duration, start.kind) {
                (Some(seconds), _) => seconds,
                (None, EventTimeKind::Date) => DAY,
                (None, _) => 0,
            };
            let civil = start.civil().map(|civil| civil.seconds()).unwrap_or(0);
            zones.resolve(start.at(IcalRecurDateTime::from_seconds(civil + length)))
        })
    });

    let recurring = out.rrules.as_ref().is_some_and(|rules| !rules.is_empty())
        || out.rdates.as_ref().is_some_and(|dates| !dates.is_empty());
    let length = match (&start, &end) {
        (Some(start), Some(end)) => match (start.civil(), end.civil()) {
            (Some(from), Some(to)) => zones.convert(to, end, start).seconds() - from.seconds(),
            _ => 0,
        },
        _ => 0,
    };
    for (index, alarm) in alarms {
        if let Some(alarm) = alarm_of(&alarm, length, recurring) {
            out.alarms.get_or_insert_default().push(alarm);
            located.alarms.push(index);
        }
    }

    located.component = PhoneComponent {
        recurrence_id: rid.map(PhoneTime::of),
        start: start.map(PhoneTime::of),
        end: end.map(PhoneTime::of),
        ..out
    };
    located
}

/// One attendee as the phone carries it; none for one without an
/// address.
fn attendee_of(prop: &IcalProp) -> Option<PhoneAttendee> {
    let email = user_address(prop).filter(|email| !email.is_empty())?;
    let mut attendee = PhoneAttendee {
        email,
        ..Default::default()
    };
    for param in &prop.params {
        match param {
            IcalParam::Cn(value) => attendee.name = value.to_string(),
            IcalParam::Role(value) => attendee.role = value.to_string(),
            IcalParam::CuType(value) => attendee.cutype = value.to_string(),
            IcalParam::PartStat(value) => attendee.partstat = value.to_string(),
            _ => {}
        }
    }
    Some(attendee)
}

/// One alarm as the phone carries it, `length` the event's in seconds:
/// a display or audio alarm, triggered relative to the start, to the
/// end through that length, or at a time on an event that does not
/// recur. A repeating one fires first at its trigger, which is what the
/// phone keeps.
fn alarm_of(alarm: &IcalComponent, length: i64, recurring: bool) -> Option<PhoneAlarm> {
    let action = alarm.props.iter().find_map(|prop| match prop.name {
        IcalPropName::Kind(IcalPropKind::Action) => raw_value(prop),
        _ => None,
    })?;
    if !action.eq_ignore_ascii_case("DISPLAY") && !action.eq_ignore_ascii_case("AUDIO") {
        return None;
    }

    let trigger = alarm
        .props
        .iter()
        .find(|prop| matches!(prop.name, IcalPropName::Kind(IcalPropKind::Trigger)))?;
    match &trigger.value {
        IcalValue::Duration(value) => {
            let mut seconds = value.seconds()?;
            let on_end = trigger.params.iter().any(|param| {
                matches!(param, IcalParam::Related(related) if related.eq_ignore_ascii_case("END"))
            });
            if on_end {
                seconds += length;
            }
            Some(PhoneAlarm {
                minutes: Some(-seconds / 60),
                absolute: None,
            })
        }
        IcalValue::DateTime(value) if !recurring => Some(PhoneAlarm {
            minutes: None,
            absolute: Some(value.0.to_string()),
        }),
        _ => None,
    }
}

/// Every date of an `RDATE` or `EXDATE` line, each with what it is
/// relative to. A period gives its start.
fn times(line: &IcalLine<'static>, zones: &Zones) -> Vec<PhoneTime> {
    let Some(zone) = EventTime::of_line(line) else {
        return Vec::new();
    };
    line.value
        .decode_list()
        .iter()
        .map(|value| PhoneTime::of(zones.resolve(time_of(value, &zone))))
        .collect()
}

/// One value of a date list, read relative to its line's zone.
fn time_of(value: &str, zone: &EventTime) -> EventTime {
    let value = value.split('/').next().unwrap_or(value);
    let (time, kind, tzid) = match value.strip_suffix(['Z', 'z']) {
        Some(time) => (time, EventTimeKind::Utc, ""),
        None if value.len() == 8 => (value, EventTimeKind::Date, ""),
        None if zone.kind == EventTimeKind::Zoned => (value, EventTimeKind::Zoned, &*zone.tzid),
        None => (value, EventTimeKind::Floating, ""),
    };
    EventTime {
        time: time.to_string(),
        kind,
        tzid: tzid.to_string(),
        offset: None,
    }
}

/// The instances of a series the provider cannot show, around now, each
/// as the time it is listed at and its identity; none for a series the
/// provider shows as it is.
///
/// An instance an override replaces is listed at its identity, which the
/// override's row hides; any other at the start the recurrence set gives
/// it, a `THISANDFUTURE` override's shift included.
fn instances(
    cst: &IcalCst<'static>,
    master: usize,
    overrides: &[usize],
    zones: &Zones,
    start: &EventTime,
    now: IcalRecurDateTime,
) -> Option<Vec<(IcalRecurDateTime, IcalRecurDateTime)>> {
    let first = start.civil()?;
    let series = decoded(child(cst, master));
    let replacing: Vec<IcalComponent> = overrides
        .iter()
        .map(|index| decoded(child(cst, *index)))
        .collect();
    let refs: Vec<&IcalComponent> = replacing.iter().collect();
    let (set, _) = set_of(&series, &refs, zones);

    let raw: Vec<String> = series
        .props
        .iter()
        .filter_map(|prop| match (&prop.name, &prop.value) {
            (IcalPropName::Kind(IcalPropKind::RRule), IcalValue::Recur(rule)) => {
                Some(rule.0.to_ascii_uppercase())
            }
            _ => None,
        })
        .collect();
    let periods = series.props.iter().any(|prop| {
        matches!(prop.name, IcalPropName::Kind(IcalPropKind::RDate))
            && (prop.params.iter().any(|param| {
                matches!(param, IcalParam::Value(value) if value.eq_ignore_ascii_case("PERIOD"))
            }) || matches!(&prop.value, IcalValue::DateTimeList(list) if list.0.iter().any(|value| value.contains('/'))))
    });
    let from = IcalRecurDateTime::from_seconds(now.seconds() - LISTED_BACK);
    if !hidden(&set, &raw, periods, first, from) {
        return None;
    }

    let until = IcalRecurDateTime::from_seconds(now.seconds() + LISTED_AHEAD);
    let walk = match zones.find(&start.tzid) {
        Some(zone) if start.kind == EventTimeKind::Zoned => set.expand_in_zone(zone),
        _ => set.expand(),
    };

    let mut past = VecDeque::new();
    let mut future = Vec::new();
    for occurrence in walk.take(WALK_MAX) {
        if occurrence.id >= until && occurrence.start >= until {
            break;
        }
        let listed = match occurrence.over {
            Some(_) => occurrence.id,
            None => occurrence.start,
        };
        if listed < from || listed >= until {
            continue;
        }
        if listed < now {
            past.push_back((listed, occurrence.id));
            if past.len() > LISTED_MAX {
                past.pop_front();
            }
        } else {
            future.push((listed, occurrence.id));
            if future.len() == LISTED_MAX {
                break;
            }
        }
    }

    // NOTE: from now outward, so a cut list keeps the instances nearest
    // to it rather than the window's first.
    let mut kept = Vec::new();
    let mut ahead = future.into_iter().peekable();
    while kept.len() < LISTED_MAX {
        let behind = past.back().copied();
        let next = ahead.peek().copied();
        let take_behind = match (behind, next) {
            (Some(behind), Some(next)) => {
                now.seconds() - behind.0.seconds() < next.0.seconds() - now.seconds()
            }
            (Some(_), None) => true,
            (None, Some(_)) => false,
            (None, None) => break,
        };
        if take_behind {
            kept.extend(past.pop_back());
        } else {
            kept.extend(ahead.next());
        }
    }
    kept.sort_unstable();
    Some(kept)
}

/// Whether the provider refuses or misexpands a series: `RSCALE` and
/// `SKIP` (RFC 7529), a `BYSETPOS` other than a monthly one over plain
/// weekdays, `BYWEEKNO`, an infinite rule beside `RDATE`s, an `RDATE`
/// period, a `THISANDFUTURE` override, or more periods of a rule's
/// frequency before `from` than the provider walks.
fn hidden(
    set: &IcalRecurSet,
    raw: &[String],
    periods: bool,
    first: IcalRecurDateTime,
    from: IcalRecurDateTime,
) -> bool {
    if periods || set.overrides.iter().any(|over| over.this_and_future) {
        return true;
    }
    if raw
        .iter()
        .any(|rule| rule.contains("RSCALE=") || rule.contains("SKIP="))
    {
        return true;
    }

    set.rules.iter().any(|rule| {
        let plain_weekdays = matches!(rule.freq, IcalRecurFreq::Monthly)
            && !rule.by_day.is_empty()
            && rule.by_day.iter().all(|day| day.ordinal.is_none())
            && rule.by_month_day.is_empty()
            && rule.by_year_day.is_empty()
            && rule.by_month.is_empty()
            && rule.by_hour.is_empty()
            && rule.by_minute.is_empty()
            && rule.by_second.is_empty();
        (!rule.by_set_pos.is_empty() && !plain_weekdays)
            || !rule.by_week_no.is_empty()
            || (rule.until.is_none() && rule.count.is_none() && !set.dates.is_empty())
            || walked(rule, first, from) > PERIODS_MAX
    })
}

/// How many periods of its frequency a rule walks from its first
/// instance to `from`, or to its own end when that comes first.
fn walked(rule: &IcalRecurRule, first: IcalRecurDateTime, from: IcalRecurDateTime) -> i64 {
    let reach = rule.until.map_or(from, |until| until.min(from));
    if reach <= first {
        return 0;
    }

    let seconds = reach.seconds() - first.seconds();
    let months = (i64::from(reach.year) - i64::from(first.year)) * 12 + i64::from(reach.month)
        - i64::from(first.month);
    let units = match rule.freq {
        IcalRecurFreq::Secondly => seconds,
        IcalRecurFreq::Minutely => seconds / 60,
        IcalRecurFreq::Hourly => seconds / 3_600,
        IcalRecurFreq::Daily => seconds / DAY,
        IcalRecurFreq::Weekly => seconds / (7 * DAY),
        IcalRecurFreq::Monthly => months,
        IcalRecurFreq::Yearly => months / 12,
    };
    let periods = units / i64::from(rule.interval.max(1));
    match rule.count {
        Some(count) => periods.min(i64::from(count)),
        None => periods,
    }
}

/// What a component's patch does once its items are no longer addressed
/// by place.
#[derive(Default)]
struct Later {
    /// The places of the items it removes.
    gone: Vec<usize>,
    props: Vec<IcalProp<'static>>,
    alarms: Vec<IcalCst<'static>>,
}

/// What a patch writes besides the edit itself.
struct Patcher<'e> {
    edit: &'e PhoneEdit,
    zones: Zones,
    /// Whether an edit is the user's to announce, bumping `SEQUENCE`.
    organizes: bool,
    /// The zones a time was written in afresh, owed a definition.
    written: Vec<String>,
    /// The zones the object names before the edit, defined or not: one it
    /// already names without a definition is its own business.
    used: Vec<String>,
}

impl Patcher<'_> {
    /// Patches one component with the fields of `model` that differ
    /// from `base`, its dates left alone unless `dates`; answers whether
    /// anything changed, the component then stamped.
    fn patch(
        &mut self,
        component: &mut IcalCst<'static>,
        base: &Located,
        model: &PhoneComponent,
        dates: bool,
    ) -> bool {
        let was = &base.component;

        // NOTE: what addresses the component's items by place goes first,
        // every removal in one pass from the last item back, and only then
        // what adds one.
        let mut later = Later::default();
        let mut changed = self.attendees(component, base, model, &mut later);
        changed |= self.alarms(component, base, model, &mut later);
        later.gone.sort_unstable();
        for index in later.gone.into_iter().rev() {
            component.items.remove(index);
        }
        for added in later.props {
            component.push(added);
        }
        for alarm in later.alarms {
            component.push_component(alarm);
        }

        let texts: [(&str, IcalPropKind, &Option<String>, &Option<String>); 8] = [
            (
                "SUMMARY",
                IcalPropKind::Summary,
                &was.summary,
                &model.summary,
            ),
            (
                "DESCRIPTION",
                IcalPropKind::Description,
                &was.description,
                &model.description,
            ),
            (
                "LOCATION",
                IcalPropKind::Location,
                &was.location,
                &model.location,
            ),
            ("URL", IcalPropKind::Url, &was.url, &model.url),
            ("STATUS", IcalPropKind::Status, &was.status, &model.status),
            ("TRANSP", IcalPropKind::Transp, &was.transp, &model.transp),
            ("CLASS", IcalPropKind::Class, &was.class, &model.class),
            ("COLOR", IcalPropKind::Color, &was.color, &model.color),
        ];
        for (name, kind, was, now) in texts {
            changed |= set_text(component, name, kind, was, now);
        }
        if let Some(organizer) = &model.organizer
            && was.organizer.as_ref() != Some(organizer)
        {
            set_value(
                component,
                "ORGANIZER",
                IcalPropKind::Organizer,
                organizer_value(organizer),
            );
            changed = true;
        }

        if dates {
            changed |= self.time(
                component,
                "DTSTART",
                IcalPropKind::DtStart,
                &was.start,
                &model.start,
            );
            changed |= self.end(component, was, model);
            changed |= rules(component, &was.rrules, &model.rrules);
            changed |= self.dates(
                component,
                "RDATE",
                IcalPropKind::RDate,
                &was.rdates,
                &model.rdates,
            );
            changed |= self.dates(
                component,
                "EXDATE",
                IcalPropKind::ExDate,
                &was.exdates,
                &model.exdates,
            );
        }

        if changed {
            self.touch(component);
        }
        changed
    }

    /// The overrides of an edit: each one changed patched, cancelled,
    /// added, and those it no longer holds removed, the instance going
    /// back to the series.
    fn overrides(
        &mut self,
        cst: &mut IcalCst<'static>,
        view: &View,
        overrides: &[PhoneComponent],
    ) -> Result<(), BridgeError> {
        let series = match view.master {
            Some(_) => Series::of(cst).ok(),
            None => None,
        };
        let mut kept = vec![false; view.overrides.len()];
        let mut gone = Vec::new();
        let mut added = Vec::new();
        let mut cancelled = Vec::new();

        for model in overrides {
            let Some(rid) = &model.recurrence_id else {
                continue;
            };
            let id = series
                .as_ref()
                .and_then(|series| self.identity(rid, series, view));
            let found = view.overrides.iter().position(|over| {
                let Some(held) = &over.component.recurrence_id else {
                    return false;
                };
                held.same(rid)
                    || match &series {
                        Some(series) => id.is_some() && self.identity(held, series, view) == id,
                        None => held.civil_eq(rid),
                    }
            });
            let cancels = model
                .status
                .as_deref()
                .is_some_and(|status| status.eq_ignore_ascii_case("CANCELLED"));

            match found {
                Some(at) => {
                    kept[at] = true;
                    let base = &view.overrides[at];
                    let was_cancelled = base
                        .component
                        .status
                        .as_deref()
                        .is_some_and(|status| status.eq_ignore_ascii_case("CANCELLED"));
                    match (cancels && !was_cancelled, id) {
                        (true, Some(id)) if series.is_some() => {
                            cancelled.push(id);
                            gone.push(base.item);
                        }
                        _ => {
                            self.patch(child_mut(cst, base.item), base, model, true);
                        }
                    }
                }
                None => {
                    let (Some(series), Some(id)) = (&series, id) else {
                        continue;
                    };
                    if cancels {
                        cancelled.push(id);
                        continue;
                    }
                    let mut instance = series.instance(cst, id);
                    let base = project_component(&instance, &self.zones);
                    if !self.patch(&mut instance, &base, model, true) {
                        self.touch(&mut instance);
                    }
                    added.push(instance);
                }
            }
        }
        for (over, kept) in view.overrides.iter().zip(kept) {
            if !kept {
                gone.push(over.item);
            }
        }

        if let (Some(series), Some(master)) = (&series, &view.master)
            && !cancelled.is_empty()
        {
            let component = child_mut(cst, master.item);
            // NOTE: what the object excludes already, read off its lines:
            // a listed master's view carries none.
            let held: Vec<IcalRecurDateTime> = component
                .items
                .iter()
                .filter_map(|item| match item {
                    IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case("EXDATE") => {
                        Some(times(line, &self.zones))
                    }
                    _ => None,
                })
                .flatten()
                .filter_map(|date| {
                    let time = date.event();
                    Some(self.zones.convert(time.civil()?, &time, &series.start))
                })
                .collect();
            for id in cancelled {
                if !held.contains(&id) {
                    component.push(date_prop(IcalPropKind::ExDate, &series.start.at(id)));
                }
            }
            self.touch(component);
        }

        gone.sort_unstable();
        gone.dedup();
        for index in gone.into_iter().rev() {
            cst.items.remove(index);
        }
        let at = scheduled(cst)
            .last()
            .map_or(cst.items.len(), |last| last + 1);
        for (offset, instance) in added.into_iter().enumerate() {
            cst.items
                .insert(at + offset, IcalItem::Component(Box::new(instance)));
        }
        Ok(())
    }

    /// The identity of the instance a recurrence id names, in the series'
    /// zone: what its override replaces, the listed time mapped back to
    /// the instance it is for a master listed instance by instance.
    fn identity(&self, rid: &PhoneTime, series: &Series, view: &View) -> Option<IcalRecurDateTime> {
        let start = &series.start;
        let time = self.resolved(rid);
        let civil = time.civil()?;
        let id = match (start.kind, time.kind) {
            (EventTimeKind::Date, _) => IcalRecurDateTime::date(civil.year, civil.month, civil.day),
            (_, EventTimeKind::Date) => {
                let at = start.civil()?;
                IcalRecurDateTime {
                    hour: at.hour,
                    minute: at.minute,
                    second: at.second,
                    ..civil
                }
            }
            _ => self.zones.convert(civil, &time, start),
        };

        Some(match &view.listed {
            Some(list) => list
                .iter()
                .find(|(listed, _)| *listed == id)
                .map_or(id, |(_, identity)| *identity),
            None => id,
        })
    }

    /// A time as the edit writes it: read in the zone the object defines
    /// from the instant it names, when the edit gave one.
    fn resolved(&self, time: &PhoneTime) -> EventTime {
        let mut event = time.event();
        if let Some(instant) = time.instant.as_deref().and_then(|stamp| moment(stamp).ok())
            && time.kind == EventTimeKind::Zoned
            && self.zones.find(&time.tzid).is_some()
            && let Some(local) = self.zones.local(&event, instant.seconds())
        {
            event.time = stamp(local, false);
        }
        event
    }

    /// Replaces one date property when the edit moves it.
    fn time(
        &mut self,
        component: &mut IcalCst<'static>,
        name: &str,
        kind: IcalPropKind,
        was: &Option<PhoneTime>,
        now: &Option<PhoneTime>,
    ) -> bool {
        let Some(now) = now else {
            return false;
        };
        if was.as_ref().is_some_and(|was| was.same(now)) {
            return false;
        }
        let time = self.resolved(now);
        self.put(component, name, kind, &time);
        true
    }

    /// Writes a date property, in place where the component has one: its
    /// digits alone when the zone stays, the whole line otherwise.
    fn put(
        &mut self,
        component: &mut IcalCst<'static>,
        name: &str,
        kind: IcalPropKind,
        time: &EventTime,
    ) {
        if let Some(line) = line_mut(component, name)
            && let Some(old) = EventTime::of_line(line)
            && old.kind == time.kind
            && old.tzid == time.tzid
        {
            IcalValueCursor { line }.set_bytes(time.wire());
            return;
        }

        if time.kind == EventTimeKind::Zoned {
            self.written.push(time.tzid.clone());
        }
        let fresh = date_prop(kind, time).encode(Escaper::default());
        match position(component, name) {
            Some(at) => component.items[at] = IcalItem::Prop(fresh),
            None => {
                component.push(date_prop(kind, time));
            }
        }
    }

    /// Moves the end: `DURATION` keeps the length it gives, any other
    /// object gets a `DTEND`.
    fn end(
        &mut self,
        component: &mut IcalCst<'static>,
        was: &PhoneComponent,
        model: &PhoneComponent,
    ) -> bool {
        let Some(end) = &model.end else {
            return false;
        };
        if was.end.as_ref().is_some_and(|was| was.same(end)) {
            return false;
        }

        let start = model.start.as_ref().or(was.start.as_ref());
        if let Some(start) = start
            && line(component, "DTEND").is_none()
            && line(component, "DURATION").is_some()
        {
            let start = self.resolved(start);
            let end = self.resolved(end);
            if let (Some(from), Some(to)) = (start.civil(), end.civil()) {
                let seconds = self.zones.convert(to, &end, &start).seconds() - from.seconds();
                let length = match start.kind {
                    EventTimeKind::Date => format!("P{}D", seconds / DAY),
                    _ => IcalDuration::from_seconds(seconds).0.into_owned(),
                };
                set_value(
                    component,
                    "DURATION",
                    IcalPropKind::Duration,
                    IcalValue::Duration(IcalDuration(length.into())),
                );
                return true;
            }
        }

        let time = self.resolved(end);
        self.put(component, "DTEND", IcalPropKind::DtEnd, &time);
        true
    }

    /// Patches the dates of an `RDATE` or `EXDATE` list: the values the
    /// edit no longer holds go, each line left empty with them, and those
    /// it adds land on one new line per zone.
    fn dates(
        &mut self,
        component: &mut IcalCst<'static>,
        name: &str,
        kind: IcalPropKind,
        was: &Option<Vec<PhoneTime>>,
        now: &Option<Vec<PhoneTime>>,
    ) -> bool {
        let Some(now) = now else {
            return false;
        };
        let was = was.clone().unwrap_or_default();
        let holds = |list: &[PhoneTime], time: &PhoneTime| list.iter().any(|one| one.same(time));
        if was.len() == now.len() && now.iter().all(|time| holds(&was, time)) {
            return false;
        }

        component.items.retain_mut(|item| {
            let IcalItem::Prop(line) = item else {
                return true;
            };
            if !line.name.get().eq_ignore_ascii_case(name) {
                return true;
            }
            let Some(zone) = EventTime::of_line(line) else {
                return true;
            };
            let mut cursor = IcalValueCursor { line };
            let kept: Vec<String> = cursor
                .list()
                .iter()
                .filter(|value| {
                    let time = PhoneTime::of(time_of(value, &zone));
                    holds(now, &time)
                })
                .map(|value| value.to_string())
                .collect();
            if kept.is_empty() {
                return false;
            }
            if kept.len() != cursor.list().len() {
                cursor.set_list(&kept);
            }
            true
        });

        let mut added: Vec<(EventTimeKind, String, Vec<String>)> = Vec::new();
        for time in now.iter().filter(|time| !holds(&was, time)) {
            let event = self.resolved(time);
            match added
                .iter_mut()
                .find(|(kind, tzid, _)| *kind == event.kind && *tzid == event.tzid)
            {
                Some((_, _, values)) => values.push(event.wire()),
                None => added.push((event.kind, event.tzid.clone(), vec![event.wire()])),
            }
        }
        for (zone, tzid, values) in added {
            let params = match zone {
                EventTimeKind::Date => vec![IcalParam::Value("DATE".into())],
                EventTimeKind::Zoned => {
                    self.written.push(tzid.clone());
                    vec![IcalParam::TzId(tzid.into())]
                }
                _ => Vec::new(),
            };
            let values = values.into_iter().map(Into::into).collect();
            component.push(prop(
                kind,
                params,
                IcalValue::DateTimeList(IcalDateTimeList(values)),
            ));
        }
        true
    }

    /// Patches the attendees: each one's standing in place, matched by
    /// address, those the edit drops and the new ones left for `later`.
    /// An attendee the phone never carried is never touched.
    fn attendees(
        &mut self,
        component: &mut IcalCst<'static>,
        base: &Located,
        model: &PhoneComponent,
        later: &mut Later,
    ) -> bool {
        let Some(now) = &model.attendees else {
            return false;
        };
        let was = base.component.attendees.clone().unwrap_or_default();
        if &was == now {
            return false;
        }

        let mut changed = false;
        for (index, held) in base.attendees.iter().zip(&was) {
            let IcalItem::Prop(line) = &mut component.items[*index] else {
                continue;
            };
            match now
                .iter()
                .find(|one| one.email.eq_ignore_ascii_case(&held.email))
            {
                Some(one) if one != held => {
                    for (param, value) in [
                        ("CN", &one.name),
                        ("ROLE", &one.role),
                        ("CUTYPE", &one.cutype),
                        ("PARTSTAT", &one.partstat),
                    ] {
                        set_param(line, param, value);
                    }
                    changed = true;
                }
                Some(_) => {}
                None => {
                    later.gone.push(*index);
                    changed = true;
                }
            }
        }

        for one in now.iter().filter(|one| {
            !was.iter()
                .any(|held| held.email.eq_ignore_ascii_case(&one.email))
        }) {
            let mut params = Vec::new();
            for (value, wrap) in [
                (&one.name, IcalParam::Cn as fn(_) -> _),
                (&one.role, IcalParam::Role),
                (&one.cutype, IcalParam::CuType),
                (&one.partstat, IcalParam::PartStat),
            ] {
                if !value.is_empty() {
                    params.push(wrap(value.clone().into()));
                }
            }
            later.props.push(prop(
                IcalPropKind::Attendee,
                params,
                IcalValue::CalAddress(IcalCalAddress(format!("mailto:{}", one.email).into())),
            ));
            changed = true;
        }
        changed
    }

    /// Patches the alarms the phone carried, matched by offset: an
    /// unchanged one stays byte for byte, a changed one gets a trigger
    /// relative to the start, a removed one and a new display alarm are
    /// left for `later`. An alarm the phone never saw is never touched.
    fn alarms(
        &mut self,
        component: &mut IcalCst<'static>,
        base: &Located,
        model: &PhoneComponent,
        later: &mut Later,
    ) -> bool {
        let Some(now) = &model.alarms else {
            return false;
        };
        let was = base.component.alarms.clone().unwrap_or_default();

        let mut fresh: Vec<&PhoneAlarm> = now.iter().collect();
        let mut stale = Vec::new();
        for (index, held) in base.alarms.iter().zip(&was) {
            match fresh.iter().position(|one| *one == held) {
                Some(at) => {
                    fresh.remove(at);
                }
                None => stale.push(*index),
            }
        }
        if stale.is_empty() && fresh.is_empty() {
            return false;
        }

        let mut fresh = fresh.into_iter().filter_map(|alarm| alarm.minutes);
        for index in stale {
            match fresh.next() {
                Some(minutes) => {
                    if let IcalItem::Component(alarm) = &mut component.items[index] {
                        let trigger = prop(IcalPropKind::Trigger, Vec::new(), trigger_of(minutes))
                            .encode(Escaper::default());
                        match position(alarm, "TRIGGER") {
                            Some(at) => alarm.items[at] = IcalItem::Prop(trigger),
                            None => {
                                alarm.push(prop(
                                    IcalPropKind::Trigger,
                                    Vec::new(),
                                    trigger_of(minutes),
                                ));
                            }
                        }
                    }
                }
                None => later.gone.push(index),
            }
        }

        let summary = model.summary.clone().unwrap_or_else(|| {
            line(component, "SUMMARY")
                .map(|line| line.value.decode().into_owned())
                .unwrap_or_default()
        });
        for minutes in fresh {
            let mut alarm = IcalCst::empty("VALARM");
            alarm.push(prop(
                IcalPropKind::Action,
                Vec::new(),
                IcalValue::Text(IcalText("DISPLAY".into())),
            ));
            alarm.push(prop(IcalPropKind::Trigger, Vec::new(), trigger_of(minutes)));
            alarm.push(prop(
                IcalPropKind::Description,
                Vec::new(),
                IcalValue::Text(IcalText(summary.clone().into())),
            ));
            later.alarms.push(alarm);
        }
        true
    }

    /// Stamps a component edited now (RFC 5545 3.8.7.2, 3.8.7.3), and
    /// counts the edit in its `SEQUENCE` when it is the organizer's to
    /// announce (RFC 5546 2.1.4).
    fn touch(&self, component: &mut IcalCst<'static>) {
        if let Ok(now) = moment(&self.edit.stamp) {
            let time = EventTime {
                time: stamp(now, false),
                kind: EventTimeKind::Utc,
                tzid: String::new(),
                offset: None,
            };
            for name in ["DTSTAMP", "LAST-MODIFIED"] {
                if let Some(line) = line_mut(component, name) {
                    IcalValueCursor { line }.set_bytes(time.wire());
                } else if name == "DTSTAMP" {
                    component.push(date_prop(IcalPropKind::DtStamp, &time));
                }
            }
        }

        if !self.organizes {
            return;
        }
        match line_mut(component, "SEQUENCE") {
            Some(line) => {
                let next = line.value.decode().trim().parse::<i64>().unwrap_or(0) + 1;
                IcalValueCursor { line }.set_bytes(next.to_string());
            }
            None => {
                component.push_raw("SEQUENCE:1").ok();
            }
        }
    }

    /// Defines each zone a time was written in afresh that the object
    /// does not define yet, before its components (RFC 5545 3.2.19).
    fn define_zones(&mut self, cst: &mut IcalCst<'static>) -> Result<(), BridgeError> {
        self.written.sort_unstable();
        self.written.dedup();
        for tzid in &self.written {
            if self.used.contains(tzid) || Zones::of_cst(cst).find(tzid).is_some() {
                continue;
            }
            let definition = self
                .edit
                .vtimezones
                .iter()
                .filter_map(|text| IcalCst::parse(text.as_str()).ok().map(IcalCst::into_static))
                .find(|zone| line(zone, "TZID").is_some_and(|line| line.value.decode() == *tzid))
                .ok_or_else(|| format!("No definition of the zone `{tzid}`"))?;
            let at = scheduled(cst).first().copied().unwrap_or(cst.items.len());
            cst.items
                .insert(at, IcalItem::Component(Box::new(definition)));
        }
        Ok(())
    }
}

impl PhoneTime {
    /// Whether two times name the same civil moment, read as written.
    fn civil_eq(&self, other: &Self) -> bool {
        self.event().civil().is_some() && self.event().civil() == other.event().civil()
    }
}

/// Every `TZID` a calendar's lines name, at any depth.
fn zones_used(cst: &IcalCst<'static>) -> Vec<String> {
    let mut used = Vec::new();
    for item in &cst.items {
        match item {
            IcalItem::Prop(line) => used.extend(
                line.params
                    .iter()
                    .filter(|param| param.name.get().eq_ignore_ascii_case("TZID"))
                    .map(|param| param.decode())
                    .filter_map(|param| match param {
                        IcalParam::TzId(tzid) => Some(tzid.into_owned()),
                        _ => None,
                    }),
            ),
            IcalItem::Component(nested) => used.extend(zones_used(nested)),
            IcalItem::Opaque(_) => {}
        }
    }
    used
}

/// A trigger `minutes` before the start, after it when negative.
fn trigger_of(minutes: i64) -> IcalValue<'static> {
    IcalValue::Duration(IcalDuration::from_seconds(-minutes * 60))
}

/// The value an organizer's address is written as.
fn organizer_value(address: &str) -> IcalValue<'static> {
    IcalValue::CalAddress(IcalCalAddress(format!("mailto:{address}").into()))
}

/// Where the first line of one property sits among a component's items.
fn position(component: &IcalCst<'static>, name: &str) -> Option<usize> {
    component.items.iter().position(
        |item| matches!(item, IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case(name)),
    )
}

/// Replaces a text property's value in place, its parameters kept;
/// empty removes it.
fn set_text(
    component: &mut IcalCst<'static>,
    name: &str,
    kind: IcalPropKind,
    was: &Option<String>,
    now: &Option<String>,
) -> bool {
    let Some(now) = now else {
        return false;
    };
    if was.as_ref() == Some(now) {
        return false;
    }

    let value = match kind {
        IcalPropKind::Url => IcalValue::Uri(IcalUri(now.clone().into())),
        _ => IcalValue::Text(IcalText(now.clone().into())),
    };
    match now.is_empty() {
        true => remove_named(component, name),
        false => set_value(component, name, kind, value),
    }
    true
}

/// Sets a property's value, its line kept with its parameters, or adds
/// the property when the component has none.
fn set_value(
    component: &mut IcalCst<'static>,
    name: &str,
    kind: IcalPropKind,
    value: IcalValue<'static>,
) {
    let encoded = prop(kind, Vec::new(), value.clone()).encode(Escaper::default());
    match line_mut(component, name) {
        Some(line) => line.value = encoded.value,
        None => {
            component.push(prop(kind, Vec::new(), value));
        }
    }
}

/// Patches the rules: in place when as many as before, rewritten whole
/// otherwise.
fn rules(
    component: &mut IcalCst<'static>,
    was: &Option<Vec<String>>,
    now: &Option<Vec<String>>,
) -> bool {
    let Some(now) = now else {
        return false;
    };
    if was.as_ref() == Some(now) {
        return false;
    }

    let held = component
        .items
        .iter()
        .filter(|item| matches!(item, IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case("RRULE")))
        .count();
    if held == now.len() {
        let mut rules = now.iter();
        for item in &mut component.items {
            if let IcalItem::Prop(line) = item
                && line.name.get().eq_ignore_ascii_case("RRULE")
                && let Some(rule) = rules.next()
            {
                set_rule(line, rule);
            }
        }
        return true;
    }

    remove_named(component, "RRULE");
    for rule in now {
        component.push(prop(
            IcalPropKind::RRule,
            Vec::new(),
            IcalValue::Recur(IcalRecur(rule.clone().into())),
        ));
    }
    true
}

/// Sets one parameter of a line, removing it when empty.
fn set_param(line: &mut IcalLine<'static>, name: &str, value: &str) {
    let at = line
        .params
        .iter()
        .position(|param| param.name.get().eq_ignore_ascii_case(name));
    if value.is_empty() {
        if let Some(at) = at {
            line.params.remove(at);
        }
        return;
    }

    let leaf = match value.contains([':', ';', ',']) {
        true => IcalLeaf::from(format!("\"{value}\"")),
        false => IcalLeaf::from(value.to_string()),
    };
    match at {
        Some(at) => {
            if line.params[at].values.len() == 1 && line.params[at].values[0].get() == leaf.get() {
                return;
            }
            line.params[at].values = vec![leaf];
        }
        None => line.params.push(IcalParamNode {
            name: IcalLeaf::from(name.to_string()),
            values: vec![leaf],
            escaper: Escaper::default(),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::calendar::tests::{ROMANCE, object};

    const NOW: &str = "20261009T120000Z";

    fn view(ical: &str) -> PhoneEvent {
        project(ical, NOW).unwrap()
    }

    fn master(ical: &str) -> PhoneComponent {
        view(ical).master.unwrap()
    }

    /// The object with an edit of its master applied, stamped `STAMP`.
    fn edited(ical: &str, edit: impl FnOnce(&mut PhoneComponent)) -> String {
        let mut event = PhoneEvent {
            master: Some(PhoneComponent::default()),
            ..Default::default()
        };
        edit(event.master.as_mut().unwrap());
        applied(ical, event, &[])
    }

    fn applied(ical: &str, event: PhoneEvent, vtimezones: &[&str]) -> String {
        let edit = serde_json::json!({
            "event": event,
            "stamp": STAMP,
            "addresses": ["jane@example.com"],
            "vtimezones": vtimezones,
        });
        apply(ical, &edit.to_string()).unwrap()
    }

    const STAMP: &str = "20261009T120000Z";

    fn time(time: &str, kind: EventTimeKind, tzid: &str) -> PhoneTime {
        PhoneTime {
            time: time.to_string(),
            kind,
            tzid: tzid.to_string(),
            offset: None,
            instant: None,
        }
    }

    const MEETING: &str = "BEGIN:VEVENT\r\nUID:meet\r\nDTSTAMP:20260101T000000Z\r\n\
        DTSTART;TZID=Europe/Paris:20261012T090000\r\nDTEND;TZID=Europe/Paris:20261012T100000\r\n\
        SUMMARY:Standup\r\nX-VENDOR;X-PARAM=1:kept\r\n\
        ATTACH:https://example.com/agenda.pdf\r\nEND:VEVENT\r\n";

    #[test]
    fn an_untouched_view_comes_back_byte_for_byte() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:a\r\nDTSTAMP:20260101T000000Z\r\n\
             DTSTART;TZID=/example.org/Romance:20261012T090000\r\nDURATION:PT1H\r\n\
             SUMMARY:Weekly\\, with a comma\r\nDESCRIPTION:Line one\\nline two\r\n\
             RRULE:FREQ=WEEKLY;BYDAY=MO\r\nEXDATE:20261019T070000Z\r\n\
             ATTENDEE;CN=Bob;ROLE=REQ-PARTICIPANT;PARTSTAT=DELEGATED:mailto:bob@example.com\r\n\
             ORGANIZER;CN=Jane:mailto:jane@example.com\r\nX-ODD:value\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:-PT10M\r\nEND:VALARM\r\n\
             BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER:-P1D\r\nEND:VALARM\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:a\r\nRECURRENCE-ID;TZID=/example.org/Romance:20261026T090000\r\n\
             DTSTART;TZID=/example.org/Romance:20261026T110000\r\nDURATION:PT1H\r\n\
             SUMMARY:Moved\r\nEND:VEVENT\r\n"
        ));

        let event = view(&ical);
        assert_eq!(applied(&ical, event, &[]), ical);
    }

    #[test]
    fn projects_each_field_of_a_master() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\nDTEND:20261012T100000Z\r\n\
             SUMMARY:Review\r\nDESCRIPTION:Notes\r\nLOCATION:Room 1\r\nURL:https://example.com/a\r\n\
             STATUS:TENTATIVE\r\nTRANSP:TRANSPARENT\r\nCLASS:PRIVATE\r\nCOLOR:tomato\r\n\
             ORGANIZER:mailto:jane@example.com\r\n\
             ATTENDEE;CN=Bob;ROLE=OPT-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED:mailto:bob@example.com\r\n\
             ATTENDEE:urn:uuid:no-address\r\n\
             ATTENDEE;EMAIL=room@example.com;CUTYPE=ROOM:urn:uuid:room\r\nEND:VEVENT\r\n",
        );

        let master = master(&ical);

        assert_eq!(view(&ical).uid, "a");
        assert_eq!(master.recurrence_id, None);
        assert_eq!(master.summary.as_deref(), Some("Review"));
        assert_eq!(master.description.as_deref(), Some("Notes"));
        assert_eq!(master.location.as_deref(), Some("Room 1"));
        assert_eq!(master.url.as_deref(), Some("https://example.com/a"));
        assert_eq!(master.status.as_deref(), Some("TENTATIVE"));
        assert_eq!(master.transp.as_deref(), Some("TRANSPARENT"));
        assert_eq!(master.class.as_deref(), Some("PRIVATE"));
        assert_eq!(master.color.as_deref(), Some("tomato"));
        assert_eq!(master.organizer.as_deref(), Some("jane@example.com"));
        assert_eq!(
            master.start,
            Some(time("20261012T090000", EventTimeKind::Utc, ""))
        );
        assert_eq!(
            master.end,
            Some(time("20261012T100000", EventTimeKind::Utc, ""))
        );
        let attendees = master.attendees.unwrap();
        assert_eq!(
            attendees.len(),
            2,
            "the one without an address is not the phone's"
        );
        assert_eq!(
            attendees[0],
            PhoneAttendee {
                email: "bob@example.com".into(),
                name: "Bob".into(),
                role: "OPT-PARTICIPANT".into(),
                cutype: "INDIVIDUAL".into(),
                partstat: "ACCEPTED".into(),
            }
        );
        assert_eq!(attendees[1].email, "room@example.com");
        assert_eq!(attendees[1].cutype, "ROOM");
    }

    #[test]
    fn an_end_is_read_from_a_duration_or_implied() {
        let lasting = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000\r\nDURATION:PT1H30M\r\nEND:VEVENT\r\n",
        );
        let instant = object("BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000\r\nEND:VEVENT\r\n");
        let day = object("BEGIN:VEVENT\r\nUID:a\r\nDTSTART;VALUE=DATE:20261012\r\nEND:VEVENT\r\n");

        assert_eq!(master(&lasting).end.unwrap().time, "20261012T103000");
        assert_eq!(master(&instant).end.unwrap().time, "20261012T090000");
        let day = master(&day);
        assert_eq!(day.start.unwrap().kind, EventTimeKind::Date);
        assert_eq!(day.end, Some(time("20261013", EventTimeKind::Date, "")));
    }

    #[test]
    fn an_implied_end_left_alone_stays_implied() {
        let ical = object("BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000\r\nEND:VEVENT\r\n");

        let written = edited(&ical, |master| {
            master.end = Some(time("20261012T090000", EventTimeKind::Floating, ""));
        });

        assert_eq!(written, ical);
    }

    #[test]
    fn a_new_title_patches_the_title_and_stamps_the_component() {
        let ical = object(MEETING);

        let written = edited(&ical, |master| master.summary = Some("Retro".into()));

        assert!(written.contains("SUMMARY:Retro\r\n"));
        assert!(written.contains("X-VENDOR;X-PARAM=1:kept\r\n"));
        assert!(written.contains("ATTACH:https://example.com/agenda.pdf\r\n"));
        assert!(written.contains("DTSTAMP:20261009T120000Z\r\n"));
        assert!(
            !written.contains("SEQUENCE"),
            "no attendees, nothing to announce"
        );
        assert_eq!(
            written
                .replace("SUMMARY:Retro", "SUMMARY:Standup")
                .replace("20261009T120000Z", "20260101T000000Z"),
            ical,
            "the line keeps its place",
        );
    }

    #[test]
    fn a_cleared_field_removes_its_property() {
        let ical = object(MEETING);

        let written = edited(&ical, |master| master.summary = Some(String::new()));

        assert!(!written.contains("SUMMARY"));
    }

    #[test]
    fn each_text_field_reaches_its_property() {
        let ical = object(MEETING);

        let written = edited(&ical, |master| {
            master.description = Some("Agenda, then; notes".into());
            master.location = Some("Room 2".into());
            master.url = Some("https://example.com/b,c".into());
            master.status = Some("CANCELLED".into());
            master.transp = Some("TRANSPARENT".into());
            master.class = Some("CONFIDENTIAL".into());
            master.color = Some("teal".into());
            master.organizer = Some("jane@example.com".into());
        });

        assert!(written.contains("DESCRIPTION:Agenda\\, then\\; notes\r\n"));
        assert!(written.contains("LOCATION:Room 2\r\n"));
        assert!(written.contains("URL:https://example.com/b,c\r\n"));
        assert!(written.contains("STATUS:CANCELLED\r\n"));
        assert!(written.contains("TRANSP:TRANSPARENT\r\n"));
        assert!(written.contains("CLASS:CONFIDENTIAL\r\n"));
        assert!(written.contains("COLOR:teal\r\n"));
        assert!(written.contains("ORGANIZER:mailto:jane@example.com\r\n"));
        let master = master(&written);
        assert_eq!(master.description.as_deref(), Some("Agenda, then; notes"));
        assert_eq!(master.url.as_deref(), Some("https://example.com/b,c"));
    }

    #[test]
    fn a_moved_time_keeps_its_zone() {
        let ical = object(MEETING);

        let written = edited(&ical, |master| {
            master.start = Some(time(
                "20261012T100000",
                EventTimeKind::Zoned,
                "Europe/Paris",
            ));
            master.end = Some(time(
                "20261012T110000",
                EventTimeKind::Zoned,
                "Europe/Paris",
            ));
        });

        assert!(written.contains("DTSTART;TZID=Europe/Paris:20261012T100000\r\n"));
        assert!(written.contains("DTEND;TZID=Europe/Paris:20261012T110000\r\n"));
        assert!(
            !written.contains("BEGIN:VTIMEZONE"),
            "the zone did not change"
        );
    }

    #[test]
    fn a_time_moved_to_another_zone_brings_its_definition() {
        let ical = object(MEETING);
        let tokyo = "BEGIN:VTIMEZONE\r\nTZID:Asia/Tokyo\r\nBEGIN:STANDARD\r\n\
            DTSTART:19700101T000000\r\nTZOFFSETFROM:+0900\r\nTZOFFSETTO:+0900\r\nEND:STANDARD\r\n\
            END:VTIMEZONE\r\n";
        let mut event = view(&ical);
        let master = event.master.as_mut().unwrap();
        master.start = Some(time("20261012T160000", EventTimeKind::Zoned, "Asia/Tokyo"));

        let written = applied(&ical, event, &[tokyo]);

        assert!(written.contains("DTSTART;TZID=Asia/Tokyo:20261012T160000\r\n"));
        let zone = written.find("BEGIN:VTIMEZONE").unwrap();
        assert!(
            zone < written.find("BEGIN:VEVENT").unwrap(),
            "before what uses it"
        );
    }

    #[test]
    fn a_zone_written_without_its_definition_is_refused() {
        let ical = object(MEETING);
        let mut event = view(&ical);
        event.master.as_mut().unwrap().start =
            Some(time("20261012T160000", EventTimeKind::Zoned, "Asia/Tokyo"));
        let edit = serde_json::json!({ "event": event, "stamp": STAMP });

        assert!(apply(&ical, &edit.to_string()).is_err());
    }

    #[test]
    fn a_zone_only_the_object_defines_carries_its_offsets() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:a\r\nDTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             DTEND;TZID=/example.org/Romance:20261227T100000\r\nEND:VEVENT\r\n"
        ));

        let master = master(&ical);

        assert_eq!(master.start.unwrap().offset, Some(7200));
        assert_eq!(master.end.unwrap().offset, Some(3600));
    }

    #[test]
    fn a_time_read_in_a_zone_only_the_object_defines_is_placed_by_its_instant() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:a\r\nDTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             END:VEVENT\r\n"
        ));

        let written = edited(&ical, |master| {
            // NOTE: 08:00 UTC in December is 09:00 in Paris, whatever
            // offset the summer start carried.
            master.start = Some(PhoneTime {
                instant: Some("20261214T080000Z".into()),
                ..time(
                    "20261214T100000",
                    EventTimeKind::Zoned,
                    "/example.org/Romance",
                )
            });
        });

        assert!(written.contains("DTSTART;TZID=/example.org/Romance:20261214T090000\r\n"));
    }

    #[test]
    fn a_windows_zone_and_a_floating_time_cross_as_written() {
        let windows = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART;TZID=Romance Standard Time:20261012T090000\r\nEND:VEVENT\r\n",
        );
        let floating = object("BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000\r\nEND:VEVENT\r\n");

        assert_eq!(
            master(&windows).start,
            Some(time(
                "20261012T090000",
                EventTimeKind::Zoned,
                "Romance Standard Time"
            ))
        );
        assert_eq!(
            master(&floating).start,
            Some(time("20261012T090000", EventTimeKind::Floating, ""))
        );

        let moved = edited(&floating, |master| {
            master.start = Some(time("20261012T100000", EventTimeKind::Floating, ""));
        });
        assert!(
            moved.contains("DTSTART:20261012T100000\r\n"),
            "still floating"
        );
    }

    #[test]
    fn an_all_day_event_moves_by_dates() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART;VALUE=DATE:20261012\r\nDTEND;VALUE=DATE:20261013\r\nEND:VEVENT\r\n",
        );

        let written = edited(&ical, |master| {
            master.start = Some(time("20261014", EventTimeKind::Date, ""));
            master.end = Some(time("20261016", EventTimeKind::Date, ""));
        });

        assert!(written.contains("DTSTART;VALUE=DATE:20261014\r\n"));
        assert!(written.contains("DTEND;VALUE=DATE:20261016\r\n"));
    }

    #[test]
    fn a_timed_event_turned_all_day_is_written_as_dates() {
        let ical = object(MEETING);

        let written = edited(&ical, |master| {
            master.start = Some(time("20261012", EventTimeKind::Date, ""));
            master.end = Some(time("20261013", EventTimeKind::Date, ""));
        });

        assert!(written.contains("DTSTART;VALUE=DATE:20261012\r\n"));
        assert!(written.contains("DTEND;VALUE=DATE:20261013\r\n"));
    }

    #[test]
    fn a_duration_keeps_being_a_duration() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\nDURATION:PT1H\r\n\
             RRULE:FREQ=DAILY\r\nEND:VEVENT\r\n",
        );
        let day = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART;VALUE=DATE:20261012\r\nDURATION:P1D\r\n\
             RRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\n",
        );

        let longer = edited(&ical, |master| {
            master.end = Some(time("20261012T103000", EventTimeKind::Utc, ""));
        });
        let days = edited(&day, |master| {
            master.end = Some(time("20261015", EventTimeKind::Date, ""));
        });

        assert!(longer.contains("DURATION:PT1H30M\r\n"));
        assert!(!longer.contains("DTEND"));
        assert!(days.contains("DURATION:P3D\r\n"));
    }

    #[test]
    fn rules_are_patched_in_place_or_rewritten() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\nRRULE:FREQ=WEEKLY;BYDAY=MO\r\n\
             SUMMARY:After\r\nEND:VEVENT\r\n",
        );

        let changed = edited(&ical, |master| {
            master.rrules = Some(vec!["FREQ=WEEKLY;BYDAY=MO,WE".into()]);
        });
        let none = edited(&ical, |master| master.rrules = Some(Vec::new()));

        assert!(changed.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE\r\nSUMMARY:After"));
        assert!(!none.contains("RRULE"));
    }

    #[test]
    fn dates_lists_keep_what_stays_and_add_what_is_new() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART;TZID=Europe/Paris:20261012T090000\r\n\
             RRULE:FREQ=DAILY\r\nEXDATE:20261013T070000Z,20261014T070000Z\r\n\
             EXDATE;TZID=Europe/Paris:20261020T090000\r\nEND:VEVENT\r\n",
        );
        let mut event = view(&ical);
        let master = event.master.as_mut().unwrap();
        let exdates = master.exdates.as_mut().unwrap();
        // NOTE: one dropped from the first line, the second line emptied,
        // one new in the series' zone.
        exdates.remove(1);
        exdates.remove(1);
        exdates.push(time(
            "20261022T090000",
            EventTimeKind::Zoned,
            "Europe/Paris",
        ));

        let written = applied(&ical, event, &[]);

        assert!(written.contains("EXDATE:20261013T070000Z\r\n"));
        assert!(written.contains("EXDATE;TZID=Europe/Paris:20261022T090000\r\n"));
        assert!(!written.contains("20261020T090000"));
        assert!(!written.contains("20261014T070000Z"));
    }

    #[test]
    fn an_all_day_exdate_is_written_as_a_date() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART;VALUE=DATE:20261012\r\nRRULE:FREQ=DAILY\r\nEND:VEVENT\r\n",
        );

        let written = edited(&ical, |master| {
            master.exdates = Some(vec![time("20261014", EventTimeKind::Date, "")]);
        });

        assert!(written.contains("EXDATE;VALUE=DATE:20261014\r\n"));
    }

    #[test]
    fn attendees_are_patched_by_address() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\nORGANIZER:mailto:jane@example.com\r\n\
             ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION;X-KEEP=1:mailto:Bob@Example.com\r\n\
             ATTENDEE;CN=Carol:mailto:carol@example.com\r\n\
             ATTENDEE:urn:uuid:no-address\r\nSEQUENCE:2\r\nEND:VEVENT\r\n",
        );
        let mut event = view(&ical);
        let attendees = event.master.as_mut().unwrap().attendees.as_mut().unwrap();
        attendees[0].partstat = "ACCEPTED".into();
        attendees.remove(1);
        attendees.push(PhoneAttendee {
            email: "dan@example.com".into(),
            name: "Dan, Jr.".into(),
            role: "OPT-PARTICIPANT".into(),
            ..Default::default()
        });

        let written = applied(&ical, event, &[]);

        assert!(
            written
                .contains("ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED;X-KEEP=1:mailto:Bob@Example.com\r\n")
        );
        assert!(!written.contains("carol"));
        assert!(
            written.contains(
                "ATTENDEE;CN=\"Dan, Jr.\";ROLE=OPT-PARTICIPANT:mailto:dan@example.com\r\n"
            )
        );
        assert!(written.contains("ATTENDEE:urn:uuid:no-address\r\n"));
        assert!(
            written.contains("SEQUENCE:3\r\n"),
            "the organizer's edit is announced"
        );
    }

    #[test]
    fn an_edit_of_someone_elses_event_is_not_announced() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\nORGANIZER:mailto:boss@example.com\r\n\
             ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:jane@example.com\r\nSEQUENCE:4\r\nEND:VEVENT\r\n",
        );
        let mut event = view(&ical);
        event.master.as_mut().unwrap().attendees.as_mut().unwrap()[0].partstat = "ACCEPTED".into();

        let written = applied(&ical, event, &[]);

        assert!(written.contains("ATTENDEE;PARTSTAT=ACCEPTED:mailto:jane@example.com\r\n"));
        assert!(written.contains("SEQUENCE:4\r\n"));
    }

    #[test]
    fn alarms_convert_to_minutes_before_the_start() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\nDTEND:20261012T100000Z\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT5M30S\r\nEND:VALARM\r\n\
             BEGIN:VALARM\r\nACTION:AUDIO\r\nTRIGGER;RELATED=END:-PT10M\r\nEND:VALARM\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:PT15M\r\nREPEAT:2\r\nDURATION:PT5M\r\nEND:VALARM\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;VALUE=DATE-TIME:20261012T080000Z\r\nEND:VALARM\r\n\
             BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER:-PT1H\r\nEND:VALARM\r\nEND:VEVENT\r\n",
        );

        let alarms = master(&ical).alarms.unwrap();

        assert_eq!(
            alarms,
            vec![
                PhoneAlarm {
                    minutes: Some(5),
                    absolute: None
                },
                // NOTE: ten minutes before the end of an hour is fifty after the
                // start, which the provider fires as negative minutes.
                PhoneAlarm {
                    minutes: Some(-50),
                    absolute: None
                },
                PhoneAlarm {
                    minutes: Some(-15),
                    absolute: None
                },
                PhoneAlarm {
                    minutes: None,
                    absolute: Some("20261012T080000Z".into())
                },
            ]
        );
    }

    #[test]
    fn an_absolute_alarm_on_a_series_is_not_the_phones() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\nRRULE:FREQ=DAILY\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;VALUE=DATE-TIME:20261012T080000Z\r\nEND:VALARM\r\n\
             END:VEVENT\r\n",
        );

        assert!(master(&ical).alarms.unwrap().is_empty());
    }

    #[test]
    fn alarms_come_back_kept_rewritten_removed_and_added() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\nDTEND:20261012T100000Z\r\nSUMMARY:Call\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:-PT10M\r\nX-WR-ALARMUID:one\r\nEND:VALARM\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT30M\r\nX-WR-ALARMUID:two\r\nEND:VALARM\r\n\
             BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER:-PT1H\r\nEND:VALARM\r\nEND:VEVENT\r\n",
        );
        let minutes = |list: &[i64]| {
            list.iter()
                .map(|minutes| PhoneAlarm {
                    minutes: Some(*minutes),
                    absolute: None,
                })
                .collect::<Vec<_>>()
        };

        let kept = edited(&ical, |master| master.alarms = Some(minutes(&[30, -50])));
        let rewritten = edited(&ical, |master| master.alarms = Some(minutes(&[-50, 20])));
        let removed = edited(&ical, |master| master.alarms = Some(minutes(&[-50])));
        let added = edited(&ical, |master| master.alarms = Some(minutes(&[-50, 30, 0])));

        assert_eq!(kept, ical, "order is no edit");
        assert!(rewritten.contains("TRIGGER;RELATED=END:-PT10M\r\nX-WR-ALARMUID:one"));
        assert!(rewritten.contains("TRIGGER:-PT20M\r\nX-WR-ALARMUID:two"));
        assert!(!removed.contains("X-WR-ALARMUID:two"));
        assert!(
            removed.contains("ACTION:EMAIL"),
            "never the phone's to remove"
        );
        assert!(added.contains(
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:PT0S\r\nDESCRIPTION:Call\r\nEND:VALARM\r\nEND:VEVENT"
        ));
        assert_eq!(master(&added).alarms.unwrap().len(), 3);
    }

    const SERIES: &str = "BEGIN:VEVENT\r\nUID:s\r\nDTSTAMP:20260101T000000Z\r\n\
        DTSTART;TZID=Europe/Paris:20261005T090000\r\nDTEND;TZID=Europe/Paris:20261005T100000\r\n\
        RRULE:FREQ=WEEKLY;BYDAY=MO\r\nSUMMARY:Weekly\r\nEND:VEVENT\r\n";

    fn override_of() -> PhoneComponent {
        PhoneComponent {
            recurrence_id: Some(time(
                "20261012T090000",
                EventTimeKind::Zoned,
                "Europe/Paris",
            )),
            summary: Some("Moved".into()),
            ..PhoneComponent::default()
        }
    }

    #[test]
    fn overrides_of_a_series_are_projected_and_others_are_not() {
        let series = object(&format!(
            "{SERIES}BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID;TZID=Europe/Paris:20261012T090000\r\n\
             DTSTART;TZID=Europe/Paris:20261012T110000\r\nSUMMARY:Later\r\nEND:VEVENT\r\n"
        ));
        let single = series.replace("RRULE:FREQ=WEEKLY;BYDAY=MO\r\n", "");

        let projected = view(&series).overrides.unwrap();
        assert_eq!(projected.len(), 1);
        assert_eq!(projected[0].summary.as_deref(), Some("Later"));
        assert_eq!(
            projected[0].recurrence_id,
            Some(time(
                "20261012T090000",
                EventTimeKind::Zoned,
                "Europe/Paris"
            ))
        );
        assert!(view(&single).overrides.unwrap().is_empty());
    }

    #[test]
    fn an_occurrence_edited_on_the_phone_becomes_an_override() {
        let ical = object(SERIES);
        let mut event = view(&ical);
        event.master = None;
        event.overrides = Some(vec![override_of()]);

        let written = applied(&ical, event, &[]);

        let overrides = view(&written).overrides.unwrap();
        assert_eq!(overrides.len(), 1);
        assert_eq!(overrides[0].summary.as_deref(), Some("Moved"));
        assert_eq!(
            overrides[0].start,
            Some(time(
                "20261012T090000",
                EventTimeKind::Zoned,
                "Europe/Paris"
            )),
            "the instance's own start, carried from the series"
        );
        assert!(written.contains("RECURRENCE-ID;TZID=Europe/Paris:20261012T090000\r\n"));
        assert!(!written[written.find("RECURRENCE-ID").unwrap()..].contains("RRULE"));
        assert!(written.ends_with("END:VEVENT\r\nEND:VCALENDAR\r\n"));
    }

    #[test]
    fn an_override_found_by_its_instant_in_utc_is_the_same() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:s\r\nDTSTART;TZID=/example.org/Romance:20261005T090000\r\n\
             RRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:s\r\n\
             RECURRENCE-ID:20261012T070000Z\r\nDTSTART:20261012T080000Z\r\nSUMMARY:Old\r\nEND:VEVENT\r\n"
        ));
        let mut event = view(&ical);
        event.master = None;
        event.overrides = Some(vec![PhoneComponent {
            recurrence_id: Some(time(
                "20261012T090000",
                EventTimeKind::Zoned,
                "/example.org/Romance",
            )),
            summary: Some("New".into()),
            ..Default::default()
        }]);

        let written = applied(&ical, event, &[]);

        assert!(written.contains("SUMMARY:New\r\n"));
        assert_eq!(written.matches("RECURRENCE-ID").count(), 1);
    }

    #[test]
    fn an_occurrence_cancelled_on_the_phone_becomes_an_exdate() {
        let ical = object(&format!(
            "{SERIES}BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID;TZID=Europe/Paris:20261019T090000\r\n\
             DTSTART;TZID=Europe/Paris:20261019T110000\r\nSUMMARY:Later\r\nEND:VEVENT\r\n"
        ));
        let mut event = view(&ical);
        event.master = None;
        let overrides = event.overrides.as_mut().unwrap();
        overrides[0].status = Some("CANCELLED".into());
        overrides.push(PhoneComponent {
            status: Some("CANCELLED".into()),
            ..override_of()
        });

        let written = applied(&ical, event, &[]);

        assert!(written.contains("EXDATE;TZID=Europe/Paris:20261012T090000\r\n"));
        assert!(written.contains("EXDATE;TZID=Europe/Paris:20261019T090000\r\n"));
        assert!(
            !written.contains("RECURRENCE-ID"),
            "the cancelled override goes"
        );
    }

    #[test]
    fn a_cancelling_override_the_object_holds_stays_one() {
        let ical = object(&format!(
            "{SERIES}BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID;TZID=Europe/Paris:20261019T090000\r\n\
             DTSTART;TZID=Europe/Paris:20261019T090000\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n"
        ));
        let mut event = view(&ical);
        event.master = None;
        event.overrides.as_mut().unwrap()[0].location = Some("Nowhere".into());

        let written = applied(&ical, event, &[]);

        assert!(written.contains("STATUS:CANCELLED\r\nLOCATION:Nowhere\r\n"));
        assert!(!written.contains("EXDATE"));
    }

    #[test]
    fn an_override_the_phone_dropped_reverts_its_instance() {
        let ical = object(&format!(
            "{SERIES}BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID;TZID=Europe/Paris:20261019T090000\r\n\
             DTSTART;TZID=Europe/Paris:20261019T110000\r\nEND:VEVENT\r\n"
        ));
        let event = PhoneEvent {
            overrides: Some(Vec::new()),
            ..Default::default()
        };

        let written = applied(&ical, event, &[]);

        assert_eq!(written, object(SERIES));
    }

    #[test]
    fn an_object_without_a_master_is_its_overrides() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:i\r\nRECURRENCE-ID:20261012T090000Z\r\nDTSTART:20261012T090000Z\r\n\
             SUMMARY:One of many\r\nEND:VEVENT\r\n",
        );

        let event = view(&ical);
        assert!(event.master.is_none());
        let mut overrides = event.overrides.clone().unwrap();
        assert_eq!(overrides.len(), 1);
        assert_eq!(overrides[0].summary.as_deref(), Some("One of many"));
        assert_eq!(applied(&ical, event, &[]), ical);

        overrides[0].summary = Some("Renamed".into());
        let written = applied(
            &ical,
            PhoneEvent {
                overrides: Some(overrides),
                ..Default::default()
            },
            &[],
        );
        assert!(written.contains("SUMMARY:Renamed\r\n"));
    }

    #[test]
    fn todos_and_journal_entries_are_not_the_phones() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:t\r\nDTSTART:20261012T090000Z\r\nSUMMARY:Buy milk\r\nEND:VTODO\r\n",
        );

        let event = view(&ical);

        assert!(event.master.is_none());
        assert!(event.overrides.unwrap().is_empty());
    }

    #[test]
    fn an_event_created_on_the_phone_starts_from_a_skeleton() {
        let event = PhoneEvent {
            uid: "new-1".into(),
            master: Some(PhoneComponent {
                summary: Some("Lunch".into()),
                start: Some(time("20261012T120000", EventTimeKind::Utc, "")),
                end: Some(time("20261012T130000", EventTimeKind::Utc, "")),
                rrules: Some(vec!["FREQ=WEEKLY".into()]),
                alarms: Some(vec![PhoneAlarm {
                    minutes: Some(10),
                    absolute: None,
                }]),
                ..Default::default()
            }),
            ..Default::default()
        };

        let written = applied("", event, &[]);

        assert!(written.starts_with("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:"));
        assert!(written.contains("UID:new-1\r\n"));
        assert!(
            written.contains("DTEND:20261012T130000Z\r\n"),
            "never a DURATION"
        );
        assert!(written.contains("RRULE:FREQ=WEEKLY\r\n"));
        assert!(written.contains("TRIGGER:-PT10M\r\n"));
        let master = master(&written);
        assert_eq!(master.summary.as_deref(), Some("Lunch"));
    }

    #[test]
    fn a_series_the_provider_shows_is_not_listed() {
        assert!(!view(&object(SERIES)).listed);
    }

    #[test]
    fn a_series_the_provider_cannot_show_is_listed_around_now() {
        for rule in [
            "FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=-1;RSCALE=GREGORIAN;SKIP=FORWARD",
            "FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO",
            "FREQ=MONTHLY;BYMONTHDAY=1,15;BYSETPOS=1",
        ] {
            let ical = object(&format!(
                "BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20261001T090000Z\r\nDURATION:PT1H\r\n\
                 RRULE:{rule}\r\nEND:VEVENT\r\n"
            ));
            let event = view(&ical);
            assert!(event.listed, "{rule}");
            let master = event.master.unwrap();
            assert_eq!(master.rrules, Some(Vec::new()));
            assert!(!master.rdates.unwrap().is_empty(), "{rule}");
        }

        let monthly = object(
            "BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20261001T090000Z\r\n\
             RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1\r\nEND:VEVENT\r\n",
        );
        assert!(
            !view(&monthly).listed,
            "plain weekdays the provider expands"
        );
    }

    #[test]
    fn a_daily_series_started_long_ago_is_listed() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20180101T090000Z\r\nRRULE:FREQ=DAILY\r\nEND:VEVENT\r\n",
        );
        let recent = object(
            "BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20240101T090000Z\r\nRRULE:FREQ=DAILY\r\nEND:VEVENT\r\n",
        );

        let event = view(&ical);

        assert!(event.listed);
        let dates = event.master.unwrap().rdates.unwrap();
        assert_eq!(dates.len(), LISTED_MAX, "cut at the cap");
        // NOTE: a year back and two ahead hold 1095 days; the cut takes
        // the far end, keeping the year back whole and 635 days ahead.
        assert_eq!(dates.first().unwrap().time, "20251010T090000");
        assert_eq!(dates.last().unwrap().time, "20280705T090000");
        assert!(!view(&recent).listed);
    }

    #[test]
    fn an_infinite_rule_beside_dates_and_a_period_are_listed() {
        let infinite = object(
            "BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20261001T090000Z\r\nRRULE:FREQ=WEEKLY\r\n\
             RDATE:20261003T090000Z\r\nEND:VEVENT\r\n",
        );
        let period = object(
            "BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20261001T090000Z\r\n\
             RDATE;VALUE=PERIOD:20261003T090000Z/PT2H\r\nEND:VEVENT\r\n",
        );

        assert!(view(&infinite).listed);
        let listed = view(&period);
        assert!(listed.listed);
        assert_eq!(
            listed
                .master
                .unwrap()
                .rdates
                .unwrap()
                .iter()
                .map(|d| d.time.as_str())
                .collect::<Vec<_>>(),
            ["20261001T090000", "20261003T090000"]
        );
    }

    #[test]
    fn a_listed_series_keeps_its_rule_whatever_the_phone_does_to_its_dates() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20261001T090000Z\r\nDTEND:20261001T100000Z\r\n\
             RRULE:FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO\r\nSUMMARY:Old\r\nEND:VEVENT\r\n",
        );
        let mut event = view(&ical);
        let master = event.master.as_mut().unwrap();
        master.summary = Some("New".into());
        master.start = Some(time("20261002T090000", EventTimeKind::Utc, ""));
        master.rdates = Some(Vec::new());
        master.rrules = Some(Vec::new());

        let written = applied(&ical, event, &[]);

        assert!(written.contains("SUMMARY:New\r\n"));
        assert!(written.contains("DTSTART:20261001T090000Z\r\n"));
        assert!(written.contains("RRULE:FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO\r\n"));
        assert!(!written.contains("RDATE"));
    }

    #[test]
    fn a_this_and_future_override_is_listed_at_its_shifted_times() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:t\r\nDTSTART:20261005T090000Z\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:t\r\nRECURRENCE-ID;RANGE=THISANDFUTURE:20261012T090000Z\r\n\
             DTSTART:20261012T110000Z\r\nEND:VEVENT\r\n",
        );

        let event = view(&ical);

        assert!(event.listed);
        let dates: Vec<String> = event
            .master
            .unwrap()
            .rdates
            .unwrap()
            .into_iter()
            .map(|date| date.time)
            .collect();
        assert_eq!(
            dates,
            [
                "20261005T090000",
                "20261012T090000",
                "20261019T110000",
                "20261026T110000"
            ]
        );

        // NOTE: the phone edits the shifted instance it shows at 11:00;
        // the override written names the instance it is.
        let edit = PhoneEvent {
            overrides: Some(vec![
                event.overrides.unwrap()[0].clone(),
                PhoneComponent {
                    recurrence_id: Some(time("20261019T110000", EventTimeKind::Utc, "")),
                    summary: Some("Third".into()),
                    ..Default::default()
                },
            ]),
            ..Default::default()
        };
        let written = applied(&ical, edit, &[]);
        assert!(written.contains("RECURRENCE-ID:20261019T090000Z\r\n"));
    }

    #[test]
    fn an_instance_cancelled_twice_is_excluded_once() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20261005T090000Z\r\n\
             RRULE:FREQ=YEARLY;BYWEEKNO=41;BYDAY=MO\r\nEXDATE:20271011T090000Z\r\nEND:VEVENT\r\n",
        );
        let edit = PhoneEvent {
            overrides: Some(vec![PhoneComponent {
                recurrence_id: Some(time("20271011T090000", EventTimeKind::Utc, "")),
                status: Some("CANCELLED".into()),
                ..Default::default()
            }]),
            ..Default::default()
        };

        let written = applied(&ical, edit, &[]);

        assert_eq!(written.matches("EXDATE").count(), 1);
    }

    #[test]
    fn attendees_and_alarms_edited_together_land_where_they_belong() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:a\r\nDTSTART:20261012T090000Z\r\n\
             ATTENDEE:mailto:bob@example.com\r\nATTENDEE:mailto:carol@example.com\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT5M\r\nEND:VALARM\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\nEND:VEVENT\r\n",
        );
        let mut event = view(&ical);
        let master = event.master.as_mut().unwrap();
        let attendees = master.attendees.as_mut().unwrap();
        attendees.remove(0);
        attendees.push(PhoneAttendee {
            email: "dan@example.com".into(),
            ..Default::default()
        });
        master.alarms = Some(vec![PhoneAlarm {
            minutes: Some(10),
            absolute: None,
        }]);

        let written = applied(&ical, event, &[]);

        assert!(written.contains(
            "ATTENDEE:mailto:carol@example.com\r\nATTENDEE:mailto:dan@example.com\r\n\
             DTSTAMP:20261009T120000Z\r\nSEQUENCE:1\r\n\
             BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\nEND:VEVENT"
        ));
    }

    #[test]
    fn a_new_override_of_an_organized_series_is_announced_once() {
        let ical = object(&SERIES.replace(
            "SUMMARY:Weekly\r\n",
            "SUMMARY:Weekly\r\nATTENDEE:mailto:bob@example.com\r\nSEQUENCE:3\r\n",
        ));
        let edit = PhoneEvent {
            overrides: Some(vec![override_of()]),
            ..Default::default()
        };

        let written = applied(&ical, edit, &[]);

        let at = written.find("SUMMARY:Moved").unwrap();
        assert!(written[at..].contains("SEQUENCE:4\r\n"));
        assert!(
            written[..at].contains("SEQUENCE:3\r\n"),
            "the series itself is untouched"
        );
    }
}
