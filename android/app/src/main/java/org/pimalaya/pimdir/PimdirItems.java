package org.pimalaya.pimdir;

import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.pimalaya.client.PimdirSql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item-level writes and reads the app performs on its own, outside a sync.
 *
 * <p>{@link PimdirStorage} services what the io-pimdir engine asks for; this
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
public final class PimdirItems {
    /** The detail ladder as the schema stores it: 0 probed, 1 meta, 2 full. */
    public static final int FULL = 2;

    /** The rung below it: the item is known and its body is not stored. */
    public static final int META = 1;

    private final PimdirDb store;
    private final PimdirBlobs blobs;

    public PimdirItems(PimdirDb store) {
        this.store = store;
        this.blobs = new PimdirBlobs(store.blobs());
    }

    /** One item as a caller hands it over: its identity and its content. */
    public static final class Row {
        final String linkId;
        final String body;

        /**
         * The typed summary row (STORAGE Annex A) as {@link PimdirSummary}
         * spells it on the wire, or null to keep whatever is stored.
         */
        final JSONObject summary;

        final String sortKey;
        final String flags;

        /**
         * The revision this row was mirrored at, or null when the caller
         * tracks none: a read-only mirror has no engine, so the validator its
         * next write is guarded by is the base revision of its source binding
         * and there is nowhere else for it to live.
         */
        final String revision;

        public Row(String linkId, String body, JSONObject summary, String sortKey, String flags,
                String revision) {
            this.linkId = linkId;
            this.body = body;
            this.summary = summary;
            this.sortKey = sortKey;
            this.flags = flags;
            this.revision = revision;
        }

        public Row(String linkId, String body, JSONObject summary, String sortKey) {
            this(linkId, body, summary, sortKey, "[]", null);
        }
    }

    public SQLiteDatabase readable() {
        return store.getReadableDatabase();
    }

    public SQLiteDatabase writable() {
        SQLiteDatabase db = store.getWritableDatabase();
        unlinkCollected(db, blobs);
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
     * <p>Bytes rather than text, for the one kind that is not text: a
     * message is what its sender encoded, and handing it back decoded as
     * UTF-8 would mangle a body in a legacy charset before the parser that
     * reads the header naming that charset ever saw it.
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
     * Files a body against an item the collection already holds, raising it
     * to full and leaving the rest of its row alone.
     *
     * <p>For the mirrors, whose rows arrive as a spine and gain their bodies
     * one open at a time: everything else writes the body with the row,
     * having had both in hand from the start. An item the collection does
     * not hold is not created, because a body with no row is a body no
     * listing can reach.
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
     * <p>Everything the store owns and the caller does not is left alone: the
     * bindings, so a staged edit keeps the base its push diffs against, and the
     * retention stamps, which a write clears because writing an item is exactly
     * how a retired one comes back.
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
     * Records the revision the mirror read the row at, as the base of its
     * source binding.
     *
     * <p>A mirror has no engine, so this binding is not a merge base: it is
     * where the one validator a guarded write needs belongs, and reading it
     * back is how the write conditions itself on what the server held. The
     * body is deliberately not pinned, since a mirror stages nothing to diff
     * against it.
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
     * <p>Outright rather than retained: retention (SPEC.md §11) exists so a
     * store is never the last holder of a body a remote expunged, and it is the
     * sync seam's job because only the sync knows a source dropped an item. A
     * caller reaching this has decided the item goes.
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

    /** The item's current body hash, null when it has none or does not exist. */
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
     * The item's public id, drawn from the store-wide counter the first time a
     * link id is seen and shared by every later placement of it.
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

    /** Files a file's bytes and indexes them, streamed, leaving the refcount to the caller. */
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
     * Files a file the bridge wrote and indexes it, moved into the blob
     * store rather than copied ({@link PimdirBlobs#adopt}), leaving the
     * refcount to the caller; the file is gone after it.
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
     * {@link #putObject(String, String, byte[])} of a body the bridge wrote
     * to a file and hashed ({@code hash}), moved into the blob store: the
     * body never read on this side. The file is gone after it.
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

    /** The file one body is stored in, null when the store holds no such blob. */
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

    /** Moves the refcount by the difference one reference made, never globally. */
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
     * Drops unreferenced objects, their blobs left for after the commit
     * ({@link #deferUnlink}).
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
        deferUnlink(orphans);
    }

    /** The objects deleted in a transaction whose blobs wait for it to commit. */
    private static final Set<String> COLLECTED = ConcurrentHashMap.newKeySet();

    /**
     * Bodies filed in the blob store ahead of the write naming them, by how
     * many such writes are under way ({@link #pin}).
     */
    private static final Map<String, Integer> PINNED = new HashMap<>();

    /**
     * Leaves the blobs of objects a transaction just deleted until it has
     * committed ({@link #unlinkCollected}): a rollback restores the rows,
     * and a row whose blob was already unlinked would be a body lost.
     */
    public static void deferUnlink(List<String> hashes) {
        COLLECTED.addAll(hashes);
    }

    /**
     * Keeps a body's file from {@link #unlinkCollected} until {@link #unpin},
     * for a body filed outside the transaction whose row names it (a
     * download's message, moved in as it is fetched and indexed by the
     * engine's write after): taken before the file is, released once that
     * write has returned. Its object row may be collected meanwhile, by a
     * writer dropping the last reference to the same hash; the file
     * stays, and the write files the row again.
     */
    public static void pin(String hash) {
        synchronized (PINNED) {
            PINNED.merge(hash, 1, Integer::sum);
        }
    }

    /** Releases one {@link #pin}. */
    public static void unpin(String hash) {
        synchronized (PINNED) {
            PINNED.computeIfPresent(hash, (key, count) -> count == 1 ? null : count - 1);
        }
    }

    /** {@link #unlinkCollected} over this store, for a collector just committed. */
    public void unlinkCollected() {
        unlinkCollected(store.getWritableDatabase(), blobs);
    }

    /**
     * Unlinks the blobs {@link #deferUnlink} left, once no transaction is
     * open on this thread: those whose object row is still gone, so one
     * rolled back or filed again keeps its file. Run before each write
     * ({@link #writable}) and after a collector commits; a crash between
     * leaves an orphan blob, never a row without its body.
     *
     * <p>Inside a transaction of its own, so no writer is between filing
     * a body and committing its row while the row is read and the file
     * unlinked; a pinned body ({@link #pin}) is left for a later run.
     */
    static void unlinkCollected(SQLiteDatabase db, PimdirBlobs blobs) {
        if (COLLECTED.isEmpty() || db.inTransaction()) {
            return;
        }
        db.beginTransaction();
        try {
            for (String hash : new ArrayList<>(COLLECTED)) {
                COLLECTED.remove(hash);
                synchronized (PINNED) {
                    if (PINNED.containsKey(hash)) {
                        COLLECTED.add(hash);
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
     * Every body the item's bindings pin: the base each source last agreed on,
     * and the diverging remote one a conflicted source is waiting to settle.
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
