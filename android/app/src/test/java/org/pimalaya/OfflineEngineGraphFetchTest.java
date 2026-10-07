package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardDelta;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.Transport;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A Graph book's bodies: what the complete round's listing holds is taken
 * from it, and everything else is read in one batched call, never one
 * request a contact.
 */
@RunWith(RobolectricTestRunner.class)
public class OfflineEngineGraphFetchTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BOOK = "https://dav.example.com/books/b1/";

    private final Account account = new Account("msgraph://example", EMAIL, "token");

    private CardStore store;
    private PimdirDb pimdir;

    /** A Graph folder, counting how its bodies were asked for. */
    private static final class Graph extends PimalayaClient {
        /** What each batched read asked for, in order. */
        final List<List<String>> batched = new ArrayList<>();

        /** Contacts the listing of a complete round carries. */
        final List<String> listed = new ArrayList<>();

        /** Contacts the delta names. */
        final List<String> changed = new ArrayList<>();

        /** Contacts gone from the server since the delta. */
        final List<String> gone = new ArrayList<>();

        static Card card(String id) {
            String vcard =
                    "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-" + id + "\r\nFN:Jane " + id
                            + "\r\nEND:VCARD\r\n";
            return new Card(id, id, "ck-" + id, vcard);
        }

        @Override
        public CardDelta syncCards(
                Transport transport, Account account, String addressbookUrl, String syncToken) {
            List<Card> rows = new ArrayList<>();
            for (String id : changed) {
                rows.add(new Card(id, id, "ck-" + id, ""));
            }
            return new CardDelta(rows, List.of(), "delta-link", true);
        }

        @Override
        public List<Card> listCards(Transport transport, Account account, String addressbookUrl) {
            List<Card> cards = new ArrayList<>();
            for (String id : listed) {
                cards.add(card(id));
            }
            return cards;
        }

        @Override
        public Card readCard(
                Transport transport, Account account, String addressbookUrl, String uri) {
            throw new AssertionError("a Graph contact read alone: " + uri);
        }

        @Override
        public List<Card> readGraphCards(Transport transport, Account account, List<String> ids) {
            batched.add(new ArrayList<>(ids));
            List<Card> cards = new ArrayList<>();
            for (String id : ids) {
                if (!gone.contains(id)) {
                    cards.add(card(id));
                }
            }
            return cards;
        }
    }

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);

        List<Addressbook> books = List.of(new Addressbook("b1", "Book One", BOOK, null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));
    }

    private OfflineEngine engine(Graph graph) {
        return new OfflineEngine(store, pimdir, graph, null, account, null);
    }

    private static List<String> handles(JSONArray items) throws Exception {
        List<String> handles = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            handles.add(items.getJSONObject(index).getString("handle"));
        }
        return handles;
    }

    @Test
    public void anIncrementalFetchReadsEveryChangedContactInOneBatchedCall() throws Exception {
        Graph graph = new Graph();
        List<String> asked = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            asked.add("AAMk" + index + "=");
        }

        JSONObject fetch = new JSONObject();
        fetch.put("op", "fetch");
        fetch.put("collection", BOOK);
        fetch.put("handles", new JSONArray(asked));
        fetch.put("tier", "full");
        JSONObject reply = new JSONObject(engine(graph).serve(fetch.toString()));

        assertEquals(List.of(asked), graph.batched);
        JSONArray items = reply.getJSONArray("items");
        assertEquals(asked, handles(items));
        assertEquals("uid-AAMk0=", items.getJSONObject(0).getString("linkId"));
        assertTrue(items.getJSONObject(0).getString("body").contains("FN:Jane AAMk0="));
    }

    @Test
    public void aContactGoneSinceTheDeltaIsLeftOutNotAFailure() throws Exception {
        Graph graph = new Graph();
        graph.gone.add("b");

        JSONObject fetch = new JSONObject();
        fetch.put("op", "fetch");
        fetch.put("collection", BOOK);
        fetch.put("handles", new JSONArray(List.of("a", "b", "c")));
        fetch.put("tier", "full");
        JSONObject reply = new JSONObject(engine(graph).serve(fetch.toString()));

        assertTrue(!reply.has("error"));
        assertEquals(List.of("a", "c"), handles(reply.getJSONArray("items")));
    }

    @Test
    public void aCompleteRoundReadsOnlyWhatItsListingLacks() throws Exception {
        Graph graph = new Graph();
        graph.changed.addAll(List.of("a", "b", "c", "d"));
        // NOTE: "d" was created between the delta and the listing.
        graph.listed.addAll(List.of("a", "b", "c"));

        JSONObject enumerate = new JSONObject();
        enumerate.put("op", "enumerate");
        enumerate.put("collection", BOOK);
        enumerate.put("cursor", JSONObject.NULL);
        JSONObject reply = new JSONObject(engine(graph).serve(enumerate.toString()));

        assertEquals(List.of(List.of("d")), graph.batched);
        JSONArray items = reply.getJSONArray("items");
        assertEquals(List.of("a", "b", "c", "d"), handles(items));
        for (int index = 0; index < items.length(); index++) {
            assertTrue(items.getJSONObject(index).has("body"));
        }
    }
}
