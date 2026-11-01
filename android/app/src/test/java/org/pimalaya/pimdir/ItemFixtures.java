package org.pimalaya.pimdir;

import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Items seeded as a sync or a reader would have filed them, for the tests.
 *
 * <p>Bodies content-addressed and refcounted on {@link PimdirItems}' terms,
 * one {@code seq} per link id store-wide (SPEC.md §9.1).
 */
public final class ItemFixtures {
    private final PimdirItems items;
    private final PimdirBlobs blobs;

    /** Seeds {@code store}. */
    public ItemFixtures(PimdirDb store) {
        this.items = new PimdirItems(store);
        this.blobs = new PimdirBlobs(store.blobs());
    }

    /** One item as a test hands it over: its identity and its content. */
    public static final class Row {
        final String linkId;
        final String body;

        /** The typed summary row (Annex A), or null to keep the stored one. */
        final JSONObject summary;

        final String sortKey;
        final String flags;

        /** The revision this row was mirrored at, or null when untracked. */
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

    /**
     * Writes one item's content, returning the hash it now carries.
     *
     * <p>A row carrying no body keeps the stored one; bindings stay, retention
     * stamps clear.
     */
    public String put(SQLiteDatabase db, String collection, Row row) {
        String hash = row.body == null || row.body.isEmpty() ? null : PimdirHash.of(row.body);
        if (hash != null) {
            store(db, hash, row.body.getBytes(StandardCharsets.UTF_8));
        }

        String previous = items.objectOf(db, collection, row.linkId);
        boolean exists = previous != null || items.knows(db, collection, row.linkId);
        if (hash == null) {
            hash = previous;
        }
        int level = hash == null ? PimdirItems.META : PimdirItems.FULL;

        if (exists) {
            db.execSQL(
                    "UPDATE items SET object_hash = ?, sort_key = ?,"
                            + " level = ?, deleted = 0, retained_at = NULL, retained_by = NULL"
                            + " WHERE collection = ? AND link_id = ?",
                    new Object[] {hash, row.sortKey, level, collection, row.linkId});
        } else {
            db.execSQL(
                    "INSERT INTO items(collection, link_id, seq, object_hash,"
                            + " sort_key, level) VALUES(?, ?, ?, ?, ?, ?)",
                    new Object[] {
                        collection, row.linkId, PimdirItems.seqFor(db, collection, row.linkId),
                        hash, row.sortKey, level
                    });
        }
        PimdirItems.writeFlags(db, collection, row.linkId, flagsOf(row.flags));

        PimdirSummary.write(db, collection, row.linkId, row.summary);
        if (row.revision != null) {
            db.execSQL(
                    "UPDATE bindings SET base_revision = ?, base_present = 1"
                            + " WHERE collection = ? AND link_id = ? AND source = ?",
                    new Object[] {row.revision, collection, row.linkId, PimdirStorage.SERVER});
            db.execSQL(
                    "INSERT INTO bindings(collection, link_id, source, handle, base_revision,"
                            + " base_present) SELECT ?, ?, ?, ?, ?, 1 WHERE NOT EXISTS (SELECT 1"
                            + " FROM bindings WHERE collection = ? AND link_id = ? AND source = ?)",
                    new Object[] {
                        collection, row.linkId, PimdirStorage.SERVER, row.linkId, row.revision,
                        collection, row.linkId, PimdirStorage.SERVER
                    });
        }
        items.adjustRefcount(db, previous, hash);
        return hash;
    }

    /**
     * Files a body against a held item, raising it to full, the row untouched.
     *
     * <p>As a reader opening a message does; an item not held is not created.
     */
    public void putObject(String collection, String linkId, byte[] body) {
        String hash = PimdirHash.of(body);
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            if (!items.knows(db, collection, linkId)) {
                Log.w("pimalaya", "no item " + linkId + " in " + collection);
                return;
            }

            String previous = items.objectOf(db, collection, linkId);
            store(db, hash, body);
            db.execSQL(
                    "UPDATE items SET object_hash = ?, level = ?"
                            + " WHERE collection = ? AND link_id = ?",
                    new Object[] {hash, PimdirItems.FULL, collection, linkId});
            items.adjustRefcount(db, previous, hash);
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** A fixture's flags, a JSON array as written, null for unknown. */
    private static java.util.List<String> flagsOf(String flags) {
        if (flags == null) {
            return null;
        }
        try {
            org.json.JSONArray array = new org.json.JSONArray(flags);
            java.util.List<String> list = new java.util.ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                list.add(array.getString(index));
            }
            return list;
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException(flags, error);
        }
    }

    /** Files a body and indexes it, leaving the refcount to the caller. */
    private void store(SQLiteDatabase db, String hash, byte[] bytes) {
        try {
            blobs.put(hash, bytes);
        } catch (IOException error) {
            throw new IllegalStateException("Could not store the body " + hash, error);
        }
        db.execSQL(
                "INSERT OR IGNORE INTO objects(hash, size, refcount) VALUES(?, ?, 0)",
                new Object[] {hash, bytes.length});
    }
}
