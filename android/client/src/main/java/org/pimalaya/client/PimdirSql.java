package org.pimalaya.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The canonical pimdir SQL, as the pimdir specification defines it.
 *
 * <p>The app runs Android's own SQLite rather than a driver of its own: the
 * platform ships one, and compiling a second engine into every ABI would work
 * against the smallest-binary goal the app is built around. So io-pimdir is
 * taken <em>without</em> its {@code client} feature, contributing the schema and
 * the statements while the execution stays on {@code android.database.sqlite}.
 *
 * <p>The statements cross the JNI boundary rather than being transcribed here.
 * Transcribing sixty of them would work exactly once: the next spec revision
 * would leave Java holding a stale copy with nothing to notice, and the whole
 * reason to depend on the crate is that it is the canonical copy.
 */
public final class PimdirSql {
    private static Map<String, String> statements;
    private static String[] migrations;

    /** The schema version the compiled-in SQL is. */
    public static int version() {
        return Native.pimdirVersion();
    }

    /**
     * The statement registered under {@code name} (its Rust constant name, such
     * as {@code LIST_ITEMS_PAGE}).
     *
     * @throws PimalayaException when no such statement exists, which means the
     *     caller and the crate disagree about the spec rather than that a query
     *     failed. Failing loudly here beats passing null into SQLite.
     */
    public static synchronized String of(String name) {
        if (statements == null) {
            statements = load();
        }
        String sql = statements.get(name);
        if (sql == null) {
            throw new PimalayaException("Unknown pimdir statement '" + name + "'");
        }
        return sql;
    }

    /** Every statement, by name; unmodifiable. */
    public static synchronized Map<String, String> all() {
        if (statements == null) {
            statements = load();
        }
        return statements;
    }

    /**
     * Every migration split into the individual statements {@code execSQL}
     * takes, in order: Android compiles one statement per call, so a schema
     * that is one script has to be cut into them ({@link #split}).
     */
    public static String[] schema() {
        List<String> statements = new ArrayList<>();
        for (String migration : migrations()) {
            statements.addAll(Arrays.asList(split(migration)));
        }
        return statements.toArray(new String[0]);
    }

    /** Every canonical migration in order, as its own script (STORAGE §6). */
    public static synchronized String[] migrations() {
        if (migrations == null) {
            migrations = loadMigrations();
        }
        return migrations.clone();
    }

    /**
     * A canonical statement with its {@code :name} parameters rewritten to the
     * positional ones Android takes, and the values bound in the order they
     * occur, drawn from {@code values} by name.
     *
     * <p>Android's SQLite binding has no named parameters, so a caller either
     * rewrites the statement or writes its own. Rewriting is what keeps the
     * column lists in the crate: the caller names the values it has and never
     * repeats the statement's own column order. A name the map does not carry
     * binds NULL, and one it carries more than once in the statement binds the
     * same value each time.
     *
     * <p>The statement is taken comment-free, since the canonical files
     * document themselves and a parameter is spelled the way prose spells a
     * colon.
     */
    public static Bound bind(String name, Map<String, Object> values) {
        String[] statements = split(of(name));
        String sql = statements.length == 0 ? "" : statements[0];
        StringBuilder rewritten = new StringBuilder(sql.length());
        List<Object> args = new ArrayList<>();
        boolean inString = false;

        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);

            if (current == '\'') {
                inString = !inString;
            } else if (!inString && current == ':' && index + 1 < sql.length()
                    && isNameChar(sql.charAt(index + 1))) {
                int end = index + 1;
                while (end < sql.length() && isNameChar(sql.charAt(end))) {
                    end++;
                }
                args.add(values.get(sql.substring(index + 1, end)));
                rewritten.append('?');
                index = end - 1;
                continue;
            }

            rewritten.append(current);
        }

        return new Bound(rewritten.toString(), args.toArray());
    }

    /** A statement with its arguments, as {@code execSQL} and friends take them. */
    public static final class Bound {
        /** The statement, its parameters positional. */
        public final String sql;

        /** The values, in the order the statement's parameters occur. */
        public final Object[] args;

        Bound(String sql, Object[] args) {
            this.sql = sql;
            this.args = args;
        }
    }

    private static boolean isNameChar(char current) {
        return Character.isLetterOrDigit(current) || current == '_';
    }

    /**
     * A multi-statement script split into the individual statements
     * {@code execSQL} takes, comments removed.
     *
     * <p>Splitting on {@code ';'} alone is wrong twice over. The canonical
     * schema documents itself and its comments contain semicolons
     * (<em>"handing out the next item `seq`; only ever increases"</em>), so
     * comments go first, tracking string literals on the way. And a trigger
     * body is statements: its own semicolons end nothing, so one ends at the
     * {@code END} that closes it and nowhere earlier.
     */
    public static String[] split(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(sql.length());
        boolean inString = false;
        boolean inComment = false;

        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);

            if (inComment) {
                if (character == '\n') {
                    inComment = false;
                    current.append(character);
                }
                continue;
            }

            if (inString) {
                current.append(character);
                if (character == '\'') {
                    inString = false;
                }
                continue;
            }

            if (character == '\'') {
                inString = true;
                current.append(character);
                continue;
            }

            if (character == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                inComment = true;
                index++;
                continue;
            }

            if (character == ';' && closed(current)) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                continue;
            }

            current.append(character);
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }
        return statements.toArray(new String[0]);
    }

    /**
     * Whether a semicolon ends what has been read: everything but a trigger
     * that has not reached its closing {@code END} yet.
     */
    private static boolean closed(StringBuilder statement) {
        String read = statement.toString().trim();
        return !read.toUpperCase(Locale.ROOT).startsWith("CREATE TRIGGER")
                || read.toUpperCase(Locale.ROOT).endsWith("END");
    }

    private static String[] loadMigrations() {
        String json = Native.pimdirMigrations();
        try {
            // NOTE: the bridge reports its own failures as {"error": ...},
            // which is an object where the migrations are an array.
            if (json.trim().startsWith("{")) {
                throw new PimalayaException(
                        "Could not load the pimdir migrations: "
                                + new JSONObject(json).optString("error"));
            }

            JSONArray array = new JSONArray(json);
            String[] loaded = new String[array.length()];
            for (int index = 0; index < loaded.length; index++) {
                loaded[index] = array.getString(index);
            }
            return loaded;
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable pimdir migrations: " + error.getMessage());
        }
    }

    private static Map<String, String> load() {
        String json = Native.pimdirSql();
        try {
            JSONObject object = new JSONObject(json);
            // NOTE: the bridge reports its own failures as {"error": ...}; a
            // statement map never carries that key.
            if (object.has("error")) {
                throw new PimalayaException("Could not load pimdir SQL: " + object.optString("error"));
            }

            Map<String, String> loaded = new HashMap<>(object.length());
            for (Iterator<String> names = object.keys(); names.hasNext(); ) {
                String name = names.next();
                loaded.put(name, object.getString(name));
            }
            return Collections.unmodifiableMap(loaded);
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable pimdir SQL: " + error.getMessage());
        }
    }

    private PimdirSql() {}
}
