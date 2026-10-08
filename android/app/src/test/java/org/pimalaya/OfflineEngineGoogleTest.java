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
 * A Google account's books: People is read once per pass and projected
 * onto each contact group, and the bodies the round did not carry are
 * read in one batched call, never one request a contact.
 */
@RunWith(RobolectricTestRunner.class)
public class OfflineEngineGoogleTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BASE = "google://" + EMAIL;
    private static final String CONTACTS = BASE + "/myContacts";
    private static final String FRIENDS = BASE + "/friends";
    private static final String FAMILY = BASE + "/family";

    private final Account account = new Account(BASE, EMAIL, "token");

    private CardStore store;
    private PimdirDb pimdir;

    /** A People account, counting how it was asked. */
    private static final class People extends PimalayaClient {
        /** The cursor of each People round, in order. */
        final List<String> rounds = new ArrayList<>();

        /** What each batched read asked for, in order. */
        final List<List<String>> batched = new ArrayList<>();

        /** Contacts gone from the server since the round. */
        final List<String> gone = new ArrayList<>();

        static Card card(String id, String... books) {
            String vcard =
                    "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-" + id + "\r\nFN:Jane " + id
                            + "\r\nEND:VCARD\r\n";
            return new Card(id, id, "e-" + id, vcard, List.of(books));
        }

        @Override
        public CardDelta syncCards(
                Transport transport, Account account, String addressbookUrl, String syncToken) {
            rounds.add(syncToken);
            List<Card> changed =
                    List.of(
                            card("a", "myContacts"),
                            card("b", "myContacts", "friends"),
                            card("c", "myContacts", "family"));
            return new CardDelta(changed, List.of(), "v2:next", syncToken == null);
        }

        @Override
        public Card readCard(
                Transport transport, Account account, String addressbookUrl, String uri) {
            throw new AssertionError("a People contact read alone: " + uri);
        }

        @Override
        public List<Card> readGoogleCards(
                Transport transport, Account account, List<String> ids) {
            batched.add(new ArrayList<>(ids));
            List<Card> cards = new ArrayList<>();
            for (String id : ids) {
                if (!gone.contains(id)) {
                    cards.add(card(id, "myContacts"));
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

        List<Addressbook> books =
                List.of(
                        new Addressbook("myContacts", "Contacts", CONTACTS, null, null),
                        new Addressbook("friends", "Friends", FRIENDS, null, null),
                        new Addressbook("family", "Family", FAMILY, null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));
    }

    private OfflineEngine engine(People people) {
        return new OfflineEngine(store, pimdir, people, null, account, null);
    }

    private static JSONObject enumerate(OfflineEngine engine, String book, String cursor)
            throws Exception {
        JSONObject yielded = new JSONObject();
        yielded.put("op", "enumerate");
        yielded.put("collection", book);
        yielded.put("cursor", cursor == null ? JSONObject.NULL : cursor);
        return new JSONObject(engine.serve(yielded.toString()));
    }

    private static List<String> handles(JSONArray items) throws Exception {
        List<String> handles = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            handles.add(items.getJSONObject(index).getString("handle"));
        }
        return handles;
    }

    @Test
    public void threeGroupsAtOneCheckpointReadPeopleOnce() throws Exception {
        People people = new People();
        OfflineEngine engine = engine(people);

        JSONObject contacts = enumerate(engine, CONTACTS, "v2:last");
        JSONObject friends = enumerate(engine, FRIENDS, "v2:last");
        JSONObject family = enumerate(engine, FAMILY, "v2:last");

        // NOTE: three rounds before, one a group.
        assertEquals(List.of("v2:last"), people.rounds);
        assertEquals(List.of("a", "b", "c"), handles(contacts.getJSONArray("items")));
        assertEquals(List.of("b"), handles(friends.getJSONArray("items")));
        assertEquals(List.of("c"), handles(family.getJSONArray("items")));
        assertEquals("v2:next", family.getString("checkpoint"));
    }

    @Test
    public void aGroupAtAnotherCheckpointReadsItsOwnRound() throws Exception {
        People people = new People();
        OfflineEngine engine = engine(people);

        enumerate(engine, CONTACTS, "v2:last");
        JSONObject friends = enumerate(engine, FRIENDS, null);
        enumerate(engine, FAMILY, "v2:last");

        assertEquals(java.util.Arrays.asList("v2:last", null), people.rounds);
        assertTrue(friends.getBoolean("complete"));
    }

    @Test
    public void aFetchReadsWhatTheRoundLacksInOneBatchedCall() throws Exception {
        People people = new People();
        people.gone.add("e");
        OfflineEngine engine = engine(people);
        enumerate(engine, CONTACTS, "v2:last");

        JSONObject fetch = new JSONObject();
        fetch.put("op", "fetch");
        fetch.put("collection", CONTACTS);
        fetch.put("handles", new JSONArray(List.of("a", "d", "b", "e")));
        fetch.put("tier", "full");
        JSONObject reply = new JSONObject(engine.serve(fetch.toString()));

        assertEquals(List.of(List.of("d", "e")), people.batched);
        JSONArray items = reply.getJSONArray("items");
        assertEquals(List.of("a", "d", "b"), handles(items));
        assertEquals("uid-d", items.getJSONObject(1).getString("linkId"));
    }
}
