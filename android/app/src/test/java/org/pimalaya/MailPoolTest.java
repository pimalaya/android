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
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An account's mailboxes run side by side on a pool of sessions: a pass of N
 * mailboxes takes about N / size of the time one after another did, the
 * inbox is taken first, no session serves two workers at once, and the
 * store's writes never overlap.
 *
 * <p>The server answers every request after a fixed latency, which is what a
 * pass waits on; everything from the engine down to the store is the real
 * thing.
 */
@RunWith(RobolectricTestRunner.class)
public class MailPoolTest {
    private static final String EMAIL = "jane@example.com";
    private static final Instant TOP = Instant.parse("2026-10-01T00:00:00Z");

    /** What one request to the fake server takes. */
    private static final long LATENCY_MS = 120;

    /** Messages in each mailbox, fewer than a chunk: a first pass lists them all. */
    private static final int SIZE = 20;

    private Context context;
    private PimdirDb pimdir;
    private MailStore store;
    private String accountId;

    /** The writes in flight, and the most ever seen at once. */
    private final AtomicInteger writing = new AtomicInteger();
    private final AtomicInteger mostWriting = new AtomicInteger();
    private final AtomicBoolean writeOutsideLock = new AtomicBoolean();

    /** A session used by two workers at once, if any ever was. */
    private final AtomicBoolean shared = new AtomicBoolean();

    /** The mailboxes in the order their listing began. */
    private final List<String> started = Collections.synchronizedList(new ArrayList<>());

    /**
     * When set, the listing of {@code first} holds its worker until the one of
     * {@code last} has begun, and every other listing waits for the one of
     * {@code first}: what a listing begins after is then the order the pool
     * took the mailboxes in, never which worker won the store's lock first.
     */
    private volatile String first;
    private volatile String last;
    private final CountDownLatch firstBegun = new CountDownLatch(1);
    private final CountDownLatch lastBegun = new CountDownLatch(1);

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        accountId = new PimdirAccount(context).idOf(EMAIL);
    }

    /** A session of the fake server, which knows when two use it at once. */
    private final class Session implements AutoCloseable {
        private final AtomicBoolean busy = new AtomicBoolean();
        boolean closed;

        void request() throws InterruptedException {
            if (busy.getAndSet(true)) {
                shared.set(true);
            }
            try {
                Thread.sleep(LATENCY_MS);
            } finally {
                busy.set(false);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Waits for a gate, bounded: a pool taking the mailboxes out of order
     * then fails the order it is checked on rather than hanging.
     */
    private static void await(CountDownLatch gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
        }
    }

    /** A message's date, {@code hours} before the top of the mailbox. */
    private static String hoursBack(long hours) {
        return TOP.minusSeconds(hours * 3600).toString();
    }

    /** The engine one worker runs a mailbox with, over its session. */
    private final class Server extends MailEngine {
        private final Session session;
        private final String failing;

        Server(Session session, String failing) {
            super(pimdir, new PimalayaClient(), null, accountId);
            this.session = session;
            this.failing = failing;
        }

        @Override
        String floor(String collection, String before, int count) {
            try {
                session.request();
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
            }
            return null;
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            String collection = yielded.getString("collection");
            started.add(collection);
            if (first != null) {
                if (collection.equals(first)) {
                    firstBegun.countDown();
                    await(lastBegun);
                } else {
                    if (collection.equals(last)) {
                        lastBegun.countDown();
                    }
                    await(firstBegun);
                }
            }
            // NOTE: timed as the real driver times its bridge call.
            long asked = System.nanoTime();
            try {
                session.request();
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
            }
            remote(System.nanoTime() - asked);
            if (collection.equals(failing)) {
                throw new IllegalStateException("the server refused " + collection);
            }
            JSONArray items = new JSONArray();
            for (int index = 0; index < SIZE; index++) {
                String date = hoursBack(index);
                items.put(named(collection + "-" + index, date));
            }
            return new JSONObject()
                    .put("items", items)
                    .put("vanished", new JSONArray())
                    .put("complete", true)
                    .put("last", true)
                    .put("checkpoint", "cp");
        }

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            return new JSONObject().put("results", new JSONArray());
        }

        @Override
        protected void applied(JSONArray effects) {
            if (!Thread.holdsLock(PimdirEngine.STORE)) {
                writeOutsideLock.set(true);
            }
            int now = writing.incrementAndGet();
            mostWriting.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            writing.decrementAndGet();
        }
    }

    private static JSONObject named(String handle, String date) throws JSONException {
        return new JSONObject()
                .put("handle", handle)
                .put("flags", new JSONArray())
                .put("linkId", handle)
                .put(
                        "summary",
                        PimdirSummary.mail(
                                null, "Subject " + handle, "", "a@example.org", null, date, 0,
                                false))
                .put("sortKey", PimdirSummary.mailSortKey(date));
    }

    /** The account's mailboxes, as a server lists them: not in a pass's order. */
    private List<Mailbox> roster(int others) {
        List<Mailbox> mailboxes = new ArrayList<>();
        mailboxes.add(new Mailbox("Junk", "junk"));
        for (int index = 0; index < others; index++) {
            mailboxes.add(new Mailbox("Folder " + index, ""));
        }
        mailboxes.add(new Mailbox("Sent", "sent"));
        mailboxes.add(new Mailbox("Inbox", "inbox"));
        return mailboxes;
    }

    /** The collections of a roster in the order a pass takes them. */
    private List<String> ordered(List<Mailbox> roster) {
        store.replaceMailboxes(EMAIL, roster);
        List<String> collections = new ArrayList<>();
        for (Mailbox mailbox : MailEngine.ordered(roster)) {
            collections.add(store.collectionOf(EMAIL, mailbox.name));
        }
        return collections;
    }

    private long stored(String collection) {
        MailStore.Query query = store.query((account, name) -> true, false, false, "");
        long all = 0;
        for (MailStore.StoredMessage message : store.all(query)) {
            if (message.collection.equals(collection)) {
                all++;
            }
        }
        return all;
    }

    /** One pass over the collections on a pool of {@code size}, the failing one refused. */
    private MailPool.Outcome pass(
            List<String> collections, int size, List<Session> opened, String failing) {
        try (MailPool<Session> pool =
                new MailPool<>(
                        size,
                        null,
                        () -> {
                            Session session = new Session();
                            synchronized (opened) {
                                opened.add(session);
                            }
                            return session;
                        })) {
            return pool.run(
                    EMAIL,
                    collections,
                    session -> new Server(session, failing),
                    MailEngine::sync,
                    null);
        }
    }

    @Test
    public void aPassOfNMailboxesTakesAboutNOverTheSizeOfThePool() {
        List<String> collections = ordered(roster(9));
        assertEquals(12, collections.size());

        long sequential = 12 * 2 * LATENCY_MS;
        List<Session> opened = new ArrayList<>();
        MailPool.Outcome outcome = pass(collections, 4, opened, null);

        assertTrue(outcome.failures.isEmpty());
        for (String collection : collections) {
            assertEquals(SIZE, stored(collection));
        }
        assertEquals("one session a worker, opened as needed", 4, opened.size());
        for (Session session : opened) {
            assertTrue("closed with the pool", session.closed);
        }

        long wall = outcome.wall / 1_000_000;
        assertTrue(
                "12 mailboxes on 4 sessions in " + wall + " ms, sequential " + sequential + " ms",
                wall < sequential / 2);
        assertTrue(
                "the network summed over every mailbox, " + outcome.remote / 1_000_000 + " ms",
                outcome.remote / 1_000_000 >= sequential);

        assertFalse("no session served two workers at once", shared.get());
        assertFalse("every write held the store's lock", writeOutsideLock.get());
        assertEquals("the store's writes never overlapped", 1, mostWriting.get());
    }

    @Test
    public void theInboxIsTakenFirstAndTheJunkLast() {
        List<String> collections = ordered(roster(3));
        String inbox = store.collectionOf(EMAIL, "Inbox");
        String sent = store.collectionOf(EMAIL, "Sent");
        String junk = store.collectionOf(EMAIL, "Junk");
        assertEquals(inbox, collections.get(0));
        assertEquals(sent, collections.get(1));
        assertEquals(junk, collections.get(collections.size() - 1));

        pass(collections, 1, new ArrayList<>(), null);
        assertEquals("one session takes them in the pass's order", collections, started);

        // NOTE: a worker reaches its listing past the store's lock, which is
        // not fair, so two workers may begin listings in another order than
        // the pool took them. The inbox's worker is held until the junk
        // begins, so the other one takes the rest one after another.
        started.clear();
        first = inbox;
        last = junk;
        pass(collections, 2, new ArrayList<>(), null);
        assertTrue(
                "the inbox among the first two begun: " + started,
                started.subList(0, 2).contains(inbox));
        assertEquals("the junk begun last", junk, started.get(started.size() - 1));
    }

    @Test
    public void oneMailboxFailingLeavesTheOthersRunning() {
        List<String> collections = ordered(roster(4));
        String failing = store.collectionOf(EMAIL, "Folder 1");

        MailPool.Outcome outcome = pass(collections, 3, new ArrayList<>(), failing);

        assertEquals(1, outcome.failures.size());
        assertEquals(failing, outcome.failures.get(0).collection);
        assertTrue(outcome.failure().getMessage().contains("the server refused"));
        for (String collection : collections) {
            assertEquals(collection.equals(failing) ? 0 : SIZE, stored(collection));
        }
    }

    @Test
    public void theCallersSessionIsTheFirstAndStaysOpen() {
        List<String> collections = ordered(roster(0));
        Session mine = new Session();
        List<Session> opened = new ArrayList<>();
        MailPool.Outcome outcome;
        try (MailPool<Session> pool =
                new MailPool<>(
                        4,
                        mine,
                        () -> {
                            Session session = new Session();
                            synchronized (opened) {
                                opened.add(session);
                            }
                            return session;
                        })) {
            outcome =
                    pool.run(
                            EMAIL,
                            collections,
                            session -> new Server(session, null),
                            MailEngine::sync,
                            null);
        }
        assertEquals("three mailboxes, three workers", 3, outcome.sessions);
        assertEquals("the caller's session serves one, two more opened", 2, opened.size());
        assertFalse("the caller closes its own", mine.closed);
    }

    @Test
    public void aFillStepTakesAsManyOfAnAccountAsItsPoolRuns() {
        List<MailStore.Edge> edges =
                List.of(
                        new MailStore.Edge(EMAIL, "Projets", "Projets", "", true,
                                "2026-09-20T00:00:00Z"),
                        new MailStore.Edge(EMAIL, "Sent", "Sent", "sent", true,
                                "2026-09-05T00:00:00Z"),
                        new MailStore.Edge(EMAIL, "INBOX", "INBOX", "inbox", true,
                                "2026-09-08T00:00:00Z"),
                        new MailStore.Edge("joe@example.org", "INBOX", "joe-INBOX", "inbox",
                                true, "2026-09-08T00:00:00Z"),
                        new MailStore.Edge(EMAIL, "Archive", "Archive", "", false, null));

        List<MailStore.Edge> batch = MailFill.batch(edges, email -> email.equals(EMAIL) ? 3 : 1);
        List<String> taken = new ArrayList<>();
        for (MailStore.Edge edge : batch) {
            taken.add(edge.collection);
        }
        assertEquals(List.of("INBOX", "joe-INBOX", "Sent", "Archive"), taken);
    }
}
