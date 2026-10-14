package org.pimalaya.contact;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.pimalaya.account.LocalBook;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;

/** Pins the merge flow's and the addressbooks dialog's decisions. */
public class ContactMergeTest {
    private static final Addressbook WORK = book("work");
    private static final Addressbook HOME = book("home");
    private static final Addressbook CLUB = book("club");

    @Test
    public void candidatesKeepTheFirstReplicaOfEachBookInOrder() throws Exception {
        Entry first = entry(HOME, "1", "a");
        Entry second = entry(WORK, "2", "a");
        Entry again = entry(HOME, "3", "b");
        Entry third = entry(CLUB, "4", "a");

        assertEquals(
                List.of(first, second, third),
                ContactMerge.candidates(List.of(first, second, again, third)));
    }

    @Test
    public void identicalOnlyForOneHash() throws Exception {
        Entry one = entry(HOME, "1", "a");
        Entry same = entry(WORK, "2", "a");
        Entry other = entry(CLUB, "3", "b");

        assertTrue(ContactMerge.identical(List.of(one, same)));
        assertFalse(ContactMerge.identical(List.of(one, same, other)));
    }

    @Test
    public void doomedSkipsTheSurvivorsCardAndASharedCardOnce() throws Exception {
        // NOTE: the ref is the card id here, as on an account-level backend
        // where one card sits in several books.
        Entry survivor = entry(HOME, "1", "a");
        Entry survivorElsewhere = entry(WORK, "1", "a");
        Entry shared = entry(HOME, "2", "b");
        Entry sharedElsewhere = entry(CLUB, "2", "b");
        Entry lone = entry(WORK, "3", "c");

        assertEquals(
                List.of(shared, lone),
                ContactMerge.doomed(
                        List.of(survivor, survivorElsewhere, shared, sharedElsewhere, lone),
                        survivor,
                        entry -> entry.card.id));
    }

    @Test
    public void documentsOnePerDistinctHashInOrder() throws Exception {
        Entry first = entry(HOME, "1", "a");
        Entry second = entry(WORK, "2", "b");
        Entry copy = entry(CLUB, "3", "a");

        assertEquals(
                List.of(vcard("1"), vcard("2")),
                ContactMerge.documents(List.of(first, second, copy)));
    }

    @Test
    public void checkedPrefersAPendingStateOverMembership() throws Exception {
        List<BookEntry> books = List.of(listed(HOME), listed(WORK), listed(CLUB));
        List<Entry> replicas = List.of(entry(HOME, "1", "a"), entry(WORK, "2", "a"));

        assertArrayEquals(
                new boolean[] {true, true, false}, ContactMerge.checked(books, replicas, null));
        assertArrayEquals(
                new boolean[] {true, false, true},
                ContactMerge.checked(
                        books, replicas, Map.of(WORK.url, false, CLUB.url, true)));
    }

    @Test
    public void pendingPutsTheOnDeviceBookOnExactlyWhenNothingIsTicked() {
        List<BookEntry> books = List.of(listed(HOME), listed(WORK));

        Map<String, Boolean> none = ContactMerge.pending(books, new boolean[] {false, false});
        assertEquals(Map.of(HOME.url, false, WORK.url, false, LocalBook.URL, true), none);

        Map<String, Boolean> one = ContactMerge.pending(books, new boolean[] {false, true});
        assertEquals(Map.of(HOME.url, false, WORK.url, true, LocalBook.URL, false), one);
    }

    private static Addressbook book(String name) {
        return new Addressbook(name, name, "https://dav.example/" + name + "/", null, null);
    }

    private static BookEntry listed(Addressbook book) {
        return new BookEntry(book, "jane@example.org", true, true, false);
    }

    private static Entry entry(Addressbook book, String id, String hash) throws Exception {
        Card card = new Card(id, null, null, vcard(id));
        JSONObject index = new JSONObject().put("hash", hash);
        return new Entry(book, "jane@example.org", new PimdirContacts.Indexed(card, index, false));
    }

    private static String vcard(String id) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + id + "\r\nEND:VCARD\r\n";
    }
}
