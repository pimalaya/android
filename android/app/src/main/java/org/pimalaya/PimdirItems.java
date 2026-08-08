package org.pimalaya;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Item-level writes and reads the app performs on its own, outside a sync.
 *
 * <p>{@link PimdirStorage} services what the io-replica engine asks for; this
 * is the other half, for everything the app does without the engine: staging an
 * edit the next sync will push, and refreshing the read-only mirrors that have
 * no engine at all (mail, calendar). Both write the same tables, so the object
 * bookkeeping lives here once rather than in each domain.
 *
 * <p>Three invariants are the whole reason this is not inline SQL:
 *
 * <ul>
 *   <li><strong>A body is content-addressed and refcounted.</strong> Writing one
 *       files a blob, inserts an {@code objects} row and moves the count by the
 *       difference this item made; releasing the last reference unlinks the
 *       blob. Counting wrong does not corrupt anything visibly, it loses bodies.
 *   <li><strong>One link id, one {@code seq}.</strong> The public id is
 *       store-global and shared by every placement of an item (SPEC.md §9.1), so
 *       a card added to a second address book must reuse the id it already has
 *       rather than draw a new one.
 *   <li><strong>A base is a reference too.</strong> {@code bindings.base_object}
 *       pins a body exactly as {@code items.object_hash} does, so a removal has
 *       to release both.
 * </ul>
 */
final class PimdirItems {
    /** The detail ladder as the schema stores it: 0 probed, 1 meta, 2 full. */
    static final int FULL = 2;

    private final PimdirDb store;
    private final PimdirBlobs blobs;

    PimdirItems(PimdirDb store) {
        this.store = store;
        this.blobs = new PimdirBlobs(store.blobs());
    }

    /** One item as a caller hands it over: its identity and its content. */
    static final class Row {
        final String linkId;
        final String body;
        final String meta;
        final String sortKey;
        final String flags;

        Row(String linkId, String body, String meta, String sortKey, String flags) {
            this.linkId = linkId;
            this.body = body;
            this.meta = meta;
            this.sortKey = sortKey;
            this.flags = flags;
        }

        Row(String linkId, String body, String meta, String sortKey) {
            this(linkId, body, meta, sortKey, "[]");
        }
    }

    SQLiteDatabase readable() {
        return store.getReadableDatabase();
    }

    SQLiteDatabase writable() {
        return store.getWritableDatabase();
    }

    /** A body by content hash, empty when the hash names nothing stored. */
    String body(String hash) {
        if (hash == null) {
            return "";
        }
        try {
            String body = blobs.getText(hash);
            if (body == null) {
                Log.w("pimalaya", "missing blob for " + hash);
                return "";
            }
            return body;
        } catch (IOException error) {
            Log.w("pimalaya", "could not read the body " + hash, error);
            return "";
        }
    }

    /**
     * Writes one item's content, returning the hash it now carries.
     *
     * <p>Everything the store owns and the caller does not is left alone: the
     * bindings, so a staged edit keeps the base its push diffs against, and the
     * retention stamps, which a write clears because writing an item is exactly
     * how a retired one comes back.
     */
    String put(SQLiteDatabase db, String collection, Row row) {
        String hash = row.body == null || row.body.isEmpty() ? null : PimdirHash.of(row.body);
        if (hash != null) {
            storeObject(db, hash, row.body);
        }

        String previous = objectOf(db, collection, row.linkId);
        boolean exists = previous != null || knows(db, collection, row.linkId);

        if (exists) {
            db.execSQL(
                    "UPDATE items SET object_hash = ?, meta = ?, sort_key = ?, flags = ?,"
                            + " level = ?, deleted = 0, retained_at = NULL, retained_by = NULL"
                            + " WHERE collection = ? AND link_id = ?",
                    new Object[] {
                        hash, row.meta, row.sortKey, row.flags, FULL, collection, row.linkId
                    });
        } else {
            db.execSQL(
                    "INSERT INTO items(collection, link_id, seq, flags, object_hash, meta,"
                            + " sort_key, level) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {
                        collection, row.linkId, seqFor(db, row.linkId), row.flags, hash,
                        row.meta, row.sortKey, FULL
                    });
        }

        adjustRefcount(db, previous, hash);
        return hash;
    }

    /**
     * Drops one item outright, releasing every body it referenced.
     *
     * <p>Outright rather than retained: retention (SPEC.md §16) exists so a
     * store is never the last holder of a body a remote expunged, and it is the
     * sync seam's job because only the sync knows a source dropped an item. A
     * caller reaching this has decided the item goes.
     */
    void remove(SQLiteDatabase db, String collection, String linkId) {
        String object = objectOf(db, collection, linkId);
        List<String> bases = baseObjectsOf(db, collection, linkId);
        String conflict = conflictObjectOf(db, collection, linkId);

        db.execSQL(
                "DELETE FROM items WHERE collection = ? AND link_id = ?",
                new Object[] {collection, linkId});

        adjustRefcount(db, object, null);
        adjustRefcount(db, conflict, null);
        for (String base : bases) {
            adjustRefcount(db, base, null);
        }
    }

    /**
     * Replaces a collection's contents with the listed rows, for the read-only
     * mirrors: what the listing no longer carries is gone from the store too.
     *
     * <p>These collections have no engine and no local edits, so there is
     * nothing to reconcile and nothing to stage. A whole-collection replace is
     * therefore both correct and the cheapest thing that is: the alternative,
     * diffing to spare a few writes, would buy nothing and could leave a row the
     * server no longer has.
     */
    void replace(String collection, List<Row> rows) {
        SQLiteDatabase db = writable();
        db.beginTransaction();
        try {
            Set<String> listed = new HashSet<>();
            for (Row row : rows) {
                listed.add(row.linkId);
                put(db, collection, row);
            }
            for (String stale : linkIdsOf(db, collection)) {
                if (!listed.contains(stale)) {
                    remove(db, collection, stale);
                }
            }
            collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Every link id a collection holds, retained rows included. */
    List<String> linkIdsOf(SQLiteDatabase db, String collection) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT link_id FROM items WHERE collection = ?",
                        new String[] {collection})) {
            List<String> ids = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
            }
            return ids;
        }
    }

    /** The item's current body hash, null when it has none or does not exist. */
    String objectOf(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT object_hash FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** Whether the collection holds the item at all. */
    boolean knows(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            return cursor.moveToFirst();
        }
    }

    /**
     * The item's public id, drawn from the store-wide counter the first time a
     * link id is seen and shared by every later placement of it.
     */
    long seqFor(SQLiteDatabase db, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT seq FROM items WHERE link_id = ? LIMIT 1",
                        new String[] {linkId})) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        try (Cursor cursor =
                db.rawQuery(
                        "UPDATE store_meta SET next_seq = next_seq + 1 WHERE id = 1"
                                + " RETURNING next_seq - 1",
                        null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 1;
        }
    }

    /** Files a body and indexes it, leaving the refcount to the caller. */
    void storeObject(SQLiteDatabase db, String hash, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            blobs.put(hash, bytes);
        } catch (IOException error) {
            throw new IllegalStateException("Could not store the body " + hash, error);
        }
        db.execSQL(
                "INSERT INTO objects(hash, size, refcount) VALUES(?, ?, 0)"
                        + " ON CONFLICT(hash) DO NOTHING",
                new Object[] {hash, bytes.length});
    }

    /** Moves the refcount by the difference one reference made, never globally. */
    void adjustRefcount(SQLiteDatabase db, String before, String after) {
        if (before == null ? after == null : before.equals(after)) {
            return;
        }
        if (before != null) {
            db.execSQL(
                    "UPDATE objects SET refcount = refcount - 1 WHERE hash = ?",
                    new Object[] {before});
        }
        if (after != null) {
            db.execSQL(
                    "UPDATE objects SET refcount = refcount + 1 WHERE hash = ?",
                    new Object[] {after});
        }
    }

    /** Drops unreferenced objects and unlinks their blobs. */
    void collectGarbage(SQLiteDatabase db) {
        List<String> orphans = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT hash FROM objects WHERE refcount <= 0", null)) {
            while (cursor.moveToNext()) {
                orphans.add(cursor.getString(0));
            }
        }
        for (String hash : orphans) {
            db.execSQL("DELETE FROM objects WHERE hash = ?", new Object[] {hash});
            // NOTE: the row goes inside the transaction and the file after it,
            // so a crash leaves an orphan blob rather than a row without a body.
            if (!blobs.remove(hash)) {
                Log.w("pimalaya", "could not unlink the orphan blob " + hash);
            }
        }
    }

    private static String conflictObjectOf(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT conflict_object FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    private static List<String> baseObjectsOf(
            SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT base_object FROM bindings WHERE collection = ? AND link_id = ?"
                                + " AND base_object IS NOT NULL",
                        new String[] {collection, linkId})) {
            List<String> bases = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                bases.add(cursor.getString(0));
            }
            return bases;
        }
    }
}
