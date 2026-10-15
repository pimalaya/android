package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;
import org.pimalaya.client.ContactClient;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Pins the merged view's grouping end to end.
 *
 * <p>Bridge-indexed replicas, link and detached rows from the card store, the
 * groups holding the very entries given, in pool order.
 */
@RunWith(RobolectricTestRunner.class)
public class ContactPoolTest {
    private static final Addressbook HOME =
            new Addressbook("home", "Home", "https://dav.example/home/", null, null);
    private static final Addressbook WORK =
            new Addressbook("work", "Work", "https://dav.example/work/", null, null);

    private CardStore store;
    private ContactPool pool;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        store = new CardStore(context, new PimdirDb(context));
        pool = new ContactPool(store, null, List.of());
    }

    @Test
    public void replicasGroupByUidLinkAndDetachIntoTheirOwnEntries() {
        List<Entry> entries =
                List.of(
                        entry(HOME, "a@x", "1", "UID:u1", "FN:Zed"),
                        entry(HOME, "a@x", "2", "FN:alpha"),
                        entry(WORK, "b@x", "3", "UID:u1", "FN:Zed too"),
                        entry(WORK, "b@x", "4", "UID:u4"),
                        entry(HOME, "c@x", "5", "UID:u5", "FN:Mid"),
                        entry(WORK, "c@x", "6", "UID:u1", "FN:Detached"));
        link("uid\u0000u4", "uid\u0000u4");
        link("uid\u0000u5", "uid\u0000u4");
        detach(pool.replicaRef(entries.get(5)));

        List<Group> groups = pool.group(entries, List.of());

        List<String> keys = new ArrayList<>();
        for (Group group : groups) {
            keys.add(group.key);
        }
        assertEquals(
                List.of(
                        "uid\u0000u4",
                        "ref\u0000a@x\nhttps://dav.example/home/\u00002",
                        "ref\u0000c@x\nhttps://dav.example/work/\u00006",
                        "uid\u0000u1"),
                keys);
        assertMembers(groups.get(0), entries, 3, 4);
        assertMembers(groups.get(1), entries, 1);
        assertMembers(groups.get(2), entries, 5);
        assertMembers(groups.get(3), entries, 0, 2);
    }

    @Test
    public void anEmptyPoolGroupsIntoNothing() {
        assertEquals(List.of(), pool.group(List.of(), List.of()));
    }

    /** The group holds exactly the given entries, same objects, in order. */
    private static void assertMembers(Group group, List<Entry> entries, int... indexes) {
        assertEquals(indexes.length, group.replicas.size());
        for (int at = 0; at < indexes.length; at++) {
            assertSame(entries.get(indexes[at]), group.replicas.get(at));
        }
    }

    /** Files a link row: {@code member}'s group joins {@code cluster}. */
    private void link(String member, String cluster) {
        store.getWritableDatabase()
                .execSQL(
                        "INSERT INTO link(member, cluster) VALUES(?, ?)",
                        new Object[] {member, cluster});
    }

    /** Files a detached row for one replica, out of its UID group. */
    private void detach(String replicaRef) {
        int newline = replicaRef.indexOf('\n');
        store.getWritableDatabase()
                .execSQL(
                        "INSERT INTO detached(account_email, card_key) VALUES(?, ?)",
                        new Object[] {
                            replicaRef.substring(0, newline), replicaRef.substring(newline + 1)
                        });
    }

    private static Entry entry(Addressbook book, String email, String id, String... lines) {
        StringBuilder vcard = new StringBuilder("BEGIN:VCARD\r\nVERSION:4.0\r\n");
        for (String line : lines) {
            vcard.append(line).append("\r\n");
        }
        vcard.append("END:VCARD\r\n");
        Card card = new Card(id, null, null, vcard.toString());
        return new Entry(
                book,
                email,
                new PimdirContacts.Indexed(card, ContactClient.indexCard(card.vcard()), false));
    }
}
