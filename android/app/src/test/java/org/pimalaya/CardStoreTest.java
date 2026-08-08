package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Addressbook;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * The app's own state about its address books, now that the books themselves
 * are collections in the pimdir store.
 *
 * <p>What is worth pinning here is the seam: a book is listed because the store
 * holds the collection, and it is displayed the way it is because this database
 * holds the switches, so the two have to meet correctly even when one of them
 * has never heard of the other.
 */
@RunWith(RobolectricTestRunner.class)
public class CardStoreTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BOOK = "https://dav.example.com/books/b1/";

    private Context context;
    private PimdirDb pimdir;
    private CardStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new CardStore(context, pimdir);
        roster(new Addressbook("b1", "Book One", BOOK, null, null));
    }

    /** Writes a roster to both halves, the way every caller does. */
    private void roster(Addressbook... books) {
        List<Addressbook> listed = List.of(books);
        store.replaceAddressbooks(EMAIL, listed);
        new PimdirCollections(pimdir, context).replace(
                EMAIL, PimdirMeta.CONTACT, PimdirCollections.of(EMAIL, listed));
    }

    private BookEntry only() {
        List<BookEntry> books = store.loadAllAddressbooks();
        assertEquals(1, books.size());
        return books.get(0);
    }

    @Test
    public void aBookIsTheCollectionPlusTheSwitchesHeldForIt() {
        BookEntry entry = only();

        // Display fields come from the store, so there is one copy of them.
        assertEquals("Book One", entry.book.name);
        assertEquals(BOOK, entry.book.url);
        assertEquals("b1", entry.book.id);
        assertEquals(EMAIL, entry.accountEmail);

        // Switches come from here, and a new book is subscribed and
        // remote-synced with the phone spoke off.
        assertTrue(entry.subscribed);
        assertTrue(entry.remoteSynced);
        assertFalse(entry.phoneSynced);
    }

    @Test
    public void aRefreshKeepsTheSwitchesTheUserSet() {
        store.setBookState(BOOK, true, false, true);

        // A re-listing must never silently turn a spoke the user chose back on.
        roster(new Addressbook("b1", "Book One renamed", BOOK, null, null));

        BookEntry entry = only();
        assertEquals("the display name follows the server", "Book One renamed", entry.book.name);
        assertTrue(entry.subscribed);
        assertFalse("the user turned remote sync off", entry.remoteSynced);
        assertTrue(entry.phoneSynced);
    }

    @Test
    public void unsubscribingForcesBothSpokesOff() {
        // Neither spoke means anything without the subscription, so leaving one
        // on would schedule syncs for a book that is not displayed.
        store.setBookState(BOOK, false, true, true);

        BookEntry entry = only();
        assertFalse(entry.subscribed);
        assertFalse(entry.remoteSynced);
        assertFalse(entry.phoneSynced);
        assertTrue("and it is gone from the home listing",
                store.loadSubscribedAddressbooks().isEmpty());
    }

    @Test
    public void aCollectionWithNoSwitchRowTakesTheDefaults() {
        // The store learned about a book this database has not seen: hiding it
        // would lose a whole address book to a bookkeeping gap.
        new PimdirCollections(pimdir, context)
                .ensure("https://dav.example.com/books/b2/", EMAIL, PimdirMeta.CONTACT, "Book Two");

        List<BookEntry> books = store.loadAllAddressbooks();
        assertEquals(2, books.size());
        for (BookEntry entry : books) {
            assertTrue(entry.subscribed);
        }
    }

    @Test
    public void aVanishedBookLeavesNothingBehind() {
        roster();

        assertTrue(store.loadAllAddressbooks().isEmpty());
        assertEquals(0, scalar("SELECT count(*) FROM collections"));
    }

    @Test
    public void forgettingAnAccountDropsItsSwitchesAndItsLinkExceptions() {
        store.unlinkGroup("g1", List.of("g1"), List.of(CardStore.replicaRef(EMAIL, "k1")));
        assertEquals(1, store.loadDetached().size());

        store.forgetAccount(EMAIL);

        assertTrue(store.loadDetached().isEmpty());
        assertEquals(0, scalar("SELECT count(*) FROM addressbook", store.getReadableDatabase()));
    }

    @Test
    public void theUpgradeCarriesTheSwitchesAndDropsWhatMoved() {
        SQLiteDatabase db = store.getWritableDatabase();
        legacySchema(db);

        store.onUpgrade(db, 2, 3);

        // The cards and their memberships are the pimdir store's now, and the
        // next sync refills it; the switches are what no sync can re-derive.
        assertFalse(table(db, "card"));
        assertFalse(table(db, "membership"));
        assertFalse("the carry scaffolding is gone", table(db, "addressbook_carry"));

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT account_email, subscribed, remote_synced, phone_synced"
                                + " FROM addressbook WHERE url = ?",
                        new String[] {BOOK})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(EMAIL, cursor.getString(0));
            assertEquals(1, cursor.getInt(1));
            assertEquals(0, cursor.getInt(2));
            assertEquals(1, cursor.getInt(3));
        }
    }

    /** The version 2 shape, enough of it for the carry to have something to do. */
    private void legacySchema(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS addressbook");
        db.execSQL(
                "CREATE TABLE addressbook (url TEXT PRIMARY KEY, account_email TEXT NOT NULL,"
                        + " id TEXT NOT NULL, name TEXT NOT NULL, description TEXT, color TEXT,"
                        + " subscribed INTEGER NOT NULL DEFAULT 1,"
                        + " remote_synced INTEGER NOT NULL DEFAULT 1,"
                        + " phone_synced INTEGER NOT NULL DEFAULT 0, checkpoint TEXT)");
        db.execSQL(
                "INSERT INTO addressbook(url, account_email, id, name, subscribed,"
                        + " remote_synced, phone_synced, checkpoint)"
                        + " VALUES(?, ?, 'b1', 'Book One', 1, 0, 1, 'sync-token-1')",
                new Object[] {BOOK, EMAIL});
        db.execSQL("CREATE TABLE card (account_email TEXT, key TEXT, vcard TEXT)");
        db.execSQL("CREATE TABLE membership (account_email TEXT, card_key TEXT)");
    }

    private static boolean table(SQLiteDatabase db, String name) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                        new String[] {name})) {
            return cursor.moveToFirst();
        }
    }

    private long scalar(String sql) {
        return scalar(sql, pimdir.getReadableDatabase());
    }

    private long scalar(String sql, SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }
}
