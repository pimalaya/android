package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.LocalBook;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Card;
import org.pimalaya.client.Clients;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Cards created on the phone, placed in and moved between Google labels.
 *
 * <p>People keeps the phone's UID, so the store files a card under it while
 * Google names the person {@code c1}. A label is a members:modify patch of that
 * person; a move never deletes it nor creates another, and an edit is never
 * lost nor written over a change made on the server: what cannot be sent with
 * its body waits, staged.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactEngineGoogleBooksTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BASE = "google://" + EMAIL;
    private static final String CONTACTS = BASE + "/myContacts";
    private static final String L1 = BASE + "/l1";
    private static final String SEED = BASE + "/seed";

    /** The UIDs the phone gave Jane and Joe, which People keeps. */
    private static final String JANE = "uuid-1";
    private static final String JOE = "uuid-2";

    private PimdirDb pimdir;
    private CardStore store;
    private PimdirContacts contacts;
    private FakePeople people;

    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + uid + "\r\nFN:" + name + "\r\nEND:VCARD\r\n";
    }

    /**
     * Contacts and two labels, and Jane and Joe created in Contacts on the
     * phone then synced: Google holds them as {@code c1} and {@code c2}, in
     * myContacts.
     */
    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);
        contacts = new PimdirContacts(pimdir);

        List<Addressbook> books =
                List.of(
                        new Addressbook("myContacts", "Contacts", CONTACTS, null, null),
                        new Addressbook("l1", "L1", L1, null, null),
                        new Addressbook("seed", "Seed amis", SEED, null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));

        people = new FakePeople();
        pass(CONTACTS, L1, SEED);
        contacts.save(CONTACTS, new Card(JANE, null, null, vcard(JANE, "Jane")));
        contacts.save(CONTACTS, new Card(JOE, null, null, vcard(JOE, "Joe")));
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("createCard c1", "createCard c2"), people.calls);
        people.calls.clear();
    }

    /** One sync pass over the books in the given order, one driver. */
    private void pass(String... books) throws Exception {
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

    /** The editor's save of a renamed card, staged where its replica is. */
    private void rename(String book, String uid, String name) {
        contacts.save(book, new Card(uid, null, null, vcard(uid, name)));
    }

    /** The addressbooks dialog ticking a book, as ContactWriter stages it. */
    private void tick(String from, String target, String uid) {
        contacts.stageMembership(from, target, uid, true);
    }

    /** The dialog unticking a book the card stays in elsewhere. */
    private void untick(String book, String uid) {
        contacts.stageMembership(book, book, uid, false);
    }

    /** One save ticking a book and unticking another: places, then removes. */
    private void move(String from, String target, String uid) {
        tick(from, target, uid);
        untick(from, uid);
    }

    /** Google's people and their groups, by People id. */
    private Map<String, List<String>> held() {
        Map<String, List<String>> held = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> person : people.groups.entrySet()) {
            held.put(person.getKey(), new ArrayList<>(person.getValue()));
        }
        return held;
    }

    /** The body the store holds for a card in a book, or null. */
    private String stored(String book, String uid) throws Exception {
        String handle = new PimdirStorage(pimdir).handleFor(book, uid);
        JSONObject row = new PimdirStorage(pimdir).loadRow(book, handle);
        return row == null ? null : row.getString("vcard");
    }

    /** The next pass sends nothing. */
    private void assertSettled() throws Exception {
        people.calls.clear();
        pass(CONTACTS, L1, SEED);
        assertEquals(List.of(), people.calls);
    }

    @Test
    public void aLabelOverAnUnsyncedEditPatchesThePersonGoogleNamed() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCard c1", "updateCardBooks c1 +[seed] -[]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts", "seed"), held().get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
    }

    @Test
    public void aLabelSyncedBeforeItsEditWaitsForTheEdit() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        tick(CONTACTS, SEED, JANE);
        pass(SEED, CONTACTS, L1);

        // NOTE: the label's copy carries the edit Google has not got yet, so
        // it waits for Contacts to send it, then patches the same person.
        assertEquals(List.of("updateCard c1"), people.calls);
        people.calls.clear();
        pass(SEED, CONTACTS, L1);
        assertEquals(List.of("updateCardBooks c1 +[seed] -[]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts", "seed"), held().get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
    }

    @Test
    public void aLabelAfterASyncedEditPatchesThePersonGoogleNamed() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        pass(CONTACTS, L1, SEED);
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCard c1", "updateCardBooks c1 +[seed] -[]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts", "seed"), held().get("c1"));
    }

    @Test
    public void aLabelTakenOffKeepsThePerson() throws Exception {
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);
        assertSettled();
        untick(SEED, JANE);
        people.calls.clear();
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCardBooks c1 +[] -[seed]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts"), held().get("c1"));
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
    }

    @Test
    public void aLabelTakenOffRightAfterItsAddKeepsThePerson() throws Exception {
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);
        untick(SEED, JANE);
        people.calls.clear();
        pass(CONTACTS, L1, SEED);

        // NOTE: members:modify answers no revision; the label's binding must
        // still meet the next listing at the one it names, or the untick is
        // read as overtaken by a remote edit and undone.
        assertEquals(List.of("updateCardBooks c1 +[] -[seed]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts"), held().get("c1"));
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
    }

    @Test
    public void aMoveOutOfContactsSyncedFirstOnlyPatchesMemberships() throws Exception {
        move(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);

        // NOTE: Contacts' removal is held until the label holds the card,
        // whose add then moves it out of Contacts in the same patch.
        assertEquals(List.of("updateCardBooks c1 +[seed] -[myContacts]"), people.calls);
        assertSettled();
        assertEquals(List.of("seed"), held().get("c1"));
        assertEquals(List.of(SEED), contacts.collectionsOf(JANE));
    }

    @Test
    public void aMoveIntoTheLabelSyncedFirstOnlyPatchesMemberships() throws Exception {
        move(CONTACTS, SEED, JANE);
        pass(SEED, CONTACTS, L1);

        assertEquals(List.of("updateCardBooks c1 +[seed] -[myContacts]"), people.calls);
        assertSettled();
        assertEquals(List.of("seed"), held().get("c1"));
        assertEquals(List.of(SEED), contacts.collectionsOf(JANE));
    }

    @Test
    public void anEditOnTheLabelsPendingCreateWaitsWithIt() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit");
        pass(CONTACTS, L1, SEED);
        pass(CONTACTS, L1, SEED);

        // NOTE: a patch would place the person without the edit, and the
        // next pull would erase it: the add waits, its edit staged.
        assertEquals(List.of(), people.calls);
        assertEquals(Map.of("c1", List.of("myContacts"), "c2", List.of("myContacts")), held());
        assertEquals(vcard(JANE, "Jane"), people.bodies.get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), stored(SEED, JANE));
    }

    @Test
    public void aMoveCarryingAnEditDeliversItGuarded() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        move(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);

        // NOTE: the edit goes as a guarded update of the person moved.
        assertEquals(
                List.of("updateCard c1", "updateCardBooks c1 +[seed] -[myContacts]"),
                people.calls);
        assertSettled();
        assertEquals(List.of("seed"), held().get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
        assertEquals(List.of(SEED), contacts.collectionsOf(JANE));
        assertEquals(List.of(), UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));
    }

    @Test
    public void aMoveCarryingAnEditLabelFirstDeliversItGuarded() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        move(CONTACTS, SEED, JANE);
        pass(SEED, CONTACTS, L1);

        assertEquals(
                List.of("updateCard c1", "updateCardBooks c1 +[seed] -[myContacts]"),
                people.calls);
        assertSettled();
        assertEquals(List.of("seed"), held().get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
        assertEquals(List.of(SEED), contacts.collectionsOf(JANE));
    }

    @Test
    public void aMoveCarryingAnEditOverARemoteEditWritesNothingOver() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        move(CONTACTS, SEED, JANE);
        people.edit("c1", vcard(JANE, "Jane Remote"));
        pass(CONTACTS, L1, SEED);
        pass(CONTACTS, L1, SEED);

        assertTrue(people.calls.toString(), !people.calls.contains("updateCard c1"));
        assertEquals(vcard(JANE, "Jane Remote"), people.bodies.get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), stored(SEED, JANE));
    }

    @Test
    public void aMoveCarryingAnEditOverARemoteEditLabelFirstWritesNothingOver()
            throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        move(CONTACTS, SEED, JANE);
        people.edit("c1", vcard(JANE, "Jane Remote"));
        pass(SEED, CONTACTS, L1);
        pass(SEED, CONTACTS, L1);

        assertTrue(people.calls.toString(), !people.calls.contains("updateCard c1"));
        assertEquals(vcard(JANE, "Jane Remote"), people.bodies.get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), stored(SEED, JANE));
    }

    @Test
    public void anEditedMoveBesideContactsDeliversItsEdit() throws Exception {
        tick(CONTACTS, L1, JANE);
        pass(L1, CONTACTS, SEED);
        assertSettled();

        rename(L1, JANE, "Jane Edit");
        move(L1, SEED, JANE);
        people.calls.clear();
        pass(L1, CONTACTS, SEED);

        assertEquals(
                List.of("updateCard c1", "updateCardBooks c1 +[seed] -[l1]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts", "seed"), held().get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
        assertEquals(vcard(JANE, "Jane Edit"), stored(CONTACTS, JANE));
        assertEquals(List.of(CONTACTS, SEED), contacts.collectionsOf(JANE));
    }

    @Test
    public void twoMovesInOneRoundPatchEachPerson() throws Exception {
        move(CONTACTS, SEED, JANE);
        move(CONTACTS, SEED, JOE);
        pass(CONTACTS, L1, SEED);

        assertEquals(
                List.of(
                        "updateCardBooks c1 +[seed] -[myContacts]",
                        "updateCardBooks c2 +[seed] -[myContacts]"),
                people.calls);
        assertSettled();
        assertEquals(Map.of("c1", List.of("seed"), "c2", List.of("seed")), held());
    }

    @Test
    public void aRoundOfMovesDeliversTheOneCarryingAnEdit() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        move(CONTACTS, SEED, JANE);
        move(CONTACTS, SEED, JOE);
        pass(SEED, CONTACTS, L1);

        assertEquals(
                List.of(
                        "updateCard c1",
                        "updateCardBooks c1 +[seed] -[myContacts]",
                        "updateCardBooks c2 +[seed] -[myContacts]"),
                people.calls);
        assertSettled();
        assertEquals(Map.of("c1", List.of("seed"), "c2", List.of("seed")), held());
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
    }

    @Test
    public void twoLabelsTakenOffInOneRoundKeepThePeople() throws Exception {
        tick(CONTACTS, SEED, JANE);
        tick(CONTACTS, SEED, JOE);
        pass(CONTACTS, L1, SEED);
        assertSettled();
        untick(SEED, JANE);
        untick(SEED, JOE);
        people.calls.clear();
        pass(CONTACTS, L1, SEED);

        assertEquals(
                List.of("updateCardBooks c1 +[] -[seed]", "updateCardBooks c2 +[] -[seed]"),
                people.calls);
        assertSettled();
        assertEquals(Map.of("c1", List.of("myContacts"), "c2", List.of("myContacts")), held());
    }

    @Test
    public void untickingTheLabelHoldingAnEditKeepsItThroughTheForm() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit");
        pass(CONTACTS, L1, SEED);
        assertEquals(List.of(), people.calls);

        // NOTE: the editor asks before an untick withdraws the only copy of
        // an edit not sent. A copy never sent knows no ancestor: keeping it
        // opens the form on Contacts, nothing written; the name kept there,
        // the untick asks nothing more.
        assertTrue(contacts.unsent(SEED, JANE));
        PimdirContacts.KeepCollision collision = contacts.keepEdit(SEED, JANE);
        assertEquals(CONTACTS, collision.collection());
        assertEquals(vcard(JANE, "Jane"), stored(CONTACTS, JANE));

        contacts.save(CONTACTS, new Card(JANE, null, null, vcard(JANE, "Jane Edit")));
        assertFalse(contacts.unsent(SEED, JANE));
        untick(SEED, JANE);
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCard c1"), people.calls);
        assertSettled();
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
        assertEquals(List.of("myContacts"), held().get("c1"));
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
    }

    @Test
    public void keepingACopyNeverSentOverAPulledRemoteEditOpensTheForm() throws Exception {
        rename(CONTACTS, JANE, "Jane\r\nTEL:1");
        pass(CONTACTS, L1, SEED);
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit\r\nTEL:1");
        pass(CONTACTS, L1, SEED);
        people.edit("c1", vcard(JANE, "Jane\r\nTEL:2"));
        pass(CONTACTS, L1, SEED);

        // NOTE: nothing written; the form starts from what Contacts holds,
        // the web's number in, the copy's name offered beside its own.
        PimdirContacts.KeepCollision collision = contacts.keepEdit(SEED, JANE);

        assertEquals(CONTACTS, collision.collection());
        String form = collision.form().vcard();
        assertTrue(form, form.contains("TEL:2\r\n"));
        assertFalse(form, form.contains("TEL:1"));
        String choices = collision.form().alternatives().toString();
        assertTrue(choices, choices.contains("Jane Edit"));
        assertEquals(vcard(JANE, "Jane\r\nTEL:2"), stored(CONTACTS, JANE));
        people.calls.clear();
        pass(CONTACTS, L1, SEED);
        assertEquals(List.of(), people.calls);
    }

    @Test
    public void keepingACopyNeverSentBeforeTheRemoteEditWritesNothing() throws Exception {
        rename(CONTACTS, JANE, "Jane\r\nTEL:1");
        pass(CONTACTS, L1, SEED);
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit\r\nTEL:1");
        pass(CONTACTS, L1, SEED);

        assertEquals(CONTACTS, contacts.keepEdit(SEED, JANE).collection());
        people.edit("c1", vcard(JANE, "Jane\r\nTEL:2"));
        people.calls.clear();
        pass(CONTACTS, L1, SEED);
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of(), people.calls);
        assertEquals(vcard(JANE, "Jane\r\nTEL:2"), people.bodies.get("c1"));
        assertEquals(vcard(JANE, "Jane Edit\r\nTEL:1"), stored(SEED, JANE));
    }

    @Test
    public void keepingASentBooksEditMergesFromItsOwnBase() throws Exception {
        rename(CONTACTS, JANE, "Jane\r\nTEL:1");
        pass(CONTACTS, L1, SEED);
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);
        rename(CONTACTS, JANE, "Jane\r\nTEL:2");
        pass(CONTACTS, L1, SEED);
        pass(CONTACTS, L1, SEED);
        people.edit("c1", vcard(JANE, "Jane\r\nTEL:1"));
        rename(SEED, JANE, "Jane Edit\r\nTEL:2");
        pass(CONTACTS);

        // NOTE: Seed was sent at TEL:2, the web then set TEL:1, which
        // Contacts pulled: Seed's own base tells its edit, the name alone.
        assertTrue(contacts.unsent(SEED, JANE));
        assertNull(contacts.keepEdit(SEED, JANE));
        untick(SEED, JANE);
        people.calls.clear();
        pass(CONTACTS, L1, SEED);

        String body = people.bodies.get("c1");
        assertTrue(body, body.contains("FN:Jane Edit\r\n"));
        assertTrue(body, body.contains("TEL:1\r\n"));
        assertFalse(body, body.contains("TEL:2"));
        assertSettled();
        assertEquals(List.of("myContacts"), held().get("c1"));
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
    }

    @Test
    public void anAddSentByARunThatFailedAfterIsNoLongerReported() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit");
        pass(CONTACTS, L1, SEED);
        assertEquals(
                List.of(new UnsentContacts.Row(JANE, "Jane Edit", SEED, null)),
                UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));

        // NOTE: the web sets the same name, which Contacts pulls; the next run
        // of Seed then sends the add, and its listing after fails.
        people.edit("c1", vcard(JANE, "Jane Edit"));
        pass(CONTACTS);
        people.failAfter = 1;
        try {
            pass(SEED);
        } catch (Exception failed) {
            // the run failing after its push is the case
        }

        assertTrue(held().toString(), held().get("c1").contains("seed"));
        assertEquals(List.of(), UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));
    }

    @Test
    public void keepingAnEditOverANewerOneLosesNeither() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane E1");
        pass(CONTACTS, L1, SEED);
        rename(CONTACTS, JANE, "Jane E2");

        // NOTE: both renamed the contact apart: nothing is written, the
        // editor opening the two names for the person to choose.
        PimdirContacts.KeepCollision collision = contacts.keepEdit(SEED, JANE);

        assertEquals(CONTACTS, collision.collection());
        assertFalse(collision.form().resolved());
        assertEquals(vcard(JANE, "Jane E2"), stored(CONTACTS, JANE));
        assertEquals(vcard(JANE, "Jane E1"), stored(SEED, JANE));
    }

    @Test
    public void aHeldAddIsStillReportedAfterARestart() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit");
        pass(CONTACTS, L1, SEED);

        PimdirDb restarted = new PimdirDb(RuntimeEnvironment.getApplication());
        assertEquals(
                List.of(new UnsentContacts.Row(JANE, "Jane Edit", SEED, null)),
                UnsentContacts.rows(restarted, RuntimeEnvironment.getApplication()));
    }

    @Test
    public void onlyTheSoleCopyOfAnEditNotSentIsWarnedAbout() throws Exception {
        tick(CONTACTS, SEED, JANE);
        assertFalse(contacts.unsent(SEED, JANE));
        assertFalse(contacts.unsent(CONTACTS, JANE));

        rename(CONTACTS, JANE, "Jane Edit");
        assertTrue(contacts.unsent(CONTACTS, JANE));
        assertFalse(contacts.unsent(SEED, JANE));
    }

    @Test
    public void aTickOverARemoteEditFollowsItRatherThanWaiting() throws Exception {
        tick(CONTACTS, SEED, JANE);
        people.edit("c1", vcard(JANE, "Jane Remote"));
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCardBooks c1 +[seed] -[]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts", "seed"), held().get("c1"));
        assertEquals(vcard(JANE, "Jane Remote"), people.bodies.get("c1"));
        assertEquals(vcard(JANE, "Jane Remote"), stored(SEED, JANE));
    }

    @Test
    public void removingTheAccountKeepsAnEditNotSent() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit");
        pass(CONTACTS, L1, SEED);

        store.ensureLocalAddressbook(LocalBook.URL, LocalBook.ACCOUNT, LocalBook.ID);
        new PimdirCollections(pimdir, RuntimeEnvironment.getApplication())
                .ensure(LocalBook.URL, LocalBook.ACCOUNT, PimdirSummary.CONTACT, "On this device");
        contacts.detachToLocal(List.of(CONTACTS, L1, SEED), LocalBook.URL);

        assertEquals(vcard(JANE, "Jane Edit"), stored(LocalBook.URL, JANE));
    }

    @Test
    public void aHeldAddIsReportedForItsContactAndBook() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit");
        pass(CONTACTS, L1, SEED);

        assertEquals(
                List.of(new UnsentContacts.Row(JANE, "Jane Edit", SEED, null)),
                UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));
    }

    @Test
    public void aMoveIntoABookNeverSyncedIsReported() throws Exception {
        move(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1);
        pass(CONTACTS, L1);

        assertEquals(List.of(), people.calls);
        assertEquals(
                List.of(new UnsentContacts.Row(JANE, "Jane", CONTACTS, SEED)),
                UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));
    }

    @Test
    public void aMoveSentInThePassIsNotReported() throws Exception {
        move(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of(), UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));
    }

    @Test
    public void aRefusedUpdateIsMergedAsAConflictNeverSentUnguarded() throws Exception {
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);
        assertSettled();

        // NOTE: a 412 from People is a conflict, merged and sent in the pass.
        rename(CONTACTS, JANE, "Jane Edit");
        untick(SEED, JANE);
        people.refuseGuard = true;
        people.calls.clear();
        pass(CONTACTS, L1, SEED);

        assertEquals(
                List.of(
                        "updateCard c1 refused",
                        "updateCard c1",
                        "updateCardBooks c1 +[] -[seed]"),
                people.calls);
        assertSettled();
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
        assertEquals(List.of("myContacts"), held().get("c1"));
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
    }

    @Test
    public void anAddWaitingOnlyForAnotherBooksEditIsNotReported() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(CONTACTS, JANE, "Jane Edit");
        rename(SEED, JANE, "Jane Edit");
        pass(SEED);

        // NOTE: Contacts sends the same edit on its own, then Seed patches.
        assertEquals(List.of(), people.calls);
        assertEquals(List.of(), UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCard c1", "updateCardBooks c1 +[seed] -[]"), people.calls);
        assertSettled();
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
    }

    @Test
    public void aMoveCarryingAnEditIntoABookNotSyncedYetReportsItsRemovalAlone()
            throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        move(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1);

        assertEquals(
                List.of(new UnsentContacts.Row(JANE, "Jane Edit", CONTACTS, SEED)),
                UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));
    }

    @Test
    public void anEditLandingAfterTheListingIsNeverWrittenOver() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        people.afterListing = () -> people.edit("c1", vcard(JANE, "Jane Remote"));
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCard c1 refused"), people.calls);
        assertEquals(vcard(JANE, "Jane Remote"), people.bodies.get("c1"));
        people.calls.clear();
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of(), people.calls);
        assertEquals(vcard(JANE, "Jane Remote"), people.bodies.get("c1"));
        assertEquals(1, new PimdirStorage(pimdir).loadConflicts(CONTACTS).size());
    }

    @Test
    public void anEditLandingAfterTheListingOfAnotherFieldMerges() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        people.afterListing = () -> people.edit("c1", vcard(JANE, "Jane\r\nTEL:2"));
        pass(CONTACTS, L1, SEED);
        people.calls.clear();
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCard c1"), people.calls);
        String body = people.bodies.get("c1");
        assertTrue(body, body.contains("FN:Jane Edit\r\n"));
        assertTrue(body, body.contains("TEL:2\r\n"));
        assertSettled();
    }

    /** The web moves Jane out of every book into one this phone skips. */
    private void movedAwayOnTheWeb() {
        people.groups.get("c1").clear();
        people.groups.get("c1").add("other");
        people.edit("c1", people.bodies.get("c1"));
    }

    @Test
    public void aTickWhileTheWebMovesTheCardAwayMakesNoSecondPerson() throws Exception {
        tick(CONTACTS, SEED, JANE);
        movedAwayOnTheWeb();
        pass(CONTACTS, L1, SEED);

        // NOTE: the web's move and the phone's tick merge on the one person.
        assertEquals(List.of("updateCardBooks c1 +[seed] -[]"), people.calls);
        assertSettled();
        assertEquals(List.of("other", "seed"), held().get("c1"));
        assertEquals(List.of(SEED), contacts.collectionsOf(JANE));
    }

    @Test
    public void aTickCarryingAnEditWhileTheWebMovesTheCardAwayIsHeld() throws Exception {
        tick(CONTACTS, SEED, JANE);
        rename(SEED, JANE, "Jane Edit");
        movedAwayOnTheWeb();
        pass(CONTACTS, L1, SEED);
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of(), people.calls);
        assertEquals(List.of("other"), held().get("c1"));
        assertEquals(
                List.of(new UnsentContacts.Row(JANE, "Jane Edit", SEED, null)),
                UnsentContacts.rows(pimdir, RuntimeEnvironment.getApplication()));
    }

    @Test
    public void aMoveWhileTheWebMovesTheCardAwayMakesNoSecondPerson() throws Exception {
        move(CONTACTS, SEED, JANE);
        movedAwayOnTheWeb();
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCardBooks c1 +[seed] -[myContacts]"), people.calls);
        assertSettled();
        assertEquals(List.of("other", "seed"), held().get("c1"));
        assertEquals(List.of(SEED), contacts.collectionsOf(JANE));
    }

    @Test
    public void anUntickRightAfterAnEditMovedTheRevisionIsKept() throws Exception {
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);
        assertSettled();

        // NOTE: Seed's binding is left a revision behind the phone's own write.
        rename(CONTACTS, JANE, "Jane Edit");
        pass(SEED, CONTACTS, L1);
        untick(SEED, JANE);
        people.calls.clear();
        pass(SEED, CONTACTS, L1);

        assertEquals(List.of("updateCardBooks c1 +[] -[seed]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts"), held().get("c1"));
        assertEquals(List.of(CONTACTS), contacts.collectionsOf(JANE));
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
    }

    @Test
    public void anUntickRightAfterAPatchMovedTheRevisionIsKept() throws Exception {
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);
        assertSettled();
        tick(CONTACTS, L1, JANE);
        pass(SEED, CONTACTS, L1);
        untick(SEED, JANE);
        people.calls.clear();
        pass(SEED, CONTACTS, L1);

        assertEquals(List.of("updateCardBooks c1 +[] -[seed]"), people.calls);
        assertSettled();
        assertEquals(List.of("myContacts", "l1"), held().get("c1"));
        assertEquals(List.of(CONTACTS, L1), contacts.collectionsOf(JANE));
    }

    @Test
    public void anUntickOverAnEditMadeElsewhereIsStillRevived() throws Exception {
        tick(CONTACTS, SEED, JANE);
        pass(CONTACTS, L1, SEED);
        assertSettled();
        untick(SEED, JANE);
        people.edit("c1", vcard(JANE, "Jane Remote"));
        people.calls.clear();
        pass(SEED, CONTACTS, L1);

        assertEquals(List.of(), people.calls);
        assertEquals(List.of(CONTACTS, SEED), contacts.collectionsOf(JANE));
        assertEquals(vcard(JANE, "Jane Remote"), stored(SEED, JANE));
    }

    /** A move with an edit whose update lands, its patch failing (a 503). */
    private void halfLandedMove() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        move(CONTACTS, SEED, JANE);
        people.failBooks = 1;
        try {
            pass(SEED);
        } catch (Exception failed) {
            // the patch failing after the update is the case
        }
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
        assertEquals(List.of("myContacts"), held().get("c1"));
        people.calls.clear();
    }

    @Test
    public void aHalfLandedMoveCompletesWhenItsSourceSyncsFirst() throws Exception {
        halfLandedMove();
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCardBooks c1 +[seed] -[myContacts]"), people.calls);
        assertSettled();
        assertEquals(List.of("seed"), held().get("c1"));
        assertEquals(List.of(SEED), contacts.collectionsOf(JANE));
    }

    @Test
    public void aHalfLandedMoveCompletesWhenItsTargetSyncsFirst() throws Exception {
        halfLandedMove();
        pass(SEED, CONTACTS, L1);

        assertEquals(List.of("updateCardBooks c1 +[seed] -[myContacts]"), people.calls);
        assertSettled();
        assertEquals(List.of("seed"), held().get("c1"));
        assertEquals(List.of(SEED), contacts.collectionsOf(JANE));
    }

    @Test
    public void anUpdateRefusedWithNoListedChangeIsAConflict() throws Exception {
        rename(CONTACTS, JANE, "Jane Edit");
        people.refuseAll = true;
        pass(CONTACTS, L1, SEED);
        pass(CONTACTS, L1, SEED);

        // NOTE: refused again and again, the update reads as a conflict.
        assertEquals(vcard(JANE, "Jane"), people.bodies.get("c1"));
        assertEquals(1, new PimdirStorage(pimdir).loadConflicts(CONTACTS).size());
        people.refuseAll = false;
        people.calls.clear();
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("updateCard c1"), people.calls);
        assertSettled();
        assertEquals(vcard(JANE, "Jane Edit"), people.bodies.get("c1"));
        assertEquals(0, new PimdirStorage(pimdir).loadConflicts(CONTACTS).size());
    }

    @Test
    public void twoCardsDeletedInOneRoundAreNamedByGoogle() throws Exception {
        contacts.stageDelete(CONTACTS, JANE);
        contacts.stageDelete(CONTACTS, JOE);
        pass(CONTACTS, L1, SEED);

        assertEquals(List.of("deleteCard c1", "deleteCard c2"), people.calls);
        assertSettled();
        assertEquals(Map.of(), held());
    }
}
