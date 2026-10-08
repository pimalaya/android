package org.pimalaya;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.PimdirSql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the store keeps of deleted items (pimdir STORAGE §11): the rows a
 * reader lists, a restore staging one back, and the purge freeing the space.
 *
 * <p>The app runs io-pimdir without its {@code client} feature, so the store's
 * {@code list_retained}, {@code retained_bytes}, {@code purge_retained_before}
 * and {@code collect_garbage} are not callable here: this is the same reads
 * and deletes through their canonical statements ({@link PimdirSql}), on the
 * terms io-pimdir runs them.
 *
 * <p>A deleted row is one of two: retained, its last binding gone and its body
 * kept until a purge, or still bound by a source the delete has not reached
 * yet (no {@code retained_at}), which waits for the server and is neither
 * restored nor purged.
 */
final class DeletedItems {
    /** Rows one keyset read takes of a collection. */
    static final int PAGE = 256;

    /** One deleted item, as the page lists it. */
    static final class Row {
        final PimdirCollections.Stored collection;
        final String kind;
        final long seq;
        final String linkId;
        final String flags;
        final String object;
        final String sortKey;
        final boolean full;

        /** When the store retained it (RFC 3339), null while a source binds it. */
        final String retainedAt;

        /** What its body weighs, 0 for none. */
        final long size;

        /** The summary's headline: subject, name or title, empty for none. */
        String title = "";

        /** The summary's second fact: sender, organization, empty for none. */
        String who = "";

        /** The summary's date: a mail's (RFC 3339) or an event's start, empty for none. */
        String when = "";

        Row(
                PimdirCollections.Stored collection,
                String kind,
                long seq,
                String linkId,
                String flags,
                String object,
                String sortKey,
                boolean full,
                String retainedAt,
                long size) {
            this.collection = collection;
            this.kind = kind;
            this.seq = seq;
            this.linkId = linkId;
            this.flags = flags;
            this.object = object;
            this.sortKey = sortKey;
            this.full = full;
            this.retainedAt = retainedAt;
            this.size = size;
        }

        /** Whether a source still binds it: the server delete is not carried out yet. */
        boolean waiting() {
            return retainedAt == null;
        }

        /** Whether the store holds its body, which a restore stages. */
        boolean stored() {
            return full && object != null;
        }
    }

    /** Newest deletion first, the rows still waiting ahead of them. */
    static final Comparator<Row> NEWEST_FIRST =
            Comparator.comparing(
                            (Row row) -> row.retainedAt,
                            Comparator.nullsFirst(Comparator.<String>reverseOrder()))
                    .thenComparing(row -> row.seq, Comparator.reverseOrder());

    private final PimdirDb store;
    private final Context context;
    private final PimdirItems items;

    DeletedItems(PimdirDb store, Context context) {
        this.store = store;
        this.context = context;
        this.items = new PimdirItems(store);
    }

    // ---- reads ------------------------------------------------------------

    /**
     * Every deleted item of every mailbox, address book and calendar, newest
     * deletion first.
     *
     * <p>io-pimdir lists the trash per collection in {@code seq} order, so the
     * walk reads each collection a keyset page at a time and orders the whole
     * at the end: a deletion's time is not what the store's index serves.
     */
    List<Row> list() {
        SQLiteDatabase db = store.getReadableDatabase();
        PimdirCollections collections = new PimdirCollections(store, context);
        List<Row> rows = new ArrayList<>();
        for (String kind :
                new String[] {PimdirSummary.MAIL, PimdirSummary.CONTACT, PimdirSummary.CALENDAR}) {
            for (PimdirCollections.Stored collection : collections.list(kind)) {
                long after = 0;
                while (true) {
                    List<Row> page = page(db, collection, kind, after);
                    summarise(db, collection.id, kind, page);
                    rows.addAll(page);
                    if (page.size() < PAGE) {
                        break;
                    }
                    after = page.get(page.size() - 1).seq;
                }
            }
        }
        rows.sort(NEWEST_FIRST);
        return rows;
    }

    /** pimdir's {@code list_retained}: one keyset page of a collection's trash. */
    private static List<Row> page(
            SQLiteDatabase db, PimdirCollections.Stored collection, String kind, long after) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection.id);
        values.put("after", after);
        values.put("limit", PAGE);
        List<Row> page = new ArrayList<>();
        try (Cursor cursor = query(db, "LIST_RETAINED_PAGE", values)) {
            int seq = cursor.getColumnIndexOrThrow("seq");
            int link = cursor.getColumnIndexOrThrow("link_id");
            int flags = cursor.getColumnIndexOrThrow("flags");
            int object = cursor.getColumnIndexOrThrow("object_hash");
            int sortKey = cursor.getColumnIndexOrThrow("sort_key");
            int level = cursor.getColumnIndexOrThrow("level");
            int retained = cursor.getColumnIndexOrThrow("retained_at");
            int size = cursor.getColumnIndexOrThrow("size");
            while (cursor.moveToNext()) {
                page.add(
                        new Row(
                                collection,
                                kind,
                                cursor.getLong(seq),
                                cursor.getString(link),
                                cursor.isNull(flags) ? null : cursor.getString(flags),
                                cursor.isNull(object) ? null : cursor.getString(object),
                                cursor.isNull(sortKey) ? "" : cursor.getString(sortKey),
                                cursor.getInt(level) == PimdirItems.FULL,
                                cursor.isNull(retained) ? null : cursor.getString(retained),
                                cursor.isNull(size) ? 0 : cursor.getLong(size)));
            }
        }
        return page;
    }

    /**
     * Attaches what each row's summary says, read through the kind's own
     * canonical statement for the page's link ids.
     */
    private static void summarise(
            SQLiteDatabase db, String collection, String kind, List<Row> page) {
        if (page.isEmpty()) {
            return;
        }
        Map<String, Row> byLink = new HashMap<>();
        JSONArray links = new JSONArray();
        for (Row row : page) {
            byLink.put(row.linkId, row);
            links.put(row.linkId);
        }
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("links", links.toString());

        String[] statements;
        switch (kind) {
            case PimdirSummary.MAIL:
                statements = new String[] {"LOAD_MAIL_SUMMARIES"};
                break;
            case PimdirSummary.CONTACT:
                statements = new String[] {"LOAD_CONTACT_SUMMARIES"};
                break;
            default:
                statements =
                        new String[] {
                            "LOAD_EVENT_SUMMARIES", "LOAD_TASK_SUMMARIES", "LOAD_JOURNAL_SUMMARIES"
                        };
        }
        for (String statement : statements) {
            try (Cursor cursor = query(db, statement, values)) {
                while (cursor.moveToNext()) {
                    Row row = byLink.get(text(cursor, "link_id"));
                    if (row == null) {
                        continue;
                    }
                    if (PimdirSummary.MAIL.equals(kind)) {
                        row.title = text(cursor, "subject");
                        String name = text(cursor, "sender_name");
                        row.who = name.isEmpty() ? text(cursor, "sender") : name;
                        row.when = text(cursor, "date");
                    } else if (PimdirSummary.CONTACT.equals(kind)) {
                        row.title = text(cursor, "fn");
                        row.who = text(cursor, "org");
                    } else {
                        row.title = text(cursor, "summary");
                        row.when = text(cursor, "dtstart");
                    }
                }
            }
        }
    }

    /** pimdir's {@code retained_bytes}: what retention holds store-wide, each body once. */
    long retainedBytes() {
        try (Cursor cursor =
                query(store.getReadableDatabase(), "RETAINED_BYTES", new HashMap<>())) {
            return cursor.moveToFirst() ? Math.max(0, cursor.getLong(0)) : 0;
        }
    }

    // ---- restore ----------------------------------------------------------

    /** Why a row cannot be restored. */
    enum Refusal {
        /** A source still binds it. */
        WAITING,
        /** The store holds its summary alone. */
        NOT_STORED,
        /** The target holds the item already. */
        PRESENT
    }

    /** Refused restore. */
    static final class RefusedException extends Exception {
        final Refusal refusal;

        RefusedException(Refusal refusal) {
            super(refusal.name());
            this.refusal = refusal;
        }
    }

    /**
     * Stages the row's stored body back into {@code target}, a collection of
     * its kind, as a local creation the next sync uploads.
     *
     * <p>Back into its last collection the create names the identity the row
     * holds, so the store revives the retained row and its public id
     * (STORAGE §11.1). Elsewhere it is a new item: a contact or an event keeps
     * its UID, one identity with one more placement; a mail is named from its
     * body, its link id being the handle its last mailbox gave it, which names
     * nothing in another one.
     */
    void restore(PimdirEngine engine, Row row, String target)
            throws RefusedException, JSONException {
        if (row.waiting()) {
            throw new RefusedException(Refusal.WAITING);
        }
        byte[] body = row.stored() ? items.objectBytes(row.collection.id, row.linkId) : null;
        if (body == null) {
            throw new RefusedException(Refusal.NOT_STORED);
        }

        JSONObject derived = PimdirSql.derive(row.kind, body);
        boolean home = target.equals(row.collection.id);
        String linkId =
                home || !PimdirSummary.MAIL.equals(row.kind)
                        ? row.linkId
                        : derived.getString("linkId");
        if (live(target, linkId)) {
            throw new RefusedException(Refusal.PRESENT);
        }
        String sortKey = derived.optString("sortKey", "");
        engine.mutateAdd(
                target,
                linkId,
                body,
                MailEngine.withFlag(flagsOf(row), MailEngine.DELETED, false),
                derived.optJSONObject("summary"),
                sortKey.isEmpty() ? row.sortKey : sortKey);
    }

    /** pimdir's {@code live_item_for_link}: whether the collection holds the identity live. */
    private boolean live(String collection, String linkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("link_id", linkId);
        try (Cursor cursor = query(store.getReadableDatabase(), "LIVE_ITEM_FOR_LINK", values)) {
            return cursor.moveToFirst();
        }
    }

    private static JSONArray flagsOf(Row row) {
        if (row.flags == null || row.flags.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(row.flags);
        } catch (JSONException error) {
            Log.w("pimalaya", "unreadable flags of " + row.linkId, error);
            return new JSONArray();
        }
    }

    // ---- free space -------------------------------------------------------

    /**
     * pimdir's {@code purge_retained_before}, then {@code collect_garbage}:
     * every item retained strictly before {@code cutoff} (RFC 3339) goes with
     * the pins it held, and the bodies nothing references any more are
     * reclaimed. A row a source still binds is never reached. Returns how
     * many items went.
     */
    int purgeBefore(String cutoff) {
        SQLiteDatabase db = store.getWritableDatabase();
        int purged = 0;
        db.beginTransaction();
        try {
            Map<String, Object> values = new HashMap<>();
            values.put("cutoff", cutoff);
            JSONArray pinned = new JSONArray();
            try (Cursor cursor = query(db, "PURGE_RETAINED_BEFORE", values)) {
                while (cursor.moveToNext()) {
                    purged++;
                    for (int column = 0; column < cursor.getColumnCount(); column++) {
                        if (!cursor.isNull(column)) {
                            pinned.put(cursor.getString(column));
                        }
                    }
                }
            }
            Map<String, Object> pins = new HashMap<>();
            pins.put("hashes", pinned.toString());
            exec(db, "RELEASE_PINS", pins);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        collectGarbage();
        return purged;
    }

    /**
     * pimdir's {@code collect_garbage}: the counts recomputed from the
     * pointers, then the objects at zero and their bodies reclaimed.
     *
     * <p>The blobs go inside the transaction, as the app's other collectors
     * do: no writer of this app takes io-pimdir's staging lock, so a body
     * filed again between the commit and the unlink would lose its file.
     */
    void collectGarbage() {
        SQLiteDatabase db = store.getWritableDatabase();
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        db.beginTransaction();
        try {
            exec(db, "RECOMPUTE_REFCOUNTS", new HashMap<>());
            List<String> garbage = new ArrayList<>();
            try (Cursor cursor = query(db, "LIST_GARBAGE_OBJECTS", new HashMap<>())) {
                while (cursor.moveToNext()) {
                    garbage.add(cursor.getString(0));
                }
            }
            exec(db, "DELETE_GARBAGE_OBJECTS", new HashMap<>());
            for (String hash : garbage) {
                if (!blobs.remove(hash)) {
                    Log.w("pimalaya", "could not unlink the collected blob " + hash);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- canonical statements ---------------------------------------------

    private static Cursor query(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        return MailStore.typed(db, bound.sql, bound.args);
    }

    private static void exec(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        db.execSQL(bound.sql, bound.args);
    }

    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }
}
