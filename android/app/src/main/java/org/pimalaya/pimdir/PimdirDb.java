package org.pimalaya.pimdir;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteCursor;
import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteOpenHelper;
import io.requery.android.database.sqlite.SQLiteProgram;
import io.requery.android.database.sqlite.SQLiteStatement;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.PimdirSql;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The pimdir store's database, on the SQLite the app bundles.
 *
 * <p>Schema from io-pimdir over JNI ({@link PimdirSql}), never transcribed.
 * Bundled SQLite for its JSON1 and the statements the app still writes by
 * hand. One store for every account and kind, at storage-draft-08: a store
 * of another draft is refused, never reconciled nor migrated ({@link
 * #refusal}), and the app reinstalled.
 */
public final class PimdirDb extends SQLiteOpenHelper {
    /** The store directory under the app's private files. */
    static final String DIRECTORY = "pimdir";

    private static final String DATABASE = "pimdir.db";

    /** The table the platform's helper keeps its locale in, not the store's. */
    private static final String PLATFORM_TABLE = "android_metadata";

    private final File blobs;

    /** The application context, for what a driver keeps beside the store. */
    private final Context context;

    /** Objects deleted in a transaction, their blobs awaiting its commit. */
    final Set<String> collected = ConcurrentHashMap.newKeySet();

    /**
     * Bodies filed ahead of the write naming them, by writes under way.
     *
     * <p>See {@link PimdirItems#pin}.
     */
    final Map<String, Integer> pinned = new HashMap<>();

    /** The store's locks, which every engine and reader over it takes. */
    private final Locks locks = new Locks();

    /** The rounds its syncs parked, for the user to settle (SYNC §5). */
    private final ParkedRounds parked = new ParkedRounds();

    /**
     * The per-domain databases this store replaced, deleted on open.
     *
     * <p>Pure caches of a read-only sync, opened by no code any more. The
     * contacts database survives: it holds the app's own book state.
     */
    private static final String[] SUPERSEDED = {"events.db", "mail.db"};

    /**
     * Opens the app's store, creating it as needed.
     *
     * <p>A caller that may meet a store of another draft checks {@link
     * #refusal} first: this one opens whatever is there.
     */
    public PimdirDb(Context context) {
        super(context, databaseFile(context).getAbsolutePath(), null, PimdirSql.version());
        this.blobs = new File(storeDir(context), "objects");
        this.context = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        for (String superseded : SUPERSEDED) {
            context.deleteDatabase(superseded);
        }
    }

    /** The store root, {@code <files>/pimdir/}: the db and the blobs. */
    static File storeDir(Context context) {
        File dir = new File(context.getFilesDir(), DIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create the pimdir store at " + dir);
        }
        return dir;
    }

    /** The store's database file, which may not exist yet. */
    private static File databaseFile(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), DATABASE);
    }

    /**
     * Why the store on disk is not opened, or null when it is (STORAGE §6).
     *
     * <p>A store another draft or another program wrote, or a damaged one, is
     * refused by io-pimdir's rules ({@link PimdirSql#refusal}) on facts read
     * off the file read-only: nothing in it is written, its journal mode
     * included, and the app has to be reinstalled. Answers {@code {refused,
     * detail}}; no file, or one declaring nothing, opens.
     */
    public static JSONObject refusal(Context context) {
        File file = databaseFile(context);
        if (!file.isFile()) {
            return null;
        }
        try {
            return PimdirSql.refusal(facts(file));
        } catch (RuntimeException | JSONException unreadable) {
            // NOTE: a file SQLite cannot even open as a database is no store
            // this app reads; refusing it keeps it as it is for the user.
            JSONObject refused = new JSONObject();
            try {
                refused.put("refused", "store-foreign");
                refused.put("detail", String.valueOf(unreadable.getMessage()));
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            return refused;
        }
    }

    /** What {@link #refusal} decides on, read off {@code file} read-only. */
    static JSONObject facts(File file) throws JSONException {
        JSONObject facts = new JSONObject();
        SQLiteDatabase db =
                SQLiteDatabase.openDatabase(
                        file.getAbsolutePath(),
                        null,
                        SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor cursor = db.rawQuery("PRAGMA user_version", null)) {
                facts.put("userVersion", cursor.moveToFirst() ? cursor.getLong(0) : 0);
            }
            facts.put("objects", new JSONObject(objects(db)));
            JSONObject meta = meta(db);
            if (meta != null) {
                facts.put("meta", meta);
            }
        } finally {
            db.close();
        }
        facts.put("canonical", new JSONObject(canonicalObjects()));
        return facts;
    }

    /**
     * The {@code store_meta} row's columns the check reads, each left out
     * where the store lacks it; null without the table or the row.
     */
    private static JSONObject meta(SQLiteDatabase db) throws JSONException {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("format", "format");
        columns.put("format_version", "formatVersion");
        columns.put("version", "version");
        columns.put("hash_algo", "hashAlgo");
        columns.put("sync_version", "syncVersion");

        StringBuilder select = new StringBuilder();
        try (Cursor cursor =
                db.rawQuery("SELECT name FROM pragma_table_info('store_meta')", null)) {
            while (cursor.moveToNext()) {
                String column = cursor.getString(0);
                if (columns.containsKey(column)) {
                    select.append(select.length() == 0 ? "" : ", ").append(column);
                }
            }
        }
        if (select.length() == 0) {
            return null;
        }

        try (Cursor cursor =
                db.rawQuery("SELECT " + select + " FROM store_meta WHERE id = 1", null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            JSONObject meta = new JSONObject();
            for (int index = 0; index < cursor.getColumnCount(); index++) {
                if (cursor.isNull(index)) {
                    continue;
                }
                String key = columns.get(cursor.getColumnName(index));
                if ("version".equals(key)) {
                    meta.put(key, cursor.getLong(index));
                } else {
                    meta.put(key, cursor.getString(index));
                }
            }
            return meta;
        }
    }

    /**
     * Every table, index and trigger a database declares, by name, with its
     * text; SQLite's own and the platform helper's left out.
     */
    private static Map<String, String> objects(SQLiteDatabase db) {
        Map<String, String> objects = new LinkedHashMap<>();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL"
                                + " AND name NOT LIKE 'sqlite_%' AND name <> ?",
                        new String[] {PLATFORM_TABLE})) {
            while (cursor.moveToNext()) {
                objects.put(cursor.getString(0), cursor.getString(1));
            }
        }
        return objects;
    }

    /** The objects the canonical migrations declare, on a scratch database. */
    private static synchronized Map<String, String> canonicalObjects() {
        if (canonical == null) {
            SQLiteDatabase scratch = SQLiteDatabase.create(null);
            try {
                for (String statement : PimdirSql.schema()) {
                    scratch.execSQL(statement);
                }
                canonical = objects(scratch);
            } finally {
                scratch.close();
            }
        }
        return canonical;
    }

    /** {@link #canonicalObjects}, once per process. */
    private static Map<String, String> canonical;

    /**
     * The application context the store was opened in.
     *
     * <p>For the settings a driver reads beside it (an account's mail bound,
     * {@code MailScope}).
     */
    public Context context() {
        return context;
    }

    /** The store's locks: its one writer, its collections' and its one sync. */
    public Locks locks() {
        return locks;
    }

    /** The rounds this store's syncs parked, process-wide. */
    public ParkedRounds parked() {
        return parked;
    }

    /** The content-addressed blob directory beside the database. */
    public File blobs() {
        return blobs;
    }

    /**
     * Where a body failing its name is moved (STORAGE §3, §7), beside {@link
     * #blobs}: never read as a body, never unlinked but by the user.
     */
    public File quarantine() {
        return new File(blobs.getParentFile(), "quarantine");
    }

    /**
     * Creates the store: 0001_init.sql and {@code init_store_meta} (STORAGE
     * §6 step 3), the helper setting {@code user_version} on commit.
     *
     * <p>While the format is a draft no migration runs on an existing store,
     * which {@link #refusal} refuses instead.
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        // NOTE: execSQL compiles one statement per call, and a migration is
        // one script whose comments contain semicolons and whose triggers
        // contain statements, so the split has to strip comments and read a
        // trigger body whole (PimdirSql.split).
        for (String statement : PimdirSql.schema()) {
            db.execSQL(statement);
        }
        JSONObject identity = PimdirSql.identity();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("version", identity.optLong("version", 1));
        meta.put("derivation", identity.optLong("derivation"));
        PimdirSql.Bound init = PimdirSql.bind("INIT_STORE_META", meta);
        db.execSQL(init.sql, init.args);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException(
                "A pimdir store at version " + from + " is never migrated to " + to
                        + " while the format is a draft");
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int from, int to) {
        // NOTE: a store a newer app wrote is refused, never recreated: what
        // it staged and never pushed lives only there (STORAGE §6).
        throw new IllegalStateException(
                "A pimdir store at version " + from + " is newer than this app's " + to);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // NOTE: a database takes its vacuum mode only while it holds no
        // table (STORAGE §4.1), so a fresh store's is set before onCreate.
        if (objects(db).isEmpty()) {
            pragma(db, "PRAGMA auto_vacuum = INCREMENTAL");
        }
        // NOTE: SQLite leaves foreign keys off and the schema's cascades
        // (bindings with items, items with collections) rely on them.
        db.setForeignKeyConstraintsEnabled(true);
        // NOTE: a page of mail runs a dozen statements per message, the
        // canonical ones and the store's own, and more than the default 25
        // distinct ones per pass: a cache that small recompiles them by turns.
        db.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE);
    }

    /**
     * Turns write-ahead logging on once the store has its tables.
     *
     * <p>Not in {@link #onConfigure}: logging gives the pool its readers, and
     * the helper reads the version (a read-only statement, so on a reader)
     * before {@link #onCreate}. That reader cached the schema of a store with
     * no table yet, and every statement it then ran failed once ("no such
     * table: collections") before SQLite reloaded the schema.
     */
    @Override
    public void onOpen(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
        // NOTE: the erasure STORAGE §11.5 asks, and a schema read as one
        // from elsewhere (§18), on the writer: a transaction holds it, where
        // a read-only statement alone would run on a reader. §4.1's
        // synchronous FULL is not set: the binding resets every connection
        // to NORMAL under write-ahead logging, and SQLite refuses the change
        // inside a transaction.
        db.beginTransaction();
        try {
            pragma(db, "PRAGMA secure_delete = ON");
            pragma(db, "PRAGMA trusted_schema = OFF");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Runs a pragma, whether or not it answers a row. */
    private static void pragma(SQLiteDatabase db, String pragma) {
        try (Cursor cursor = db.rawQuery(pragma, null)) {
            cursor.moveToFirst();
        }
    }

    /** Gives {@code collection} the attachments role. */
    public static void setRole(SQLiteDatabase db, String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("role", PimdirCollections.ATTACHMENTS_ROLE);
        PimdirSql.Bound bound = PimdirSql.bind("SET_COLLECTION_ROLE", values);
        db.execSQL(bound.sql, bound.args);
    }

    /**
     * The {@code LIKE} pattern {@code search_mail} matches.
     *
     * <p>The words with {@code %} around them, a literal {@code %}, {@code _}
     * or {@code \} escaped with {@code \}, as io-pimdir's {@code
     * reader::like_pattern}.
     */
    public static String likePattern(String words) {
        StringBuilder pattern = new StringBuilder("%");
        for (char character : words.trim().toCharArray()) {
            if (character == '%' || character == '_' || character == '\\') {
                pattern.append('\\');
            }
            pattern.append(character);
        }
        return pattern.append('%').toString();
    }

    /**
     * Runs a read binding each value as its own type.
     *
     * <p>{@code rawQuery} binds text, and {@code :seen = EXISTS (...)} has no
     * affinity: {@code '1'} never equals {@code 1}, so the unread chip would
     * match nothing. A statement reading lists goes through {@link #read}.
     */
    public static Cursor typed(SQLiteDatabase db, String sql, Object[] args) {
        return db.rawQueryWithFactory(
                (database, driver, table, query) -> {
                    bind(query, args);
                    return new SQLiteCursor(driver, table, query);
                },
                sql,
                null,
                null);
    }

    /**
     * Runs a canonical read and answers its cursor, its lists bound through
     * {@code temp.param} first (STORAGE §4.4).
     *
     * <p>A read with no list streams as {@link #typed} does; one with lists
     * is read whole inside the transaction that holds its connection, the
     * table being per connection, and answered as a copy.
     */
    public static Cursor query(SQLiteDatabase db, PimdirSql.Bound bound) {
        if (bound.params.isEmpty()) {
            return typed(db, bound.sql, bound.args);
        }
        try {
            return read(db, bound, PimdirDb::copy);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A cursor's rows copied whole, each value as its own type. */
    private static Cursor copy(Cursor cursor) {
        android.database.MatrixCursor copy =
                new android.database.MatrixCursor(cursor.getColumnNames(), cursor.getCount());
        while (cursor.moveToNext()) {
            Object[] row = new Object[cursor.getColumnCount()];
            for (int index = 0; index < row.length; index++) {
                switch (cursor.getType(index)) {
                    case Cursor.FIELD_TYPE_NULL:
                        row[index] = null;
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        row[index] = cursor.getLong(index);
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        row[index] = cursor.getDouble(index);
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        row[index] = cursor.getBlob(index);
                        break;
                    default:
                        row[index] = cursor.getString(index);
                }
            }
            copy.addRow(row);
        }
        return copy;
    }

    /** What a {@link #read} does with its cursor, read whole before it ends. */
    public interface Rows<T> {
        T read(Cursor cursor) throws JSONException;
    }

    /**
     * Runs a canonical read, its lists bound through {@code temp.param}
     * (STORAGE §4.4), and hands its cursor to {@code rows}.
     *
     * <p>The table is per connection, so the lists and the read share one:
     * the caller's transaction, else one of the read's own, the cursor read
     * whole inside it ({@code rows} returns what it read).
     */
    public static <T> T read(SQLiteDatabase db, PimdirSql.Bound bound, Rows<T> rows)
            throws JSONException {
        if (bound.params.isEmpty()) {
            try (Cursor cursor = typed(db, bound.sql, bound.args)) {
                return rows.read(cursor);
            }
        }
        boolean own = !db.inTransaction();
        if (own) {
            db.beginTransactionDeferred();
        }
        try {
            params(db, bound);
            T read;
            try (Cursor cursor = typed(db, bound.sql, bound.args)) {
                read = rows.read(cursor);
            }
            if (own) {
                db.setTransactionSuccessful();
            }
            return read;
        } finally {
            if (own) {
                db.endTransaction();
            }
        }
    }

    /**
     * Binds a statement's lists into {@code temp.param} on the connection the
     * caller's transaction holds: {@code create_param}, {@code clear_param},
     * then {@code bind_param} for each value in order.
     */
    private static void params(SQLiteDatabase db, PimdirSql.Bound bound) {
        db.execSQL(PimdirSql.split(PimdirSql.of("CREATE_PARAM"))[0]);
        db.execSQL(PimdirSql.bind("CLEAR_PARAM", Map.of()).sql);
        PimdirSql.Bound insert = PimdirSql.bind("BIND_PARAM", Map.of());
        try (SQLiteStatement statement = db.compileStatement(insert.sql)) {
            for (Map.Entry<String, List<Object>> slot : bound.params.entrySet()) {
                int position = 0;
                for (Object value : slot.getValue()) {
                    Map<String, Object> values = new HashMap<>();
                    values.put("slot", slot.getKey());
                    values.put("position", position++);
                    values.put("value", value);
                    statement.clearBindings();
                    bind(statement, PimdirSql.bind("BIND_PARAM", values).args);
                    statement.executeInsert();
                }
            }
        }
    }

    /**
     * Runs a canonical write, {@code RETURNING} dropped; answers rows changed.
     *
     * <p>Never through a cursor: a refill of its 2 MB window reruns the
     * statement, so a {@code DELETE ... RETURNING} deletes again, its later
     * rows lost. Its lists are bound first, in the caller's transaction or
     * one of its own.
     */
    public static int changed(SQLiteDatabase db, PimdirSql.Bound bound) {
        String sql = RETURNING.matcher(bound.sql).replaceFirst("");
        if (bound.params.isEmpty()) {
            try (SQLiteStatement statement = db.compileStatement(sql)) {
                bind(statement, bound.args);
                return statement.executeUpdateDelete();
            }
        }
        boolean own = !db.inTransaction();
        if (own) {
            db.beginTransaction();
        }
        try {
            params(db, bound);
            int changed;
            try (SQLiteStatement statement = db.compileStatement(sql)) {
                bind(statement, bound.args);
                changed = statement.executeUpdateDelete();
            }
            if (own) {
                db.setTransactionSuccessful();
            }
            return changed;
        } finally {
            if (own) {
                db.endTransaction();
            }
        }
    }

    /** A trailing {@code RETURNING} clause, which {@link #changed} drops. */
    private static final Pattern RETURNING =
            Pattern.compile("\\s+RETURNING\\s[^;]*;?\\s*$", Pattern.CASE_INSENSITIVE);

    private static void bind(SQLiteProgram query, Object[] args) {
        for (int index = 0; index < args.length; index++) {
            Object value = args[index];
            int position = index + 1;
            if (value == null) {
                query.bindNull(position);
            } else if (value instanceof Number) {
                query.bindLong(position, ((Number) value).longValue());
            } else if (value instanceof Boolean) {
                query.bindLong(position, ((Boolean) value) ? 1 : 0);
            } else if (value instanceof byte[]) {
                query.bindBlob(position, (byte[]) value);
            } else {
                query.bindString(position, value.toString());
            }
        }
    }
}
