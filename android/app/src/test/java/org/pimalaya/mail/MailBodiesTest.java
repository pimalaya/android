package org.pimalaya.mail;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The body step: the bodies a frontier and a mailbox kept whole plan, the
 * Wi-Fi it waits for, an arrival taken whatever its date, and the upgrade
 * it lands them through, each body read into a file and filed as the
 * object it is, from the engine down to the store.
 *
 * <p>The server is one mailbox of four messages: three from the last days
 * (one of 40 KB, one of 3 MB, one of unknown size carrying an attachment)
 * and one from three years back, answering the engine's listing and the
 * source each body is read from.
 */
@RunWith(RobolectricTestRunner.class)
public class MailBodiesTest {
    private static final String EMAIL = "jane@example.com";

    /** A message body that is not UTF-8: an 8-bit Latin-1 part. */
    private static final byte[] LATIN1 =
            "Subject: café\r\n\r\ncafé\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private Context context;
    private PimdirDb pimdir;
    private MailStore store;
    private String accountId;
    private String inbox;
    private Server server;

    private final String window = Instant.now().minus(30, ChronoUnit.DAYS).toString();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "inbox")));
        inbox = store.collectionOf(EMAIL, "INBOX");
        accountId = new PimdirAccount(context).idOf(EMAIL);
        MailWindow.set(context, accountId, window);
        server = new Server();
        server.sync(inbox);
    }

    /** UIDs 4 to 2 are recent, newest first; UID 1 is three years old. */
    private final class Server extends MailEngine {
        final List<String> read = new ArrayList<>();
        final List<String> pushed = new ArrayList<>();

        /** How many more reads of a handle the server fails, -1 for all of them. */
        final java.util.Map<String, Integer> refusing = new java.util.HashMap<>();

        /** The handles the server answers gone (deleted since they were listed). */
        final java.util.Set<String> vanished = new java.util.HashSet<>();


        Server() {
            super(pimdir, new PimalayaClient(), null, accountId);
        }

        @Override
        String floor(String collection, String before, int count) {
            return null;
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            JSONObject page = new JSONObject().put("vanished", new JSONArray()).put("last", true);
            if ("delta".equals(yielded.getJSONObject("listing").getString("kind"))) {
                return page.put("items", new JSONArray()).put("complete", false)
                        .put("checkpoint", "cp");
            }
            JSONArray items =
                    new JSONArray()
                            .put(named("4", daysBack(1), 0, true))
                            .put(named("3", daysBack(2), 40_000, false))
                            .put(named("2", daysBack(3), 3_000_000, false))
                            .put(named("1", daysBack(3 * 365), 1_000, false));
            return page.put("items", items).put("complete", true).put("checkpoint", "cp");
        }

        @Override
        protected java.util.Map<String, java.io.File> sources(
                String mailbox, List<String> handles, java.io.File dir) {
            java.util.Map<String, java.io.File> files = new java.util.HashMap<>();
            for (String handle : handles) {
                read.add(handle);
                if (vanished.contains(handle)) {
                    gone.add(handle);
                    continue;
                }
                int refused = refusing.getOrDefault(handle, 0);
                if (refused != 0) {
                    refusing.put(handle, refused - 1);
                    continue;
                }
                java.io.File file = new java.io.File(dir, handle + ".eml");
                try {
                    java.nio.file.Files.write(file.toPath(), LATIN1);
                } catch (java.io.IOException failure) {
                    throw new AssertionError(failure);
                }
                files.put(handle, file);
            }
            return files;
        }

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            JSONArray changes = yielded.getJSONArray("changes");
            for (int index = 0; index < changes.length(); index++) {
                pushed.add(changes.getJSONObject(index).toString());
            }
            return new JSONObject().put("results", new JSONArray());
        }
    }

    private static String daysBack(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).toString();
    }

    private static JSONObject named(String handle, String date, long size, boolean attachment)
            throws JSONException {
        return new JSONObject()
                .put("handle", handle)
                .put("flags", new JSONArray())
                .put("linkId", handle)
                .put(
                        "summary",
                        PimdirSummary.mail(
                                null, "Subject " + handle, "", "a@example.org", null, date, size,
                                attachment))
                .put("sortKey", PimdirSummary.mailSortKey(date));
    }

    /** The bodies the inbox plans from {@code since} on (null for all), newest first. */
    private List<MailBodies.Row> planned(String since) {
        return MailBodies.wanted(store.bodyRows(List.of(inbox), since));
    }

    /** A step host downloading through the server, on Wi-Fi or not. */
    private MailBodies.Host host(boolean wifi) {
        return new MailBodies.Host() {
            @Override
            public boolean allowed(String accountEmail) {
                return wifi;
            }

            @Override
            public List<MailBodies.Row> download(String accountEmail, List<MailBodies.Row> rows) {
                return server.download(inbox, rows);
            }
        };
    }

    @Test
    public void theStepRaisesOnlyBodiesWithinTheWindow() {
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(window));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));

        assertEquals("newest first, within the window", List.of("4", "3", "2"), server.read);
        assertArrayEquals(
                "the 8-bit body is stored as fetched", LATIN1, store.storedSource(inbox, "4"));
        assertNull("the one below the window stays a summary", store.storedSource(inbox, "1"));
        assertTrue("and nothing is planned again", planned(window).isEmpty());
    }

    @Test
    public void aMailboxKeptWholeDownloadsPastTheWindow() {
        MailOffline.setWhole(context, inbox, true);
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(null));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));

        assertEquals("newest first", List.of("4", "3", "2", "1"), server.read);
        assertArrayEquals(LATIN1, store.storedSource(inbox, "1"));
    }

    @Test
    public void theJunkAndTheTrashDownloadOnlyKeptWhole() {
        assertTrue(MailBodies.downloads("inbox", false));
        assertTrue(MailBodies.downloads(null, false));
        assertFalse(MailBodies.downloads("junk", false));
        assertFalse(MailBodies.downloads("trash", false));
        assertTrue(MailBodies.downloads("trash", true));

        assertTrue(MailBodies.takes(daysBack(1), window, "inbox", false));
        assertFalse("below the window", MailBodies.takes(daysBack(60), window, "inbox", false));
        assertFalse("undated under a window", MailBodies.takes("", window, "inbox", false));
        assertTrue("undated under all mail", MailBodies.takes("", null, "inbox", false));
        assertFalse(MailBodies.takes(daysBack(1), window, "junk", false));
        assertTrue(MailBodies.takes(daysBack(60), window, "trash", true));
    }

    @Test
    public void nothingDownloadsOffWifi() {
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(window));
        assertEquals(MailBodies.Step.PAUSED, MailBodies.step(run, host(false)));
        assertTrue("whatever the size: " + server.read, server.read.isEmpty());

        // NOTE: the plan is kept, so Wi-Fi picks it up.
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));
        assertEquals(List.of("4", "3", "2"), server.read);
    }

    @Test
    public void anArrivalDownloadsWhateverItsDate() {
        MailBodies.Row old = planned(null).get(3);
        assertEquals("1", old.linkId);
        server.download(inbox, List.of(old));
        assertTrue("below the frontier, a plan takes nothing", server.read.isEmpty());

        server.download(
                inbox,
                List.of(
                        new MailBodies.Row(
                                inbox, "1", old.sortKey, false, old.size, old.attachment, true)));
        assertEquals(List.of("1"), server.read);
        assertArrayEquals(LATIN1, store.storedSource(inbox, "1"));
    }

    @Test
    public void twoNewestFirstListsMergeNewestFirst() {
        MailBodies.Row a = new MailBodies.Row("x", "a", "2026-10-03T00:00:00Z", false, null, null);
        MailBodies.Row b = new MailBodies.Row("y", "b", "2026-10-02T00:00:00Z", false, null, null);
        MailBodies.Row c = new MailBodies.Row("x", "c", "2026-10-01T00:00:00Z", false, null, null);
        List<MailBodies.Row> merged = MailBodies.merged(List.of(a, c), List.of(b));
        assertEquals(List.of(a, b, c), merged);
    }

    @Test
    public void aWindowMovedLaterFreesTheBodiesBelowIt() {
        MailWindow.set(context, accountId, null);
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(null));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));

        // NOTE: a mailbox kept whole keeps all of it.
        MailOffline.setWhole(context, inbox, true);
        assertEquals(0, store.release(EMAIL, window));
        MailOffline.setWhole(context, inbox, false);

        assertEquals("the one below the window", 1, store.release(EMAIL, window));
        assertNull(store.storedSource(inbox, "1"));
        assertArrayEquals("the window's stay", LATIN1, store.storedSource(inbox, "2"));
        MailStore.Query all = store.query((account, collection) -> true, false, false, "");
        assertEquals("its header stays, for search", 4, store.count(all));
        assertEquals("and it plans again under all mail", 1, planned(null).size());

        // NOTE: a plan made before the window moved later fetches nothing
        // it freed: the window is read again as the chunk downloads.
        MailWindow.set(context, accountId, window);
        server.read.clear();
        run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(null));
        MailBodies.step(run, host(true));
        assertTrue("nothing fetched back: " + server.read, server.read.isEmpty());
        assertNull(store.storedSource(inbox, "1"));
    }

    @Test
    public void aBodyFetchedAgainAfterAReleaseIsNoEditToPush() {
        MailWindow.set(context, accountId, null);
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(null));
        MailBodies.step(run, host(true));
        assertEquals(1, store.release(EMAIL, window));

        run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(null));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));
        assertArrayEquals(LATIN1, store.storedSource(inbox, "1"));

        server.sync(inbox);
        assertTrue("nothing pushed: " + server.pushed, server.pushed.isEmpty());
    }

    /** Steps a run until it is done, at most {@code steps} times. */
    private static void drain(MailBodies.Run run, MailBodies.Host host, int steps) {
        for (int step = 0; step < steps; step++) {
            if (MailBodies.step(run, host) == MailBodies.Step.DONE) {
                return;
            }
        }
        throw new AssertionError("the run never ended");
    }

    @Test
    public void aBodyRefusedOnceIsTriedAgainLaterInTheRun() {
        server.refusing.put("3", 1);
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(window));
        assertEquals(MailBodies.Step.AGAIN, MailBodies.step(run, host(true)));
        assertNull("not landed yet", store.storedSource(inbox, "3"));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));

        assertEquals("tried again after the others", List.of("4", "3", "2", "3"), server.read);
        assertArrayEquals(LATIN1, store.storedSource(inbox, "3"));
        assertFalse(MailState.of(context).failed.contains(MailOwed.key(inbox, "3")));
    }

    @Test
    public void aBodyFailingEveryAttemptIsNotPlannedAgainUntilItsMailboxIsListedAnew() {
        server.refusing.put("3", -1);
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(window));
        drain(run, host(true), 10);

        assertEquals(
                MailBodies.ATTEMPTS,
                server.read.stream().filter(handle -> handle.equals("3")).count());
        assertNull(store.storedSource(inbox, "3"));
        assertTrue(MailState.of(context).failed.contains(MailOwed.key(inbox, "3")));
        assertArrayEquals("the others landed", LATIN1, store.storedSource(inbox, "2"));

        // NOTE: what restarted the run every few seconds on the phone: a
        // new plan right after the last, nothing listed in between.
        server.read.clear();
        for (int again = 0; again < 3; again++) {
            run = new MailBodies.Run(MailState.of(context));
            assertEquals("nothing planned", 0, run.plan(EMAIL, planned(window)));
            assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));
        }
        assertTrue("never asked again", server.read.isEmpty());

        server.refusing.clear();
        server.sync(inbox);
        assertFalse("a new listing", MailState.of(context).failed.contains(MailOwed.key(inbox, "3")));
        run = new MailBodies.Run(MailState.of(context));
        assertEquals(1, run.plan(EMAIL, planned(window)));
        drain(run, host(true), 10);
        assertArrayEquals(LATIN1, store.storedSource(inbox, "3"));
        assertFalse(MailState.of(context).failed.contains(MailOwed.key(inbox, "3")));
    }

    @Test
    public void aBodyGoneIsOwedNoMoreAndNotTriedAgain() {
        String key = MailOwed.key(inbox, "3");
        MailOwed.owe(context, inbox, List.of("3"));
        server.vanished.add("3");
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(window));
        drain(run, host(true), 10);

        assertEquals(
                "asked once, no attempt after",
                1,
                server.read.stream().filter(handle -> handle.equals("3")).count());
        assertTrue(MailState.of(context).gone.contains(key));
        assertFalse("gone is no failure", MailState.of(context).failed.contains(key));
        assertFalse("owed no more", MailOwed.owed(context, inbox, "3"));
        assertArrayEquals("the others landed", LATIN1, store.storedSource(inbox, "2"));

        server.read.clear();
        run = new MailBodies.Run(MailState.of(context));
        assertEquals("nothing planned", 0, run.plan(EMAIL, planned(window)));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));
        assertTrue(server.read.isEmpty());

        server.sync(inbox);
        assertFalse("its mailbox listed anew", MailState.of(context).gone.contains(key));
    }

    @Test
    public void aDownloadFailingWholeIsTriedAgainAndTwiceInARowLeavesTheRun() {
        MailBodies.Host down =
                new MailBodies.Host() {
                    @Override
                    public boolean allowed(String accountEmail) {
                        return true;
                    }

                    @Override
                    public List<MailBodies.Row> download(
                            String accountEmail, List<MailBodies.Row> rows) {
                        throw new IllegalStateException("No session");
                    }
                };
        MailBodies.Run run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(window));
        assertEquals("put back once", MailBodies.Step.AGAIN, MailBodies.step(run, down));
        assertEquals("then the account leaves", MailBodies.Step.DONE, MailBodies.step(run, down));
        assertTrue("no session marks no message failed", MailState.of(context).failed.isEmpty());

        // NOTE: a token expiring mid-run: the next download renews it.
        int[] calls = {0};
        MailBodies.Host renewed =
                new MailBodies.Host() {
                    @Override
                    public boolean allowed(String accountEmail) {
                        return true;
                    }

                    @Override
                    public List<MailBodies.Row> download(
                            String accountEmail, List<MailBodies.Row> rows) {
                        if (calls[0]++ == 0) {
                            throw new IllegalStateException("Token expired");
                        }
                        return server.download(inbox, rows);
                    }
                };
        run = new MailBodies.Run(MailState.of(context));
        run.plan(EMAIL, planned(window));
        drain(run, renewed, 3);
        assertEquals(List.of("4", "3", "2"), server.read);
        assertTrue(planned(window).isEmpty());
    }

    @Test
    public void anArrivalIsOwedBesideTheAccountUntilItsBodyLands() throws JSONException {
        // NOTE: a delta read through the engine's own listing, which notes
        // what it brings new.
        JSONObject delta =
                new JSONObject()
                        .put("items", new JSONArray().put(named("5", daysBack(400), 1_000, false)))
                        .put("vanished", new JSONArray())
                        .put("complete", false)
                        .put("last", true)
                        .put("checkpoint", "cp2");
        PimalayaClient listing =
                new PimalayaClient() {
                    @Override
                    public String enumerateMailboxRaw(
                            org.pimalaya.client.MailSession session,
                            String mailbox,
                            JSONObject request) {
                        return delta.toString();
                    }
                };
        new MailEngine(pimdir, listing, null, accountId).sync(inbox);
        assertTrue("a delta's new member is owed", MailOwed.owed(context, inbox, "5"));
        assertFalse("one already held is not", MailOwed.owed(context, inbox, "4"));
        assertEquals(List.of(MailOwed.key(inbox, "5")), MailOwed.all(context));

        MailStore.StoredMessage stored = store.message(inbox, "5");
        MailBodies.Row row =
                new MailBodies.Row(
                        inbox, "5", stored.sortKey, false, stored.size, stored.attachment, true);
        assertTrue(server.download(inbox, List.of(row)).isEmpty());
        assertArrayEquals(LATIN1, store.storedSource(inbox, "5"));
        assertFalse("owed no more", MailOwed.owed(context, inbox, "5"));
    }
}
