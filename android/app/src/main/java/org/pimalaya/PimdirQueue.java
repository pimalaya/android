package org.pimalaya;

import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.pimalaya.client.PimdirSql;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The action queue: the write door for what the app wants done and the
 * store cannot do itself (pimdir STORAGE section 15).
 *
 * <p>Every other write here mutates the store and is finished when the
 * transaction commits. A submission is not: what it wants is a message
 * handed to a server, which is somewhere else and may be refused, may
 * have to wait for a network, and cannot be rolled back once it has
 * happened. The queue is the shape the standard gives that: a row
 * carrying a kind, a versioned payload and the body the action needs,
 * pinned in the blob directory so nothing collects it while it waits.
 *
 * <p>Two halves, as the standard names them. <strong>Producing</strong>
 * ({@link #enqueue}) is one transaction: the collection ensured, the
 * body stored and pinned, the row appended. <strong>Applying</strong> is
 * the drain, and for a submission it is the one shape section 15.5
 * describes, an intent whose effect is not a store mutation: the row is
 * not claimed and deleted up front, because a claim that deleted it
 * before the message went would lose the message. It is carried out
 * first and acknowledged after ({@link #acknowledge}), which makes a
 * submission at-least-once. A drain interrupted between the handover and
 * the acknowledgement sends the message twice, and that is the trade
 * the standard names against losing it.
 *
 * <p>The statements are io-pimdir's, bound by name: nothing here spells
 * a column list of its own.
 */
final class PimdirQueue {
    /**
     * The app's own action kind: hand this message to the account's
     * submission endpoint.
     *
     * <p>An open string, which is what lets an application carry an
     * intent of its own (section 15.3), with a mail submission as the
     * standard's worked example. The store owes it append order, blob
     * pinning and the skip rule, and nothing else.
     */
    static final String SUBMIT = "submit";

    /** The payload version this app writes and reads. */
    private static final int VERSION = 1;

    /** What the queue records as the origin of a row. */
    private static final String PRODUCER = "pimalaya.android";

    private final PimdirDb store;
    private final PimdirItems items;
    private final PimdirBlobs blobs;
    private final PimdirAccount accounts;

    PimdirQueue(PimdirDb store, PimdirAccount accounts) {
        this.store = store;
        this.items = new PimdirItems(store);
        this.blobs = new PimdirBlobs(store.blobs());
        this.accounts = accounts;
    }

    /** One queued action, pending or parked. */
    static final class Action {
        final long id;

        /** The collection it is against, which is what scopes a read. */
        final String collection;

        /** The kind, an open string; this app writes {@link #SUBMIT}. */
        final String kind;

        /** The versioned payload, as it was written. */
        final JSONObject payload;

        /** The body the action carries, or null when it carries none. */
        final String objectHash;

        /** When it was appended, as the statement stamped it. */
        final String createdAt;

        final int attempts;

        /**
         * What parked it, or null while it is still pending.
         *
         * <p>The column is the pending/parked switch itself: the drain
         * reads the rows whose error is null, so recording one takes the
         * action out of the drain until somebody looks at it.
         */
        final String error;

        Action(long id, String collection, String kind, JSONObject payload, String objectHash,
                String createdAt, int attempts, String error) {
            this.id = id;
            this.collection = collection;
            this.kind = kind;
            this.payload = payload;
            this.objectHash = objectHash;
            this.createdAt = createdAt;
            this.attempts = attempts;
            this.error = error;
        }

        /** Whether this row is waiting rather than parked. */
        boolean pending() {
            return error == null;
        }
    }

    /**
     * Appends one action, with the body it carries.
     *
     * <p>The producer's transaction of section 15.1, in its order: the
     * collection ensured so the foreign key has a target, the body
     * written to the blob directory and indexed, the pin taken because
     * the row about to name that body is a pointer like any other, and
     * the row appended last. Without the pin the body sits at refcount
     * zero and the next collector unlinks a message that has not been
     * sent.
     */
    long enqueue(String collection, String accountEmail, String kind, JSONObject payload,
            byte[] body) {
        String account = accounts.idOf(accountEmail);
        String hash = body == null ? null : PimdirHash.of(body);

        if (hash != null) {
            try {
                blobs.put(hash, body);
            } catch (IOException error) {
                throw new IllegalStateException("Could not store the body " + hash, error);
            }
        }

        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            run(db, "ENSURE_COLLECTION", values("collection", collection, "account", account));

            if (hash != null) {
                run(db, "STORE_OBJECT", values("hash", hash, "size", body.length));
                run(db, "PIN_OBJECT", values("hash", hash));
            }

            run(db, "ENQUEUE_ACTION", values(
                    "producer", PRODUCER,
                    "collection", collection,
                    "action", kind,
                    "payload", payload.toString(),
                    "object_hash", hash));

            long id = lastId(db);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    /** One collection's pending actions, oldest first (section 15.4). */
    List<Action> pending(String collection) {
        return load("LOAD_PENDING_ACTIONS", values("collection", collection), collection, null);
    }

    /** Every pending action in the store, in the append order a drain owes them. */
    List<Action> pending() {
        return load("LIST_PENDING_ACTIONS", values(), null, null);
    }

    /**
     * Every parked action, which is what a failed send became.
     *
     * <p>The canonical listing is an operator's: it says what failed and
     * why, and carries no {@code object_hash}, having no use for the body.
     * This app does have one, a refused message still being a message
     * somebody wrote and can still open, so the pin is read back beside
     * it. It is the one column here that does not come from a statement.
     */
    List<Action> parked() {
        List<Action> parked = load("LOAD_PARKED_ACTIONS", values(), null, "error");

        List<Action> whole = new ArrayList<>(parked.size());
        for (Action action : parked) {
            whole.add(
                    new Action(
                            action.id,
                            action.collection,
                            action.kind,
                            action.payload,
                            pinOf(action.id),
                            action.createdAt,
                            action.attempts,
                            action.error));
        }
        return whole;
    }

    /** The body one row pins, or null when it pins none. */
    private String pinOf(long id) {
        try (Cursor cursor =
                store.getReadableDatabase()
                        .rawQuery(
                                "SELECT object_hash FROM queue WHERE id = ?",
                                new String[] {String.valueOf(id)})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** The body one action carries, or null when the store holds none. */
    byte[] body(String hash) {
        if (hash == null) {
            return null;
        }
        try {
            byte[] body = blobs.get(hash);
            if (body == null) {
                Log.w("pimalaya", "missing blob for the queued body " + hash);
            }
            return body;
        } catch (IOException error) {
            Log.w("pimalaya", "could not read the queued body " + hash, error);
            return null;
        }
    }

    /**
     * Removes one row and releases the body it pinned.
     *
     * <p>Section 15.5's cancel, which is both acknowledging an intent
     * that was carried out and withdrawing one nobody wants any more:
     * the row is gone either way, and the difference is only who asked.
     * Returning the pin in the same transaction is the invariant, the
     * body being unreferenced from the moment the row leaves.
     */
    void acknowledge(long id) {
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            String hash = null;
            PimdirSql.Bound bound = PimdirSql.bind("CANCEL_ACTION", values("id", id));
            try (Cursor cursor = db.rawQuery(bound.sql, strings(bound.args))) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    hash = cursor.getString(0);
                }
            }

            if (hash != null) {
                items.adjustRefcount(db, hash, null);
                items.collectGarbage(db);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Parks one action: the attempt counted, the reason recorded, the
     * row taken out of the drain and left where somebody can see it.
     *
     * <p>For a failure that is the store's or the server's final word.
     * Nothing retries a parked row on its own, which is the whole point:
     * a message the server refuses would otherwise be offered to it
     * again on every sync, for ever.
     */
    void park(long id, String reason) {
        SQLiteDatabase db = store.getWritableDatabase();
        run(db, "PARK_ACTION", values("id", id, "error", reason));
    }

    /**
     * Counts one failed attempt and leaves the row pending, for a
     * failure of the environment rather than of the message.
     */
    void bumpAttempts(long id) {
        SQLiteDatabase db = store.getWritableDatabase();
        run(db, "BUMP_ATTEMPTS", values("id", id));
    }

    /** Runs one canonical statement with the values it names. */
    private static void run(SQLiteDatabase db, String name, java.util.Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        db.execSQL(bound.sql, bound.args);
    }

    /**
     * Reads one canonical listing into actions.
     *
     * <p>The three listings differ in their columns, so each is read by
     * name rather than by position: the store-wide drain carries the
     * collection and the parked one carries the error, and neither is
     * where the per-collection read would look for it.
     */
    private List<Action> load(String name, java.util.Map<String, Object> values,
            String collection, String error) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);

        List<Action> actions = new ArrayList<>();
        try (Cursor cursor =
                store.getReadableDatabase().rawQuery(bound.sql, strings(bound.args))) {
            while (cursor.moveToNext()) {
                actions.add(
                        new Action(
                                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                                collection != null ? collection : text(cursor, "collection"),
                                text(cursor, "action"),
                                payload(text(cursor, "payload")),
                                text(cursor, "object_hash"),
                                text(cursor, "created_at"),
                                cursor.getInt(cursor.getColumnIndexOrThrow("attempts")),
                                error == null ? null : text(cursor, error)));
            }
        }
        return actions;
    }

    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    /** A payload as it was written, empty when it cannot be read back. */
    private static JSONObject payload(String json) {
        if (json == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(json);
        } catch (org.json.JSONException error) {
            Log.w("pimalaya", "unreadable queue payload: " + error.getMessage());
            return new JSONObject();
        }
    }

    /**
     * The payload of a submission: what this app queues.
     *
     * <p>It carries what a listing draws as well as what the drain needs,
     * because a waiting message is not an item and there is no summary row
     * beside it to read a subject and a date off. The bytes would say all
     * of this, and parsing a message to draw a list is the one thing the
     * sort key exists to avoid.
     */
    static JSONObject submission(String from, String messageId, String subject, String sortKey) {
        try {
            return new JSONObject()
                    .put("v", VERSION)
                    .put("from", from)
                    .put("messageId", messageId)
                    .put("subject", subject)
                    .put("sortKey", sortKey);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Could not write the submission payload", error);
        }
    }

    /** Whether this app knows how to carry one action out. */
    static boolean known(Action action) {
        return SUBMIT.equals(action.kind);
    }

    /** The row the enqueue just appended. */
    private static long lastId(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT last_insert_rowid()", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private static java.util.Map<String, Object> values(Object... pairs) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    /** Bound arguments as {@code rawQuery} takes them. */
    private static String[] strings(Object[] args) {
        String[] bound = new String[args.length];
        for (int index = 0; index < args.length; index++) {
            bound[index] = args[index] == null ? null : String.valueOf(args[index]);
        }
        return bound;
    }
}
