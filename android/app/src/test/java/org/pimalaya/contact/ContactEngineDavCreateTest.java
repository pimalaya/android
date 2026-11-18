package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.R;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.BookCard;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardDelta;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.NewCard;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.StubHost;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A phone-created card pushed to a CardDAV server that alters what it keeps.
 *
 * <p>Real engine and store over a fake server: Google files it under its own
 * name and rewrites its UID; an altering server answers the PUT with no ETag.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactEngineDavCreateTest {
    private static final String EMAIL = "dav-create@example.com";
    private static final String BASE = "https://dav.example.com/";
    private static final String BOOK = BASE + "books/created/";

    private final Account account = new Account(BASE, EMAIL, "secret");

    private CardStore store;
    private PimdirDb pimdir;

    /** A CardDAV book listed whole on every round, as Google lists it. */
    private static final class Dav extends ContactClient {
        /** The cards by resource name, as (ETag, body). */
        final Map<String, String[]> cards = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();

        /** The bytes a card is served as, where they are not its text's. */
        final Map<String, byte[]> served = new LinkedHashMap<>();

        /** What an update answers instead of landing, by resource name. */
        final Map<String, PimalayaException> refusing = new LinkedHashMap<>();

        /** The updates asked, by resource name. */
        final List<String> updates = new ArrayList<>();

        /** Files a create under a name of its own and rewrites its UID. */
        final boolean renames;

        /** Answers a write with the ETag it stored it at. */
        final boolean answersEtag;

        int revisions;

        Dav(boolean renames, boolean answersEtag) {
            this.renames = renames;
            this.answersEtag = answersEtag;
        }

        /** Another client's edit: the card's body and ETag move. */
        void editElsewhere(String name, String from, String to) {
            String[] card = cards.get(name);
            card[0] = "e" + ++revisions;
            card[1] = card[1].replace(from, to);
        }

        @Override
        public CardDelta syncCards(
                Transport transport, Account account, String addressbookUrl, String syncToken) {
            List<Card> listed = new ArrayList<>();
            for (Map.Entry<String, String[]> card : cards.entrySet()) {
                listed.add(new Card(card.getKey(), card.getKey(), card.getValue()[0], ""));
            }
            return new CardDelta(listed, List.of(), null, true);
        }

        @Override
        public List<Card> multigetCards(
                Transport transport, Account account, String addressbookUrl, List<String> uris) {
            List<Card> read = new ArrayList<>();
            for (String uri : uris) {
                String[] card = cards.get(uri);
                byte[] bytes = served.get(uri);
                if (card != null && bytes != null) {
                    // NOTE: as the bridge hands a body that is not UTF-8.
                    read.add(
                            new Card(
                                    uri,
                                    uri,
                                    card[0],
                                    new String(bytes, StandardCharsets.UTF_8),
                                    Base64.getEncoder().encodeToString(bytes),
                                    List.of()));
                } else if (card != null) {
                    read.add(new Card(uri, uri, card[0], card[1]));
                }
            }
            return read;
        }

        @Override
        public Card createCard(
                Transport transport, Account account, String addressbookUrl, NewCard created) {
            String id = created.id();
            String vcard = created.vcard();
            String name = renames ? "g" + (cards.size() + 1) : id;
            String stored = renames ? vcard.replaceAll("UID:[^\r\n]*", "UID:" + name) : vcard;
            cards.put(name, new String[] {"e" + ++revisions, stored});
            return new Card(name, name, answersEtag ? "e" + revisions : null, vcard);
        }

        @Override
        public Card updateCard(
                Transport transport, Account account, BookCard updated, String baseVcard) {
            Card card = updated.card();
            updates.add(card.uri());
            PimalayaException refused = refusing.get(card.uri());
            if (refused != null) {
                throw refused;
            }
            String[] held = cards.get(card.uri());
            if (held == null) {
                throw new PimalayaException("not found: " + card.uri(), 404);
            }
            held[0] = "e" + ++revisions;
            held[1] = card.vcard();
            return new Card(card.id(), card.uri(), answersEtag ? held[0] : null, card.vcard());
        }

        @Override
        public void deleteCard(Transport transport, Account account, BookCard removed) {
            Card card = removed.card();
            if (cards.remove(card.uri()) == null) {
                throw new PimalayaException("not found: " + card.uri(), 404);
            }
            deleted.add(card.uri());
        }
    }

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);

        List<Addressbook> books =
                List.of(new Addressbook("created", "Created", BOOK, null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));
    }

    private void sync(Dav dav) throws Exception {
        new ContactEngine(
                        store,
                        pimdir,
                        new Clients(
                                new AccountClient(), dav, new CalendarClient(), new MailClient(),
                                new OfflineClient()),
                        null,
                        account)
                .syncBook(BOOK, true);
    }

    private PimdirContacts contacts() {
        return new PimdirContacts(pimdir);
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        for (PimdirContacts.Indexed indexed : contacts().list(BOOK)) {
            names.add(indexed.name);
        }
        return names;
    }

    private Card held(String name) {
        for (PimdirContacts.Indexed indexed : contacts().list(BOOK)) {
            if (indexed.name.equals(name)) {
                return indexed.card();
            }
        }
        throw new AssertionError("no " + name + " in " + names());
    }

    /** A card as the contact form saves a new one. */
    private void create(String uid, String name) {
        String vcard =
                "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + uid + "\r\nFN:" + name + "\r\nN:;" + name
                        + ";;;\r\nEND:VCARD\r\n";
        contacts().save(BOOK, new Card(uid, null, null, vcard));
    }

    /**
     * Google rewrote the created card's UID: it stays the phone's card.
     *
     * <p>The edit staged on it meanwhile is merged and pushed, not dropped with
     * an item read as replaced.
     */
    @Test
    public void anEditOfACardGoogleRewroteIsKeptWhenItMovesElsewhere() throws Exception {
        Dav dav = new Dav(true, true);
        create("u-1", "Created");
        sync(dav);
        assertEquals(List.of("g1"), new ArrayList<>(dav.cards.keySet()));

        dav.editElsewhere("g1", "END:VCARD", "TEL:+33100000000\r\nEND:VCARD");
        Card created = held("Created");
        contacts().save(
                BOOK,
                new Card(created.id(), created.uri(), created.etag(),
                        created.vcard().replace("FN:Created", "FN:Edited")));
        sync(dav);

        String server = dav.cards.get("g1")[1];
        assertTrue("the phone's edit pushed: " + server, server.contains("FN:Edited"));
        assertTrue("the edit elsewhere kept: " + server, server.contains("TEL:+33100000000"));
        assertEquals(List.of("Edited"), names());
        assertEquals("still the card the phone created", "u-1", held("Edited").id());
    }

    /**
     * A create answered with no ETag: the binding takes the stored one.
     *
     * <p>The next listing reads nothing changed, so the removal staged
     * meanwhile is pushed, not undone.
     */
    @Test
    public void aCardCreatedThenDeletedIsDeletedOnAServerAnsweringNoEtag() throws Exception {
        Dav dav = new Dav(true, false);
        create("u-1", "Created");
        sync(dav);

        contacts().stageDelete(BOOK, held("Created").id());
        sync(dav);

        assertEquals(List.of("g1"), dav.deleted);
        sync(dav);
        assertEquals("not pulled back", List.of(), names());
    }

    /** A listed card stating {@code uid}, named {@code name}. */
    private static String listed(String uid, String name) {
        String line = uid == null ? "" : "UID:" + uid + "\r\n";
        return "BEGIN:VCARD\r\nVERSION:3.0\r\n" + line + "FN:" + name + "\r\nN:;" + name
                + ";;;\r\nEND:VCARD\r\n";
    }

    /**
     * Each card is keyed as STORAGE §9 assigns: a UID spelled like a key the
     * writer derives is escaped, a blank or missing one takes the hash key.
     */
    @Test
    public void aListedCardIsKeyedAsStorageNineAssigns() throws Exception {
        Dav dav = new Dav(false, true);
        dav.cards.put("c1.vcf", new String[] {"e1", listed("hash:0123", "Escaped")});
        dav.cards.put("c2.vcf", new String[] {"e2", listed("   ", "Blank")});
        dav.cards.put("c3.vcf", new String[] {"e3", listed(null, "Missing")});
        dav.cards.put("c4.vcf", new String[] {"e4", listed("urn:uuid:4", "Plain")});

        sync(dav);

        assertEquals("esc:hash:0123", held("Escaped").id());
        assertEquals("urn:uuid:4", held("Plain").id());
        for (String name : List.of("Blank", "Missing")) {
            String id = held(name).id();
            assertTrue(name + " takes the hash key: " + id, id.matches("hash:[0-9a-f]{16}"));
        }
    }

    /**
     * A card created under a UID spelled like a derived key is pushed under
     * its UID, never the escape, and the listing lands it.
     */
    @Test
    public void aCreateUnderAnEscapedUidLandsOnItsListing() throws Exception {
        Dav dav = new Dav(false, true);
        String vcard = listed("hash:0123", "Created");
        contacts().save(BOOK, new Card("esc:hash:0123", null, null, vcard));

        sync(dav);
        sync(dav);

        assertEquals(List.of("hash:0123.vcf"), new ArrayList<>(dav.cards.keySet()));
        assertEquals(List.of("Created"), names());
        assertEquals("esc:hash:0123", held("Created").id());
    }

    /**
     * A Latin-1 vCard 2.1 is stored as the bytes its server sent, hashed as
     * those, and an edit of its text, which holds U+FFFD where the bytes
     * were, is refused rather than written to the server.
     */
    @Test
    public void aCardThatIsNotUtf8IsStoredAsServedAndNeverWrittenBackAsText()
            throws Exception {
        byte[] served =
                ("BEGIN:VCARD\r\nVERSION:2.1\r\nUID:latin\r\nFN:René\r\n"
                                + "N:;René;;;\r\nEND:VCARD\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1);
        Dav dav = new Dav(false, true);
        dav.cards.put("c1.vcf", new String[] {"e1", new String(served, StandardCharsets.UTF_8)});
        dav.served.put("c1.vcf", served);

        sync(dav);

        PimdirContacts.Indexed card = contacts().list(BOOK).get(0);
        assertEquals("latin", card.id);
        assertTrue(java.util.Arrays.equals(
                served, new org.pimalaya.pimdir.PimdirItems(pimdir).objectBytes(BOOK, "latin")));
        assertEquals(org.pimalaya.pimdir.PimdirHash.of(served), card.object);

        Card held = card.card();
        org.junit.Assert.assertThrows(
                PimalayaException.class,
                () -> contacts().save(
                        BOOK,
                        new Card(held.id(), held.uri(), held.etag(),
                                held.vcard().replace("END:VCARD", "TEL:1\r\nEND:VCARD"))));
        sync(dav);
        assertEquals("e1", dav.cards.get("c1.vcf")[0]);
    }

    /**
     * A card kept as served, not UTF-8, ticked into another book from the
     * editor: the copy would carry its text, U+FFFD where the bytes were, so
     * it is refused as an edit is, nothing staged in the other book.
     */
    @Test
    public void aCardThatIsNotUtf8IsNotCopiedIntoAnotherBook() throws Exception {
        String other = BASE + "books/other/";
        List<Addressbook> books =
                List.of(
                        new Addressbook("created", "Created", BOOK, null, null),
                        new Addressbook("other", "Other", other, null, null));
        store.replaceAddressbooks(EMAIL, books);
        new PimdirCollections(pimdir, RuntimeEnvironment.getApplication())
                .replace(EMAIL, PimdirSummary.CONTACT, PimdirCollections.of(EMAIL, books));
        byte[] served =
                ("BEGIN:VCARD\r\nVERSION:2.1\r\nUID:latin\r\nFN:René\r\n"
                                + "N:;René;;;\r\nEND:VCARD\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1);
        Dav dav = new Dav(false, true);
        dav.cards.put("c1.vcf", new String[] {"e1", new String(served, StandardCharsets.UTF_8)});
        dav.served.put("c1.vcf", served);
        sync(dav);

        StubHost host = Robolectric.buildActivity(StubHost.class).create().get();
        host.setContentView(R.layout.panel_contact);
        List<AccountEntry> accounts =
                new ArrayList<>(
                        List.of(
                                AccountEntry.empty(EMAIL)
                                        .with(
                                                PimDomain.CONTACTS,
                                                BASE,
                                                null,
                                                AccountCredential.password(EMAIL, "secret"))));
        ContactPool pool = new ContactPool(store, contacts(), accounts);
        Clients clients =
                new Clients(
                        new AccountClient(), dav, new CalendarClient(), new MailClient(),
                        new OfflineClient());
        ContactEditor editor =
                new ContactEditor(
                        host,
                        new ContactParts(pimdir, store, contacts(), pool, clients, accounts),
                        new ContactForm(host),
                        () -> {});
        for (Group group : pool.group(pool.loadEntries(), accounts)) {
            if (group.primary().id.equals("latin")) {
                editor.openGroup(group);
            }
        }
        editor.edit().pendingBookState = Map.of(other, true);
        editor.save();

        assertEquals(List.of(BOOK), contacts().collectionsOf("latin"));
    }

    /**
     * An update the server refuses as it stands (a 415 naming the request, as
     * the bridge reads it) is that card's: the book's other edits land, the
     * refusal is recorded on its binding, and the next pass holds it rather
     * than asking the server again (pimdir SYNC §4, §5).
     */
    @Test
    public void anUpdateRefusedAsItStandsIsHeldAndTheRestPushed() throws Exception {
        Dav dav = new Dav(false, true);
        for (String name : List.of("Ann", "Bob", "Cid")) {
            String uri = name.toLowerCase() + ".vcf";
            dav.cards.put(uri, new String[] {"e-" + name, listed("u-" + name, name)});
        }
        sync(dav);
        for (String name : List.of("Ann", "Bob", "Cid")) {
            Card card = held(name);
            contacts().save(
                    BOOK,
                    new Card(card.id(), card.uri(), card.etag(),
                            card.vcard().replace("FN:" + name, "FN:" + name + " Edited")));
        }
        dav.refusing.put(
                "bob.vcf",
                new PimalayaException("WebDAV server returned HTTP 415", 415, true));

        sync(dav);

        assertTrue(dav.cards.get("ann.vcf")[1].contains("FN:Ann Edited"));
        assertTrue(dav.cards.get("cid.vcf")[1].contains("FN:Cid Edited"));
        assertTrue(dav.cards.get("bob.vcf")[1].contains("FN:Bob\r\n"));
        try (android.database.Cursor cursor =
                pimdir.getReadableDatabase()
                        .rawQuery(
                                "SELECT handle FROM bindings WHERE refusal IS NOT NULL", null)) {
            assertTrue("recorded on the binding", cursor.moveToFirst());
            assertEquals("bob.vcf", cursor.getString(0));
        }

        dav.updates.clear();
        sync(dav);
        assertTrue("held: " + dav.updates, !dav.updates.contains("bob.vcf"));
    }
}
