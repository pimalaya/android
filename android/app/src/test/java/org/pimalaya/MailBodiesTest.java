package org.pimalaya;

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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The body step: the bodies a window and a mailbox kept whole plan, the
 * metered cap it waits on, and the upgrade it lands them through, from the
 * engine down to the store.
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
        protected byte[] source(String mailbox, String handle) {
            read.add(handle);
            return LATIN1;
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

    /** A step host downloading through the server, on the network given. */
    private MailBodies.Host host(boolean metered) {
        return new MailBodies.Host() {
            @Override
            public boolean allowed(String accountEmail) {
                return true;
            }

            @Override
            public boolean metered() {
                return metered;
            }

            @Override
            public void download(String accountEmail, List<MailBodies.Row> rows) {
                server.download(inbox, rows);
            }
        };
    }

    @Test
    public void theStepRaisesOnlyBodiesWithinTheWindow() {
        MailBodies.Run run = new MailBodies.Run();
        run.plan(EMAIL, planned(window));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(false)));

        assertEquals("newest first, within the window", List.of("4", "3", "2"), server.read);
        assertArrayEquals(
                "the 8-bit body is stored as fetched", LATIN1, store.storedSource(inbox, "4"));
        assertNull("the one below the window stays a summary", store.storedSource(inbox, "1"));
        assertTrue("and nothing is planned again", planned(window).isEmpty());
    }

    @Test
    public void aMailboxKeptWholeDownloadsPastTheWindow() {
        MailOffline.setWhole(context, inbox, true);
        MailBodies.Run run = new MailBodies.Run();
        run.plan(EMAIL, planned(null));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(false)));

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
    public void aMeteredNetworkTakesTheSmallBodiesAlone() {
        assertTrue(MailBodies.fits(MailBodies.CAP, null));
        assertFalse(MailBodies.fits(MailBodies.CAP + 1, 0));
        assertTrue("unknown, carrying no attachment", MailBodies.fits(null, 0));
        assertFalse("unknown, carrying one", MailBodies.fits(null, 1));
        assertFalse("unknown, never examined", MailBodies.fits(null, null));

        MailBodies.Run run = new MailBodies.Run();
        run.plan(EMAIL, planned(window));
        assertEquals(MailBodies.Step.AGAIN, MailBodies.step(run, host(true)));
        assertEquals("the 40 KB body alone", List.of("3"), server.read);
        assertEquals(
                "the larger ones wait", MailBodies.Step.PAUSED, MailBodies.step(run, host(true)));
        assertNull(store.storedSource(inbox, "2"));

        // NOTE: the plan is kept, so an unmetered network picks it up.
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(false)));
        assertEquals(List.of("3", "4", "2"), server.read);
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
        MailBodies.Run run = new MailBodies.Run();
        run.plan(EMAIL, planned(null));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(false)));

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
        run = new MailBodies.Run();
        run.plan(EMAIL, planned(null));
        MailBodies.step(run, host(false));
        assertTrue("nothing fetched back: " + server.read, server.read.isEmpty());
        assertNull(store.storedSource(inbox, "1"));
    }

    @Test
    public void aBodyFetchedAgainAfterAReleaseIsNoEditToPush() {
        MailWindow.set(context, accountId, null);
        MailBodies.Run run = new MailBodies.Run();
        run.plan(EMAIL, planned(null));
        MailBodies.step(run, host(false));
        assertEquals(1, store.release(EMAIL, window));

        run = new MailBodies.Run();
        run.plan(EMAIL, planned(null));
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(false)));
        assertArrayEquals(LATIN1, store.storedSource(inbox, "1"));

        server.sync(inbox);
        assertTrue("nothing pushed: " + server.pushed, server.pushed.isEmpty());
        MailStore.Query all = store.query((account, collection) -> true, false, false, "");
        for (MailStore.StoredMessage message : store.all(all)) {
            assertFalse("no change owed for " + message.id, message.unsynced);
        }
    }
}
