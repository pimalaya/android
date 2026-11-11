package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Cards created on the phone, edited and moved between JMAP address books in
 * rounds of ContactCard/set.
 *
 * <p>A JMAP card keeps the UID the phone gave it while the server names it
 * {@code j1}: every change of a round addresses that name.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactEngineJmapBooksTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BASE = AccountClient.jmapBase("jmap.example.org");
    private static final String B1 = "jmap://jmap.example.org/books/b1";
    private static final String B2 = "jmap://jmap.example.org/books/b2";

    private static final String JANE = "uuid-1";
    private static final String JOE = "uuid-2";

    private PimdirDb pimdir;
    private CardStore store;
    private PimdirContacts contacts;
    private Server server;

    /** A JMAP account: cards in address books, answering as ContactCard/set. */
    private static final class Server extends ContactClient {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> bodies = new LinkedHashMap<>();
        final Map<String, Set<String>> books = new LinkedHashMap<>();
        final Map<String, Integer> revisions = new HashMap<>();
        int created;

        Card card(String id) {
            return new Card(
                    id,
                    id,
                    "s-" + id + "-" + revisions.get(id),
                    bodies.get(id),
                    new ArrayList<>(books.get(id)));
        }

        static String bookOf(String url) {
            return url.substring(url.lastIndexOf('/') + 1);
        }

        @Override
        public CardDelta syncCards(
                Transport transport, Account account, String addressbookUrl, String syncToken) {
            List<Card> cards = new ArrayList<>();
            for (String id : bodies.keySet()) {
                cards.add(card(id));
            }
            return new CardDelta(cards, List.of(), "t", true);
        }

        @Override
        public Card readCard(
                Transport transport, Account account, String addressbookUrl, String uri) {
            return card(uri);
        }

        @Override
        public Card updateCard(
                Transport transport, Account account, BookCard target, String baseVcard) {
            throw new AssertionError("a card written alone: " + target.card().uri());
        }

        @Override
        public void updateCardBooks(Transport transport, Account account, BooksUpdate update) {
            throw new AssertionError("a membership patched alone: " + update.cardId());
        }

        /** One outcome per change; an id the server never issued: notFound. */
        @Override
        public List<PushOutcome> pushCards(
                Transport transport, Account account, String addressbookUrl,
                List<PushChange> changes) {
            List<PushOutcome> outcomes = new ArrayList<>();
            for (PushChange change : changes) {
                String id = change.id();
                if (change.op() == PushOp.CREATE) {
                    id = "j" + ++created;
                    bodies.put(id, change.vcard());
                    books.put(id, new LinkedHashSet<>(List.of(bookOf(addressbookUrl))));
                    revisions.put(id, 0);
                    calls.add("CREATE " + id);
                    outcomes.add(new PushOutcome(change.ref(), true, id, null, null));
                    continue;
                }
                calls.add(change.op() + " " + id + " +" + change.add() + " -" + change.remove());
                if (!bodies.containsKey(id)) {
                    outcomes.add(new PushOutcome(change.ref(), false, null, null, "notFound"));
                    continue;
                }
                switch (change.op()) {
                    case UPDATE:
                        bodies.put(id, change.vcard());
                        break;
                    case BOOKS:
                        books.get(id).addAll(change.add());
                        books.get(id).removeAll(change.remove());
                        break;
                    default:
                        bodies.remove(id);
                        books.remove(id);
                        revisions.remove(id);
                        outcomes.add(new PushOutcome(change.ref(), true, null, null, null));
                        continue;
                }
                revisions.put(id, revisions.get(id) + 1);
                outcomes.add(new PushOutcome(change.ref(), true, null, null, null));
            }
            return outcomes;
        }
    }

    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + uid + "\r\nFN:" + name + "\r\nEND:VCARD\r\n";
    }

    /** Two books, and Jane and Joe created in b1 on the phone then synced. */
    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);
        contacts = new PimdirContacts(pimdir);

        List<Addressbook> list =
                List.of(
                        new Addressbook("b1", "Personal", B1, null, null),
                        new Addressbook("b2", "Friends", B2, null, null));
        store.replaceAddressbooks(EMAIL, list);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, list));

        server = new Server();
        pass(B1, B2);
        contacts.save(B1, new Card(JANE, null, null, vcard(JANE, "Jane")));
        contacts.save(B1, new Card(JOE, null, null, vcard(JOE, "Joe")));
        pass(B1, B2);

        assertEquals(List.of("CREATE j1", "CREATE j2"), server.calls);
        server.calls.clear();
    }

    private void pass(String... books) throws Exception {
        ContactEngine engine =
                new ContactEngine(
                        store,
                        pimdir,
                        new Clients(
                                new AccountClient(), server, new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        null,
                        new Account(BASE, EMAIL, "token"));
        for (String book : books) {
            engine.syncBook(book, true);
        }
    }

    private void move(String from, String target, String uid) {
        contacts.stageMembership(from, target, uid, true);
        contacts.stageMembership(from, from, uid, false);
    }

    private void assertSettled() throws Exception {
        server.calls.clear();
        pass(B1, B2);
        assertEquals(List.of(), server.calls);
    }

    @Test
    public void twoEditsInOneRoundAreNamedByTheServer() throws Exception {
        contacts.save(B1, new Card(JANE, null, null, vcard(JANE, "Jane Edit")));
        contacts.save(B1, new Card(JOE, null, null, vcard(JOE, "Joe Edit")));
        pass(B1, B2);

        assertEquals(List.of("UPDATE j1 +[] -[]", "UPDATE j2 +[] -[]"), server.calls);
        assertSettled();
        assertEquals(vcard(JANE, "Jane Edit"), server.bodies.get("j1"));
        assertEquals(vcard(JOE, "Joe Edit"), server.bodies.get("j2"));
    }

    @Test
    public void aRoundOfEditsTakesTheCardsOutOfTheBooksTheyLeave() throws Exception {
        contacts.stageMembership(B1, B2, JANE, true);
        contacts.stageMembership(B1, B2, JOE, true);
        pass(B1, B2);
        assertSettled();

        // NOTE: the updates move both cards' revisions, so b2's tombstones,
        // still listed, would read as overtaken and be revived.
        contacts.save(B1, new Card(JANE, null, null, vcard(JANE, "Jane Edit")));
        contacts.save(B1, new Card(JOE, null, null, vcard(JOE, "Joe Edit")));
        contacts.stageMembership(B2, B2, JANE, false);
        contacts.stageMembership(B2, B2, JOE, false);
        server.calls.clear();
        pass(B1, B2);

        assertEquals(
                List.of(
                        "UPDATE j1 +[] -[]",
                        "UPDATE j2 +[] -[]",
                        "BOOKS j1 +[] -[b2]",
                        "BOOKS j2 +[] -[b2]"),
                server.calls);
        assertSettled();
        assertEquals(List.of("b1"), new ArrayList<>(server.books.get("j1")));
        assertEquals(List.of("b1"), new ArrayList<>(server.books.get("j2")));
        assertEquals(List.of(B1), contacts.collectionsOf(JANE));
        assertEquals(List.of(B1), contacts.collectionsOf(JOE));
    }

    @Test
    public void twoMovesInOneRoundPatchEachCard() throws Exception {
        move(B1, B2, JANE);
        move(B1, B2, JOE);
        pass(B1, B2);

        assertEquals(List.of("BOOKS j1 +[b2] -[b1]", "BOOKS j2 +[b2] -[b1]"), server.calls);
        assertSettled();
        assertEquals(List.of("b2"), new ArrayList<>(server.books.get("j1")));
        assertEquals(List.of("b2"), new ArrayList<>(server.books.get("j2")));
        assertEquals(List.of(B2), contacts.collectionsOf(JANE));
    }

    @Test
    public void aRoundOfMovesHoldsTheOneCarryingAnEdit() throws Exception {
        contacts.save(B1, new Card(JANE, null, null, vcard(JANE, "Jane Edit")));
        move(B1, B2, JANE);
        move(B1, B2, JOE);
        pass(B2, B1);
        pass(B2, B1);

        assertEquals(List.of("BOOKS j2 +[b2] -[b1]"), server.calls);
        assertEquals(List.of("b1"), new ArrayList<>(server.books.get("j1")));
        assertEquals(vcard(JANE, "Jane"), server.bodies.get("j1"));
    }
}
