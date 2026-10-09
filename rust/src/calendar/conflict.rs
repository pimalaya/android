//! A calendar object both sides edited since the base they agreed on:
//! what the merge settles alone, and what a person is asked.
//!
//! ical-rs's three-way merge does the merging ([`IcalMerge`]); this side
//! decides what its collisions are to a reader. A collision is one field
//! of the entry page: a property of one component, or one occurrence of a
//! series as a whole, offering what each side holds. The stamps a write
//! leaves behind (`DTSTAMP`, `LAST-MODIFIED`, `SEQUENCE`), the producer
//! and the zone definitions are not fields, and settle themselves: the
//! later stamp, every zone a date names.
//!
//! The newer side, by `LAST-MODIFIED` and else `DTSTAMP`, holds a
//! collision until a person picks, except that an update is never held
//! under a removal. Nothing is lost meanwhile: a conflict carries both.

use std::{cmp::Ordering, collections::BTreeMap, slice};

use ical::{
    recur::IcalRecurDateTime,
    tree::{
        cst::{IcalCst, IcalItem},
        line::IcalLine,
        merge::{
            IcalComponentPath, IcalMerge, IcalMergeAction, IcalMergeConflict, IcalMergeReason,
            IcalPropPath,
        },
    },
    value::duration::IcalDuration,
    version::IcalVersion,
};
use serde::{Deserialize, Serialize};

use crate::types::BridgeError;

use super::{EventTime, EventTimeKind, SCHEDULED, Zones, scheduled, series, text};

/// What a write stamps rather than what it says.
const STAMPS: [&str; 3] = ["DTSTAMP", "LAST-MODIFIED", "SEQUENCE"];

/// The properties the entry page edits, by the key its edit object
/// names them with.
const FIELDS: [(&str, &str); 13] = [
    ("SUMMARY", "summary"),
    ("DESCRIPTION", "description"),
    ("LOCATION", "location"),
    ("URL", "url"),
    ("STATUS", "status"),
    ("CATEGORIES", "categories"),
    ("PRIORITY", "priority"),
    ("PERCENT-COMPLETE", "percentComplete"),
    ("DTSTART", "start"),
    ("DTEND", "end"),
    ("DUE", "due"),
    ("COMPLETED", "completed"),
    ("RRULE", "recurrence"),
];

/// The dates among them, which a choice also hands over as a time.
const DATES: [&str; 4] = ["DTSTART", "DTEND", "DUE", "COMPLETED"];

/// What a component's span is made of: its start, and its end, its due
/// date or its duration.
const SPAN: [&str; 4] = ["DTSTART", "DTEND", "DUE", "DURATION"];

/// Seconds in a day, which a duration from a date counts in.
const DAY: i64 = 86_400;

/// Whose version of a field a choice is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EventSide {
    /// The body staged here.
    Local,
    /// The body the source holds, recorded when the conflict was.
    Remote,
}

/// What a collision is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum EventConflictKind {
    /// Both sides changed one field, differently.
    Field,
    /// One side changed what defines the series and the other one of
    /// its occurrences, which the change may have moved from under it.
    Recurrence,
}

/// One collision the merge cannot settle, as the entry page asks it.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventConflict {
    /// What a resolution names it by.
    pub id: usize,
    pub kind: EventConflictKind,
    /// The component it is on: `VEVENT`, `VTODO` or `VJOURNAL`, or
    /// `VCALENDAR` for the object's own properties.
    pub component: String,
    /// The occurrence it is on, as its override's `RECURRENCE-ID` names
    /// it; absent on a series or an entry that does not recur.
    pub recurrence_id: Option<EventTime>,
    /// What it is: the edit object's key for a property the page edits
    /// (`summary`, `start`, `recurrence` for the rule), `when` for a start
    /// and an end that collide only together, `occurrence` for
    /// one occurrence whole, `entry` for the component whole, else the
    /// property or nested component's name as written.
    pub field: String,
    /// The side that changed the series, on a recurrence conflict.
    pub series: Option<EventSide>,
    /// What each side holds, the one the merged object holds first.
    pub choices: Vec<EventChoice>,
}

/// One side's value of a conflicted field.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventChoice {
    pub side: EventSide,
    /// The value as a reader sees it, empty where the side holds none.
    pub value: String,
    /// The value of a date, with its zone; the start, on a span.
    pub time: Option<EventTime>,
    /// The end a span runs to, its duration added when it has no end.
    pub end: Option<EventTime>,
}

/// A conflicted object merged: what the merge holds, and what is left
/// to ask.
#[derive(Debug, Serialize)]
pub struct EventMerge {
    /// The merged object, each collision holding its pre-filled side.
    pub ical: String,
    /// Whether nothing is left to ask, so `ical` is the resolution.
    pub resolved: bool,
    pub conflicts: Vec<EventConflict>,
}

/// One step of a component's address: its name, and its `UID` (with its
/// `RECURRENCE-ID` after a solidus) or its position among its
/// same-named siblings, the way ical-rs addresses one.
type Step = (String, String);

/// What a collision is on.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Address {
    /// The lines of one property of a component, or the one line of it
    /// carrying an identity (an `ATTENDEE`'s address).
    Prop {
        path: Vec<Step>,
        name: String,
        identity: Option<String>,
    },
    /// A component whole.
    Component(Vec<Step>),
    /// The span of a component: its start, and its end or duration,
    /// which mean something only together.
    Span(Vec<Step>),
}

impl Address {
    /// The component it is in.
    fn path(&self) -> &[Step] {
        match self {
            Self::Prop { path, .. } | Self::Component(path) | Self::Span(path) => path,
        }
    }

    /// The properties a span is made of, one field each.
    fn parts(path: &[Step]) -> impl Iterator<Item = Address> + '_ {
        SPAN.into_iter().map(|name| Address::Prop {
            path: path.to_vec(),
            name: name.into(),
            identity: None,
        })
    }
}

/// One collision, its sides in the order the form offers them.
struct Collision {
    kind: EventConflictKind,
    address: Address,
    series: Option<EventSide>,
    choices: Vec<EventSide>,
}

/// The merge of three objects, before its stamps and zones settle.
struct Settled {
    merged: IcalCst<'static>,
    local: IcalCst<'static>,
    remote: IcalCst<'static>,
    collisions: Vec<Collision>,
}

/// Merges a conflicted object: the body staged here and the one its
/// source holds, against the base both came from (empty when none was
/// agreed). Answers the merged object and every collision left to ask.
pub fn merge(base: &str, local: &str, remote: &str) -> Result<EventMerge, BridgeError> {
    let mut settled = settle(base, local, remote)?;
    let held: Vec<EventSide> = settled
        .collisions
        .iter()
        .map(|collision| collision.choices[0])
        .collect();
    settled.finish(&held);

    let conflicts: Vec<EventConflict> = settled
        .collisions
        .iter()
        .enumerate()
        .map(|(id, collision)| settled.conflict(id, collision))
        .collect();

    Ok(EventMerge {
        ical: text(&settled.merged)?,
        resolved: conflicts.is_empty(),
        conflicts,
    })
}

/// The resolution of a conflicted object: its merge, each conflict
/// `picks` names taking that side, every other the side it was
/// pre-filled with. `picks` is a JSON object of side by conflict id.
pub fn resolve(base: &str, local: &str, remote: &str, picks: &str) -> Result<String, BridgeError> {
    let picks: BTreeMap<usize, EventSide> =
        serde_json::from_str(picks).map_err(|err| err.to_string())?;
    let mut settled = settle(base, local, remote)?;

    let mut sides: Vec<EventSide> = settled
        .collisions
        .iter()
        .map(|collision| collision.choices[0])
        .collect();
    for (id, side) in picks {
        *sides
            .get_mut(id)
            .ok_or_else(|| format!("No conflict {id}"))? = side;
    }

    let mut chosen: Vec<(Address, EventSide)> = settled
        .collisions
        .iter()
        .zip(&sides)
        .filter(|(collision, side)| collision.choices[0] != **side)
        .map(|(collision, side)| (collision.address.clone(), *side))
        .collect();
    // NOTE: a component taken whole decides everything in it, so it goes
    // after the properties it holds, which it would otherwise undo.
    chosen.sort_by_key(|(address, _)| matches!(address, Address::Component(_)));

    for (address, side) in &chosen {
        let from = match side {
            EventSide::Local => &settled.local,
            EventSide::Remote => &settled.remote,
        };
        transplant(&mut settled.merged, address, from);
    }
    settled.finish(&sides);

    text(&settled.merged)
}

/// Runs the merge, and settles which side each collision holds.
fn settle(base: &str, local: &str, remote: &str) -> Result<Settled, BridgeError> {
    let local = parse(local)?;
    let remote = parse(remote)?;
    let mut base = match base.trim().is_empty() {
        true => skeleton(&local, &remote),
        false => parse(base)?,
    };
    instances(&mut base, &local, &remote);

    // NOTE: the newer side is the merge's left, which keeps its value in a
    // collision; a tie or a side without stamps leaves it to the edit here.
    let remote_newer = matches!(
        (latest(&remote), latest(&local)),
        (Some(remote), Some(local)) if remote > local
    );
    let (left, right) = match remote_newer {
        true => ((&remote, EventSide::Remote), (&local, EventSide::Local)),
        false => ((&local, EventSide::Local), (&remote, EventSide::Remote)),
    };

    let report = IcalMerge {
        base: &base,
        left: left.0,
        right: right.0,
    }
    .merge();

    let mut collisions: Vec<Collision> = Vec::new();
    for conflict in &report.conflicts {
        let Some((kind, address, series)) = collision(conflict, left.1, right.1) else {
            continue;
        };
        if collisions
            .iter()
            .any(|held| held.kind == kind && held.address == address)
        {
            continue;
        }
        collisions.push(Collision {
            kind,
            address,
            series,
            choices: Vec::new(),
        });
    }

    let mut merged = report.merged;
    for collision in &mut collisions {
        // NOTE: a recurrence conflict is between two components, so only
        // the object as a whole is newer on one side.
        let prefer = match collision.kind {
            EventConflictKind::Field => newer(&local, &remote, &collision.address),
            EventConflictKind::Recurrence => None,
        }
        .unwrap_or(left.1);
        collision.choices = choose(&mut merged, &local, &remote, &collision.address, prefer);
    }

    // NOTE: a start and an end are one field for what the merge makes of
    // them: each side's stands alone, and the two together can name no
    // span. Then the dates are asked as one, each side's pair a choice.
    let paths: Vec<Vec<Step>> = scheduled(&merged)
        .into_iter()
        .filter_map(|index| match &merged.items[index] {
            IcalItem::Component(component) if keyed(component) => {
                Some(vec![(name_of(component), identity(component, 0))])
            }
            _ => None,
        })
        .collect();
    for path in paths {
        let address = Address::Span(path);
        if spans(&merged, address.path())
            || same(&merged, &local, &address)
            || same(&merged, &remote, &address)
        {
            continue;
        }

        collisions.retain(|held| {
            !matches!(&held.address, Address::Prop { path, name, .. }
                if path == address.path() && SPAN.contains(&name.as_str()))
        });
        let prefer = newer(&local, &remote, &address).unwrap_or(left.1);
        let choices = choose(&mut merged, &local, &remote, &address, prefer);
        collisions.push(Collision {
            kind: EventConflictKind::Field,
            address,
            series: None,
            choices,
        });
    }

    Ok(Settled {
        merged,
        local,
        remote,
        collisions,
    })
}

/// A side's text as a tree, owned.
fn parse(ical: &str) -> Result<IcalCst<'static>, BridgeError> {
    Ok(IcalCst::parse(ical)
        .map_err(|err| err.to_string())?
        .into_static())
}

/// What one ical-rs conflict is to a reader: its kind, the field it is
/// on, and the side that moved the series when it is a recurrence one.
/// None for a collision on what a write stamps rather than says.
fn collision(
    conflict: &IcalMergeConflict,
    left: EventSide,
    right: EventSide,
) -> Option<(EventConflictKind, Address, Option<EventSide>)> {
    let (held, kind) = match &conflict.left {
        IcalMergeReason::Divergent(action) => (action, EventConflictKind::Field),
        IcalMergeReason::Recurrence(action) => (action, EventConflictKind::Recurrence),
    };

    if kind == EventConflictKind::Recurrence {
        // NOTE: which of the two is on an occurrence says which side moved
        // the series, and the occurrence is what is asked about.
        let (instance, series) = match overrides(component_of(&conflict.right)) {
            true => (&conflict.right, left),
            false => (held, right),
        };
        let path = steps(component_of(instance)).into_iter().take(1).collect();
        return Some((kind, Address::Component(path), Some(series)));
    }

    let address = match (held, &conflict.right) {
        (
            IcalMergeAction::ComponentAdded { at } | IcalMergeAction::ComponentRemoved { at },
            other,
        )
        | (
            other,
            IcalMergeAction::ComponentAdded { at } | IcalMergeAction::ComponentRemoved { at },
        ) => {
            // NOTE: the outer of the two, which holds the other.
            let inner = component_of(other);
            match inner.0.len() < at.0.len() {
                true => Address::Component(steps(inner)),
                false => Address::Component(steps(at)),
            }
        }
        (_, action) => {
            let at = prop_of(action)?;
            Address::Prop {
                path: steps(&at.component),
                name: at.name.to_ascii_uppercase(),
                identity: at.identity.as_ref().map(|identity| identity.to_string()),
            }
        }
    };

    (!stamped(&address)).then_some((kind, address, None))
}

/// Whether a collision is on what a write stamps rather than says: a
/// stamp, the producer, a zone definition.
fn stamped(address: &Address) -> bool {
    let name = match address {
        Address::Prop { name, .. } => Some(name.as_str()),
        _ => None,
    };

    match (address.path().first(), name) {
        (Some((component, _)), _) if component == "VTIMEZONE" => true,
        (_, Some(name)) if STAMPS.contains(&name) => true,
        (None, Some(name)) => name == "PRODID",
        _ => false,
    }
}

/// The component an action lands in.
fn component_of<'c, 'a>(action: &'c IcalMergeAction<'a>) -> &'c IcalComponentPath<'a> {
    match action {
        IcalMergeAction::ComponentAdded { at } | IcalMergeAction::ComponentRemoved { at } => at,
        IcalMergeAction::PropAdded { at, .. }
        | IcalMergeAction::PropRemoved { at, .. }
        | IcalMergeAction::ValueChanged { at, .. }
        | IcalMergeAction::ValueItemAdded { at, .. }
        | IcalMergeAction::ValueItemRemoved { at, .. }
        | IcalMergeAction::ParamAdded { at, .. }
        | IcalMergeAction::ParamRemoved { at, .. }
        | IcalMergeAction::ParamChanged { at, .. } => &at.component,
    }
}

/// The property an action lands on, for one that lands on one.
fn prop_of<'c, 'a>(action: &'c IcalMergeAction<'a>) -> Option<&'c IcalPropPath<'a>> {
    match action {
        IcalMergeAction::ComponentAdded { .. } | IcalMergeAction::ComponentRemoved { .. } => None,
        IcalMergeAction::PropAdded { at, .. }
        | IcalMergeAction::PropRemoved { at, .. }
        | IcalMergeAction::ValueChanged { at, .. }
        | IcalMergeAction::ValueItemAdded { at, .. }
        | IcalMergeAction::ValueItemRemoved { at, .. }
        | IcalMergeAction::ParamAdded { at, .. }
        | IcalMergeAction::ParamRemoved { at, .. }
        | IcalMergeAction::ParamChanged { at, .. } => Some(at),
    }
}

/// An ical-rs component path as owned steps.
fn steps(path: &IcalComponentPath) -> Vec<Step> {
    path.0
        .iter()
        .map(|step| (step.name.to_string(), step.key.to_string()))
        .collect()
}

/// Whether a path is in an override of one occurrence.
fn overrides(path: &IcalComponentPath) -> bool {
    path.0.first().is_some_and(|step| step.key.contains('/'))
}

/// Which side holds a field until someone picks: the newer edit of the
/// component it is in, when both sides stamped it.
fn newer(
    local: &IcalCst<'static>,
    remote: &IcalCst<'static>,
    address: &Address,
) -> Option<EventSide> {
    let path = address.path().first()?;
    let local = stamp(find(local, slice::from_ref(path))?)?;
    let remote = stamp(find(remote, slice::from_ref(path))?)?;

    match remote.cmp(&local) {
        Ordering::Greater => Some(EventSide::Remote),
        Ordering::Less => Some(EventSide::Local),
        Ordering::Equal => None,
    }
}

/// When a component was last written: its `LAST-MODIFIED`, else its
/// `DTSTAMP`, in seconds.
fn stamp(component: &IcalCst<'static>) -> Option<i64> {
    ["LAST-MODIFIED", "DTSTAMP"].into_iter().find_map(|name| {
        let line = prop_lines(component).find(|line| line.name.get().eq_ignore_ascii_case(name))?;
        Some(EventTime::of_line(line)?.civil()?.seconds())
    })
}

/// When an object was last written: the latest stamp of what it
/// schedules.
fn latest(cst: &IcalCst<'static>) -> Option<i64> {
    scheduled(cst)
        .into_iter()
        .filter_map(|index| match &cst.items[index] {
            IcalItem::Component(component) => stamp(component),
            _ => None,
        })
        .max()
}

/// Settles which side a collision holds, moving the preferred side's
/// value into the merged object when the merge kept the other one, and
/// answers the sides to offer, the held one first.
///
/// An update is not replaced by a removal: the merge keeps it whichever
/// side it came from, and so does the pre-fill. Nor is a span the merge
/// made of one side's start and the other's end held, which neither of
/// them wrote.
fn choose(
    merged: &mut IcalCst<'static>,
    local: &IcalCst<'static>,
    remote: &IcalCst<'static>,
    address: &Address,
    prefer: EventSide,
) -> Vec<EventSide> {
    let side = |side: EventSide| match side {
        EventSide::Local => local,
        EventSide::Remote => remote,
    };
    let other = |side: EventSide| match side {
        EventSide::Local => EventSide::Remote,
        EventSide::Remote => EventSide::Local,
    };

    let kept = match (same(merged, local, address), same(merged, remote, address)) {
        (true, false) => Some(EventSide::Local),
        (false, true) => Some(EventSide::Remote),
        (true, true) => Some(prefer),
        (false, false) => None,
    };
    let held = match kept {
        Some(kept) if kept == prefer || !present(side(prefer), address) => kept,
        _ if present(side(prefer), address) => prefer,
        _ => other(prefer),
    };
    if kept != Some(held) {
        transplant(merged, address, side(held));
    }

    match same(local, remote, address) {
        true => vec![held],
        false => vec![held, other(held)],
    }
}

impl Settled {
    /// Puts what follows from the fields in order once they are, each
    /// collision holding the side `sides` names: the series' exclusions,
    /// the stamps and the zones.
    fn finish(&mut self, sides: &[EventSide]) {
        for (collision, side) in self.collisions.iter().zip(sides) {
            let Address::Component(path) = &collision.address else {
                continue;
            };
            let [step] = path.as_slice() else {
                continue;
            };
            restamp(&mut self.merged, path, &self.local, &self.remote);

            let from = match side {
                EventSide::Local => &self.local,
                EventSide::Remote => &self.remote,
            };
            let Some(over) = find(from, slice::from_ref(step)) else {
                continue;
            };
            let named = |name: &str| {
                prop_lines(over).find(|line| line.name.get().eq_ignore_ascii_case(name))
            };
            let (Some(uid), Some(id)) = (named("UID"), named("RECURRENCE-ID")) else {
                continue;
            };

            // NOTE: an occurrence kept is one its series includes, so the
            // EXDATE its removal came with goes too, unless the side kept
            // holds that as well.
            let uid = uid.raw_value_str();
            if !series::restore(&mut from.clone(), &uid, id) {
                series::restore(&mut self.merged, &uid, id);
            }
        }

        define_zones(&mut self.merged, [&self.local, &self.remote]);
    }

    /// One collision as the form asks it.
    fn conflict(&self, id: usize, collision: &Collision) -> EventConflict {
        let top = collision.address.path().first();

        let recurrence_id = top.filter(|(_, key)| key.contains('/')).and_then(|step| {
            [&self.merged, &self.local, &self.remote]
                .into_iter()
                .find_map(|cst| {
                    let component = find(cst, slice::from_ref(step))?;
                    let line = prop_lines(component)
                        .find(|line| line.name.get().eq_ignore_ascii_case("RECURRENCE-ID"))?;
                    Some(Zones::of_cst(cst).resolve(EventTime::of_line(line)?))
                })
        });

        let choices = collision
            .choices
            .iter()
            .map(|side| {
                let cst = match side {
                    EventSide::Local => &self.local,
                    EventSide::Remote => &self.remote,
                };
                choice(*side, cst, &collision.address)
            })
            .collect();

        EventConflict {
            id,
            kind: collision.kind,
            component: top
                .map(|(name, _)| name.clone())
                .unwrap_or_else(|| "VCALENDAR".into()),
            recurrence_id,
            field: field(collision.kind, &collision.address),
            series: collision.series,
            choices,
        }
    }
}

/// What a collision is called on the form.
fn field(kind: EventConflictKind, address: &Address) -> String {
    match (kind, address) {
        (EventConflictKind::Recurrence, _) => "occurrence".into(),
        (_, Address::Span(_)) => "when".into(),
        (_, Address::Component(path)) => match path.as_slice() {
            [(_, key)] if key.contains('/') => "occurrence".into(),
            [_] => "entry".into(),
            _ => path
                .last()
                .map(|(name, _)| name.clone())
                .unwrap_or_default(),
        },
        (_, Address::Prop { path, name, .. }) => match path.as_slice() {
            [_] => FIELDS
                .iter()
                .find(|(prop, _)| prop == name)
                .map(|(_, key)| (*key).to_string())
                .unwrap_or_else(|| name.clone()),
            [] => name.clone(),
            _ => path
                .last()
                .map(|(name, _)| name.clone())
                .unwrap_or_default(),
        },
    }
}

/// One side's value of a field, as a reader sees it.
fn choice(side: EventSide, cst: &IcalCst<'static>, address: &Address) -> EventChoice {
    let zones = Zones::of_cst(cst);
    let (value, time, end) = match address {
        Address::Component(path) => (
            find(cst, path)
                .map(|component| component.to_string())
                .unwrap_or_default(),
            None,
            None,
        ),
        Address::Span(path) => match bounds(cst, path) {
            Some(bounds) => (
                String::new(),
                Some(zones.resolve(bounds.start)),
                bounds.end.map(|end| zones.resolve(end)),
            ),
            None => (String::new(), None, None),
        },
        Address::Prop { name, .. } => {
            let lines = lines_of(cst, address);
            let time = match lines.as_slice() {
                [one] if DATES.contains(&name.as_str()) => {
                    EventTime::of_line(one).map(|time| zones.resolve(time))
                }
                _ => None,
            };
            let readable = FIELDS.iter().any(|(prop, _)| prop == name);
            let value = lines
                .iter()
                .map(|line| match readable {
                    true => line.value.decode().into_owned(),
                    false => line.to_string().trim_end().to_string(),
                })
                .collect::<Vec<_>>()
                .join("\n");
            (value, time, None)
        }
    };

    EventChoice {
        side,
        value,
        time,
        end,
    }
}

/// Where a component starts and runs to.
struct Bounds {
    start: EventTime,
    /// Its `DTEND` or `DUE`, else its start moved by its `DURATION`.
    end: Option<EventTime>,
    /// Its `DURATION`, in seconds.
    duration: Option<i64>,
}

/// A component's bounds, none when it has no start.
fn bounds(cst: &IcalCst<'static>, path: &[Step]) -> Option<Bounds> {
    let component = find(cst, path)?;
    let first = |name: &str| prop_lines(component).find(|line| named(line, name, None));
    let date = |name: &str| first(name).and_then(EventTime::of_line);

    let start = date("DTSTART")?;
    let duration = first("DURATION").and_then(|line| IcalDuration(line.value.decode()).seconds());
    let end = date("DTEND").or_else(|| date("DUE")).or_else(|| {
        let civil = start.civil()?;
        Some(start.at(IcalRecurDateTime::from_seconds(civil.seconds() + duration?)))
    });

    Some(Bounds {
        start,
        end,
        duration,
    })
}

/// Whether a component's dates make a span (RFC 5545 3.6.1, 3.8.2.2,
/// 3.8.2.5): an end after its start and of its kind, a date ending on a
/// date, a duration that is positive, in whole days from a date.
fn spans(cst: &IcalCst<'static>, path: &[Step]) -> bool {
    let Some(Bounds {
        start,
        end,
        duration,
    }) = bounds(cst, path)
    else {
        return true;
    };

    let date = start.kind == EventTimeKind::Date;
    if duration.is_some_and(|seconds| seconds <= 0 || date && seconds % DAY != 0) {
        return false;
    }
    let Some(end) = end else {
        return true;
    };
    if date != (end.kind == EventTimeKind::Date) {
        return false;
    }

    let zones = Zones::of_cst(cst);
    let instant = |time: &EventTime| {
        let civil = time.civil()?;
        match time.kind {
            EventTimeKind::Date | EventTimeKind::Floating => Some(civil.seconds()),
            _ => zones.instant(time, civil),
        }
    };
    match (instant(&start), instant(&end)) {
        (Some(start), Some(end)) => end > start,
        // NOTE: a zone only the platform knows compares as written within
        // itself, and across two such zones this side cannot tell.
        _ => start.kind != end.kind || start.tzid != end.tzid || end.time > start.time,
    }
}

/// Whether two objects hold the same value of a field.
fn same(left: &IcalCst<'static>, right: &IcalCst<'static>, address: &Address) -> bool {
    match address {
        Address::Prop { .. } => lines_of(left, address)
            .iter()
            .map(|line| line.decode(IcalVersion::V2_0))
            .eq(lines_of(right, address)
                .iter()
                .map(|line| line.decode(IcalVersion::V2_0))),
        Address::Component(path) => {
            find(left, path).map(ToString::to_string) == find(right, path).map(ToString::to_string)
        }
        Address::Span(path) => Address::parts(path).all(|part| same(left, right, &part)),
    }
}

/// Whether an object holds a value of a field at all.
fn present(cst: &IcalCst<'static>, address: &Address) -> bool {
    match address {
        Address::Prop { .. } => !lines_of(cst, address).is_empty(),
        Address::Component(path) => find(cst, path).is_some(),
        Address::Span(path) => Address::parts(path).any(|part| present(cst, &part)),
    }
}

/// The lines of a property field in one object.
fn lines_of<'c>(cst: &'c IcalCst<'static>, address: &Address) -> Vec<&'c IcalLine<'static>> {
    let Address::Prop {
        path,
        name,
        identity,
    } = address
    else {
        return Vec::new();
    };

    find(cst, path)
        .map(|component| {
            prop_lines(component)
                .filter(|line| named(line, name, identity.as_deref()))
                .collect()
        })
        .unwrap_or_default()
}

/// Whether a line is the property a field names.
fn named(line: &IcalLine, name: &str, identity: Option<&str>) -> bool {
    line.name.get().eq_ignore_ascii_case(name)
        && identity.is_none_or(|identity| line.value.to_string().to_lowercase() == identity)
}

/// A component's property lines.
fn prop_lines<'c>(component: &'c IcalCst<'static>) -> impl Iterator<Item = &'c IcalLine<'static>> {
    component.items.iter().filter_map(|item| match item {
        IcalItem::Prop(line) => Some(line),
        _ => None,
    })
}

/// Replaces a field of the merged object with what another side holds,
/// in the place the merged one held it.
fn transplant(merged: &mut IcalCst<'static>, address: &Address, from: &IcalCst<'static>) {
    match address {
        Address::Span(path) => {
            for part in Address::parts(path) {
                transplant(merged, &part, from);
            }
        }
        Address::Prop {
            path,
            name,
            identity,
        } => {
            let lines: Vec<IcalItem<'static>> = lines_of(from, address)
                .into_iter()
                .map(|line| IcalItem::Prop(line.clone()))
                .collect();
            let Some(component) = find_mut(merged, path) else {
                return;
            };

            let matches = |item: &IcalItem| matches!(item, IcalItem::Prop(line) if named(line, name, identity.as_deref()));
            let at = component.items.iter().position(matches);
            component.items.retain(|item| !matches(item));
            let at = at.unwrap_or_else(|| {
                component
                    .items
                    .iter()
                    .position(|item| matches!(item, IcalItem::Component(_)))
                    .unwrap_or(component.items.len())
            });
            component.items.splice(at..at, lines);
        }
        Address::Component(path) => {
            let Some((step, above)) = path.split_last() else {
                return;
            };
            let taken = find(from, path).cloned();
            let Some(parent) = find_mut(merged, above) else {
                return;
            };

            match (position(parent, step), taken) {
                (Some(at), Some(component)) => {
                    parent.items[at] = IcalItem::Component(Box::new(component))
                }
                (Some(at), None) => {
                    parent.items.remove(at);
                }
                (None, Some(component)) => {
                    // NOTE: beside its same-named siblings, where a reader
                    // walking the object in order looks for it.
                    let at = parent
                        .items
                        .iter()
                        .rposition(|item| {
                            matches!(item, IcalItem::Component(held) if name_of(held) == step.0)
                        })
                        .map(|at| at + 1)
                        .unwrap_or(parent.items.len());
                    parent
                        .items
                        .insert(at, IcalItem::Component(Box::new(component)));
                }
                (None, None) => {}
            }
        }
    }
}

/// The component a path names.
fn find<'c>(cst: &'c IcalCst<'static>, path: &[Step]) -> Option<&'c IcalCst<'static>> {
    path.iter()
        .try_fold(cst, |held, step| match &held.items[position(held, step)?] {
            IcalItem::Component(child) => Some(&**child),
            _ => None,
        })
}

fn find_mut<'c>(cst: &'c mut IcalCst<'static>, path: &[Step]) -> Option<&'c mut IcalCst<'static>> {
    let mut held = cst;
    for step in path {
        let at = position(held, step)?;
        held = match &mut held.items[at] {
            IcalItem::Component(child) => &mut **child,
            _ => return None,
        };
    }
    Some(held)
}

/// Where the child a step names sits among a component's items.
fn position(parent: &IcalCst<'static>, step: &Step) -> Option<usize> {
    let mut ordinal = 0;
    parent.items.iter().position(|item| {
        let IcalItem::Component(child) = item else {
            return false;
        };
        if name_of(child) != step.0 {
            return false;
        }
        let held = identity(child, ordinal);
        ordinal += 1;
        held == step.1
    })
}

/// A component's name, uppercase.
fn name_of(component: &IcalCst) -> String {
    component
        .begin
        .as_ref()
        .map(|begin| begin.raw_value_str().to_ascii_uppercase())
        .unwrap_or_default()
}

/// A component's key among its same-named siblings, as ical-rs reads
/// it: its `UID`, its `RECURRENCE-ID` after a solidus, or its position.
fn identity(component: &IcalCst<'static>, ordinal: usize) -> String {
    let raw = |name: &str| {
        prop_lines(component)
            .find(|line| line.name.get().eq_ignore_ascii_case(name))
            .map(|line| line.raw_value_str().into_owned())
    };

    match (raw("UID"), raw("RECURRENCE-ID")) {
        (Some(uid), Some(id)) => format!("{uid}/{id}"),
        (Some(uid), None) => uid,
        (None, _) => ordinal.to_string(),
    }
}

/// Whether a component carries a `UID`, which is what names it.
fn keyed(component: &IcalCst<'static>) -> bool {
    prop_lines(component).any(|line| line.name.get().eq_ignore_ascii_case("UID"))
}

/// The base two sides that agreed on none are merged against: every
/// component both hold, by its identity alone, so every property either
/// wrote is an addition and two that differ collide.
///
/// Without it a merge against nothing would hold one side's component
/// whole and drop the other's, which is a side lost silently.
fn skeleton(local: &IcalCst<'static>, remote: &IcalCst<'static>) -> IcalCst<'static> {
    let mut base = IcalCst::v2();
    for item in &local.items {
        let IcalItem::Component(component) = item else {
            continue;
        };
        let step = (name_of(component), identity(component, 0));
        if !keyed(component) || find(remote, slice::from_ref(&step)).is_none() {
            continue;
        }

        let mut bare = IcalCst::empty(step.0);
        bare.items = prop_lines(component)
            .filter(|line| {
                let name = line.name.get();
                name.eq_ignore_ascii_case("UID") || name.eq_ignore_ascii_case("RECURRENCE-ID")
            })
            .map(|line| IcalItem::Prop(line.clone()))
            .collect();
        base.items.push(IcalItem::Component(Box::new(bare)));
    }
    base
}

/// Gives the base the override each side wrote of one occurrence on its
/// own: the series carried to that occurrence, which is what both
/// started from.
///
/// Without it two overrides of one occurrence, neither in the base, are
/// two additions of one component, and the merge keeps one whole: an
/// edit of its title here and of its place there would collide rather
/// than merge.
fn instances(base: &mut IcalCst<'static>, local: &IcalCst<'static>, remote: &IcalCst<'static>) {
    for item in &local.items {
        let IcalItem::Component(component) = item else {
            continue;
        };
        let step = (name_of(component), identity(component, 0));
        if !step.1.contains('/')
            || !SCHEDULED.iter().any(|kind| kind.to_string() == step.0)
            || find(base, slice::from_ref(&step)).is_some()
            || find(remote, slice::from_ref(&step)).is_none()
        {
            continue;
        }

        let lines: Vec<_> = prop_lines(component).collect();
        let (Some(uid), Some(id)) = (
            lines
                .iter()
                .find(|line| line.name.get().eq_ignore_ascii_case("UID")),
            lines
                .iter()
                .find(|line| line.name.get().eq_ignore_ascii_case("RECURRENCE-ID")),
        ) else {
            continue;
        };
        if let Some(over) = series::instance_of(base, &uid.raw_value_str(), id) {
            base.items.push(IcalItem::Component(Box::new(over)));
        }
    }
}

/// Puts the later stamp and the greater sequence of both sides on a
/// component taken whole from one of them (RFC 5545 3.8.7, RFC 5546
/// 2.1.4): the merge settles them so on what it merges, and a component
/// taken whole comes with the stamps its side wrote, which can be the
/// earlier.
fn restamp(
    merged: &mut IcalCst<'static>,
    path: &[Step],
    local: &IcalCst<'static>,
    remote: &IcalCst<'static>,
) {
    for name in STAMPS {
        let address = Address::Prop {
            path: path.to_vec(),
            name: name.into(),
            identity: None,
        };
        let rank = |cst: &IcalCst<'static>| {
            let line = *lines_of(cst, &address).first()?;
            match name {
                "SEQUENCE" => line.value.decode().trim().parse::<i64>().ok(),
                _ => Some(EventTime::of_line(line)?.civil()?.seconds()),
            }
        };

        let best = [local, remote]
            .into_iter()
            .filter_map(|side| Some((rank(side)?, side)))
            .max_by_key(|(rank, _)| *rank);
        if let Some((value, side)) = best
            && rank(merged).is_none_or(|held| held < value)
        {
            transplant(merged, &address, side);
        }
    }
}

/// Copies in the definition of every zone a date of the merged object
/// names and it does not define, from the side that does: a date taken
/// from the other side brings its `TZID` with it (RFC 5545 3.2.19).
fn define_zones(merged: &mut IcalCst<'static>, sides: [&IcalCst<'static>; 2]) {
    let mut named = Vec::new();
    zones_named(merged, &mut named);

    let defined = Zones::of_cst(merged);
    for tzid in named {
        if defined.find(&tzid).is_some() {
            continue;
        }
        let definition = sides.iter().find_map(|side| {
            side.items.iter().find_map(|item| match item {
                IcalItem::Component(zone)
                    if name_of(zone) == "VTIMEZONE"
                        && prop_lines(zone).any(|line| {
                            line.name.get().eq_ignore_ascii_case("TZID")
                                && line.value.decode() == tzid
                        }) =>
                {
                    Some((**zone).clone())
                }
                _ => None,
            })
        });
        if let Some(zone) = definition {
            let at = scheduled(merged)
                .first()
                .copied()
                .unwrap_or(merged.items.len());
            merged.items.insert(at, IcalItem::Component(Box::new(zone)));
        }
    }
}

/// Every `TZID` the dates of a component and of everything in it name.
fn zones_named(component: &IcalCst<'static>, out: &mut Vec<String>) {
    for item in &component.items {
        match item {
            IcalItem::Prop(line) => {
                if let Some(time) = EventTime::of_line(line)
                    && !time.tzid.is_empty()
                    && !out.contains(&time.tzid)
                {
                    out.push(time.tzid);
                }
            }
            IcalItem::Component(child) if name_of(child) != "VTIMEZONE" => zones_named(child, out),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};

    use super::{
        super::{
            expand, read, remove,
            tests::{ROMANCE, object},
            write,
        },
        EventConflictKind, EventSide, merge, resolve,
    };

    /// An entry that does not recur, as both sides last agreed on it.
    fn single() -> String {
        object(
            "BEGIN:VEVENT\r\nUID:e\r\nDTSTAMP:20260101T000000Z\r\n\
             DTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n\
             SUMMARY:Standup\r\nLOCATION:Room 1\r\nEND:VEVENT\r\n",
        )
    }

    /// A weekly series of six Mondays from 5 January 2026, and whatever
    /// overrides follow it.
    fn weekly(overrides: &str) -> String {
        object(&format!(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTAMP:20260101T000000Z\r\n\
             DTSTART:20260105T090000\r\nDTEND:20260105T100000\r\nSUMMARY:Standup\r\n\
             RRULE:FREQ=WEEKLY;COUNT=6\r\nEND:VEVENT\r\n{overrides}"
        ))
    }

    /// The second Monday moved to the Tuesday.
    const MOVED: &str = "BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260112T090000\r\n\
         DTSTAMP:20260101T000000Z\r\nDTSTART:20260113T090000\r\nDTEND:20260113T100000\r\n\
         SUMMARY:Standup, moved\r\nEND:VEVENT\r\n";

    /// An edit the entry page makes, stamped at `stamp`.
    fn edited(ical: &str, mut edit: Value, stamp: &str) -> String {
        edit["stamp"] = json!(stamp);
        write(ical, &edit.to_string()).unwrap()
    }

    /// The same, on the occurrence of the 12th alone.
    fn edited_twelfth(ical: &str, mut edit: Value, stamp: &str) -> String {
        edit["scope"] = json!("this");
        edit["recurrenceId"] = json!("20260112T090000");
        edited(ical, edit, stamp)
    }

    #[test]
    fn a_title_here_and_a_place_there_merge_with_no_question() {
        let base = single();
        let local = edited(
            &base,
            json!({"summary": "Standup, late"}),
            "20260201T100000Z",
        );
        let remote = edited(&base, json!({"location": "Room 2"}), "20260201T110000Z");

        let merged = merge(&base, &local, &remote).unwrap();

        assert!(merged.resolved);
        assert!(merged.conflicts.is_empty());
        let entry = read(&merged.ical, "").unwrap();
        assert_eq!(entry.summary, "Standup, late");
        assert_eq!(entry.location, "Room 2");
        // Both sides stamped their write, which is no question either: the
        // later stamp stands, once.
        assert_eq!(merged.ical.matches("DTSTAMP").count(), 1);
        assert!(merged.ical.contains("DTSTAMP:20260201T110000Z\r\n"));
        assert!(merged.ical.contains("LAST-MODIFIED:20260201T110000Z\r\n"));
    }

    #[test]
    fn two_titles_are_asked_the_newer_one_held() {
        let base = single();
        let local = edited(&base, json!({"summary": "Here"}), "20260201T100000Z");
        let remote = edited(&base, json!({"summary": "There"}), "20260201T110000Z");

        let merged = merge(&base, &local, &remote).unwrap();

        assert!(!merged.resolved);
        assert_eq!(merged.conflicts.len(), 1);
        let conflict = &merged.conflicts[0];
        assert_eq!(conflict.kind, EventConflictKind::Field);
        assert_eq!(conflict.field, "summary");
        assert_eq!(conflict.component, "VEVENT");
        assert!(conflict.recurrence_id.is_none());
        let offered: Vec<_> = conflict
            .choices
            .iter()
            .map(|choice| (choice.side, choice.value.as_str()))
            .collect();
        assert_eq!(
            offered,
            [(EventSide::Remote, "There"), (EventSide::Local, "Here")]
        );
        assert_eq!(read(&merged.ical, "").unwrap().summary, "There");

        // Picking the other side is the resolution, and nothing else moves.
        let picked = resolve(&base, &local, &remote, r#"{"0": "local"}"#).unwrap();
        let entry = read(&picked, "").unwrap();
        assert_eq!(entry.summary, "Here");
        assert_eq!(entry.location, "Room 1");
        assert!(picked.contains("LAST-MODIFIED:20260201T110000Z\r\n"));

        let kept = resolve(&base, &local, &remote, "{}").unwrap();
        assert_eq!(read(&kept, "").unwrap().summary, "There");
    }

    #[test]
    fn the_newer_side_is_told_by_dtstamp_without_last_modified() {
        let base = single();
        // Edited here an hour earlier, and elsewhere by a client that writes
        // DTSTAMP alone.
        let local = edited(&base, json!({"summary": "Here"}), "20260201T100000Z");
        let remote = base
            .replace("DTSTAMP:20260101T000000Z", "DTSTAMP:20260201T110000Z")
            .replace("SUMMARY:Standup", "SUMMARY:There");

        let merged = merge(&base, &local, &remote).unwrap();
        assert_eq!(merged.conflicts[0].choices[0].side, EventSide::Remote);
        assert_eq!(read(&merged.ical, "").unwrap().summary, "There");

        // The other way round, the edit here is the later one.
        let remote = remote.replace("DTSTAMP:20260201T110000Z", "DTSTAMP:20260201T090000Z");
        let merged = merge(&base, &local, &remote).unwrap();
        assert_eq!(merged.conflicts[0].choices[0].side, EventSide::Local);
        assert_eq!(read(&merged.ical, "").unwrap().summary, "Here");
    }

    #[test]
    fn an_override_edited_on_both_sides_is_asked_on_its_occurrence() {
        let base = weekly(MOVED);
        let local = edited_twelfth(&base, json!({"summary": "Here"}), "20260201T100000Z");
        let remote = edited_twelfth(&base, json!({"summary": "There"}), "20260201T110000Z");

        let merged = merge(&base, &local, &remote).unwrap();

        assert_eq!(merged.conflicts.len(), 1);
        let conflict = &merged.conflicts[0];
        assert_eq!(conflict.field, "summary");
        assert_eq!(
            conflict.recurrence_id.as_ref().unwrap().time,
            "20260112T090000"
        );
        assert_eq!(
            read(&merged.ical, "20260112T090000").unwrap().summary,
            "There"
        );
        assert_eq!(read(&merged.ical, "").unwrap().summary, "Standup");
    }

    #[test]
    fn overrides_of_one_occurrence_written_apart_merge_field_by_field() {
        let base = weekly("");
        let local = edited_twelfth(&base, json!({"summary": "Retro"}), "20260201T100000Z");
        let remote = edited_twelfth(&base, json!({"location": "Room 9"}), "20260201T110000Z");

        let merged = merge(&base, &local, &remote).unwrap();

        assert!(merged.resolved, "{:?}", merged.conflicts);
        assert_eq!(merged.ical.matches("BEGIN:VEVENT").count(), 2);
        let twelfth = read(&merged.ical, "20260112T090000").unwrap();
        assert_eq!(twelfth.summary, "Retro");
        assert_eq!(twelfth.location, "Room 9");
    }

    #[test]
    fn an_override_removed_here_and_edited_there_is_kept_and_asked() {
        let base = weekly(MOVED);
        let local = remove(
            &base,
            &json!({
                "scope": "this",
                "recurrenceId": "20260112T090000",
                "stamp": "20260201T110000Z",
            })
            .to_string(),
        )
        .unwrap()
        .unwrap();
        let remote = edited_twelfth(&base, json!({"summary": "There"}), "20260201T100000Z");

        let merged = merge(&base, &local, &remote).unwrap();

        // The removal is the newer write, and still does not take the edit
        // with it: the override stays, and the occurrence is asked.
        assert!(!merged.resolved);
        assert_eq!(
            read(&merged.ical, "20260112T090000").unwrap().summary,
            "There"
        );
        let occurrence = merged
            .conflicts
            .iter()
            .find(|conflict| conflict.field == "occurrence")
            .unwrap();
        assert_eq!(
            occurrence.recurrence_id.as_ref().unwrap().time,
            "20260112T090000"
        );
        assert!(
            occurrence
                .choices
                .iter()
                .any(|choice| choice.side == EventSide::Local && choice.value.is_empty())
        );
        assert!(occurrence.choices[0].value.contains("SUMMARY:There"));
        // Kept, it is in its series again: the EXDATE its removal came with
        // goes with the removal.
        assert!(!merged.ical.contains("EXDATE"));
        let found = expand(&merged.ical, "20260101T000000", "20270101T000000").unwrap();
        assert!(found.iter().any(|one| one.summary == "There"));

        let picks = json!({occurrence.id.to_string(): "local"}).to_string();
        let removed = resolve(&base, &local, &remote, &picks).unwrap();
        assert!(removed.contains("EXDATE:20260112T090000\r\n"));
        assert_eq!(removed.matches("BEGIN:VEVENT").count(), 1);
    }

    #[test]
    fn a_rule_changed_here_and_an_occurrence_there_is_a_recurrence_conflict() {
        let base = weekly(MOVED);
        let local = base
            .replace("RRULE:FREQ=WEEKLY;COUNT=6", "RRULE:FREQ=WEEKLY;COUNT=3")
            .replacen("DTSTAMP:20260101T000000Z", "DTSTAMP:20260201T090000Z", 1);
        let remote = edited_twelfth(&base, json!({"summary": "There"}), "20260201T100000Z");

        let merged = merge(&base, &local, &remote).unwrap();

        let conflict = merged
            .conflicts
            .iter()
            .find(|conflict| conflict.kind == EventConflictKind::Recurrence)
            .unwrap();
        assert_eq!(conflict.field, "occurrence");
        assert_eq!(conflict.series, Some(EventSide::Local));
        assert_eq!(
            conflict.recurrence_id.as_ref().unwrap().time,
            "20260112T090000"
        );
        // Both changes stand until someone picks, the occurrence's newer.
        assert!(merged.ical.contains("COUNT=3"));
        assert_eq!(
            read(&merged.ical, "20260112T090000").unwrap().summary,
            "There"
        );

        // Following the series takes the occurrence as the series' side has it.
        let picks = json!({conflict.id.to_string(): "local"}).to_string();
        let followed = resolve(&base, &local, &remote, &picks).unwrap();
        assert!(followed.contains("COUNT=3"));
        assert_eq!(
            read(&followed, "20260112T090000").unwrap().summary,
            "Standup, moved"
        );
        // Taken whole, it keeps the later stamp the other side wrote on it.
        assert!(followed.contains("DTSTAMP:20260201T100000Z\r\n"));
    }

    #[test]
    fn two_creations_of_one_entry_ask_what_differs() {
        let first = single();
        let second = first
            .replace("SUMMARY:Standup", "SUMMARY:Daily")
            .replace("DTSTAMP:20260101T000000Z", "DTSTAMP:20260101T080000Z");

        let merged = merge("", &first, &second).unwrap();

        // With no base, neither title is lost: the one both share is no
        // question, the one they differ on is.
        let fields: Vec<_> = merged
            .conflicts
            .iter()
            .map(|conflict| conflict.field.as_str())
            .collect();
        assert_eq!(fields, ["summary"]);
        assert_eq!(read(&merged.ical, "").unwrap().summary, "Daily");
        assert_eq!(read(&merged.ical, "").unwrap().location, "Room 1");
    }

    /// A zone of a fixed three hours east, the object's own.
    const FIXED: &str = "BEGIN:VTIMEZONE\r\nTZID:/example.org/Fixed\r\n\
         BEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0300\r\n\
         TZOFFSETTO:+0300\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";

    #[test]
    fn a_time_is_held_whole_and_taken_with_its_zone() {
        let base = single();
        let local = edited(
            &base,
            json!({
                "start": {"time": "20260105T080000", "kind": "zoned", "tzid": "/example.org/Fixed"},
                "vtimezone": FIXED,
            }),
            "20260201T110000Z",
        );
        let remote = edited(
            &base,
            json!({
                "start": {"time": "20260105T100000", "kind": "zoned", "tzid": "/example.org/Romance"},
                "vtimezone": ROMANCE,
            }),
            "20260201T100000Z",
        );

        let merged = merge(&base, &local, &remote).unwrap();

        // The two zones are no question, the two starts one.
        assert_eq!(merged.conflicts.len(), 1);
        let conflict = &merged.conflicts[0];
        assert_eq!(conflict.field, "start");
        let sides: Vec<_> = conflict.choices.iter().map(|choice| choice.side).collect();
        assert_eq!(sides, [EventSide::Local, EventSide::Remote]);
        let there = conflict.choices[1].time.as_ref().unwrap();
        assert_eq!(there.tzid, "/example.org/Romance");
        assert_eq!(there.offset, Some(3600));
        assert!(
            merged
                .ical
                .contains("DTSTART;TZID=/example.org/Fixed:20260105T080000\r\n")
        );

        // The start taken from there brings the zone it is in.
        let picked = resolve(&base, &local, &remote, r#"{"0": "remote"}"#).unwrap();
        assert!(picked.contains("DTSTART;TZID=/example.org/Romance:20260105T100000\r\n"));
        assert!(picked.contains("BEGIN:VTIMEZONE\r\nTZID:/example.org/Romance\r\n"));
    }

    #[test]
    fn a_time_moved_here_under_a_zone_given_there_is_not_blended() {
        let base = single();
        let local = edited(
            &base,
            json!({"start": {"time": "20260105T080000"}}),
            "20260201T110000Z",
        );
        let remote = edited(
            &base,
            json!({
                "start": {"time": "20260105T100000", "kind": "zoned", "tzid": "/example.org/Romance"},
                "vtimezone": ROMANCE,
            }),
            "20260201T100000Z",
        );

        let merged = merge(&base, &local, &remote).unwrap();

        // The merge alone would write 08:00 in Romance, which neither side
        // said: the newer side's start is held whole instead.
        assert_eq!(merged.conflicts[0].field, "start");
        assert_eq!(merged.conflicts[0].choices[0].side, EventSide::Local);
        assert!(merged.ical.contains("DTSTART:20260105T080000\r\n"));
    }

    #[test]
    fn a_start_moved_here_past_the_end_set_there_asks_the_dates() {
        let base = single();
        let local = edited(
            &base,
            json!({"start": {"time": "20260105T094500"}}),
            "20260201T100000Z",
        );
        let remote = edited(
            &base,
            json!({"end": {"time": "20260105T093000"}}),
            "20260201T110000Z",
        );

        let merged = merge(&base, &local, &remote).unwrap();

        // Neither date collides, yet 09:45 to 09:30 is no span: the dates
        // are asked as one, each side's pair whole, the newer held.
        assert!(!merged.resolved);
        assert_eq!(merged.conflicts.len(), 1);
        let conflict = &merged.conflicts[0];
        assert_eq!(conflict.field, "when");
        let pairs: Vec<_> = conflict
            .choices
            .iter()
            .map(|choice| {
                (
                    choice.side,
                    choice.time.as_ref().unwrap().time.as_str(),
                    choice.end.as_ref().unwrap().time.as_str(),
                )
            })
            .collect();
        assert_eq!(
            pairs,
            [
                (EventSide::Remote, "20260105T090000", "20260105T093000"),
                (EventSide::Local, "20260105T094500", "20260105T100000"),
            ]
        );
        assert!(merged.ical.contains("DTSTART:20260105T090000\r\n"));
        assert!(merged.ical.contains("DTEND:20260105T093000\r\n"));

        let picked = resolve(&base, &local, &remote, r#"{"0": "local"}"#).unwrap();
        assert!(picked.contains("DTSTART:20260105T094500\r\n"));
        assert!(picked.contains("DTEND:20260105T100000\r\n"));
    }

    #[test]
    fn a_duration_no_longer_fitting_a_date_start_asks_the_dates() {
        let base = object(
            "BEGIN:VEVENT\r\nUID:d\r\nDTSTAMP:20260101T000000Z\r\n\
             DTSTART:20260105T090000\r\nDURATION:PT1H\r\nEND:VEVENT\r\n",
        );
        let local = base.replace("DTSTART:20260105T090000", "DTSTART;VALUE=DATE:20260105");
        let remote = base.replace("DURATION:PT1H", "DURATION:PT2H");

        let merged = merge(&base, &local, &remote).unwrap();

        assert_eq!(merged.conflicts.len(), 1);
        assert_eq!(merged.conflicts[0].field, "when");
    }
}
