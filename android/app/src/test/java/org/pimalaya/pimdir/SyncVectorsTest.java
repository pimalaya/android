package org.pimalaya.pimdir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Derived;
import org.pimalaya.client.Flags;
import org.pimalaya.client.Mutation;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.client.SyncVectors;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The sync conformance suite (pimdir SYNC §11) through the Java owner: each
 * case is seeded into a store of the app's, run by io-pimdir's engine over
 * the app's load and write against a scripted remote, and compared as rows.
 *
 * <p>The cases expected not to pass are in sync-vectors.txt beside this
 * test: one failing or passing against it fails the suite.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncVectorsTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /** The format version this runner reads a case under (SYNC §11). */
    private static final int FORMAT = 6;

    /** The system flags, each its bit of {@code marks} (STORAGE §13). */
    private static final List<String> MARKS =
            List.of("\\Seen", "\\Answered", "\\Flagged", "\\Draft", "\\Deleted");

    /** Any instant, standing for one SQLite stamped (SYNC §11). */
    private static final String INSTANT = "2026-10-07T00:00:00.000Z";

    /** The columns naming a row, which pair it with the row a case states. */
    private static final String[] NAMING = {
        "id", "collection", "link_id", "source", "role", "position"
    };

    @Test
    public void everySyncVectorRunsThroughTheJavaLoadAndWrite() throws Exception {
        Map<String, String> listedFailing = new TreeMap<>();
        Map<String, String> listedUnbound = new TreeMap<>();
        readLedger(listedFailing, listedUnbound);

        File[] cases = PimdirVectors.syncCases();
        assertTrue("the sync vectors carry no case", cases.length > 0);

        List<String> departures = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>();
        int passed = 0;
        int failed = 0;
        int unbound = 0;
        for (File file : cases) {
            String name = file.getName().substring(0, file.getName().length() - ".json".length());
            names.add(name);
            JSONObject vector =
                    new JSONObject(
                            new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));

            String reason = unboundBy(vector);
            if (reason != null) {
                unbound += 1;
                System.out.println("unbound " + name + ": " + reason);
                if (!listedUnbound.containsKey(name)) {
                    departures.add(name + " cannot be bound (" + reason + "), list it unbound");
                }
                continue;
            }
            if (listedUnbound.containsKey(name)) {
                departures.add(name + " is listed unbound and binds");
            }

            String failure = null;
            try {
                reproduce(name, vector);
            } catch (Exception | AssertionError thrown) {
                failure = String.valueOf(thrown.getMessage());
            }
            if (failure == null) {
                passed += 1;
                System.out.println("passed " + name);
                if (listedFailing.containsKey(name)) {
                    departures.add(name + " passes, take it off the list");
                }
            } else {
                failed += 1;
                System.out.println("failed " + name + ": " + failure);
                if (!listedFailing.containsKey(name)) {
                    departures.add(name + " fails: " + failure);
                }
            }
        }
        for (String listed : listedFailing.keySet()) {
            if (!names.contains(listed)) {
                departures.add(listed + " is listed and holds no case");
            }
        }
        for (String listed : listedUnbound.keySet()) {
            if (!names.contains(listed)) {
                departures.add(listed + " is listed and holds no case");
            }
        }

        System.out.println(
                cases.length + " sync vectors: " + passed + " passed, " + failed + " failed, "
                        + unbound + " unbound");
        if (!departures.isEmpty()) {
            fail(
                    departures.size() + " departure(s) from sync-vectors.txt:\n"
                            + String.join("\n", departures));
        }
    }

    /**
     * Reads sync-vectors.txt: one case a line, {@code fails} or {@code
     * unbound}, the case's file stem, a colon and its reason.
     */
    private void readLedger(Map<String, String> failing, Map<String, String> unbound)
            throws Exception {
        try (InputStream in = getClass().getResourceAsStream("sync-vectors.txt");
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int space = line.indexOf(' ');
                int colon = line.indexOf(": ");
                String status = space < 0 ? "" : line.substring(0, space);
                Map<String, String> listed = null;
                if ("fails".equals(status)) {
                    listed = failing;
                } else if ("unbound".equals(status)) {
                    listed = unbound;
                }
                if (listed == null || colon < space || colon + 2 >= line.length()) {
                    throw new IllegalStateException("sync-vectors.txt: malformed line " + line);
                }
                String name = line.substring(space + 1, colon);
                if (failing.containsKey(name) || unbound.containsKey(name)) {
                    throw new IllegalStateException("sync-vectors.txt: " + name + " twice");
                }
                listed.put(name, line.substring(colon + 2));
            }
        }
    }

    /**
     * Why the app's entry points cannot run a case, or null when they can:
     * a verb, an option or a mutation the bridge never states, or an owner
     * the app is not.
     */
    private static String unboundBy(JSONObject vector) throws JSONException {
        JSONObject run = vector.getJSONObject("run");
        JSONObject options = run.optJSONObject("options");
        JSONObject remote = vector.optJSONObject("remote");
        // NOTE: vectors 70 and 158 (SYNC §11), by the collection they state.
        for (JSONObject rows :
                new JSONObject[] {
                    vector.getJSONObject("store"),
                    vector.getJSONObject("expect").getJSONObject("store")
                }) {
            for (JSONObject collection : list(rows.optJSONArray("collections"))) {
                if ("\u0001attachments".equals(collection.optString("id"))) {
                    return "an owner recording attachments in the write";
                }
            }
        }
        switch (run.getString("verb")) {
            case "rekey":
                return "no rekey";
            case "upgrade":
                if (!"Full".equals(run.optString("tier"))) {
                    return "no upgrade to the meta tier";
                }
                break;
            case "mutate": {
                JSONObject mutation = run.getJSONObject("mutation");
                if ("Abandon".equals(mutation.getString("kind"))) {
                    return "no abandon";
                }
                break;
            }
            default: {
                JSONArray heads = remote == null ? null : remote.optJSONArray("heads");
                if (heads != null && heads.length() > 0) {
                    return "no head";
                }
            }
        }
        if (options == null) {
            return null;
        }
        if (!options.optBoolean("push", true)) {
            return "no sync without push";
        }
        JSONObject rights = options.optJSONObject("rights");
        for (String right : new String[] {"flags", "add", "remove"}) {
            if (rights != null && !rights.optBoolean(right, true)) {
                return "no sync without the right to " + right;
            }
        }
        if (!"manual".equals(options.optString("conflict", "manual"))) {
            return "no conflict policy of the engine's";
        }
        JSONObject scope = options.optJSONObject("scope");
        if (scope != null && !scope.isNull("until")) {
            return "no scope bounded above";
        }
        return null;
    }

    /** Builds one case into a store, runs it and compares what it expects. */
    private void reproduce(String name, JSONObject vector) throws Exception {
        assertEquals("the sync format's version", FORMAT, vector.getInt("v"));
        File files = folder.newFolder(name);
        Context context = new StoreContext(RuntimeEnvironment.getApplication(), files);
        PimdirDb store = new PimdirDb(context);
        try {
            Case run = new Case(vector);
            seed(store, run);
            Scripted driver = new Scripted(store, run);
            Ran ran = run(store, driver, run);
            compare(store, run, driver, ran);
        } finally {
            store.close();
        }
    }

    /** A context whose files, so whose store, are one case's own. */
    private static final class StoreContext extends ContextWrapper {
        private final File files;

        StoreContext(Context base, File files) {
            super(base);
            this.files = files;
        }

        @Override
        public File getFilesDir() {
            return files;
        }

        @Override
        public File getNoBackupFilesDir() {
            return files;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }

    /** One case: what it states, and the bodies it names by label. */
    private static final class Case {
        final JSONObject vector;
        final JSONObject run;
        final JSONObject remote;
        final JSONObject expect;
        final String collection;
        final String source;
        /** The kind of the collection the run names, empty for none. */
        final String kind;
        final Map<String, String> hashes = new LinkedHashMap<>();
        final Map<String, byte[]> bytes = new LinkedHashMap<>();

        Case(JSONObject vector) throws JSONException {
            this.vector = vector;
            this.run = vector.getJSONObject("run");
            this.remote = vector.optJSONObject("remote");
            this.expect = vector.getJSONObject("expect");
            this.collection = run.getString("collection");
            this.source = run.getString("source");
            String kind = "";
            JSONArray collections = vector.getJSONObject("store").getJSONArray("collections");
            for (int index = 0; index < collections.length(); index++) {
                JSONObject stated = collections.getJSONObject(index);
                if (collection.equals(stated.getString("id")) && !stated.isNull("kind")) {
                    kind = stated.getString("kind");
                }
            }
            this.kind = kind;
        }

        /**
         * A source of the case as the store names it: the run's is the app's
         * server, the one name it syncs under, and every other keeps its
         * order around it.
         */
        String stored(String stated) {
            if (stated == null) {
                return null;
            }
            int order = stated.compareTo(source);
            return order == 0 ? PimdirStorage.SERVER : (order < 0 ? "0-" : "z-") + stated;
        }

        /** {@link #stored} reversed. */
        String stated(String stored) {
            if (stored == null) {
                return null;
            }
            return PimdirStorage.SERVER.equals(stored) ? source : stored.substring(2);
        }

        /** The object name a label stands for, the label for another. */
        String hash(JSONObject row, String key) throws JSONException {
            if (row.isNull(key)) {
                return null;
            }
            String label = row.getString(key);
            String hash = hashes.get(label);
            return hash == null ? label : hash;
        }

        /** The label naming an object, the name itself for another. */
        Object label(String hash) {
            if (hash == null) {
                return JSONObject.NULL;
            }
            for (Map.Entry<String, String> named : hashes.entrySet()) {
                if (named.getValue().equals(hash)) {
                    return named.getKey();
                }
            }
            return hash;
        }
    }

    /**
     * Seeds a store from a case's {@code store} rows, by the standard's own
     * statements and plain SQL, never through the write under test.
     */
    private static void seed(PimdirDb store, Case run) throws Exception {
        JSONObject stated = run.vector.getJSONObject("store");
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        JSONObject objects = stated.getJSONObject("objects");
        for (String label : keys(objects)) {
            JSONObject object = objects.getJSONObject(label);
            byte[] body = PimdirVectors.bytes("vectors/" + object.getString("body"));
            String hash = PimdirHash.of(body);
            blobs.put(hash, body);
            run.hashes.put(label, hash);
            run.bytes.put(label, body);
            // NOTE: a row and no file, or a file failing its name (SYNC §11).
            File path = blobs.pathOf(hash);
            if (!object.isNull("blob") && !object.getBoolean("blob")) {
                Files.delete(path.toPath());
            } else if (object.optBoolean("corrupt")) {
                byte[] altered = java.util.Arrays.copyOf(body, body.length + 1);
                altered[body.length] = '\n';
                Files.write(path.toPath(), altered);
            }
        }

        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            seedRows(db, run, stated);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void seedRows(SQLiteDatabase db, Case run, JSONObject stated)
            throws Exception {
        JSONArray collections = stated.getJSONArray("collections");
        for (int index = 0; index < collections.length(); index++) {
            JSONObject collection = collections.getJSONObject(index);
            String id = collection.getString("id");
            PimdirDb.exec(
                    db,
                    "SET_COLLECTION_KIND",
                    PimdirSql.values(
                            "collection", id,
                            "account", text(collection, "account"),
                            "kind", collection.isNull("kind") ? "" : collection.getString("kind")));
            db.execSQL(
                    "UPDATE collections SET conflict = ?, generation = ? WHERE id = ?",
                    new Object[] {
                        collection.isNull("conflict") ? "manual" : collection.getString("conflict"),
                        collection.optLong("generation", 1),
                        id
                    });
        }
        for (Map.Entry<String, String> object : run.hashes.entrySet()) {
            PimdirDb.exec(
                    db,
                    "STORE_OBJECT",
                    PimdirSql.values(
                            "hash", object.getValue(),
                            "size", run.bytes.get(object.getKey()).length));
        }

        JSONArray items = stated.getJSONArray("items");
        long nextSeq = 1;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String collection = item.getString("collection");
            String linkId = item.getString("link_id");
            long seq = item.getLong("seq");
            nextSeq = Math.max(nextSeq, seq + 1);
            JSONArray flags = item.optJSONArray("flags");
            Map<String, Object> row = PimdirSql.values("collection", collection, "link_id", linkId);
            row.put("seq", seq);
            row.put("flags_known", flags == null ? 0 : 1);
            row.put("marks", marks(flags));
            row.put("object_hash", run.hash(item, "object"));
            row.put("sort_key", item.isNull("sort_key") ? "" : item.getString("sort_key"));
            row.put("level", item.optLong("level", 0));
            row.put("deleted", item.optLong("deleted", 0));
            row.put("conflicted", item.optLong("conflicted", 0));
            row.put("conflict_object", run.hash(item, "conflict_object"));
            PimdirDb.exec(db, "INSERT_ITEM", row);
            for (String flag : flagRows(flags)) {
                db.execSQL(
                        "INSERT INTO placement_flag(item, flag) VALUES("
                                + "(SELECT id FROM items WHERE collection = ? AND link_id = ?), ?)",
                        new Object[] {collection, linkId, flag});
            }
            if (item.optBoolean("retained")) {
                PimdirDb.exec(
                        db,
                        "RETAIN_ITEM",
                        PimdirSql.values(
                                "collection", collection,
                                "link_id", linkId,
                                "source", run.stored(text(item, "retained_by"))));
            }
        }
        db.execSQL("UPDATE store_meta SET next_seq = ?", new Object[] {nextSeq});

        JSONArray bindings = stated.getJSONArray("bindings");
        for (int index = 0; index < bindings.length(); index++) {
            JSONObject binding = bindings.getJSONObject(index);
            String collection = binding.getString("collection");
            String linkId = binding.getString("link_id");
            String source = run.stored(binding.getString("source"));
            // NOTE: a case stating no agreement point agrees with the item's
            // body, as the store would have recorded it (STORAGE §10).
            String shared = null;
            if (binding.has("shared_object")) {
                shared = run.hash(binding, "shared_object");
            } else {
                for (int at = 0; at < items.length(); at++) {
                    JSONObject item = items.getJSONObject(at);
                    if (collection.equals(item.getString("collection"))
                            && linkId.equals(item.getString("link_id"))) {
                        shared = run.hash(item, "object");
                        break;
                    }
                }
            }
            JSONArray baseFlags = binding.optJSONArray("base_flags");
            Map<String, Object> row = PimdirSql.values("collection", collection, "link_id", linkId);
            row.put("source", source);
            row.put("handle", binding.getString("handle"));
            row.put("base_flags_known", baseFlags == null ? 0 : 1);
            row.put("base_marks", marks(baseFlags));
            row.put("base_object", run.hash(binding, "base_object"));
            row.put("base_revision", text(binding, "base_revision"));
            row.put("base_present", binding.optLong("base_present", 0));
            row.put("conflicted", binding.optLong("conflicted", 0));
            row.put("conflict_revision", text(binding, "conflict_revision"));
            row.put("conflict_object", run.hash(binding, "conflict_object"));
            row.put("shared_object", shared);
            PimdirDb.exec(db, "INSERT_BINDING", row);
            for (String flag : flagRows(baseFlags)) {
                db.execSQL(
                        "INSERT INTO binding_base_flag(item, source, flag) VALUES("
                                + "(SELECT id FROM items WHERE collection = ? AND link_id = ?),"
                                + " ?, ?)",
                        new Object[] {collection, linkId, source, flag});
            }
        }

        JSONArray supersessions = stated.optJSONArray("supersessions");
        for (int index = 0; supersessions != null && index < supersessions.length(); index++) {
            JSONObject pair = supersessions.getJSONObject(index);
            PimdirDb.exec(
                    db,
                    "SUPERSEDE_ITEM",
                    PimdirSql.values(
                            "collection", pair.getString("collection"),
                            "link_id", pair.getString("link_id"),
                            "by_collection", pair.getString("by_collection"),
                            "by_link_id", pair.getString("by_link_id")));
        }
        for (int index = 0; index < bindings.length(); index++) {
            JSONObject binding = bindings.getJSONObject(index);
            if (binding.has("round")) {
                db.execSQL(
                        "UPDATE bindings SET round = ? WHERE item = (SELECT id FROM items"
                                + " WHERE collection = ? AND link_id = ?) AND source = ?",
                        new Object[] {
                            binding.isNull("round") ? null : binding.getLong("round"),
                            binding.getString("collection"),
                            binding.getString("link_id"),
                            run.stored(binding.getString("source"))
                        });
            }
        }

        JSONArray summaries = stated.optJSONArray("summaries");
        for (int index = 0; summaries != null && index < summaries.length(); index++) {
            seedSummary(db, summaries.getJSONObject(index));
        }

        JSONArray references = stated.optJSONArray("references");
        for (int index = 0; references != null && index < references.length(); index++) {
            JSONObject reference = references.getJSONObject(index);
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : keys(reference)) {
                row.put(column, reference.getString(column));
            }
            PimdirDb.exec(db, "ADD_REFERENCE", row);
        }

        JSONArray sources = stated.getJSONArray("sources");
        for (int index = 0; index < sources.length(); index++) {
            JSONObject source = sources.getJSONObject(index);
            String collection = source.getString("collection");
            String named = run.stored(source.getString("source"));
            PimdirDb.exec(
                    db,
                    "UPSERT_CHECKPOINT",
                    PimdirSql.values(
                            "collection", collection,
                            "source", named,
                            "checkpoint", blob(source, "checkpoint")));
            // NOTE: a band head reads the instant a case states (SYNC §11).
            String started =
                    source.isNull("round_started_at")
                            ? (source.optBoolean("round_open") ? INSTANT : null)
                            : source.getString("round_started_at");
            db.execSQL(
                    "UPDATE sources SET round = ?, round_since = ?, round_until = ?,"
                            + " round_cursor = ?, round_checkpoint = ?, round_started_at = ?,"
                            + " covered_since = ?, covered_until = ?, covered_at = ?,"
                            + " round_band = ? WHERE collection = ? AND source = ?",
                    new Object[] {
                        source.optLong("round", 0),
                        text(source, "round_since"),
                        text(source, "round_until"),
                        blob(source, "round_cursor"),
                        blob(source, "round_checkpoint"),
                        started,
                        text(source, "covered_since"),
                        text(source, "covered_until"),
                        source.optBoolean("covered") ? INSTANT : null,
                        source.optBoolean("round_band") ? 1 : 0,
                        collection,
                        named
                    });
        }
        PimdirDb.exec(db, "RECOMPUTE_REFCOUNTS", PimdirSql.values());
    }

    /** One summary row a case states, its lists in their tables (§16). */
    private static void seedSummary(SQLiteDatabase db, JSONObject entry) throws JSONException {
        String table = entry.getString("table");
        JSONObject row = entry.getJSONObject("row");
        List<String> columns = new ArrayList<>();
        List<Object> bound = new ArrayList<>();
        bound.add(entry.getString("collection"));
        bound.add(entry.getString("link_id"));
        for (String column : keys(row)) {
            if (listOf(table, column) != null) {
                continue;
            }
            Object value = row.get(column);
            columns.add(column);
            if (value == JSONObject.NULL) {
                bound.add(null);
            } else if (value instanceof Boolean) {
                bound.add((Boolean) value ? 1 : 0);
            } else if (value instanceof Number) {
                bound.add(((Number) value).longValue());
            } else {
                bound.add(value.toString());
            }
        }
        db.execSQL(
                "INSERT INTO " + table + " (item, " + String.join(", ", columns) + ") VALUES ("
                        + "(SELECT id FROM items WHERE collection = ? AND link_id = ?)"
                        + ", ?".repeat(columns.size()) + ")",
                bound.toArray());
        for (String column : keys(row)) {
            String[] list = listOf(table, column);
            JSONArray values = row.optJSONArray(column);
            for (int at = 0; list != null && values != null && at < values.length(); at++) {
                db.execSQL(
                        "INSERT INTO " + list[0] + " (item, position, " + list[1] + ") VALUES ("
                                + "(SELECT id FROM items WHERE collection = ? AND link_id = ?),"
                                + " ?, ?)",
                        new Object[] {
                            entry.getString("collection"),
                            entry.getString("link_id"),
                            at,
                            values.getString(at)
                        });
            }
        }
    }

    /**
     * The table and value column a summary list a case states as an array is
     * kept in (STORAGE §4.3), null for a column of the summary's own.
     */
    private static String[] listOf(String table, String column) {
        if ("mail_summary".equals(table) && "in_reply_to".equals(column)) {
            return new String[] {"mail_in_reply_to", "message_id"};
        }
        if ("task_summary".equals(table) && "categories".equals(column)) {
            return new String[] {"task_category", "value"};
        }
        return null;
    }

    /** A flag set's system flags as the row's bits, 0 for an unknown set. */
    private static long marks(JSONArray flags) throws JSONException {
        long marks = 0;
        for (int index = 0; flags != null && index < flags.length(); index++) {
            int bit = MARKS.indexOf(flags.getString(index));
            marks += bit < 0 ? 0 : 1L << bit;
        }
        return marks;
    }

    /** A flag set's other flags, each a row. */
    private static List<String> flagRows(JSONArray flags) throws JSONException {
        List<String> rows = new ArrayList<>();
        for (int index = 0; flags != null && index < flags.length(); index++) {
            if (!MARKS.contains(flags.getString(index))) {
                rows.add(flags.getString(index));
            }
        }
        return rows;
    }

    /**
     * The remote a case scripts, behind the app's storage half: its pages,
     * its fetch answers by handle and tier, its outcomes by handle, and
     * every push it was handed, each on the wire a driver of the app speaks.
     */
    private static final class Scripted extends Stager {
        private final Case run;
        private final PimdirBlobs blobs;
        private final List<JSONObject> pages = new ArrayList<>();
        private final Integer interruptedAfter;
        private boolean cursorRejected;
        private int served;

        /** Whether the scripted connector failed a listing. */
        boolean interrupted;

        /** The pushes handed over, as a case spells them. */
        final List<JSONObject> pushes = new ArrayList<>();

        Scripted(PimdirDb store, Case run) throws Exception {
            super(store, Clients.real());
            this.run = run;
            this.blobs = new PimdirBlobs(store.blobs());
            JSONObject remote = run.remote == null ? new JSONObject() : run.remote;
            JSONArray stated = remote.optJSONArray("pages");
            if (stated != null) {
                for (int index = 0; index < stated.length(); index++) {
                    pages.add(page(stated.getJSONObject(index)));
                }
            } else if (remote.optJSONObject("snapshot") != null) {
                pages.add(page(remote.getJSONObject("snapshot")));
            }
            this.interruptedAfter =
                    remote.isNull("interrupted_after") ? null : remote.getInt("interrupted_after");
            this.cursorRejected = remote.optBoolean("cursor_rejected");
        }

        /** A message's bodies as the mail driver looks them up, else all. */
        @Override
        protected JSONObject lookup(JSONArray links) throws JSONException {
            if (!PimdirSummary.MAIL.equals(bare(run.kind))) {
                return super.lookup(links);
            }
            JSONArray collections = run.vector.getJSONObject("store").getJSONArray("collections");
            String account = null;
            for (int index = 0; index < collections.length(); index++) {
                JSONObject stated = collections.getJSONObject(index);
                if (run.collection.equals(stated.getString("id"))) {
                    account = text(stated, "account");
                }
            }
            return offline.lookupMessages(links, account);
        }

        @Override
        protected String remote(String op, JSONObject yielded, long parsed) throws JSONException {
            try {
                switch (op) {
                    case "enumerate":
                        return enumerate(yielded);
                    case "fetch":
                        return fetch(yielded).toString();
                    case "push":
                        return push(yielded).toString();
                    default:
                        return super.remote(op, yielded, parsed);
                }
            } catch (java.io.IOException unread) {
                throw new IllegalStateException(unread);
            }
        }

        private String enumerate(JSONObject yielded) throws JSONException {
            JSONObject listing = yielded.getJSONObject("listing");
            String kind = listing.getString("kind");
            if ("head".equals(kind)) {
                throw new IllegalStateException("A head was asked for, the bridge lists none");
            }
            boolean resumed = "round".equals(kind) && !listing.isNull("cursor");
            if (resumed && cursorRejected) {
                cursorRejected = false;
                return new JSONObject().put("cursorRejected", true).toString();
            }
            if (interruptedAfter != null && served >= interruptedAfter) {
                interrupted = true;
                return error("interrupted");
            }
            if (pages.isEmpty()) {
                interrupted = true;
                return error("no page scripted");
            }
            served += 1;
            return pages.remove(0).toString();
        }

        /** One page as a case spells it, on the wire. */
        private JSONObject page(JSONObject stated) throws Exception {
            JSONArray items = new JSONArray();
            JSONArray members = stated.getJSONArray("items");
            for (int index = 0; index < members.length(); index++) {
                JSONObject member = members.getJSONObject(index);
                JSONObject meta = member.getJSONObject("meta");
                byte[] body =
                        meta.isNull("body")
                                ? new byte[0]
                                : PimdirVectors.bytes("vectors/" + meta.getString("body"));
                if (member.isNull("flags")) {
                    throw new IllegalStateException(
                            "The wire states a listed member's flags, " + member + " states none");
                }
                JSONObject item = SyncVectors.named(run.kind, meta, body);
                item.put("handle", member.getString("handle"));
                item.put("flags", member.getJSONArray("flags"));
                item.putOpt("revision", text(member, "revision"));
                // NOTE: a member carries its body as a DAV listing may.
                if (member.optBoolean("carries")) {
                    item.put("hash", PimdirHash.of(body));
                    item.put("body", utf8(body));
                }
                items.put(item);
            }

            JSONObject page = new JSONObject();
            page.put("items", items);
            page.put(
                    "vanished",
                    stated.isNull("vanished") ? new JSONArray() : stated.get("vanished"));
            page.put("complete", stated.optBoolean("complete", true));
            page.put("last", stated.optBoolean("last", true));
            page.putOpt("cursor", text(stated, "cursor"));
            page.putOpt("checkpoint", text(stated, "checkpoint"));
            page.put("narrowed", stated.optBoolean("narrowed"));
            page.put("capped", stated.optBoolean("capped"));
            if (!stated.isNull("enumerated")) {
                page.put("enumerated", stated.get("enumerated"));
            }
            return page;
        }

        private JSONObject fetch(JSONObject yielded) throws JSONException, java.io.IOException {
            boolean full = "full".equals(yielded.getString("tier"));
            JSONObject answers = run.remote == null ? null : run.remote.optJSONObject("fetch");
            JSONArray handles = yielded.getJSONArray("handles");
            JSONArray items = new JSONArray();
            for (int index = 0; index < handles.length(); index++) {
                String handle = handles.getString(index);
                JSONObject tiers = answers == null ? null : answers.optJSONObject(handle);
                JSONObject answer =
                        tiers == null ? null : tiers.optJSONObject(full ? "Full" : "Meta");
                if (answer == null) {
                    continue;
                }
                byte[] body = PimdirVectors.bytes("vectors/" + answer.getString("body"));
                JSONObject item;
                if (PimdirSummary.FILE.equals(run.kind)) {
                    item = SyncVectors.named(run.kind, answer.getJSONObject("meta"), body);
                } else if (!full && PimdirSummary.MAIL.equals(run.kind)) {
                    // NOTE: a meta fetch answers from the header (Annex A.1).
                    item = SyncVectors.named(run.kind, new JSONObject(), body);
                } else {
                    Derived derived = PimdirSql.derive(run.kind, body);
                    item = new JSONObject();
                    item.put("linkId", derived.linkId());
                    item.put("sortKey", derived.sortKey());
                    item.putOpt("summary", derived.summary());
                }
                item.put("handle", handle);
                item.putOpt("revision", text(answer, "revision"));
                if (full) {
                    item.put("hash", PimdirHash.of(body));
                    String text = utf8OrNull(body);
                    if (text != null) {
                        item.put("body", text);
                    } else {
                        item.put("bodyBase64", java.util.Base64.getEncoder().encodeToString(body));
                    }
                }
                items.put(item);
            }
            return new JSONObject().put("items", items);
        }

        private JSONObject push(JSONObject yielded) throws JSONException {
            JSONArray outcomes = run.expect.optJSONArray("outcomes");
            JSONArray changes = yielded.getJSONArray("changes");
            JSONArray results = new JSONArray();
            for (int index = 0; index < changes.length(); index++) {
                JSONObject change = changes.getJSONObject(index);
                pushes.add(record(change));
                String handle = change.getString("handle");
                // NOTE: a push with no outcome fed back is unreported, which
                // leaves it pending (SYNC §5).
                JSONObject outcome = null;
                for (int at = 0; outcomes != null && at < outcomes.length(); at++) {
                    if (handle.equals(outcomes.getJSONObject(at).optString("handle"))) {
                        outcome = outcomes.getJSONObject(at);
                        break;
                    }
                }
                if (outcome == null) {
                    continue;
                }
                JSONObject result = new JSONObject();
                result.put("handle", handle);
                result.put("accepted", !"Rejected".equals(outcome.optString("outcome")));
                result.putOpt("assigned", text(outcome, "assigned"));
                result.putOpt("revision", text(outcome, "revision"));
                result.put("relocated", outcome.optBoolean("relocated"));
                results.put(result);
            }
            return new JSONObject().put("results", results);
        }

        /** One pushed change as a case's {@code expect.pushes} spells it. */
        private JSONObject record(JSONObject change) throws JSONException {
            JSONObject push = new JSONObject();
            push.put("handle", change.getString("handle"));
            push.put("key", change.getString("key"));
            switch (change.getString("op")) {
                case "setFlags":
                    push.put("kind", "SetFlags");
                    push.put("add", change.getJSONArray("add"));
                    push.put("remove", change.getJSONArray("remove"));
                    break;
                case "remove":
                    push.put("kind", "Remove");
                    push.put("to", orNull(text(change, "to")));
                    push.put("link_id", orNull(text(change, "linkId")));
                    push.put("if_match", orNull(text(change, "ifMatch")));
                    break;
                case "update":
                    push.put("kind", "Update");
                    push.put("object", run.label(text(change, "object")));
                    push.put("if_match", orNull(text(change, "ifMatch")));
                    break;
                case "add": {
                    push.put("kind", "Add");
                    push.put("link_id", orNull(text(change, "linkId")));
                    JSONArray flags = change.optJSONArray("flags");
                    push.put("flags", flags == null ? JSONObject.NULL : flags);
                    JSONObject origin = change.optJSONObject("origin");
                    push.put("origin", origin == null ? JSONObject.NULL : origin);
                    push.put("object", run.label(text(change, "object")));
                    JSONObject supersedes = change.optJSONObject("supersedes");
                    if (supersedes != null) {
                        JSONObject stated = new JSONObject();
                        stated.put("collection", supersedes.getString("collection"));
                        stated.put("handle", supersedes.getString("handle"));
                        stated.put("if_match", orNull(text(supersedes, "ifMatch")));
                        push.put("supersedes", stated);
                    }
                    break;
                }
                default:
                    throw new JSONException("Unknown change " + change);
            }
            return push;
        }

        /**
         * A body a mutation carries, read through the store as a producer's
         * drain reads it: checked against its name, null when it cannot be
         * read (STORAGE §7, §15.2).
         */
        byte[] readable(String hash) throws java.io.IOException {
            return blobs.get(hash);
        }
    }

    /** What a run gave back. */
    private static final class Ran {
        List<JSONObject> events = new ArrayList<>();
        boolean failed;
        JSONObject parked;
    }

    /** Runs one case's verb through the bridge. */
    private static Ran run(PimdirDb store, Scripted driver, Case run) throws Exception {
        JSONObject options = run.run.optJSONObject("options");
        JSONObject rights = options == null ? null : options.optJSONObject("rights");
        boolean content = rights == null || rights.optBoolean("content", true);
        JSONObject reply;
        Ran ran = new Ran();
        switch (run.run.getString("verb")) {
            case "sync": {
                JSONObject scope = options == null ? null : options.optJSONObject("scope");
                String since = scope == null ? null : text(scope, "since");
                reply =
                        SyncVectors.sync(
                                driver,
                                run.collection,
                                content,
                                since == null ? "" : since,
                                run.remote == null || run.remote.optBoolean("scope_bound", true),
                                options != null && options.optBoolean("accept_absences"));
                // NOTE: a connector failing between two pages leaves what
                // landed, and fails nothing (SYNC §5).
                if (!reply.isNull("error") && !driver.interrupted) {
                    throw new IllegalStateException(reply.getString("error"));
                }
                break;
            }
            case "upgrade": {
                List<String> handles = new ArrayList<>();
                JSONArray stated = run.run.getJSONArray("handles");
                for (int index = 0; index < stated.length(); index++) {
                    handles.add(stated.getString(index));
                }
                reply = SyncVectors.upgrade(driver, run.collection, handles, content);
                if (!reply.isNull("error") || reply.optBoolean("failed")) {
                    throw new IllegalStateException(
                            reply.optString("error", reply.optString("refusal")));
                }
                break;
            }
            case "mutate": {
                Mutation mutation = mutation(store, driver, run);
                if (mutation == null) {
                    ran.failed = true;
                    return ran;
                }
                reply =
                        SyncVectors.mutate(
                                driver,
                                run.collection,
                                mutation,
                                run.run.getJSONObject("mutation").optBoolean("overrule"));
                if (!reply.has("events")) {
                    throw new IllegalStateException(reply.optString("error"));
                }
                // NOTE: a mutation its store refused failed, as one its
                // engine refused did.
                if (!reply.isNull("error")) {
                    ran.failed = true;
                    return ran;
                }
                break;
            }
            default:
                throw new IllegalStateException("Unsupported verb " + run.run.getString("verb"));
        }
        JSONArray events = reply.getJSONArray("events");
        for (int index = 0; index < events.length(); index++) {
            ran.events.add(events.getJSONObject(index));
        }
        ran.failed = reply.optBoolean("failed");
        ran.parked = reply.optJSONObject("parked");
        return ran;
    }

    /**
     * The mutation a {@code mutate} run carries (SYNC §7), as the app's
     * producers build one; null when the body it carries cannot be read.
     */
    private static Mutation mutation(PimdirDb store, Scripted driver, Case run) throws Exception {
        JSONObject stated = run.run.getJSONObject("mutation");
        String kind = stated.getString("kind");
        List<String> resolves = null;
        if (!stated.isNull("resolves")) {
            resolves = new ArrayList<>();
            JSONArray labels = stated.getJSONArray("resolves");
            for (int index = 0; index < labels.length(); index++) {
                String hash = run.hashes.get(labels.getString(index));
                resolves.add(hash == null ? labels.getString(index) : hash);
            }
        }
        String ifMatch = run.hash(stated, "if_match");
        switch (kind) {
            case "SetFlags":
                return Mutation.setFlags(
                        handle(store, run, stated),
                        flags(stated.getJSONArray("flags")),
                        stated.has("base") ? flags(stated.getJSONArray("base")) : null);
            case "Remove":
                return Mutation.remove(handle(store, run, stated), ifMatch, resolves);
            case "Copy":
            case "Move": {
                String target = stated.getString("to");
                boolean syncs = driver.offline.syncsTarget(run.collection, target);
                String handle = handle(store, run, stated);
                return "Copy".equals(kind)
                        ? Mutation.copy(handle, target, syncs)
                        : Mutation.move(handle, target, syncs);
            }
            case "Edit": {
                String hash = run.hash(stated, "object");
                byte[] body = driver.readable(hash);
                if (body == null) {
                    return null;
                }
                Derived derived = derived(run.kind, body);
                return Mutation.edit(
                        handle(store, run, stated),
                        hash,
                        utf8(body),
                        derived == null ? null : derived.summary(),
                        derived == null ? null : derived.sortKey(),
                        resolves,
                        ifMatch);
            }
            case "Add": {
                String hash = run.hash(stated, "object");
                byte[] body = driver.readable(hash);
                if (body == null) {
                    return null;
                }
                Derived derived = derived(run.kind, body);
                String linkId = stated.isNull("link_id") ? null : stated.getString("link_id");
                if (linkId == null && derived == null) {
                    throw new IllegalStateException("A link id, stated or derived");
                }
                Flags flags =
                        stated.isNull("flags") ? Flags.none() : flags(stated.getJSONArray("flags"));
                return Mutation.add(
                        linkId == null ? derived.linkId() : linkId,
                        flags,
                        hash,
                        body,
                        derived == null ? null : derived.summary(),
                        derived == null ? "" : derived.sortKey());
            }
            default:
                throw new IllegalStateException("Unsupported mutation " + kind);
        }
    }

    /** What Annex A derives from a body, null for a kind it has none for. */
    private static Derived derived(String kind, byte[] body) {
        return PimdirSummary.FILE.equals(bare(kind)) || kind.isEmpty()
                ? null
                : PimdirSql.derive(kind, body);
    }

    /** The handle the run's source binds the item of a mutation's seq by. */
    private static String handle(PimdirDb store, Case run, JSONObject stated) throws Exception {
        try (Cursor cursor =
                store.getReadableDatabase()
                        .rawQuery(
                                "SELECT b.handle FROM items i JOIN bindings b ON b.item = i.id"
                                        + " WHERE i.collection = ? AND i.seq = ? AND b.source = ?",
                                new String[] {
                                    run.collection,
                                    String.valueOf(stated.getLong("seq")),
                                    PimdirStorage.SERVER
                                })) {
            if (!cursor.moveToFirst()) {
                throw new IllegalStateException("No item bound under that seq");
            }
            return cursor.getString(0);
        }
    }

    private static Flags flags(JSONArray stated) throws JSONException {
        List<String> flags = new ArrayList<>();
        for (int index = 0; index < stated.length(); index++) {
            flags.add(stated.getString(index));
        }
        return new Flags(flags);
    }

    /**
     * Compares what a run did and left with what its case expects, every
     * difference told at once.
     */
    private static void compare(PimdirDb store, Case run, Scripted driver, Ran ran)
            throws Exception {
        List<String> differences = new ArrayList<>();
        List<JSONObject> pushes = list(run.expect.optJSONArray("pushes"));
        if (pushes.size() != driver.pushes.size()) {
            differences.add("pushes: expected " + pushes + ", got " + driver.pushes);
        }
        for (int index = 0; index < Math.min(pushes.size(), driver.pushes.size()); index++) {
            // NOTE: a push is compared on the keys its case carries (§11).
            JSONObject expected = pushes.get(index);
            JSONObject projected = new JSONObject();
            for (String key : keys(expected)) {
                Object value = driver.pushes.get(index).opt(key);
                projected.put(key, value == null ? JSONObject.NULL : value);
            }
            if (!canon(expected).equals(canon(projected))) {
                differences.add(
                        "push " + index + ": expected " + canon(expected) + ", got "
                                + canon(projected));
            }
        }

        // NOTE: a multiset: vector 41 lists its events out of handle order.
        List<String> events = sorted(list(run.expect.optJSONArray("events")));
        if (!events.equals(sorted(ran.events))) {
            differences.add("events: expected " + events + ", got " + sorted(ran.events));
        }
        if (run.expect.optBoolean("failed") != ran.failed) {
            differences.add(ran.failed ? "the run failed" : "the run did not fail");
        }
        String parked = canon(run.expect.isNull("parked") ? null : run.expect.get("parked"));
        if (!parked.equals(canon(ran.parked))) {
            differences.add("parked: expected " + parked + ", got " + canon(ran.parked));
        }

        SQLiteDatabase db = store.getReadableDatabase();
        JSONObject expect = run.expect.getJSONObject("store");
        for (String table : keys(expect)) {
            if ("objects".equals(table)) {
                compareObjects(store, db, expect.getJSONObject(table), differences);
                continue;
            }
            List<JSONObject> expected = list(expect.optJSONArray(table));
            List<JSONObject> actual =
                    "summaries".equals(table)
                            ? summaries(db, expected)
                            : rows(db, run, table, expected);
            compareRows(table, expected, actual, differences);
        }
        if (!differences.isEmpty()) {
            fail(String.join("; ", differences));
        }
    }

    /**
     * Compares the objects a case states after its run: each one's row, and
     * the file the run leaves under its name (SYNC §11).
     */
    private static void compareObjects(
            PimdirDb store, SQLiteDatabase db, JSONObject objects, List<String> differences)
            throws Exception {
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        for (String label : keys(objects)) {
            JSONObject stated = objects.getJSONObject(label);
            byte[] body = PimdirVectors.bytes("vectors/" + stated.getString("body"));
            String name = PimdirHash.of(body);
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT count(*) FROM objects WHERE hash = ?", new String[] {name})) {
                cursor.moveToFirst();
                if (cursor.getInt(0) != 1) {
                    differences.add("object " + label + " lost its row");
                }
            }
            File path = blobs.pathOf(name);
            byte[] held = path.isFile() ? Files.readAllBytes(path.toPath()) : null;
            boolean named = held != null && name.equals(PimdirHash.of(held));
            if (!stated.isNull("blob") && !stated.getBoolean("blob")) {
                if (held != null) {
                    differences.add("object " + label + " has a file, expected none");
                }
            } else if (stated.optBoolean("corrupt")) {
                if (held == null || named) {
                    differences.add("object " + label + " holds no bytes failing its name");
                }
            } else if (!named) {
                differences.add("object " + label + " holds no bytes hashing to its name");
            }
        }
    }

    /**
     * Compares a table's rows as a set, each on the columns the row a case
     * states for it names (SYNC §11).
     */
    private static void compareRows(
            String table, List<JSONObject> expected, List<JSONObject> actual,
            List<String> differences)
            throws JSONException {
        List<JSONObject> projected = new ArrayList<>();
        for (JSONObject row : actual) {
            JSONObject paired = null;
            for (JSONObject wanted : expected) {
                boolean named = true;
                for (String key : NAMING) {
                    named &= !wanted.has(key) || canon(wanted.get(key)).equals(canon(row.opt(key)));
                }
                if (named) {
                    paired = wanted;
                    break;
                }
            }
            if (paired == null) {
                projected.add(row);
                continue;
            }
            JSONObject kept = new JSONObject();
            for (String key : keys(row)) {
                if (paired.has(key)) {
                    kept.put(key, row.get(key));
                }
            }
            projected.add(kept);
        }
        List<String> missing = sorted(expected);
        List<String> unexpected = sorted(projected);
        for (String row : sorted(projected)) {
            if (missing.remove(row)) {
                unexpected.remove(row);
            }
        }
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            differences.add(table + ": expected " + missing + ", got " + unexpected);
        }
    }

    /**
     * Every row of a table a case expects, on the columns its expected rows
     * name, bodies by label and sources as the case names them.
     */
    private static List<JSONObject> rows(
            SQLiteDatabase db, Case run, String table, List<JSONObject> expected)
            throws JSONException {
        TreeSet<String> columns = new TreeSet<>();
        for (JSONObject row : expected) {
            columns.addAll(keys(row));
        }
        String flags =
                "CASE WHEN %1$sflags_known = 1 THEN trim("
                        + "CASE WHEN %2$s & 1 THEN '\\Seen ' ELSE '' END"
                        + " || CASE WHEN %2$s & 2 THEN '\\Answered ' ELSE '' END"
                        + " || CASE WHEN %2$s & 4 THEN '\\Flagged ' ELSE '' END"
                        + " || CASE WHEN %2$s & 8 THEN '\\Draft ' ELSE '' END"
                        + " || CASE WHEN %2$s & 16 THEN '\\Deleted ' ELSE '' END"
                        + " || coalesce((%3$s), '')) END";
        String query;
        switch (table) {
            case "items":
                query =
                        "SELECT i.collection, i.link_id, i.seq, "
                                + String.format(
                                        flags,
                                        "i.",
                                        "i.marks",
                                        "SELECT group_concat(f.flag, ' ') FROM placement_flag f"
                                                + " WHERE f.item = i.id")
                                + ", i.object_hash, i.level, i.deleted, i.retained_at,"
                                + " i.retained_by, i.sort_key, i.conflicted, i.conflict_object"
                                + " FROM items i";
                break;
            case "bindings":
                query =
                        "SELECT b.collection, i.link_id, b.source, b.handle, "
                                + String.format(
                                        flags,
                                        "b.base_",
                                        "b.base_marks",
                                        "SELECT group_concat(g.flag, ' ') FROM binding_base_flag g"
                                                + " WHERE g.item = b.item AND g.source = b.source")
                                + ", b.base_object, b.base_revision, b.base_present, b.conflicted,"
                                + " b.conflict_revision, b.conflict_object, b.shared_object,"
                                + " b.round FROM bindings b JOIN items i ON i.id = b.item";
                break;
            case "sources":
                query =
                        "SELECT collection, source, checkpoint, round, round_started_at,"
                                + " round_since, round_until, round_cursor, round_checkpoint,"
                                + " covered_at, covered_since, covered_until, round_band"
                                + " FROM sources";
                break;
            case "collections":
                query =
                        "SELECT id, account, kind, conflict, generation, name, role"
                                + " FROM collections";
                break;
            case "addresses":
                query =
                        "SELECT i.collection, i.link_id, a.role, a.position, a.address, a.name"
                                + " FROM item_address a JOIN items i ON i.id = a.item";
                break;
            case "references":
                query =
                        "SELECT from_kind, from_link_id, to_kind, to_link_id, role, origin"
                                + " FROM item_reference";
                break;
            case "supersessions":
                query =
                        "SELECT i.collection, i.link_id, j.collection, j.link_id"
                                + " FROM supersessions s JOIN items i ON i.id = s.item"
                                + " JOIN items j ON j.id = s.by_item";
                break;
            default:
                throw new IllegalStateException("Unknown table " + table);
        }

        List<JSONObject> rows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(query, null)) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                switch (table) {
                    case "items":
                        row.put("collection", cursor.getString(0));
                        row.put("link_id", cursor.getString(1));
                        row.put("seq", cursor.getLong(2));
                        row.put("flags", flagSet(cursor, 3));
                        row.put("object", run.label(textOrNull(cursor, 4)));
                        row.put("level", cursor.getLong(5));
                        row.put("deleted", cursor.getLong(6));
                        row.put("retained", !cursor.isNull(7));
                        row.put("retained_by", orNull(run.stated(textOrNull(cursor, 8))));
                        row.put("sort_key", cursor.getString(9));
                        row.put("conflicted", cursor.getLong(10));
                        row.put("conflict_object", run.label(textOrNull(cursor, 11)));
                        break;
                    case "bindings":
                        row.put("collection", cursor.getString(0));
                        row.put("link_id", cursor.getString(1));
                        row.put("source", run.stated(cursor.getString(2)));
                        row.put("handle", cursor.getString(3));
                        row.put("base_flags", flagSet(cursor, 4));
                        row.put("base_object", run.label(textOrNull(cursor, 5)));
                        row.put("base_revision", orNull(textOrNull(cursor, 6)));
                        row.put("base_present", cursor.getLong(7));
                        row.put("conflicted", cursor.getLong(8));
                        row.put("conflict_revision", orNull(textOrNull(cursor, 9)));
                        row.put("conflict_object", run.label(textOrNull(cursor, 10)));
                        row.put("shared_object", run.label(textOrNull(cursor, 11)));
                        row.put("round", cursor.isNull(12) ? JSONObject.NULL : cursor.getLong(12));
                        break;
                    case "sources":
                        row.put("collection", cursor.getString(0));
                        row.put("source", run.stated(cursor.getString(1)));
                        row.put("checkpoint", orNull(token(cursor, 2)));
                        row.put("round", cursor.getLong(3));
                        row.put("round_open", !cursor.isNull(4));
                        row.put("round_since", orNull(textOrNull(cursor, 5)));
                        row.put("round_until", orNull(textOrNull(cursor, 6)));
                        row.put("round_cursor", orNull(token(cursor, 7)));
                        row.put("round_checkpoint", orNull(token(cursor, 8)));
                        row.put("covered", !cursor.isNull(9));
                        row.put("covered_since", orNull(textOrNull(cursor, 10)));
                        row.put("covered_until", orNull(textOrNull(cursor, 11)));
                        row.put("round_band", cursor.getLong(12) != 0);
                        break;
                    case "collections":
                        row.put("id", cursor.getString(0));
                        row.put("account", orNull(textOrNull(cursor, 1)));
                        row.put("kind", cursor.getString(2));
                        row.put("conflict", cursor.getString(3));
                        row.put("generation", cursor.getLong(4));
                        row.put("name", cursor.getString(5));
                        row.put("role", orNull(textOrNull(cursor, 6)));
                        break;
                    case "addresses":
                        row.put("collection", cursor.getString(0));
                        row.put("link_id", cursor.getString(1));
                        row.put("role", cursor.getString(2));
                        row.put("position", cursor.getLong(3));
                        row.put("address", cursor.getString(4));
                        row.put("name", orNull(textOrNull(cursor, 5)));
                        break;
                    case "references":
                        row.put("from_kind", cursor.getString(0));
                        row.put("from_link_id", cursor.getString(1));
                        row.put("to_kind", cursor.getString(2));
                        row.put("to_link_id", cursor.getString(3));
                        row.put("role", cursor.getString(4));
                        row.put("origin", cursor.getString(5));
                        break;
                    default:
                        row.put("collection", cursor.getString(0));
                        row.put("link_id", cursor.getString(1));
                        row.put("by_collection", cursor.getString(2));
                        row.put("by_link_id", cursor.getString(3));
                }
                JSONObject named = new JSONObject();
                for (String column : columns) {
                    if (row.has(column)) {
                        named.put(column, row.get(column));
                    }
                }
                rows.add(named);
            }
        }
        return rows;
    }

    /** The summary rows a case expects, read from their tables. */
    private static List<JSONObject> summaries(SQLiteDatabase db, List<JSONObject> expected)
            throws JSONException {
        List<JSONObject> rows = new ArrayList<>();
        for (JSONObject entry : expected) {
            String table = entry.getString("table");
            String[] key = {entry.getString("collection"), entry.getString("link_id")};
            List<String> columns = new ArrayList<>();
            List<String> lists = new ArrayList<>();
            for (String column : keys(entry.getJSONObject("row"))) {
                (listOf(table, column) == null ? columns : lists).add(column);
            }
            Object row = JSONObject.NULL;
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT " + String.join(", ", columns) + " FROM " + table
                                    + " WHERE item = (SELECT id FROM items WHERE collection = ?"
                                    + " AND link_id = ?)",
                            key)) {
                if (cursor.moveToFirst()) {
                    JSONObject read = new JSONObject();
                    for (int at = 0; at < columns.size(); at++) {
                        switch (cursor.getType(at)) {
                            case Cursor.FIELD_TYPE_INTEGER:
                                read.put(columns.get(at), cursor.getLong(at));
                                break;
                            case Cursor.FIELD_TYPE_FLOAT:
                                read.put(columns.get(at), cursor.getDouble(at));
                                break;
                            case Cursor.FIELD_TYPE_STRING:
                                read.put(columns.get(at), cursor.getString(at));
                                break;
                            default:
                                read.put(columns.get(at), JSONObject.NULL);
                        }
                    }
                    row = read;
                }
            }
            for (String column : lists) {
                if (row == JSONObject.NULL) {
                    break;
                }
                String[] list = listOf(table, column);
                JSONArray values = new JSONArray();
                try (Cursor cursor =
                        db.rawQuery(
                                "SELECT " + list[1] + " FROM " + list[0] + " WHERE item ="
                                        + " (SELECT id FROM items WHERE collection = ?"
                                        + " AND link_id = ?) ORDER BY position",
                                key)) {
                    while (cursor.moveToNext()) {
                        values.put(cursor.getString(0));
                    }
                }
                ((JSONObject) row).put(column, values);
            }
            JSONObject read = new JSONObject();
            read.put("table", table);
            read.put("collection", key[0]);
            read.put("link_id", key[1]);
            read.put("row", row);
            rows.add(read);
        }
        return rows;
    }

    /**
     * A flag set as a read answers it (STORAGE §4.4), as the sorted array a
     * case states, null for an unknown one.
     */
    private static Object flagSet(Cursor cursor, int column) {
        if (cursor.isNull(column)) {
            return JSONObject.NULL;
        }
        TreeSet<String> flags = new TreeSet<>();
        for (String flag : cursor.getString(column).split(" ")) {
            if (!flag.isEmpty()) {
                flags.add(flag);
            }
        }
        return new JSONArray(flags);
    }

    private static String textOrNull(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getString(column);
    }

    /** An opaque token column as the text a case states it as. */
    private static String token(Cursor cursor, int column) {
        if (cursor.isNull(column)) {
            return null;
        }
        return cursor.getType(column) == Cursor.FIELD_TYPE_BLOB
                ? new String(cursor.getBlob(column), StandardCharsets.UTF_8)
                : cursor.getString(column);
    }

    private static Object orNull(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    /** A text field, null when absent or null, never the string "null". */
    private static String text(JSONObject object, String key) throws JSONException {
        return object.isNull(key) ? null : object.getString(key);
    }

    /** A text field as the bytes a token column stores. */
    private static byte[] blob(JSONObject object, String key) throws JSONException {
        String text = text(object, key);
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    }

    /** A kind's bare media type. */
    private static String bare(String kind) {
        return kind.split(";")[0].trim();
    }

    private static String utf8OrNull(byte[] body) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException binary) {
            return null;
        }
    }

    /** A body the wire carries as text alone. */
    private static String utf8(byte[] body) {
        String text = utf8OrNull(body);
        if (text == null) {
            throw new IllegalStateException("The wire carries this body as text, it is not");
        }
        return text;
    }

    private static List<String> keys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> each = object.keys(); each.hasNext(); ) {
            keys.add(each.next());
        }
        return keys;
    }

    private static List<JSONObject> list(JSONArray array) throws JSONException {
        List<JSONObject> list = new ArrayList<>();
        for (int index = 0; array != null && index < array.length(); index++) {
            list.add(array.getJSONObject(index));
        }
        return list;
    }

    /** Rows as their canonical text, in its order. */
    private static List<String> sorted(List<JSONObject> rows) throws JSONException {
        List<String> sorted = new ArrayList<>();
        for (JSONObject row : rows) {
            sorted.add(canon(row));
        }
        Collections.sort(sorted);
        return sorted;
    }

    /** A JSON value as text two equal values share: keys sorted. */
    private static String canon(Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> parts = new ArrayList<>();
            for (String key : new TreeSet<>(keys(object))) {
                parts.add(JSONObject.quote(key) + ":" + canon(object.get(key)));
            }
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<String> parts = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                parts.add(canon(array.get(index)));
            }
            return "[" + String.join(",", parts) + "]";
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return number == Math.rint(number)
                    ? Long.toString(((Number) value).longValue())
                    : Double.toString(number);
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        return JSONObject.quote(value.toString());
    }
}
