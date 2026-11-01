package org.pimalaya.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.Occurrence;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirIndexDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;

/**
 * The agenda drawn from the search index (cairn/changes/lists-from-the-index):
 * a window's occurrences off the index's occurrence table, each row from its
 * item's summary, on the platform's classes over SQLite 3.18.2, no body read.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarAgendaTest {
    private static final String EMAIL = "jane@example.com";

    private TimeZone zone;
    private PimdirDb pimdir;
    private EventStore events;
    private CalendarStager stager;
    private EventStore.StoredCalendar calendar;

    @Before
    public void setUp() {
        // NOTE: the index reads floating times where the device is.
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        events = new EventStore(context, pimdir);
        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar(
                                "work", "Work", "https://dav.example.org/cal/", "", null, "",
                                true)));
        calendar = events.loadCalendars().get(0);
        stager =
                new CalendarStager(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                new MailClient(), new OfflineClient()));
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(zone);
    }

    private void stage(String id, String uid, String lines) throws Exception {
        stager.mutateAdd(
                calendar.id,
                id,
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                        + "UID:" + uid + "\r\nDTSTAMP:20260101T000000Z\r\n" + lines
                        + "END:VEVENT\r\nEND:VCALENDAR\r\n",
                Flags.none(),
                null,
                "");
    }

    private static void deleteAll(File dir) {
        File[] files = dir.listFiles();
        for (File file : files == null ? new File[0] : files) {
            if (file.isDirectory()) {
                deleteAll(file);
            }
            file.delete();
        }
    }

    /** A civil day as {@code YYYYMMDD}. */
    private static String day(java.time.LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** Next week's Monday: inside the index's horizon whenever this runs. */
    private static java.time.LocalDate monday() {
        return java.time.LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
    }

    @Test
    public void aWindowIsReadOffTheIndexWithNoBody() throws Exception {
        java.time.LocalDate monday = monday();
        stage(
                "standup.ics",
                "standup",
                "DTSTART:20260105T090000\r\nDTEND:20260105T091500\r\n"
                        + "RRULE:FREQ=WEEKLY\r\nSUMMARY:Stand-up\r\nLOCATION:Room 2\r\n");
        stage(
                "holiday.ics",
                "holiday",
                "DTSTART;VALUE=DATE:" + day(monday.plusDays(1)) + "\r\nSUMMARY:Holiday\r\n");
        PimdirIndexDb index = pimdir.startIndex();
        index.refresh();

        // NOTE: the bodies gone: the window still lists, from the index and
        // the summaries.
        deleteAll(pimdir.blobs());

        String from = CalendarList.instantOf(day(monday));
        String until = CalendarList.instantOf(day(monday.plusDays(7)));
        assertEquals(
                monday.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toString(), from);
        JSONObject agenda = index.agenda(from, until);
        assertEquals("America/New_York", agenda.getString("floatingZone"));
        assertFalse(agenda.getBoolean("horizonLeft"));
        assertEquals(0, agenda.getLong("cut"));
        JSONArray rows = agenda.getJSONArray("occurrences");
        assertEquals(2, rows.length());

        ZoneId floating = ZoneId.of(agenda.getString("floatingZone"));
        Occurrence standup = CalendarList.occurrenceOf(rows.getJSONObject(0), floating);
        assertEquals("VEVENT", standup.component());
        assertEquals("Stand-up", standup.summary());
        assertEquals("Room 2", standup.location());
        assertFalse(standup.allDay());
        java.time.ZonedDateTime nine = monday.atTime(9, 0).atZone(ZoneId.of("America/New_York"));
        assertEquals(
                "09:00 on the clock that states it",
                nine.toInstant().toEpochMilli(),
                Zones.instant(standup.start()));
        assertEquals(EventTimeKind.UTC, standup.start().kind());
        assertEquals(
                nine.plusMinutes(15).toInstant().toEpochMilli(), Zones.instant(standup.end()));
        assertNull(standup.recurrenceId());

        Occurrence holiday = CalendarList.occurrenceOf(rows.getJSONObject(1), floating);
        assertTrue(holiday.allDay());
        assertEquals("its own day", day(monday.plusDays(1)), holiday.start().time());
        assertEquals(EventTimeKind.DATE, holiday.start().kind());
        assertEquals(day(monday.plusDays(2)), holiday.end().time());
    }

    @Test
    public void aDeletedEntryLeavesTheWindowBeforeTheIndexFoldsIt() throws Exception {
        java.time.LocalDate monday = monday();
        stage("lunch.ics", "lunch", "DTSTART:" + day(monday) + "T170000Z\r\nSUMMARY:Lunch\r\n");
        PimdirIndexDb index = pimdir.startIndex();
        index.refresh();
        String from = CalendarList.instantOf(day(monday));
        String until = CalendarList.instantOf(day(monday.plusDays(1)));
        assertEquals(1, index.agenda(from, until).getJSONArray("occurrences").length());

        stager.mutateRemove(calendar.id, stager.offline.handleFor(calendar.id, "lunch.ics"));

        assertEquals(0, index.agenda(from, until).getJSONArray("occurrences").length());
    }

    @Test
    public void aWindowPastTheHorizonSaysSo() throws Exception {
        PimdirIndexDb index = pimdir.startIndex();
        index.refresh();
        JSONObject agenda =
                index.agenda(
                        CalendarList.instantOf("21000101"), CalendarList.instantOf("21000108"));
        assertTrue(agenda.getBoolean("horizonLeft"));
    }
}
