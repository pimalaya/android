package org.pimalaya.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * The name the store files a body under (STORAGE §5): BLAKE3, 52
     * characters of lowercase base32, computed by io-pimdir.
     */
    public static String hash(byte[] body) {
        return Native.pimdirHash(body);
    }

    /**
     * A file's {@link #hash}, read whole a buffer at a time.
     *
     * @throws java.io.IOException when the file cannot be read
     */
    public static String hashFile(java.io.File file) throws java.io.IOException {
        JSONObject reply;
        try {
            reply = new JSONObject(Native.pimdirHashFile(file.getAbsolutePath()));
        } catch (JSONException error) {
            throw new java.io.IOException("Unreadable hash reply: " + error.getMessage());
        }
        if (reply.has("error")) {
            throw new java.io.IOException(reply.optString("error"));
        }
        return reply.optString("hash");
    }

    /**
     * The versions a store is created with and opened at: {@code
     * formatVersion}, {@code syncVersion}, {@code derivation}, {@code version}
     * and {@code hashAlgo} (STORAGE §4, §6).
     */
    public static JSONObject identity() {
        return Replies.object(Native.pimdirIdentity());
    }

    /**
     * Why an existing store is not opened (STORAGE §6), or null when it is.
     *
     * <p>{@code facts} is what the owner read off the file without writing:
     * {@code userVersion}, {@code objects} and {@code canonical} (name to
     * text) and the {@code meta} row. Answers {@code {refused, detail}}.
     */
    public static JSONObject refusal(JSONObject facts) {
        JSONObject reply = Replies.object(Native.pimdirCheck(facts.toString()));
        return reply.has("refused") ? reply : null;
    }

    /**
     * A queue row read strictly as its payload schema types it (STORAGE
     * §15.3): its {@code targetCollection}, if any, or {@code skipped} when
     * it states a later payload version, which the drain leaves pending and
     * untouched (§15.2).
     *
     * @throws PimalayaException naming what is malformed, a row a producer
     *     never appends and the drain parks whole
     */
    public static JSONObject action(String kind, String payload, String objectHash) {
        return Replies.object(
                Native.pimdirAction(kind, payload, objectHash == null ? "" : objectHash));
    }

    /**
     * The {@code submit} payload queuing {@code message} for {@code source} to
     * send from {@code from} (STORAGE Annex B.2): its envelope recipients and
     * subject read off the message, nothing of the app's own.
     *
     * @throws PimalayaException for a message naming no recipient
     */
    public static String submit(String source, String from, byte[] message) {
        return Replies.object(Native.pimdirSubmit(source, from, message)).optString("payload");
    }

    /**
     * One upsert of a mutable kind absorbed into the item it names, by
     * io-pimdir's own cross-source merge (SYNC §9): the body the item keeps,
     * its conflict, the source's {@code sharedObject}, whether the upsert
     * {@code diverged} and whether the item {@code keptShared} its body.
     *
     * @param input {@code {policy?, source, item?, bindings, placement,
     *     overruling?}}, the item and bindings as stored before the upsert
     */
    public static JSONObject merge(JSONObject input) {
        return Replies.object(Native.pimdirMerge(input.toString()));
    }

    /**
     * Renames {@code from} to {@code to} unless a name holds {@code to}, never
     * replacing it (STORAGE §5); answers false when the name was kept and
     * {@code from} left where it was.
     *
     * <p>A rename rather than a hard link: Android's SELinux policy denies
     * {@code link} on an app's own data files.
     *
     * @throws java.io.IOException when the rename fails otherwise (another
     *     file system, a missing source)
     */
    public static boolean renameNoReplace(java.io.File from, java.io.File to)
            throws java.io.IOException {
        JSONObject reply;
        try {
            reply = new JSONObject(
                    Native.pimdirRename(from.getAbsolutePath(), to.getAbsolutePath()));
        } catch (JSONException error) {
            throw new java.io.IOException("Unreadable rename reply: " + error.getMessage());
        }
        if (reply.has("error")) {
            throw new java.io.IOException(reply.optString("error"));
        }
        return !reply.optBoolean("held");
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
        Map<String, List<Object>> params = new LinkedHashMap<>();
        for (String slot : rewritten.slots) {
            params.put(slot, listOf(values.get(slot)));
        }
        return new Bound(rewritten.sql, args, params);
    }

    /**
     * A list parameter's values: a {@link java.util.Collection}, a {@link
     * JSONArray} or the JSON array text earlier callers bound; none for null.
     */
    private static List<Object> listOf(Object value) {
        List<Object> values = new ArrayList<>();
        if (value == null) {
            return values;
        }
        if (value instanceof java.util.Collection) {
            values.addAll((java.util.Collection<?>) value);
            return values;
        }
        try {
            JSONArray array =
                    value instanceof JSONArray
                            ? (JSONArray) value
                            : new JSONArray(value.toString());
            for (int index = 0; index < array.length(); index++) {
                values.add(array.get(index));
            }
        } catch (JSONException error) {
            throw new PimalayaException("A list parameter is no JSON array: " + value);
        }
        return values;
    }

    /**
     * A statement rewritten to positional parameters, each one's name, and
     * the {@code temp.param} slots it reads its lists from (STORAGE §4.4).
     */
    private static final class Rewritten {
        final String sql;
        final String[] names;
        final String[] slots;

        Rewritten(String sql, String[] names, String[] slots) {
            this.sql = sql;
            this.names = names;
            this.slots = slots;
        }
    }

    /** A list slot a canonical statement reads, {@code slot = 'links'}. */
    private static final java.util.regex.Pattern SLOT =
            java.util.regex.Pattern.compile("slot\\s*=\\s*'([a-z_]+)'");

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

        Set<String> slots = new LinkedHashSet<>();
        java.util.regex.Matcher slot = SLOT.matcher(sql);
        while (slot.find()) {
            slots.add(slot.group(1));
        }
        Rewritten made =
                new Rewritten(
                        rewritten.toString(),
                        names.toArray(new String[0]),
                        slots.toArray(new String[0]));
        rewrites.put(name, made);
        return made;
    }

    /** A statement with its arguments, as {@code execSQL} takes them. */
    public static final class Bound {
        /** The statement, its parameters positional. */
        public final String sql;

        /** The values, in the order the statement's parameters occur. */
        public final Object[] args;

        /**
         * The lists the statement reads from {@code temp.param}, by slot, in
         * order: bound on the connection that runs it, beforehand
         * ({@code clear_param}, then {@code bind_param} per value). Empty for
         * a statement reading none.
         */
        public final Map<String, List<Object>> params;

        /** A statement and its positional values, reading no list. */
        public Bound(String sql, Object[] args) {
            this(sql, args, Collections.emptyMap());
        }

        /**
         * Another statement over this one, a wrapper reading it as a
         * subquery: the lists it reads go with it.
         */
        public Bound with(String sql, Object[] args) {
            return new Bound(sql, args, params);
        }

        /** A statement, its positional values and the lists it reads. */
        public Bound(String sql, Object[] args, Map<String, List<Object>> params) {
            this.sql = sql;
            this.args = args;
            this.params = params;
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
