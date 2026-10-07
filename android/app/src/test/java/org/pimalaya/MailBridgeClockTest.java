package org.pimalaya;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * Where a first round's time goes once the network is out of it: the
 * bridge's JSON on both sides, the engine, and the store, page by page.
 *
 * <p>The pages are what a connector answers, read from the string the
 * native call returns, so the clock covers everything but the network.
 * The numbers are printed for the record; what is asserted is only that
 * every member landed.
 */
@RunWith(RobolectricTestRunner.class)
public class MailBridgeClockTest {
    private static final int PAGES = 4;
    private static final int PAGE = 1000;

    /** One page of named members, as the native call's string. */
    private static String page(int index) throws JSONException {
        JSONArray items = new JSONArray();
        for (int row = 0; row < PAGE; row++) {
            int number = index * PAGE + row;
            String handle = String.valueOf(1_000_000 - number);
            String date =
                    String.format(
                            "2026-%02d-%02dT%02d:%02d:00Z",
                            10 - number / 20_000 % 9,
                            28 - number / 1000 % 27,
                            23 - number / 60 % 24,
                            59 - number % 60);
            items.put(
                    new JSONObject()
                            .put("handle", handle)
                            .put("flags", new JSONArray().put("\\Seen"))
                            .put("linkId", handle)
                            .put(
                                    "summary",
                                    PimdirSummary.mail(
                                            "<" + handle + "@example.org>",
                                            "A subject long enough to be a real one " + handle,
                                            "Sender " + number % 50,
                                            "sender" + number % 50 + "@example.org",
                                            null,
                                            date,
                                            4096,
                                            false))
                            .put("sortKey", PimdirSummary.mailSortKey(date)));
        }
        JSONObject page =
                new JSONObject()
                        .put("items", items)
                        .put("vanished", new JSONArray())
                        .put("complete", true)
                        .put("last", index == PAGES - 1);
        if (index < PAGES - 1) {
            page.put("cursor", "1:" + (1_000_000 - (index + 1) * PAGE));
        } else {
            page.put("checkpoint", "1:42");
        }
        return page.toString();
    }

    @Test
    public void aFirstRoundIsTimedPageByPage() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        MailStore store = new MailStore(context, pimdir);
        store.replaceMailboxes("jane@example.com", List.of(new Mailbox("INBOX", "inbox")));
        String collection = store.collectionOf("jane@example.com", "INBOX");

        String[] pages = new String[PAGES];
        for (int index = 0; index < PAGES; index++) {
            pages[index] = page(index);
        }

        PimdirEngine remote =
                new PimdirEngine(pimdir, new PimalayaClient()) {
                    @Override
                    protected PimDomain domain() {
                        return PimDomain.MAIL;
                    }

                    int next;

                    @Override
                    protected boolean listingsNamed() {
                        return true;
                    }

                    @Override
                    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
                        JSONObject listed = new JSONObject(pages[next++]);
                        listed(listed.getJSONArray("items").length());
                        return listed;
                    }

                    @Override
                    protected JSONObject fetch(JSONObject yielded) {
                        return new JSONObject();
                    }

                    @Override
                    protected JSONObject push(JSONObject yielded) throws JSONException {
                        return new JSONObject().put("results", new JSONArray());
                    }
                };

        long started = System.nanoTime();
        new PimalayaClient().offlineSyncImmutable(remote, collection, null, false);
        long total = (System.nanoTime() - started) / 1_000_000;

        System.out.println("first round of " + PAGES * PAGE + " in " + total + " ms: " + remote.clock);
        try (Cursor cursor =
                pimdir.getReadableDatabase()
                        .rawQuery(
                                "SELECT count(*) FROM items WHERE collection = ?",
                                new String[] {collection})) {
            cursor.moveToFirst();
            assertEquals(PAGES * PAGE, cursor.getInt(0));
        }
        assertEquals(PAGES * PAGE, remote.clock.listed);
    }
}
