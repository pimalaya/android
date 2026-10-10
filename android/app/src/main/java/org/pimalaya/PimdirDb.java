package org.pimalaya;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteOpenHelper;
import org.pimalaya.client.PimdirSql;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pimdir store's database, on the SQLite the app bundles.
 *
 * <p>The schema is not written here: it comes from io-pimdir over JNI
 * ({@link PimdirSql}), so the app runs the specification's bytes rather than a
 * transcription that would drift from it. What this class owns is the Android
 * side of the contract, which the crate deliberately does not: where the file
 * lives, when it is created, and the {@code objects/} directory beside it.
 *
 * <p>The SQLite is bundled ({@code io.requery.android.database.sqlite}, the
 * platform's binding over a newer engine): the schema needs 3.37 (STRICT
 * tables), which the platform ships only from Android 14. io-pimdir is taken
 * without its {@code client} feature on purpose: compiling rusqlite in as well
 * would put a second engine in every ABI of a binary whose first design goal
 * is to be small.
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

    /** The process's one store, null until first asked for ({@link #shared}). */
    private static PimdirDb shared;

    /** The database file this store opens. */
    private final File file;

    /**
     * The process's one store, opened once: every screen, service and job
     * shares it, so the store is one connection pool rather than one per
     * caller, each holding the file open until the collector finds it.
     * A store under another files directory (a test's) opens its own.
     */
    static synchronized PimdirDb shared(Context context) {
        File file = new File(storeDir(context), DATABASE);
        if (shared == null || !shared.file.equals(file)) {
            shared = new PimdirDb(context.getApplicationContext() == null
                    ? context
                    : context.getApplicationContext());
        }
        return shared;
    }

    PimdirDb(Context context) {
        super(context, new File(storeDir(context), DATABASE).getAbsolutePath(), null,
                PimdirSql.version());
        this.file = new File(storeDir(context), DATABASE);
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
        reconcileDraftShape(db, blobs);
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
    private static void reconcileDraftShape(SQLiteDatabase db, File blobs) {
        rebuildCollections(db);
        Map<String, String> existing = heldObjects(db);
        Set<String> mail = columnsOf(db, "mail_summary");
        boolean invitations = !mail.isEmpty() && !mail.contains("invitation");

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

        if (invitations) {
            backfillReferences(db, blobs);
        }
        FileStore.reconcileRoles(db);
    }

    /**
     * Rebuilds {@code collections} when the constraint its role is checked
     * by predates the canonical one (the {@code attachments} role, §14.3),
     * which no {@code ALTER TABLE} changes: §6's procedure, foreign keys off
     * so the drop cascades nothing, {@code legacy_alter_table} on so the
     * rename leaves the other tables' triggers alone, the rows copied, the
     * keys checked before the commit. Its indexes and triggers go with the
     * old table and come back with the reconcile's second pass.
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
     * The references a store written before automatic references owes, once:
     * every held message's invitation derived from its body (Annex A.1),
     * then every rule over the whole store (§14.2), the invitation's among
     * them. Read on {@code list_mail_page_filtered}'s keyset over every
     * mailbox; a body the blob directory lacks leaves its row as it is.
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
                try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
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

    /** Writes one held message's summary from its body when it names an invitation. */
    private static void restateInvitation(
            SQLiteDatabase db, PimdirBlobs blobs, String collection, String linkId, String hash) {
        try {
            byte[] body = blobs.get(hash);
            if (body == null) {
                return;
            }
            org.json.JSONObject summary =
                    PimdirSql.derive(PimdirSummary.MAIL, body).optJSONObject("summary");
            org.json.JSONObject mail = summary == null ? null : summary.optJSONObject("mail");
            if (mail != null && mail.has("invitation")) {
                PimdirSummary.write(db, collection, linkId, summary);
            }
        } catch (java.io.IOException | RuntimeException unreadable) {
            Log.w("pimalaya", "invitation not derived: " + linkId, unreadable);
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
     * <p>SQLite refuses to drop a column an index, a constraint or a trigger
     * still names, so such a column is kept. That is why it is a log and not a
     * failure: a column nothing reads and nothing writes decides nothing, and
     * refusing to open a store over one would be a worse answer than carrying
     * it.
     */
    private static void dropColumn(SQLiteDatabase db, String table, String column) {
        try {
            db.execSQL("ALTER TABLE " + table + " DROP COLUMN " + column);
        } catch (SQLException error) {
            Log.i("pimalaya", "keeping retired column " + table + "." + column, error);
        }
    }

    /**
     * The column names the store's table actually holds; empty when there is
     * none.
     *
     * <p>Read through a {@code SELECT}, not a bare {@code PRAGMA}: the reconcile
     * runs outside a transaction, so a read may land on another of the pool's
     * WAL connections, and only a statement that opens a read transaction
     * notices the schema the primary connection has just changed. A bare
     * pragma answers from the connection's cached schema.
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
}
