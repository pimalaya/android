package org.pimalaya.calendar;

import static org.junit.Assert.assertEquals;

import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.calendar.CalendarMapping.Row;
import org.pimalaya.calendar.CalendarMapping.Write;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.PhoneEdit;
import org.pimalaya.client.PhoneEvent;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the calendar mapping on a corpus.
 *
 * <p>Outcomes captured from the JSON view the records replaced
 * (calendar-corpus.txt): projections, provider rows, merges of a phone edit of
 * each field and the objects they patch, reverts, write plans, fingerprints.
 * Views and objects kept as SHA-256 prefixes, fingerprints whole.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarCorpusTest {
    private static final String OWNER = "jane@example.com";
    private static final String NOW = "20261009T120000Z";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId YORK = ZoneId.of("America/New_York");
    private static final List<CalendarMapping.Phone> PHONES =
            List.of(
                    new CalendarMapping.Phone("ev.ics", OWNER, PARIS),
                    new CalendarMapping.Phone("ev.ics", OWNER, YORK));

    private static String object(String body) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + body
                + "END:VCALENDAR\r\n";
    }

    private static String event(String body) {
        return "BEGIN:VEVENT\r\nDTSTAMP:20260101T000000Z\r\n" + body + "END:VEVENT\r\n";
    }

    private static final List<String> ICALS =
            List.of(
                    object(event("UID:a\r\nDTSTART;TZID=Europe/Paris:20261012T090000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261012T100000\r\n"
                            + "SUMMARY:Review\r\nDESCRIPTION:Line one\\nline two\r\n"
                            + "LOCATION:Room 1\r\nURL:https://example.com/a\r\n"
                            + "TRANSP:TRANSPARENT\r\nCLASS:CONFIDENTIAL\r\nCOLOR:Tomato\r\n"
                            + "ORGANIZER;CN=Jane:mailto:Jane@Example.com\r\n"
                            + "ATTENDEE;CN=Bob;ROLE=OPT-PARTICIPANT;PARTSTAT=ACCEPTED:"
                            + "mailto:bob@example.com\r\n"
                            + "ATTENDEE;ROLE=REQ-PARTICIPANT:mailto:Jane@Example.com\r\n"
                            + "ATTENDEE;CUTYPE=ROOM;PARTSTAT=TENTATIVE:mailto:room@example.com\r\n"
                            + "X-VENDOR;X-PARAM=1:kept\r\n"
                            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"
                            + "BEGIN:VALARM\r\nACTION:AUDIO\r\nTRIGGER:-P1D\r\nEND:VALARM\r\n")),
                    object(event("UID:s\r\nDTSTART;TZID=Europe/Paris:20261005T090000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261005T100000\r\n"
                            + "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=10\r\n"
                            + "EXDATE;VALUE=DATE:20261019\r\n"
                            + "RDATE;TZID=Europe/Paris:20261008T090000\r\nSUMMARY:Weekly\r\n"
                            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\n"
                            + "END:VALARM\r\n")),
                    object(event("UID:d1\r\nDTSTART;VALUE=DATE:20261012\r\n"
                            + "DTEND;VALUE=DATE:20261014\r\nSUMMARY:Trip\r\n")),
                    object(event("UID:d2\r\nDTSTART;VALUE=DATE:20261012\r\nRRULE:FREQ=YEARLY\r\n"
                            + "EXDATE;VALUE=DATE:20271012\r\nSUMMARY:Birthday\r\n")),
                    object(event("UID:f\r\nDTSTART:20261012T090000\r\nDTEND:20261012T100000\r\n"
                            + "SUMMARY:Floating\r\n")),
                    object(event("UID:u\r\nDTSTART:20261012T090000Z\r\nDTEND:20261012T100000Z\r\n"
                            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:-PT10M\r\n"
                            + "END:VALARM\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\n"
                            + "TRIGGER;VALUE=DATE-TIME:20261012T080000Z\r\nEND:VALARM\r\n")),
                    object(event("UID:ud\r\nDTSTART:20261001T080000Z\r\nDURATION:PT30M\r\n"
                            + "RRULE:FREQ=DAILY;UNTIL=20261031T080000Z\r\nSUMMARY:Standup\r\n")),
                    object(event("UID:o\r\nDTSTART;TZID=Europe/Paris:20261005T090000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261005T100000\r\n"
                            + "RRULE:FREQ=WEEKLY;UNTIL=20261231T225959Z\r\nSUMMARY:Sync\r\n"
                            + "ORGANIZER:mailto:boss@example.com\r\n"
                            + "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:jane@example.com\r\n"
                            + "ATTENDEE;PARTSTAT=DECLINED:mailto:bob@example.com\r\n")
                            + event("UID:o\r\nRECURRENCE-ID;TZID=Europe/Paris:20261012T090000\r\n"
                            + "DTSTART;TZID=Europe/Paris:20261012T110000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261012T120000\r\nSUMMARY:Sync later\r\n")
                            + event("UID:o\r\nRECURRENCE-ID;TZID=Europe/Paris:20261019T090000\r\n"
                            + "DTSTART;TZID=Europe/Paris:20261019T090000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261019T100000\r\nSTATUS:CANCELLED\r\n")),
                    object(event("UID:m\r\nRECURRENCE-ID:20261012T090000Z\r\n"
                            + "DTSTART:20261012T090000Z\r\nSUMMARY:One of many\r\n")
                            + event("UID:m\r\nRECURRENCE-ID;TZID=Europe/Paris:20261019T110000\r\n"
                            + "DTSTART;TZID=Europe/Paris:20261019T110000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261019T120000\r\nSUMMARY:Two\r\n")),
                    object("BEGIN:VTIMEZONE\r\nTZID:/example.org/Romance\r\nBEGIN:DAYLIGHT\r\n"
                            + "DTSTART:19810329T020000\r\nTZOFFSETFROM:+0100\r\n"
                            + "TZOFFSETTO:+0200\r\n"
                            + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\n"
                            + "BEGIN:STANDARD\r\nDTSTART:19961027T030000\r\nTZOFFSETFROM:+0200\r\n"
                            + "TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                            + "END:STANDARD\r\nEND:VTIMEZONE\r\n"
                            + event("UID:r\r\nDTSTART;TZID=/example.org/Romance:20261012T090000\r\n"
                            + "DTEND;TZID=/example.org/Romance:20261012T100000\r\n"
                            + "RRULE:FREQ=WEEKLY;COUNT=5\r\n"
                            + "EXDATE;TZID=/example.org/Romance:20261019T090000\r\n")),
                    object(event("UID:l\r\nDTSTART:20261005T090000Z\r\nDTEND:20261005T100000Z\r\n"
                            + "RRULE:FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO\r\nSUMMARY:Old\r\n")),
                    object(event("UID:st\r\nDTSTART:20261012T090000Z\r\nDTEND:20261012T100000Z\r\n"
                            + "STATUS:TENTATIVE\r\nCLASS:PRIVATE\r\nCOLOR:#ff0000\r\n")),
                    object(event("UID:sc\r\nDTSTART:20261012T090000Z\r\nDTEND:20261012T100000Z\r\n"
                            + "STATUS:CANCELLED\r\nCLASS:PUBLIC\r\nTRANSP:OPAQUE\r\n")),
                    object(event("UID:fs\r\nDTSTART:20261005T180000\r\nDTEND:20261005T190000\r\n"
                            + "RRULE:FREQ=WEEKLY;UNTIL=20261130T180000\r\n"
                            + "RDATE:20261008T180000\r\nEXDATE:20261012T180000\r\n")),
                    object(event("UID:x\r\nDTSTART;TZID=Europe/Paris:20261012T090000\r\n"
                            + "DTEND;TZID=America/New_York:20261012T100000\r\n"
                            + "SUMMARY:Flight\r\n")),
                    object(event("UID:du\r\nDTSTART;TZID=America/New_York:20261012T090000\r\n"
                            + "DURATION:PT90M\r\nSUMMARY:Long\r\n")),
                    object(event("UID:ad\r\nDTSTART;VALUE=DATE:20261005\r\n"
                            + "DTEND;VALUE=DATE:20261006\r\nRRULE:FREQ=WEEKLY;UNTIL=20261130\r\n"
                            + "RDATE;VALUE=DATE:20261008\r\n")
                            + event("UID:ad\r\nRECURRENCE-ID;VALUE=DATE:20261012\r\n"
                            + "DTSTART;VALUE=DATE:20261013\r\nDTEND;VALUE=DATE:20261014\r\n"
                            + "SUMMARY:Moved day\r\n")),
                    object(event("UID:w\r\nDTSTART;TZID=W. Europe Standard Time:20261012T090000\r\n"
                            + "DTEND;TZID=W. Europe Standard Time:20261012T100000\r\n"
                            + "SUMMARY:Windows\r\n")),
                    object(event("UID:n\r\nSUMMARY:No start\r\n")),
                    "not an object",
                    "");

    /** Phone edits, each on a copy of an object's rows: name and change. */
    private static final Map<String, Consumer<List<Row>>> EDITS = new LinkedHashMap<>();

    static {
        EDITS.put("none", rows -> {});
        EDITS.put("title", rows -> put(rows, Events.TITLE, "Retro"));
        EDITS.put("description", rows -> put(rows, Events.DESCRIPTION, "Other"));
        EDITS.put("location", rows -> put(rows, Events.EVENT_LOCATION, ""));
        EDITS.put("url", rows -> rows.get(0).url = "https://example.com/new");
        EDITS.put("url-cleared", rows -> rows.get(0).url = null);
        EDITS.put("status-tentative", rows -> put(rows, Events.STATUS, Events.STATUS_TENTATIVE));
        EDITS.put("status-cancelled", rows -> put(rows, Events.STATUS, Events.STATUS_CANCELED));
        EDITS.put("status-confirmed", rows -> put(rows, Events.STATUS, Events.STATUS_CONFIRMED));
        EDITS.put("status-null", rows -> put(rows, Events.STATUS, null));
        EDITS.put("free", rows -> put(rows, Events.AVAILABILITY, Events.AVAILABILITY_FREE));
        EDITS.put("busy", rows -> put(rows, Events.AVAILABILITY, Events.AVAILABILITY_BUSY));
        EDITS.put("public", rows -> put(rows, Events.ACCESS_LEVEL, Events.ACCESS_PUBLIC));
        EDITS.put("private", rows -> put(rows, Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE));
        EDITS.put("default", rows -> put(rows, Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT));
        EDITS.put(
                "confidential",
                rows -> put(rows, Events.ACCESS_LEVEL, Events.ACCESS_CONFIDENTIAL));
        EDITS.put("color", rows -> put(rows, Events.EVENT_COLOR_KEY, "teal"));
        EDITS.put("color-cleared", rows -> put(rows, Events.EVENT_COLOR_KEY, null));
        EDITS.put("organizer", rows -> put(rows, Events.ORGANIZER, "other@example.com"));
        EDITS.put("moved", rows -> {
            shift(rows, Events.DTSTART, 3_600_000L);
            shift(rows, Events.DTEND, 3_600_000L);
        });
        EDITS.put("longer", rows -> {
            shift(rows, Events.DTEND, 1_800_000L);
            if (rows.get(0).values.get(Events.DURATION) != null) {
                put(rows, Events.DURATION, "PT2H");
            }
        });
        EDITS.put("new-york", rows -> {
            put(rows, Events.EVENT_TIMEZONE, "America/New_York");
            put(rows, Events.EVENT_END_TIMEZONE, "America/New_York");
        });
        EDITS.put("utc-zone", rows -> {
            put(rows, Events.EVENT_TIMEZONE, "UTC");
            put(rows, Events.EVENT_END_TIMEZONE, "UTC");
        });
        EDITS.put("all-day", rows -> {
            put(rows, Events.ALL_DAY, 1);
            put(rows, Events.EVENT_TIMEZONE, "UTC");
            put(rows, Events.DTSTART, at("2026-10-12T00:00", ZoneId.of("UTC")));
            if (rows.get(0).values.get(Events.DURATION) == null) {
                put(rows, Events.DTEND, at("2026-10-13T00:00", ZoneId.of("UTC")));
            } else {
                put(rows, Events.DURATION, "P1D");
            }
        });
        EDITS.put("timed", rows -> {
            put(rows, Events.ALL_DAY, 0);
            put(rows, Events.EVENT_TIMEZONE, "Europe/Paris");
            put(rows, Events.DTSTART, at("2026-10-12T15:00", PARIS));
            if (rows.get(0).values.get(Events.DURATION) == null) {
                put(rows, Events.DTEND, at("2026-10-12T16:00", PARIS));
            } else {
                put(rows, Events.DURATION, "PT1H");
            }
        });
        EDITS.put("rrule", rows -> rule(rows, "FREQ=DAILY;COUNT=3"));
        EDITS.put("rrule-until-date", rows -> rule(rows, "FREQ=WEEKLY;UNTIL=20261231"));
        EDITS.put("rrule-until-utc", rows -> rule(rows, "FREQ=WEEKLY;UNTIL=20261231T000000Z"));
        EDITS.put("rrule-reordered", rows -> {
            Object rule = rows.get(0).values.get(Events.RRULE);
            if (rule != null) {
                String[] parts = String.valueOf(rule).split(";");
                Collections.reverse(Arrays.asList(parts));
                put(rows, Events.RRULE, String.join(";", parts) + ";INTERVAL=1;WKST=MO");
            }
        });
        EDITS.put("rrule-removed", rows -> {
            put(rows, Events.RRULE, null);
            put(rows, Events.RDATE, null);
            put(rows, Events.EXDATE, null);
        });
        EDITS.put("rdate", rows -> cell(rows, Events.RDATE, "20261110T080000Z"));
        EDITS.put("exdate", rows -> cell(rows, Events.EXDATE, "20261026T080000Z"));
        EDITS.put("exdate-day", rows -> cell(rows, Events.EXDATE, "20261026"));
        EDITS.put("exdate-cleared", rows -> put(rows, Events.EXDATE, null));
        EDITS.put("reminder", rows -> rows.get(0).reminders.add(
                reminder(30, Reminders.METHOD_ALERT)));
        EDITS.put("reminder-default", rows -> rows.get(0).reminders.add(
                reminder(15, Reminders.METHOD_DEFAULT)));
        EDITS.put("reminder-email", rows -> rows.get(0).reminders.add(0,
                reminder(60, Reminders.METHOD_EMAIL)));
        EDITS.put("reminders-cleared", rows -> rows.get(0).reminders.clear());
        EDITS.put("attendee", rows -> rows.get(0).attendees.add(
                guest("new@example.com", "New", Attendees.TYPE_REQUIRED,
                        Attendees.ATTENDEE_STATUS_NONE, Attendees.RELATIONSHIP_ATTENDEE)));
        EDITS.put("resource", rows -> rows.get(0).attendees.add(
                guest("hall@example.com", "", Attendees.TYPE_RESOURCE,
                        Attendees.ATTENDEE_STATUS_ACCEPTED, Attendees.RELATIONSHIP_ATTENDEE)));
        EDITS.put("attendee-removed", rows -> {
            if (!rows.get(0).attendees.isEmpty()) {
                rows.get(0).attendees.remove(0);
            }
        });
        EDITS.put("answered", rows -> {
            for (Map<String, Object> guest : rows.get(0).attendees) {
                guest.put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_DECLINED);
            }
        });
        EDITS.put("organizer-row", rows -> {
            rows.get(0).attendees.add(
                    guest(OWNER, "", Attendees.TYPE_NONE, Attendees.ATTENDEE_STATUS_ACCEPTED,
                            Attendees.RELATIONSHIP_ORGANIZER));
            put(rows, Events.ORGANIZER, OWNER);
        });
        EDITS.put("dtend-zeroed", rows -> {
            if (rows.get(0).values.get(Events.DURATION) != null) {
                put(rows, Events.DTEND, 0L);
            }
        });
        EDITS.put("etar-duration", rows -> {
            Object duration = rows.get(0).values.get(Events.DURATION);
            if (duration != null) {
                put(rows, Events.DURATION,
                        "P" + CalendarMapping.seconds(String.valueOf(duration)) + "S");
            }
        });
        EDITS.put("exception-moved", rows -> exception(rows, 0, 7_200_000L, -1));
        EDITS.put("exception-second", rows -> exception(rows, 1, 0L, -1));
        EDITS.put("exception-untouched", rows -> exception(rows, 0, 0L, -1));
        EDITS.put(
                "exception-cancelled",
                rows -> exception(rows, 0, 0L, Events.STATUS_CANCELED));
        EDITS.put("override-title", rows -> {
            if (rows.size() > 1) {
                rows.get(1).values.put(Events.TITLE, "Changed one");
            }
        });
        EDITS.put("override-moved", rows -> {
            if (rows.size() > 1) {
                shift(rows.subList(1, 2), Events.DTSTART, 1_800_000L);
                shift(rows.subList(1, 2), Events.DTEND, 1_800_000L);
            }
        });
        EDITS.put("override-cancelled", rows -> {
            if (rows.size() > 1) {
                rows.get(rows.size() - 1).values.put(Events.STATUS, Events.STATUS_CANCELED);
            }
        });
        EDITS.put("override-gone", rows -> {
            if (rows.size() > 1) {
                rows.remove(rows.size() - 1);
            }
        });
    }

    /** Rows a calendar app creates, merged against an empty view. */
    private static List<Row> created() {
        List<Row> created = new ArrayList<>();
        Row timed = new Row();
        timed.values.put(Events._SYNC_ID, "new.ics");
        timed.values.put(Events.UID_2445, "new-uid");
        timed.values.put(Events.TITLE, "Lunch");
        timed.values.put(Events.DTSTART, at("2026-10-12T12:00", PARIS));
        timed.values.put(Events.DTEND, at("2026-10-12T13:00", PARIS));
        timed.values.put(Events.EVENT_TIMEZONE, "Europe/Paris");
        timed.values.put(Events.ALL_DAY, 0);
        timed.reminders.add(reminder(10, Reminders.METHOD_ALERT));
        created.add(timed);

        Row day = new Row();
        day.values.put(Events._SYNC_ID, "day.ics");
        day.values.put(Events.TITLE, "Holiday");
        day.values.put(Events.DTSTART, at("2026-10-12T00:00", ZoneId.of("UTC")));
        day.values.put(Events.DURATION, "P1D");
        day.values.put(Events.RRULE, "FREQ=YEARLY;UNTIL=20301012T000000Z");
        day.values.put(Events.EXDATE, "20271012T000000Z");
        day.values.put(Events.EVENT_TIMEZONE, "UTC");
        day.values.put(Events.ALL_DAY, 1);
        day.values.put(Events.STATUS, Events.STATUS_TENTATIVE);
        created.add(day);

        Row series = new Row();
        series.values.put(Events._SYNC_ID, "series.ics");
        series.values.put(Events.UID_2445, "series-uid");
        series.values.put(Events.DTSTART, at("2026-10-12T08:00", YORK));
        series.values.put(Events.DURATION, "P3600S");
        series.values.put(Events.RRULE, "FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261231");
        series.values.put(Events.RDATE, "20261017T120000Z");
        series.values.put(Events.EVENT_TIMEZONE, "America/New_York");
        series.values.put(Events.ALL_DAY, 0);
        series.values.put(Events.ORGANIZER, OWNER);
        series.values.put(Events.AVAILABILITY, Events.AVAILABILITY_FREE);
        series.values.put(Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE);
        series.attendees.add(
                guest("bob@example.com", "Bob", Attendees.TYPE_OPTIONAL,
                        Attendees.ATTENDEE_STATUS_INVITED, Attendees.RELATIONSHIP_ATTENDEE));
        series.url = "https://example.com/s";
        created.add(series);

        Row utc = new Row();
        utc.values.put(Events._SYNC_ID, "utc.ics");
        utc.values.put(Events.DTSTART, at("2026-10-12T08:00", ZoneId.of("UTC")));
        utc.values.put(Events.DTEND, at("2026-10-12T08:30", ZoneId.of("UTC")));
        utc.values.put(Events.EVENT_TIMEZONE, "UTC");
        utc.values.put(Events.EVENT_COLOR_KEY, "tomato");
        created.add(utc);
        return created;
    }

    @Test
    public void everyOutcomeIsTheOneCaptured() throws Exception {
        List<String> expected = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("calendar-corpus.txt");
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                expected.add(line);
            }
        }

        List<String> actual = outcomes();
        for (int index = 0; index < Math.min(expected.size(), actual.size()); index++) {
            assertEquals("line " + (index + 1), expected.get(index), actual.get(index));
        }
        assertEquals(expected.size(), actual.size());
    }

    private static List<String> outcomes() throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ICALS.size(); i++) {
            String ical = ICALS.get(i);
            PhoneEvent view;
            try {
                view = view(ical);
                // NOTE: no object, no projection: the empty view.
                out.add("project " + i + " " + (ical.isEmpty() ? "empty" : hash(canon(view))));
            } catch (Exception error) {
                out.add("project " + i + " ERR " + error.getMessage());
                continue;
            }

            for (int p = 0; p < PHONES.size(); p++) {
                CalendarMapping.Phone context = PHONES.get(p);
                List<Row> held = CalendarMapping.rows(view, context);
                out.add("rows " + i + " " + p + " " + hash(render(held)));
                List<List<Row>> starts = new ArrayList<>();
                if (!held.isEmpty()) {
                    starts.add(held);
                } else if (ical.isEmpty()) {
                    for (Row row : created()) {
                        starts.add(List.of(row));
                    }
                }

                for (int s = 0; s < starts.size(); s++) {
                    for (Map.Entry<String, Consumer<List<Row>>> edit : EDITS.entrySet()) {
                        List<Row> phone = copies(starts.get(s));
                        edit.getValue().accept(phone);
                        out.add(merged(view, ical, phone, context,
                                "merge " + i + " " + p + " " + s + " " + edit.getKey() + " "));
                        out.add("fingerprint " + CalendarMapping.fingerprint(phone));
                        out.add("plan " + hash(plan(ids(phone), held)));
                    }
                }
            }
        }
        return out;
    }

    private static PhoneEvent view(String ical) {
        return ical.isEmpty() ? PhoneEvent.EMPTY : CalendarClient.projectEvent(ical, NOW);
    }

    /** A merge's outcome: none, or the view and the object it patches. */
    private static String merged(
            PhoneEvent view, String ical, List<Row> phone, CalendarMapping.Phone context,
            String line) {
        try {
            PhoneEvent merged = CalendarMapping.merge(view, phone, context);
            line += merged == null ? "null" : hash(canon(merged)) + " " + applied(ical, merged);
            return line + " reverts=" + CalendarMapping.reverts(view, phone, context);
        } catch (Exception error) {
            return line + "ERR " + error.getClass().getSimpleName() + " " + error.getMessage();
        }
    }

    /** The object with the edit patched on, or the error it raises. */
    private static String applied(String ical, PhoneEvent merged) throws Exception {
        long since = at("2026-01-01T00:00", ZoneId.of("UTC"));
        PhoneEdit edit =
                new PhoneEdit(
                        merged,
                        NOW,
                        List.of(OWNER),
                        List.of(Zones.vtimezone(PARIS, since), Zones.vtimezone(YORK, since)));
        try {
            return hash(CalendarClient.applyEvent(ical, edit));
        } catch (Exception error) {
            return "ERR " + error.getMessage();
        }
    }

    /**
     * A view as compared: its wire keys sorted, an absent value (null, false)
     * left out.
     */
    private static String canon(Object node) throws Exception {
        if (node instanceof Record) {
            Map<String, Object> fields = new TreeMap<>();
            for (RecordComponent component : node.getClass().getRecordComponents()) {
                Object value = component.getAccessor().invoke(node);
                if (value != null && !Boolean.FALSE.equals(value)) {
                    String name = component.getName();
                    fields.put("classification".equals(name) ? "class" : name, value);
                }
            }
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<String, Object> field : fields.entrySet()) {
                out.append(field.getKey()).append('=').append(canon(field.getValue())).append(';');
            }
            return out.append('}').toString();
        }
        if (node instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object value : (List<?>) node) {
                out.append(canon(value)).append(',');
            }
            return out.append(']').toString();
        }
        if (node instanceof EventTimeKind) {
            return '"' + ((EventTimeKind) node).wire() + '"';
        }
        if (node instanceof String) {
            return '"' + (String) node + '"';
        }
        return String.valueOf(node);
    }

    private static String render(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        for (Row row : rows) {
            out.append(new TreeMap<>(row.values))
                    .append(row.reminders)
                    .append(row.attendees)
                    .append(row.url)
                    .append('\n');
        }
        return out.toString();
    }

    private static String plan(List<Row> existing, List<Row> desired) {
        StringBuilder out = new StringBuilder();
        for (Write write : CalendarMapping.plan(existing, desired)) {
            out.append(write.table).append(' ').append(write.op).append(' ').append(write.id)
                    .append(' ').append(write.parent).append(' ').append(write.event)
                    .append(' ').append(write.values).append('\n');
        }
        return out.toString();
    }

    /** The rows as a calendar app finds them, to edit. */
    private static List<Row> copies(List<Row> rows) {
        List<Row> copies = new ArrayList<>();
        for (Row row : rows) {
            Row copy = new Row();
            copy.values.putAll(row.values);
            for (Map<String, Object> reminder : row.reminders) {
                copy.reminders.add(new LinkedHashMap<>(reminder));
            }
            for (Map<String, Object> attendee : row.attendees) {
                copy.attendees.add(new LinkedHashMap<>(attendee));
            }
            copy.url = row.url;
            copies.add(copy);
        }
        return copies;
    }

    /** The rows as read back from the provider: ids on every row. */
    private static List<Row> ids(List<Row> rows) {
        List<Row> read = copies(rows);
        long id = 1;
        for (Row row : read) {
            row.id = id++;
            for (Map<String, Object> reminder : row.reminders) {
                reminder.put("_id", id++);
            }
            for (Map<String, Object> attendee : row.attendees) {
                attendee.put("_id", id++);
            }
            row.urlId = row.url == null ? -1 : id++;
        }
        return read;
    }

    private static void put(List<Row> rows, String column, Object value) {
        rows.get(0).values.put(column, value);
    }

    private static void shift(List<Row> rows, String column, long by) {
        Object value = rows.get(0).values.get(column);
        if (value != null) {
            rows.get(0).values.put(column, Long.parseLong(String.valueOf(value)) + by);
        }
    }

    private static void rule(List<Row> rows, String rule) {
        put(rows, Events.RRULE, rule);
        if (rows.get(0).values.get(Events.DURATION) == null) {
            Row row = rows.get(0);
            long start = Long.parseLong(String.valueOf(row.values.get(Events.DTSTART)));
            Object end = row.values.get(Events.DTEND);
            long length = end == null ? 3_600_000L : Long.parseLong(String.valueOf(end)) - start;
            row.values.put(Events.DTEND, null);
            row.values.put(Events.DURATION, "P" + length / 1000 + "S");
        }
    }

    private static void cell(List<Row> rows, String column, String value) {
        Object held = rows.get(0).values.get(column);
        put(rows, column, held == null ? value : held + "," + value);
    }

    /**
     * An exception row as a calendar app inserts one, copying the master's.
     *
     * <p>At the {@code nth} instance the master's rows name, shifted by
     * {@code by}, its status {@code status} unless negative.
     */
    private static void exception(List<Row> rows, int nth, long by, int status) {
        Row master = CalendarMapping.master(rows);
        if (master == null) {
            return;
        }
        long start = Long.parseLong(String.valueOf(master.values.get(Events.DTSTART)));
        long instance = start + nth * 7L * 86_400_000L;
        Row row = new Row();
        row.values.putAll(master.values);
        row.values.remove(Events._SYNC_ID);
        row.values.put(Events.ORIGINAL_SYNC_ID, "ev.ics");
        row.values.put(Events.ORIGINAL_INSTANCE_TIME, instance);
        row.values.put(Events.ORIGINAL_ALL_DAY, 0);
        row.values.put(Events.DTSTART, instance + by);
        row.values.put(Events.DTEND, instance + by + 3_600_000L);
        row.values.put(Events.DURATION, null);
        row.values.put(Events.RRULE, null);
        row.values.put(Events.STATUS, status < 0 ? Events.STATUS_TENTATIVE : status);
        row.reminders.addAll(master.reminders);
        rows.add(row);
    }

    private static Map<String, Object> reminder(long minutes, int method) {
        Map<String, Object> reminder = new LinkedHashMap<>();
        reminder.put(Reminders.MINUTES, minutes);
        reminder.put(Reminders.METHOD, method);
        return reminder;
    }

    private static Map<String, Object> guest(
            String email, String name, int type, int status, int relationship) {
        Map<String, Object> guest = new LinkedHashMap<>();
        guest.put(Attendees.ATTENDEE_EMAIL, email);
        guest.put(Attendees.ATTENDEE_NAME, name);
        guest.put(Attendees.ATTENDEE_TYPE, type);
        guest.put(Attendees.ATTENDEE_STATUS, status);
        guest.put(Attendees.ATTENDEE_RELATIONSHIP, relationship);
        return guest;
    }

    private static long at(String local, ZoneId zone) {
        return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli();
    }

    private static String hash(String text) throws Exception {
        byte[] digest =
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < 8; index++) {
            out.append(String.format("%02x", digest[index]));
        }
        return out.toString();
    }
}
