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

import java.util.ArrayList;
import java.util.List;

/**
 * A Graph delta lists a message by id and markers alone: one the store binds
 * keeps the summary it holds, every other one is read with its summary, one
 * gone since is left out; and a round over a scope the store covers is told
 * apart from a first chunk, which lists its band.
 */
@RunWith(RobolectricTestRunner.class)
public class MailNamingTest {
    private static final String EMAIL = "jane@example.com";
    private static final String DATE = "2026-10-01T08:00:00Z";

    private PimdirDb pimdir;
    private MailStore store;
    private String inbox;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("Inbox", "inbox")));
        inbox = store.collectionOf(EMAIL, "Inbox");
    }

    /** A mailbox holding one message, {@code 1}, that reads ids from a list. */
    private final class Server extends MailEngine {
        final List<List<String>> reads = new ArrayList<>();

        Server() {
            super(
                    pimdir,
                    new PimalayaClient(),
                    null,
                    new PimdirAccount(RuntimeEnvironment.getApplication()).idOf(EMAIL));
        }

        @Override
        String floor(String collection, String before, int count) {
            return null;
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            return new JSONObject()
                    .put("items", new JSONArray().put(named("1")))
                    .put("vanished", new JSONArray())
                    .put("complete", true)
                    .put("last", true)
                    .put("checkpoint", "graph-delta:link");
        }

        @Override
        protected JSONArray read(String collection, List<String> handles) {
            reads.add(handles);
            JSONArray read = new JSONArray();
            for (String handle : handles) {
                if (handle.equals("2")) {
                    read.put(named(handle));
                }
            }
            return read;
        }

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            return new JSONObject().put("results", new JSONArray());
        }
    }

    private static JSONObject named(String handle) {
        try {
            return new JSONObject()
                    .put("handle", handle)
                    .put("flags", new JSONArray())
                    .put("linkId", handle)
                    .put(
                            "summary",
                            PimdirSummary.mail(
                                    null, "Subject " + handle, "", "a@example.org", null, DATE, 0,
                                    false))
                    .put("sortKey", PimdirSummary.mailSortKey(DATE));
        } catch (JSONException failure) {
            throw new AssertionError(failure);
        }
    }

    private static JSONObject unnamed(String handle) throws JSONException {
        return new JSONObject()
                .put("handle", handle)
                .put("flags", new JSONArray().put(MailEngine.SEEN))
                .put("linkId", handle);
    }

    @Test
    public void aDeltaMemberIsNamedByTheStoreOrReadById() throws Exception {
        Server server = new Server();
        server.sync(inbox);

        JSONObject page =
                new JSONObject()
                        .put(
                                "items",
                                new JSONArray()
                                        .put(unnamed("1"))
                                        .put(unnamed("2"))
                                        .put(unnamed("3")));
        server.nameUnnamed(inbox, page);

        assertEquals(List.of(List.of("2", "3")), server.reads);
        JSONArray items = page.getJSONArray("items");
        assertEquals(2, items.length());

        JSONObject bound = items.getJSONObject(0);
        assertEquals("1", bound.getString("handle"));
        assertFalse("the store keeps its summary", bound.has("summary"));
        assertEquals(MailEngine.SEEN, bound.getJSONArray("flags").getString(0));

        JSONObject read = items.getJSONObject(1);
        assertEquals("2", read.getString("handle"));
        assertTrue(read.has("summary"));
    }

    @Test
    public void aRoundOverACoveredScopeIsToldFromAFirstChunk() throws Exception {
        Server server = new Server();
        JSONObject scope = new JSONObject().put("since", DATE);
        assertFalse("never listed", server.covered(inbox, scope));

        server.sync(inbox);
        assertTrue("listed whole", server.covered(inbox, scope));
        assertTrue(server.covered(inbox, new JSONObject()));
    }
}
