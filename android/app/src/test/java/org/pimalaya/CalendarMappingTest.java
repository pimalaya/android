package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.ExtendedProperties;
import android.provider.CalendarContract.Reminders;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.CalendarMapping.Row;
import org.pimalaya.CalendarMapping.Write;
import org.pimalaya.client.EventViews;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the calendar object to CalendarContract mapping
 * (docs/calendar-mapping.md): the columns an object's view projects, the
 * provider's cell and duration formats, and the field-space merge that takes
 * a field from the phone only when its rows differ from what the base itself
 * projects. Objects go through the real native projection and patch, so a
 * round trip here is the one a phone makes. Under Robolectric for the one
 * reason that the native library loads into one class loader per process.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarMappingTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String OWNER = "jane@example.com";
    private static final CalendarMapping.Phone PHONE =
            new CalendarMapping.Phone("ev.ics", OWNER, PARIS);
    private static final String NOW = "20261009T120000Z";

    private static String object(String body) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + body
                + "END:VCALENDAR\r\n";
    }

    private static JSONObject view(String ical) {
        return EventViews.projectEvent(ical, NOW);
    }

    private static List<Row> rows(String ical) throws Exception {
        return CalendarMapping.rows(view(ical), PHONE);
    }

    /** The rows as a calendar app finds them, to edit. */
    private static List<Row> phone(String ical) throws Exception {
        List<Row> copies = new ArrayList<>();
        for (Row row : rows(ical)) {
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

    private static JSONObject merge(String ical, List<Row> phone) throws Exception {
        return CalendarMapping.merge(view(ical), phone, PHONE);
    }

    /** The object with the phone's edit patched onto it, as a read does. */
    private static String applied(String ical, JSONObject merged, String... vtimezones)
            throws Exception {
        JSONObject edit = new JSONObject();
        edit.put("event", merged);
        edit.put("stamp", "20261009T120000Z");
        edit.put("addresses", new JSONArray().put(OWNER));
        JSONArray zones = new JSONArray();
        for (String zone : vtimezones) {
            zones.put(zone);
        }
        edit.put("vtimezones", zones);
        return EventViews.applyEvent(ical, edit);
    }

    private static long at(String local, ZoneId zone) {
        return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli();
    }

    private static final String MEETING =
            object(
                    "BEGIN:VEVENT\r\nUID:a\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;TZID=Europe/Paris:20261012T090000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261012T100000\r\n"
                            + "SUMMARY:Review\r\nLOCATION:Room 1\r\nURL:https://example.com/a\r\n"
                            + "TRANSP:TRANSPARENT\r\nCLASS:CONFIDENTIAL\r\nCOLOR:Tomato\r\n"
                            + "ORGANIZER;CN=Jane:mailto:Jane@Example.com\r\n"
                            + "ATTENDEE;CN=Bob;ROLE=OPT-PARTICIPANT;PARTSTAT=ACCEPTED:"
                            + "mailto:bob@example.com\r\n"
                            + "ATTENDEE;ROLE=REQ-PARTICIPANT:mailto:Jane@Example.com\r\n"
                            + "X-VENDOR;X-PARAM=1:kept\r\nATTACH:https://example.com/agenda.pdf\r\n"
                            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"
                            + "END:VEVENT\r\n");

    private static final String SERIES =
            object(
                    "BEGIN:VEVENT\r\nUID:s\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;TZID=Europe/Paris:20261005T090000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261005T100000\r\n"
                            + "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=10\r\n"
                            + "EXDATE;VALUE=DATE:20261019\r\n"
                            + "RDATE;TZID=Europe/Paris:20261008T090000\r\nSUMMARY:Weekly\r\n"
                            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"
                            + "END:VEVENT\r\n");

    @Test
    public void aSingleEventProjectsEveryColumn() throws Exception {
        List<Row> rows = rows(MEETING);

        assertEquals(1, rows.size());
        Map<String, Object> event = rows.get(0).values;
        assertEquals("ev.ics", event.get(Events._SYNC_ID));
        assertEquals("a", event.get(Events.UID_2445));
        assertEquals("Review", event.get(Events.TITLE));
        assertEquals("Room 1", event.get(Events.EVENT_LOCATION));
        assertEquals(at("2026-10-12T09:00", PARIS), event.get(Events.DTSTART));
        assertEquals("Europe/Paris", event.get(Events.EVENT_TIMEZONE));
        assertEquals(at("2026-10-12T10:00", PARIS), event.get(Events.DTEND));
        assertEquals("Europe/Paris", event.get(Events.EVENT_END_TIMEZONE));
        assertNull(event.get(Events.DURATION));
        assertNull(event.get(Events.RRULE));
        assertEquals(0, event.get(Events.ALL_DAY));
        assertEquals(Events.STATUS_CONFIRMED, event.get(Events.STATUS));
        assertEquals(Events.AVAILABILITY_FREE, event.get(Events.AVAILABILITY));
        assertEquals(Events.ACCESS_CONFIDENTIAL, event.get(Events.ACCESS_LEVEL));
        assertEquals("tomato", event.get(Events.EVENT_COLOR_KEY));
        assertEquals("Jane@Example.com", event.get(Events.ORGANIZER));
        assertEquals(1, event.get(Events.IS_ORGANIZER));
        assertEquals(1, event.get(Events.HAS_ATTENDEE_DATA));
        assertEquals("https://example.com/a", rows.get(0).url);

        assertEquals(1, rows.get(0).reminders.size());
        assertEquals(15L, rows.get(0).reminders.get(0).get(Reminders.MINUTES));
        assertEquals(Reminders.METHOD_ALERT, rows.get(0).reminders.get(0).get(Reminders.METHOD));

        List<Map<String, Object>> attendees = rows.get(0).attendees;
        assertEquals("bob@example.com", attendees.get(0).get(Attendees.ATTENDEE_EMAIL));
        assertEquals("Bob", attendees.get(0).get(Attendees.ATTENDEE_NAME));
        assertEquals(Attendees.TYPE_OPTIONAL, attendees.get(0).get(Attendees.ATTENDEE_TYPE));
        assertEquals(
                Attendees.ATTENDEE_STATUS_ACCEPTED,
                attendees.get(0).get(Attendees.ATTENDEE_STATUS));
        assertEquals(
                Attendees.RELATIONSHIP_ATTENDEE,
                attendees.get(0).get(Attendees.ATTENDEE_RELATIONSHIP));
        // NOTE: the user's own row is spelled as OWNER_ACCOUNT, which the
        // provider derives the user's answer from, case-sensitively.
        assertEquals(OWNER, attendees.get(1).get(Attendees.ATTENDEE_EMAIL));
        assertEquals(Attendees.TYPE_REQUIRED, attendees.get(1).get(Attendees.ATTENDEE_TYPE));
        assertEquals(
                Attendees.ATTENDEE_STATUS_INVITED, attendees.get(1).get(Attendees.ATTENDEE_STATUS));
        assertEquals(
                Attendees.RELATIONSHIP_ORGANIZER,
                attendees.get(1).get(Attendees.ATTENDEE_RELATIONSHIP));
    }

    @Test
    public void aSeriesCarriesADurationAndItsDatesInUtc() throws Exception {
        Map<String, Object> event = rows(SERIES).get(0).values;

        assertEquals("PT1H", event.get(Events.DURATION));
        assertNull(event.get(Events.DTEND));
        assertNull(event.get(Events.EVENT_END_TIMEZONE));
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=10", event.get(Events.RRULE));
        // NOTE: a date EXDATE on a timed series removes nothing on the
        // provider, so it is aligned to the start's time in its zone.
        assertEquals("20261019T070000Z", event.get(Events.EXDATE));
        // NOTE: DTSTART rides in the cell, or the provider drops it.
        assertEquals("20261005T070000Z,20261008T070000Z", event.get(Events.RDATE));
    }

    @Test
    public void anAllDaySeriesIsInUtcWithAWholeDayDuration() throws Exception {
        Map<String, Object> event =
                rows(object("BEGIN:VEVENT\r\nUID:d\r\nDTSTART;VALUE=DATE:20261012\r\n"
                                + "DTEND;VALUE=DATE:20261013\r\nRRULE:FREQ=YEARLY\r\n"
                                + "EXDATE;VALUE=DATE:20271012\r\nEND:VEVENT\r\n"))
                        .get(0)
                        .values;

        assertEquals(1, event.get(Events.ALL_DAY));
        assertEquals("UTC", event.get(Events.EVENT_TIMEZONE));
        assertEquals(at("2026-10-12T00:00", ZoneOffset.UTC), event.get(Events.DTSTART));
        assertEquals("P1D", event.get(Events.DURATION));
        assertEquals("20271012T000000Z", event.get(Events.EXDATE));
    }

    @Test
    public void anEventWithoutAnEndTakesTheOneRfc5545Implies() throws Exception {
        Map<String, Object> day =
                rows(object("BEGIN:VEVENT\r\nUID:d\r\nDTSTART;VALUE=DATE:20261012\r\n"
                                + "END:VEVENT\r\n"))
                        .get(0)
                        .values;

        assertEquals(at("2026-10-13T00:00", ZoneOffset.UTC), day.get(Events.DTEND));
    }

    @Test
    public void statusIsNeverNullAndEveryEnumHasItsDefault() throws Exception {
        assertEquals(Events.STATUS_CONFIRMED, CalendarMapping.status(""));
        assertEquals(Events.STATUS_TENTATIVE, CalendarMapping.status("X-SOMETHING"));
        assertEquals(Events.STATUS_CANCELED, CalendarMapping.status("cancelled"));
        assertEquals(Events.ACCESS_DEFAULT, CalendarMapping.access(""));
        assertEquals(Events.ACCESS_PRIVATE, CalendarMapping.access("X-SECRET"));
        assertEquals(Events.ACCESS_PUBLIC, CalendarMapping.access("PUBLIC"));
        assertNull(CalendarMapping.colorKey("#ff0000"));
        assertEquals("rebeccapurple", CalendarMapping.colorKey("RebeccaPurple"));
        assertEquals(
                Attendees.TYPE_RESOURCE, CalendarMapping.attendeeType("REQ-PARTICIPANT", "ROOM"));
        assertEquals(Attendees.TYPE_NONE, CalendarMapping.attendeeType("NON-PARTICIPANT", ""));
        assertEquals(Attendees.ATTENDEE_STATUS_NONE, CalendarMapping.attendeeStatus("DELEGATED"));
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED, CalendarMapping.attendeeStatus(""));
    }

    @Test
    public void durationsAreWrittenInRfcFormAndReadInEtarsToo() {
        assertEquals("PT1H", CalendarMapping.duration(3_600, false));
        assertEquals("P1DT2H30M", CalendarMapping.duration(95_400, false));
        assertEquals("PT0S", CalendarMapping.duration(0, false));
        assertEquals("P2D", CalendarMapping.duration(172_800, true));
        assertEquals("P1D", CalendarMapping.duration(0, true));

        assertEquals(3_600, CalendarMapping.seconds("P3600S"));
        assertEquals(3_600, CalendarMapping.seconds("PT1H"));
        assertEquals(86_400, CalendarMapping.seconds("P1D"));
        assertEquals(604_800, CalendarMapping.seconds("P1W"));
        assertEquals(-300, CalendarMapping.seconds("-PT5M"));
    }

    @Test
    public void aCellReadsZonedLinesBareUtcAndDates() {
        assertEquals(
                List.of(
                        at("2026-10-12T09:00", PARIS),
                        at("2026-10-13T09:00", PARIS),
                        at("2026-10-20T07:00", ZoneOffset.UTC),
                        at("2026-10-21T07:00", ZoneOffset.UTC),
                        at("2026-10-22T00:00", ZoneOffset.UTC)),
                CalendarMapping.instants(
                        "Europe/Paris;20261012T090000,20261013T090000\n"
                                + "20261020T070000Z,20261021T070000\n20261022"));
    }

    @Test
    public void anOverrideRowNamesItsInstanceInWholeSeconds() throws Exception {
        String ical =
                SERIES.replace(
                        "END:VEVENT\r\nEND:VCALENDAR",
                        "END:VEVENT\r\nBEGIN:VEVENT\r\nUID:s\r\n"
                                + "RECURRENCE-ID;VALUE=DATE:20261012\r\n"
                                + "DTSTART;TZID=Europe/Paris:20261012T110000\r\n"
                                + "DTEND;TZID=Europe/Paris:20261012T120000\r\nSUMMARY:Later\r\n"
                                + "END:VEVENT\r\nEND:VCALENDAR");

        List<Row> rows = rows(ical);

        assertEquals(2, rows.size());
        Map<String, Object> exception = rows.get(1).values;
        assertEquals("ev.ics", exception.get(Events.ORIGINAL_SYNC_ID));
        // NOTE: a date RECURRENCE-ID coerced to the series' start: the
        // instance at 09:00 Paris.
        assertEquals(at("2026-10-12T09:00", PARIS), exception.get(Events.ORIGINAL_INSTANCE_TIME));
        assertEquals(0, exception.get(Events.ORIGINAL_ALL_DAY));
        assertFalse(exception.containsKey(Events._SYNC_ID));
        assertEquals(at("2026-10-12T12:00", PARIS), exception.get(Events.DTEND));
        assertNull(exception.get(Events.DURATION));
        assertNull(exception.get(Events.RRULE));
        assertNull(exception.get(Events.EXDATE));
        assertEquals("Later", exception.get(Events.TITLE));
    }

    @Test
    public void anObjectWithoutAMasterIsAStandaloneRowPerOverride() throws Exception {
        List<Row> rows =
                rows(object("BEGIN:VEVENT\r\nUID:i\r\nRECURRENCE-ID:20261012T090000Z\r\n"
                        + "DTSTART:20261012T090000Z\r\nSUMMARY:One of many\r\nEND:VEVENT\r\n"));

        assertEquals(1, rows.size());
        assertEquals(
                "ev.ics" + CalendarMapping.STANDALONE + "20261012T090000Z",
                rows.get(0).values.get(Events._SYNC_ID));
        assertNull(rows.get(0).values.get(Events.ORIGINAL_SYNC_ID));
        assertEquals("ev.ics", CalendarMapping.itemOf("ev.ics" + CalendarMapping.STANDALONE + "x"));
    }

    @Test
    public void untouchedRowsAreNoEdit() throws Exception {
        for (String ical : new String[] {MEETING, SERIES}) {
            assertNull(merge(ical, phone(ical)));
        }
    }

    @Test
    public void aTitleChangedOnThePhoneComesBackWithEverythingElse() throws Exception {
        List<Row> phone = phone(MEETING);
        phone.get(0).values.put(Events.TITLE, "Retro");

        JSONObject merged = merge(MEETING, phone);
        String written = applied(MEETING, merged);

        assertEquals("Retro", merged.getJSONObject("master").getString("summary"));
        assertTrue(written.contains("SUMMARY:Retro\r\n"));
        assertTrue(written.contains("X-VENDOR;X-PARAM=1:kept\r\n"));
        assertTrue(written.contains("ATTACH:https://example.com/agenda.pdf\r\n"));
        assertTrue(written.contains("ATTENDEE;CN=Bob;ROLE=OPT-PARTICIPANT;PARTSTAT=ACCEPTED:"));
        assertTrue(written.contains("TRIGGER:-PT15M\r\n"));
    }

    @Test
    public void whatTheProviderRewritesOnItsOwnIsNoEdit() throws Exception {
        List<Row> phone = phone(SERIES);
        // NOTE: a tzdata update writes 0 into a recurring row's DTEND, and
        // an app may leave the default status of a row it saves.
        phone.get(0).values.put(Events.DTEND, 0L);
        phone.get(0).values.put(Events.STATUS, null);

        assertNull(merge(SERIES, phone));

        String day = object("BEGIN:VEVENT\r\nUID:d\r\nDTSTART;VALUE=DATE:20261012\r\n"
                + "RRULE:FREQ=YEARLY\r\nEND:VEVENT\r\n");
        List<Row> etar = phone(day);
        etar.get(0).values.put(Events.DURATION, "P86400S");
        assertNull(merge(day, etar));
    }

    @Test
    public void aConfirmedStatusIsNoEditATentativeOneIs() throws Exception {
        List<Row> confirmed = phone(SERIES);
        confirmed.get(0).values.put(Events.STATUS, Events.STATUS_CONFIRMED);
        List<Row> tentative = phone(SERIES);
        tentative.get(0).values.put(Events.STATUS, Events.STATUS_TENTATIVE);

        assertNull(merge(SERIES, confirmed));
        assertEquals(
                "TENTATIVE", merge(SERIES, tentative).getJSONObject("master").getString("status"));
    }

    @Test
    public void aMovedEventKeepsItsZoneAndAZoneChangeNamesTheNewOne() throws Exception {
        List<Row> moved = phone(MEETING);
        moved.get(0).values.put(Events.DTSTART, at("2026-10-12T10:00", PARIS));
        moved.get(0).values.put(Events.DTEND, at("2026-10-12T11:00", PARIS));
        List<Row> elsewhere = phone(MEETING);
        ZoneId york = ZoneId.of("America/New_York");
        elsewhere.get(0).values.put(Events.DTSTART, at("2026-10-12T09:00", york));
        elsewhere.get(0).values.put(Events.EVENT_TIMEZONE, "America/New_York");
        elsewhere.get(0).values.put(Events.DTEND, at("2026-10-12T10:00", york));
        elsewhere.get(0).values.put(Events.EVENT_END_TIMEZONE, "America/New_York");

        JSONObject kept = merge(MEETING, moved).getJSONObject("master");
        JSONObject changed = merge(MEETING, elsewhere).getJSONObject("master");

        assertEquals("20261012T100000", kept.getJSONObject("start").getString("time"));
        assertEquals("Europe/Paris", kept.getJSONObject("start").getString("tzid"));
        assertEquals("20261012T110000", kept.getJSONObject("end").getString("time"));
        assertTrue(applied(MEETING, merge(MEETING, moved))
                .contains("DTSTART;TZID=Europe/Paris:20261012T100000\r\n"));
        assertEquals("America/New_York", changed.getJSONObject("start").getString("tzid"));
        assertEquals("20261012T090000", changed.getJSONObject("start").getString("time"));
    }

    @Test
    public void aFloatingTimeIsComparedOnTheWallClock() throws Exception {
        String floating = object("BEGIN:VEVENT\r\nUID:f\r\nDTSTART:20261012T090000\r\n"
                + "DTEND:20261012T100000\r\nEND:VEVENT\r\n");
        ZoneId london = ZoneId.of("Europe/London");
        List<Row> phone = phone(floating);
        // NOTE: projected while the device was in London, not projected
        // again yet: the same wall clock, no edit.
        phone.get(0).values.put(Events.DTSTART, at("2026-10-12T09:00", london));
        phone.get(0).values.put(Events.EVENT_TIMEZONE, "Europe/London");
        phone.get(0).values.put(Events.DTEND, at("2026-10-12T10:00", london));
        phone.get(0).values.put(Events.EVENT_END_TIMEZONE, "Europe/London");

        assertNull(merge(floating, phone));
    }

    @Test
    public void aZoneOnlyTheObjectDefinesIsComparedByInstant() throws Exception {
        String romance =
                object("BEGIN:VTIMEZONE\r\nTZID:/example.org/Romance\r\nBEGIN:DAYLIGHT\r\n"
                        + "DTSTART:19810329T020000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\n"
                        + "BEGIN:STANDARD\r\nDTSTART:19961027T030000\r\nTZOFFSETFROM:+0200\r\n"
                        + "TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                        + "END:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:r\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20261012T090000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20261012T100000\r\nEND:VEVENT\r\n");
        List<Row> rows = rows(romance);
        assertEquals("Europe/Paris", rows.get(0).values.get(Events.EVENT_TIMEZONE));
        assertEquals(at("2026-10-12T09:00", PARIS), rows.get(0).values.get(Events.DTSTART));

        List<Row> phone = phone(romance);
        phone.get(0).values.put(Events.EVENT_TIMEZONE, "Europe/Brussels");
        phone.get(0).values.put(Events.EVENT_END_TIMEZONE, "Europe/Brussels");
        assertNull(merge(romance, phone));

        phone.get(0).values.put(Events.EVENT_TIMEZONE, "Europe/Paris");
        phone.get(0).values.put(Events.EVENT_END_TIMEZONE, "Europe/Paris");
        phone.get(0).values.put(Events.DTSTART, at("2026-12-14T09:00", PARIS));
        phone.get(0).values.put(Events.DTEND, at("2026-12-14T10:00", PARIS));
        String written = applied(romance, merge(romance, phone));
        // NOTE: placed by its instant in the object's own zone, at the
        // winter offset the summer start did not carry.
        assertTrue(written.contains("DTSTART;TZID=/example.org/Romance:20261214T090000\r\n"));
    }

    @Test
    public void rulesAreComparedParsedTheirEndCoerced() throws Exception {
        List<Row> fossify = phone(SERIES);
        fossify.get(0).values.put(Events.RRULE, "COUNT=10;BYDAY=MO;INTERVAL=1;FREQ=WEEKLY;WKST=MO");
        assertNull(merge(SERIES, fossify));

        String day = object("BEGIN:VEVENT\r\nUID:d\r\nDTSTART;VALUE=DATE:20261012\r\n"
                + "RRULE:FREQ=DAILY;UNTIL=20261020\r\nEND:VEVENT\r\n");
        List<Row> etar = phone(day);
        etar.get(0).values.put(Events.RRULE, "FREQ=DAILY;UNTIL=20261020T000000Z");
        assertNull(merge(day, etar));

        List<Row> ended = phone(SERIES);
        ended.get(0).values.put(Events.RRULE, "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261102");
        JSONArray rules = merge(SERIES, ended).getJSONObject("master").getJSONArray("rrules");
        // NOTE: a date UNTIL against a zoned start bounds its whole day, in
        // UTC (RFC 5545 3.3.10).
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261102T225959Z", rules.getString(0));
    }

    @Test
    public void anExdateAddedOnThePhoneJoinsTheOnesKept() throws Exception {
        List<Row> phone = phone(SERIES);
        phone.get(0).values.put(Events.EXDATE, "20261019T070000Z,20261026T080000Z");

        JSONArray exdates =
                merge(SERIES, phone).getJSONObject("master").getJSONArray("exdates");
        String written = applied(SERIES, merge(SERIES, phone));

        assertEquals(2, exdates.length());
        assertEquals("date", exdates.getJSONObject(0).getString("kind"));
        assertEquals("20261026T090000", exdates.getJSONObject(1).getString("time"));
        assertEquals("Europe/Paris", exdates.getJSONObject(1).getString("tzid"));
        assertTrue(written.contains("EXDATE;VALUE=DATE:20261019\r\n"));
        assertTrue(written.contains("EXDATE;TZID=Europe/Paris:20261026T090000\r\n"));
    }

    @Test
    public void remindersAreASetOfOffsetsTheEmailOnesThePhones() throws Exception {
        List<Row> reordered = phone(MEETING);
        Map<String, Object> email = new LinkedHashMap<>();
        email.put(Reminders.MINUTES, 60);
        email.put(Reminders.METHOD, Reminders.METHOD_EMAIL);
        reordered.get(0).reminders.add(0, email);
        assertNull(merge(MEETING, reordered));

        List<Row> added = phone(MEETING);
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put(Reminders.MINUTES, 30);
        alert.put(Reminders.METHOD, Reminders.METHOD_DEFAULT);
        added.get(0).reminders.add(alert);
        JSONArray alarms = merge(MEETING, added).getJSONObject("master").getJSONArray("alarms");

        assertEquals(2, alarms.length());
        assertEquals(15, alarms.getJSONObject(0).getInt("minutes"));
        assertEquals(30, alarms.getJSONObject(1).getInt("minutes"));
        assertTrue(applied(MEETING, merge(MEETING, added)).contains("TRIGGER:-PT30M\r\n"));
    }

    @Test
    public void anAlarmOnTheEndShowsAfterTheStartAndStaysOnTheEnd() throws Exception {
        String ical = object("BEGIN:VEVENT\r\nUID:e\r\nDTSTART:20261012T090000Z\r\n"
                + "DTEND:20261012T100000Z\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\n"
                + "TRIGGER;RELATED=END:-PT10M\r\nEND:VALARM\r\nEND:VEVENT\r\n");

        List<Row> rows = rows(ical);

        // NOTE: ten minutes before the end of an hour is fifty after the
        // start, which the provider fires as negative minutes.
        assertEquals(-50L, rows.get(0).reminders.get(0).get(Reminders.MINUTES));
        assertNull(merge(ical, phone(ical)));
    }

    @Test
    public void theOrganizerAloneAddsNothingAnAnswerChangesOneAttendee() throws Exception {
        String plain = object("BEGIN:VEVENT\r\nUID:p\r\nDTSTART:20261012T090000Z\r\n"
                + "DTEND:20261012T100000Z\r\nEND:VEVENT\r\n");
        List<Row> etar = phone(plain);
        Map<String, Object> self = new LinkedHashMap<>();
        self.put(Attendees.ATTENDEE_EMAIL, OWNER);
        self.put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ORGANIZER);
        self.put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_ACCEPTED);
        etar.get(0).attendees.add(self);
        // NOTE: the provider fills the owner into an event written without
        // an organizer.
        etar.get(0).values.put(Events.ORGANIZER, OWNER);
        assertNull(merge(plain, etar));

        List<Row> answered = phone(MEETING);
        answered.get(0).attendees.get(1).put(
                Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_ACCEPTED);
        String written = applied(MEETING, merge(MEETING, answered));
        assertTrue(written.contains(
                "ATTENDEE;CN=Bob;ROLE=OPT-PARTICIPANT;PARTSTAT=ACCEPTED:"
                        + "mailto:bob@example.com\r\n"));
        assertTrue(written.contains(
                "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:Jane@Example.com\r\n"));
    }

    /** An exception row as a calendar app inserts one: the master's row copied. */
    private static Row exception(String ical, long instance) throws Exception {
        Row master = phone(ical).get(0);
        Row row = new Row();
        row.values.putAll(master.values);
        row.values.remove(Events._SYNC_ID);
        row.values.put(Events.ORIGINAL_SYNC_ID, "ev.ics");
        row.values.put(Events.ORIGINAL_INSTANCE_TIME, instance);
        row.values.put(Events.ORIGINAL_ALL_DAY, 0);
        row.values.put(Events.DTSTART, instance);
        row.values.put(Events.DTEND, instance + 3_600_000L);
        row.values.put(Events.DURATION, null);
        row.values.put(Events.RRULE, null);
        // NOTE: the provider's exception path defaults the status.
        row.values.put(Events.STATUS, Events.STATUS_TENTATIVE);
        row.reminders.addAll(master.reminders);
        return row;
    }

    @Test
    public void anOccurrenceMovedOnThePhoneBecomesAnOverrideOfWhatMoved() throws Exception {
        long instance = at("2026-10-12T09:00", PARIS);
        Row moved = exception(SERIES, instance);
        moved.values.put(Events.DTSTART, at("2026-10-12T11:00", PARIS));
        moved.values.put(Events.DTEND, at("2026-10-12T12:00", PARIS));
        List<Row> phone = phone(SERIES);
        phone.add(moved);

        JSONObject merged = merge(SERIES, phone);
        JSONObject over = merged.getJSONArray("overrides").getJSONObject(0);
        String written = applied(SERIES, merged);

        assertEquals("20261012T090000", over.getJSONObject("recurrenceId").getString("time"));
        assertEquals("20261012T110000", over.getJSONObject("start").getString("time"));
        assertFalse("the provider's default status is no edit", over.has("status"));
        assertFalse(over.has("summary"));
        assertTrue(written.contains("RECURRENCE-ID;TZID=Europe/Paris:20261012T090000\r\n"));
        assertTrue(written.contains("DTSTART;TZID=Europe/Paris:20261012T110000\r\n"));
    }

    @Test
    public void anUntouchedNewExceptionRowAddsAnOverrideOfNothing() throws Exception {
        List<Row> phone = phone(SERIES);
        phone.add(exception(SERIES, at("2026-10-12T09:00", PARIS)));

        JSONObject over = merge(SERIES, phone).getJSONArray("overrides").getJSONObject(0);

        assertEquals("the recurrence id alone", 1, over.length());
    }

    @Test
    public void anOccurrenceCancelledOnThePhoneIsAnExdateOnce() throws Exception {
        Row cancelled = exception(SERIES, at("2026-10-12T09:00", PARIS));
        cancelled.values.put(Events.STATUS, Events.STATUS_CANCELED);
        List<Row> phone = phone(SERIES);
        phone.add(cancelled);

        String written = applied(SERIES, merge(SERIES, phone));
        assertTrue(written.contains("EXDATE;TZID=Europe/Paris:20261012T090000\r\n"));
        assertFalse(written.contains("RECURRENCE-ID"));

        // NOTE: the cancelled row the phone keeps after that is the EXDATE
        // the object now holds.
        List<Row> after = phone(written);
        Row kept = exception(written, at("2026-10-12T09:00", PARIS));
        kept.values.put(Events.STATUS, Events.STATUS_CANCELED);
        after.add(kept);
        assertNull(merge(written, after));
    }

    @Test
    public void anExceptionRowGoneRevertsItsInstance() throws Exception {
        String ical =
                SERIES.replace(
                        "END:VEVENT\r\nEND:VCALENDAR",
                        "END:VEVENT\r\nBEGIN:VEVENT\r\nUID:s\r\n"
                                + "RECURRENCE-ID;TZID=Europe/Paris:20261012T090000\r\n"
                                + "DTSTART;TZID=Europe/Paris:20261012T110000\r\n"
                                + "END:VEVENT\r\nEND:VCALENDAR");
        List<Row> phone = phone(ical);
        phone.remove(1);

        JSONObject merged = merge(ical, phone);

        assertEquals(0, merged.getJSONArray("overrides").length());
        assertFalse(applied(ical, merged).contains("RECURRENCE-ID"));
    }

    @Test
    public void aListedSeriesTakesNoDateFromThePhoneAndIsPutBack() throws Exception {
        String ical = object("BEGIN:VEVENT\r\nUID:l\r\nDTSTART:20261005T090000Z\r\n"
                + "DTEND:20261005T100000Z\r\nRRULE:FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO\r\n"
                + "SUMMARY:Old\r\nEND:VEVENT\r\n");
        assertTrue(view(ical).optBoolean("listed"));
        List<Row> phone = phone(ical);
        assertNull(phone.get(0).values.get(Events.RRULE));
        phone.get(0).values.put(Events.TITLE, "New");
        phone.get(0).values.put(Events.DTSTART, (Long) phone.get(0).values.get(Events.DTSTART)
                + 3_600_000L);

        JSONObject master = merge(ical, phone).getJSONObject("master");

        assertEquals("New", master.getString("summary"));
        assertEquals("20261005T090000", master.getJSONObject("start").getString("time"));
        assertTrue(CalendarMapping.reverts(view(ical), phone, PHONE));
        assertFalse(CalendarMapping.reverts(view(ical), phone(ical), PHONE));
    }

    @Test
    public void anEventCreatedOnThePhoneBecomesAnObject() throws Exception {
        Row created = new Row();
        created.values.put(Events._SYNC_ID, "new.ics");
        created.values.put(Events.UID_2445, "new-uid");
        created.values.put(Events.TITLE, "Lunch");
        created.values.put(Events.DTSTART, at("2026-10-12T12:00", PARIS));
        created.values.put(Events.DTEND, at("2026-10-12T13:00", PARIS));
        created.values.put(Events.EVENT_TIMEZONE, "Europe/Paris");
        created.values.put(Events.ALL_DAY, 0);
        Map<String, Object> reminder = new LinkedHashMap<>();
        reminder.put(Reminders.MINUTES, 10);
        reminder.put(Reminders.METHOD, Reminders.METHOD_ALERT);
        created.reminders.add(reminder);

        JSONObject merged = CalendarMapping.merge(new JSONObject(), List.of(created), PHONE);
        String written = applied("", merged, Zones.vtimezone(PARIS, at("2026-10-12T12:00",
                PARIS)));

        assertEquals("new-uid", merged.getString("uid"));
        assertTrue(written.contains("UID:new-uid\r\n"));
        assertTrue(written.contains("SUMMARY:Lunch\r\n"));
        assertTrue(written.contains("DTSTART;TZID=Europe/Paris:20261012T120000\r\n"));
        assertTrue(written.contains("DTEND;TZID=Europe/Paris:20261012T130000\r\n"));
        assertTrue(written.contains("BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\n"));
        assertTrue(written.contains("TRIGGER:-PT10M\r\n"));
        assertFalse("a status the phone left at its default is none", written.contains("STATUS"));
    }

    @Test
    public void aPlanUpdatesInPlaceAndLeavesWhatIsNotItsOwn() throws Exception {
        long gone = at("2026-10-19T09:00", PARIS);
        long added = at("2026-10-26T09:00", PARIS);

        Row master = new Row();
        master.id = 10;
        master.values.put(Events._SYNC_ID, "ev.ics");
        master.reminders.add(reminder(100, 15, Reminders.METHOD_ALERT));
        master.reminders.add(reminder(101, 60, Reminders.METHOD_EMAIL));
        master.reminders.add(reminder(102, 5, Reminders.METHOD_ALERT));
        Map<String, Object> bob = new LinkedHashMap<>();
        bob.put("_id", 200L);
        bob.put(Attendees.ATTENDEE_EMAIL, "Bob@example.com");
        bob.put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_INVITED);
        master.attendees.add(bob);
        master.url = "https://old";
        master.urlId = 300;
        Row stale = new Row();
        stale.id = 11;
        stale.values.put(Events.ORIGINAL_SYNC_ID, "ev.ics");
        stale.values.put(Events.ORIGINAL_INSTANCE_TIME, gone);

        Row wanted = new Row();
        wanted.values.put(Events._SYNC_ID, "ev.ics");
        wanted.reminders.add(reminder(-1, 15, Reminders.METHOD_ALERT));
        wanted.reminders.add(reminder(-1, 30, Reminders.METHOD_ALERT));
        Map<String, Object> answered = new LinkedHashMap<>();
        answered.put(Attendees.ATTENDEE_EMAIL, "bob@example.com");
        answered.put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_ACCEPTED);
        wanted.attendees.add(answered);
        wanted.url = "https://new";
        Row fresh = new Row();
        fresh.values.put(Events.ORIGINAL_SYNC_ID, "ev.ics");
        fresh.values.put(Events.ORIGINAL_INSTANCE_TIME, added);

        List<Write> plan = CalendarMapping.plan(List.of(master, stale), List.of(wanted, fresh));

        List<String> done = new ArrayList<>();
        for (Write write : plan) {
            done.add(write.table + " " + write.op + " " + write.id);
        }
        assertEquals(
                List.of(
                        "events update 10",
                        "reminders insert -1",
                        "reminders delete 102",
                        "attendees update 200",
                        "properties update 300",
                        "events insert -1",
                        "events delete 11"),
                done);
        assertEquals(10L, plan.get(1).event);
        assertEquals(10L, plan.get(5).values.get(Events.ORIGINAL_ID));
        assertEquals(-1, plan.get(5).parent);
        assertEquals(CalendarMapping.URL, plan.get(4).values.get(ExtendedProperties.NAME));
    }

    @Test
    public void aNewObjectsRowsHangOffItsInsertedMaster() throws Exception {
        List<Write> plan = CalendarMapping.plan(List.of(), rows(MEETING));

        assertEquals("insert", plan.get(0).op);
        for (Write write : plan.subList(1, plan.size())) {
            assertEquals("insert", write.op);
            assertEquals(0, write.parent);
        }
    }

    @Test
    public void aFingerprintMovesWithTheRowsAlone() throws Exception {
        List<Row> rows = phone(MEETING);
        String before = CalendarMapping.fingerprint(rows);
        assertEquals(before, CalendarMapping.fingerprint(phone(MEETING)));

        rows.get(0).reminders.clear();
        assertNotNull(before);
        assertFalse(before.equals(CalendarMapping.fingerprint(rows)));
    }

    private static Map<String, Object> reminder(long id, long minutes, int method) {
        Map<String, Object> reminder = new LinkedHashMap<>();
        if (id >= 0) {
            reminder.put("_id", id);
        }
        reminder.put(Reminders.MINUTES, minutes);
        reminder.put(Reminders.METHOD, method);
        return reminder;
    }
}
