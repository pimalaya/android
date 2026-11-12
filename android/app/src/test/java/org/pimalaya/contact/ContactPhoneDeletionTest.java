package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Card;
import org.pimalaya.client.Clients;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.pimdir.PhonePass;
import org.pimalaya.pimdir.PhoneSpoke;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirEngine;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** A contact's copy deleted in the phone's Contacts app, through the mirror. */
@RunWith(RobolectricTestRunner.class)
public class ContactPhoneDeletionTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BASE = "google://" + EMAIL;
    private static final String CONTACTS = BASE + "/myContacts";
    private static final String SEED = BASE + "/seed";

    /** The UID the phone gave Jane, which People keeps. */
    private static final String JANE = "uuid-1";

    private PimdirDb pimdir;
    private CardStore store;
    private PimdirContacts contacts;
    private FakePeople people;
    private RawContacts phone;

    /** A book's raw contacts: bodies by handle, the deleted ones apart. */
    private static final class RawContacts implements PhoneSpoke {
        private final PimdirStorage offline;
        final Map<String, String> bodies = new LinkedHashMap<>();
        final Set<String> deleted = new LinkedHashSet<>();
        final List<String> purged = new ArrayList<>();

        RawContacts(PimdirDb pimdir) {
            this.offline = new PimdirStorage(pimdir);
        }

        @Override
        public boolean available(String spoke) {
            return true;
        }

        @Override
        public boolean changed(String spoke) {
            return true;
        }

        @Override
        public int count(String spoke) {
            return bodies.size() - deleted.size();
        }

        @Override
        public JSONObject enumerate(String spoke) throws JSONException {
            JSONArray items = new JSONArray();
            for (Map.Entry<String, String> raw : bodies.entrySet()) {
                if (!deleted.contains(raw.getKey())) {
                    items.put(
                            new JSONObject()
                                    .put("handle", raw.getKey())
                                    .put("revision", CardStore.byteHash(raw.getValue())));
                }
            }
            return new JSONObject()
                    .put("items", items)
                    .put("vanished", new JSONArray(deleted))
                    .put("complete", false);
        }

        @Override
        public JSONObject fetch(String spoke, JSONArray handles) throws JSONException {
            return new JSONObject().put("items", new JSONArray());
        }

        /** As the mirror: a create lands on a raw contact named as staged. */
        @Override
        public JSONObject push(String spoke, JSONArray changes, IntConsumer pushed)
                throws JSONException {
            JSONArray results = new JSONArray();
            for (int index = 0; index < changes.length(); index++) {
                JSONObject change = changes.getJSONObject(index);
                String handle = change.getString("handle");
                JSONObject row = offline.loadRow(spoke, handle);
                String name = PimdirStorage.nameOf(handle);
                switch (change.getString("op")) {
                    case "add":
                    case "update":
                        bodies.put(name, row.getString("vcard"));
                        results.put(
                                PimdirEngine.result(
                                        handle,
                                        true,
                                        "add".equals(change.getString("op")) ? name : null,
                                        CardStore.byteHash(row.getString("vcard"))));
                        break;
                    default:
                        bodies.remove(handle);
                        results.put(PimdirEngine.result(handle, true, null, null));
                }
            }
            return new JSONObject().put("results", results);
        }

        @Override
        public List<String> deleted(String spoke) {
            return new ArrayList<>(deleted);
        }

        @Override
        public void purge(String spoke, List<String> handles) {
            for (String handle : handles) {
                purged.add(handle);
                bodies.remove(handle);
                deleted.remove(handle);
            }
        }
    }

    /** The contacts phone pass over the fake mirror, deferring as the app's. */
    private static final class Mirror extends PhonePass {
        private final PimdirContacts contacts;

        Mirror(PimdirDb pimdir, RawContacts phone) {
            super(pimdir, Clients.real(), phone, PimdirSummary.CONTACT);
            this.contacts = new PimdirContacts(pimdir);
        }

        @Override
        protected boolean settle(String spoke) {
            return false;
        }

        @Override
        protected Set<String> deferred(String spoke, List<String> handles)
                throws JSONException {
            return ContactPhonePass.waiting(offline, contacts, spoke, handles);
        }

        @Override
        protected PimDomain domain() {
            return PimDomain.CONTACTS;
        }
    }

    private static String vcard(String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + JANE + "\r\nFN:" + name + "\r\nEND:VCARD\r\n";
    }

    /** Jane in Contacts and the mirrored label, whose copy holds a rename. */
    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);
        contacts = new PimdirContacts(pimdir);
        List<Addressbook> books =
                List.of(
                        new Addressbook("myContacts", "Contacts", CONTACTS, null, null),
                        new Addressbook("seed", "Seed amis", SEED, null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));

        people = new FakePeople();
        phone = new RawContacts(pimdir);
        server(CONTACTS, SEED);
        contacts.save(CONTACTS, new Card(JANE, null, null, vcard("Jane")));
        server(CONTACTS, SEED);
        contacts.stageMembership(CONTACTS, SEED, JANE, true);
        contacts.save(SEED, new Card(JANE, null, null, vcard("Jane Edit")));
        mirror();
        assertEquals(1, phone.bodies.size());
    }

    /** One server pass over the books. */
    private void server(String... books) throws Exception {
        ContactEngine engine =
                new ContactEngine(
                        store,
                        pimdir,
                        new Clients(
                                new AccountClient(), people, new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        null,
                        new Account(BASE, EMAIL, "token"));
        for (String book : books) {
            engine.syncBook(book, true);
        }
    }

    /** One phone pass of the label. */
    private void mirror() throws Exception {
        new Mirror(pimdir, phone).sync(SEED);
    }

    /** Deletes the label's raw contact, as the phone's Contacts app does. */
    private String deleteOnThePhone() {
        String handle = phone.bodies.keySet().iterator().next();
        phone.deleted.add(handle);
        return handle;
    }

    @Test
    public void aPhoneDeletionOfTheOnlyCopyOfAChangeWaitsForIt() throws Exception {
        String handle = deleteOnThePhone();
        mirror();

        assertEquals(List.of(), phone.purged);
        assertEquals(List.of(CONTACTS, SEED), contacts.collectionsOf(JANE));
        assertTrue(contacts.unsent(SEED, JANE));
        assertEquals(
                List.of(
                        new UnsentContacts.Row(JANE, "Jane Edit", SEED, null),
                        new UnsentContacts.Row(JANE, "Jane Edit", SEED, null, true)),
                UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication(), phone));
        assertTrue(phone.deleted.contains(handle));
    }

    @Test
    public void aWaitingPhoneDeletionGoesOnceTheChangeIsSent() throws Exception {
        String handle = deleteOnThePhone();
        mirror();
        contacts.save(CONTACTS, new Card(JANE, null, null, vcard("Jane Edit")));
        server(CONTACTS);
        mirror();

        assertEquals(List.of(handle), phone.purged);
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
        assertFalse(contacts.withdrawalWaits(CONTACTS, JANE));
        assertEquals(
                List.of(), UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication(), phone));
    }

    @Test
    public void aPhoneDeletionOfACopyHoldingNoChangeGoesAtOnce() throws Exception {
        contacts.save(SEED, new Card(JANE, null, null, vcard("Jane")));
        mirror();
        assertFalse(contacts.withdrawalWaits(SEED, JANE));
        String handle = deleteOnThePhone();
        mirror();

        assertEquals(List.of(handle), phone.purged);
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
    }

    @Test
    public void deletingTheContactItselfDoesNotWait() throws Exception {
        contacts.save(CONTACTS, new Card(JANE, null, null, vcard("Jane Edit")));
        contacts.stageDelete(SEED, JANE);

        assertFalse(contacts.withdrawalWaits(CONTACTS, JANE));
    }
}
