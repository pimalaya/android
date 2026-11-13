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
 * The pimdir SQL the app runs: io-pimdir's canonical schema, and the
 * statements of the app's own shape, {@code pimalaya-android} (the canonical
 * text where SQLite 3.18 runs it, the app's own where it does not, and the
 * statements of the app's own beside them, rust/pimdir/).
 *
 * <p>io-pimdir comes without its {@code client} feature: schema and
 * statements cross JNI, never transcribed, and run on the app's SQLite, not
 * a second engine. A name may run several statements in one transaction
 * ({@link Bound#steps}), where 3.18 lacks {@code RETURNING} and upserts.
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
        String hash = Native.pimdirHash(body);
        if (hash.startsWith("{")) {
            throw new PimalayaException(Replies.read(hash).optString("error"));
        }
        return hash;
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

    /** What a no-replace rename did ({@link #renameNoReplace}). */
    public enum Filed {
        /** The source now holds the name. */
        MOVED,
        /** The name was held by a file hashing to it, kept; the source left. */
        HELD,
        /**
         * The name was held by a file failing it, a link or a part included,
         * which holds no body; the source left, for the owner to move that
         * file into quarantine/ and rename again (STORAGE §5, §7).
         */
        FAILING
    }

    /**
     * Renames {@code from} to {@code to} unless a name holds {@code to}, never
     * replacing it (STORAGE §5), {@code to} named by its body's hash; a name
     * held is verified before it is kept.
     *
     * <p>A rename rather than a hard link: Android's SELinux policy denies
     * {@code link} on an app's own data files. Where the kernel or the file
     * system refuses a no-replace rename, the bytes are copied into the name
     * created exclusively, never renamed over it. Before API 30 that copy is
     * the only move: the app's seccomp filter there need not allow
     * {@code renameat2}, and a blocked call kills the process.
     *
     * @throws java.io.IOException when the rename fails otherwise (another
     *     file system, a missing source, a name that is no body's)
     */
    public static Filed renameNoReplace(java.io.File from, java.io.File to)
            throws java.io.IOException {
        JSONObject reply;
        try {
            reply = new JSONObject(
                    Native.pimdirRename(
                            from.getAbsolutePath(),
                            to.getAbsolutePath(),
                            android.os.Build.VERSION.SDK_INT >= 30));
        } catch (JSONException error) {
            throw new java.io.IOException("Unreadable rename reply: " + error.getMessage());
        }
        if (reply.has("error")) {
            throw new java.io.IOException(reply.optString("error"));
        }
        switch (reply.optString("filed")) {
            case "moved":
                return Filed.MOVED;
            case "held":
                return Filed.HELD;
            case "failing":
                return Filed.FAILING;
            default:
                throw new java.io.IOException("Unreadable rename reply: " + reply);
        }
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

    /** Every migration in order, as its own script (STORAGE §6): the canonical ones. */
    public static synchronized String[] migrations() {
        if (migrations == null) {
            migrations = loadMigrations();
        }
        return migrations.clone();
    }

    /**
     * A statement by name, its {@code :name} parameters made positional.
     *
     * <p>Android binds no named parameters; rewriting keeps column lists in the
     * crate. Values come from {@code values} by name: a missing one binds NULL,
     * a repeated one the same value. Comments are stripped first.
     *
     * @throws PimalayaException when the caller names a value the statement
     *     does not take while leaving one of its parameters unnamed, which is
     *     what a parameter renamed in the crate looks like: it would bind
     *     NULL unseen
     */
    public static Bound bind(String name, Map<String, Object> values) {
        Rewritten[] rewritten = rewritten(name);
        refuseRenamed(name, rewritten, values);
        Map<String, List<Object>> params = new LinkedHashMap<>();
        List<Bound> steps = new ArrayList<>(rewritten.length);
        for (Rewritten step : rewritten) {
            Object[] args = new Object[step.names.length];
            for (int index = 0; index < args.length; index++) {
                args[index] = values.get(step.names[index]);
            }
            for (String slot : step.slots) {
                params.put(slot, listOf(values.get(slot)));
            }
            steps.add(new Bound(step.sql, args));
        }
        if (steps.size() == 1) {
            return new Bound(steps.get(0).sql, steps.get(0).args, params);
        }
        return new Bound(params, steps);
    }

    /**
     * Refuses a call that names a value no statement of {@code name} takes,
     * as a parameter or a list slot, while a parameter of it goes unnamed.
     *
     * <p>Either alone is how the app binds: an optional parameter left out
     * binds NULL, and one map of values serves several statements. Both at
     * once is a parameter the standard renamed under its caller, which SQLite
     * would run on NULL: a release with no bound, a purge of nothing.
     */
    private static void refuseRenamed(
            String name, Rewritten[] rewritten, Map<String, Object> values) {
        Set<String> taken = new LinkedHashSet<>();
        for (Rewritten step : rewritten) {
            taken.addAll(Arrays.asList(step.names));
            taken.addAll(Arrays.asList(step.slots));
        }
        if (values.keySet().containsAll(taken)) {
            return;
        }
        for (String given : values.keySet()) {
            if (!taken.contains(given)) {
                Set<String> unnamed = new LinkedHashSet<>(taken);
                unnamed.removeAll(values.keySet());
                throw new PimalayaException(
                        "The pimdir statement '" + name + "' takes no '" + given
                                + "', and its " + unnamed + " would bind NULL");
            }
        }
    }

    /**
     * A read of the app's shape over another read: {@code name}'s text holds
     * {@code :inner} where {@code inner}'s statement goes, as a subquery or
     * after a {@code WITH}. Its own parameters bind from {@code values}, the
     * lists of both through {@code temp.param}; nothing of the text is
     * Java's.
     */
    public static Bound wrap(String name, Bound inner, Map<String, Object> values) {
        if (!inner.steps.isEmpty()) {
            throw new PimalayaException("A statement running several reads as no subquery");
        }
        Rewritten[] halves = wrappers.get(name);
        if (halves == null) {
            String[] statements = split(of(name));
            String text = statements.length == 1 ? statements[0] : "";
            int at = text.indexOf(INNER);
            if (at < 0) {
                throw new PimalayaException("The pimdir statement '" + name + "' wraps nothing");
            }
            halves =
                    new Rewritten[] {
                        rewrite(text.substring(0, at)), rewrite(text.substring(at + INNER.length()))
                    };
            wrappers.put(name, halves);
        }
        Rewritten before = halves[0];
        Rewritten after = halves[1];
        Object[] args = new Object[before.names.length + inner.args.length + after.names.length];
        int at = 0;
        for (String param : before.names) {
            args[at++] = values.get(param);
        }
        System.arraycopy(inner.args, 0, args, at, inner.args.length);
        at += inner.args.length;
        for (String param : after.names) {
            args[at++] = values.get(param);
        }
        Map<String, List<Object>> params = new LinkedHashMap<>(inner.params);
        for (Rewritten half : halves) {
            for (String slot : half.slots) {
                params.put(slot, listOf(values.get(slot)));
            }
        }
        return new Bound(before.sql + inner.sql + after.sql, args, params);
    }

    /** Where a wrapping read's text takes the read it wraps. */
    private static final String INNER = ":inner";

    /** The wrapping reads rewritten, by name: their text before and after. */
    private static final Map<String, Rewritten[]> wrappers =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The values of a statement's parameters, from alternating names and
     * values: {@code values("collection", id, "link_id", key)}.
     */
    public static Map<String, Object> values(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Names and values come in pairs");
        }
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
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
    private static final Map<String, Rewritten[]> rewrites =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One name's rewrite, done once: each of its statements comment-free, in
     * order, each {@code :name} made {@code ?} (names in string literals
     * stay).
     */
    private static Rewritten[] rewritten(String name) {
        Rewritten[] held = rewrites.get(name);
        if (held != null) {
            return held;
        }
        String[] statements = split(of(name));
        if (statements.length == 0) {
            statements = new String[] {""};
        }
        Rewritten[] made = new Rewritten[statements.length];
        for (int index = 0; index < statements.length; index++) {
            made[index] = rewrite(statements[index]);
        }
        rewrites.put(name, made);
        return made;
    }

    /**
     * The name a bound statement's text was rewritten from, null for one no
     * name gave (a wrapped read): a profile's label, read once a batch, so
     * a scan of the names bound so far.
     */
    public static String nameOf(String sql) {
        for (Map.Entry<String, Rewritten[]> entry : rewrites.entrySet()) {
            for (Rewritten step : entry.getValue()) {
                if (step.sql.equals(sql)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** One statement made positional, the lists it reads named. */
    private static Rewritten rewrite(String sql) {
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
        return new Rewritten(
                rewritten.toString(), names.toArray(new String[0]), slots.toArray(new String[0]));
    }

    /**
     * A statement with its arguments, as {@code execSQL} takes them, or the
     * statements one name runs in order ({@link #steps}).
     */
    public static final class Bound {
        /**
         * The statement, its parameters positional; null for a name running
         * several, which only the store's runner executes.
         */
        public final String sql;

        /** The values, in the order the statement's parameters occur. */
        public final Object[] args;

        /**
         * The statements a name of the app's shape runs in order, in one
         * transaction (a write and the read standing for its {@code
         * RETURNING}, an update and the insert standing for an upsert), each
         * bound on its own; empty for a name running one.
         */
        public final List<Bound> steps;

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
            if (!steps.isEmpty()) {
                throw new PimalayaException("A statement running several reads as no subquery");
            }
            return new Bound(sql, args, params);
        }

        /** A statement, its positional values and the lists it reads. */
        public Bound(String sql, Object[] args, Map<String, List<Object>> params) {
            this.sql = sql;
            this.args = args;
            this.params = params;
            this.steps = Collections.emptyList();
        }

        /** The statements one name runs in order, and the lists they read. */
        private Bound(Map<String, List<Object>> params, List<Bound> steps) {
            this.sql = null;
            this.args = new Object[0];
            this.params = params;
            this.steps = Collections.unmodifiableList(steps);
        }

        /**
         * Whether this statement answers rows: a {@code SELECT} or a {@code
         * WITH} read, for a step; a name running several answers its read's.
         */
        public boolean reads() {
            if (!steps.isEmpty()) {
                for (Bound step : steps) {
                    if (step.reads()) {
                        return true;
                    }
                }
                return false;
            }
            String head = sql.trim();
            int end = 0;
            while (end < head.length() && Character.isLetter(head.charAt(end))) {
                end++;
            }
            String keyword = head.substring(0, end).toUpperCase(Locale.ROOT);
            return keyword.equals("SELECT") || keyword.equals("WITH") || keyword.equals("VALUES");
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
