package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.BookCard;
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
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A JMAP card the server edits between the listing and the push is never
 * written over: the update names the state its listing read, and a card
 * changed since is left for the next listing's merge.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactEngineJmapGuardTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BASE = AccountClient.jmapBase("jmap.example.org");
    private static final String B1 = "jmap://jmap.example.org/books/b1";

    private static final String JANE = "uuid-1";
    private static final String JOE = "uuid-2";

    private PimdirDb pimdir;
    private CardStore store;
    private PimdirContacts contacts;
    private Server server;

    /**
     * A JMAP account behind the bridge: each change moves the account state,
     * and an update naming a state is refused when its card changed since, as
     * ContactCard/changes read before the set tells. An update naming none is
     * written, as the set alone writes it.
     */
    private static final class Server extends ContactClient {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> bodies = new LinkedHashMap<>();
        final Map<String, Integer> changedAt = new HashMap<>();
        int state;
        int created;
        /** Run once after a listing, before the push that follows it. */
        Runnable afterListing;

        Card card(String id) {
            return new Card(id, id, "r-" + id + "-" + changedAt.get(id), bodies.get(id), List.of("b1"));
        }

        void edit(String id, String body) {
            bodies.put(id, body);
            changedAt.put(id, ++state);
        }

        @Override
        public CardDelta syncCards(
                Transport transport, Account account, String addressbookUrl, String syncToken) {
            List<Card> cards = new ArrayList<>();
            for (String id : bodies.keySet()) {
                cards.add(card(id));
            }
            CardDelta delta = new CardDelta(cards, List.of(), "s" + state, true);
            Runnable behind = afterListing;
            afterListing = null;
            if (behind != null) {
                behind.run();
            }
            return delta;
        }

        @Override
        public Card readCard(
                Transport transport, Account account, String addressbookUrl, String uri) {
            return card(uri);
        }

        /** The unguarded lone update the bridge sent before the guard. */
        @Override
        public Card updateCard(
                Transport transport, Account account, BookCard target, String baseVcard) {
            String id = target.card().uri();
            calls.add("ALONE " + id);
            edit(id, target.card().vcard());
            return card(id);
        }

        @Override
        public List<PushOutcome> pushCards(
                Transport transport, Account account, String addressbookUrl,
                List<PushChange> changes) {
            List<PushOutcome> outcomes = new ArrayList<>();
            for (PushChange change : changes) {
                String id = change.id();
                if (change.op() == PushOp.CREATE) {
                    id = "j" + ++created;
                    edit(id, change.vcard());
                    calls.add("CREATE " + id);
                    outcomes.add(new PushOutcome(change.ref(), true, id, null, null));
                    continue;
                }
                if (change.op() == PushOp.UPDATE
                        && change.since() != null
                        && changedAt.get(id) > Integer.parseInt(change.since().substring(1))) {
                    calls.add("UPDATE " + id + " changed since " + change.since());
                    outcomes.add(new PushOutcome(change.ref(), false, null, null, "changed"));
                    continue;
                }
                calls.add(change.op() + " " + id);
                edit(id, change.vcard());
                outcomes.add(new PushOutcome(change.ref(), true, null, null, null));
            }
            return outcomes;
        }
    }

    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + uid + "\r\nFN:" + name + "\r\nEND:VCARD\r\n";
    }

    /** One book, Jane and Joe created on the phone then synced. */
    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);
        contacts = new PimdirContacts(pimdir);

        List<Addressbook> list = List.of(new Addressbook("b1", "Personal", B1, null, null));
        store.replaceAddressbooks(EMAIL, list);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, list));

        server = new Server();
        pass();
        contacts.save(B1, new Card(JANE, null, null, vcard(JANE, "Jane")));
        contacts.save(B1, new Card(JOE, null, null, vcard(JOE, "Joe")));
        pass();

        assertEquals(List.of("CREATE j1", "CREATE j2"), server.calls);
        server.calls.clear();
    }

    private void pass() throws Exception {
        new ContactEngine(
                        store,
                        pimdir,
                        new Clients(
                                new AccountClient(), server, new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        null,
                        new Account(BASE, EMAIL, "token"))
                .syncBook(B1, true);
    }

    private int conflicts() throws Exception {
        return new PimdirStorage(pimdir).loadConflicts(B1).size();
    }

    @Test
    public void anEditLandingAfterTheListingIsNeverWrittenOver() throws Exception {
        contacts.save(B1, new Card(JANE, null, null, vcard(JANE, "Jane Edit")));
        server.afterListing = () -> server.edit("j1", vcard(JANE, "Jane Remote"));
        pass();

        assertEquals(List.of("UPDATE j1 changed since s2"), server.calls);
        assertEquals(vcard(JANE, "Jane Remote"), server.bodies.get("j1"));
        server.calls.clear();
        pass();

        // NOTE: both sides renamed Jane: the form asks, nothing is sent.
        assertEquals(List.of(), server.calls);
        assertEquals(vcard(JANE, "Jane Remote"), server.bodies.get("j1"));
        assertEquals(1, conflicts());
    }

    @Test
    public void anEditOfAnotherFieldLandingAfterTheListingMerges() throws Exception {
        contacts.save(B1, new Card(JANE, null, null, vcard(JANE, "Jane Edit")));
        server.afterListing = () -> server.edit("j1", vcard(JANE, "Jane\r\nTEL:2"));
        pass();
        pass();

        String merged = server.bodies.get("j1");
        assertTrue(merged, merged.contains("FN:Jane Edit") && merged.contains("TEL:2"));
        assertEquals(0, conflicts());
    }

    @Test
    public void aRoundWritesTheCardsTheServerLeftAlone() throws Exception {
        contacts.save(B1, new Card(JANE, null, null, vcard(JANE, "Jane Edit")));
        contacts.save(B1, new Card(JOE, null, null, vcard(JOE, "Joe Edit")));
        server.afterListing = () -> server.edit("j1", vcard(JANE, "Jane Remote"));
        pass();

        assertEquals(List.of("UPDATE j1 changed since s2", "UPDATE j2"), server.calls);
        assertEquals(vcard(JANE, "Jane Remote"), server.bodies.get("j1"));
        assertEquals(vcard(JOE, "Joe Edit"), server.bodies.get("j2"));
    }
}
