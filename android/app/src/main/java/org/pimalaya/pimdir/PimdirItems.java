package org.pimalaya.pimdir;

import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.pimalaya.client.PimdirSql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Item writes and reads the app performs outside a sync.
 *
 * <p>Staged edits and the read-only mirrors, beside {@link PimdirStorage}'s
 * engine yields. Owns the invariants: bodies content-addressed and refcounted
 * (base pins included), one {@code seq} per link id store-wide (SPEC.md §9.1).
 */
public final class PimdirItems {
    /** The detail ladder as the schema stores it: 0 probed, 1 meta, 2 full. */
    public static final int FULL = 2;

    /** The rung below it: the item is known and its body is not stored. */
    public static final int META = 1;

    private final PimdirDb store;
    private final PimdirBlobs blobs;

    /** Item access over {@code store}. */
    public PimdirItems(PimdirDb store) {
        this.store = store;
        this.blobs = new PimdirBlobs(store.blobs());
    }

    /** One item as a caller hands it over: its identity and its content. */
    public static final class Row {
        final String linkId;
        final String body;

        /** The typed summary row (Annex A), or null to keep the stored one. */
        final JSONObject summary;

        final String sortKey;
        final String flags;

        /**
         * The revision this row was mirrored at, or null when untracked.
         *
         * <p>Stored as the source binding's base: a mirror's guarded write has
         * nowhere else to keep its validator.
         */
        final String revision;

        /** A row with flags and a mirrored revision (null when untracked). */
        public Row(String linkId, String body, JSONObject summary, String sortKey, String flags,
                String revision) {
            this.linkId = linkId;
            this.body = body;
            this.summary = summary;
            this.sortKey = sortKey;
            this.flags = flags;
            this.revision = revision;
        }

        /** A row with no flags and no revision. */
        public Row(String linkId, String body, JSONObject summary, String sortKey) {
            this(linkId, body, summary, sortKey, "[]", null);
        }
    }

    /** The store for reading. */
    public SQLiteDatabase readable() {
        return store.getReadableDatabase();
    }

    /** The store for writing, deferred blobs unlinked first. */
    public SQLiteDatabase writable() {
        SQLiteDatabase db = store.getWritableDatabase();
        unlinkCollected(store, db, blobs);
        return db;
    }

    /** A body by content hash, empty when the hash names nothing stored. */
    public String body(String hash) {
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
     * The bytes one item's body holds, or null when it holds none.
     *
     * <p>Bytes, not text: a message in a legacy charset would be mangled before
     * its parser saw the header naming it.
     */
    public byte[] objectBytes(String collection, String linkId) {
        String hash = objectOf(readable(), collection, linkId);
        if (hash == null) {
            return null;
        }
        try {
            byte[] body = blobs.get(hash);
            if (body == null) {
                Log.w("pimalaya", "missing blob for " + hash);
            }
            return body;
        } catch (IOException error) {
            Log.w("pimalaya", "could not read the body " + hash, error);
            return null;
        }
    }

    /**
     * Files a body against a held item, raising it to full, the row untouched.
     *
     * <p>For the mirrors' spines gaining bodies one open at a time. An item not
     * held is not created: no listing could reach it.
     */
    public void putObject(String collection, String linkId, byte[] body) {
        String hash = PimdirHash.of(body);
        SQLiteDatabase db = writable();
        db.beginTransaction();
        try {
            if (!knows(db, collection, linkId)) {
                Log.w("pimalaya", "no item " + linkId + " in " + collection);
                return;
            }

            String previous = objectOf(db, collection, linkId);
            storeObject(db, hash, body);
            db.execSQL(
                    "UPDATE items SET object_hash = ?, level = ?"
                            + " WHERE collection = ? AND link_id = ?",
                    new Object[] {hash, FULL, collection, linkId});
            adjustRefcount(db, previous, hash);
            collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Writes one item's content, returning the hash it now carries.
     *
     * <p>Bindings stay (a staged edit keeps its base); retention stamps clear,
     * a write reviving a retired item.
     */
    public String put(SQLiteDatabase db, String collection, Row row) {
        String hash = row.body == null || row.body.isEmpty() ? null : PimdirHash.of(row.body);
        if (hash != null) {
            storeObject(db, hash, row.body);
        }

        String previous = objectOf(db, collection, row.linkId);
        boolean exists = previous != null || knows(db, collection, row.linkId);

        // NOTE: a row carrying no body keeps the stored one, on the same
        // terms as the summary it does not restate. A mail refresh restates
        // the spine of every mailbox and knows nothing about bodies, so
        // taking the absence as a removal would discard the message a reader
        // stored by opening it, once a round, for as long as they keep the
        // account.
        if (hash == null) {
            hash = previous;
        }
        int level = hash == null ? META : FULL;

        if (exists) {
            db.execSQL(
                    "UPDATE items SET object_hash = ?, sort_key = ?, flags = ?,"
                            + " level = ?, deleted = 0, retained_at = NULL, retained_by = NULL"
                            + " WHERE collection = ? AND link_id = ?",
                    new Object[] {hash, row.sortKey, row.flags, level, collection, row.linkId});
        } else {
            db.execSQL(
                    "INSERT INTO items(collection, link_id, seq, flags, object_hash,"
                            + " sort_key, level) VALUES(?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {
                        collection, row.linkId, seqFor(db, row.linkId), row.flags, hash,
                        row.sortKey, level
                    });
        }

        PimdirSummary.write(db, collection, row.linkId, row.summary);
        if (row.revision != null) {
            bindRevision(db, collection, row.linkId, row.revision);
        }
        adjustRefcount(db, previous, hash);
        return hash;
    }

    /**
     * Records the revision the mirror read, as its source binding's base.
     *
     * <p>Not a merge base: the validator a guarded write reads back. The body
     * is not pinned; a mirror stages nothing to diff.
     */
    private void bindRevision(
            SQLiteDatabase db, String collection, String linkId, String revision) {
        db.execSQL(
                "INSERT INTO bindings(collection, link_id, source, handle, base_revision,"
                        + " base_present) VALUES(?, ?, ?, ?, ?, 1)"
                        + " ON CONFLICT(collection, link_id, source) DO UPDATE SET"
                        + " base_revision = excluded.base_revision, base_present = 1",
                new Object[] {collection, linkId, PimdirStorage.SERVER, linkId, revision});
    }

    /**
     * Drops one item outright, releasing every body it referenced.
     *
     * <p>Not retained (SPEC.md §11): only the sync knows a source dropped an
     * item, and a caller here decided it goes.
     */
    public void remove(SQLiteDatabase db, String collection, String linkId) {
        String object = objectOf(db, collection, linkId);
        List<String> pinned = bindingObjectsOf(db, collection, linkId);
        String conflict = conflictObjectOf(db, collection, linkId);

        db.execSQL(
                "DELETE FROM items WHERE collection = ? AND link_id = ?",
                new Object[] {collection, linkId});

        adjustRefcount(db, object, null);
        adjustRefcount(db, conflict, null);
        for (String pin : pinned) {
            adjustRefcount(db, pin, null);
        }
    }

    /** Every link id a collection holds, retained rows included. */
    public List<String> linkIdsOf(SQLiteDatabase db, String collection) {
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

    /** The item's current body hash, or null for none or no item. */
    public String objectOf(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT object_hash FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** Whether the collection holds the item at all. */
    public boolean knows(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            return cursor.moveToFirst();
        }
    }

    /**
     * The item's public id, shared by every placement of its link id.
     *
     * <p>Drawn from the store-wide counter the first time a link id is seen.
     */
    public long seqFor(SQLiteDatabase db, String linkId) {
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

    /** Files a text body and indexes it, leaving the refcount to the caller. */
    void storeObject(SQLiteDatabase db, String hash, String body) {
        storeObject(db, hash, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Files a body and indexes it, leaving the refcount to the caller. */
    void storeObject(SQLiteDatabase db, String hash, byte[] bytes) {
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

    /** Files and indexes a file's bytes, streamed; refcount the caller's. */
    public void storeObject(SQLiteDatabase db, String hash, java.io.File source) {
        try {
            blobs.put(hash, source);
        } catch (IOException error) {
            throw new IllegalStateException("Could not store the body " + hash, error);
        }
        db.execSQL(
                "INSERT INTO objects(hash, size, refcount) VALUES(?, ?, 0)"
                        + " ON CONFLICT(hash) DO NOTHING",
                new Object[] {hash, source.length()});
    }

    /**
     * Files and indexes a file the bridge wrote, moved; refcount the caller's.
     *
     * <p>Moved by {@link PimdirBlobs#adopt}: the file is gone after it.
     */
    void adoptObject(SQLiteDatabase db, String hash, java.io.File source) {
        long size = source.length();
        try {
            blobs.adopt(hash, source);
        } catch (IOException error) {
            throw new IllegalStateException("Could not store the body " + hash, error);
        }
        db.execSQL(
                "INSERT INTO objects(hash, size, refcount) VALUES(?, ?, 0)"
                        + " ON CONFLICT(hash) DO NOTHING",
                new Object[] {hash, size});
    }

    /**
     * {@link #putObject(String, String, byte[])} of a file the bridge hashed.
     *
     * <p>Moved into the blob store, never read here; the file is gone after.
     */
    public void putObject(String collection, String linkId, String hash, java.io.File source) {
        SQLiteDatabase db = writable();
        db.beginTransaction();
        try {
            if (!knows(db, collection, linkId)) {
                Log.w("pimalaya", "no item " + linkId + " in " + collection);
                source.delete();
                return;
            }

            String previous = objectOf(db, collection, linkId);
            adoptObject(db, hash, source);
            db.execSQL(
                    "UPDATE items SET object_hash = ?, level = ?"
                            + " WHERE collection = ? AND link_id = ?",
                    new Object[] {hash, FULL, collection, linkId});
            adjustRefcount(db, previous, hash);
            collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** The file one body is stored in, null when no such blob is held. */
    public java.io.File blobFile(String hash) {
        if (hash == null) {
            return null;
        }
        java.io.File file = blobs.pathOf(hash);
        return file.isFile() ? file : null;
    }

    /** The file one item's body is stored in, null when it holds none. */
    public java.io.File objectFile(String collection, String linkId) {
        return blobFile(objectOf(readable(), collection, linkId));
    }

    /** Moves the refcount by the difference one reference made. */
    public void adjustRefcount(SQLiteDatabase db, String before, String after) {
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

    /**
     * Drops unreferenced objects, their blobs left for after the commit.
     *
     * <p>See {@link #deferUnlink}.
     */
    public void collectGarbage(SQLiteDatabase db) {
        List<String> orphans = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT hash FROM objects WHERE refcount <= 0", null)) {
            while (cursor.moveToNext()) {
                orphans.add(cursor.getString(0));
            }
        }
        for (String hash : orphans) {
            db.execSQL("DELETE FROM objects WHERE hash = ?", new Object[] {hash});
        }
        deferUnlink(store, orphans);
    }

    /**
     * Leaves deleted objects' blobs until the transaction commits.
     *
     * <p>A rollback restores the rows, which must still find their blobs
     * ({@link #unlinkCollected}).
     */
    public static void deferUnlink(PimdirDb store, List<String> hashes) {
        store.collected.addAll(hashes);
    }

    /**
     * Keeps a body's file from {@link #unlinkCollected} until {@link #unpin}.
     *
     * <p>For a body filed before the write naming it (a downloaded message):
     * taken before the file, released after the write. Its row may be collected
     * meanwhile; the file stays and the write refiles the row.
     */
    public static void pin(PimdirDb store, String hash) {
        synchronized (store.pinned) {
            store.pinned.merge(hash, 1, Integer::sum);
        }
    }

    /** Releases one {@link #pin}. */
    public static void unpin(PimdirDb store, String hash) {
        synchronized (store.pinned) {
            store.pinned.computeIfPresent(hash, (key, count) -> count == 1 ? null : count - 1);
        }
    }

    /** {@link #unlinkCollected} over this store, after a collector commits. */
    public void unlinkCollected() {
        unlinkCollected(store, store.getWritableDatabase(), blobs);
    }

    /**
     * Unlinks deferred blobs whose rows are still gone, outside transactions.
     *
     * <p>Run before each write ({@link #writable}) and after collectors, in its
     * own transaction; pinned bodies wait. A crash leaves an orphan blob, never
     * a row without body.
     */
    static void unlinkCollected(PimdirDb store, SQLiteDatabase db, PimdirBlobs blobs) {
        if (store.collected.isEmpty() || db.inTransaction()) {
            return;
        }
        db.beginTransaction();
        try {
            for (String hash : new ArrayList<>(store.collected)) {
                store.collected.remove(hash);
                synchronized (store.pinned) {
                    if (store.pinned.containsKey(hash)) {
                        store.collected.add(hash);
                        continue;
                    }
                    PimdirSql.Bound exists =
                            PimdirSql.bind("OBJECT_EXISTS", Map.of("hash", hash));
                    boolean held;
                    try (Cursor cursor = PimdirDb.typed(db, exists.sql, exists.args)) {
                        held = cursor.moveToFirst();
                    }
                    if (!held && !blobs.remove(hash)) {
                        Log.w("pimalaya", "could not unlink the orphan blob " + hash);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
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

    /**
     * Every body the item's bindings pin.
     *
     * <p>Each source's agreed base, and a conflicted source's diverging remote.
     */
    private static List<String> bindingObjectsOf(
            SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT base_object, conflict_object FROM bindings"
                                + " WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            List<String> pinned = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                for (int column = 0; column < 2; column++) {
                    if (!cursor.isNull(column)) {
                        pinned.add(cursor.getString(column));
                    }
                }
            }
            return pinned;
        }
    }
}
