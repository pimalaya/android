package org.pimalaya;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.PimalayaClient;

/**
 * The app's own state about the contacts it stores, beside the store itself.
 *
 * <p>The contacts live in the pimdir store ({@link PimdirContacts}); what is
 * here is everything pimdir deliberately has no column for, because it is this
 * app's policy rather than a property of the data:
 *
 * <ul>
 *   <li>an address book's three switches: {@code subscribed} drives what is
 *       displayed, {@code remote_synced} and {@code phone_synced} the two sync
 *       spokes. Plus the account it belongs to and its id on that backend,
 *       which the store has no room for either (its {@code account} column is a
 *       stable id, not an address).
 *   <li>the merged view's link exceptions (docs/merged-view.md): a replica
 *       detached from automatic grouping, and group keys joined into a cluster.
 *       Nothing on any server and nothing in a sync depends on them.
 *   <li>the duplicate groups the user chose to leave alone.
 * </ul>
 *
 * <p>The display fields of a book (name, description, colour) are <em>not</em>
 * here: they are the collection's, they live in the store, and holding a second
 * copy would mean two places to disagree. {@link #loadAllAddressbooks} joins the
 * two in memory instead, over the handful of rows a device has.
 */
public class CardStore extends SQLiteOpenHelper {
    private static final String DATABASE = "cards.db";

    // NOTE: version 3 is where the cards themselves left for the pimdir store;
    // an upgrade drops what moved and keeps the switches and the link layer,
    // neither of which any sync can re-derive.
    private static final int VERSION = 3;

    private final PimdirCollections collections;

    public CardStore(Context context, PimdirDb pimdir) {
        super(context, DATABASE, null, VERSION);
        this.collections = new PimdirCollections(pimdir, context);
    }

    /**
     * The storage key of a card that lives in one collection (CardDAV,
     * Graph): resource ids are only unique per collection there, so the
     * collection is part of the identity. Account-level backends (JMAP,
     * Google) use the bare server id, unique across the account.
     */
    public static String key(String addressbookUrl, String id) {
        return addressbookUrl + "\u0000" + id;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS addressbook ("
                        + "url TEXT PRIMARY KEY, "
                        + "account_email TEXT NOT NULL, "
                        + "id TEXT NOT NULL, "
                        + "subscribed INTEGER NOT NULL DEFAULT 1, "
                        + "remote_synced INTEGER NOT NULL DEFAULT 1, "
                        + "phone_synced INTEGER NOT NULL DEFAULT 0)");
        // NOTE: view-layer link exceptions of the merged list
        // (docs/merged-view.md); nothing on any server or in sync
        // depends on them. detached = a replica excluded from automatic
        // grouping, link = group keys joined into a shared cluster.
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS detached ("
                        + "account_email TEXT NOT NULL, "
                        + "card_key TEXT NOT NULL, "
                        + "PRIMARY KEY (account_email, card_key))");
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS link ("
                        + "member TEXT PRIMARY KEY, "
                        + "cluster TEXT NOT NULL)");
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS dismissed_duplicate ("
                        + "group_key TEXT PRIMARY KEY)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NOTE: the cards, their memberships and the books' display fields all
        // moved to the pimdir store. The switches are what cannot be
        // re-derived, so they are carried over and everything else is dropped;
        // the next sync refills the store from the servers.
        db.execSQL("DROP TABLE IF EXISTS card");
        db.execSQL("DROP TABLE IF EXISTS membership");

        Set<String> columns = new java.util.HashSet<>(columnsOf(db, "addressbook"));
        if (columns.contains("name")) {
            db.execSQL("DROP TABLE IF EXISTS addressbook_carry");
            db.execSQL("ALTER TABLE addressbook RENAME TO addressbook_carry");
            onCreate(db);
            db.execSQL(
                    "INSERT INTO addressbook (url, account_email, id, subscribed,"
                            + " remote_synced, phone_synced) SELECT url, account_email, id,"
                            + " subscribed, remote_synced, phone_synced FROM addressbook_carry");
            db.execSQL("DROP TABLE addressbook_carry");
            return;
        }
        onCreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NOTE: the default helper throws on downgrade and strands the
        // app; rebuild on this build's schema like an upgrade instead.
        onUpgrade(db, oldVersion, newVersion);
    }

    /** A table's column names, per PRAGMA table_info. */
    private static List<String> columnsOf(SQLiteDatabase db, String table) {
        List<String> columns = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
            }
        }
        return columns;
    }

    /** A replica reference for the link layer (emails hold no newline). */
    public static String replicaRef(String accountEmail, String key) {
        return accountEmail + "\n" + key;
    }

    /** Whether the duplicate group was already dismissed. */
    public boolean isDuplicateDismissed(String groupKey) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "dismissed_duplicate",
                        new String[] {"group_key"},
                        "group_key = ?",
                        new String[] {groupKey},
                        null,
                        null,
                        null)) {
            return cursor.moveToFirst();
        }
    }

    /** Remembers a duplicate group the user chose to leave alone. */
    public void dismissDuplicate(String groupKey) {
        ContentValues values = new ContentValues();
        values.put("group_key", groupKey);
        getWritableDatabase()
                .insertWithOnConflict(
                        "dismissed_duplicate", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** The replicas detached from automatic (UID) grouping, as refs. */
    public Set<String> loadDetached() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "detached",
                        new String[] {"account_email", "card_key"},
                        null,
                        null,
                        null,
                        null,
                        null)) {
            Set<String> refs = new java.util.HashSet<>(cursor.getCount());
            while (cursor.moveToNext()) {
                refs.add(replicaRef(cursor.getString(0), cursor.getString(1)));
            }
            return refs;
        }
    }

    /** The link rows: group key to the cluster it belongs to. */
    public Map<String, String> loadLinks() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "link",
                        new String[] {"member", "cluster"},
                        null,
                        null,
                        null,
                        null,
                        null)) {
            Map<String, String> links = new HashMap<>(cursor.getCount());
            while (cursor.moveToNext()) {
                links.put(cursor.getString(0), cursor.getString(1));
            }
            return links;
        }
    }

    /**
     * Links the given group keys into one cluster (merging any cluster
     * a key already belongs to), so their rows collapse into one
     * merged contact.
     *
     * <p>NOTE: unwired today (linking goes through a shared UID);
     * kept, with {@link #unlinkGroup}, for the match-suggestion flows
     * docs/merged-view.md reserves the link and detached tables for.
     * When they come back, the cluster computation belongs in the
     * bridge next to groupContacts.
     */
    public void linkGroups(List<String> groupKeys) {
        if (groupKeys.size() < 2) {
            return;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String cluster = groupKeys.get(0);
            for (String key : groupKeys) {
                ContentValues rebind = new ContentValues();
                rebind.put("cluster", cluster);
                db.update("link", rebind, "cluster = ?", new String[] {key});

                ContentValues values = new ContentValues();
                values.put("member", key);
                values.put("cluster", cluster);
                db.insertWithOnConflict("link", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Splits a merged contact apart: drops the cluster's link rows and
     * detaches every replica, so each becomes its own row until it is
     * explicitly linked again.
     */
    public void unlinkGroup(String clusterKey, List<String> members, List<String> replicaRefs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("link", "cluster = ? OR member = ?", new String[] {clusterKey, clusterKey});
            for (String member : members) {
                db.delete("link", "member = ?", new String[] {member});
            }

            for (String ref : replicaRefs) {
                int newline = ref.indexOf('\n');
                ContentValues values = new ContentValues();
                values.put("account_email", ref.substring(0, newline));
                values.put("card_key", ref.substring(newline + 1));
                db.insertWithOnConflict(
                        "detached", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Replaces one account's address books with the fetched set, keeping the
     * switches of any book already known; new ones default to subscribed with
     * remote sync on.
     *
     * <p>Only the switches: the books themselves are collections in the store
     * and {@link PimdirCollections} is what writes them, so a book that
     * vanished server-side loses its contents there rather than here.
     */
    public void replaceAddressbooks(String accountEmail, List<Addressbook> books) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Map<String, int[]> wasSwitched = new HashMap<>();
            try (Cursor cursor =
                    db.query(
                            "addressbook",
                            new String[] {"url", "subscribed", "remote_synced", "phone_synced"},
                            "account_email = ?",
                            new String[] {accountEmail},
                            null,
                            null,
                            null)) {
                while (cursor.moveToNext()) {
                    wasSwitched.put(
                            cursor.getString(0),
                            new int[] {cursor.getInt(1), cursor.getInt(2), cursor.getInt(3)});
                }
            }

            db.delete("addressbook", "account_email = ?", new String[] {accountEmail});

            for (Addressbook book : books) {
                ContentValues values = new ContentValues();
                values.put("url", book.url);
                values.put("account_email", accountEmail);
                values.put("id", book.id);
                int[] switches = wasSwitched.get(book.url);
                values.put("subscribed", switches == null ? 1 : switches[0]);
                values.put("remote_synced", switches == null ? 1 : switches[1]);
                values.put("phone_synced", switches == null ? 0 : switches[2]);
                db.insert("addressbook", null, values);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Sets one addressbook's three switches. Both sync switches require
     * the subscription, so they are forced off whenever the book is not
     * subscribed.
     */
    public void setBookState(
            String url, boolean subscribed, boolean remoteSynced, boolean phoneSynced) {
        boolean remote = subscribed && remoteSynced;
        boolean phone = subscribed && phoneSynced;

        ContentValues values = new ContentValues();
        values.put("subscribed", subscribed ? 1 : 0);
        values.put("remote_synced", remote ? 1 : 0);
        values.put("phone_synced", phone ? 1 : 0);
        getWritableDatabase().update("addressbook", values, "url = ?", new String[] {url});
    }

    /** Forgets an account's books; its contacts are the store's business. */
    public void forgetAccount(String accountEmail) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("addressbook", "account_email = ?", new String[] {accountEmail});
            db.delete("detached", "account_email = ?", new String[] {accountEmail});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Seeds the built-in local addressbook, so a fresh install or a
     * schema rebuild always finds it present and subscribed. Ignored
     * when the row already exists, keeping any cards and subscription.
     */
    public void ensureLocalAddressbook(String url, String accountEmail, String id) {
        ContentValues values = new ContentValues();
        values.put("url", url);
        values.put("account_email", accountEmail);
        values.put("id", id);
        values.put("subscribed", 1);
        getWritableDatabase()
                .insertWithOnConflict("addressbook", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Every subscribed addressbook, ordered by account then name (drives the home listing). */
    public List<BookEntry> loadSubscribedAddressbooks() {
        List<BookEntry> subscribed = new ArrayList<>();
        for (BookEntry entry : loadAllAddressbooks()) {
            if (entry.subscribed) {
                subscribed.add(entry);
            }
        }
        return subscribed;
    }

    /**
     * Every known addressbook, subscribed or not (drives the subscription
     * editor): the store's contact collections, each carrying the switches this
     * database holds for it.
     *
     * <p>A collection with no switch row is one the store learned about before
     * this database did, so it takes the same defaults a new book does rather
     * than being hidden.
     */
    public List<BookEntry> loadAllAddressbooks() {
        Map<String, int[]> switches = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                "addressbook",
                                new String[] {
                                    "url", "id", "subscribed", "remote_synced", "phone_synced"
                                },
                                null,
                                null,
                                null,
                                null,
                                null)) {
            while (cursor.moveToNext()) {
                switches.put(
                        cursor.getString(0),
                        new int[] {cursor.getInt(2), cursor.getInt(3), cursor.getInt(4)});
                ids.put(cursor.getString(0), cursor.getString(1));
            }
        }

        List<BookEntry> books = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirMeta.CONTACT)) {
            int[] state = switches.get(stored.id);
            String id = ids.get(stored.id);
            books.add(
                    new BookEntry(
                            new Addressbook(
                                    id == null ? stored.id : id,
                                    stored.name,
                                    stored.id,
                                    stored.description,
                                    stored.color),
                            stored.accountEmail,
                            state == null || state[0] == 1,
                            state == null || state[1] == 1,
                            state != null && state[2] == 1));
        }
        return books;
    }

    // NOTE: the phone-spoke naming belongs to the storage seam, which is
    // where it now lives; these stay as the callers' shorthand until the
    // callers themselves move off this class.

    /** The phone collection id of an addressbook (the second engine spoke). */
    public static String phoneCollection(String url) {
        return PimdirStorage.phoneCollection(url);
    }

    /** True when the collection id addresses the phone spoke. */
    public static boolean isPhoneCollection(String collection) {
        return PimdirStorage.isPhoneCollection(collection);
    }

    /** The addressbook URL behind a collection id, phone or server. */
    public static String serverUrl(String collection) {
        return PimdirStorage.collectionOf(collection);
    }

    /** The byte-exact content hash naming a body in the object store. */
    public static String byteHash(String text) {
        return PimdirHash.of(text);
    }

    /**
     * A row's engine handle: its resource name, reconstructed for
     * never-pushed rows (CardDAV creations name the resource id.vcf;
     * the other backends address the bare id).
     */
    static String rowHandle(String url, String uri, String id) {
        if (uri != null && !uri.isEmpty()) {
            return uri;
        }
        return isCarddavUrl(url) ? id + ".vcf" : id;
    }

    /** True for the backends whose cards are account-level (JMAP, Google). */
    static boolean isAccountLevelUrl(String url) {
        return PimalayaClient.isAccountLevel(url);
    }

    /** True for plain CardDAV collections. */
    static boolean isCarddavUrl(String url) {
        return PimalayaClient.isCarddav(url);
    }
}
