package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.BookCard;
import org.pimalaya.client.BooksUpdate;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardDelta;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.NewCard;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PushChange;
import org.pimalaya.client.PushOp;
import org.pimalaya.client.PushOutcome;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * What a push hands the contact client on each backend's path, and what the
 * engine answers the bridge from the client's replies: the batch of a JMAP
 * or Graph round, the Google round's batch verbs and membership patches, and
 * the one-change verbs. The real engine and store over a fake server; the
 * expected calls and results were captured on the code that built the push
 * batch as JSON.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactEnginePushTest {
    private static final String EMAIL = "push@example.com";

    private CardStore store;
    private PimdirDb pimdir;
    private Server server;
    private ContactEngine engine;

    /** A server holding five cards, recording every call it is asked. */
    private static final class Server extends ContactClient {
        final List<String> calls = new ArrayList<>();
        final List<List<PushChange>> batches = new ArrayList<>();
        final Map<String, String> held = new LinkedHashMap<>();
        final String bookId;
        int created;

        Server(String bookId) {
            this.bookId = bookId;
            for (String id : List.of("a", "b", "c", "d", "e")) {
                held.put(id, vcard(id, id.toUpperCase()));
            }
        }

        Card card(String id) {
            return new Card(id, id, "e-" + id, held.get(id), List.of(bookId));
        }

        @Override
        public CardDelta syncCards(
                Transport transport, Account account, String addressbookUrl, String syncToken) {
            List<Card> cards = new ArrayList<>();
            for (String id : held.keySet()) {
                cards.add(card(id));
            }
            return new CardDelta(cards, List.of(), "t1", true);
        }

        @Override
        public List<Card> listCards(Transport transport, Account account, String addressbookUrl) {
            List<Card> cards = new ArrayList<>();
            for (String id : held.keySet()) {
                cards.add(card(id));
            }
            return cards;
        }

        @Override
        public List<Card> multigetCards(
                Transport transport, Account account, String addressbookUrl, List<String> uris) {
            List<Card> cards = new ArrayList<>();
            for (String uri : uris) {
                cards.add(card(uri));
            }
            return cards;
        }

        @Override
        public List<Card> readGraphCards(Transport transport, Account account, List<String> ids) {
            return multigetCards(transport, account, null, ids);
        }

        @Override
        public List<Card> readGoogleCards(
                Transport transport, Account account, List<String> ids) {
            return multigetCards(transport, account, null, ids);
        }

        @Override
        public Card readCard(
                Transport transport, Account account, String addressbookUrl, String uri) {
            return card(uri);
        }

        /**
         * Accepts every change but a destroy, which it refuses, and leaves
         * {@code n2} unanswered.
         */
        @Override
        public List<PushOutcome> pushCards(
                Transport transport, Account account, String addressbookUrl,
                List<PushChange> changes) {
            calls.add("pushCards " + addressbookUrl);
            batches.add(changes);
            List<PushOutcome> outcomes = new ArrayList<>();
            for (PushChange change : changes) {
                String ref = change.ref();
                if (ref.equals("n2")) {
                    continue;
                }
                if (change.op() == PushOp.DESTROY) {
                    outcomes.add(new PushOutcome(ref, false, null, null, "refused"));
                } else {
                    outcomes.add(
                            new PushOutcome(
                                    ref,
                                    true,
                                    change.op() == PushOp.CREATE ? "srv-" + ref : null,
                                    change.op() == PushOp.UPDATE ? "k-" + ref : null,
                                    null));
                }
            }
            return outcomes;
        }

        @Override
        public void updateCardBooks(Transport transport, Account account, BooksUpdate update) {
            calls.add(
                    "updateCardBooks " + update.cardId() + " +" + update.add() + " -"
                            + update.remove());
        }

        @Override
        public Card createCard(
                Transport transport, Account account, String addressbookUrl, NewCard card) {
            calls.add(
                    "createCard " + addressbookUrl + " " + card.id() + " "
                            + JSONObject.quote(card.vcard()));
            return new Card("srv-" + card.id(), "srv-" + card.id(), "e-new", card.vcard());
        }

        @Override
        public Card updateCard(
                Transport transport, Account account, BookCard target, String baseVcard) {
            Card card = target.card();
            calls.add(
                    "updateCard " + target.bookUrl() + " " + card.id() + " " + card.uri() + " "
                            + card.etag() + " " + JSONObject.quote(card.vcard()) + " "
                            + (baseVcard == null ? "null" : JSONObject.quote(baseVcard)));
            return new Card(card.id(), card.uri(), "e-upd", card.vcard());
        }

        @Override
        public void deleteCard(Transport transport, Account account, BookCard target) {
            Card card = target.card();
            calls.add(
                    "deleteCard " + target.bookUrl() + " " + card.id() + " " + card.uri() + " "
                            + card.etag());
        }

        @Override
        public List<Card> createCards(Transport transport, Account account, List<String> vcards) {
            List<Card> cards = new ArrayList<>();
            for (String vcard : vcards) {
                String id = "g" + ++created;
                cards.add(new Card(id, id, "e-" + id, vcard));
            }
            calls.add("createCards " + vcards.size());
            return cards;
        }

        @Override
        public void deleteCards(Transport transport, Account account, List<String> ids) {
            calls.add("deleteCards " + ids);
        }
    }

    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:uid-" + uid + "\r\nFN:" + name
                + "\r\nEND:VCARD\r\n";
    }

    private static String quoted(String uid, String name) {
        return JSONObject.quote(vcard(uid, name));
    }

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);
    }

    /**
     * An account of one book (and another it could copy from), synced once:
     * the server's five cards held. Then two cards created, {@code b} edited
     * and {@code c} deleted on this side.
     */
    private void account(String base, String book, String bookId) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        List<Addressbook> books =
                List.of(
                        new Addressbook(bookId, "Book", book, null, null),
                        new Addressbook("other", "Other", book + "other", null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));

        server = new Server(bookId);
        engine =
                new ContactEngine(
                        store,
                        pimdir,
                        new Clients(
                                new AccountClient(), server, new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        null,
                        new Account(base, EMAIL, "token"));
        engine.syncBook(book, true);
        server.calls.clear();

        PimdirContacts contacts = new PimdirContacts(pimdir);
        contacts.save(book, new Card("n1", null, null, vcard("n1", "New one")));
        contacts.save(book, new Card("n2", null, null, vcard("n2", "New two")));
        contacts.save(book, new Card("uid-b", "b", "e-b", vcard("b", "B edited")));
        contacts.stageDelete(book, "uid-c");
    }

    private static JSONObject change(String op, String handle) throws Exception {
        JSONObject change = new JSONObject();
        change.put("op", op);
        change.put("handle", handle);
        return change;
    }

    private static JSONObject origin(String book) throws Exception {
        JSONObject origin = new JSONObject();
        origin.put("collection", book + "other");
        origin.put("handle", "a");
        return origin;
    }

    /** Serves one push yield, answering its results. */
    private String push(String book, JSONObject... changes) throws Exception {
        JSONObject yielded = new JSONObject();
        yielded.put("op", "push");
        yielded.put("collection", book);
        yielded.put("changes", new JSONArray(List.of(changes)));
        return new JSONObject(engine.serve(yielded.toString())).getJSONArray("results").toString();
    }

    /** One round of every kind of change, in one yield. */
    private String round(String book) throws Exception {
        return push(
                book,
                change("add", "n1"),
                change("add", "a").put("origin", origin(book)),
                change("update", "b").put("ifMatch", "e-b"),
                change("remove", "c").put("ifMatch", "e-c"),
                change("remove", "d"),
                change("flag", "e"),
                change("add", "missing"),
                change("update", "n2"));
    }

    /** The same changes, a yield each. */
    private List<String> singles(String book) throws Exception {
        return List.of(
                push(book, change("add", "n1")),
                push(book, change("add", "a").put("origin", origin(book))),
                push(book, change("update", "b").put("ifMatch", "e-b")),
                push(book, change("remove", "c").put("ifMatch", "e-c")),
                push(book, change("remove", "d")));
    }

    private static void assertSameResults(String expected, String actual) throws Exception {
        assertEquals(new JSONArray(expected).toString(), new JSONArray(actual).toString());
    }

    private static PushChange create(String ref, String vcard) {
        return new PushChange(ref, PushOp.CREATE, null, vcard, null, List.of(), List.of());
    }

    private static PushChange update(String ref, String id, String vcard, String base) {
        return new PushChange(ref, PushOp.UPDATE, id, vcard, base, List.of(), List.of());
    }

    private static PushChange destroy(String ref, String id) {
        return new PushChange(ref, PushOp.DESTROY, id, null, null, List.of(), List.of());
    }

    private static PushChange books(String ref, String id, List<String> add, List<String> remove) {
        return new PushChange(ref, PushOp.BOOKS, id, null, null, add, remove);
    }

    @Test
    public void aJmapRoundIsOneBatchOfMembershipsWhereTheCardIsOnTheAccount() throws Exception {
        String book = "jmap://jmap.example.org/books/b1";
        account(AccountClient.jmapBase("jmap.example.org"), book, "b1");

        assertSameResults(
                "[{\"handle\":\"n1\",\"accepted\":true,\"assigned\":\"srv-n1\"},"
                        + "{\"handle\":\"a\",\"accepted\":true,\"assigned\":\"a\"},"
                        + "{\"handle\":\"b\",\"accepted\":true,\"revision\":\"k-b\"},"
                        + "{\"handle\":\"c\",\"accepted\":false},"
                        + "{\"handle\":\"d\",\"accepted\":true},"
                        + "{\"handle\":\"e\",\"accepted\":true},"
                        + "{\"handle\":\"missing\",\"accepted\":false},"
                        + "{\"handle\":\"n2\",\"accepted\":false}]",
                round(book));
        assertEquals(List.of("pushCards " + book), server.calls);
        assertEquals(
                List.of(
                        List.of(
                                create("n1", vcard("n1", "New one")),
                                books("a", "uid-a", List.of("b1"), List.of()),
                                update("b", "uid-b", vcard("b", "B edited"), vcard("b", "B")),
                                destroy("c", "uid-c"),
                                books("d", "uid-d", List.of(), List.of("b1")),
                                update("n2", "n2", vcard("n2", "New two"), null))),
                server.batches);

        server.calls.clear();
        List<String> singles = singles(book);
        assertSameResults(
                "[{\"handle\":\"n1\",\"accepted\":true,\"assigned\":\"srv-n1\","
                        + "\"revision\":\"e-new\"}]",
                singles.get(0));
        assertSameResults(
                "[{\"handle\":\"a\",\"accepted\":true,\"assigned\":\"a\"}]", singles.get(1));
        assertSameResults(
                "[{\"handle\":\"b\",\"accepted\":true,\"revision\":\"e-upd\"}]", singles.get(2));
        assertSameResults("[{\"handle\":\"c\",\"accepted\":true}]", singles.get(3));
        assertSameResults("[{\"handle\":\"d\",\"accepted\":true}]", singles.get(4));
        assertEquals(
                List.of(
                        "createCard " + book + " n1 " + quoted("n1", "New one"),
                        "updateCardBooks uid-a +[b1] -[]",
                        "updateCard " + book + " uid-b b e-b " + quoted("b", "B edited") + " "
                                + quoted("b", "B"),
                        "deleteCard " + book + " uid-c c e-c",
                        "updateCardBooks uid-d +[] -[b1]"),
                server.calls);
    }

    @Test
    public void aGraphRoundIsOneBatchOfCreatesAndDestroys() throws Exception {
        String book = "https://graph.example.com/books/b1/";
        account("msgraph://example", book, "b1");

        assertSameResults(
                "[{\"handle\":\"n1\",\"accepted\":true,\"assigned\":\"srv-n1\"},"
                        + "{\"handle\":\"a\",\"accepted\":true,\"assigned\":\"srv-a\"},"
                        + "{\"handle\":\"b\",\"accepted\":true,\"revision\":\"k-b\"},"
                        + "{\"handle\":\"c\",\"accepted\":false},"
                        + "{\"handle\":\"d\",\"accepted\":false},"
                        + "{\"handle\":\"e\",\"accepted\":true},"
                        + "{\"handle\":\"missing\",\"accepted\":false},"
                        + "{\"handle\":\"n2\",\"accepted\":false}]",
                round(book));
        assertEquals(List.of("pushCards " + book), server.calls);
        assertEquals(
                List.of(
                        List.of(
                                create("n1", vcard("n1", "New one")),
                                create("a", vcard("a", "A")),
                                update("b", "uid-b", vcard("b", "B edited"), vcard("b", "B")),
                                destroy("c", "uid-c"),
                                destroy("d", "uid-d"),
                                update("n2", "n2", vcard("n2", "New two"), null))),
                server.batches);

        server.calls.clear();
        List<String> singles = singles(book);
        assertSameResults(
                "[{\"handle\":\"n1\",\"accepted\":true,\"assigned\":\"srv-n1.vcf\","
                        + "\"revision\":\"e-new\"}]",
                singles.get(0));
        assertSameResults(
                "[{\"handle\":\"a\",\"accepted\":true,\"assigned\":\"srv-a.vcf\","
                        + "\"revision\":\"e-new\"}]",
                singles.get(1));
        assertSameResults(
                "[{\"handle\":\"b\",\"accepted\":true,\"revision\":\"e-upd\"}]", singles.get(2));
        assertSameResults("[{\"handle\":\"c\",\"accepted\":true}]", singles.get(3));
        assertSameResults("[{\"handle\":\"d\",\"accepted\":true}]", singles.get(4));
        assertEquals(
                List.of(
                        "createCard " + book + " n1.vcf " + quoted("n1", "New one"),
                        "createCard " + book + " a.vcf " + quoted("a", "A"),
                        "updateCard " + book + " uid-b b e-b " + quoted("b", "B edited") + " "
                                + quoted("b", "B"),
                        "deleteCard " + book + " uid-c c e-c",
                        "deleteCard " + book + " uid-d d null"),
                server.calls);
    }

    @Test
    public void aGoogleRoundBatchesCreatesAndDeletesAndPatchesMemberships() throws Exception {
        String book = AccountClient.googleBase(EMAIL) + "/friends";
        account(AccountClient.googleBase(EMAIL), book, "friends");

        assertSameResults(
                "[{\"handle\":\"n1\",\"accepted\":true,\"assigned\":\"g1\","
                        + "\"revision\":\"e-g1\"},"
                        + "{\"handle\":\"a\",\"accepted\":true,\"assigned\":\"a\"},"
                        + "{\"handle\":\"b\",\"accepted\":true,\"revision\":\"e-upd\"},"
                        + "{\"handle\":\"c\",\"accepted\":true},"
                        + "{\"handle\":\"d\",\"accepted\":true},"
                        + "{\"handle\":\"e\",\"accepted\":true},"
                        + "{\"handle\":\"missing\",\"accepted\":false},"
                        + "{\"handle\":\"n2\",\"accepted\":true,\"revision\":\"e-upd\"}]",
                round(book));
        assertEquals(
                List.of(
                        "updateCardBooks uid-a +[friends] -[]",
                        "updateCard " + book + " uid-b b e-b " + quoted("b", "B edited") + " "
                                + quoted("b", "B"),
                        "updateCardBooks uid-d +[] -[friends]",
                        "updateCard " + book + " n2 n2 null " + quoted("n2", "New two")
                                + " null",
                        "createCards 1",
                        "updateCardBooks g1 +[friends] -[]",
                        "deleteCards [uid-c]"),
                server.calls);

        server.calls.clear();
        List<String> singles = singles(book);
        assertSameResults(
                "[{\"handle\":\"n1\",\"accepted\":true,\"assigned\":\"srv-n1\","
                        + "\"revision\":\"e-new\"}]",
                singles.get(0));
        assertSameResults(
                "[{\"handle\":\"a\",\"accepted\":true,\"assigned\":\"a\"}]", singles.get(1));
        assertSameResults(
                "[{\"handle\":\"b\",\"accepted\":true,\"revision\":\"e-upd\"}]", singles.get(2));
        assertSameResults("[{\"handle\":\"c\",\"accepted\":true}]", singles.get(3));
        assertSameResults("[{\"handle\":\"d\",\"accepted\":true}]", singles.get(4));
        assertEquals(
                List.of(
                        "createCard " + book + " n1 " + quoted("n1", "New one"),
                        "updateCardBooks srv-n1 +[friends] -[]",
                        "updateCardBooks uid-a +[friends] -[]",
                        "updateCard " + book + " uid-b b e-b " + quoted("b", "B edited") + " "
                                + quoted("b", "B"),
                        "deleteCard " + book + " uid-c c e-c",
                        "updateCardBooks uid-d +[] -[friends]"),
                server.calls);
    }
}
