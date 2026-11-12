package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.robolectric.shadows.ShadowAlertDialog;

/** The contact editor's saves and questions on a Google account. */
@RunWith(RobolectricTestRunner.class)
public class ContactWriterGoogleTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BASE = "google://" + EMAIL;
    private static final String CONTACTS = BASE + "/myContacts";
    private static final String LABEL = BASE + "/3e1f";

    /** The UID the phone gave Jane, which People keeps. */
    private static final String JANE = "uuid-1";

    private StubHost host;
    private PimdirDb pimdir;
    private CardStore store;
    private PimdirContacts contacts;
    private FakePeople people;
    private List<AccountEntry> accounts;
    private ContactPool pool;
    private ContactEditor editor;

    private static String vcard(String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + JANE + "\r\nFN:" + name + "\r\nEND:VCARD\r\n";
    }

    /** Contacts and a label, and Jane created in Contacts then synced. */
    @Before
    public void setUp() throws Exception {
        host = Robolectric.buildActivity(StubHost.class).create().get();
        host.setContentView(R.layout.panel_contact);
        Context context = host;
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);
        contacts = new PimdirContacts(pimdir);

        List<Addressbook> books =
                List.of(
                        new Addressbook("myContacts", "Contacts", CONTACTS, null, null),
                        new Addressbook("3e1f", "Seed amis", LABEL, null, null));
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

        pass();
        contacts.save(CONTACTS, new Card(JANE, null, null, vcard("Jane")));
        pass();
        people.calls.clear();
    }

    /** One sync pass over both books. */
    private void pass() throws Exception {
        ContactEngine engine =
                new ContactEngine(
                        store,
                        pimdir,
                        new Clients(
                                new AccountClient(), people, new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        null,
                        new Account(BASE, EMAIL, "token"));
        engine.syncBook(LABEL, true);
        engine.syncBook(CONTACTS, true);
    }

    /** Opens Jane in the editor, as a tap on her row does. */
    private void open() {
        for (Group group : pool.group(pool.loadEntries(), accounts)) {
            if (group.primary().id.equals(JANE)) {
                editor.openGroup(group);
                return;
            }
        }
        throw new AssertionError("Jane is not listed");
    }

    /** The body the store holds for Jane in a book, or null. */
    private String stored(String book) throws Exception {
        PimdirStorage offline = new PimdirStorage(pimdir);
        JSONObject row = offline.loadRow(book, offline.handleFor(book, JANE));
        return row == null ? null : row.getString("vcard");
    }

    /** The dialog the last step showed, the main looper run first. */
    private AlertDialog dialog() {
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    @Test
    public void anEditIsStagedOnEveryBookTheContactIsIn() throws Exception {
        contacts.stageMembership(CONTACTS, LABEL, JANE, true);
        open();
        editor.form().load(ContactClient.projectCard(vcard("Jane Edit")), null, null);
        editor.save();

        assertTrue(stored(CONTACTS), stored(CONTACTS).contains("FN:Jane Edit\r\n"));
        assertTrue(stored(LABEL), stored(LABEL).contains("FN:Jane Edit\r\n"));
        pass();
        pass();
        assertTrue(people.calls.toString(), people.calls.contains("updateCard c1"));
        assertTrue(
                people.calls.toString(), people.calls.contains("updateCardBooks c1 +[3e1f] -[]"));
        assertEquals(List.of(), UnsentContacts.rows(pimdir, host));
        assertTrue(people.bodies.get("c1"), people.bodies.get("c1").contains("FN:Jane Edit\r\n"));
    }

    @Test
    public void anEditOfACardInTwoSyncedBooksSendsOneUpdate() throws Exception {
        contacts.stageMembership(CONTACTS, LABEL, JANE, true);
        pass();
        people.calls.clear();
        open();
        editor.form().load(ContactClient.projectCard(vcard("Jane Edit")), null, null);
        editor.save();
        pass();

        assertEquals(List.of("updateCard c1"), people.calls);
        people.calls.clear();
        pass();
        assertEquals(List.of(), people.calls);
        assertTrue(stored(CONTACTS), stored(CONTACTS).contains("FN:Jane Edit\r\n"));
        assertTrue(stored(LABEL), stored(LABEL).contains("FN:Jane Edit\r\n"));
    }

    /** Jane ticked into the label with a rename staged there alone. */
    private void unsentOnTheLabel() throws Exception {
        contacts.stageMembership(CONTACTS, LABEL, JANE, true);
        contacts.save(LABEL, new Card(JANE, null, null, vcard("Jane Edit")));
        pass();
        assertTrue(contacts.unsent(LABEL, JANE));
    }

    @Test
    public void untickingTheBookHoldingAnUnsentChangeAsksFirst() throws Exception {
        unsentOnTheLabel();
        open();
        editor.edit().pendingBookState = Map.of(LABEL, false);
        editor.save();

        AlertDialog asked = dialog();
        assertEquals(
                host.getString(R.string.contact_unsent_keep_title),
                shadowOf(asked).getTitle().toString());
        assertEquals(
                host.getString(R.string.contact_unsent_keep_message, "Seed amis", "Jane Edit"),
                shadowOf(asked).getMessage().toString());
        assertEquals(
                host.getString(R.string.contact_unsent_keep),
                asked.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(
                host.getString(R.string.contact_unsent_drop),
                asked.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertEquals(List.of(CONTACTS, LABEL), contacts.collectionsOf(JANE));
    }

    @Test
    public void keepingAChangeNeverSentOpensTheFormOnTheStayingBook() throws Exception {
        unsentOnTheLabel();
        open();
        editor.edit().pendingBookState = Map.of(LABEL, false);
        editor.save();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(CONTACTS, editor.edit().book.url());
        assertEquals(1, editor.edit().replicas.size());
        assertEquals(List.of(CONTACTS, LABEL), contacts.collectionsOf(JANE));
        assertEquals(vcard("Jane"), stored(CONTACTS));
        assertEquals(vcard("Jane Edit"), stored(LABEL));
    }

    @Test
    public void droppingTheChangeTakesTheContactOutOfTheBook() throws Exception {
        unsentOnTheLabel();
        open();
        editor.edit().pendingBookState = Map.of(LABEL, false);
        editor.save();
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
        pass();
        assertEquals(List.of("myContacts"), new ArrayList<>(people.groups.get("c1")));
        assertEquals(vcard("Jane"), people.bodies.get("c1"));
    }

    @Test
    public void untickingABookHoldingNoUnsentChangeAsksNothing() throws Exception {
        contacts.stageMembership(CONTACTS, LABEL, JANE, true);
        pass();
        open();
        editor.edit().pendingBookState = Map.of(LABEL, false);
        editor.save();
        shadowOf(Looper.getMainLooper()).idle();

        AlertDialog shown = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(shown == null || !shown.isShowing());
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
    }

    @Test
    public void notSentYetNamesEachHeldChangeByContactAndBook() throws Exception {
        unsentOnTheLabel();
        List<UnsentContacts.Row> rows = UnsentContacts.rows(pimdir, host);
        UnsentContacts.show(host, pimdir, rows);

        AlertDialog shown = dialog();
        assertEquals(
                host.getString(R.string.sync_unsent_title), shadowOf(shown).getTitle().toString());
        assertEquals(
                host.getString(R.string.sync_unsent_body)
                        + "\n\n"
                        + host.getString(R.string.sync_unsent_add, "Jane Edit", "Seed amis", EMAIL),
                shadowOf(shown).getMessage().toString());
        assertFalse(shown.getButton(AlertDialog.BUTTON_NEGATIVE).isShown());
        shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(shown.isShowing());
    }

    @Test
    public void notSentYetNamesAPhoneDeletionWaitingForItsChange() throws Exception {
        List<UnsentContacts.Row> rows =
                List.of(new UnsentContacts.Row(JANE, "Jane Edit", LABEL, null, true));
        UnsentContacts.show(host, pimdir, rows);

        assertEquals(
                host.getString(R.string.sync_unsent_body)
                        + "\n\n"
                        + host.getString(
                                R.string.sync_unsent_phone, "Jane Edit", "Seed amis", EMAIL),
                shadowOf(dialog()).getMessage().toString());
    }

    @Test
    public void notSentYetNamesAMoveWaitingForItsTarget() throws Exception {
        contacts.stageMembership(CONTACTS, LABEL, JANE, true);
        contacts.stageMembership(CONTACTS, CONTACTS, JANE, false);
        ContactEngine engine =
                new ContactEngine(
                        store,
                        pimdir,
                        new Clients(
                                new AccountClient(), people, new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        null,
                        new Account(BASE, EMAIL, "token"));
        engine.syncBook(CONTACTS, true);

        UnsentContacts.show(host, pimdir, UnsentContacts.rows(pimdir, host));
        assertEquals(
                host.getString(R.string.sync_unsent_body)
                        + "\n\n"
                        + host.getString(
                                R.string.sync_unsent_move, "Jane", "Contacts", EMAIL, "Seed amis"),
                shadowOf(dialog()).getMessage().toString());
    }
}
