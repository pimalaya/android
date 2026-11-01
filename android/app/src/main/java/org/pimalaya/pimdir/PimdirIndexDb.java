package org.pimalaya.pimdir;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.PimdirIndex;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The search index, index.db beside the store (pimdir SEARCH.md), on the
 * platform's SQLite.
 *
 * <p>Thin: the bridge holds the index's schema, statements and logic
 * ({@link PimdirIndex}) and sends the SQL it runs ({@link #serve}); this
 * class holds the file, its one handle and the runs. The handle has the
 * store attached as {@code store}, which costs it write-ahead logging on the
 * platform, so index.db runs in rollback-journal mode and its pool holds one
 * connection, which this class alone uses, one run at a time ({@link
 * #run}): a refresh commits a run per {@link #BATCH} rows of the store's
 * feed, and a query waits at most one of them. The only writer of index.db,
 * under the store's index lock ({@link Locks#index}).
 *
 * <p>A run is a deferred transaction the bridge's statements open and
 * commit, never one of the platform's: below API 35 those are immediate or
 * exclusive, and take the write lock of every attached database, which
 * would hold the store's writer off for the whole run.
 */
public final class PimdirIndexDb implements PimdirIndex.Steps {
    /** The index's file in the store directory (SEARCH §2). */
    static final String DATABASE = "index.db";

    /** The store's feed rows one refresh run folds, one transaction each. */
    static final int BATCH = 200;

    private final PimdirDb store;

    private final File file;

    /** The one handle, null until opened, while refused or after a failure. */
    private SQLiteDatabase db;

    /** Why index.db is not used (another shape, a later version), or null. */
    private volatile String refused;

    /** Runs the refreshes one after another, off the caller's thread. */
    private final ExecutorService refresher =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "pimalaya-index");
                        thread.setDaemon(true);
                        return thread;
                    });

    /** Whether a refresh waits to start, so requests meanwhile make one. */
    private final AtomicBoolean queued = new AtomicBoolean();

    /** The index of {@code store}, opened on first use. */
    public PimdirIndexDb(PimdirDb store) {
        this.store = store;
        this.file = new File(store.blobs().getParentFile(), DATABASE);
    }

    /** Why index.db is not used (another shape, a later version), or null. */
    public String refused() {
        return refused;
    }

    /**
     * Runs {@code body} in one deferred transaction on the handle, opened
     * and built as needed; answers null for an index refused. A failure
     * closes the handle, which rolls the run back, the next run opening it
     * again.
     */
    synchronized <T> T run(Function<SQLiteDatabase, T> body) {
        SQLiteDatabase handle = open();
        if (handle == null) {
            return null;
        }
        boolean done = false;
        try {
            handle.execSQL(PimdirIndex.statement("BEGIN_RUN"));
            T answer = body.apply(handle);
            handle.execSQL(PimdirIndex.statement("COMMIT_RUN"));
            done = true;
            return answer;
        } finally {
            if (!done) {
                close();
            }
        }
    }

    /**
     * The handle, opened and built as needed (SEARCH §2): an empty index.db
     * built, an older one or one of another store deleted and built again,
     * one of another shape or a later version left as it is and refused.
     */
    private SQLiteDatabase open() {
        if (db != null || refused != null) {
            return db;
        }
        db = connect();
        try {
            JSONObject held = PimdirIndex.held(this);
            String state = held.optString("held");
            if (state.equals("refused")) {
                refused = held.optString("detail");
                Log.w("pimalaya", "search index refused: " + refused);
                close();
                return null;
            }
            if (state.equals("rebuild")) {
                Log.i("pimalaya", "search index rebuilt: " + held.optString("reason"));
                close();
                SQLiteDatabase.deleteDatabase(file);
                db = connect();
                state = "empty";
            }
            if (state.equals("empty")) {
                db.execSQL(PimdirIndex.statement("BEGIN_RUN"));
                PimdirIndex.init(this);
                db.execSQL(PimdirIndex.statement("COMMIT_RUN"));
            }
        } catch (RuntimeException failed) {
            close();
            throw failed;
        }
        return db;
    }

    /** Opens index.db, creating it, the store attached as {@code store}. */
    private SQLiteDatabase connect() {
        // NOTE: the store's helper creates pimdir.db on its first open, and
        // the index may be asked before anything else opened it, as a fresh
        // install's first refresh is: an attach of a store with no schema
        // yet reads no table (no such table: store.store_meta).
        store.getWritableDatabase();
        SQLiteDatabase opened =
                SQLiteDatabase.openDatabase(
                        file.getAbsolutePath(),
                        null,
                        SQLiteDatabase.OPEN_READWRITE
                                | SQLiteDatabase.CREATE_IF_NECESSARY
                                // NOTE: no android_metadata table: index.db
                                // holds the shape's tables alone (SEARCH §2).
                                | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        // NOTE: the attach turns logging off on the handle (execSQL), and a
        // platform turning it on by default for compatibility would make
        // index.db a WAL database first: off before anything is written.
        opened.disableWriteAheadLogging();
        opened.setForeignKeyConstraintsEnabled(true);
        File pimdir = new File(store.blobs().getParentFile(), "pimdir.db");
        opened.execSQL(
                PimdirIndex.statement("ATTACH_STORE"), new Object[] {pimdir.getAbsolutePath()});
        return opened;
    }

    /** Asks for a refresh, run on the index's thread; requests coalesce. */
    public void refreshSoon() {
        if (queued.compareAndSet(false, true)) {
            refresher.execute(
                    () -> {
                        queued.set(false);
                        try {
                            refresh();
                        } catch (RuntimeException failed) {
                            Log.w("pimalaya", "search index refresh failed", failed);
                        }
                    });
        }
    }

    /**
     * Folds the store's feed into the index until it is exhausted (SEARCH
     * §4), one run per {@link #BATCH} rows; answers the last run's report,
     * null for an index refused.
     */
    public JSONObject refresh() {
        long folded = 0;
        JSONObject report;
        synchronized (store.locks().index) {
            String blobs = store.blobs().getAbsolutePath();
            long now = System.currentTimeMillis() / 1000;
            // NOTE: floating times and dates read where the device is, so an
            // agenda draws them at the wall time they state (SEARCH §7); a
            // zone other than the one the index records re-expands them.
            String zone = java.util.TimeZone.getDefault().getID();
            boolean first = true;
            do {
                boolean missing = first;
                report =
                        run(
                                handle ->
                                        PimdirIndex.refresh(
                                                this, blobs, now, BATCH, missing, zone));
                if (report == null) {
                    return null;
                }
                folded += report.optLong("folded");
                first = false;
            } while (!report.optBoolean("exhausted", true));
        }
        Runnable listener = this.folded;
        if (folded > 0 && listener != null) {
            listener.run();
        }
        return report;
    }

    /** Told after a refresh folded anything, on the refresh's thread. */
    private volatile Runnable folded;

    /**
     * Runs {@code listener} after each refresh that folded a row of the
     * store's feed, on the refresh's thread: what a list drawn from the
     * index reloads on. One listener, null for none.
     */
    public void onFolded(Runnable listener) {
        this.folded = listener;
    }

    /**
     * The occurrences of {@code [start, end)}, RFC 3339 instants in UTC, of
     * every live calendar item (SEARCH §7), with the row each draws and what
     * the window could not see: {@code {occurrences, horizon?, horizonLeft,
     * cut, floatingZone}}, or null for an index refused.
     */
    public JSONObject agenda(String start, String end) {
        return run(handle -> PimdirIndex.agenda(this, start, end, PimdirStorage.SERVER));
    }

    /**
     * Every hit of a query (SEARCH §8), ordered {@code date} or {@code
     * name}: {@code {hits}}, {@code {refused}} for a query the language
     * refuses, or null for an index refused.
     */
    public JSONObject hits(String query, String order) {
        long now = System.currentTimeMillis() / 1000;
        return run(handle -> PimdirIndex.hits(this, query, order, now));
    }

    /**
     * What the index recorded of every card's body (SEARCH §6): {@code
     * {cards}}, or null for an index refused.
     */
    public JSONObject cardFacts() {
        return run(handle -> PimdirIndex.cardFacts(this));
    }

    /**
     * One page of a query's hits, each with its placements and a snippet of
     * its body: {@code {hits, more}}, {@code {refused}}, or null for an
     * index refused.
     */
    public JSONObject search(String query, String order, int limit, int offset) {
        long now = System.currentTimeMillis() / 1000;
        String blobs = store.blobs().getAbsolutePath();
        return run(
                handle -> PimdirIndex.search(this, blobs, query, order, limit, offset, now));
    }

    /**
     * Runs the bridge's steps in order on the handle, inside the run, each
     * read answering its rows.
     */
    @Override
    public String serve(String request) {
        try {
            JSONArray steps = new JSONObject(request).getJSONArray("steps");
            JSONArray rows = new JSONArray();
            for (int index = 0; index < steps.length(); index++) {
                JSONObject step = steps.getJSONObject(index);
                Object[] args = args(step.getJSONArray("args"));
                String sql = step.getString("sql");
                if (step.getBoolean("read")) {
                    rows.put(read(db, sql, args));
                } else {
                    try (SQLiteStatement statement = db.compileStatement(sql)) {
                        bind(statement, args);
                        statement.executeUpdateDelete();
                    }
                    rows.put(new JSONArray());
                }
            }
            return new JSONObject().put("rows", rows).toString();
        } catch (JSONException | RuntimeException failed) {
            Log.w("pimalaya", "search index step failed", failed);
            try {
                return new JSONObject()
                        .put("error", String.valueOf(failed.getMessage()))
                        .toString();
            } catch (JSONException impossible) {
                return "{\"error\": \"search index step failed\"}";
            }
        }
    }

    /** A step's values, each as its own type. */
    private static Object[] args(JSONArray values) throws JSONException {
        Object[] args = new Object[values.length()];
        for (int index = 0; index < args.length; index++) {
            Object value = values.get(index);
            if (value == JSONObject.NULL) {
                args[index] = null;
            } else if (value instanceof Double || value instanceof Float) {
                args[index] = ((Number) value).doubleValue();
            } else if (value instanceof Number) {
                args[index] = ((Number) value).longValue();
            } else {
                args[index] = value.toString();
            }
        }
        return args;
    }

    /** The rows of one read, each value as its own type. */
    private static JSONArray read(SQLiteDatabase handle, String sql, Object[] args)
            throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = PimdirDb.typed(handle, sql, args)) {
            while (cursor.moveToNext()) {
                JSONArray row = new JSONArray();
                for (int column = 0; column < cursor.getColumnCount(); column++) {
                    switch (cursor.getType(column)) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            row.put(cursor.getLong(column));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            row.put(cursor.getDouble(column));
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            row.put(cursor.getString(column));
                            break;
                        default:
                            row.put(JSONObject.NULL);
                    }
                }
                rows.put(row);
            }
        }
        return rows;
    }

    private static void bind(SQLiteStatement statement, Object[] args) {
        for (int index = 0; index < args.length; index++) {
            Object value = args[index];
            int position = index + 1;
            if (value == null) {
                statement.bindNull(position);
            } else if (value instanceof Double) {
                statement.bindDouble(position, (Double) value);
            } else if (value instanceof Long) {
                statement.bindLong(position, (Long) value);
            } else {
                statement.bindString(position, value.toString());
            }
        }
    }

    /**
     * Closes the handle, rolling back a run under way; the index opens again
     * on next use.
     */
    public synchronized void close() {
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
