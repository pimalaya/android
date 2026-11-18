package org.pimalaya.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.EventTarget;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirIndexDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An entry created, edited and deleted over CalDAV answering PUTs without ETag.
 *
 * <p>As Google's does: the real calendar driver, engine and store over a fake
 * server.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarDavWriteTest {
    private static final String EMAIL = "dav-write@example.com";
    private static final String URL = "https://dav.example.org/cal/";

    private final Account account = new Account("https://dav.example.org/", EMAIL, "secret");

    private PimdirDb pimdir;
    private Clients clients;
    private EventStore events;
    private CalendarEngine engine;
    private String calendar;
    private final Dav dav = new Dav();

    /** A CalDAV calendar listed whole each round; a PUT gets no ETag. */
    private static final class Dav extends CalendarClient {
        /** The entries by resource name, as (ETag, body). */
        final Map<String, String[]> entries = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();

        /** The bytes an entry is served as, where they are not its text's. */
        final Map<String, byte[]> served = new LinkedHashMap<>();
        int revisions;

        @Override
        public EventDelta syncEvents(
                Transport transport, Account account, String calendarUrl, String cursor) {
            List<EventRef> listed = new ArrayList<>();
            for (Map.Entry<String, String[]> entry : entries.entrySet()) {
                listed.add(new EventRef(entry.getKey(), entry.getValue()[0]));
            }
            return new EventDelta(listed, List.of(), List.of(), null, true);
        }

        @Override
        public List<Event> multigetEvents(
                Transport transport, Account account, String calendarUrl, List<String> ids) {
            List<Event> read = new ArrayList<>();
            for (String id : ids) {
                String[] entry = entries.get(id);
                byte[] bytes = served.get(id);
                if (entry != null && bytes != null) {
                    // NOTE: as the bridge hands a body that is not UTF-8.
                    read.add(
                            new Event(
                                    id,
                                    entry[0],
                                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                                    java.util.Base64.getEncoder().encodeToString(bytes)));
                } else if (entry != null) {
                    read.add(new Event(id, entry[0], entry[1]));
                }
            }
            return read;
        }

        @Override
        public EventRef createEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            String id = target.id();
            entries.put(id, new String[] {"e" + ++revisions, ical});
            return new EventRef(id, null);
        }

        @Override
        public String updateEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            String id = target.id();
            String[] entry = entries.get(id);
            if (entry == null) {
                throw new PimalayaException("not found: " + id, 404);
            }
            entry[0] = "e" + ++revisions;
            entry[1] = ical;
            return null;
        }

        @Override
        public void deleteEvent(Transport transport, Account account, EventTarget target) {
            String id = target.id();
            if (entries.remove(id) == null) {
                throw new PimalayaException("not found: " + id, 404);
            }
            deleted.add(id);
        }
    }

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        events = new EventStore(context, pimdir);
        events.replaceCalendars(
                EMAIL, List.of(new Calendar("work", "Work", URL, "", null, "", true)));
        calendar = events.loadCalendars().get(0).id;
        clients =
                new Clients(
                        new AccountClient(), new ContactClient(), dav, new MailClient(),
                        new OfflineClient());
        engine =
                new CalendarEngine(
                        pimdir,
                        clients,
                        new RemoteAccount(null, account, new PimdirAccount(context).idOf(EMAIL)));
    }

    private static String ical(String summary) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                + "UID:e-1\r\nDTSTAMP:20261011T090000Z\r\nDTSTART:20261013T090000Z\r\n"
                + "DTEND:20261013T100000Z\r\nSUMMARY:" + summary + "\r\nEND:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    private EventStore.StoredEvent stored() {
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (event.collectionId.equals(calendar)) {
                return event;
            }
        }
        throw new AssertionError("no entry stored");
    }

    private boolean anyStored() {
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (event.collectionId.equals(calendar)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void anEntryCreatedEditedThenDeletedIsDeletedOnAServerAnsweringNoEtag()
            throws Exception {
        engine.mutateAdd(calendar, "e-1", ical("Created"), Flags.none(), null, "");
        engine.sync(calendar);
        assertEquals(List.of("e-1.ics"), new ArrayList<>(dav.entries.keySet()));

        engine.mutateEdit(calendar, stored().handle, ical("Edited"), null, "");
        engine.sync(calendar);
        assertTrue(dav.entries.get("e-1.ics")[1].contains("SUMMARY:Edited"));

        engine.mutateRemove(calendar, stored().handle);
        engine.sync(calendar);

        assertEquals(List.of("e-1.ics"), dav.deleted);
        engine.sync(calendar);
        assertTrue("not pulled back", !anyStored());
    }

    @Test
    public void anEntryMergedByTheSyncShowsWhatItMergedInTheAgenda() throws Exception {
        LocalDate day = LocalDate.now().plusDays(7);
        String date = day.format(DateTimeFormatter.BASIC_ISO_DATE);
        dav.entries.put("e-1.ics", new String[] {"e0", entry(date, "Standup", "Room 1")});
        engine.sync(calendar);
        new CalendarStager(pimdir, clients)
                .mutateEdit(calendar, stored().handle, entry(date, "Standup", "Room 2"), null, "");
        dav.entries.put("e-1.ics", new String[] {"e9", entry(date, "Retro", "Room 1")});

        engine.sync(calendar);

        PimdirIndexDb index = pimdir.startIndex();
        index.refresh();
        String until = day.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        JSONObject row =
                index.agenda(CalendarList.instantOf(date), CalendarList.instantOf(until))
                        .getJSONArray("occurrences")
                        .getJSONObject(0);
        String body = stored().ical();
        assertTrue(body, body.contains("SUMMARY:Retro\r\n"));
        assertEquals(body, "Retro", row.getString("summary"));
        assertEquals(body, "Room 2", row.getString("location"));
    }

    /**
     * An entry that is not UTF-8 is stored as the bytes its server sent,
     * hashed as those, and an edit of its text, which holds U+FFFD where the
     * bytes were, is refused rather than written to the server.
     */
    @Test
    public void anEntryThatIsNotUtf8IsStoredAsServedAndNeverWrittenBackAsText()
            throws Exception {
        byte[] served =
                ical("Réunion").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        dav.entries.put("e-1.ics", new String[] {"e1", ical("R�union")});
        dav.served.put("e-1.ics", served);

        engine.sync(calendar);

        EventStore.StoredEvent entry = stored();
        assertEquals("e-1", entry.id);
        assertTrue(java.util.Arrays.equals(
                served, new org.pimalaya.pimdir.PimdirItems(pimdir).objectBytes(calendar, "e-1")));
        assertEquals(org.pimalaya.pimdir.PimdirHash.of(served), entry.object);

        org.junit.Assert.assertThrows(
                PimalayaException.class,
                () -> engine.mutateEdit(calendar, entry.handle, ical("Edited"), null, ""));
        engine.sync(calendar);
        assertEquals("e1", dav.entries.get("e-1.ics")[0]);
    }

    /** The link ids the calendar holds, sorted. */
    private List<String> linkIds() {
        List<String> ids = new ArrayList<>();
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (event.collectionId.equals(calendar)) {
                ids.add(event.id);
            }
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    /**
     * An entry is keyed by its UID (STORAGE §9, Annex A.3), escaped when it
     * is spelled like a derived key, and a second resource under one UID is
     * kept as an item of its own under a minted key.
     */
    @Test
    public void anEntryIsKeyedByItsUidAndASecondResourceIsMinted() throws Exception {
        dav.entries.put("a.ics", new String[] {"e1", ical("First")});
        dav.entries.put("b.ics", new String[] {"e2", ical("Second")});
        String escaped = ical("Escaped").replace("UID:e-1", "UID:hash:0123");
        dav.entries.put("c.ics", new String[] {"e3", escaped});

        engine.sync(calendar);

        assertEquals(List.of("dup:e-1#b.ics", "e-1", "esc:hash:0123"), linkIds());
    }

    /** An entry on {@code day} at {@code location}. */
    private static String entry(String day, String summary, String location) {
        return ical(summary)
                .replace("20261013", day)
                .replace("END:VEVENT", "LOCATION:" + location + "\r\nEND:VEVENT");
    }
}
