//! What a calendar date or date-time is relative to, and the offsets an
//! object's own `VTIMEZONE`s put in force.
//!
//! No time-zone database here (docs/pimalaya-android-plan.md): an IANA
//! or a Windows name is resolved on the Java side, by the platform's
//! tzdata, and what this resolves is only what the object defines for
//! itself, through ical-rs's `IcalTz`. That is enough for the two jobs
//! the library has to do on its own: hand the Java side an offset for a
//! `TZID` the platform cannot name, and compare two dates spelled in
//! different zones (a `RECURRENCE-ID` or an `EXDATE` in another zone
//! than the series). The recurrence set does the latter on its own, its
//! times told on its start's clock by ical-rs.

use ical::{
    component::{IcalComponentKind, IcalComponentName},
    ical::Ical,
    param::IcalParam,
    prop::IcalProp,
    recur::IcalRecurDateTime,
    tree::{cst::IcalCst, line::IcalLine},
    tz::IcalTz,
    value::IcalValue,
    version::IcalVersion,
};
use serde::{Deserialize, Serialize};

/// What one date or date-time is relative to (RFC 5545 3.3.4, 3.3.5).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EventTimeKind {
    /// A `DATE`: a day, in no zone at all.
    Date,
    /// A local time with no zone, read in the reader's own.
    Floating,
    /// A `Z`-suffixed UTC time.
    Utc,
    /// A local time in the zone its `TZID` names.
    Zoned,
}

/// One date or date-time of a component, as its property spells it.
///
/// The unit that crosses the bridge wherever a time does: civil, like
/// the recurrence it comes out of, plus what it is civil in. Turning it
/// into an instant is the Java side's step, which has the database.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventTime {
    /// `YYYYMMDD` for a date, `YYYYMMDDTHHMMSS` otherwise, never `Z`.
    pub time: String,
    pub kind: EventTimeKind,
    /// The `TZID`, verbatim; empty unless zoned.
    pub tzid: String,
    /// The offset in seconds east of UTC that the object's own
    /// `VTIMEZONE` puts in force at this time, when it defines the zone.
    /// A time the clock skips takes the offset before the gap and one it
    /// repeats the earlier of the two, as RFC 5545 3.3.5 says.
    pub offset: Option<i32>,
}

impl EventTime {
    /// The time a date property carries, with what it is relative to.
    pub fn of_prop(prop: &IcalProp) -> Option<Self> {
        let raw = match &prop.value {
            IcalValue::Date(value) => value.0.as_ref(),
            IcalValue::DateTime(value) => value.0.as_ref(),
            IcalValue::DateTimeList(values) => values.0.first()?.as_ref(),
            _ => return None,
        };

        let mut time = Self::of_raw(raw);
        if time.kind == EventTimeKind::Floating
            && let Some(tzid) = prop.params.iter().find_map(|param| match param {
                IcalParam::TzId(tzid) => Some(tzid.to_string()),
                _ => None,
            })
        {
            time.kind = EventTimeKind::Zoned;
            time.tzid = tzid;
        }

        Some(time)
    }

    /// The same, off a raw content line.
    pub fn of_line(line: &IcalLine) -> Option<Self> {
        Self::of_prop(&line.decode(IcalVersion::V2_0))
    }

    /// A value as written, before its `TZID` is known: a date, a UTC
    /// time or a floating one.
    fn of_raw(raw: &str) -> Self {
        let (time, kind) = match raw.strip_suffix(['Z', 'z']) {
            Some(time) => (time, EventTimeKind::Utc),
            None if raw.len() == 8 => (raw, EventTimeKind::Date),
            None => (raw, EventTimeKind::Floating),
        };

        Self {
            time: time.to_string(),
            kind,
            tzid: String::new(),
            offset: None,
        }
    }

    /// The civil moment it names, a date at midnight.
    pub fn civil(&self) -> Option<IcalRecurDateTime> {
        IcalRecurDateTime::parse(&self.time).ok()
    }

    /// Another moment in the same zone, spelled the way this one is.
    pub fn at(&self, moment: IcalRecurDateTime) -> Self {
        Self {
            time: stamp(moment, self.kind == EventTimeKind::Date),
            kind: self.kind,
            tzid: self.tzid.clone(),
            offset: None,
        }
    }

    /// The value as a property writes it, `Z` included.
    pub fn wire(&self) -> String {
        match self.kind {
            EventTimeKind::Utc => format!("{}Z", self.time),
            _ => self.time.clone(),
        }
    }
}

/// A civil moment as a stamp: `YYYYMMDD` for a date, else
/// `YYYYMMDDTHHMMSS`.
pub fn stamp(moment: IcalRecurDateTime, date: bool) -> String {
    let day = format!("{:04}{:02}{:02}", moment.year, moment.month, moment.day);
    match date {
        true => day,
        false => format!(
            "{day}T{:02}{:02}{:02}",
            moment.hour, moment.minute, moment.second
        ),
    }
}

/// The zones one calendar object defines for itself.
#[derive(Default)]
pub struct Zones(pub Vec<IcalTz>);

impl Zones {
    /// Every `VTIMEZONE` of a decoded calendar.
    pub fn of(ical: &Ical) -> Self {
        Self(
            ical.components
                .iter()
                .filter(|component| {
                    matches!(
                        component.name,
                        IcalComponentName::Kind(IcalComponentKind::VTimezone)
                    )
                })
                .filter_map(IcalTz::of_component)
                .collect(),
        )
    }

    /// The same, off a calendar's syntax tree.
    pub fn of_cst(cst: &IcalCst) -> Self {
        Self::of(&cst.decode())
    }

    /// The zone the object defines under a `TZID`.
    pub fn find(&self, tzid: &str) -> Option<&IcalTz> {
        self.0.iter().find(|zone| zone.id == tzid)
    }

    /// A time with the offset its zone puts in force, when the object
    /// defines that zone.
    ///
    /// Read as RFC 5545 3.3.5 reads a written DATE-TIME, not as 3.3.10
    /// reads a generated instance: the zoned walk has already dropped
    /// those a gap swallows, and a literal time in a gap (a `DTSTART`, an
    /// override's start) still happens, at the offset before it.
    pub fn resolve(&self, mut time: EventTime) -> EventTime {
        if time.kind == EventTimeKind::Zoned
            && let Some(local) = time.civil()
        {
            time.offset = self
                .find(&time.tzid)
                .map(|zone| zone.resolve(local).literal_offset());
        }
        time
    }

    /// The instant a civil moment of a time's zone names, in seconds since
    /// the epoch, read as a written time is; none for a date, a floating
    /// time or a zone the object does not define.
    pub fn instant(&self, zone: &EventTime, local: IcalRecurDateTime) -> Option<i64> {
        match zone.kind {
            EventTimeKind::Utc => Some(local.seconds()),
            EventTimeKind::Zoned => {
                Some(self.find(&zone.tzid)?.resolve(local).literal_instant(local))
            }
            _ => None,
        }
    }

    /// The civil moment an instant is in a time's zone.
    pub fn local(&self, zone: &EventTime, instant: i64) -> Option<IcalRecurDateTime> {
        match zone.kind {
            EventTimeKind::Utc => Some(IcalRecurDateTime::from_seconds(instant)),
            EventTimeKind::Zoned => Some(self.find(&zone.tzid)?.local(instant)),
            _ => None,
        }
    }

    /// A civil moment of one zone told in another, unchanged when either
    /// is civil (a date, a floating time) or one the object does not
    /// define: comparing them as written is then all anyone can do.
    pub fn convert(
        &self,
        local: IcalRecurDateTime,
        from: &EventTime,
        to: &EventTime,
    ) -> IcalRecurDateTime {
        if from.kind == to.kind && from.tzid == to.tzid {
            return local;
        }

        self.instant(from, local)
            .and_then(|instant| self.local(to, instant))
            .unwrap_or(local)
    }
}

#[cfg(test)]
mod tests {
    use super::{
        super::tests::{ROMANCE, object},
        *,
    };

    #[test]
    fn an_instant_just_past_a_gap_reads_on_the_clock_after_it() {
        let ical = object(ROMANCE);
        let cst = IcalCst::parse(ical.as_str()).unwrap();
        let zones = Zones::of_cst(&cst);
        let paris = EventTime {
            time: String::new(),
            kind: EventTimeKind::Zoned,
            tzid: "/example.org/Romance".into(),
            offset: None,
        };
        let at = |stamp: &str| IcalRecurDateTime::parse(stamp).unwrap();

        // 01:30 UTC on 29 March 2026 is 03:30 in Paris, the clock having
        // skipped from 02:00 to 03:00 half an hour before.
        assert_eq!(
            zones.local(&paris, at("20260329T013000").seconds()),
            Some(at("20260329T033000"))
        );
    }
}
