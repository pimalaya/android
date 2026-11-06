package org.pimalaya.calendar;

import static org.junit.Assume.assumeTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.Occurrence;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirIndexDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The agenda's read of a week, timed on the host (cairn/changes/lists-from-
 * the-index): every body read and expanded, as the agenda did, against the
 * index's occurrences and the summaries. Run by hand, with {@code
 * PIMALAYA_MEASURE} set; the platform's classes over SQLite 3.18.2.
 */
@RunWith(RobolectricTestRunner.class)
public class AgendaMeasure {
    /** The entries of the device the agenda was first timed on (2026-10-30). */
    private static final int ENTRIES = 478;

    /** One in this many entries is a weekly series. */
    private static final int SERIES = 6;

    private static final int ROUNDS = 7;

    @Test
    public void aWeekReadByExpansionAndFromTheIndex() throws Exception {
        assumeTrue(System.getenv("PIMALAYA_MEASURE") != null);
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        EventStore events = new EventStore(context, pimdir);
        events.replaceCalendars(
                "jane@example.com",
                List.of(
                        new Calendar(
                                "work", "Work", "https://dav.example.org/cal/", "", null, "",
                                true)));
        EventStore.StoredCalendar calendar = events.loadCalendars().get(0);
        CalendarStager stager =
                new CalendarStager(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                new MailClient(), new OfflineClient()));

        // NOTE: single entries spread over the year either side of today,
        // a weekly series every sixth, each with a description of a few
        // lines as real entries carry.
        LocalDate today = LocalDate.now();
        DateTimeFormatter basic = DateTimeFormatter.BASIC_ISO_DATE;
        for (int at = 0; at < ENTRIES; at++) {
            LocalDate day = today.plusDays((at * 37L) % 730 - 365);
            String rule = at % SERIES == 0 ? "RRULE:FREQ=WEEKLY\r\n" : "";
            stager.mutateAdd(
                    calendar.id,
                    "e" + at + ".ics",
                    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                            + "UID:e" + at + "\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;TZID=Europe/Paris:" + day.format(basic) + "T"
                            + String.format("%02d", 8 + at % 10) + "0000\r\n"
                            + "DTEND;TZID=Europe/Paris:" + day.format(basic) + "T"
                            + String.format("%02d", 9 + at % 10) + "0000\r\n"
                            + rule
                            + "SUMMARY:Entry " + at + "\r\nLOCATION:Room " + at % 7 + "\r\n"
                            + "DESCRIPTION:A line of notes about entry " + at
                            + "\\nand another\\, longer one\\nand a third.\r\n"
                            + "END:VEVENT\r\nEND:VCALENDAR\r\n",
                    Flags.none(),
                    null,
                    "");
        }

        long built = System.nanoTime();
        PimdirIndexDb index = pimdir.startIndex();
        index.refresh();
        long refreshed = (System.nanoTime() - built) / 1_000_000;

        String first = CalendarList.firstOfWeek(
                today.format(basic), java.util.Calendar.getInstance().getFirstDayOfWeek());
        LocalDate monday = LocalDate.parse(first, basic);
        // NOTE: the week card's window, then the list's, each with its day
        // of slack either side, as reload reads them.
        measure(
                events,
                index,
                monday.minusDays(1).format(basic),
                monday.plusDays(8).format(basic),
                refreshed);
        measure(
                events,
                index,
                today.minusDays(1).format(basic),
                today.plusDays(CalendarList.LIST_DAYS + 2).format(basic),
                refreshed);
    }

    private static void measure(
            EventStore events, PimdirIndexDb index, String from, String until, long refreshed)
            throws Exception {
        long[] expanded = new long[ROUNDS];
        long[] indexed = new long[ROUNDS];
        int expandedRows = 0;
        int indexedRows = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long started = System.nanoTime();
            List<Occurrence> rows = new ArrayList<>();
            for (EventStore.StoredEvent event : events.loadEvents()) {
                rows.addAll(CalendarClient.expandEvent(event.ical(), from, until));
            }
            expanded[round] = System.nanoTime() - started;
            expandedRows = rows.size();

            started = System.nanoTime();
            events.loadCalendars();
            events.conflicted();
            JSONObject agenda =
                    index.agenda(CalendarList.instantOf(from), CalendarList.instantOf(until));
            ZoneId floating = ZoneId.of(agenda.getString("floatingZone"));
            JSONArray occurrences = agenda.getJSONArray("occurrences");
            List<Occurrence> read = new ArrayList<>();
            for (int at = 0; at < occurrences.length(); at++) {
                read.add(CalendarList.occurrenceOf(occurrences.getJSONObject(at), floating));
            }
            indexed[round] = System.nanoTime() - started;
            indexedRows = read.size();
        }

        System.out.println(
                "agenda measure: "
                        + ENTRIES
                        + " entries, "
                        + from
                        + ".."
                        + until
                        + "; expanded "
                        + expandedRows
                        + " rows, median "
                        + median(expanded)
                        + " ms (first "
                        + expanded[0] / 1_000_000
                        + " ms); index "
                        + indexedRows
                        + " rows, median "
                        + median(indexed)
                        + " ms (first "
                        + indexed[0] / 1_000_000
                        + " ms); first index build "
                        + refreshed
                        + " ms");
    }

    private static long median(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] / 1_000_000;
    }
}
