package org.pimalaya;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.pimalaya.client.PimdirSql;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pimdir store's database, on Android's own SQLite.
 *
 * <p>The schema is not written here: it comes from io-pimdir over JNI
 * ({@link PimdirSql}), so the app runs the specification's bytes rather than a
 * transcription that would drift from it. What this class owns is the Android
 * side of the contract, which the crate deliberately does not: where the file
 * lives, when it is created, and the {@code objects/} directory beside it.
 *
 * <p>io-pimdir is taken without its {@code client} feature on purpose. Android
 * ships SQLite; compiling rusqlite in would put a second engine in every ABI of
 * a binary whose first design goal is to be small.
 *
 * <p>One store holds every account and every domain: {@code collections.kind}
 * carries the media type ({@code message/rfc822}, {@code text/vcard},
 * {@code text/calendar}) and {@code collections.account} groups by account, so
 * the merged view's two filter axes are columns rather than three databases.
 */
final class PimdirDb extends SQLiteOpenHelper {
    /** The store directory under the app's private files. */
    static final String DIRECTORY = "pimdir";

    private static final String DATABASE = "pimdir.db";

    private final File blobs;

    /** The application context, for what a driver keeps beside the store. */
    private final Context context;

    /**
     * The per-domain databases this store replaced.
     *
     * <p>They are deleted rather than left alone because they are pure caches of
     * a read-only sync: nothing in them is unrecoverable, and an install that
     * has been through the migration would otherwise carry a mail spine and a
     * calendar mirror that no code opens again. The contacts database survives,
     * since it still holds the app's own state about its books.
     */
    private static final String[] SUPERSEDED = {"events.db", "mail.db"};

    PimdirDb(Context context) {
        super(context, new File(storeDir(context), DATABASE).getAbsolutePath(), null,
                PimdirSql.version());
        this.blobs = new File(storeDir(context), "objects");
        this.context = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        for (String superseded : SUPERSEDED) {
            context.deleteDatabase(superseded);
        }
    }

    /** The store root: {@code <files>/pimdir/}, holding the db and the blobs. */
    static File storeDir(Context context) {
        File dir = new File(context.getFilesDir(), DIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create the pimdir store at " + dir);
        }
        return dir;
    }

    /**
     * The application context the store was opened in, for the settings a
     * driver reads beside it (an account's mail bound, {@link MailScope}).
     */
    Context context() {
        return context;
    }

    /** The content-addressed blob directory beside the database. */
    File blobs() {
        return blobs;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        migrate(db, 0);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        migrate(db, from);
    }

    /**
     * Runs every canonical migration above version {@code from}, in order,
     * stamping {@code store_meta} with each version reached (STORAGE §6), the
     * way io-pimdir's own runner does. The helper wraps the whole run in one
     * transaction and sets {@code user_version} once it commits.
     *
     * <p>Migrated rather than recreated: the store holds what only it holds,
     * staged edits and the outbox, which a recreate would lose.
     */
    private static void migrate(SQLiteDatabase db, int from) {
        String[] migrations = PimdirSql.migrations();

        for (int index = from; index < migrations.length; index++) {
            // NOTE: execSQL compiles one statement per call, and a migration
            // is one script whose comments contain semicolons and whose
            // triggers contain statements, so the split has to strip comments
            // and read a trigger body whole (PimdirSql.split).
            for (String statement : PimdirSql.split(migrations[index])) {
                db.execSQL(statement);
            }

            int reached = index + 1;
            if (reached == 1) {
                // NOTE: the recorded algorithm has to be the one the app
                // actually computes (PimdirHash), or every blob in the store is
                // filed under a name no other pimdir reader can verify.
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("version", reached);
                meta.put("hash_algo", PimdirHash.ALGORITHM);
                PimdirSql.Bound init = PimdirSql.bind("INIT_STORE_META", meta);
                db.execSQL(init.sql, init.args);
            } else {
                db.execSQL(
                        "UPDATE store_meta SET version = ? WHERE id = 1", new Object[] {reached});
            }
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // Foreign keys are off by default in SQLite and the schema leans on
        // them: bindings cascade with their item, items with their collection.
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
        // NOTE: a page of mail runs a dozen statements per message, the
        // canonical ones and the store's own, and more than the default 25
        // distinct ones per pass: a cache that small recompiles them by turns.
        db.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        reconcileDraftShape(db);
    }

    /**
     * Reconciles the store's shape with the canonical schema's, for a store
     * already created at version 1 (SPEC.md §6, the draft allowance).
     *
     * <p>While the spec is a draft, version 1 is not frozen: a schema change may
     * be folded into the initial migration rather than added as version 2. Such
     * a store is not detectably out of date, its {@code user_version} already
     * matching, so {@link #onUpgrade} never fires and the drift would surface
     * later as a query error. §6 asks an implementation to reconcile the shape
     * on open or refuse the store; this reconciles, which is what io-pimdir's
     * own client does.
     *
     * <p>Read from the canonical DDL rather than from a transcribed list of
     * folded columns, which is the whole reason the schema crosses the JNI
     * boundary ({@link PimdirSql}): the crate's list lives behind its
     * {@code client} feature, and a copy of it here goes stale the first time
     * a draft folds a column in or back out, with nothing to notice.
     */
    private static void reconcileDraftShape(SQLiteDatabase db) {
        Map<String, String> existing = heldObjects(db);

        // The tables first, columns and all: an index and a trigger name the
        // columns they read, so creating one over a table that has not been
        // widened yet fails on a column the schema declares and the store does
        // not hold yet.
        for (String statement : PimdirSql.schema()) {
            if (statement.trim().toUpperCase().startsWith("CREATE TABLE")
                    && !existing.containsKey(declaredName(statement))) {
                db.execSQL(statement);
            }
        }

        // A table a later draft folded out (the probes of SYNC §3, before
        // every member arrived named) goes, its rows being what the next
        // listing restates. The platform's own tables are not the schema's
        // to judge.
        Map<String, Map<String, String>> canonical = canonicalColumns();
        for (Map.Entry<String, String> held : existing.entrySet()) {
            String name = held.getKey();
            boolean table = held.getValue().trim().toUpperCase().startsWith("CREATE TABLE");
            if (table && !canonical.containsKey(name) && !name.startsWith("sqlite_")
                    && !name.startsWith("android_")) {
                db.execSQL("DROP TABLE IF EXISTS " + name);
            }
        }

        for (Map.Entry<String, Map<String, String>> table : canonical.entrySet()) {
            Set<String> held = columnsOf(db, table.getKey());
            // NOTE: an empty side means the question cannot be asked rather
            // than that the answer is "everything": no such table yet, or a
            // parse that read no column out of the DDL. Dropping against an
            // empty canonical set would empty the table's shape instead.
            if (held.isEmpty() || table.getValue().isEmpty()) {
                continue;
            }

            for (Map.Entry<String, String> column : table.getValue().entrySet()) {
                if (!held.contains(column.getKey()) && addable(column.getValue())) {
                    db.execSQL("ALTER TABLE " + table.getKey() + " ADD COLUMN "
                            + column.getKey() + " " + column.getValue());
                }
            }

            for (String column : held) {
                if (!table.getValue().containsKey(column)) {
                    dropColumn(db, table.getKey(), column);
                }
            }
        }

        // Then what reads them. An index and a trigger hold no rows, so one
        // whose text has moved is rebuilt rather than patched: CREATE ... IF
        // NOT EXISTS keys on the name and would leave the old plan, or the old
        // trigger body, in place.
        for (String statement : PimdirSql.schema()) {
            String name = declaredName(statement);
            if (name == null || statement.trim().toUpperCase().startsWith("CREATE TABLE")) {
                continue;
            }

            String stored = existing.get(name);
            if (stored == null || !normalized(stored).equals(normalized(statement))) {
                db.execSQL("DROP " + kindOf(statement) + " IF EXISTS " + name);
                db.execSQL(statement);
            }
        }
    }

    /**
     * Every table, index and trigger the store holds, mapping its name to the
     * text it was created with, which is what {@code sqlite_master} keeps.
     *
     * <p>Comparing that text against the canonical statement is what tells an
     * index or a trigger whose shape has moved from one that has not, without
     * a transcribed list of either.
     */
    private static Map<String, String> heldObjects(SQLiteDatabase db) {
        Map<String, String> objects = new LinkedHashMap<>();
        try (Cursor cursor =
                db.rawQuery("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL", null)) {
            while (cursor.moveToNext()) {
                objects.put(cursor.getString(0), cursor.getString(1));
            }
        }
        return objects;
    }

    /** The name a {@code CREATE} statement declares, or null for anything else. */
    private static String declaredName(String statement) {
        String[] tokens = statement.trim().split("\\s+|\\(");
        if (tokens.length < 3 || !tokens[0].equalsIgnoreCase("CREATE")) {
            return null;
        }

        // A UNIQUE index names itself one token later than the others do.
        int kind = tokens[1].equalsIgnoreCase("UNIQUE") ? 2 : 1;
        if (tokens.length <= kind + 1) {
            return null;
        }

        switch (tokens[kind].toUpperCase()) {
            case "TABLE":
            case "INDEX":
            case "TRIGGER":
                return tokens[kind + 1];
            default:
                return null;
        }
    }

    /** What a {@code CREATE} statement declares, as {@code DROP} spells it. */
    private static String kindOf(String statement) {
        return statement.trim().toUpperCase().startsWith("CREATE TRIGGER") ? "TRIGGER" : "INDEX";
    }

    /** A statement with its whitespace collapsed, for comparing two spellings. */
    private static String normalized(String statement) {
        return statement.replaceAll("\\s+", " ").trim();
    }

    /**
     * Every table the canonical schema declares, each mapping its column names
     * to their declarations, in declaration order.
     *
     * <p>The parse is deliberately shallow: split the {@code CREATE TABLE}
     * body on its top-level commas, take the first token of each part as the
     * name and the rest as the declaration, and skip a part opening on a table
     * constraint keyword. That is enough for a comparison against
     * {@code PRAGMA table_info}, and stops well short of understanding SQL.
     */
    private static Map<String, Map<String, String>> canonicalColumns() {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();

        for (String statement : PimdirSql.schema()) {
            String[] head = statement.split("\\(", 2);
            if (head.length < 2 || !head[0].trim().toUpperCase().startsWith("CREATE TABLE")) {
                continue;
            }

            String name = head[0].trim().split("\\s+")[2];
            Map<String, String> columns = new LinkedHashMap<>();
            for (String part : splitTopLevel(head[1].substring(0, head[1].lastIndexOf(')')))) {
                String[] tokens = part.trim().split("\\s+", 2);
                if (tokens.length < 2 || CONSTRAINTS.contains(tokens[0].toUpperCase())) {
                    continue;
                }
                columns.put(tokens[0], tokens[1].trim());
            }
            tables.put(name, columns);
        }
        return tables;
    }

    /** The keywords a {@code CREATE TABLE} part opens on when it names no column. */
    private static final Set<String> CONSTRAINTS =
            Set.of("PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "CONSTRAINT");

    /** A comma-separated list split outside its parentheses. */
    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;

        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                parts.add(body.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    /**
     * Whether a column carrying this declaration can be added to a populated
     * table: SQLite refuses one that is {@code NOT NULL} with no default,
     * having no value to write into the rows already there.
     *
     * <p>Read before any {@code CHECK}: a check is an expression, and one
     * saying {@code covered_at IS NOT NULL OR ...} constrains nothing the
     * rows already there do not satisfy.
     */
    private static boolean addable(String declaration) {
        String upper = declaration.toUpperCase();
        int check = upper.indexOf("CHECK");
        String constraints = check < 0 ? upper : upper.substring(0, check);
        return !constraints.contains("NOT NULL") || constraints.contains("DEFAULT");
    }

    /**
     * Drops a column the canonical schema no longer declares, best-effort.
     *
     * <p>{@code ALTER TABLE … DROP COLUMN} landed in SQLite 3.35, which
     * Android ships from API 34 on, so a store on an older device keeps the
     * column. That is why it is a log and not a failure: a column nothing
     * reads and nothing writes decides nothing, and refusing to open a store
     * over one would be a worse answer than carrying it.
     */
    private static void dropColumn(SQLiteDatabase db, String table, String column) {
        try {
            db.execSQL("ALTER TABLE " + table + " DROP COLUMN " + column);
        } catch (SQLException error) {
            Log.i("pimalaya", "keeping retired column " + table + "." + column, error);
        }
    }

    /** The column names the store's table actually holds; empty when there is none. */
    private static Set<String> columnsOf(SQLiteDatabase db, String table) {
        Set<String> columns = new LinkedHashSet<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(1));
            }
        }
        return columns;
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int from, int to) {
        // NOTE: a store written by a newer app has a shape this one cannot
        // read, and no migration runs backwards, so it is recreated: what it
        // staged and never pushed is lost, which only a downgrade costs. Every
        // table goes, read from the store rather than listed here: the list
        // is what would go stale.
        List<String> tables = new ArrayList<>();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table'"
                                + " AND name NOT LIKE 'sqlite_%'",
                        null)) {
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0));
            }
        }
        for (String table : tables) {
            db.execSQL("DROP TABLE IF EXISTS " + table);
        }
        migrate(db, 0);
    }
}
