package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.Transport;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An account's calendars run side by side, and a calendar's entries are read
 * in as few requests as the backend allows: a first sync of 120 events over
 * three Graph calendars, once read one request an event and one calendar
 * after another, takes a fraction of the time.
 *
 * <p>The server answers every request after a fixed latency, which is what a
 * pass waits on; the calendar driver's reads, the engine and the store are
 * the real thing.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarPoolTest {
    private static final String EMAIL = "jane@example.com";
    private static final String OTHER = "john@example.org";

    /** What one request to the fake server takes. */
    private static final long LATENCY_MS = 20;

    /** Events in each of the three calendars. */
    private static final int SIZE = 40;

    private Context context;
    private PimdirDb pimdir;
    private EventStore events;
    private String email;
    private String accountId;
    private final Account account = new Account("msgraph://example", EMAIL, "token");

    /** A transport used by two workers at once, if any ever was. */
    private final AtomicBoolean shared = new AtomicBoolean();

    /** A request sent while the store's lock was held, if any ever was. */
    private final AtomicBoolean networkUnderLock = new AtomicBoolean();

    /** The writes in flight, and the most ever seen at once. */
    private final AtomicInteger writing = new AtomicInteger();
    private final AtomicInteger mostWriting = new AtomicInteger();
    private final AtomicBoolean writeOutsideLock = new AtomicBoolean();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        events = new EventStore(context, pimdir);
        use(EMAIL);
    }

    /** Runs the next passes as another account's, over collections of its own. */
    private void use(String address) {
        email = address;
        accountId = new PimdirAccount(context).idOf(address);
    }

    /** How the fake reads the bodies a pass asks for. */
    private enum Reads {
        /** One request an event: Graph before `$batch`. */
        ONE_BY_ONE,
        /** One request per 20 events: Graph's `$batch`. */
        BATCHED,
        /** None: the listing carried them (Google, JMAP). */
        LISTED
    }

    private static String ical(String uid, String summary) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:" + uid + "\r\n"
                + "DTSTART:20261008T090000Z\r\nSUMMARY:" + summary + "\r\nEND:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    /** A calendar server whose requests each take {@link #LATENCY_MS}. */
    private final class Server extends PimalayaClient {
        /** Each calendar's events by id, as (revision, body). */
        final Map<String, Map<String, String[]>> calendars = new LinkedHashMap<>();

        final Reads reads;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger bodiesRead = new AtomicInteger();
        private final Map<Transport, AtomicBoolean> busy =
                Collections.synchronizedMap(new IdentityHashMap<>());

        Server(Reads reads) {
            this.reads = reads;
        }

        private void request(Transport transport) {
            if (Thread.holdsLock(PimdirEngine.STORE)) {
                networkUnderLock.set(true);
            }
            AtomicBoolean using = busy.computeIfAbsent(transport, key -> new AtomicBoolean());
            if (using.getAndSet(true)) {
                shared.set(true);
            }
            requests.incrementAndGet();
            try {
                Thread.sleep(LATENCY_MS);
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
            } finally {
                using.set(false);
            }
        }

        @Override
        public EventDelta syncEvents(
                Transport transport, Account account, String calendarUrl, String cursor) {
            request(transport);
            List<EventRef> changed = new ArrayList<>();
            List<Event> bodies = new ArrayList<>();
            for (Map.Entry<String, String[]> event : calendars.get(calendarUrl).entrySet()) {
                changed.add(new EventRef(event.getKey(), event.getValue()[0]));
                if (reads == Reads.LISTED) {
                    bodies.add(new Event(event.getKey(), event.getValue()[0], event.getValue()[1]));
                }
            }
            return new EventDelta(changed, bodies, List.of(), null, true);
        }

        @Override
        public List<Event> multigetEvents(
                Transport transport, Account account, String calendarUrl, List<String> ids) {
            int sent = reads == Reads.ONE_BY_ONE ? ids.size() : (ids.size() + 19) / 20;
            for (int index = 0; index < sent; index++) {
                request(transport);
            }
            bodiesRead.addAndGet(ids.size());
            List<Event> read = new ArrayList<>();
            for (String id : ids) {
                String[] event = calendars.get(calendarUrl).get(id);
                if (event != null) {
                    read.add(new Event(id, event[0], event[1]));
                }
            }
            return read;
        }
    }

    /**
     * The calendar driver, its storage writes watched: the real
     * {@link CalendarEngine}'s listing and reads behind the shared engine.
     */
    private final class Watched extends PimdirEngine {
        final CalendarEngine inner;

        Watched(Server server, Transport transport) {
            super(pimdir, server);
            inner = new CalendarEngine(pimdir, server, transport, account, accountId);
        }

        void sync(String collection) {
            client.offlineSync(this, collection, false);
            hydrate(collection);
        }

        @Override
        protected PimDomain domain() {
            return PimDomain.CALENDAR;
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            return inner.enumerate(yielded);
        }

        @Override
        protected JSONObject fetch(JSONObject yielded) throws JSONException {
            return inner.fetch(yielded);
        }

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            return inner.push(yielded);
        }

        @Override
        protected void applied(JSONArray effects) {
            if (!Thread.holdsLock(PimdirEngine.STORE)) {
                writeOutsideLock.set(true);
            }
            int now = writing.incrementAndGet();
            mostWriting.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            writing.decrementAndGet();
        }
    }

    /** Three calendars of {@link #SIZE} events on a server, stored as an account's. */
    private List<String> threeCalendars(Server server) {
        List<Calendar> listed = new ArrayList<>();
        for (String name : new String[] {"work", "home", "birthdays"}) {
            String url = "msgraph://example/" + name;
            listed.add(new Calendar(name, name, url, null, null));
            Map<String, String[]> members = new LinkedHashMap<>();
            for (int index = 0; index < SIZE; index++) {
                String id = name + "-" + index;
                members.put(id, new String[] {"ck1", ical(id, "Event " + id)});
            }
            server.calendars.put(url, members);
        }
        events.replaceCalendars(email, listed);
        List<String> collections = new ArrayList<>();
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (calendar.accountEmail.equals(email)) {
                collections.add(calendar.id);
            }
        }
        return collections;
    }

    /** One pass over the calendars on a pool of {@code size}. */
    private CalendarPool.Outcome pass(
            Server server, List<String> collections, int size, List<Transport> opened) {
        Transport first = new Transport();
        opened.add(first);
        try (CalendarPool<Transport> pool =
                new CalendarPool<>(
                        size,
                        first,
                        () -> {
                            Transport transport = new Transport();
                            synchronized (opened) {
                                opened.add(transport);
                            }
                            return transport;
                        })) {
            return pool.run(
                    email,
                    collections,
                    (transport, collection, remote) -> {
                        Watched engine = new Watched(server, transport);
                        try {
                            engine.sync(collection);
                        } finally {
                            remote.addAndGet(engine.inner.remoteSoFar());
                        }
                    });
        }
    }

    /** The bodies stored under the current account, by event id. */
    private Map<String, String> stored() {
        List<String> mine = new ArrayList<>();
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (calendar.accountEmail.equals(email)) {
                mine.add(calendar.id);
            }
        }
        Map<String, String> bodies = new HashMap<>();
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (mine.contains(event.collectionId)) {
                bodies.put(event.id, event.ical);
            }
        }
        return bodies;
    }

    @Test
    public void aFirstSyncOfThreeCalendarsTakesAFractionOfTheTime() {
        Server before = new Server(Reads.ONE_BY_ONE);
        List<String> collections = threeCalendars(before);
        CalendarPool.Outcome sequential = pass(before, collections, 1, new ArrayList<>());
        Map<String, String> wanted = stored();
        assertEquals(3 * SIZE, wanted.size());
        assertEquals("a listing and an event a request", 3 * (1 + SIZE), before.requests.get());

        use(OTHER);
        Server now = new Server(Reads.BATCHED);
        collections = threeCalendars(now);
        List<Transport> opened = new ArrayList<>();
        CalendarPool.Outcome outcome = pass(now, collections, CalendarPool.SIZE, opened);

        assertTrue(outcome.failures.isEmpty());
        assertEquals("the same entries", wanted, stored());
        assertEquals("a listing and two batches a calendar", 3 * 3, now.requests.get());
        assertEquals("one transport a worker", 3, opened.size());

        long before_ms = sequential.wall / 1_000_000;
        long after_ms = outcome.wall / 1_000_000;
        assertTrue(
                "120 events in " + after_ms + " ms, one by one in a row " + before_ms + " ms",
                after_ms * 4 < before_ms);
        assertTrue(
                "the network summed over every calendar, " + outcome.remote / 1_000_000 + " ms",
                outcome.remote / 1_000_000 >= 3 * 3 * LATENCY_MS);

        assertFalse("no transport served two workers at once", shared.get());
        assertFalse("no request sent holding the store", networkUnderLock.get());
        assertFalse("every write held the store's lock", writeOutsideLock.get());
        assertEquals("the store's writes never overlapped", 1, mostWriting.get());
    }

    @Test
    public void aListingThatCarriedTheBodiesReadsNoneAgain() {
        Server server = new Server(Reads.LISTED);
        List<String> collections = threeCalendars(server);

        pass(server, collections, CalendarPool.SIZE, new ArrayList<>());

        assertEquals("one listing a calendar, nothing else", 3, server.requests.get());
        assertEquals(0, server.bodiesRead.get());
        Map<String, String> bodies = stored();
        assertEquals(3 * SIZE, bodies.size());
        assertEquals(ical("work-7", "Event work-7"), bodies.get("work-7"));

        // NOTE: an entry changed on the server is taken from the listing too,
        // and the others, bound at their revision, are left as they were.
        server.calendars.get("msgraph://example/work")
                .put("work-7", new String[] {"ck2", ical("work-7", "Moved")});
        pass(server, collections, CalendarPool.SIZE, new ArrayList<>());

        assertEquals(0, server.bodiesRead.get());
        bodies = stored();
        assertEquals(3 * SIZE, bodies.size());
        assertEquals(ical("work-7", "Moved"), bodies.get("work-7"));
        assertEquals(ical("work-8", "Event work-8"), bodies.get("work-8"));
    }

    @Test
    public void aBatchedPassReadsOnlyWhatChanged() {
        Server server = new Server(Reads.BATCHED);
        List<String> collections = threeCalendars(server);
        pass(server, collections, CalendarPool.SIZE, new ArrayList<>());
        assertEquals(3 * SIZE, server.bodiesRead.get());

        server.calendars.get("msgraph://example/home")
                .put("home-3", new String[] {"ck2", ical("home-3", "Moved")});
        server.requests.set(0);
        pass(server, collections, CalendarPool.SIZE, new ArrayList<>());

        assertEquals("one listing a calendar and one read", 3 + 1, server.requests.get());
        assertEquals(3 * SIZE + 1, server.bodiesRead.get());
        assertEquals(ical("home-3", "Moved"), stored().get("home-3"));
    }

    @Test
    public void oneCalendarFailingLeavesTheOthers() {
        Server server = new Server(Reads.BATCHED);
        List<String> collections = threeCalendars(server);
        String failing = collections.get(1);

        CalendarPool.Outcome outcome;
        try (CalendarPool<Transport> pool =
                new CalendarPool<>(CalendarPool.SIZE, null, Transport::new)) {
            outcome =
                    pool.run(
                            email,
                            collections,
                            (transport, collection, remote) -> {
                                if (collection.equals(failing)) {
                                    throw new IllegalStateException("the server refused");
                                }
                                new Watched(server, transport).sync(collection);
                            });
        }

        assertEquals(1, outcome.failures.size());
        assertEquals(failing, outcome.failures.get(0).collection);
        assertEquals(2 * SIZE, stored().size());
    }
}
