package org.pimalaya.contact;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Addressbook;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirHash;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;

/**
 * The app's own state about the contacts, beside the pimdir store.
 *
 * <p>App policy pimdir has no column for: each book's three switches, account
 * and backend id, the merged view's link exceptions, the ignored duplicates.
 * Display fields stay the store's ({@link #loadAllAddressbooks} joins them).
 */
public class CardStore extends SQLiteOpenHelper {
    private static final String DATABASE = "cards.db";

    // NOTE: version 3 is where the cards themselves left for the pimdir store;
    // an upgrade drops what moved and keeps the switches and the link layer,
    // neither of which any sync can re-derive.
    private static final int VERSION = 3;

    private final PimdirCollections collections;

    /** Opens the app's contact state database beside the pimdir store. */
    public CardStore(Context context, PimdirDb pimdir) {
        super(context, DATABASE, null, VERSION);
        this.collections = new PimdirCollections(pimdir, context);
    }

    /**
     * The storage key of a card: collection-scoped on CardDAV and Graph.
     *
     * <p>Resource ids are unique per collection there; JMAP and Google use the
     * bare server id, unique across the account.
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
     * Links group keys into one cluster, their rows becoming one contact.
     *
     * <p>NOTE: unwired (linking goes through a shared UID); kept with
     * {@link #unlinkGroup} for docs/merged-view.md's match suggestions, whose
     * cluster computation then belongs in the bridge.
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

    /** Splits a merged contact: drops its links and detaches every replica. */
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
     * Replaces one account's books' switches; new ones subscribed, remote on.
     *
     * <p>{@link PimdirCollections} writes the books. A book matches by URL,
     * else by id within the account (an ambiguous id is skipped): that keeps
     * the switches across a URL scheme change, like JMAP's added account id.
     */
    public void replaceAddressbooks(String accountEmail, List<Addressbook> books) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Map<String, int[]> byUrl = new HashMap<>();
            Map<String, int[]> byId = new HashMap<>();
            Set<String> ambiguous = new java.util.HashSet<>();
            try (Cursor cursor =
                    db.query(
                            "addressbook",
                            new String[] {
                                "url", "id", "subscribed", "remote_synced", "phone_synced"
                            },
                            "account_email = ?",
                            new String[] {accountEmail},
                            null,
                            null,
                            null)) {
                while (cursor.moveToNext()) {
                    int[] switches =
                            new int[] {cursor.getInt(2), cursor.getInt(3), cursor.getInt(4)};
                    byUrl.put(cursor.getString(0), switches);
                    String id = cursor.getString(1);
                    if (byId.put(id, switches) != null) {
                        ambiguous.add(id);
                    }
                }
            }
            byId.keySet().removeAll(ambiguous);

            db.delete("addressbook", "account_email = ?", new String[] {accountEmail});

            for (Addressbook book : books) {
                ContentValues values = new ContentValues();
                values.put("url", book.url());
                values.put("account_email", accountEmail);
                values.put("id", book.id());
                int[] switches = byUrl.get(book.url());
                if (switches == null) {
                    switches = byId.get(book.id());
                }
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
     * Sets one book's three switches; both sync switches need the subscription.
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
     * Seeds the built-in local book, present and subscribed; kept if it exists.
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

    /** Every subscribed book, by account then name (the home listing). */
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
     * The subscribed books as the store holds them, the on-device one included.
     */
    public List<PimdirCollections.Stored> subscribedBooks(PimdirCollections collections) {
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (BookEntry book : loadSubscribedAddressbooks()) {
            subscribed.add(book.book.url());
        }
        List<PimdirCollections.Stored> books = new ArrayList<>();
        for (PimdirCollections.Stored book : collections.list(PimdirSummary.CONTACT)) {
            if (subscribed.contains(book.id)) {
                books.add(book);
            }
        }
        return books;
    }

    /**
     * Every known book with its switches, for the subscription editor.
     *
     * <p>A collection with no switch row takes a new book's defaults.
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
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.CONTACT)) {
            int[] state = switches.get(stored.id);
            String id = ids.get(stored.id);
            books.add(
                    new BookEntry(
                            new Addressbook(
                                    id == null ? stored.id : id,
                                    stored.name,
                                    stored.id,
                                    stored.description,
                                    stored.color,
                                    stored.role == null ? "" : stored.role,
                                    stored.writable),
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
     * The name a create offers: {@code id.vcf} on CardDAV, else the bare id.
     */
    static String resourceName(String url, String id) {
        return isCarddavUrl(url) ? id + ".vcf" : id;
    }

    /** True for the backends whose cards are account-level (JMAP, Google). */
    static boolean isAccountLevelUrl(String url) {
        return AccountClient.isAccountLevel(url);
    }

    /** True for plain CardDAV collections. */
    static boolean isCarddavUrl(String url) {
        return AccountClient.isCarddav(url);
    }
}
