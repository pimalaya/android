package org.pimalaya.pimdir;

import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.pimalaya.client.PimdirSql;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The action queue: what the app wants done beyond the store (STORAGE §15).
 *
 * <p>{@link #enqueue} is one transaction (collection, pinned body, row). A
 * submission is carried out first and acknowledged after (§15.5), so it is
 * at-least-once. Statements are io-pimdir's, bound by name.
 */
public final class PimdirQueue {
    /**
     * The app's action kind: hand this message to the submission endpoint.
     *
     * <p>An open string (§15.3); the store owes it append order, blob pinning
     * and the skip rule, nothing else.
     */
    public static final String SUBMIT = "submit";

    /** What the queue records as the origin of a row. */
    private static final String PRODUCER = "pimalaya.android";

    private final PimdirDb store;
    private final PimdirItems items;
    private final PimdirBlobs blobs;
    private final PimdirAccount accounts;

    /** The queue of {@code store}, namespacing through {@code accounts}. */
    public PimdirQueue(PimdirDb store, PimdirAccount accounts) {
        this.store = store;
        this.items = new PimdirItems(store);
        this.blobs = new PimdirBlobs(store.blobs());
        this.accounts = accounts;
    }

    /**
     * One queued action, pending or parked.
     *
     * @param id the queue row
     * @param collection the collection it is against, which scopes a read
     * @param kind the kind, an open string; this app writes {@link #SUBMIT}
     * @param payload the versioned payload, as it was written
     * @param objectHash the body the action carries, or null for none
     * @param createdAt when it was appended, as the statement stamped it
     * @param attempts how many attempts failed
     * @param error what parked it, or null while pending: the drain reads only
     *     null-error rows, so recording one parks the action
     */
    public record Action(
            long id,
            String collection,
            String kind,
            String payload,
            String objectHash,
            String createdAt,
            int attempts,
            String error) {
        /** Whether this row is waiting rather than parked. */
        public boolean pending() {
            return error == null;
        }
    }

    /**
     * One action to append.
     *
     * @param collection the collection it is against
     * @param accountEmail the account owning that collection
     * @param kind the kind, an open string
     * @param payload the versioned payload, kept as the text it is
     * @param body the body it carries, or null for none
     */
    public record Enqueued(
            String collection, String accountEmail, String kind, String payload, byte[] body) {}

    /**
     * Appends one action, with the body it carries.
     *
     * <p>§15.1's order: collection ensured (foreign key), body stored and
     * indexed, pin taken, row appended. Without the pin the next collector
     * unlinks an unsent message.
     */
    public long enqueue(Enqueued action) {
        String collection = action.collection();
        String accountEmail = action.accountEmail();
        String kind = action.kind();
        String payload = action.payload();
        byte[] body = action.body();

        String account = accounts.idOf(accountEmail);
        String hash = body == null ? null : PimdirHash.of(body);

        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            run(db, "ENSURE_COLLECTION", values("collection", collection, "account", account));

            // NOTE: filed inside the transaction, so no collector unlinks
            // the file of an object row it reads gone before this one lands
            // (PimdirItems.unlinkCollected).
            if (hash != null) {
                try {
                    blobs.put(hash, body);
                } catch (IOException error) {
                    throw new IllegalStateException("Could not store the body " + hash, error);
                }
                run(db, "STORE_OBJECT", values("hash", hash, "size", body.length));
                run(db, "PIN_OBJECT", values("hash", hash));
            }

            run(db, "ENQUEUE_ACTION", values(
                    "producer", PRODUCER,
                    "collection", collection,
                    "action", kind,
                    "payload", payload,
                    "object_hash", hash));

            long id = lastId(db);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    /** One collection's pending actions, oldest first (section 15.4). */
    public List<Action> pending(String collection) {
        return load("LOAD_PENDING_ACTIONS", values("collection", collection), collection, null);
    }

    /** Every pending action in the store, in the append order a drain owes. */
    public List<Action> pending() {
        return load("LIST_PENDING_ACTIONS", values(), null, null);
    }

    /**
     * Every parked action, which is what a failed send became.
     *
     * <p>The canonical listing has no {@code object_hash}; the pin is read
     * back beside it so a refused message can still be opened.
     */
    public List<Action> parked() {
        List<Action> parked = load("LOAD_PARKED_ACTIONS", values(), null, "error");

        List<Action> whole = new ArrayList<>(parked.size());
        for (Action action : parked) {
            whole.add(
                    new Action(
                            action.id(),
                            action.collection(),
                            action.kind(),
                            action.payload(),
                            pinOf(action.id()),
                            action.createdAt(),
                            action.attempts(),
                            action.error()));
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
    public byte[] body(String hash) {
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
     * Removes one row and releases its body's pin, in one transaction.
     *
     * <p>§15.5's cancel: acknowledging an intent carried out, or withdrawing
     * one nobody wants.
     */
    public void acknowledge(long id) {
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
     * Parks one action: attempt counted, reason recorded, out of the drain.
     *
     * <p>For the store's or server's final word: nothing retries a parked
     * row, or a refused message would be offered again on every sync.
     */
    public void park(long id, String reason) {
        SQLiteDatabase db = store.getWritableDatabase();
        run(db, "PARK_ACTION", values("id", id, "error", reason));
    }

    /**
     * Counts one failed attempt and leaves the row pending.
     *
     * <p>For a failure of the environment rather than of the message.
     */
    public void bumpAttempts(long id) {
        SQLiteDatabase db = store.getWritableDatabase();
        run(db, "BUMP_ATTEMPTS", values("id", id));
    }

    /** Runs one canonical statement with the values it names. */
    private static void run(SQLiteDatabase db, String name, java.util.Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        db.execSQL(bound.sql, bound.args);
    }

    /**
     * Reads one canonical listing into actions, its columns by name.
     *
     * <p>The three listings differ in columns: the drain carries the
     * collection, the parked one the error.
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
                                text(cursor, "payload"),
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

    /** Whether this app knows how to carry one action out. */
    static boolean known(Action action) {
        return SUBMIT.equals(action.kind());
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
