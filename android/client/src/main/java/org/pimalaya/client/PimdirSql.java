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
 * <p>io-pimdir comes without its {@code client} feature: schema and
 * statements cross JNI, never transcribed, and run on the app's bundled
 * SQLite (the platform's is too old below Android 14), not a second engine.
 */
public final class PimdirSql {
    private static Map<String, String> statements;
    private static String[] migrations;

    /** The schema version the compiled-in SQL is. */
    public static int version() {
        return Native.pimdirVersion();
    }

    /**
     * The statement named by its Rust constant, e.g. {@code LIST_ITEMS_PAGE}.
     *
     * @throws PimalayaException when there is none: the caller and the crate
     *     disagree about the spec, which fails loudly rather than reach SQLite
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

    /**
     * What io-pimdir derives from one body of {@code kind} (STORAGE Annex A).
     *
     * <p>Beside the statements because it is the same contribution: the rule a
     * writer of the store follows, from the crate rather than restated here.
     *
     * @throws PimalayaException when the kind has no derivation.
     */
    public static Derived derive(String kind, byte[] body) {
        return Derived.from(Replies.object(Native.pimdirDerive(kind, body)));
    }

    /** Every statement, by name; unmodifiable. */
    static synchronized Map<String, String> all() {
        if (statements == null) {
            statements = load();
        }
        return statements;
    }

    /**
     * Every migration split into single statements, in order ({@link #split}).
     *
     * <p>Android's {@code execSQL} compiles one statement per call.
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
     * A canonical statement, its {@code :name} parameters made positional.
     *
     * <p>Android binds no named parameters; rewriting keeps column lists in the
     * crate. Values come from {@code values} by name: a missing one binds NULL,
     * a repeated one the same value. Comments are stripped first.
     */
    public static Bound bind(String name, Map<String, Object> values) {
        Rewritten rewritten = rewritten(name);
        Object[] args = new Object[rewritten.names.length];
        for (int index = 0; index < args.length; index++) {
            args[index] = values.get(rewritten.names[index]);
        }
        return new Bound(rewritten.sql, args);
    }

    /** A statement rewritten to positional parameters, and each one's name. */
    private static final class Rewritten {
        final String sql;
        final String[] names;

        Rewritten(String sql, String[] names) {
            this.sql = sql;
            this.names = names;
        }
    }

    /** The rewritten statements, by name: a sync binds thousands of times. */
    private static final Map<String, Rewritten> rewrites = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One statement's rewrite, done once: each {@code :name} becomes {@code ?}.
     *
     * <p>Its first statement, comment-free; names in string literals stay.
     */
    private static Rewritten rewritten(String name) {
        Rewritten held = rewrites.get(name);
        if (held != null) {
            return held;
        }
        String[] statements = split(of(name));
        String sql = statements.length == 0 ? "" : statements[0];
        StringBuilder rewritten = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
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
                names.add(sql.substring(index + 1, end));
                rewritten.append('?');
                index = end - 1;
                continue;
            }

            rewritten.append(current);
        }

        Rewritten made = new Rewritten(rewritten.toString(), names.toArray(new String[0]));
        rewrites.put(name, made);
        return made;
    }

    /** A statement with its arguments, as {@code execSQL} takes them. */
    public static final class Bound {
        /** The statement, its parameters positional. */
        public final String sql;

        /** The values, in the order the statement's parameters occur. */
        public final Object[] args;

        /** A statement and its positional values. */
        public Bound(String sql, Object[] args) {
            this.sql = sql;
            this.args = args;
        }
    }

    private static boolean isNameChar(char current) {
        return Character.isLetterOrDigit(current) || current == '_';
    }

    /**
     * A multi-statement script split into single statements, comments removed.
     *
     * <p>Not on {@code ';'} alone: the schema's comments hold semicolons, so
     * they go first (string literals tracked), and a trigger's body
     * semicolons end nothing before its {@code END}.
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

    /** Whether a semicolon ends what was read: not inside a trigger's body. */
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
