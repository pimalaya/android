package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
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
     * The schema split into the individual statements {@code execSQL} takes,
     * comments removed.
     *
     * <p>Android's {@code execSQL} compiles one statement per call, so the
     * migration has to be split, and splitting it on {@code ';'} alone is
     * wrong: the canonical schema documents itself, and its comments contain
     * semicolons (<em>"handing out the next item `seq`; only ever
     * increases"</em>). Cutting there truncates a {@code CREATE TABLE} and
     * SQLite rejects it as incomplete input. So comments go first, tracking
     * string literals on the way so a {@code --} inside one would survive.
     */
    public static String[] schema() {
        String sql = of("MIGRATION_0001");
        StringBuilder stripped = new StringBuilder(sql.length());
        boolean inString = false;
        boolean inComment = false;

        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);

            if (inComment) {
                if (current == '\n') {
                    inComment = false;
                    stripped.append(current);
                }
                continue;
            }

            if (inString) {
                stripped.append(current);
                if (current == '\'') {
                    inString = false;
                }
                continue;
            }

            if (current == '\'') {
                inString = true;
                stripped.append(current);
                continue;
            }

            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                inComment = true;
                index++;
                continue;
            }

            stripped.append(current);
        }

        String[] parts = stripped.toString().split(";");
        int kept = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                parts[kept++] = part.trim();
            }
        }
        String[] result = new String[kept];
        System.arraycopy(parts, 0, result, 0, kept);
        return result;
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
