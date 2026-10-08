package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
import java.util.function.Function;

/**
 * The body step an account's offline setting runs: the bodies it plans,
 * the network rule it waits on, and the upgrade it lands them through,
 * from the engine down to the store.
 *
 * <p>The server is one mailbox of two messages, a recent one and one from
 * three years back, answering the engine's listing and the source each
 * body is read from.
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

    private final String recent = Instant.now().minus(1, ChronoUnit.DAYS).toString();
    private final String old = Instant.now().minus(3 * 365, ChronoUnit.DAYS).toString();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "inbox")));
        inbox = store.collectionOf(EMAIL, "INBOX");
        accountId = new PimdirAccount(context).idOf(EMAIL);
        server = new Server();
        server.sync(inbox);
    }

    /** UID 2 is the recent message, UID 1 the old one. */
    private final class Server extends MailEngine {
        final List<String> read = new ArrayList<>();

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
            JSONArray items = new JSONArray().put(named("2", recent)).put(named("1", old));
            return page.put("items", items).put("complete", true).put("checkpoint", "cp");
        }

        @Override
        protected byte[] source(String mailbox, String handle) {
            read.add(handle);
            return LATIN1;
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

    /** The bodies the account's setting plans, newest first. */
    private List<MailBodies.Row> planned() {
        Function<String, String> boundOf =
                collection -> MailScope.sinceOf(context, accountId, collection);
        return MailBodies.wanted(store.bodyRows(List.of(inbox)), boundOf);
    }

    /** A step host downloading through the server, on the network given. */
    private MailBodies.Host host(boolean metered) {
        return new MailBodies.Host() {
            @Override
            public boolean allowed(String accountEmail) {
                return MailBodies.networkAllows(
                        true, metered, MailOffline.metered(context, accountId));
            }

            @Override
            public void download(String accountEmail, List<MailBodies.Row> rows) {
                server.download(inbox, rows);
            }
        };
    }

    @Test
    public void theStepRaisesOnlyBodiesWithinTheBound() {
        MailOffline.setPolicy(context, accountId, MailOffline.Policy.BACKGROUND);
        MailScope.set(context, accountId, 12);

        MailBodies.Run run = new MailBodies.Run();
        run.plan(EMAIL, planned());
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(false)));

        assertEquals("only the message within the bound is read", List.of("2"), server.read);
        assertArrayEquals(
                "the 8-bit body is stored as fetched", LATIN1, store.storedSource(inbox, "2"));
        assertNull("the one below the bound stays a summary", store.storedSource(inbox, "1"));
        assertTrue("and nothing is planned again", planned().isEmpty());
    }

    @Test
    public void aMailboxKeptWholeDownloadsPastTheBound() {
        MailScope.set(context, accountId, 12);
        MailOffline.setWhole(context, inbox, true);

        MailBodies.Run run = new MailBodies.Run();
        run.plan(EMAIL, planned());
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(false)));

        assertEquals("newest first", List.of("2", "1"), server.read);
        assertArrayEquals(LATIN1, store.storedSource(inbox, "1"));
    }

    @Test
    public void aMeteredNetworkDefersIt() {
        MailOffline.setPolicy(context, accountId, MailOffline.Policy.BACKGROUND);

        MailBodies.Run run = new MailBodies.Run();
        run.plan(EMAIL, planned());
        assertEquals(MailBodies.Step.PAUSED, MailBodies.step(run, host(true)));
        assertTrue("nothing read on a metered network", server.read.isEmpty());
        assertNull(store.storedSource(inbox, "2"));

        // NOTE: the plan is kept, so the account's switch picks it up.
        MailOffline.setMetered(context, accountId, true);
        assertEquals(MailBodies.Step.DONE, MailBodies.step(run, host(true)));
        assertEquals(List.of("2", "1"), server.read);
    }
}
