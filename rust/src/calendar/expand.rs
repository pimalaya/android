//! # Expansion
//!
//! One calendar object's occurrences inside a window, walked as the
//! recurrence set RFC 5545 composes and bounded against hostile rules.

use std::time::{Duration, Instant};

use ical::{
    component::{IcalComponent, IcalComponentKind},
    prop::IcalPropKind,
    recur::IcalRecurDateTime,
};
use serde::Serialize;

use crate::{
    calendar::{
        collect_scheduled, cst_of, has, series, start_of, text_of, time_of,
        zone::{EventTime, EventTimeKind, Zones},
    },
    error::BridgeError,
};

/// How many occurrences one event may contribute to a window.
///
/// A window is a month at most, so a legitimate rule cannot exceed this
/// even at `FREQ=HOURLY`; the cap is what keeps a `FREQ=SECONDLY` event
/// from filling the agenda with a million rows.
const MAX_OCCURRENCES: usize = 1024;

/// How many occurrences one series is walked through, placed or not.
///
/// A walk jumps to its window ([`IcalRecurSetExpand::skip_to`]) except
/// under `COUNT`, stepping from DTSTART: `FREQ=SECONDLY;COUNT=` from 1970
/// is billions of steps. Past this bound (about 50 ms) it places no more.
///
/// [`IcalRecurSetExpand::skip_to`]: ical::recur::set::IcalRecurSetExpand::skip_to
pub const MAX_WALKED: usize = 200_000;

/// How long one expansion may walk, all its series together: the
/// backstop of [`MAX_WALKED`] for an object carrying many series.
const MAX_EXPANSION: Duration = Duration::from_secs(2);

/// A day, in seconds: the slack an expansion's skip keeps below its
/// window for an override told on another clock.
const DAY_SECONDS: i64 = 86_400;

/// One rendered occurrence of a calendar component.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Occurrence {
    /// The component this came out of: `VEVENT`, `VTODO` or `VJOURNAL`.
    ///
    /// An agenda row shows all three alike but for its glyph, so the kind
    /// crosses rather than three separate lists.
    pub component: String,
    /// When it starts: where the rules placed it, or where an override
    /// moved it, in that override's own zone.
    pub start: EventTime,
    /// When it ends; the start on a zero-length component, which is
    /// every journal entry and most to-dos.
    pub end: EventTime,
    /// Which instance of its series it is: the value a `RECURRENCE-ID`
    /// naming it carries, in the series' zone.
    ///
    /// Absent on an entry that does not recur, which tells the page there
    /// is no series to ask about.
    pub recurrence_id: Option<EventTime>,
    /// `SUMMARY`, empty when the component carries none.
    pub summary: String,
    /// `LOCATION`, empty when the component carries none.
    pub location: String,
    /// Whether the start is a DATE rather than a DATE-TIME.
    pub all_day: bool,
}

/// One object's occurrences starting in `[from, until)`, `YYYYMMDD[THHMMSS]`.
///
/// A series is the RFC 5545 3.8.5 set: rules and dates less `EXDATE`s, each
/// override replacing or cancelling its instance. Stamps are civil, compared
/// as written: a caller widens the window by its largest offset and sorts.
pub fn expand(ical: &str, from: &str, until: &str) -> Result<Vec<Occurrence>, BridgeError> {
    let from = IcalRecurDateTime::parse(from).map_err(|err| err.to_string())?;
    let until = IcalRecurDateTime::parse(until).map_err(|err| err.to_string())?;

    let cst = cst_of(ical)?;
    let decoded = cst.decode();
    let zones = Zones::of(&decoded);

    let mut found = Vec::new();
    for component in &decoded.components {
        collect_scheduled(component, &mut found);
    }

    let window = Window {
        from,
        until,
        deadline: Instant::now() + MAX_EXPANSION,
    };
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

        // NOTE: an override is placed by its series' walk. One whose series
        // the object does not hold (an invitation to a single instance)
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
    /// When the expansion stops walking ([`MAX_EXPANSION`]).
    deadline: Instant,
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

        // NOTE: RFC 5545 recurs the start and keeps the duration, so only
        // the start needs expanding.
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

        let (set, replaced) = series::set_of(component, overrides, zones);
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
        let cutoff = replaced
            .iter()
            .filter(|(_, over)| moved_of(over, kind).is_some_and(|(_, at)| at < self.until))
            .map(|(id, _)| IcalRecurDateTime::from_seconds(id.seconds() + 1))
            .fold(self.until, IcalRecurDateTime::max);

        // NOTE: the walk jumps to the first identity whose occurrence can
        // start in the window: one length below it, less the furthest an
        // override moves an instance forward (its own, or a range's), and a
        // day for an override told on another clock.
        let forward = replaced
            .iter()
            .filter_map(|(id, over)| Some(moved_of(over, kind)?.1.seconds() - id.seconds()))
            .chain(
                set.overrides
                    .iter()
                    .map(|over| over.start.seconds() - over.id.seconds()),
            )
            .fold(0, i64::max);
        let skip = self.from.seconds() - length - forward - DAY_SECONDS;

        let mut walk = match zones.find(&start.tzid) {
            Some(zone) if start.kind == EventTimeKind::Zoned => set.expand_in_zone(zone),
            _ => set.expand(),
        };
        // NOTE: a rule bounded by `COUNT` cannot jump, and skipping it
        // walks every earlier occurrence unbounded: it is walked instead,
        // under [`MAX_WALKED`].
        if set
            .rules
            .iter()
            .chain(&set.exrules)
            .all(|rule| rule.count.is_none())
        {
            walk.skip_to(IcalRecurDateTime::from_seconds(skip));
        }

        let mut placed = 0;
        let walk = walk.take_while(|occurrence| occurrence.id < cutoff);
        for (walked, occurrence) in walk.take(MAX_WALKED).enumerate() {
            // NOTE: the clock is read once every 4096 steps, a read per
            // step costing more than the step.
            if walked % 4096 == 4095 && Instant::now() >= self.deadline {
                break;
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
                    let Some((moved, at)) = moved_of(over, kind).filter(|(_, at)| self.holds(*at))
                    else {
                        continue;
                    };
                    let finish = end_of(over, kind).unwrap_or_else(|| {
                        moved.at(IcalRecurDateTime::from_seconds(at.seconds() + length))
                    });
                    out.push(rendered(over, moved, finish, id));
                }
                None if !self.holds(occurrence.start) => continue,
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

/// Where an override moved its instance to, read off its own start as
/// written: the recurrence set tells it on the series' clock instead.
fn moved_of(
    over: &IcalComponent,
    kind: IcalComponentKind,
) -> Option<(EventTime, IcalRecurDateTime)> {
    let moved = start_of(over, kind)?;
    let at = moved.civil()?;
    Some((moved, at))
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

#[cfg(test)]
mod tests {
    use crate::calendar::{
        expand::*,
        tests::{ROMANCE, object},
    };

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
        // NOTE: each one names the instance it is, which is what an edit of
        // it alone is written against.
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
        // NOTE: the start keeps its Z as what it is relative to, the time
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
        // NOTE: the due date placed the row, so it cannot also end it: a
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
        // NOTE: a time zone definition carries DTSTARTs of its own (when
        // its offsets take effect), and expanding those would put a row on
        // the agenda for every zone change since 1970.
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

        // NOTE: the second occurrence crosses midnight as the first does.
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
        // NOTE: a weekly series whose Tuesday occurrence an override moved
        // to Wednesday.
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
        // NOTE: still the Tuesday instance, which is what an edit of it
        // names.
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
        // NOTE: written by another client: from the third Monday on, an
        // hour later.
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

        // NOTE: summer time, then winter time from the last Sunday of
        // October: the zone's own rules, with no database behind them.
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

        // NOTE: RFC 5545 3.3.5: a skipped time is read at the offset before
        // the gap, a repeated one at its first occurrence.
        assert_eq!(found[0].start.offset, Some(3600));
        assert_eq!(found[1].start.offset, Some(7200));
    }

    #[test]
    fn a_utc_until_against_a_zoned_start_keeps_its_last_occurrence() {
        // NOTE: RFC 5545 3.3.10 has a zoned series end on a UTC time: 07:00Z
        // is 09:00 in Paris in summer, which a comparison of the two as
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

        // NOTE: the override is written in UTC too, and still replaces the
        // instance it names rather than adding a second one.
        assert_eq!(starts(&found), ["20260706T090000", "20260708T150000"]);
    }

    #[test]
    fn a_secondly_series_from_1970_jumps_to_the_window() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:1\r\nDTSTART:19700101T000000\r\n\
             SUMMARY:Tick\r\nRRULE:FREQ=SECONDLY\r\nEND:VEVENT\r\n",
        );

        let started = Instant::now();
        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(found.len(), MAX_OCCURRENCES);
        assert_eq!(found[0].start.time, "20260101T000000");
        assert!(started.elapsed() < MAX_EXPANSION);
    }

    #[test]
    fn a_counted_secondly_series_from_1970_walks_a_bounded_number_of_steps() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:1\r\nDTSTART:19700101T000000\r\n\
             SUMMARY:Tick\r\nRRULE:FREQ=SECONDLY;COUNT=4000000000\r\nEND:VEVENT\r\n",
        );

        let started = Instant::now();
        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert!(found.is_empty());
        assert!(started.elapsed() < MAX_EXPANSION);
    }

    #[test]
    fn an_instance_moved_from_years_back_into_the_window_is_placed() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:s\r\nDTSTART:20000103T090000\r\n\
             DTEND:20000103T100000\r\nSUMMARY:Weekly\r\nRRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\n\
             BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20100104T090000\r\n\
             DTSTART:20260114T150000\r\nDTEND:20260114T160000\r\nSUMMARY:Moved\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert!(found.iter().any(|occurrence| occurrence.summary == "Moved"
            && occurrence.start.time == "20260114T150000"));
        assert_eq!(found.iter().filter(|o| o.summary == "Weekly").count(), 4);
    }

    #[test]
    fn a_secondly_series_starting_in_the_window_still_places_its_cap() {
        let ical = object(
            "BEGIN:VEVENT\r\nUID:1\r\nDTSTART:20260105T090000\r\n\
             SUMMARY:Tick\r\nRRULE:FREQ=SECONDLY\r\nEND:VEVENT\r\n",
        );

        let found = expand(&ical, "20260101T000000", "20260201T000000").unwrap();

        assert_eq!(found.len(), MAX_OCCURRENCES);
    }
}
