package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A card created on the phone and pushed to a CardDAV server that does not
 * keep it as sent, the real engine and store over a fake server: Google
 * files it under a name of its own and rewrites its UID to that name, and
 * a server altering what it stores answers the PUT with no ETag.
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
                if (card != null) {
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
                return indexed.card;
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
     * Google rewrote the card's UID when it took the create, so its body
     * names another identity once it moves: the card stays the one the
     * phone created, and the edit staged on it meanwhile is merged and
     * pushed rather than dropped with an item read as replaced.
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
     * A server answering the create with no ETag: the binding takes the one
     * the card is stored at, so the listing after it reads nothing changed
     * and the removal staged meanwhile is pushed, not undone.
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
}
