//! Calendar reading and writing: one calendar object's occurrences
//! inside a window, and the edits its entry page makes.
//!
//! The parsing half is ical-rs's decoded model and syntax tree, the
//! recurrence half its recurrence set. Expansion is civil, as RFC 5545
//! defines it: an event at 09:00 recurs at 09:00 on the wall clock of
//! its start. Every time crosses the bridge with what it is civil in
//! ([`EventTime`]), and the Java side makes it an instant with the
//! platform's time-zone database; this side resolves only the zones an
//! object defines for itself ([`zone`]).

mod conflict;
mod occurrence;
pub mod phone;
mod series;
mod zone;

use ical::{
    component::{IcalComponent, IcalComponentKind, IcalComponentName},
    param::IcalParam,
    prop::{
        IcalProp, IcalPropKind, IcalPropName, categories::CATEGORIES, description::DESCRIPTION,
        location::LOCATION, percent_complete::PERCENT_COMPLETE, priority::PRIORITY, status::STATUS,
        summary::SUMMARY, url::URL,
    },
    recur::IcalRecurDateTime,
    tree::{
        cst::{IcalCst, IcalItem},
        line::IcalLine,
        prop::lens::IcalPropLens,
        value::cursor::IcalValueCursor,
    },
    value::{
        IcalValue,
        datetime::{IcalDate, IcalDateTime},
        integer::IcalInteger,
        text::{IcalText, IcalTextList},
        uri::IcalUri,
    },
};
use serde::{Deserialize, Serialize};

use crate::types::BridgeError;

pub use conflict::{merge, resolve};
pub use occurrence::{
    OccurrenceChange, OccurrenceWrite, occurrence_changes, occurrence_window, occurrences,
};
pub use series::{remove, split};
use zone::Zones;
pub use zone::{EventTime, EventTimeKind};

/// How many occurrences one event may contribute to a window.
///
/// A window is a month at most, so a legitimate rule cannot exceed this
/// even at `FREQ=HOURLY`; the cap is what keeps a `FREQ=SECONDLY` event
/// from filling the agenda with a million rows.
const MAX_OCCURRENCES: usize = 1024;

/// What every object this side composes names as its producer.
const PRODID: &str = "-//Pimalaya//Pimalaya for Android//EN";

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
    /// When it starts: where the rules placed it, or where an override
    /// moved it, in that override's own zone.
    pub start: EventTime,
    /// When it ends; the start on a zero-length component, which is
    /// every journal entry and most to-dos.
    pub end: EventTime,
    /// Which instance of its series it is: the value a `RECURRENCE-ID`
    /// naming it carries, in the series' zone. Absent on an entry that
    /// does not recur, which is what tells the page there is no series
    /// to ask about.
    pub recurrence_id: Option<EventTime>,
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
/// The series' own component, or the override of the instance a
/// `recurrence_id` names when the object holds one: that is what the
/// occurrence a reader opened looks like. Either way the rule is the
/// series', since an override repeats nothing.
pub fn read(ical: &str, recurrence_id: &str) -> Result<EventDetail, BridgeError> {
    let cst = IcalCst::parse(ical).map_err(|err| err.to_string())?;
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

/// What one edit changes on a component.
///
/// Every field is optional, and an absent one is left alone: the form
/// sends what it changed, so a to-do's edit never mentions an end and a
/// journal entry's never mentions a due date. A present field that is
/// empty <em>removes</em> the property, which is how a form clears a
/// value.
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
    /// Now, as a UTC `YYYYMMDDTHHMMSSZ` stamp, for the two properties
    /// that record when the object was last touched. Passed in rather
    /// than read here, so this stays a pure function of its inputs.
    pub stamp: Option<String>,
    /// Which occurrences of a series the edit applies to.
    pub scope: EventScope,
    /// The occurrence the page was opened on: its identity, as
    /// [`Occurrence::recurrence_id`] gave it. With it, the dates of an
    /// edit are that occurrence's, and an edit of the whole series moves
    /// the series by as much as the occurrence moved.
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

/// The object a new entry starts from: one component carrying the three
/// properties RFC 5545 requires of it, and the `VTIMEZONE` its start's
/// zone needs.
///
/// `UID` identifies it (section 3.8.4.7), `DTSTAMP` says when it was
/// composed (section 3.8.7.2), and `DTSTART` places it, which every one
/// of the three components an agenda shows needs to appear on a day. A
/// to-do could be placed by `DUE` instead, but a page that opens with
/// neither has nowhere to put the entry, so the start is what a new one
/// carries and the page moves it.
///
/// The start is a date, a floating time, or a time in the zone `tzid`
/// names, which `vtimezone` defines: every `TZID` an object uses owes
/// it a definition (section 3.2.19), and this side has no database to
/// write one from, so the caller does.
///
/// Both stamps are the caller's, like the edit's, so this stays a pure
/// function of its inputs: nothing here reads a clock or mints an id.
pub fn create(
    component: &str,
    uid: &str,
    stamp: &str,
    start: &str,
    tzid: &str,
    vtimezone: &str,
) -> Result<String, BridgeError> {
    let kind = match component {
        "VEVENT" | "VTODO" | "VJOURNAL" => component,
        other => return Err(format!("Cannot create a `{other}`").into()),
    };

    let (dtstart, zone) = match (start.len(), tzid.is_empty()) {
        (8, _) => (format!("DTSTART;VALUE=DATE:{start}"), ""),
        (_, true) => (format!("DTSTART:{start}"), ""),
        _ => (format!("DTSTART;TZID={tzid}:{start}"), vtimezone.trim_end()),
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
         {dtstart}\r\n\
         END:{kind}\r\n\
         END:VCALENDAR\r\n"
    );

    // NOTE: parsed back rather than returned as built. The values come
    // from the caller, and an object this refused to read would be one
    // the page then opens on nothing.
    let cst = IcalCst::parse(object.as_str()).map_err(|err| err.to_string())?;
    if !tzid.is_empty() && start.len() != 8 && Zones::of_cst(&cst).find(tzid).is_none() {
        return Err(format!("No definition of the zone `{tzid}`").into());
    }

    Ok(object)
}

/// Applies one edit to a calendar object, returning the new iCalendar.
///
/// A patch and not a rebuild: the object is walked as a concrete syntax
/// tree and only the edited properties are replaced, so everything the
/// form does not manage (VALARMs, X- properties, the ATTENDEE list, the
/// parameters on the lines it leaves alone) survives byte for byte.
/// That is the same rule the vCard side follows, and for the same
/// reason: a client that rewrites what it does not understand loses
/// other clients' data.
///
/// An edit of one occurrence patches its override, written first when
/// the object has none. An edit of the whole series opened on one of
/// its occurrences moves the series' dates by as much as that
/// occurrence's moved. Splitting a series is [`split`], since it writes
/// two objects.
pub fn write(ical: &str, edit: &str) -> Result<String, BridgeError> {
    let edit: EventEdit = serde_json::from_str(edit).map_err(|err| err.to_string())?;

    let cst = IcalCst::parse(ical).map_err(|err| err.to_string())?;
    let mut cst = cst.into_static();

    match edit.scope {
        EventScope::All => series::all(&mut cst, &edit)?,
        EventScope::This => series::this(&mut cst, &edit)?,
        EventScope::Following => return Err("Splitting a series writes two objects".into()),
    }
    define_zone(&mut cst, &edit)?;

    text(&cst)
}

/// Replaces every edited property of one component, in place.
fn patch(component: &mut IcalCst<'static>, edit: &EventEdit) {
    replace_text::<SUMMARY>(component, IcalPropKind::Summary, &edit.summary);
    replace_text::<DESCRIPTION>(component, IcalPropKind::Description, &edit.description);
    replace_text::<LOCATION>(component, IcalPropKind::Location, &edit.location);
    replace_text::<STATUS>(component, IcalPropKind::Status, &edit.status);

    if let Some(value) = &edit.url {
        component.remove::<URL>();
        if !value.is_empty() {
            insert(
                component,
                prop(
                    IcalPropKind::Url,
                    Vec::new(),
                    IcalValue::Uri(IcalUri(value.clone().into())),
                ),
            );
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
            insert(
                component,
                prop(
                    IcalPropKind::Categories,
                    Vec::new(),
                    IcalValue::TextList(IcalTextList(items)),
                ),
            );
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

    touch(component, edit);
}

/// Stamps a component as edited now.
///
/// RFC 5545 3.8.7.2 and 3.8.7.3: an edit is when the object was last
/// built, and when it last changed. Both, because a server and another
/// client read different ones.
fn touch(component: &mut IcalCst<'static>, edit: &EventEdit) {
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
        insert(
            component,
            prop(
                kind,
                Vec::new(),
                IcalValue::Text(IcalText(value.clone().into())),
            ),
        );
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
        insert(
            component,
            prop(
                kind,
                Vec::new(),
                IcalValue::Integer(IcalInteger(value.clone().into())),
            ),
        );
    }
}

/// Replaces a date property.
///
/// A value relative to nothing new keeps the line it replaces, `TZID`
/// and `VALUE` and position included, and only its digits change: an
/// event at 09:00 New York moved to 10:00 is still in New York. A value
/// naming its own zone, or one of the other shape (a date replacing a
/// date-time), is written fresh, with the parameters its zone needs:
/// without `VALUE=DATE` a bare `YYYYMMDD` is not a legal DATE-TIME and
/// servers reject the object.
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
    insert(component, date_prop(kind, &time));
}

/// A date property carrying one time, with the parameters its zone
/// needs.
fn date_prop(kind: IcalPropKind, time: &EventTime) -> IcalProp<'static> {
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
fn define_zone(cst: &mut IcalCst<'static>, edit: &EventEdit) -> Result<(), BridgeError> {
    let Some(definition) = edit.vtimezone.as_deref().filter(|text| !text.is_empty()) else {
        return Ok(());
    };

    let zone = IcalCst::parse(definition)
        .map_err(|err| err.to_string())?
        .into_static();
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

fn prop(
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

/// Adds a property to a component, before its nested components.
///
/// Not `IcalCst::push`, which appends after them: RFC 5545 3.6.1 lists
/// a VEVENT's properties before its VALARMs, and a strict server reads
/// a property past an alarm as the object being malformed.
fn insert(component: &mut IcalCst<'static>, prop: IcalProp<'static>) {
    component.push(prop);
    if let Some(item) = component.items.pop() {
        place(component, item);
    }
}

/// Puts one item before a component's nested components.
fn place(component: &mut IcalCst<'static>, item: IcalItem<'static>) {
    let at = component
        .items
        .iter()
        .position(|item| matches!(item, IcalItem::Component(_)))
        .unwrap_or(component.items.len());
    component.items.insert(at, item);
}

/// Drops every line of one property from a component.
fn remove_named(component: &mut IcalCst<'static>, name: &str) {
    component.items.retain(
        |item| !matches!(item, IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case(name)),
    );
}

/// The first line of one property of a component.
fn line<'c>(component: &'c IcalCst<'static>, name: &str) -> Option<&'c IcalLine<'static>> {
    component.items.iter().find_map(|item| match item {
        IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case(name) => Some(line),
        _ => None,
    })
}

fn line_mut<'c>(
    component: &'c mut IcalCst<'static>,
    name: &str,
) -> Option<&'c mut IcalLine<'static>> {
    component.items.iter_mut().find_map(|item| match item {
        IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case(name) => Some(line),
        _ => None,
    })
}

/// Where every scheduled component sits among the calendar's items.
fn scheduled(cst: &IcalCst) -> Vec<usize> {
    cst.items
        .iter()
        .enumerate()
        .filter_map(|(index, item)| match item {
            IcalItem::Component(child) => {
                let name = child.begin.as_ref()?.raw_value_str().to_ascii_uppercase();
                SCHEDULED
                    .iter()
                    .any(|kind| kind.to_string() == name)
                    .then_some(index)
            }
            _ => None,
        })
        .collect()
}

/// The component at one of the calendar's items.
fn child<'c>(cst: &'c IcalCst<'static>, index: usize) -> &'c IcalCst<'static> {
    match &cst.items[index] {
        IcalItem::Component(child) => child,
        _ => unreachable!("not a component"),
    }
}

fn child_mut<'c>(cst: &'c mut IcalCst<'static>, index: usize) -> &'c mut IcalCst<'static> {
    match &mut cst.items[index] {
        IcalItem::Component(child) => child,
        _ => unreachable!("not a component"),
    }
}

/// A calendar's text.
fn text(cst: &IcalCst) -> Result<String, BridgeError> {
    String::from_utf8(cst.to_bytes()).map_err(|err| err.to_string().into())
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

    // NOTE: a VEVENT nests only VALARMs, but a VCALENDAR arriving inside
    // another component (some servers wrap) would otherwise be missed.
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
/// occurrences starting inside `[from, until)`, both civil `YYYYMMDD` or
/// `YYYYMMDDTHHMMSS` stamps.
///
/// A series is walked as the recurrence set RFC 5545 3.8.5 composes:
/// its rules and dates, less its `EXDATE`s, each instance an override
/// replaces taken from that override, and an override cancelling its
/// instance leaving it out. The window is compared with each start as
/// written, so a caller widens it by the largest offset it can meet and
/// sorts the instants out itself.
pub fn expand(ical: &str, from: &str, until: &str) -> Result<Vec<Occurrence>, BridgeError> {
    let from = IcalRecurDateTime::parse(from).map_err(|err| err.to_string())?;
    let until = IcalRecurDateTime::parse(until).map_err(|err| err.to_string())?;

    let cst = IcalCst::parse(ical).map_err(|err| err.to_string())?;
    let decoded = cst.decode();
    let zones = Zones::of(&decoded);

    let mut found = Vec::new();
    for component in &decoded.components {
        collect_scheduled(component, &mut found);
    }

    let window = Window { from, until };
    let mut occurrences = Vec::new();
    for &(component, kind) in &found {
        let uid = text_of(component, IcalPropKind::Uid);
        let mut overrides = Vec::new();
        let mut series = false;
        for (other, _) in &found {
            if uid.is_none() || text_of(other, IcalPropKind::Uid) != uid {
                continue;
            }
            match has(other, IcalPropKind::RecurrenceId) {
                true => overrides.push(*other),
                false => series = true,
            }
        }

        // NOTE: an override is placed by the walk of the series it
        // belongs to. One whose series the object does not hold (an
        // invitation to a single instance) is all there is of it, and
        // stands alone.
        if has(component, IcalPropKind::RecurrenceId) {
            if !series {
                window.place(component, kind, &[], &zones, &mut occurrences);
            }
            continue;
        }

        window.place(component, kind, &overrides, &zones, &mut occurrences);
    }

    occurrences.sort_by(|left, right| left.start.time.cmp(&right.start.time));
    Ok(occurrences)
}

/// The civil span an expansion keeps the starts of.
#[derive(Clone, Copy)]
struct Window {
    from: IcalRecurDateTime,
    until: IcalRecurDateTime,
}

impl Window {
    fn holds(&self, moment: IcalRecurDateTime) -> bool {
        moment >= self.from && moment < self.until
    }

    /// Places one series, or one lone component, on the window.
    fn place(
        &self,
        component: &IcalComponent,
        kind: IcalComponentKind,
        overrides: &[&IcalComponent],
        zones: &Zones,
        out: &mut Vec<Occurrence>,
    ) {
        let Some(start) = start_of(component, kind) else {
            return;
        };
        let Some(first) = start.civil() else {
            return;
        };

        // The length, carried forward onto every occurrence: RFC 5545
        // recurs the start and keeps the duration, so only the start
        // needs expanding.
        let end = end_of(component, kind).filter(|end| end.civil().is_some());
        let length = end
            .as_ref()
            .and_then(EventTime::civil)
            .map(|end| (end.seconds() - first.seconds()).max(0))
            .unwrap_or(0);

        let rendered = |source: &IcalComponent,
                        start: EventTime,
                        end: EventTime,
                        id: Option<EventTime>| Occurrence {
            component: kind.to_string(),
            all_day: start.kind == EventTimeKind::Date,
            start: zones.resolve(start),
            end: zones.resolve(end),
            recurrence_id: id.map(|id| zones.resolve(id)),
            summary: text_of(source, IcalPropKind::Summary).unwrap_or_default(),
            location: text_of(source, IcalPropKind::Location).unwrap_or_default(),
        };

        let (set, replaced) = series::set_of(component, &start, overrides, zones);
        if set.rules.is_empty() && set.dates.is_empty() && replaced.is_empty() {
            if self.holds(first) {
                let end = end.unwrap_or_else(|| start.clone());
                out.push(rendered(component, start, end, None));
            }
            return;
        }

        // NOTE: an override can move an instance into the window from
        // past it, so the walk runs on to the last identity such a move
        // comes from.
        let cutoff = set
            .overrides
            .iter()
            .filter(|over| over.start < self.until)
            .map(|over| IcalRecurDateTime::from_seconds(over.id.seconds() + 1))
            .fold(self.until, IcalRecurDateTime::max);

        let walk = match zones.find(&start.tzid) {
            Some(zone) if start.kind == EventTimeKind::Zoned => set.expand_in_zone(zone),
            _ => set.expand(),
        };

        let mut placed = 0;
        for occurrence in walk.take_while(|occurrence| occurrence.id < cutoff) {
            if !self.holds(occurrence.start) {
                continue;
            }

            let id = Some(start.at(occurrence.id));
            let over = occurrence.over.and_then(|index| {
                let id = set.overrides[index].id;
                replaced.iter().find(|(other, _)| *other == id)
            });

            match over {
                Some((_, over)) => {
                    if text_of(over, IcalPropKind::Status)
                        .is_some_and(|status| status.eq_ignore_ascii_case("CANCELLED"))
                    {
                        continue;
                    }
                    let Some(moved) = start_of(over, kind) else {
                        continue;
                    };
                    let finish = end_of(over, kind).unwrap_or_else(|| {
                        moved.at(IcalRecurDateTime::from_seconds(
                            occurrence.start.seconds() + length,
                        ))
                    });
                    out.push(rendered(over, moved, finish, id));
                }
                None => {
                    let shift = occurrence.start.seconds() - first.seconds();
                    let finish = match (&end, end.as_ref().and_then(EventTime::civil)) {
                        (Some(end), Some(civil)) => {
                            end.at(IcalRecurDateTime::from_seconds(civil.seconds() + shift))
                        }
                        _ => start.at(occurrence.start),
                    };
                    out.push(rendered(component, start.at(occurrence.start), finish, id));
                }
            }

            placed += 1;
            if placed == MAX_OCCURRENCES {
                break;
            }
        }
    }
}

/// The time a component's row is placed at.
///
/// A to-do need not carry a DTSTART (RFC 5545 3.6.2 makes both dates
/// optional), and one that carries only a DUE belongs on the day it is
/// due: that is the date a person looks for, and dropping the to-do
/// because the other property is absent would hide it entirely.
fn start_of(component: &IcalComponent, kind: IcalComponentKind) -> Option<EventTime> {
    match kind {
        IcalComponentKind::VTodo => time_of(component, IcalPropKind::DtStart)
            .or_else(|| time_of(component, IcalPropKind::Due)),
        _ => time_of(component, IcalPropKind::DtStart),
    }
}

/// The time a component's row ends at, when it has one.
///
/// A journal entry never does (RFC 5545 3.6.3 gives it no end), and a
/// to-do's DUE is an end only when a DTSTART placed the row somewhere
/// else; otherwise DUE is the start and the row has no length.
fn end_of(component: &IcalComponent, kind: IcalComponentKind) -> Option<EventTime> {
    match kind {
        IcalComponentKind::VEvent => time_of(component, IcalPropKind::DtEnd),
        IcalComponentKind::VTodo => time_of(component, IcalPropKind::DtStart)
            .and_then(|_| time_of(component, IcalPropKind::Due)),
        _ => None,
    }
}

fn is_kind(name: &IcalComponentName, kind: IcalComponentKind) -> bool {
    matches!(name, IcalComponentName::Kind(found) if *found == kind)
}

/// Whether a component carries a property.
fn has(component: &IcalComponent, kind: IcalPropKind) -> bool {
    props(component, kind).next().is_some()
}

/// Every property of one kind a component carries.
fn props<'c>(
    component: &'c IcalComponent<'c>,
    kind: IcalPropKind,
) -> impl Iterator<Item = &'c IcalProp<'c>> {
    component
        .props
        .iter()
        .filter(move |prop| matches!(&prop.name, IcalPropName::Kind(found) if *found == kind))
}

/// The first date property of one kind, with its zone.
fn time_of(component: &IcalComponent, kind: IcalPropKind) -> Option<EventTime> {
    props(component, kind).next().and_then(EventTime::of_prop)
}

/// The raw text of a property's value, whatever value type it decoded to.
fn text_of(component: &IcalComponent, kind: IcalPropKind) -> Option<String> {
    props(component, kind).next().and_then(raw_value)
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

    pub(super) fn object(body: &str) -> String {
        format!("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n{body}END:VCALENDAR\r\n")
    }

    /// A zone defined by the object alone, under a name no database
    /// knows: the rules of Paris since 1996.
    pub(super) const ROMANCE: &str = "BEGIN:VTIMEZONE\r\nTZID:/example.org/Romance\r\n\
         BEGIN:DAYLIGHT\r\nDTSTART:19810329T020000\r\nTZOFFSETFROM:+0100\r\n\
         TZOFFSETTO:+0200\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\n\
         BEGIN:STANDARD\r\nDTSTART:19961027T030000\r\nTZOFFSETFROM:+0200\r\n\
         TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nEND:STANDARD\r\n\
         END:VTIMEZONE\r\n";

    fn starts(found: &[Occurrence]) -> Vec<&str> {
        found.iter().map(|one| one.start.time.as_str()).collect()
    }

    #[test]
    fn expands_a_weekly_event_inside_the_window() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:1\r\nDTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n\
             SUMMARY:Standup\r\nRRULE:FREQ=WEEKLY;BYDAY=MO\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(
            starts(&found),
            [
                "20260105T090000",
                "20260112T090000",
                "20260119T090000",
                "20260126T090000"
            ]
        );
        assert_eq!(found[0].component, "VEVENT");
        assert_eq!(found[0].summary, "Standup");
        assert_eq!(found[0].end.time, "20260105T100000");
        assert_eq!(found[0].start.kind, EventTimeKind::Floating);
        assert!(!found[0].all_day);
        // Each one names the instance it is, which is what an edit of it
        // alone is written against.
        assert_eq!(
            found[1].recurrence_id.as_ref().unwrap().time,
            "20260112T090000"
        );
    }

    #[test]
    fn an_entry_that_does_not_recur_names_no_instance() {
        let ical = object("BEGIN:VEVENT\r\nUID:1\r\nDTSTART:20260105T090000Z\r\nEND:VEVENT\r\n");

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert!(found[0].recurrence_id.is_none());
        // The start keeps its Z as what it is relative to, the time
        // itself staying civil.
        assert_eq!(found[0].start.time, "20260105T090000");
        assert_eq!(found[0].start.kind, EventTimeKind::Utc);
    }

    #[test]
    fn places_a_todo_at_its_due_when_it_has_no_start() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:7\r\nDUE:20260115T170000\r\nSUMMARY:File taxes\r\nEND:VTODO\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].component, "VTODO");
        assert_eq!(found[0].start.time, "20260115T170000");
        // The due date placed the row, so it cannot also end it: a
        // zero-length to-do is one moment, not one that runs to itself.
        assert_eq!(found[0].end.time, "20260115T170000");
    }

    #[test]
    fn runs_a_todo_from_its_start_to_its_due() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:8\r\nDTSTART:20260115T090000\r\nDUE:20260115T170000\r\n\
             END:VTODO\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(found[0].start.time, "20260115T090000");
        assert_eq!(found[0].end.time, "20260115T170000");
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
        assert_eq!(found[0].start.kind, EventTimeKind::Date);
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
        assert_eq!(found[0].end.time, "20260201T003000");
        assert_eq!(found[1].start.time, "20260201T230000");
        assert_eq!(found[1].end.time, "20260202T003000");
    }

    #[test]
    fn windows_out_what_falls_outside() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:3\r\nDTSTART:20260105T090000\r\n\
             RRULE:FREQ=WEEKLY;BYDAY=MO\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260113T000000", "20260121T000000").unwrap();

        assert_eq!(starts(&found), ["20260119T090000"]);
    }

    #[test]
    fn reads_a_single_all_day_event() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:4\r\nDTSTART;VALUE=DATE:20260214\r\n\
             SUMMARY:Holiday\r\nLOCATION:Home\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260201", "20260301").unwrap();

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].start.time, "20260214");
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

        assert_eq!(starts(&found), ["20260110T090000", "20260120T090000"]);
    }

    #[test]
    fn an_exdate_takes_its_occurrence_out() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260105T090000\r\n\
             RRULE:FREQ=WEEKLY;COUNT=4\r\nEXDATE:20260112T090000\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(
            starts(&found),
            ["20260105T090000", "20260119T090000", "20260126T090000"]
        );
    }

    #[test]
    fn a_moved_occurrence_shows_once_at_its_new_time() {
        // The delta's scenario: a weekly series whose Tuesday occurrence
        // an override moved to Wednesday.
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260106T090000\r\nDTEND:20260106T100000\r\n\
             SUMMARY:Sync\r\nRRULE:FREQ=WEEKLY;BYDAY=TU\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260113T090000\r\n\
             DTSTART:20260114T110000\r\nDTEND:20260114T120000\r\nSUMMARY:Sync, moved\r\n\
             END:VEVENT\r\n",
        );

        let found = expand(&ical, "20260112T000000", "20260119T000000").unwrap();

        assert_eq!(found.len(), 1);
        assert_eq!(found[0].start.time, "20260114T110000");
        assert_eq!(found[0].end.time, "20260114T120000");
        assert_eq!(found[0].summary, "Sync, moved");
        // Still the Tuesday instance, which is what an edit of it names.
        assert_eq!(
            found[0].recurrence_id.as_ref().unwrap().time,
            "20260113T090000"
        );
    }

    #[test]
    fn an_occurrence_moved_into_the_window_from_past_it_shows() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260106T090000\r\n\
             RRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260120T090000\r\n\
             DTSTART:20260115T090000\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260112T000000", "20260119T000000").unwrap();

        assert_eq!(starts(&found), ["20260113T090000", "20260115T090000"]);
    }

    #[test]
    fn a_cancelled_override_takes_its_occurrence_out() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260105T090000\r\n\
             RRULE:FREQ=WEEKLY;COUNT=3\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260112T090000\r\n\
             DTSTART:20260112T090000\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(starts(&found), ["20260105T090000", "20260119T090000"]);
    }

    #[test]
    fn a_this_and_future_override_moves_every_later_occurrence() {
        // Written by another client: from the third Monday on, an hour
        // later.
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260105T090000\r\n\
             RRULE:FREQ=WEEKLY;COUNT=4\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID;RANGE=THISANDFUTURE:20260119T090000\r\n\
             DTSTART:20260119T100000\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(
            starts(&found),
            [
                "20260105T090000",
                "20260112T090000",
                "20260119T100000",
                "20260126T100000"
            ]
        );
    }

    #[test]
    fn a_zone_only_the_object_defines_is_resolved_from_it() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\nDTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             DTEND;TZID=/example.org/Romance:20260706T100000\r\n\
             RRULE:FREQ=MONTHLY;COUNT=6\r\nEND:VEVENT\r\n"
        ));

        let found = expand(&ical, "20260701T000000", "20261201T000000").unwrap();

        // Summer time, then winter time from the last Sunday of October:
        // the zone's own rules, with no database behind them.
        assert_eq!(found[0].start.kind, EventTimeKind::Zoned);
        assert_eq!(found[0].start.tzid, "/example.org/Romance");
        assert_eq!(found[0].start.offset, Some(7200));
        assert_eq!(found[0].end.offset, Some(7200));
        assert_eq!(found[4].start.time, "20261106T090000");
        assert_eq!(found[4].start.offset, Some(3600));
    }

    #[test]
    fn a_time_the_clock_skips_takes_the_offset_before_the_gap() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\nDTSTART;TZID=/example.org/Romance:20260329T023000\r\n\
             END:VEVENT\r\nBEGIN:VEVENT\r\nUID:y\r\n\
             DTSTART;TZID=/example.org/Romance:20261025T023000\r\nEND:VEVENT\r\n"
        ));

        let found = expand(&ical, "20260301T000000", "20261101T000000").unwrap();

        // RFC 5545 3.3.5: a skipped time is read at the offset before
        // the gap, a repeated one at its first occurrence.
        assert_eq!(found[0].start.offset, Some(3600));
        assert_eq!(found[1].start.offset, Some(7200));
    }

    #[test]
    fn a_utc_until_against_a_zoned_start_keeps_its_last_occurrence() {
        // RFC 5545 3.3.10 has a zoned series end on a UTC time: 07:00Z is
        // 09:00 in Paris in summer, which a comparison of the two as
        // written would read as two hours too early.
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\nDTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             RRULE:FREQ=DAILY;UNTIL=20260708T070000Z\r\nEND:VEVENT\r\n"
        ));

        let found = expand(&ical, "20260701T000000", "20260801T000000").unwrap();

        assert_eq!(
            starts(&found),
            ["20260706T090000", "20260707T090000", "20260708T090000"]
        );
    }

    #[test]
    fn an_exdate_in_utc_against_a_zoned_start_still_takes_it_out() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\nDTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             RRULE:FREQ=DAILY;COUNT=3\r\nEXDATE:20260707T070000Z\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:z\r\nRECURRENCE-ID:20260708T070000Z\r\n\
             DTSTART;TZID=/example.org/Romance:20260708T150000\r\nEND:VEVENT\r\n"
        ));

        let found = expand(&ical, "20260701T000000", "20260801T000000").unwrap();

        // The override is written in UTC too, and still replaces the
        // instance it names rather than adding a second one.
        assert_eq!(starts(&found), ["20260706T090000", "20260708T150000"]);
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
        // The rule is the series', since an override repeats nothing.
        assert_eq!(moved.recurrence, "FREQ=WEEKLY");
        // And it says it is the override, which a conflict on it is shown on.
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
        // A to-do carrying no DTSTART is placed at its DUE, and a
        // date-only DUE makes the page an all-day one; it still has no
        // start, or an edit would write one.
        assert!(detail.start.is_none());
        assert!(detail.all_day);
    }

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
        // Cleared, so the property goes rather than being written empty.
        assert!(!written.contains("LOCATION"));
        // A client that rewrites what it does not understand loses other
        // clients' data, so none of this may move.
        assert!(written.contains("X-VENDOR-FLAG:keep me"));
        assert!(written.contains("BEGIN:VALARM"));
        assert!(written.contains("TRIGGER:-PT10M"));
        assert!(written.contains("UID:42"));
        assert!(written.contains("DTSTART:20260105T090000"));
        assert!(written.contains("LAST-MODIFIED:20260809T150000Z"));
        // RFC 5545 3.6.1 lists an event's properties before its alarms.
        assert!(written.find("SUMMARY").unwrap() < written.find("BEGIN:VALARM").unwrap());
        assert!(written.find("DTSTAMP").unwrap() < written.find("BEGIN:VALARM").unwrap());

        // The result is a calendar object the reader can read back.
        let detail = read(&written, "").unwrap();
        assert_eq!(detail.summary, "Daily standup");
        assert_eq!(detail.location, "");
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
        // Without VALUE=DATE a bare YYYYMMDD is not a legal DATE-TIME and
        // servers reject the object.
        assert!(written.contains("DTSTART;VALUE=DATE:20260105"));
        assert!(read(&written, "").unwrap().all_day);

        let edit = serde_json::json!({
            "start": {"time": "20260105T090000", "kind": "zoned", "tzid": "/example.org/Romance"},
            "vtimezone": ROMANCE,
        });
        let zoned = write(&written, &edit.to_string()).unwrap();
        assert!(zoned.contains("DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"));
        // RFC 5545 3.2.19: a TZID owes its definition, which the edit
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
        // The comma is data, not a separator, so it escapes on the wire.
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
    fn reading_an_object_with_nothing_scheduled_fails() {
        let ical = object("BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\nEND:VTIMEZONE\r\n");

        assert!(read(&ical, "").is_err());
    }

    #[test]
    fn a_new_object_is_one_the_page_can_open_and_edit() {
        let created = create(
            "VEVENT",
            "abc-123",
            "20260105T080000Z",
            "20260105T090000",
            "",
            "",
        )
        .unwrap();

        // What a new entry has to be is readable by the page that opens
        // it and placed on a day by the agenda that lists it, which is
        // the whole reason it carries a DTSTART it did not have to.
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

        // And an edit patches it like any other object.
        let written = write(&created, r#"{"summary":"Dentist"}"#).unwrap();
        assert_eq!(read(&written, "").unwrap().summary, "Dentist");
    }

    #[test]
    fn a_new_object_starts_in_its_zone_with_the_definition_beside_it() {
        let created = create(
            "VEVENT",
            "abc-123",
            "20260105T080000Z",
            "20260706T090000",
            "/example.org/Romance",
            ROMANCE,
        )
        .unwrap();

        assert!(created.contains("DTSTART;TZID=/example.org/Romance:20260706T090000\r\n"));
        let found = expand(&created, "20260701T000000", "20260801T000000").unwrap();
        assert_eq!(found[0].start.offset, Some(7200));

        // A zone nobody defined is one no reader can place.
        assert!(
            create(
                "VEVENT",
                "1",
                "20260105T080000Z",
                "20260706T090000",
                "Europe/Paris",
                ""
            )
            .is_err()
        );
        // A date is in no zone, so it needs no definition.
        let day = create(
            "VEVENT",
            "1",
            "20260105T080000Z",
            "20260706",
            "Europe/Paris",
            ROMANCE,
        )
        .unwrap();
        assert!(day.contains("DTSTART;VALUE=DATE:20260706\r\n"));
        assert!(!day.contains("VTIMEZONE"));
    }

    #[test]
    fn only_the_three_scheduled_components_can_be_created() {
        let new = |component, start| create(component, "1", "20260105T080000Z", start, "", "");

        assert!(new("VTODO", "20260105T090000").is_ok());
        assert!(new("VJOURNAL", "20260105").is_ok());
        assert!(new("VTIMEZONE", "20260105T090000").is_err());
    }
}
