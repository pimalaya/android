//! # Anchoring
//!
//! A floating time (RFC 5545 3.3.5, a local time naming no zone) as Google
//! and Graph can take it: neither stores one, and both refuse an event
//! starting at one. Before such a write every floating date-time of an
//! event is read in the phone's zone, defined by the `VTIMEZONE` Java's
//! platform rules give, and a floating `UNTIL` bounding its series is
//! written as the UTC instant it names there (RFC 5545 3.3.10).

use std::borrow::Cow;

use ical::{
    recur::IcalRecurDateTime,
    tree::{
        cst::{IcalCst, IcalItem},
        leaf::IcalLeaf,
        line::IcalLine,
        param::node::IcalParamNode,
    },
};

use crate::{
    calendar::{cst_of, line, scheduled, zone::Zones},
    error::BridgeError,
};

/// The date-time properties of an event a floating value is anchored in.
const ANCHORED: [&str; 5] = ["DTSTART", "DTEND", "RECURRENCE-ID", "EXDATE", "RDATE"];

/// `ical` with every floating date-time of its events anchored in the zone
/// `vtimezone` defines, which it then holds; in UTC when that is empty.
/// [`None`] when it holds no floating time, the object then written as it
/// stands.
pub fn anchored(ical: &str, vtimezone: &str) -> Result<Option<String>, BridgeError> {
    let mut cst = cst_of(ical)?;
    let floating = cst
        .items
        .iter()
        .filter_map(event)
        .any(|event| event.items.iter().any(is_floating));
    if !floating {
        return Ok(None);
    }

    let tzid = match vtimezone.trim() {
        "" => None,
        definition => {
            let wrapped = format!("BEGIN:VCALENDAR\r\n{definition}\r\nEND:VCALENDAR\r\n");
            let zone = cst_of(&wrapped)?
                .into_static()
                .items
                .into_iter()
                .find_map(|item| match item {
                    IcalItem::Component(zone) if is_named(&zone, "VTIMEZONE") => Some(zone),
                    _ => None,
                })
                .ok_or("The phone's zone definition is no VTIMEZONE")?;
            let tzid = line(&zone, "TZID")
                .map(|line| line.value.decode().into_owned())
                .ok_or("The phone's zone definition carries no TZID")?;
            if Zones::of_cst(&cst).find(&tzid).is_none() {
                let at = scheduled(&cst).first().copied().unwrap_or(cst.items.len());
                cst.items.insert(at, IcalItem::Component(zone));
            }
            Some(tzid)
        }
    };
    let zones = Zones::of_cst(&cst);
    let utc = |local: IcalRecurDateTime| match &tzid {
        Some(tzid) => zones
            .find(tzid)
            .map(|zone| zone.resolve(local).literal_instant(local)),
        None => Some(local.seconds()),
    };

    for item in &mut cst.items {
        let IcalItem::Component(event) = item else {
            continue;
        };
        if !is_named(event, "VEVENT") {
            continue;
        }

        let floating_start = event
            .items
            .iter()
            .any(|item| is_floating(item) && named(item, "DTSTART"));
        if floating_start {
            for item in &mut event.items {
                if let IcalItem::Prop(line) = item {
                    anchor_until(line, &utc);
                }
            }
        }

        for item in &mut event.items {
            if !is_floating(item) {
                continue;
            }
            let IcalItem::Prop(line) = item else {
                continue;
            };
            match &tzid {
                Some(tzid) => line.params.insert(
                    0,
                    IcalParamNode {
                        name: IcalLeaf(Cow::Borrowed("TZID")),
                        values: vec![IcalLeaf(Cow::Owned(tzid.clone()))],
                        escaper: Default::default(),
                    },
                ),
                None => {
                    let utc: Vec<String> =
                        values(line).into_iter().map(|value| value + "Z").collect();
                    line.value.set(&utc);
                }
            }
        }
    }

    String::from_utf8(cst.to_bytes())
        .map(Some)
        .map_err(|err| err.to_string().into())
}

/// Rewrites the floating `UNTIL` of a recurrence rule as the UTC instant
/// `utc` reads its wall time as; left as it is when the zone cannot say.
fn anchor_until(line: &mut IcalLine<'_>, utc: &impl Fn(IcalRecurDateTime) -> Option<i64>) {
    let name = line.name.get();
    if !name.eq_ignore_ascii_case("RRULE") && !name.eq_ignore_ascii_case("EXRULE") {
        return;
    }

    let raw = line.raw_value_str().into_owned();
    let Some((index, wall)) = raw.split(';').enumerate().find_map(|(index, part)| {
        let (key, value) = part.split_once('=')?;
        let value = value.trim();
        (key.trim().eq_ignore_ascii_case("UNTIL") && is_wall_time(value))
            .then(|| (index, value.to_owned()))
    }) else {
        return;
    };

    let Some(instant) = IcalRecurDateTime::parse(&wall).ok().and_then(utc) else {
        return;
    };
    let until = IcalRecurDateTime::from_seconds(instant);
    let until = format!(
        "UNTIL={:04}{:02}{:02}T{:02}{:02}{:02}Z",
        until.year, until.month, until.day, until.hour, until.minute, until.second
    );
    line.value.set_component(index, &[until]);
}

/// The event a calendar item is, if it is one.
fn event<'c, 'a>(item: &'c IcalItem<'a>) -> Option<&'c IcalCst<'a>> {
    match item {
        IcalItem::Component(event) if is_named(event, "VEVENT") => Some(event),
        _ => None,
    }
}

/// Whether a component is named `name`.
fn is_named(component: &IcalCst<'_>, name: &str) -> bool {
    component
        .begin
        .as_ref()
        .is_some_and(|begin| begin.raw_value_str().trim().eq_ignore_ascii_case(name))
}

/// Whether an item is a property named `name`.
fn named(item: &IcalItem<'_>, name: &str) -> bool {
    matches!(item, IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case(name))
}

/// The values of a list property, such as `EXDATE`.
fn values(line: &IcalLine<'_>) -> Vec<String> {
    line.raw_value_str()
        .split(',')
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .collect()
}

/// Whether an item is a date-time property of [`ANCHORED`] naming neither
/// a zone nor UTC.
fn is_floating(item: &IcalItem<'_>) -> bool {
    let IcalItem::Prop(line) = item else {
        return false;
    };
    if !ANCHORED
        .iter()
        .any(|name| line.name.get().eq_ignore_ascii_case(name))
    {
        return false;
    }

    let typed = line.params.iter().any(|param| {
        let name = param.name.get();
        name.eq_ignore_ascii_case("TZID")
            || (name.eq_ignore_ascii_case("VALUE")
                && !param
                    .values
                    .iter()
                    .all(|value| value.get().eq_ignore_ascii_case("DATE-TIME")))
    });
    if typed {
        return false;
    }

    let values = values(line);
    !values.is_empty() && values.iter().all(|value| is_wall_time(value))
}

/// Whether a value is a date-time with no `Z`: `YYYYMMDDTHHMMSS`.
fn is_wall_time(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() == 15
        && bytes[8] == b'T'
        && bytes
            .iter()
            .enumerate()
            .all(|(index, byte)| index == 8 || byte.is_ascii_digit())
}
#[cfg(test)]
mod tests {
    use crate::calendar::{anchor::anchored, tests::object};

    /// Paris from 2025 on, as Java's platform rules define it.
    const PARIS: &str = "BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\n\
         BEGIN:DAYLIGHT\r\nDTSTART:20250330T020000\r\nTZOFFSETFROM:+0100\r\n\
         TZOFFSETTO:+0200\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\n\
         BEGIN:STANDARD\r\nDTSTART:20251026T030000\r\nTZOFFSETFROM:+0200\r\n\
         TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nEND:STANDARD\r\n\
         END:VTIMEZONE\r\n";

    fn series(start: &str, until: &str) -> String {
        object(&format!(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTAMP:20261101T000000Z\r\nDTSTART{start}\r\n\
             RRULE:FREQ=DAILY;UNTIL={until}\r\nEXDATE:20260702T100000\r\nEND:VEVENT\r\n"
        ))
    }

    /// Every floating time of the event takes the zone, which the object
    /// then defines, and the floating `UNTIL` is the instant it names
    /// there: summer time in July.
    #[test]
    fn a_floating_series_takes_the_zone_and_bounds_its_rule_in_utc() {
        let ical = anchored(&series(":20260701T100000", "20260710T100000"), PARIS)
            .unwrap()
            .unwrap();

        assert!(
            ical.contains("DTSTART;TZID=Europe/Paris:20260701T100000\r\n"),
            "{ical}"
        );
        assert!(
            ical.contains("EXDATE;TZID=Europe/Paris:20260702T100000\r\n"),
            "{ical}"
        );
        assert!(ical.contains("UNTIL=20260710T080000Z"), "{ical}");
        assert_eq!(ical.matches("BEGIN:VTIMEZONE\r\n").count(), 1, "{ical}");
        assert!(ical.find("BEGIN:VTIMEZONE") < ical.find("BEGIN:VEVENT"));
    }

    /// With no zone of the phone's, a floating time is read in UTC.
    #[test]
    fn with_no_zone_a_floating_time_is_utc() {
        let ical = anchored(&series(":20260701T100000", "20260710T100000"), "")
            .unwrap()
            .unwrap();

        assert!(ical.contains("DTSTART:20260701T100000Z\r\n"), "{ical}");
        assert!(ical.contains("UNTIL=20260710T100000Z"), "{ical}");
    }

    /// An event holding no floating time is written as it stands.
    #[test]
    fn a_zoned_event_is_left_as_it_stands() {
        let zoned = series(";TZID=Europe/Paris:20260701T100000", "20260710T080000Z")
            .replace("EXDATE:", "EXDATE;TZID=Europe/Paris:");

        assert_eq!(anchored(&zoned, PARIS).unwrap(), None);
    }
}
