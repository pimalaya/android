//! A recurring entry as one series: the set its occurrences come out
//! of, and the three ways one of them is changed on its own.
//!
//! One occurrence is an override (RFC 5545 3.8.4.4) when it is edited
//! and an `EXDATE` (3.8.5.1) when it is deleted, the shapes every server
//! reads. This and following is a split rather than a `RANGE`
//! `THISANDFUTURE` override, which many servers refuse: the series ends
//! with an `UNTIL` before the occurrence, and a new object with a fresh
//! `UID` carries the rest of the rule from it, its overrides and its
//! `EXDATE`s along.

use ical::{
    component::IcalComponent,
    prop::IcalPropKind,
    recur::{
        IcalRecurDateTime, IcalRecurFreq, IcalRecurRule,
        expand::IcalRecurExpand,
        set::{IcalRecurOverride, IcalRecurSet},
    },
    tree::{
        codec::mode::Escaper,
        cst::{IcalCst, IcalItem},
        line::IcalLine,
        value::cursor::IcalValueCursor,
    },
    value::{IcalValue, recur::IcalRecur},
};

use crate::types::BridgeError;

use super::{
    DateEdit, EventEdit, EventScope, EventTime, EventTimeKind, Zones, child, child_mut, date_prop,
    define_zone, insert, line, line_mut, patch, prop, props, remove_named, scheduled, text, touch,
    zone::stamp,
};

/// Seconds in a day.
const DAY: i64 = 86_400;

/// The recurrence set of one series and the overrides it holds, each
/// by the identity it replaces.
///
/// `IcalRecurSet` reads every date as the civil time it spells, which
/// is right while they share a zone and wrong when they do not: a `UTC`
/// `UNTIL` against a start in Paris, as RFC 5545 3.3.10 requires it to
/// be written, ends the series at the wrong hour, and an `EXDATE` or a
/// `RECURRENCE-ID` written in another zone names no instance at all.
/// Each is told in the series' zone here first, through the zones the
/// object defines.
pub(super) fn set_of<'c>(
    master: &IcalComponent,
    start: &EventTime,
    overrides: &[&'c IcalComponent<'c>],
    zones: &Zones,
) -> (
    IcalRecurSet,
    Vec<(IcalRecurDateTime, &'c IcalComponent<'c>)>,
) {
    let mut set = IcalRecurSet::of_component(master);
    // NOTE: a to-do placed by its DUE has no DTSTART for the set to read.
    set.start = start.civil();

    if start.kind == EventTimeKind::Zoned {
        let utc = EventTime {
            kind: EventTimeKind::Utc,
            ..start.clone()
        };
        set.rules = props(master, IcalPropKind::RRule)
            .filter_map(|prop| {
                let IcalValue::Recur(raw) = &prop.value else {
                    return None;
                };
                let mut rule = IcalRecurRule::parse(&raw.0).ok()?;
                if let Some(until) = rule.until
                    && utc_until(&raw.0)
                {
                    rule.until = Some(zones.convert(until, &utc, start));
                }
                Some(rule)
            })
            .collect();
    }

    set.dates = dates_of(master, IcalPropKind::RDate, start, zones);
    set.exdates = dates_of(master, IcalPropKind::ExDate, start, zones);

    let mut replaced = Vec::new();
    for over in overrides {
        let Some(raw) = props(over, IcalPropKind::RecurrenceId)
            .next()
            .and_then(EventTime::of_prop)
        else {
            continue;
        };
        let Some(civil) = raw.civil() else {
            continue;
        };

        let mut one = IcalRecurSet::default();
        one.with_override(over);
        let Some(entry) = one.overrides.pop() else {
            continue;
        };

        let id = zones.convert(civil, &raw, start);
        set.overrides.push(IcalRecurOverride { id, ..entry });
        replaced.push((id, *over));
    }
    set.overrides.sort_unstable_by_key(|over| over.id);

    (set, replaced)
}

/// Whether a raw rule's `UNTIL` is a UTC time.
fn utc_until(rule: &str) -> bool {
    rule.split(';').any(|part| {
        part.split_once('=').is_some_and(|(name, value)| {
            name.trim().eq_ignore_ascii_case("UNTIL") && value.trim().ends_with(['Z', 'z'])
        })
    })
}

/// Every date of the `RDATE`s or `EXDATE`s of a component, told in the
/// series' zone. A period contributes its start.
fn dates_of(
    component: &IcalComponent,
    kind: IcalPropKind,
    start: &EventTime,
    zones: &Zones,
) -> Vec<IcalRecurDateTime> {
    let mut dates = Vec::new();

    for prop in props(component, kind) {
        let Some(zone) = EventTime::of_prop(prop) else {
            continue;
        };
        let values: Vec<&str> = match &prop.value {
            IcalValue::DateTimeList(values) => {
                values.0.iter().map(|value| value.as_ref()).collect()
            }
            IcalValue::DateTime(value) => vec![value.0.as_ref()],
            IcalValue::Date(value) => vec![value.0.as_ref()],
            _ => continue,
        };

        for value in values {
            let value = value.split('/').next().unwrap_or(value);
            if let Ok(civil) = IcalRecurDateTime::parse(value) {
                dates.push(zones.convert(civil, &zone, start));
            }
        }
    }

    dates.sort_unstable();
    dates.dedup();
    dates
}

/// One series located in an object's syntax tree.
struct Series {
    /// Where its own component sits among the calendar's items.
    master: usize,
    /// The property its start is read from: `DTSTART`, or the `DUE` of a
    /// to-do carrying none.
    anchor: &'static str,
    start: EventTime,
    zones: Zones,
    /// Every override of it, by where it sits and the identity it
    /// replaces, in the series' zone.
    overrides: Vec<(usize, IcalRecurDateTime)>,
}

impl Series {
    fn of(cst: &IcalCst<'static>) -> Result<Self, BridgeError> {
        let zones = Zones::of_cst(cst);
        let components = scheduled(cst);

        let master = components
            .iter()
            .copied()
            .find(|index| line(child(cst, *index), "RECURRENCE-ID").is_none())
            .or(components.first().copied())
            .ok_or("The object holds nothing to edit")?;
        let component = child(cst, master);

        let (anchor, start) = ["DTSTART", "DUE"]
            .into_iter()
            .find_map(|name| Some((name, EventTime::of_line(line(component, name)?)?)))
            .ok_or("The series has no start")?;
        let uid = line(component, "UID").map(|line| line.value.decode().into_owned());

        let overrides = components
            .into_iter()
            .filter(|index| *index != master)
            .filter_map(|index| {
                let over = child(cst, index);
                if line(over, "UID").map(|line| line.value.decode().into_owned()) != uid {
                    return None;
                }
                let raw = EventTime::of_line(line(over, "RECURRENCE-ID")?)?;
                Some((index, zones.convert(raw.civil()?, &raw, &start)))
            })
            .collect();

        Ok(Self {
            master,
            anchor,
            start,
            zones,
            overrides,
        })
    }

    fn first(&self) -> Result<IcalRecurDateTime, BridgeError> {
        self.start
            .civil()
            .ok_or_else(|| "The series' start is unreadable".into())
    }

    /// Where the override of one instance sits, when there is one.
    fn replaced(&self, id: IcalRecurDateTime) -> Option<usize> {
        self.overrides
            .iter()
            .find(|(_, over)| *over == id)
            .map(|(index, _)| *index)
    }

    /// The dates the occurrence at `id` has before an edit: its
    /// override's, or the series' own carried to it.
    fn placed(
        &self,
        cst: &IcalCst<'static>,
        id: IcalRecurDateTime,
        name: &str,
    ) -> Option<IcalRecurDateTime> {
        if let Some(index) = self.replaced(id) {
            return EventTime::of_line(line(child(cst, index), name)?)?.civil();
        }

        let shift = id.seconds() - self.first().ok()?.seconds();
        let own = EventTime::of_line(line(child(cst, self.master), name)?)?.civil()?;
        Some(IcalRecurDateTime::from_seconds(own.seconds() + shift))
    }

    /// An edit made on the occurrence at `id`, told relative to the
    /// series component `base` instead.
    ///
    /// The page shows and edits that occurrence's dates, while what is
    /// written is the series' own: each date moves by as many days as
    /// the occurrence's did and takes the time of day it was given, so
    /// moving next Tuesday's meeting an hour later for all occurrences
    /// moves the series an hour later rather than to next Tuesday.
    fn rebased(
        &self,
        cst: &IcalCst<'static>,
        base: &IcalCst<'static>,
        id: IcalRecurDateTime,
        edit: &EventEdit,
    ) -> EventEdit {
        let rebase = |date: &Option<DateEdit>, name: &str| -> Option<DateEdit> {
            let date = date.as_ref()?;
            let moved = || {
                let edited = IcalRecurDateTime::parse(&date.time).ok()?;
                let before = self.placed(cst, id, name)?;
                let own = EventTime::of_line(line(base, name)?)?.civil()?;

                let days = day_of(edited) - day_of(before);
                let mut day = IcalRecurDateTime::from_seconds(day_of(own) * DAY + days * DAY);
                (day.hour, day.minute, day.second) = (edited.hour, edited.minute, edited.second);
                Some(stamp(day, date.time.len() == 8))
            };

            Some(match moved() {
                Some(time) => DateEdit {
                    time,
                    ..date.clone()
                },
                None => date.clone(),
            })
        };

        EventEdit {
            start: rebase(&edit.start, "DTSTART"),
            end: rebase(&edit.end, "DTEND"),
            due: rebase(&edit.due, "DUE"),
            ..edit.clone()
        }
    }

    /// A new override of the instance at `id`: the series' component
    /// carried to it, with nothing that repeats.
    fn instance(&self, cst: &IcalCst<'static>, id: IcalRecurDateTime) -> IcalCst<'static> {
        let mut over = child(cst, self.master).clone();

        for name in ["RRULE", "RDATE", "EXDATE", "EXRULE"] {
            remove_named(&mut over, name);
        }
        if let Ok(first) = self.first() {
            let shift = id.seconds() - first.seconds();
            for name in ["DTSTART", "DTEND", "DUE"] {
                self.shift(&mut over, name, shift);
            }
        }

        let identity = date_prop(IcalPropKind::RecurrenceId, &self.start.at(id));
        insert(&mut over, identity);
        over
    }

    /// Moves one date property of a component by some seconds.
    fn shift(&self, component: &mut IcalCst<'static>, name: &str, seconds: i64) {
        let Some(line) = line_mut(component, name) else {
            return;
        };
        let Some(time) = EventTime::of_line(line) else {
            return;
        };
        let Some(civil) = time.civil() else {
            return;
        };

        let moved = time.at(IcalRecurDateTime::from_seconds(civil.seconds() + seconds));
        IcalValueCursor { line }.set_bytes(moved.wire());
    }

    /// Keeps the `RDATE` or `EXDATE` values of a component that `keep`
    /// accepts, by their date in the series' zone, moved by some
    /// seconds; a line left with none goes.
    fn keep_dates(
        &self,
        component: &mut IcalCst<'static>,
        name: &str,
        keep: impl Fn(IcalRecurDateTime) -> bool,
        seconds: i64,
    ) {
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
                .filter_map(|value| {
                    // NOTE: a period moves by its start alone; its length
                    // stays whatever it was.
                    let (date, rest) = match value.split_once('/') {
                        Some((date, rest)) => (date, Some(rest)),
                        None => (value.as_ref(), None),
                    };
                    let civil = IcalRecurDateTime::parse(date).ok()?;
                    if !keep(self.zones.convert(civil, &zone, &self.start)) {
                        return None;
                    }

                    let moved = zone
                        .at(IcalRecurDateTime::from_seconds(civil.seconds() + seconds))
                        .wire();
                    Some(match rest {
                        Some(rest) => format!("{moved}/{rest}"),
                        None => moved,
                    })
                })
                .collect();

            if kept.is_empty() {
                return false;
            }
            cursor.set_list(&kept);
            true
        });
    }

    /// Ends the series before the instance at `id`: its rules gain an
    /// `UNTIL` just before it, a `COUNT` becoming one, and its dates and
    /// overrides from it on go.
    fn end_before(
        &self,
        cst: &mut IcalCst<'static>,
        id: IcalRecurDateTime,
        edit: &EventEdit,
    ) -> Result<(), BridgeError> {
        let utc = edit
            .recurrence_instant
            .as_deref()
            .and_then(|instant| IcalRecurDateTime::parse(instant).ok())
            .map(|instant| instant.seconds())
            .or_else(|| self.zones.instant(&self.start, id));
        let until = until_of(&self.start, id, utc);
        let bound = IcalRecurDateTime::parse(&until).ok();
        let mut counts = self.remaining(cst, id).into_iter();

        let master = child_mut(cst, self.master);
        rewrite_rules(master, |rule| {
            // NOTE: a rule already ending sooner keeps its end, its UNTIL
            // or a COUNT spent before the cut, or the cut would bring back
            // instances it never had.
            let own = IcalRecurRule::parse(rule).ok().and_then(|rule| rule.until);
            let spent = counts.next().flatten() == Some(0);
            match (own, bound) {
                _ if spent => None,
                (Some(own), Some(bound)) if own <= bound => None,
                _ => Some((Some(until.clone()), None)),
            }
        });
        for name in ["RDATE", "EXDATE"] {
            self.keep_dates(master, name, |date| date < id, 0);
        }
        touch(master, edit);

        let mut gone: Vec<usize> = self
            .overrides
            .iter()
            .filter(|(_, over)| *over >= id)
            .map(|(index, _)| *index)
            .collect();
        gone.sort_unstable();
        for index in gone.into_iter().rev() {
            cst.items.remove(index);
        }

        Ok(())
    }

    /// Turns a copy of the object into the series starting at the
    /// instance at `id`: a fresh `UID`, the start carried there, what is
    /// left of a `COUNT`, the dates and overrides from it on, and the
    /// edit, which moves every one of them by as much as it moves the
    /// start.
    fn begin_at(
        &self,
        cst: &mut IcalCst<'static>,
        id: IcalRecurDateTime,
        edit: &EventEdit,
    ) -> Result<(), BridgeError> {
        let uid = edit
            .uid
            .as_deref()
            .filter(|uid| !uid.is_empty())
            .ok_or("A split needs the new series' UID")?;
        let first = self.first()?;
        let original = cst.clone();
        let counts = self.remaining(cst, id);

        let mut master = child(cst, self.master).clone();
        if let Some(line) = line_mut(&mut master, "UID") {
            IcalValueCursor { line }.set_text(uid);
        }
        for name in ["DTSTART", "DTEND", "DUE"] {
            self.shift(&mut master, name, id.seconds() - first.seconds());
        }
        // NOTE: a rule whose count the earlier instances spent repeats
        // nothing from here, and COUNT=0 is no rule: the new series is
        // its start and whatever dates it keeps.
        let mut spent = counts.iter();
        master.items.retain(|item| match item {
            IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case("RRULE") => {
                spent.next() != Some(&Some(0))
            }
            _ => true,
        });
        let mut counts = counts.into_iter().filter(|count| *count != Some(0));
        rewrite_rules(&mut master, |_| Some((None, counts.next().flatten())));
        for name in ["RDATE", "EXDATE"] {
            self.keep_dates(&mut master, name, |date| date >= id, 0);
        }

        let rebased = self.rebased(&original, &master, id, edit);
        patch(&mut master, &rebased);
        follow_rules(&mut master, self.anchor, id)?;

        let moved = line(&master, self.anchor)
            .and_then(EventTime::of_line)
            .and_then(|time| time.civil())
            .map(|start| start.seconds() - id.seconds())
            .unwrap_or(0);
        for name in ["RDATE", "EXDATE"] {
            self.keep_dates(&mut master, name, |_| true, moved);
        }
        rewrite_rules(&mut master, |rule| {
            if moved == 0 {
                return None;
            }
            // NOTE: what an EXDATE moved by an edit can no longer reach is
            // the rule's own end, which moves with the start the same way.
            let until = IcalRecurRule::parse(rule).ok()?.until?;
            let until = IcalRecurDateTime::from_seconds(until.seconds() + moved);
            Some((Some(with_suffix(rule, until)), None))
        });
        *child_mut(cst, self.master) = master;

        let mut gone = Vec::new();
        for (index, over) in &self.overrides {
            if *over < id {
                gone.push(*index);
                continue;
            }
            let component = child_mut(cst, *index);
            if let Some(line) = line_mut(component, "UID") {
                IcalValueCursor { line }.set_text(uid);
            }
            self.shift(component, "RECURRENCE-ID", moved);
            if *over == id {
                patch(component, edit);
            }
        }
        gone.sort_unstable();
        for index in gone.into_iter().rev() {
            cst.items.remove(index);
        }

        Ok(())
    }

    /// What is left of each rule's `COUNT` from the instance at `id` on,
    /// rule by rule: RFC 5545 counts every instance the rule generates,
    /// an excluded one included.
    fn remaining(&self, cst: &IcalCst<'static>, id: IcalRecurDateTime) -> Vec<Option<u32>> {
        let Ok(first) = self.first() else {
            return Vec::new();
        };
        let zone = self
            .zones
            .find(&self.start.tzid)
            .filter(|_| self.start.kind == EventTimeKind::Zoned);

        child(cst, self.master)
            .items
            .iter()
            .filter_map(|item| match item {
                IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case("RRULE") => {
                    Some(line.value.decode().into_owned())
                }
                _ => None,
            })
            .map(|raw| {
                let rule = IcalRecurRule::parse(&raw).ok()?;
                let count = rule.count?;
                let mut walk = IcalRecurExpand::new(rule, first);
                if let Some(zone) = zone {
                    walk = walk.in_zone(zone.clone());
                }
                let before = walk
                    .take_while(|moment| *moment < id)
                    .take(count as usize)
                    .count() as u32;
                Some(count - before)
            })
            .collect()
    }
}

/// The whole series, or the entry that does not recur: its own
/// component, patched, the dates rebased onto it when the edit was made
/// on one of its occurrences.
pub(super) fn all(cst: &mut IcalCst<'static>, edit: &EventEdit) -> Result<(), BridgeError> {
    let series = Series::of(cst)?;
    let edit = match identity(edit) {
        Ok(id) => series.rebased(cst, child(cst, series.master), id, edit),
        Err(_) => edit.clone(),
    };

    let master = child_mut(cst, series.master);
    patch(master, &edit);
    follow_rules(master, series.anchor, series.first()?)
}

/// One occurrence, through its override, written first when the object
/// has none.
pub(super) fn this(cst: &mut IcalCst<'static>, edit: &EventEdit) -> Result<(), BridgeError> {
    let series = Series::of(cst)?;
    let id = identity(edit)?;

    let index = match series.replaced(id) {
        Some(index) => index,
        None => {
            let over = series.instance(cst, id);
            // NOTE: after the series' last component, where an override
            // of it is looked for by a reader walking the object in order.
            let at = series
                .overrides
                .iter()
                .map(|(index, _)| *index)
                .chain([series.master])
                .max()
                .unwrap_or(series.master)
                + 1;
            cst.items.insert(at, IcalItem::Component(Box::new(over)));
            at
        }
    };

    patch(child_mut(cst, index), edit);
    Ok(())
}

/// Splits a series at the occurrence an edit names: the series ended
/// before it, and the new series from it on, edited. Answers the two
/// objects, the second absent when the occurrence is the series' first,
/// which makes the edit one of all of it.
pub fn split(ical: &str, edit: &str) -> Result<(String, Option<String>), BridgeError> {
    let edit: EventEdit = serde_json::from_str(edit).map_err(|err| err.to_string())?;
    let mut cst = IcalCst::parse(ical)
        .map_err(|err| err.to_string())?
        .into_static();

    let series = Series::of(&cst)?;
    let id = identity(&edit)?;

    if id <= series.first()? {
        all(&mut cst, &edit)?;
        define_zone(&mut cst, &edit)?;
        return Ok((text(&cst)?, None));
    }

    let mut next = cst.clone();
    series.begin_at(&mut next, id, &edit)?;
    define_zone(&mut next, &edit)?;
    series.end_before(&mut cst, id, &edit)?;

    Ok((text(&cst)?, Some(text(&next)?)))
}

/// Removes the occurrences an edit's scope names from a series: one
/// becomes an `EXDATE` on the series, its override going with it, and
/// this and following ends the series before it. Answers the object
/// left, or none when nothing is: the whole series, or this and
/// following from its first occurrence, removes the entry.
pub fn remove(ical: &str, edit: &str) -> Result<Option<String>, BridgeError> {
    let edit: EventEdit = serde_json::from_str(edit).map_err(|err| err.to_string())?;
    let mut cst = IcalCst::parse(ical)
        .map_err(|err| err.to_string())?
        .into_static();

    let series = Series::of(&cst)?;
    let id = identity(&edit)?;

    match edit.scope {
        EventScope::All => return Ok(None),
        EventScope::Following if id <= series.first()? => return Ok(None),
        EventScope::Following => series.end_before(&mut cst, id, &edit)?,
        EventScope::This => {
            let master = child_mut(&mut cst, series.master);
            insert(
                master,
                date_prop(IcalPropKind::ExDate, &series.start.at(id)),
            );
            touch(master, &edit);
            if let Some(index) = series.replaced(id) {
                cst.items.remove(index);
            }
        }
    }

    Ok(Some(text(&cst)?))
}

/// The identity of the occurrence an edit was made on.
fn identity(edit: &EventEdit) -> Result<IcalRecurDateTime, BridgeError> {
    let id = edit
        .recurrence_id
        .as_deref()
        .ok_or("The edit names no occurrence")?;
    IcalRecurDateTime::parse(id).map_err(|err| err.to_string().into())
}

/// The day a civil moment falls on, counted from the epoch.
fn day_of(moment: IcalRecurDateTime) -> i64 {
    moment.seconds().div_euclid(DAY)
}

/// The `UNTIL` ending a series just before the instance at `id`.
///
/// RFC 5545 3.3.10 makes it a date for a date start, a floating time for
/// a floating one, and a UTC time for anything else. Any second from
/// the last kept instance up to this one bounds the same set; the
/// earlier of this one's instant and its wall time is the choice that
/// still bounds it for a reader comparing `UNTIL` with local times, as a
/// civil expansion does.
fn until_of(start: &EventTime, id: IcalRecurDateTime, utc: Option<i64>) -> String {
    match start.kind {
        EventTimeKind::Date => stamp(IcalRecurDateTime::from_seconds(id.seconds() - DAY), true),
        EventTimeKind::Floating => stamp(IcalRecurDateTime::from_seconds(id.seconds() - 1), false),
        _ => {
            let at = utc.unwrap_or(id.seconds()).min(id.seconds()) - 1;
            format!("{}Z", stamp(IcalRecurDateTime::from_seconds(at), false))
        }
    }
}

/// An `UNTIL` moved, spelled with the suffix the old one had.
fn with_suffix(rule: &str, until: IcalRecurDateTime) -> String {
    let date = rule.split(';').find_map(|part| {
        let (name, value) = part.split_once('=')?;
        name.trim()
            .eq_ignore_ascii_case("UNTIL")
            .then(|| value.trim().to_string())
    });

    match date {
        Some(date) if date.len() == 8 => stamp(until, true),
        Some(date) if date.ends_with(['Z', 'z']) => format!("{}Z", stamp(until, false)),
        _ => stamp(until, false),
    }
}

/// Rewrites every `RRULE` of a component, rule by rule: `bound` answers
/// the `UNTIL` and the `COUNT` that replace the rule's own, or nothing
/// to leave it as it is.
fn rewrite_rules(
    component: &mut IcalCst<'static>,
    mut bound: impl FnMut(&str) -> Option<(Option<String>, Option<u32>)>,
) {
    for item in &mut component.items {
        let IcalItem::Prop(line) = item else {
            continue;
        };
        if !line.name.get().eq_ignore_ascii_case("RRULE") {
            continue;
        }

        let raw = line.value.decode().into_owned();
        let Some((until, count)) = bound(&raw) else {
            continue;
        };
        if until.is_none() && count.is_none() {
            continue;
        }

        let mut parts: Vec<String> = raw
            .split(';')
            .filter(|part| {
                let name = part.split('=').next().unwrap_or("").trim();
                !part.is_empty()
                    && !name.eq_ignore_ascii_case("UNTIL")
                    && !name.eq_ignore_ascii_case("COUNT")
            })
            .map(str::to_string)
            .collect();
        if let Some(until) = until {
            parts.push(format!("UNTIL={until}"));
        }
        if let Some(count) = count {
            parts.push(format!("COUNT={count}"));
        }

        set_rule(line, &parts.join(";"));
    }
}

/// Replaces the value of one `RRULE` line.
fn set_rule(line: &mut IcalLine<'static>, rule: &str) {
    let rule = IcalValue::Recur(IcalRecur(rule.to_string().into()));
    line.value = prop(IcalPropKind::RRule, Vec::new(), rule)
        .encode(Escaper::default())
        .value;
}

/// Moves every `RRULE` of a component with its start, from where it
/// was to where its start line now says (RFC 5545 3.8.5.3: the start
/// should be one of the instances its rule generates).
fn follow_rules(
    component: &mut IcalCst<'static>,
    anchor: &str,
    from: IcalRecurDateTime,
) -> Result<(), BridgeError> {
    let Some(to) = line(component, anchor)
        .and_then(EventTime::of_line)
        .and_then(|time| time.civil())
    else {
        return Ok(());
    };
    if to == from {
        return Ok(());
    }

    for item in &mut component.items {
        let IcalItem::Prop(line) = item else {
            continue;
        };
        if line.name.get().eq_ignore_ascii_case("RRULE") {
            let rule = follow(&line.value.decode(), from, to)?;
            set_rule(line, &rule);
        }
    }
    Ok(())
}

/// RFC 5545's weekday names, Sunday first as `IcalRecurWeekday` counts
/// them.
const WEEKDAYS: [&str; 7] = ["SU", "MO", "TU", "WE", "TH", "FR", "SA"];

/// A rule moved with its start from `from` to `to`.
///
/// What anchors it to a day moves by the days the start moved, where
/// that is well defined: a weekday by the same days, an ordinal weekday
/// or a single day of the month to what the new start is, a day of the
/// month or the year by the same days while it cannot cross the end of
/// a month or a year, a single month or hour to the new start's. Any
/// other is refused rather than written into a rule that no longer
/// recurs where the start was put.
fn follow(
    rule: &str,
    from: IcalRecurDateTime,
    to: IcalRecurDateTime,
) -> Result<String, BridgeError> {
    let refused = || {
        BridgeError::from(format!(
            "The series repeats by `{rule}`, which cannot follow its start to that day"
        ))
    };
    let Ok(parsed) = IcalRecurRule::parse(rule) else {
        return Ok(rule.to_string());
    };

    let days = day_of(to) - day_of(from);
    let monthly = matches!(parsed.freq, IcalRecurFreq::Monthly)
        || matches!(parsed.freq, IcalRecurFreq::Yearly) && !parsed.by_month.is_empty();
    let last_day = i64::from(days_in_month(to.year, to.month));

    let mut parts = Vec::new();
    for part in rule.split(';').filter(|part| !part.is_empty()) {
        let (name, value) = part.split_once('=').unwrap_or((part, ""));
        let numbers = || -> Result<Vec<i64>, BridgeError> {
            value
                .split(',')
                .map(|item| item.trim().parse().map_err(|_| refused()))
                .collect()
        };
        let shifted = |bound: i64| -> Result<String, BridgeError> {
            let moved = numbers()?
                .into_iter()
                .map(|day| {
                    let moved = day + days;
                    let kept = match day > 0 {
                        true => (1..=bound).contains(&moved),
                        false => (-bound..=-1).contains(&moved),
                    };
                    kept.then(|| moved.to_string()).ok_or_else(refused)
                })
                .collect::<Result<Vec<_>, _>>()?;
            Ok(moved.join(","))
        };
        let single = |was: i64, now: i64| -> Result<String, BridgeError> {
            match numbers()?.as_slice() {
                [only] if *only == was => Ok(now.to_string()),
                _ => Err(refused()),
            }
        };

        let value = match name.trim().to_ascii_uppercase().as_str() {
            "BYDAY" if days != 0 => {
                if parsed.by_day.iter().any(|entry| entry.ordinal.is_some()) {
                    if parsed.by_day.len() != 1 || !monthly || !parsed.by_set_pos.is_empty() {
                        return Err(refused());
                    }
                    let ordinal = match parsed.by_day[0].ordinal {
                        Some(ordinal) if ordinal < 0 => -((last_day - i64::from(to.day)) / 7 + 1),
                        _ => (i64::from(to.day) - 1) / 7 + 1,
                    };
                    format!("{ordinal}{}", WEEKDAYS[weekday_of(to)])
                } else {
                    if !parsed.by_set_pos.is_empty() {
                        return Err(refused());
                    }
                    let mut moved: Vec<&str> = Vec::new();
                    for entry in &parsed.by_day {
                        let day = (entry.weekday as i64 + days).rem_euclid(7) as usize;
                        if !moved.contains(&WEEKDAYS[day]) {
                            moved.push(WEEKDAYS[day]);
                        }
                    }
                    moved.join(",")
                }
            }
            "BYMONTHDAY" if days != 0 => match numbers()?.as_slice() {
                [only] if *only < 0 => (i64::from(to.day) - last_day - 1).to_string(),
                [_] => to.day.to_string(),
                _ => shifted(28)?,
            },
            "BYYEARDAY" if days != 0 => shifted(365)?,
            "BYWEEKNO" if days != 0 => return Err(refused()),
            "BYMONTH" if to.month != from.month => {
                single(i64::from(from.month), i64::from(to.month))?
            }
            "BYHOUR" if to.hour != from.hour => single(i64::from(from.hour), i64::from(to.hour))?,
            "BYMINUTE" if to.minute != from.minute => {
                single(i64::from(from.minute), i64::from(to.minute))?
            }
            "BYSECOND" if to.second != from.second => {
                single(i64::from(from.second), i64::from(to.second))?
            }
            _ => value.to_string(),
        };
        parts.push(format!("{name}={value}"));
    }

    Ok(parts.join(";"))
}

/// The weekday a civil moment falls on, Sunday being 0.
fn weekday_of(moment: IcalRecurDateTime) -> usize {
    // NOTE: 1 January 1970 was a Thursday.
    (day_of(moment) + 4).rem_euclid(7) as usize
}

/// The days in a month of the proleptic Gregorian calendar.
fn days_in_month(year: i32, month: u8) -> u8 {
    match month {
        2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::{
        super::{
            expand, read,
            tests::{ROMANCE, object},
            write,
        },
        remove, split,
    };

    fn starts(ical: &str) -> Vec<String> {
        expand(ical, "20260101T000000", "20270101T000000")
            .unwrap()
            .into_iter()
            .map(|occurrence| occurrence.start.time)
            .collect()
    }

    /// A weekly series of six Mondays, from 5 January 2026.
    fn weekly(extra: &str) -> String {
        object(&format!(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTAMP:20260101T000000Z\r\n\
             DTSTART:20260105T090000\r\nDTEND:20260105T100000\r\nSUMMARY:Standup\r\n\
             RRULE:FREQ=WEEKLY;COUNT=6\r\n{extra}END:VEVENT\r\n"
        ))
    }

    #[test]
    fn one_occurrence_edited_is_an_override_of_it() {
        // The delta's scenario: Tuesday's meeting moved to Wednesday.
        let ical = weekly("");
        let edit = json!({
            "scope": "this",
            "recurrenceId": "20260112T090000",
            "start": {"time": "20260113T090000"},
            "end": {"time": "20260113T100000"},
            "summary": "Standup, moved",
            "stamp": "20260102T000000Z",
        });

        let written = write(&ical, &edit.to_string()).unwrap();

        assert_eq!(
            starts(&written),
            [
                "20260105T090000",
                "20260113T090000",
                "20260119T090000",
                "20260126T090000",
                "20260202T090000",
                "20260209T090000"
            ]
        );
        // One object, the series untouched and the override beside it,
        // repeating nothing.
        assert_eq!(written.matches("BEGIN:VEVENT").count(), 2);
        assert_eq!(written.matches("RRULE").count(), 1);
        assert!(written.contains("RECURRENCE-ID:20260112T090000\r\n"));
        assert_eq!(
            read(&written, "20260112T090000").unwrap().summary,
            "Standup, moved"
        );
        assert_eq!(read(&written, "").unwrap().summary, "Standup");

        // Edited again, the same override is patched rather than doubled.
        let again = json!({
            "scope": "this",
            "recurrenceId": "20260112T090000",
            "location": "Room 3",
        });
        let twice = write(&written, &again.to_string()).unwrap();
        assert_eq!(twice.matches("RECURRENCE-ID").count(), 1);
        let moved = read(&twice, "20260112T090000").unwrap();
        assert_eq!(moved.location, "Room 3");
        assert_eq!(moved.summary, "Standup, moved");
    }

    #[test]
    fn an_override_of_a_zoned_series_names_its_instance_in_the_series_zone() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\n\
             DTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             RRULE:FREQ=DAILY;COUNT=3\r\nEND:VEVENT\r\n"
        ));
        let edit = json!({"scope": "this", "recurrenceId": "20260707T090000", "summary": "Moved"});

        let written = write(&ical, &edit.to_string()).unwrap();

        assert!(written.contains("RECURRENCE-ID;TZID=/example.org/Romance:20260707T090000\r\n"));
        assert!(written.contains("DTSTART;TZID=/example.org/Romance:20260707T090000\r\n"));
    }

    #[test]
    fn one_occurrence_deleted_is_an_exdate_on_the_series() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\n\
             DTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             RRULE:FREQ=DAILY;COUNT=3\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:z\r\nRECURRENCE-ID;TZID=/example.org/Romance:20260707T090000\r\n\
             DTSTART;TZID=/example.org/Romance:20260707T150000\r\nEND:VEVENT\r\n"
        ));
        let edit = json!({"scope": "this", "recurrenceId": "20260707T090000"});

        let left = remove(&ical, &edit.to_string()).unwrap().unwrap();

        assert!(left.contains("EXDATE;TZID=/example.org/Romance:20260707T090000\r\n"));
        // The override of it goes too, or it would come back as a lone
        // instance.
        assert!(!left.contains("RECURRENCE-ID"));
        assert_eq!(
            expand(&left, "20260701T000000", "20260801T000000")
                .unwrap()
                .into_iter()
                .map(|occurrence| occurrence.start.time)
                .collect::<Vec<_>>(),
            ["20260706T090000", "20260708T090000"]
        );
    }

    #[test]
    fn the_whole_series_moves_by_as_much_as_the_occurrence_it_was_opened_on() {
        // Opened on the third Monday and moved an hour later for all: the
        // series moves an hour, not to the third Monday.
        let ical = weekly("");
        let edit = json!({
            "scope": "all",
            "recurrenceId": "20260119T090000",
            "start": {"time": "20260119T100000"},
            "end": {"time": "20260119T110000"},
        });

        let written = write(&ical, &edit.to_string()).unwrap();

        assert!(written.contains("DTSTART:20260105T100000\r\n"));
        assert!(written.contains("DTEND:20260105T110000\r\n"));
        assert_eq!(starts(&written).len(), 6);
    }

    #[test]
    fn this_and_following_splits_a_counted_series() {
        // The delta's scenario: from the third week on, an hour later.
        let ical = weekly("");
        let edit = json!({
            "scope": "following",
            "recurrenceId": "20260119T090000",
            "start": {"time": "20260119T100000"},
            "end": {"time": "20260119T110000"},
            "uid": "s-2",
            "stamp": "20260102T000000Z",
        });

        let (master, series) = split(&ical, &edit.to_string()).unwrap();
        let series = series.unwrap();

        // The first two weeks keep the old hour, and the series counts
        // no more: its COUNT became the UNTIL ending it.
        assert_eq!(starts(&master), ["20260105T090000", "20260112T090000"]);
        assert!(master.contains("RRULE:FREQ=WEEKLY;UNTIL=20260119T085959\r\n"));
        assert!(!master.contains("COUNT"));
        assert!(master.contains("DTSTAMP:20260102T000000Z"));

        // The four left are the new series', at the new hour, under a
        // UID of their own.
        assert_eq!(
            starts(&series),
            [
                "20260119T100000",
                "20260126T100000",
                "20260202T100000",
                "20260209T100000"
            ]
        );
        assert!(series.contains("RRULE:FREQ=WEEKLY;COUNT=4\r\n"));
        assert!(series.contains("UID:s-2\r\n"));
        assert!(!series.contains("UID:s\r\n"));
        assert!(series.contains("DTEND:20260119T110000\r\n"));
        assert_eq!(read(&series, "").unwrap().summary, "Standup");
    }

    #[test]
    fn this_and_following_ends_a_zoned_series_on_a_utc_until() {
        let ical = object(&format!(
            "{ROMANCE}BEGIN:VEVENT\r\nUID:z\r\n\
             DTSTART;TZID=/example.org/Romance:20260706T090000\r\n\
             RRULE:FREQ=WEEKLY;UNTIL=20260831T070000Z\r\nEND:VEVENT\r\n"
        ));
        let edit = json!({
            "scope": "following",
            "recurrenceId": "20260720T090000",
            "recurrenceInstant": "20260720T070000Z",
            "start": {"time": "20260720T100000"},
            "uid": "z-2",
        });

        let (master, series) = split(&ical, &edit.to_string()).unwrap();
        let series = series.unwrap();

        // RFC 5545 3.3.10: UTC for a zoned start, and a second before the
        // occurrence's instant.
        assert!(master.contains("UNTIL=20260720T065959Z"));
        let window = |ical: &str| {
            expand(ical, "20260701T000000", "20261001T000000")
                .unwrap()
                .into_iter()
                .map(|occurrence| occurrence.start.time)
                .collect::<Vec<_>>()
        };
        assert_eq!(window(&master), ["20260706T090000", "20260713T090000"]);

        // The new series keeps the end the old one had, moved an hour
        // with its start so its last Monday stays in.
        assert!(series.contains("UNTIL=20260831T080000Z"));
        assert!(series.contains("DTSTART;TZID=/example.org/Romance:20260720T100000\r\n"));
        assert!(series.contains("BEGIN:VTIMEZONE"));
        assert_eq!(
            window(&series),
            [
                "20260720T100000",
                "20260727T100000",
                "20260803T100000",
                "20260810T100000",
                "20260817T100000",
                "20260824T100000",
                "20260831T100000"
            ]
        );
    }

    #[test]
    fn this_and_following_takes_the_later_overrides_and_exdates_along() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260105T090000\r\nSUMMARY:Standup\r\n\
             RRULE:FREQ=WEEKLY;COUNT=6\r\nEXDATE:20260112T090000,20260202T090000\r\n\
             END:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260105T090000\r\n\
             DTSTART:20260105T080000\r\nSUMMARY:Early\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260126T090000\r\n\
             DTSTART:20260127T090000\r\nSUMMARY:Tuesday\r\nEND:VEVENT\r\n",
        );
        let edit = json!({
            "scope": "following",
            "recurrenceId": "20260119T090000",
            "start": {"time": "20260119T100000"},
            "uid": "s-2",
        });

        let (master, series) = split(&ical, &edit.to_string()).unwrap();
        let series = series.unwrap();

        // What came before stays: the early override and the first
        // EXDATE. What comes after leaves.
        assert!(master.contains("EXDATE:20260112T090000\r\n"));
        assert!(master.contains("SUMMARY:Early"));
        assert!(!master.contains("SUMMARY:Tuesday"));
        assert_eq!(starts(&master), ["20260105T080000"]);

        // And lands in the new series, under its UID, each identity moved
        // by the hour the series moved so it still names an instance.
        assert!(series.contains("EXDATE:20260202T100000\r\n"));
        assert!(!series.contains("20260112"));
        assert!(!series.contains("SUMMARY:Early"));
        assert!(series.contains("RECURRENCE-ID:20260126T100000\r\n"));
        assert_eq!(series.matches("UID:s-2\r\n").count(), 2);
        assert_eq!(
            starts(&series),
            ["20260119T100000", "20260127T090000", "20260209T100000"]
        );
    }

    #[test]
    fn this_and_following_from_the_first_occurrence_is_all_of_them() {
        let ical = weekly("");
        let edit = json!({
            "scope": "following",
            "recurrenceId": "20260105T090000",
            "summary": "Daily",
            "uid": "s-2",
        });

        let (master, series) = split(&ical, &edit.to_string()).unwrap();

        assert!(series.is_none());
        assert!(master.contains("RRULE:FREQ=WEEKLY;COUNT=6\r\n"));
        assert_eq!(read(&master, "").unwrap().summary, "Daily");
    }

    #[test]
    fn this_and_following_deleted_ends_the_series() {
        let ical = weekly("");
        let from = |id: &str| json!({"scope": "following", "recurrenceId": id}).to_string();

        let left = remove(&ical, &from("20260119T090000")).unwrap().unwrap();
        assert_eq!(starts(&left), ["20260105T090000", "20260112T090000"]);

        // From its first occurrence nothing is left, and the entry goes.
        assert!(remove(&ical, &from("20260105T090000")).unwrap().is_none());
        let all = json!({"scope": "all", "recurrenceId": "20260119T090000"}).to_string();
        assert!(remove(&ical, &all).unwrap().is_none());
    }

    /// A series of `rule` from Tuesday 6 January 2026 at 09:00, its whole
    /// self moved by an edit opened on its first occurrence.
    fn moved(rule: &str, start: &str) -> Result<String, crate::types::BridgeError> {
        let ical = object(&format!(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260106T090000\r\nRRULE:{rule}\r\nEND:VEVENT\r\n"
        ));
        let edit = json!({
            "scope": "all",
            "recurrenceId": "20260106T090000",
            "start": {"time": start},
        });
        write(&ical, &edit.to_string())
    }

    #[test]
    fn a_weekday_rule_moves_with_its_start() {
        // A Tuesday series moved to Wednesday recurs on Wednesdays.
        let written = moved("FREQ=WEEKLY;BYDAY=TU", "20260107T090000").unwrap();
        assert!(written.contains("RRULE:FREQ=WEEKLY;BYDAY=WE\r\n"));
        assert_eq!(
            &starts(&written)[..2],
            ["20260107T090000", "20260114T090000"]
        );

        let written = moved("FREQ=WEEKLY;BYDAY=TU,TH", "20260107T090000").unwrap();
        assert!(written.contains("RRULE:FREQ=WEEKLY;BYDAY=WE,FR\r\n"));

        // Monday and Wednesday, a day later, are Tuesday and Thursday.
        let written = moved("FREQ=WEEKLY;BYDAY=MO,WE", "20260107T090000").unwrap();
        assert!(written.contains("RRULE:FREQ=WEEKLY;BYDAY=TU,TH\r\n"));
    }

    #[test]
    fn a_day_of_the_month_moves_with_its_start() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260115T090000\r\n\
             RRULE:FREQ=MONTHLY;BYMONTHDAY=15;COUNT=3\r\nEND:VEVENT\r\n",
        );
        let edit = json!({
            "scope": "all",
            "recurrenceId": "20260215T090000",
            "start": {"time": "20260216T090000"},
        });

        let written = write(&ical, &edit.to_string()).unwrap();

        assert!(written.contains("RRULE:FREQ=MONTHLY;BYMONTHDAY=16;COUNT=3\r\n"));
        assert_eq!(
            starts(&written),
            ["20260116T090000", "20260216T090000", "20260316T090000"]
        );
    }

    #[test]
    fn an_ordinal_weekday_takes_the_new_starts_place_in_its_month() {
        // The second Tuesday, from next month on the second Wednesday.
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260113T090000\r\n\
             RRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=4\r\nEND:VEVENT\r\n",
        );
        let edit = json!({
            "scope": "following",
            "recurrenceId": "20260210T090000",
            "start": {"time": "20260211T090000"},
            "uid": "s-2",
        });

        let (_, series) = split(&ical, &edit.to_string()).unwrap();
        let series = series.unwrap();

        assert!(series.contains("RRULE:FREQ=MONTHLY;BYDAY=2WE;COUNT=3\r\n"));
        assert_eq!(
            starts(&series),
            ["20260211T090000", "20260311T090000", "20260408T090000"]
        );
    }

    #[test]
    fn a_rule_that_cannot_follow_its_start_refuses_the_move() {
        // The 10th and the 25th, five days later, would need a 30th that
        // February has not: refused rather than drifting.
        let refused = moved("FREQ=MONTHLY;BYMONTHDAY=10,25", "20260111T090000");
        assert!(refused.is_err());
        assert!(moved("FREQ=MONTHLY;BYDAY=TU,TH;BYSETPOS=1", "20260107T090000").is_err());
        assert!(moved("FREQ=YEARLY;BYWEEKNO=2", "20260107T090000").is_err());

        // A move of the time alone leaves a rule with no hour of its own.
        let later = moved("FREQ=WEEKLY;BYDAY=TU", "20260106T100000").unwrap();
        assert!(later.contains("RRULE:FREQ=WEEKLY;BYDAY=TU\r\n"));
    }

    #[test]
    fn a_split_past_a_spent_count_carries_no_rule() {
        // Two weekly instances, then a date of its own: from that date on
        // the rule repeats nothing, and COUNT=0 is no rule to write.
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260105T090000\r\n\
             RRULE:FREQ=WEEKLY;COUNT=2\r\nRDATE:20260301T090000,20260401T090000\r\n\
             END:VEVENT\r\n",
        );
        let edit = json!({
            "scope": "following",
            "recurrenceId": "20260301T090000",
            "summary": "Later",
            "uid": "s-2",
        });

        let (master, series) = split(&ical, &edit.to_string()).unwrap();
        let series = series.unwrap();

        assert!(!series.contains("RRULE"));
        assert!(!series.contains("COUNT=0"));
        assert_eq!(starts(&series), ["20260301T090000", "20260401T090000"]);
        assert_eq!(starts(&master), ["20260105T090000", "20260112T090000"]);
    }

    #[test]
    fn a_rule_ending_sooner_keeps_its_end() {
        // The occurrence cut at is one an RDATE adds past the rule's end.
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20260105T090000\r\n\
             RRULE:FREQ=WEEKLY;UNTIL=20260112T090000\r\nRDATE:20260301T090000\r\nEND:VEVENT\r\n",
        );
        let edit = json!({"scope": "following", "recurrenceId": "20260301T090000"});

        let left = remove(&ical, &edit.to_string()).unwrap().unwrap();

        assert!(left.contains("RRULE:FREQ=WEEKLY;UNTIL=20260112T090000\r\n"));
        assert_eq!(starts(&left), ["20260105T090000", "20260112T090000"]);
    }

    #[test]
    fn an_all_day_series_ends_on_the_day_before() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:d\r\nDTSTART;VALUE=DATE:20260105\r\n\
             RRULE:FREQ=DAILY;COUNT=5\r\nEND:VEVENT\r\n",
        );
        let edit = json!({"scope": "following", "recurrenceId": "20260107"});

        let left = remove(&ical, &edit.to_string()).unwrap().unwrap();

        // A date start takes a date UNTIL (RFC 5545 3.3.10).
        assert!(left.contains("UNTIL=20260106\r\n"));
        assert_eq!(starts(&left), ["20260105", "20260106"]);
    }
}
