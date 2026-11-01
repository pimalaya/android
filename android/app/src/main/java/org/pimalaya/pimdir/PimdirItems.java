package org.pimalaya.pimdir;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.pimalaya.client.PimdirSql;

import java.io.IOException;
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
     * Drops one item outright, releasing every body it referenced.
     *
     * <p>Not retained (SPEC.md §11): only the sync knows a source dropped an
     * item, and a caller here decided it goes.
     */
    public void remove(SQLiteDatabase db, String collection, String linkId) {
        String object = objectOf(db, collection, linkId);
        List<String> pinned = bindingObjectsOf(db, collection, linkId);
        String conflict = conflictObjectOf(db, collection, linkId);

        PimdirDb.exec(db, "DELETE_ITEM", key(collection, linkId));

        adjustRefcount(db, object, null);
        adjustRefcount(db, conflict, null);
        for (String pin : pinned) {
            adjustRefcount(db, pin, null);
        }
    }

    /** Every link id a collection holds, retained rows included. */
    public List<String> linkIdsOf(SQLiteDatabase db, String collection) {
        try (Cursor cursor =
                PimdirDb.query(db, "LIST_LINK_IDS", PimdirSql.values("collection", collection))) {
            List<String> ids = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
            }
            return ids;
        }
    }

    /** The item's current body hash, or null for none or no item. */
    public String objectOf(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor = PimdirDb.query(db, "ITEM_OBJECT", key(collection, linkId))) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** Whether the collection holds the item at all. */
    public boolean knows(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor = PimdirDb.query(db, "ITEM_EXISTS", key(collection, linkId))) {
            return cursor.moveToFirst();
        }
    }

    /**
     * The public id an item of {@code linkId} takes in {@code collection},
     * shared by every placement of that key within the collection's kind
     * (STORAGE §9.1, {@code seq_for_link_any}).
     *
     * <p>Drawn from the store-wide counter ({@code bump_next_seq}) the first
     * time the key is seen in the kind, and always for a key the writer
     * derived ({@code alt:}, {@code hash:}, {@code dup:}), which names no
     * identity to share one with.
     */
    public static long seqFor(SQLiteDatabase db, String collection, String linkId) {
        if (!derived(linkId)) {
            Map<String, Object> values = new java.util.HashMap<>();
            values.put("collection", collection);
            values.put("link_id", linkId);
            PimdirSql.Bound held = PimdirSql.bind("SEQ_FOR_LINK_ANY", values);
            try (Cursor cursor = PimdirDb.typed(db, held.sql, held.args)) {
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            }
        }
        PimdirSql.Bound bump = PimdirSql.bind("BUMP_NEXT_SEQ", Map.of());
        try (Cursor cursor = PimdirDb.query(db, bump)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 1;
        }
    }

    /**
     * Writes an item's flags as rows (STORAGE §13): {@code flags_known}, then
     * {@code retain_flags} and {@code add_flags} over the set; null forgets
     * them, the schema's trigger dropping the rows. The row is written first.
     */
    public static void writeFlags(
            SQLiteDatabase db,
            String collection,
            String linkId,
            java.util.Collection<String> flags) {
        Map<String, Object> known = key(collection, linkId);
        known.put("flags_known", flags == null ? 0 : 1);
        PimdirDb.exec(db, "SET_FLAGS_KNOWN", known);
        if (flags == null) {
            return;
        }
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("collection", collection);
        values.put("link_id", linkId);
        values.put("flags", new ArrayList<Object>(new java.util.LinkedHashSet<>(flags)));
        PimdirDb.changed(db, PimdirSql.bind("RETAIN_FLAGS", values));
        PimdirDb.changed(db, PimdirSql.bind("ADD_FLAGS", values));
    }

    /**
     * Writes a binding's base flags as rows, on {@link #writeFlags}'s terms
     * ({@code retain_base_flags}, {@code add_base_flags}).
     */
    public static void writeBaseFlags(
            SQLiteDatabase db,
            String collection,
            String linkId,
            String source,
            java.util.Collection<String> flags) {
        Map<String, Object> known = key(collection, linkId);
        known.put("source", source);
        known.put("base_flags_known", flags == null ? 0 : 1);
        PimdirDb.exec(db, "SET_BASE_FLAGS_KNOWN", known);
        if (flags == null) {
            return;
        }
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("collection", collection);
        values.put("link_id", linkId);
        values.put("source", source);
        values.put("flags", new ArrayList<Object>(new java.util.LinkedHashSet<>(flags)));
        PimdirDb.changed(db, PimdirSql.bind("RETAIN_BASE_FLAGS", values));
        PimdirDb.changed(db, PimdirSql.bind("ADD_BASE_FLAGS", values));
    }

    /** A space-joined flags column as its flags, null for a NULL one. */
    public static List<String> flagsFrom(String joined) {
        if (joined == null) {
            return null;
        }
        List<String> flags = new ArrayList<>();
        for (String flag : joined.split(" ")) {
            if (!flag.isEmpty()) {
                flags.add(flag);
            }
        }
        return flags;
    }

    /** Whether a key is one the writer derived (STORAGE §9). */
    static boolean derived(String linkId) {
        return linkId.startsWith("alt:") || linkId.startsWith("hash:") || linkId.startsWith("dup:");
    }

    /** Files and indexes a file's bytes, streamed; refcount the caller's. */
    public void storeObject(SQLiteDatabase db, String hash, java.io.File source) {
        try {
            blobs.put(hash, source);
        } catch (IOException error) {
            throw new IllegalStateException("Could not store the body " + hash, error);
        }
        PimdirDb.exec(db, "FILE_OBJECT", PimdirSql.values("hash", hash, "size", source.length()));
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
        PimdirDb.exec(db, "FILE_OBJECT", PimdirSql.values("hash", hash, "size", size));
    }

    /**
     * Files a body the bridge hashed against a held item, raising it to full.
     *
     * <p>For the mirrors' spines gaining bodies one open at a time, the row
     * untouched; an item not held is not created. Moved into the blob store,
     * never read here: the file is gone after.
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
            Map<String, Object> values = key(collection, linkId);
            values.put("object_hash", hash);
            values.put("level", FULL);
            PimdirDb.exec(db, "SET_ITEM_OBJECT", values);
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
        adjust(db, before, -1);
        adjust(db, after, 1);
    }

    /**
     * Drops unreferenced objects, their blobs left for after the commit.
     *
     * <p>See {@link #deferUnlink}.
     */
    public void collectGarbage(SQLiteDatabase db) {
        deferUnlink(store, collectGarbageRows(db));
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

    /** One item's key as a statement's values, which a caller may add to. */
    static Map<String, Object> key(String collection, String linkId) {
        return PimdirSql.values("collection", collection, "link_id", linkId);
    }

    /** Moves one body's refcount by {@code delta} ({@code adjust_refcount}). */
    static void adjust(SQLiteDatabase db, String hash, int delta) {
        if (hash != null) {
            PimdirDb.exec(db, "ADJUST_REFCOUNT", PimdirSql.values("hash", hash, "delta", delta));
        }
    }

    /**
     * Deletes the objects no pointer names ({@code list_garbage_objects},
     * then {@code delete_garbage_objects}); answers their hashes, whose
     * files go once the transaction commits.
     */
    static List<String> collectGarbageRows(SQLiteDatabase db) {
        List<String> orphans = new ArrayList<>();
        try (Cursor cursor = PimdirDb.query(db, "LIST_GARBAGE_OBJECTS", Map.of())) {
            while (cursor.moveToNext()) {
                orphans.add(cursor.getString(0));
            }
        }
        if (!orphans.isEmpty()) {
            PimdirDb.exec(db, "DELETE_GARBAGE_OBJECTS", Map.of());
        }
        return orphans;
    }

    private static String conflictObjectOf(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                PimdirDb.query(db, "ITEM_CONFLICT_OBJECT", key(collection, linkId))) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /**
     * Every body the item's bindings pin.
     *
     * <p>Each source's agreed base, a conflicted source's diverging remote, and
     * the shared body each last agreed with (STORAGE §5).
     */
    private static List<String> bindingObjectsOf(
            SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                PimdirDb.query(db, "ITEM_BINDING_OBJECTS", key(collection, linkId))) {
            List<String> pinned = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                for (int column = 0; column < 3; column++) {
                    if (!cursor.isNull(column)) {
                        pinned.add(cursor.getString(column));
                    }
                }
            }
            return pinned;
        }
    }
}
