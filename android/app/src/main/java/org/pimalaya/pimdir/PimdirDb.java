package org.pimalaya.pimdir;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteCursor;
import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteOpenHelper;
import io.requery.android.database.sqlite.SQLiteProgram;
import io.requery.android.database.sqlite.SQLiteStatement;
import org.pimalaya.client.PimdirSql;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The pimdir store's database, on the SQLite the app bundles.
 *
 * <p>Schema from io-pimdir over JNI ({@link PimdirSql}), never transcribed.
 * Bundled SQLite for STRICT tables (3.37, platform only from Android 14). One
 * store for every account and kind.
 */
public final class PimdirDb extends SQLiteOpenHelper {
    /** The store directory under the app's private files. */
    static final String DIRECTORY = "pimdir";

    private static final String DATABASE = "pimdir.db";

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

    /**
     * The per-domain databases this store replaced, deleted on open.
     *
     * <p>Pure caches of a read-only sync, opened by no code any more. The
     * contacts database survives: it holds the app's own book state.
     */
    private static final String[] SUPERSEDED = {"events.db", "mail.db"};

    /** Opens the app's store, creating or migrating it as needed. */
    public PimdirDb(Context context) {
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

    /** The store root, {@code <files>/pimdir/}: the db and the blobs. */
    static File storeDir(Context context) {
        File dir = new File(context.getFilesDir(), DIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create the pimdir store at " + dir);
        }
        return dir;
    }

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

    /** The content-addressed blob directory beside the database. */
    public File blobs() {
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
     * Runs every canonical migration above {@code from}, in order.
     *
     * <p>Stamps {@code store_meta} per version (STORAGE §6), as io-pimdir does;
     * the helper sets {@code user_version} on commit. Migrated, never
     * recreated: staged edits and the outbox live only here.
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
        // NOTE: SQLite leaves foreign keys off and the schema's cascades
        // (bindings with items, items with collections) rely on them.
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
        // NOTE: a page of mail runs a dozen statements per message, the
        // canonical ones and the store's own, and more than the default 25
        // distinct ones per pass: a cache that small recompiles them by turns.
        db.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        reconcileDraftShape(db, blobs);
    }

    /**
     * Reconciles a version-1 store's shape with the canonical schema's.
     *
     * <p>A draft folds changes into version 1 (SPEC.md §6) unseen by {@link
     * #onUpgrade}; reconciled as io-pimdir's client does, from the canonical
     * DDL.
     */
    private static void reconcileDraftShape(SQLiteDatabase db, File blobs) {
        rebuildCollections(db);
        Map<String, String> existing = heldObjects(db);
        Set<String> mail = columnsOf(db, "mail_summary");
        boolean invitations = !mail.isEmpty() && !mail.contains("invitation");

        // NOTE: tables are widened first: an index or trigger over a column
        // the store does not hold yet fails to create.
        for (String statement : PimdirSql.schema()) {
            if (statement.trim().toUpperCase().startsWith("CREATE TABLE")
                    && !existing.containsKey(declaredName(statement))) {
                db.execSQL(statement);
            }
        }

        // NOTE: a table a later draft folded out (SYNC §3's probes) goes, the
        // next listing restating its rows; platform tables are left alone.
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

        // NOTE: a moved index or trigger is rebuilt: CREATE ... IF NOT EXISTS
        // keys on the name and would keep the old plan or body.
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

        if (invitations) {
            backfillReferences(db, blobs);
        }
        reconcileRoles(db);
    }

    /**
     * Gives the attachments role to an older store's attachments collections.
     *
     * <p>Found by the id {@code FileStore} files them under; once, on open,
     * after the role's constraint takes it.
     */
    private static void reconcileRoles(SQLiteDatabase db) {
        List<String> missing = new ArrayList<>();
        String sql = PimdirSql.split(PimdirSql.of("LIST_COLLECTIONS"))[0];
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (PimdirSummary.FILE.equals(cursor.getString(2)) && cursor.isNull(9)
                        && id.endsWith(PimdirAccount.SEPARATOR + PimdirCollections.ATTACHMENTS)) {
                    missing.add(id);
                }
            }
        }
        for (String id : missing) {
            setRole(db, id);
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
     * Rebuilds {@code collections} whose role check predates §14.3's.
     *
     * <p>§6's procedure: foreign keys off (no cascade), {@code
     * legacy_alter_table} on (other triggers stay), rows copied, keys checked
     * before commit.
     */
    private static void rebuildCollections(SQLiteDatabase db) {
        String create = null;
        for (String statement : PimdirSql.schema()) {
            if (statement.trim().toUpperCase().startsWith("CREATE TABLE")
                    && "collections".equals(declaredName(statement))) {
                create = statement;
            }
        }
        String stored = heldObjects(db).get("collections");
        if (create == null || stored == null || !create.contains("'attachments'")
                || stored.contains("'attachments'")) {
            return;
        }

        Set<String> columns = columnsOf(db, "collections");
        columns.retainAll(canonicalColumns().get("collections").keySet());
        String list = String.join(", ", columns);
        db.setForeignKeyConstraintsEnabled(false);
        try {
            db.beginTransaction();
            try {
                db.execSQL("PRAGMA legacy_alter_table = ON");
                db.execSQL(create.replaceFirst("collections", "collections_rebuild"));
                db.execSQL("INSERT INTO collections_rebuild (" + list + ") SELECT " + list
                        + " FROM collections");
                db.execSQL("DROP TABLE collections");
                db.execSQL("ALTER TABLE collections_rebuild RENAME TO collections");
                db.execSQL("PRAGMA legacy_alter_table = OFF");
                try (Cursor broken = db.rawQuery("PRAGMA foreign_key_check", null)) {
                    if (broken.moveToFirst()) {
                        throw new IllegalStateException(
                                "The collections rebuild left a foreign key dangling");
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            db.setForeignKeyConstraintsEnabled(true);
        }
    }

    /**
     * Backfills the automatic references an older store owes, once.
     *
     * <p>Each held message's invitation from its body (Annex A.1), then every
     * rule store-wide (§14.2), over {@code list_mail_page_filtered}'s keyset. A
     * body missing from the blobs leaves its row.
     */
    private static void backfillReferences(SQLiteDatabase db, File blobs) {
        List<String> mailboxes = new ArrayList<>();
        try (Cursor cursor =
                db.rawQuery(PimdirSql.split(PimdirSql.of("LIST_COLLECTIONS"))[0], null)) {
            while (cursor.moveToNext()) {
                if (PimdirSummary.MAIL.equals(cursor.getString(2))) {
                    mailboxes.add(cursor.getString(0));
                }
            }
        }
        PimdirBlobs store = new PimdirBlobs(blobs);
        db.beginTransaction();
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("collections", new org.json.JSONArray(mailboxes).toString());
            values.put("limit", 500);
            while (!mailboxes.isEmpty()) {
                PimdirSql.Bound bound = PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values);
                List<String[]> held = new ArrayList<>();
                int read = 0;
                try (Cursor cursor = typed(db, bound.sql, bound.args)) {
                    while (cursor.moveToNext()) {
                        read++;
                        if (!cursor.isNull(4)) {
                            held.add(new String[] {
                                cursor.getString(0), cursor.getString(2), cursor.getString(4)
                            });
                        }
                        values.put("after_key", cursor.isNull(5) ? "" : cursor.getString(5));
                        values.put("after_seq", cursor.getLong(1));
                        values.put("after_collection", cursor.getString(0));
                    }
                }
                for (String[] row : held) {
                    restateInvitation(db, store, row[0], row[1], row[2]);
                }
                if (read < 500) {
                    break;
                }
            }
            PimdirSummary.linkAll(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Restates a held message's summary from a body naming an invitation. */
    private static void restateInvitation(
            SQLiteDatabase db, PimdirBlobs blobs, String collection, String linkId, String hash) {
        try {
            byte[] body = blobs.get(hash);
            if (body == null) {
                return;
            }
            org.json.JSONObject summary =
                    PimdirSql.derive(PimdirSummary.MAIL, body).summary();
            org.json.JSONObject mail = summary == null ? null : summary.optJSONObject("mail");
            if (mail != null && mail.has("invitation")) {
                PimdirSummary.write(db, collection, linkId, summary);
            }
        } catch (java.io.IOException | RuntimeException unreadable) {
            Log.w("pimalaya", "invitation not derived: " + linkId, unreadable);
        }
    }
    /**
     * Every table, index and trigger held, by name, with its creating text.
     *
     * <p>Compared against the canonical statement, it tells a moved index or
     * trigger from an unchanged one.
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

    /** The name a {@code CREATE} statement declares, or null otherwise. */
    private static String declaredName(String statement) {
        String[] tokens = statement.trim().split("\\s+|\\(");
        if (tokens.length < 3 || !tokens[0].equalsIgnoreCase("CREATE")) {
            return null;
        }

            // NOTE: a UNIQUE index names itself one token later.
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

    /** A statement with whitespace collapsed, to compare two spellings. */
    private static String normalized(String statement) {
        return statement.replaceAll("\\s+", " ").trim();
    }

    /**
     * Every canonical table's columns, name to declaration, in order.
     *
     * <p>A shallow parse (top-level commas, first token the name, constraint
     * parts skipped): enough to compare with {@code PRAGMA table_info}.
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

    /** Keywords a {@code CREATE TABLE} part opens on when naming no column. */
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
     * Whether a column so declared can be added to a populated table.
     *
     * <p>SQLite refuses {@code NOT NULL} without a default. Read before any
     * {@code CHECK}, which may well hold for every existing row.
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
     * <p>One still named by an index, constraint or trigger is kept and logged:
     * an unused column decides nothing, refusing the store would be worse.
     */
    private static void dropColumn(SQLiteDatabase db, String table, String column) {
        try {
            db.execSQL("ALTER TABLE " + table + " DROP COLUMN " + column);
        } catch (SQLException error) {
            Log.i("pimalaya", "keeping retired column " + table + "." + column, error);
        }
    }

    /**
     * The column names the store's table holds; empty when there is none.
     *
     * <p>Through a {@code SELECT}, not a bare {@code PRAGMA}: outside a
     * transaction a read may hit another WAL connection, which sees the new
     * schema only in a read transaction.
     */
    private static Set<String> columnsOf(SQLiteDatabase db, String table) {
        Set<String> columns = new LinkedHashSet<>();
        try (Cursor cursor =
                db.rawQuery("SELECT name FROM pragma_table_info(?)", new String[] {table})) {
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(0));
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
     * match nothing.
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
     * Runs a canonical write, {@code RETURNING} dropped; answers rows changed.
     *
     * <p>Never through a cursor: a refill of its 2 MB window reruns the
     * statement, so a {@code DELETE ... RETURNING} deletes again, its later
     * rows lost.
     */
    public static int changed(SQLiteDatabase db, PimdirSql.Bound bound) {
        String sql = RETURNING.matcher(bound.sql).replaceFirst("");
        try (SQLiteStatement statement = db.compileStatement(sql)) {
            bind(statement, bound.args);
            return statement.executeUpdateDelete();
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
