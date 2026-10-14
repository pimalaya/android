package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.pimalaya.client.Card;
import org.pimalaya.contact.BookEntry;
import org.pimalaya.contact.PimdirContacts;
import org.robolectric.ParameterizedRobolectricTestRunner;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * An address book listed, a contact created, edited and deleted on the phone.
 */
public class E2eContactsTest extends E2eCase {
    public E2eContactsTest(String target) {
        super(target);
    }

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> targets() {
        return E2eTarget.parameters(E2eTarget.CONTACTS);
    }

    @Test
    @E2eScenario({
        "carddav-sync: A CardDAV collection is enumerated whatever the server implements"
                + " / A server with no sync-collection",
        "offline-store: A local action is visible before any sync / A contact saved offline",
        "offline-store: An action is a local write / An entry edited with the radio off",
        "offline-store: A staged removal is a tombstone the load hands back / The delete is derived",
    })
    public void aContactRoundTripsThroughTheAddressBook() throws Exception {
        String name = scratch();
        items.seed(name, subject("seeded"));
        // NOTE: Google's contact list settles late behind its API.
        settles(
                "the server lists the seeded contact",
                () -> items.titles(name, tag).contains(subject("seeded")) ? null : "not yet");

        connected();
        BookEntry book = phone.book(address, bookName(name));
        assertNotNull("an address book listed", book);
        String url = book.book.url();
        settles(
                "the seeded contact synced",
                () -> {
                    sync(phone);
                    return phone.cards(url, subject("seeded")).size() == 1 ? null : "not yet";
                });

        // NOTE: as the contact form saves a new one.
        String uid = UUID.randomUUID().toString();
        phone.contacts().save(url, new Card(uid, null, null, vcard(uid, subject("created"))));
        List<PimdirContacts.Indexed> staged = phone.cards(url, subject("created"));
        assertEquals("shown at once", 1, staged.size());
        assertTrue("pending", phone.owes(url, staged.get(0).card.id()));

        sync(phone);
        settles(
                "the server holds the created contact",
                () -> {
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("created")) ? null : "titles " + titles;
                });

        Card created = phone.cards(url, subject("created")).get(0).card;
        String edited =
                created.vcard().replace(subject("created"), subject("edited"));
        phone.contacts().save(url, new Card(created.id(), created.uri(), created.etag(), edited));
        assertEquals("edited at once", 1, phone.cards(url, subject("edited")).size());
        // NOTE: synced again while waiting: a push the server refused stays
        // staged for the pass that follows.
        settles(
                "the server holds the edit",
                () -> {
                    sync(phone);
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("edited")) && !titles.contains(subject("created"))
                            ? null
                            : "server " + titles + ", phone " + names(phone, url);
                });

        E2eDevice observer = observer();
        BookEntry seen = observer.book(address, book.book.name());
        assertNotNull("another device lists the book", seen);
        settles(
                "another device reads the edit",
                () -> {
                    sync(observer);
                    return observer.cards(seen.book.url(), subject("edited")).size() == 1
                            ? null
                            : "not yet";
                });

        Card toDelete = phone.cards(url, subject("edited")).get(0).card;
        phone.contacts().stageDelete(url, toDelete.id());
        assertTrue("gone at once", phone.cards(url, subject("edited")).isEmpty());
        settles(
                "the server holds it no more",
                () -> {
                    sync(phone);
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("edited"))
                            ? "server " + titles + ", phone " + names(phone, url)
                            : null;
                });
    }

    @Test
    @E2eScenario({
        "offline-store: A staged removal is a tombstone the load hands back / The row is not resurrected",
    })
    public void aContactWrittenElsewhereIsDeleted() throws Exception {
        String name = scratch();
        items.seed(name, subject("elsewhere"));
        settles(
                "the server lists the seeded contact",
                () -> items.titles(name, tag).contains(subject("elsewhere")) ? null : "not yet");

        connected();
        String url = phone.book(address, bookName(name)).book.url();
        settles(
                "the seeded contact synced",
                () -> {
                    sync(phone);
                    return phone.cards(url, subject("elsewhere")).size() == 1 ? null : "not yet";
                });
        Card seeded = phone.cards(url, subject("elsewhere")).get(0).card;
        phone.contacts().stageDelete(url, seeded.id());
        assertTrue("gone at once", phone.cards(url, subject("elsewhere")).isEmpty());
        settles(
                "the server holds it no more",
                () -> {
                    sync(phone);
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("elsewhere"))
                            ? "server " + titles + ", phone " + names(phone, url)
                            : null;
                });
    }

    @Test
    @E2eScenario({
        "offline-store: A staged removal is a tombstone the load hands back / The row is not resurrected",
    })
    public void aContactCreatedOnThePhoneIsDeleted() throws Exception {
        String name = scratch();
        connected();
        String url = phone.book(address, bookName(name)).book.url();
        String uid = UUID.randomUUID().toString();
        phone.contacts().save(url, new Card(uid, null, null, vcard(uid, subject("created"))));
        settles(
                "the server holds the created contact",
                () -> {
                    sync(phone);
                    return items.titles(name, tag).contains(subject("created")) ? null : "not yet";
                });
        Card created = phone.cards(url, subject("created")).get(0).card;
        phone.contacts().stageDelete(url, created.id());
        assertTrue("gone at once", phone.cards(url, subject("created")).isEmpty());
        settles(
                "the server holds it no more",
                () -> {
                    sync(phone);
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("created"))
                            ? "server " + titles + ", phone " + names(phone, url)
                            : null;
                });
    }

    /**
     * The book a test works in: the scratch one, or Google's first listed.
     *
     * <p>Google's contacts are one fixed list.
     */
    private String bookName(String scratch) {
        return target.id.equals("google-carddav") ? null : scratch;
    }

    /** What a device's book holds under the tag, staged ones marked. */
    private String names(E2eDevice device, String url) {
        List<String> names = new java.util.ArrayList<>();
        for (PimdirContacts.Indexed indexed : device.cards(url, tag)) {
            names.add(indexed.name + (device.owes(url, indexed.card.id()) ? " (staged)" : ""));
        }
        return names.toString();
    }

    /** One contacts pass on a device, which must not fail. */
    private void sync(E2eDevice device) {
        Exception failure = device.syncContacts(address);
        if (failure != null) {
            throw new AssertionError("the pass failed: " + failure.getMessage(), failure);
        }
    }

    /** A card as the contact form writes a new one: vCard 3.0, a name. */
    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + uid + "\r\nFN:" + name + "\r\nN:;" + name
                + ";;;\r\nEND:VCARD\r\n";
    }
}
