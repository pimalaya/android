package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.R;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Card;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.StubHost;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * The contact editor's writes after a sync replaced the card it read: never
 * written over, merged onto what the sync left, the form asking on a
 * property both set apart.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactWriterStaleTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BASE = "google://" + EMAIL;
    private static final String CONTACTS = BASE + "/myContacts";

    private static final String JANE = "uuid-1";
    private static final String TWIN = "uuid-2";

    private StubHost host;
    private PimdirDb pimdir;
    private CardStore store;
    private PimdirContacts contacts;
    private FakePeople people;
    private List<AccountEntry> accounts;
    private ContactPool pool;
    private ContactEditor editor;
    private DuplicateReview review;

    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + uid + "\r\nFN:" + name
                + "\r\nEMAIL:jane@example.org\r\nEND:VCARD\r\n";
    }

    /** Jane in Contacts, created on the phone then synced as c1. */
    @Before
    public void setUp() throws Exception {
        host = Robolectric.buildActivity(StubHost.class).create().get();
        host.setContentView(R.layout.panel_contact);
        Context context = host;
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);
        contacts = new PimdirContacts(pimdir);

        List<Addressbook> books =
                List.of(new Addressbook("myContacts", "Contacts", CONTACTS, null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));

        people = new FakePeople();
        accounts =
                new ArrayList<>(
                        List.of(
                                AccountEntry.empty(EMAIL)
                                        .with(
                                                PimDomain.CONTACTS,
                                                BASE,
                                                null,
                                                AccountCredential.password(EMAIL, "token"))));
        pool = new ContactPool(store, contacts, accounts);
        Clients clients =
                new Clients(
                        new AccountClient(), people, new CalendarClient(), new MailClient(),
                        new OfflineClient());
        ContactParts parts = new ContactParts(pimdir, store, contacts, pool, clients, accounts);
        editor = new ContactEditor(host, parts, new ContactForm(host), () -> {});
        review = new DuplicateReview(host, parts, editor, () -> {});

        pass();
        contacts.save(CONTACTS, new Card(JANE, null, null, vcard(JANE, "Jane")));
        pass();
        people.calls.clear();
    }

    private void pass() throws Exception {
        new ContactEngine(
                        store,
                        pimdir,
                        new Clients(
                                new AccountClient(), people, new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        null,
                        new Account(BASE, EMAIL, "token"))
                .syncBook(CONTACTS, true);
    }

    /** The listed replicas of the card {@code uid}, their bodies read. */
    private List<Entry> entries(String... uids) {
        List<Entry> found = new ArrayList<>();
        for (String uid : uids) {
            for (Entry entry : pool.loadEntries()) {
                if (entry.id.equals(uid)) {
                    entry.card();
                    found.add(entry);
                }
            }
        }
        return found;
    }

    /** Opens Jane in the editor, as a tap on her row does. */
    private void open() {
        assertTrue(editor.openMerged(entries(JANE)));
    }

    /** The body the store holds for a card, or null. */
    private String stored(String uid) throws Exception {
        PimdirStorage offline = new PimdirStorage(pimdir);
        JSONObject row = offline.loadRow(CONTACTS, offline.handleFor(CONTACTS, uid));
        return row == null || row.getBoolean("deleted") ? null : row.getString("vcard");
    }

    /** Saves the form holding {@code body}. */
    private void save(String body) {
        editor.form().load(ContactClient.projectCard(body), null, null);
        editor.save();
    }

    @Test
    public void aSaveAfterAPassBroughtAnotherFieldKeepsIt() throws Exception {
        open();
        people.edit("c1", vcard(JANE, "Jane").replace("END:", "TEL:+33 1\r\nEND:"));
        pass();
        save(vcard(JANE, "Jane Edit"));

        String held = stored(JANE);
        assertTrue(held, held.contains("FN:Jane Edit\r\n"));
        assertTrue(held, held.contains("TEL:+33 1\r\n"));
    }

    @Test
    public void aSaveAfterAPassRenamedTheContactAsksFirst() throws Exception {
        open();
        people.edit("c1", vcard(JANE, "Jane Remote"));
        pass();
        save(vcard(JANE, "Jane Edit"));

        // NOTE: both renamed Jane: nothing written, the form asks.
        assertTrue(stored(JANE), stored(JANE).contains("FN:Jane Remote\r\n"));
        save(vcard(JANE, "Jane Edit"));

        assertTrue(stored(JANE), stored(JANE).contains("FN:Jane Edit\r\n"));
        assertEquals(0, new PimdirStorage(pimdir).loadConflicts(CONTACTS).size());
    }

    @Test
    public void aMergeAfterAPassChangedACardItRemovesIsRefused() throws Exception {
        contacts.save(CONTACTS, new Card(TWIN, null, null, vcard(TWIN, "Jane D.")));
        pass();
        List<Entry> twins = entries(JANE, TWIN);
        assertTrue(editor.openMerged(twins));
        editor.edit().mergeSurvivor = twins.get(0);
        people.edit("c2", vcard(TWIN, "Jane D.").replace("END:", "TEL:+33 2\r\nEND:"));
        pass();
        save(vcard(JANE, "Jane"));

        // NOTE: the twin's new phone would go with it: nothing is staged.
        assertNotNull(stored(TWIN));
        assertTrue(stored(TWIN), stored(TWIN).contains("TEL:+33 2\r\n"));
        assertEquals(vcard(JANE, "Jane"), stored(JANE));
    }

    @Test
    public void aLinkAfterAPassChangedACardKeepsTheChange() throws Exception {
        contacts.save(CONTACTS, new Card(TWIN, null, null, vcard(TWIN, "Jane D.")));
        pass();
        List<Entry> twins = entries(JANE, TWIN);
        people.edit("c2", vcard(TWIN, "Jane D.").replace("END:", "TEL:+33 2\r\nEND:"));
        pass();
        review.link(twins);

        String twin = stored(TWIN);
        assertFalse(twin == null);
        // NOTE: linked, Joe now carries Jane's UID under his own link id.
        assertTrue(twin, twin.contains("UID:" + JANE + "\r\n"));
        assertTrue(twin, twin.contains("TEL:+33 2\r\n"));
    }
}
