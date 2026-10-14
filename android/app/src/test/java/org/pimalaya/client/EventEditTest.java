package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.pimalaya.client.CalendarFixtures.DAY;
import static org.pimalaya.client.CalendarFixtures.JOURNAL;
import static org.pimalaya.client.CalendarFixtures.MOVED;
import static org.pimalaya.client.CalendarFixtures.ROMANCE;
import static org.pimalaya.client.CalendarFixtures.SINGLE;
import static org.pimalaya.client.CalendarFixtures.TODO;
import static org.pimalaya.client.CalendarFixtures.UTC;
import static org.pimalaya.client.CalendarFixtures.ZONED;
import static org.pimalaya.client.CalendarFixtures.weekly;
import static org.pimalaya.client.ReplyShape.assertSameJson;

import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Every edit the entry page builds equals the JSON object it built before.
 *
 * <p>The bridge writes, splits and removes the same iCalendar from it; literals
 * captured on EventView's old builder for the same page states.
 */
@RunWith(RobolectricTestRunner.class)
public class EventEditTest {
    private static final String STAMP = "20260201T100000Z";

    private static final String ROMANCE_ID = "/example.org/Romance";

    /** A date moved where it was: its zone left to the line it replaces. */
    private static DateEdit at(String time) {
        return new DateEdit(time, null, null);
    }

    @Test
    public void texts() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(
                                        EventEdit.Field.SUMMARY, "Lunch",
                                        EventEdit.Field.LOCATION, ""),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"summary\":\"Lunch\",\"location\":\"\",\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:e\r\n"
                        + "DTSTART:20260105T090000\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "SUMMARY:Lunch\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(SINGLE, edit));
    }

    @Test
    public void moved() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(
                                        EventEdit.DateField.START, at("20260105T100000"),
                                        EventEdit.DateField.END, at("20260105T110000")),
                                null,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"start\":{\"time\":\"20260105T100000\"},"
                        + "\"end\":{\"time\":\"20260105T110000\"},\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:e\r\n"
                        + "DTSTART:20260105T100000\r\n"
                        + "DTEND:20260105T110000\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "LOCATION:Room 1\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(SINGLE, edit));
    }

    @Test
    public void rezoned() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(
                                        EventEdit.DateField.START,
                                        new DateEdit(
                                                "20260105T090000",
                                                EventTimeKind.ZONED,
                                                ROMANCE_ID)),
                                ROMANCE,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"vtimezone\":\"BEGIN:VTIMEZONE\\r\\nTZID:\\/example.org\\/Romance\\r"
                        + "\\nBEGIN:DAYLIGHT\\r\\nDTSTART:19810329T020000\\r\\nTZOFFSETFROM:+0100"
                        + "\\r\\nTZOFFSETTO:+0200\\r\\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\\r\\n"
                        + "END:DAYLIGHT\\r\\nBEGIN:STANDARD\\r\\nDTSTART:19961027T030000\\r\\nTZOFF"
                        + "SETFROM:+0200\\r\\nTZOFFSETTO:+0100\\r\\nRRULE:FREQ=YEARLY;BYMONTH=10;BY"
                        + "DAY=-1SU\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\n\","
                        + "\"start\":{\"time\":\"20260105T090000\",\"kind\":\"zoned\","
                        + "\"tzid\":\"\\/example.org\\/Romance\"},\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VTIMEZONE\r\n"
                        + "TZID:/example.org/Romance\r\n"
                        + "BEGIN:DAYLIGHT\r\n"
                        + "DTSTART:19810329T020000\r\n"
                        + "TZOFFSETFROM:+0100\r\n"
                        + "TZOFFSETTO:+0200\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
                        + "END:DAYLIGHT\r\n"
                        + "BEGIN:STANDARD\r\n"
                        + "DTSTART:19961027T030000\r\n"
                        + "TZOFFSETFROM:+0200\r\n"
                        + "TZOFFSETTO:+0100\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                        + "END:STANDARD\r\n"
                        + "END:VTIMEZONE\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:e\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "LOCATION:Room 1\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(SINGLE, edit));
    }

    @Test
    public void bothZoned() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(
                                        EventEdit.DateField.START,
                                        new DateEdit(
                                                "20260105T090000",
                                                EventTimeKind.ZONED,
                                                ROMANCE_ID),
                                        EventEdit.DateField.END,
                                        new DateEdit(
                                                "20260105T100000",
                                                EventTimeKind.ZONED,
                                                ROMANCE_ID)),
                                ROMANCE,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"vtimezone\":\"BEGIN:VTIMEZONE\\r\\nTZID:\\/example.org\\/Romance\\r"
                        + "\\nBEGIN:DAYLIGHT\\r\\nDTSTART:19810329T020000\\r\\nTZOFFSETFROM:+0100"
                        + "\\r\\nTZOFFSETTO:+0200\\r\\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\\r\\n"
                        + "END:DAYLIGHT\\r\\nBEGIN:STANDARD\\r\\nDTSTART:19961027T030000\\r\\nTZOFF"
                        + "SETFROM:+0200\\r\\nTZOFFSETTO:+0100\\r\\nRRULE:FREQ=YEARLY;BYMONTH=10;BY"
                        + "DAY=-1SU\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\n\","
                        + "\"start\":{\"time\":\"20260105T090000\",\"kind\":\"zoned\","
                        + "\"tzid\":\"\\/example.org\\/Romance\"},"
                        + "\"end\":{\"time\":\"20260105T100000\",\"kind\":\"zoned\","
                        + "\"tzid\":\"\\/example.org\\/Romance\"},\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VTIMEZONE\r\n"
                        + "TZID:/example.org/Romance\r\n"
                        + "BEGIN:DAYLIGHT\r\n"
                        + "DTSTART:19810329T020000\r\n"
                        + "TZOFFSETFROM:+0100\r\n"
                        + "TZOFFSETTO:+0200\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
                        + "END:DAYLIGHT\r\n"
                        + "BEGIN:STANDARD\r\n"
                        + "DTSTART:19961027T030000\r\n"
                        + "TZOFFSETFROM:+0200\r\n"
                        + "TZOFFSETTO:+0100\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                        + "END:STANDARD\r\n"
                        + "END:VTIMEZONE\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:e\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "LOCATION:Room 1\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20260105T100000\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(SINGLE, edit));
    }

    @Test
    public void unzoned() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(
                                        EventEdit.DateField.START,
                                        new DateEdit(
                                                "20260107T090000",
                                                EventTimeKind.FLOATING,
                                                "")),
                                null,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"start\":{\"time\":\"20260107T090000\",\"kind\":\"floating\","
                        + "\"tzid\":\"\"},\"stamp\":\"20260201T100000Z\",\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:u\r\n"
                        + "DTEND:20260107T083000Z\r\n"
                        + "SUMMARY:Call\r\n"
                        + "DTSTART:20260107T090000\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(UTC, edit));
    }

    @Test
    public void allDayOff() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(
                                        EventEdit.DateField.START,
                                        new DateEdit("20260108T090000", EventTimeKind.FLOATING, ""),
                                        EventEdit.DateField.END,
                                        new DateEdit(
                                                "20260108T100000",
                                                EventTimeKind.FLOATING,
                                                "")),
                                null,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"start\":{\"time\":\"20260108T090000\",\"kind\":\"floating\","
                        + "\"tzid\":\"\"},\"end\":{\"time\":\"20260108T100000\","
                        + "\"kind\":\"floating\",\"tzid\":\"\"},\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:d\r\n"
                        + "SUMMARY:Holiday\r\n"
                        + "DTSTART:20260108T090000\r\n"
                        + "DTEND:20260108T100000\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(DAY, edit));
    }

    @Test
    public void todo() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(
                                        EventEdit.Field.STATUS, "IN-PROCESS",
                                        EventEdit.Field.PRIORITY, "",
                                        EventEdit.Field.PERCENT_COMPLETE, "50"),
                                Map.of(
                                        EventEdit.DateField.DUE, at("20260110T170000"),
                                        EventEdit.DateField.COMPLETED, at("")),
                                null,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"status\":\"IN-PROCESS\",\"priority\":\"\",\"percentComplete\":\"50\","
                        + "\"due\":{\"time\":\"20260110T170000\"},\"completed\":{\"time\":\"\"},"
                        + "\"stamp\":\"20260201T100000Z\",\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VTODO\r\n"
                        + "UID:t\r\n"
                        + "DUE:20260110T170000Z\r\n"
                        + "SUMMARY:File report\r\n"
                        + "STATUS:IN-PROCESS\r\n"
                        + "PERCENT-COMPLETE:50\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VTODO\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(TODO, edit));
    }

    @Test
    public void journalParis() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(EventEdit.Field.SUMMARY, ""),
                                Map.of(
                                        EventEdit.DateField.START,
                                        new DateEdit(
                                                "20260110T090000",
                                                EventTimeKind.ZONED,
                                                "Europe/Paris")),
                                null,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"summary\":\"\",\"start\":{\"time\":\"20260110T090000\","
                        + "\"kind\":\"zoned\",\"tzid\":\"Europe\\/Paris\"},"
                        + "\"stamp\":\"20260201T100000Z\",\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VJOURNAL\r\n"
                        + "UID:j\r\n"
                        + "DESCRIPTION:Met the team\r\n"
                        + "DTSTART;TZID=Europe/Paris:20260110T090000\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VJOURNAL\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(JOURNAL, edit));
    }

    @Test
    public void stampOnly() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(EventScope.ALL, null, null, null);
        assertSameJson(
                "{\"stamp\":\"20260201T100000Z\",\"scope\":\"all\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:e\r\n"
                        + "DTSTART:20260105T090000\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "LOCATION:Room 1\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(SINGLE, edit));
    }

    @Test
    public void zonedThis() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(
                                        EventEdit.Field.DESCRIPTION, "",
                                        EventEdit.Field.URL, "https://example.org/r2",
                                        EventEdit.Field.CATEGORIES, "WORK"),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(EventScope.THIS, "20260112T090000", "20260112T080000Z", null);
        assertSameJson(
                "{\"description\":\"\",\"url\":\"https:\\/\\/example.org\\/r2\","
                        + "\"categories\":\"WORK\",\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"this\",\"recurrenceId\":\"20260112T090000\","
                        + "\"recurrenceInstant\":\"20260112T080000Z\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VTIMEZONE\r\n"
                        + "TZID:/example.org/Romance\r\n"
                        + "BEGIN:DAYLIGHT\r\n"
                        + "DTSTART:19810329T020000\r\n"
                        + "TZOFFSETFROM:+0100\r\n"
                        + "TZOFFSETTO:+0200\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
                        + "END:DAYLIGHT\r\n"
                        + "BEGIN:STANDARD\r\n"
                        + "DTSTART:19961027T030000\r\n"
                        + "TZOFFSETFROM:+0200\r\n"
                        + "TZOFFSETTO:+0100\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                        + "END:STANDARD\r\n"
                        + "END:VTIMEZONE\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:z\r\n"
                        + "DTSTAMP:20260101T000000Z\r\n"
                        + "CREATED:20251201T080000Z\r\n"
                        + "LAST-MODIFIED:20251202T080000Z\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20260105T100000\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=3\r\n"
                        + "SUMMARY:Review\r\n"
                        + "DESCRIPTION:Weekly review\r\n"
                        + "LOCATION:Room 2\r\n"
                        + "URL:https://example.org/review\r\n"
                        + "STATUS:CONFIRMED\r\n"
                        + "CATEGORIES:WORK,TEAM\r\n"
                        + "PRIORITY:5\r\n"
                        + "ORGANIZER;CN=Ann:mailto:ann@example.org\r\n"
                        + "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.org\r\n"
                        + "ATTENDEE:mailto:cy@example.org\r\n"
                        + "END:VEVENT\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:z\r\n"
                        + "RECURRENCE-ID;TZID=/example.org/Romance:20260112T090000\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260113T110000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20260113T120000\r\n"
                        + "SUMMARY:Review, moved\r\n"
                        + "URL:https://example.org/r2\r\n"
                        + "CATEGORIES:WORK\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(ZONED, edit));
    }

    @Test
    public void weeklyThis() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(EventEdit.Field.SUMMARY, "Standup, late"),
                                Map.of(EventEdit.DateField.START, at("20260119T093000")),
                                null,
                                STAMP)
                        .scoped(EventScope.THIS, "20260119T090000", "20260119T090000Z", null);
        assertSameJson(
                "{\"summary\":\"Standup, late\",\"start\":{\"time\":\"20260119T093000\"},"
                        + "\"stamp\":\"20260201T100000Z\",\"scope\":\"this\","
                        + "\"recurrenceId\":\"20260119T090000\","
                        + "\"recurrenceInstant\":\"20260119T090000Z\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "DTSTAMP:20260101T000000Z\r\n"
                        + "DTSTART:20260105T090000\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=6\r\n"
                        + "END:VEVENT\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "RECURRENCE-ID:20260112T090000\r\n"
                        + "DTSTAMP:20260101T000000Z\r\n"
                        + "DTSTART:20260113T090000\r\n"
                        + "DTEND:20260113T100000\r\n"
                        + "SUMMARY:Standup, moved\r\n"
                        + "END:VEVENT\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "DTSTART:20260119T093000\r\n"
                        + "DTEND:20260119T100000\r\n"
                        + "RECURRENCE-ID:20260119T090000\r\n"
                        + "SUMMARY:Standup\\, late\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(weekly(MOVED), edit));
    }

    @Test
    public void weeklyAll() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(EventEdit.Field.SUMMARY, "Daily"),
                                Map.of(EventEdit.DateField.START, at("20260119T091500")),
                                null,
                                STAMP)
                        .scoped(EventScope.ALL, "20260119T090000", "20260119T090000Z", null);
        assertSameJson(
                "{\"summary\":\"Daily\",\"start\":{\"time\":\"20260119T091500\"},"
                        + "\"stamp\":\"20260201T100000Z\",\"scope\":\"all\","
                        + "\"recurrenceId\":\"20260119T090000\","
                        + "\"recurrenceInstant\":\"20260119T090000Z\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "DTSTART:20260105T091500\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=6\r\n"
                        + "SUMMARY:Daily\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "RECURRENCE-ID:20260112T090000\r\n"
                        + "DTSTAMP:20260101T000000Z\r\n"
                        + "DTSTART:20260113T090000\r\n"
                        + "DTEND:20260113T100000\r\n"
                        + "SUMMARY:Standup, moved\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.writeEvent(weekly(MOVED), edit));
    }

    @Test
    public void weeklyFollowing() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(EventEdit.Field.SUMMARY, "Later"),
                                Map.of(
                                        EventEdit.DateField.START, at("20260119T100000"),
                                        EventEdit.DateField.END, at("20260119T110000")),
                                null,
                                STAMP)
                        .scoped(EventScope.FOLLOWING, "20260119T090000", "20260119T090000Z", "s-2");
        assertSameJson(
                "{\"summary\":\"Later\",\"start\":{\"time\":\"20260119T100000\"},"
                        + "\"end\":{\"time\":\"20260119T110000\"},\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"following\",\"recurrenceId\":\"20260119T090000\","
                        + "\"recurrenceInstant\":\"20260119T090000Z\",\"uid\":\"s-2\"}",
                edit.toJson().toString());
        assertEquals(
                new EventSplit(
                        "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "DTSTART:20260105T090000\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "RRULE:FREQ=WEEKLY;UNTIL=20260119T085959\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR\r\n",
                        "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s-2\r\n"
                        + "DTSTART:20260119T100000\r\n"
                        + "DTEND:20260119T110000\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=4\r\n"
                        + "SUMMARY:Later\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR\r\n"),
                CalendarClient.splitEvent(weekly(""), edit));
    }

    @Test
    public void zonedFollowing() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(EventEdit.Field.SUMMARY, "Later"),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(EventScope.FOLLOWING, "20260112T090000", "20260112T080000Z", "n");
        assertSameJson(
                "{\"summary\":\"Later\",\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"following\",\"recurrenceId\":\"20260112T090000\","
                        + "\"recurrenceInstant\":\"20260112T080000Z\",\"uid\":\"n\"}",
                edit.toJson().toString());
        assertEquals(
                new EventSplit(
                        "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VTIMEZONE\r\n"
                        + "TZID:/example.org/Romance\r\n"
                        + "BEGIN:DAYLIGHT\r\n"
                        + "DTSTART:19810329T020000\r\n"
                        + "TZOFFSETFROM:+0100\r\n"
                        + "TZOFFSETTO:+0200\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
                        + "END:DAYLIGHT\r\n"
                        + "BEGIN:STANDARD\r\n"
                        + "DTSTART:19961027T030000\r\n"
                        + "TZOFFSETFROM:+0200\r\n"
                        + "TZOFFSETTO:+0100\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                        + "END:STANDARD\r\n"
                        + "END:VTIMEZONE\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:z\r\n"
                        + "CREATED:20251201T080000Z\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20260105T100000\r\n"
                        + "RRULE:FREQ=WEEKLY;UNTIL=20260112T075959Z\r\n"
                        + "SUMMARY:Review\r\n"
                        + "DESCRIPTION:Weekly review\r\n"
                        + "LOCATION:Room 2\r\n"
                        + "URL:https://example.org/review\r\n"
                        + "STATUS:CONFIRMED\r\n"
                        + "CATEGORIES:WORK,TEAM\r\n"
                        + "PRIORITY:5\r\n"
                        + "ORGANIZER;CN=Ann:mailto:ann@example.org\r\n"
                        + "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.org\r\n"
                        + "ATTENDEE:mailto:cy@example.org\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR\r\n",
                        "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VTIMEZONE\r\n"
                        + "TZID:/example.org/Romance\r\n"
                        + "BEGIN:DAYLIGHT\r\n"
                        + "DTSTART:19810329T020000\r\n"
                        + "TZOFFSETFROM:+0100\r\n"
                        + "TZOFFSETTO:+0200\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
                        + "END:DAYLIGHT\r\n"
                        + "BEGIN:STANDARD\r\n"
                        + "DTSTART:19961027T030000\r\n"
                        + "TZOFFSETFROM:+0200\r\n"
                        + "TZOFFSETTO:+0100\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                        + "END:STANDARD\r\n"
                        + "END:VTIMEZONE\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:n\r\n"
                        + "CREATED:20251201T080000Z\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260112T090000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20260112T100000\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=2\r\n"
                        + "DESCRIPTION:Weekly review\r\n"
                        + "LOCATION:Room 2\r\n"
                        + "URL:https://example.org/review\r\n"
                        + "STATUS:CONFIRMED\r\n"
                        + "CATEGORIES:WORK,TEAM\r\n"
                        + "PRIORITY:5\r\n"
                        + "ORGANIZER;CN=Ann:mailto:ann@example.org\r\n"
                        + "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.org\r\n"
                        + "ATTENDEE:mailto:cy@example.org\r\n"
                        + "SUMMARY:Later\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:n\r\n"
                        + "RECURRENCE-ID;TZID=/example.org/Romance:20260112T090000\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260113T110000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20260113T120000\r\n"
                        + "SUMMARY:Later\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR\r\n"),
                CalendarClient.splitEvent(ZONED, edit));
    }

    @Test
    public void firstFollowing() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(EventEdit.Field.LOCATION, "Hall"),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(EventScope.FOLLOWING, "20260105T090000", "20260105T090000Z", "n2");
        assertSameJson(
                "{\"location\":\"Hall\",\"stamp\":\"20260201T100000Z\","
                        + "\"scope\":\"following\",\"recurrenceId\":\"20260105T090000\","
                        + "\"recurrenceInstant\":\"20260105T090000Z\",\"uid\":\"n2\"}",
                edit.toJson().toString());
        assertEquals(
                new EventSplit(
                        "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "DTSTART:20260105T090000\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=6\r\n"
                        + "LOCATION:Hall\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR\r\n",
                        null),
                CalendarClient.splitEvent(weekly(""), edit));
    }

    @Test
    public void deleteThis() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(EventScope.THIS, "20260119T090000", "20260119T090000Z", null);
        assertSameJson(
                "{\"stamp\":\"20260201T100000Z\",\"scope\":\"this\","
                        + "\"recurrenceId\":\"20260119T090000\","
                        + "\"recurrenceInstant\":\"20260119T090000Z\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "DTSTART:20260105T090000\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=6\r\n"
                        + "EXDATE:20260119T090000\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.removeEvent(weekly(""), edit));
    }

    @Test
    public void deleteFollowing() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(
                                EventScope.FOLLOWING,
                                "20260119T090000",
                                "20260119T090000Z",
                                "x-deleteFollowing");
        assertSameJson(
                "{\"stamp\":\"20260201T100000Z\",\"scope\":\"following\","
                        + "\"recurrenceId\":\"20260119T090000\","
                        + "\"recurrenceInstant\":\"20260119T090000Z\","
                        + "\"uid\":\"x-deleteFollowing\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:s\r\n"
                        + "DTSTART:20260105T090000\r\n"
                        + "DTEND:20260105T100000\r\n"
                        + "SUMMARY:Standup\r\n"
                        + "RRULE:FREQ=WEEKLY;UNTIL=20260119T085959\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.removeEvent(weekly(""), edit));
    }

    @Test
    public void deleteFirstFollowing() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(
                                EventScope.FOLLOWING,
                                "20260105T090000",
                                "20260105T090000Z",
                                "x-deleteFirstFollowing");
        assertSameJson(
                "{\"stamp\":\"20260201T100000Z\",\"scope\":\"following\","
                        + "\"recurrenceId\":\"20260105T090000\","
                        + "\"recurrenceInstant\":\"20260105T090000Z\","
                        + "\"uid\":\"x-deleteFirstFollowing\"}",
                edit.toJson().toString());
        assertEquals(
                null,
                CalendarClient.removeEvent(weekly(""), edit));
    }

    @Test
    public void deleteZonedThis() throws Exception {
        EventEdit edit =
                EventEdit.of(
                                Map.of(),
                                Map.of(),
                                null,
                                STAMP)
                        .scoped(EventScope.THIS, "20260112T090000", "20260112T080000Z", null);
        assertSameJson(
                "{\"stamp\":\"20260201T100000Z\",\"scope\":\"this\","
                        + "\"recurrenceId\":\"20260112T090000\","
                        + "\"recurrenceInstant\":\"20260112T080000Z\"}",
                edit.toJson().toString());
        assertEquals(
                "BEGIN:VCALENDAR\r\n"
                        + "VERSION:2.0\r\n"
                        + "PRODID:-//test//EN\r\n"
                        + "BEGIN:VTIMEZONE\r\n"
                        + "TZID:/example.org/Romance\r\n"
                        + "BEGIN:DAYLIGHT\r\n"
                        + "DTSTART:19810329T020000\r\n"
                        + "TZOFFSETFROM:+0100\r\n"
                        + "TZOFFSETTO:+0200\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
                        + "END:DAYLIGHT\r\n"
                        + "BEGIN:STANDARD\r\n"
                        + "DTSTART:19961027T030000\r\n"
                        + "TZOFFSETFROM:+0200\r\n"
                        + "TZOFFSETTO:+0100\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                        + "END:STANDARD\r\n"
                        + "END:VTIMEZONE\r\n"
                        + "BEGIN:VEVENT\r\n"
                        + "UID:z\r\n"
                        + "CREATED:20251201T080000Z\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20260105T100000\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=3\r\n"
                        + "SUMMARY:Review\r\n"
                        + "DESCRIPTION:Weekly review\r\n"
                        + "LOCATION:Room 2\r\n"
                        + "URL:https://example.org/review\r\n"
                        + "STATUS:CONFIRMED\r\n"
                        + "CATEGORIES:WORK,TEAM\r\n"
                        + "PRIORITY:5\r\n"
                        + "ORGANIZER;CN=Ann:mailto:ann@example.org\r\n"
                        + "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.org\r\n"
                        + "ATTENDEE:mailto:cy@example.org\r\n"
                        + "EXDATE;TZID=/example.org/Romance:20260112T090000\r\n"
                        + "DTSTAMP:20260201T100000Z\r\n"
                        + "LAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n"
                        + "END:VCALENDAR",
                CalendarClient.removeEvent(ZONED, edit));
    }
}
