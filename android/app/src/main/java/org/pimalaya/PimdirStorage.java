package org.pimalaya;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.PimdirSql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * io-pimdir's storage seam over a pimdir store.
 *
 * <p>The engine runs in Rust and is I/O-free, so every storage yield arrives
 * here as a JSON envelope and is serviced against Android's own SQLite. What
 * changes from the previous {@code OfflineStore} is only the schema underneath:
 * pimdir's generic {@code items} and {@code bindings} instead of a
 * contacts-shaped {@code card} and {@code membership}, with bodies moved out to
 * content-addressed blobs.
 *
 * <p>The mapping is the whole of it:
 *
 * <ul>
 *   <li>a placement is one {@code items} row keyed {@code (collection, link_id)}
 *       plus one {@code bindings} row per source, carrying that source's handle
 *       and the three-way-merge base
 *   <li>a body is an object: written to the blob directory under the hash the
 *       engine computed, referenced by {@code object_hash}, refcounted
 *   <li>the vCard index columns become the typed summary row STORAGE Annex A
 *       fixes ({@link PimdirSummary}), with the addresses beside it in
 *       {@code item_address}, and the ordering they never had becomes
 *       {@code sort_key}
 * </ul>
 *
 * <p>Writes are one transaction, and drops are held to the end of a batch and
 * cancelled by an upsert of the same placement: an accepted create rekeys by
 * dropping its placeholder and upserting the assigned handle, and applying those
 * in arrival order would delete what it had just renamed.
 */
final class PimdirStorage {
    /** The source name this app syncs a server under. */
    static final String SERVER = "server";

    /** The source name the phone's own contacts are reconciled under. */
    static final String PHONE = "phone";

    /** Marks an engine collection id as an addressbook's phone spoke. */
    private static final String PHONE_PREFIX = "phone:";

    /**
     * The namespace a staged create's handle sits under until a push assigns
     * one (SYNC.md §2): {@code U+0001} followed by the link id.
     *
     * <p>A name no protocol hands out, so it collides with no member the next
     * enumeration lists and both sides of the bridge derive one create's change
     * key alike. It is the engine's spelling, so the store hands out the same
     * one for a placement no source binds yet, and strips it back off wherever
     * a handle is resolved or a resource is named.
     */
    private static final String PROVISIONAL = String.valueOf((char) 0x01);

    /**
     * The longest scope list a load narrows on, past which it reads the whole
     * collection. A handle scope binds its list twice, so the bound stays well
     * under SQLite's variable limit.
     */
    private static final int SCOPE_MAX = 400;

    private final PimdirDb store;
    private final PimdirBlobs blobs;

    PimdirStorage(PimdirDb store) {
        this.store = store;
        this.blobs = new PimdirBlobs(store.blobs());
    }

    // ---- the two spokes ---------------------------------------------------

    /** The phone collection id of an addressbook (the second engine spoke). */
    static String phoneCollection(String collection) {
        return PHONE_PREFIX + collection;
    }

    /** True when the engine collection id addresses the phone spoke. */
    static boolean isPhoneCollection(String collection) {
        return collection.startsWith(PHONE_PREFIX);
    }

    /**
     * The stored collection behind an engine collection id.
     *
     * <p>The engine reconciles a book against two remotes, the server and the
     * phone, and names them as two collections. The store holds <strong>one</strong>
     * collection with two bindings per item instead, which is what pimdir's
     * {@code bindings} table is for: one shared truth, one row per source that
     * syncs it. That is also what makes cross-spoke propagation free, since a
     * phone-won write leaves the item diverging from the server's base by
     * construction rather than by a second write.
     */
    static String collectionOf(String collection) {
        return isPhoneCollection(collection)
                ? collection.substring(PHONE_PREFIX.length())
                : collection;
    }

    /** Which of the two sources an engine collection id speaks for. */
    static String sourceOf(String collection) {
        return isPhoneCollection(collection) ? PHONE : SERVER;
    }

    // ---- provisional handles ----------------------------------------------

    /** The handle a create of this identity is staged under (SYNC.md §2). */
    static String provisionalOf(String linkId) {
        return PROVISIONAL + linkId;
    }

    /**
     * The name behind an engine handle: the identity for a staged create, the
     * handle itself for a member a source already binds.
     *
     * <p>What a create is pushed as: a server is offered the item's own name,
     * never the provisional one, which is a spelling of this side's bookkeeping
     * and would land as a resource no reader could open.
     */
    static String nameOf(String handle) {
        return handle.startsWith(PROVISIONAL) ? handle.substring(PROVISIONAL.length()) : handle;
    }

    // ---- load -------------------------------------------------------------

    /**
     * The collection's placements and its sync cursor, as the reply to a load
     * yield.
     *
     * <p>Retained rows are excluded: a retained item is the store's memory of a
     * removal that has finished propagating (SPEC.md §11), and the merge
     * reconciles only what load returns, so returning one would re-derive it.
     *
     * <p>{@code scope} narrows the read to the rows the engine is about to
     * touch, so a flag change on one message costs one row rather than the size
     * of the mailbox. It is a floor and not a ceiling: answering more than it
     * asks for stays correct, which is what {@link #scopeClause} falls back to
     * when the list is longer than one statement can bind.
     */
    JSONObject loadCollection(String collection, JSONObject scope) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        String stored = collectionOf(collection);
        String source = sourceOf(collection);
        JSONObject reply = new JSONObject();
        JSONArray placements = new JSONArray();
        reply.put("placements", placements);

        String checkpoint = checkpointOf(db, stored, source);
        if (checkpoint != null) {
            reply.put("checkpoint", checkpoint);
        }

        List<String> args = new ArrayList<>(List.of(source, stored));
        String narrowed = scopeClause(scope, args);

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.link_id, i.flags, i.object_hash, i.sort_key, i.level,"
                                + " b.handle, b.base_flags, b.base_object, b.base_revision,"
                                + " b.base_present, b.conflicted, b.conflict_revision,"
                                + " b.conflict_object"
                                + " FROM items i"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ? AND i.deleted = 0"
                                + " AND i.retained_at IS NULL" + narrowed,
                        args.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                placements.put(placementOf(collection, cursor));
            }
        }

        for (JSONObject probe : probes(db, stored, source, scope)) {
            placements.put(probe);
        }
        return reply;
    }

    /**
     * The source's unnamed handles, as probed placements beside the named ones
     * (SYNC.md §3).
     *
     * <p>A probe is a handle an enumeration reported and no fetch has resolved
     * an identity for yet. It is not an item: the store keys those by link id,
     * and filing a probe under its handle would mint an item the next fetch has
     * to un-mint, which is what puts two bindings on one handle. So it lives in
     * its own table until a fetch names it, and rides back here so the merge
     * sees the member and the sync knows a create must wait.
     *
     * <p>A {@code links} scope reads no probe: a probe has no link id, so no
     * such scope can name one.
     */
    private List<JSONObject> probes(
            SQLiteDatabase db, String collection, String source, JSONObject scope)
            throws JSONException {
        JSONArray handles = scope == null ? null : scope.optJSONArray("handles");
        if (scope != null && "links".equals(scope.optString("kind", "all"))) {
            return List.of();
        }

        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("source", source);
        boolean narrowed = handles != null && handles.length() > 0
                && handles.length() <= SCOPE_MAX;
        if (narrowed) {
            values.put("handles", handles.toString());
        }
        PimdirSql.Bound bound =
                PimdirSql.bind(narrowed ? "LOAD_PROBES_BY_HANDLE" : "LOAD_PROBES", values);

        List<JSONObject> probed = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(bound.sql, stringsOf(bound.args))) {
            while (cursor.moveToNext()) {
                JSONObject placement = new JSONObject();
                placement.put("collection", engineCollectionOf(collection, source));
                placement.put("handle", cursor.getString(0));
                placement.put("level", "probed");
                placement.put("sortKey", "");
                placement.put("status", "clean");
                if (!cursor.isNull(1)) {
                    placement.put("flags", arrayOf(cursor.getString(1)));
                }
                probed.add(placement);
            }
        }
        return probed;
    }

    /** The engine collection id a stored collection carries for one source. */
    private static String engineCollectionOf(String collection, String source) {
        return PHONE.equals(source) ? phoneCollection(collection) : collection;
    }

    /** A bound statement's arguments as the strings {@code rawQuery} takes. */
    private static String[] stringsOf(Object[] args) {
        String[] strings = new String[args.length];
        for (int index = 0; index < args.length; index++) {
            strings[index] = args[index] == null ? null : args[index].toString();
        }
        return strings;
    }

    /**
     * The extra {@code WHERE} the load scope asks for, appending its binds to
     * {@code args}, or the empty string for a whole-collection read.
     *
     * <p>A list longer than {@link #SCOPE_MAX} widens back to the whole
     * collection rather than being chunked: SQLite binds a bounded number of
     * variables per statement, and over-answering a floor is correct where a
     * truncated list would silently hide rows from the merge.
     */
    private static String scopeClause(JSONObject scope, List<String> args) throws JSONException {
        if (scope == null) {
            return "";
        }

        String kind = scope.optString("kind", "all");
        JSONArray values = scope.optJSONArray("handles".equals(kind) ? "handles" : "links");
        if (values == null || values.length() == 0 || values.length() > SCOPE_MAX) {
            return "";
        }

        StringBuilder marks = new StringBuilder();
        for (int index = 0; index < values.length(); index++) {
            marks.append(index == 0 ? "?" : ",?");
        }

        if (!"handles".equals(kind)) {
            for (int index = 0; index < values.length(); index++) {
                args.add(values.getString(index));
            }
            return " AND i.link_id IN (" + marks + ")";
        }

        // NOTE: an unbound row is placed under its provisional handle
        // (placementOf), so a handle scope has to reach it under the identity
        // that handle spells, or the row the engine staged and is now editing
        // comes back missing.
        for (int index = 0; index < values.length(); index++) {
            args.add(values.getString(index));
        }
        for (int index = 0; index < values.length(); index++) {
            args.add(nameOf(values.getString(index)));
        }
        return " AND (b.handle IN (" + marks + ")"
                + " OR (b.handle IS NULL AND i.link_id IN (" + marks + ")))";
    }

    /**
     * One item-plus-binding row as the engine's placement shape.
     *
     * <p>The summary is deliberately not read back. A write that carries none
     * keeps the stored row (SPEC.md §9.3's rule for the sort key, which the
     * summary follows), and the engine only ever sets one from a derivation:
     * a fetch's or an edit's. So the row a load omits is the row the next
     * write preserves, and reading five typed tables back into a placement
     * would buy the engine nothing it does with it.
     */
    private JSONObject placementOf(String collection, Cursor cursor) throws JSONException {
        String linkId = cursor.getString(0);
        String handle = cursor.isNull(5) ? provisionalOf(linkId) : cursor.getString(5);

        JSONObject placement = new JSONObject();
        placement.put("collection", collection);
        placement.put("handle", handle);
        placement.put("linkId", linkId);
        if (!cursor.isNull(2)) {
            placement.put("object", cursor.getString(2));
        }
        placement.put("sortKey", cursor.isNull(3) ? "" : cursor.getString(3));
        placement.put("level", levelName(cursor.getInt(4)));
        // NOTE: a NULL column is a set nobody has read, which the wire says by
        // leaving the field out. Sending an empty array instead would push that
        // absence onto whichever side did know the markers.
        if (!cursor.isNull(1)) {
            placement.put("flags", arrayOf(cursor.getString(1)));
        }

        // A binding's conflict is this source diverging from its own remote; the
        // item's is the cross-source one. The engine wants the per-source view.
        boolean conflicted = !cursor.isNull(10) && cursor.getInt(10) == 1;
        placement.put("status", conflicted ? "conflict" : "clean");
        if (!cursor.isNull(11)) {
            placement.put("conflictRevision", cursor.getString(11));
        }
        if (!cursor.isNull(12)) {
            placement.put("conflictObject", cursor.getString(12));
        }

        if (!cursor.isNull(5)) {
            if (hasBase(cursor)) {
                JSONObject base = new JSONObject();
                if (!cursor.isNull(6)) {
                    base.put("flags", arrayOf(cursor.getString(6)));
                }
                if (!cursor.isNull(7)) {
                    base.put("object", cursor.getString(7));
                }
                if (!cursor.isNull(8)) {
                    base.put("revision", cursor.getString(8));
                }
                placement.put("base", base);
            }
        } else {
            // NOTE: no base means a pending create, and a pending create of an
            // item another collection already holds on the same source is a
            // membership addition rather than a second upload. Naming where it
            // comes from is what lets the push adapter tell the two apart.
            JSONObject origin = originOf(collection, linkId);
            if (origin != null) {
                placement.put("origin", origin);
            }
        }
        return placement;
    }

    /**
     * Where else this source already holds the same item, as the placement's
     * origin, or null when it holds it nowhere else.
     *
     * <p>This is what makes "add this contact to a second address book" a
     * membership patch on the account-level backends: the body is already on the
     * account, so uploading it again would mint a second card rather than place
     * the one that exists.
     */
    private JSONObject originOf(String collection, String linkId) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        String stored = collectionOf(collection);
        String source = sourceOf(collection);
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT b.collection, b.handle FROM bindings b"
                                + " JOIN items i ON i.collection = b.collection"
                                + " AND i.link_id = b.link_id"
                                + " WHERE b.link_id = ? AND b.source = ? AND b.collection <> ?"
                                + " AND i.deleted = 0 AND i.retained_at IS NULL LIMIT 1",
                        new String[] {linkId, source, stored})) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            JSONObject origin = new JSONObject();
            origin.put("collection", cursor.getString(0));
            origin.put("handle", cursor.getString(1));
            return origin;
        }
    }

    // ---- lookup -----------------------------------------------------------

    /**
     * Which link ids already have a stored body, as the reply to a lookup yield.
     *
     * <p>Deliberately empty for contacts and answered for the rest. The engine's
     * cross-collection dedup assumes a link id names immutable bytes, which
     * holds for a message and does not for a card: two replicas sharing a vCard
     * UID diverge legitimately, so one body must never stand in for another's.
     * Mail and calendar bodies are immutable under their link id, so they dedup.
     */
    JSONObject lookupObjects(JSONArray links) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        JSONObject objects = new JSONObject();

        for (int index = 0; index < links.length(); index++) {
            String linkId = links.getString(index);
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT i.object_hash, o.size FROM items i"
                                    + " JOIN collections c ON c.id = i.collection"
                                    + " JOIN objects o ON o.hash = i.object_hash"
                                    + " WHERE i.link_id = ? AND c.kind <> ? LIMIT 1",
                            new String[] {linkId, PimdirSummary.CONTACT})) {
                if (cursor.moveToFirst() && blobs.has(cursor.getString(0))) {
                    // The size is the witness an immutable link needs (SYNC §6):
                    // the engine records the object without ever reading it.
                    JSONObject object = new JSONObject();
                    object.put("hash", cursor.getString(0));
                    object.put("size", cursor.getLong(1));
                    objects.put(linkId, object);
                }
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("objects", objects);
        return reply;
    }

    // ---- write ------------------------------------------------------------

    /**
     * Applies one batch of engine writes atomically, returning what it did to
     * the items for the sync report.
     */
    JSONArray applyWrites(JSONArray writes) throws JSONException {
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            Map<String, byte[]> bodies = new HashMap<>();
            List<JSONObject> drops = new ArrayList<>();
            Set<String> upserted = new HashSet<>();
            JSONArray effects = new JSONArray();
            Set<String> superseded = supersededHandles(writes);

            for (int index = 0; index < writes.length(); index++) {
                JSONObject op = writes.getJSONObject(index);
                switch (op.getString("op")) {
                    case "storeObject":
                        storeObject(db, op, bodies);
                        break;
                    case "upsert": {
                        JSONObject placement = op.getJSONObject("placement");
                        String kind = applyUpsert(db, placement, superseded);
                        if (kind != null) {
                            effects.put(effect(placement.getString("collection"),
                                    placement.getString("handle"), kind));
                        }
                        upserted.add(placement.getString("collection") + "\n"
                                + placement.getString("handle"));
                        break;
                    }
                    case "drop":
                        drops.add(op);
                        break;
                    case "setCheckpoint":
                        setCheckpoint(db, op);
                        break;
                    default:
                        throw new JSONException("Unknown write op " + op.getString("op"));
                }
            }

            // NOTE: held to the end and cancelled by an upsert of the same
            // placement, so an accepted create's rekey does not delete itself.
            for (JSONObject drop : drops) {
                String collection = drop.getString("collection");
                String handle = drop.getString("handle");
                if (upserted.contains(collection + "\n" + handle)) {
                    continue;
                }
                // NOTE: only a delete retires the item. A superseded drop is a
                // provisional handle an accepted add replaced and a rekeyed one
                // a handle a rebuild renumbered, so the row goes and the item
                // stays; reading either as a removal would retain what the same
                // batch just renamed, and propagate a delete nobody asked for.
                boolean deleted = "deleted".equals(drop.optString("reason", "deleted"));
                applyDrop(db, collection, handle, deleted);
                if (deleted) {
                    effects.put(effect(collection, handle, "removed"));
                }
            }

            collectGarbage(db);
            db.setTransactionSuccessful();
            return effects;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * The handles this batch supersedes, keyed by stored collection and source.
     *
     * <p>Read ahead of the upserts because they are what licenses a rebind: a
     * binding pins one handle, so an upsert moving it is a source holding one
     * identity twice unless the same batch says that handle is being replaced.
     * Both non-deleting reasons license it: an accepted add reconciles its
     * provisional handle, and a rebuild renumbers the whole spine.
     */
    private static Set<String> supersededHandles(JSONArray writes) throws JSONException {
        Set<String> superseded = new HashSet<>();
        for (int index = 0; index < writes.length(); index++) {
            JSONObject op = writes.getJSONObject(index);
            if ("drop".equals(op.getString("op"))
                    && !"deleted".equals(op.optString("reason", "deleted"))) {
                String engine = op.getString("collection");
                superseded.add(
                        collectionOf(engine) + "\n" + sourceOf(engine) + "\n"
                                + op.getString("handle"));
            }
        }
        return superseded;
    }

    /** Files a body under the hash the engine computed and indexes it. */
    private void storeObject(SQLiteDatabase db, JSONObject op, Map<String, byte[]> bodies)
            throws JSONException {
        String hash = op.getString("hash");
        byte[] body = op.optString("body", "").getBytes(StandardCharsets.UTF_8);
        bodies.put(hash, body);

        try {
            blobs.put(hash, body);
        } catch (IOException error) {
            throw new JSONException("Could not store the body " + hash + ": " + error.getMessage());
        }
        db.execSQL(
                "INSERT INTO objects(hash, size, refcount) VALUES(?, ?, 0)"
                        + " ON CONFLICT(hash) DO NOTHING",
                new Object[] {hash, body.length});
    }

    /**
     * Writes one placement, returning what changed for the sync report:
     * {@code created}, {@code changed} when the body differs, or null for a
     * bookkeeping-only upsert (flags, bases).
     */
    private String applyUpsert(SQLiteDatabase db, JSONObject placement, Set<String> superseded)
            throws JSONException {
        String engineCollection = placement.getString("collection");
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        String handle = placement.getString("handle");
        // The identity the placement names, else the one its handle is already
        // bound to (STORAGE §10). Never the handle itself: an item is keyed by
        // its link id, so filing an unnamed handle as one mints an item the
        // next fetch has to un-mint, leaving two bindings on one handle.
        String bound = linkOf(db, collection, source, handle);
        String linkId = placement.isNull("linkId") ? bound : placement.optString("linkId", bound);
        String object = placement.isNull("object") ? null : placement.optString("object", null);
        JSONObject summary = placement.optJSONObject("summary");
        String sortKey = placement.optString("sortKey", "");
        int level = levelValue(placement.optString("level", "probed"));
        // NOTE: absent means nobody has read the markers, which the column says
        // as NULL. Storing "[]" for it would turn a never-read set into an
        // authoritative "carries none" and clear whatever the other side knew.
        String flags = placement.optJSONArray("flags") == null
                ? null
                : placement.getJSONArray("flags").toString();

        if (linkId == null) {
            upsertProbe(db, collection, source, handle, flags);
            return null;
        }
        deleteProbe(db, collection, source, handle);

        // The handle named another identity until now: a resource replaced in
        // place, or a spine whose fetch resolved what its enumeration could
        // not. Its binding is retired first, as a delete of that handle would
        // (STORAGE §10), since a handle names one item per source.
        if (bound != null && !bound.equals(linkId)) {
            applyDrop(db, engineCollection, handle, true);
        }

        String previousObject = null;
        boolean exists = false;
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT object_hash FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            if (cursor.moveToFirst()) {
                exists = true;
                previousObject = cursor.isNull(0) ? null : cursor.getString(0);
            }
        }

        if (!exists) {
            db.execSQL(
                    "INSERT INTO items(collection, link_id, seq, flags, object_hash,"
                            + " sort_key, level) VALUES(?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {
                        collection, linkId, nextSeq(db, linkId), flags, object, sortKey, level
                    });
        } else {
            // A write that does not restate the key must preserve it (SPEC.md
            // §9.3): the reference write is a replace-all, so blanking it here
            // would reset the ordering of every item a sync touched.
            db.execSQL(
                    "UPDATE items SET flags = ?, object_hash = ?, level = ?,"
                            + " sort_key = CASE WHEN ? = '' THEN sort_key ELSE ? END,"
                            + " deleted = 0, retained_at = NULL, retained_by = NULL"
                            + " WHERE collection = ? AND link_id = ?",
                    new Object[] {flags, object, level, sortKey, sortKey, collection, linkId});
        }

        // On the same terms as the sort key: a write carrying no summary keeps
        // the stored row, which is what a flag push and a pulled deletion do.
        PimdirSummary.write(db, collection, linkId, summary);
        writeBinding(db, collection, source, linkId, handle, placement, superseded);
        adjustRefcount(db, previousObject, object);

        if (!exists) {
            return "created";
        }
        return sameObject(previousObject, object) ? null : "changed";
    }

    /**
     * The source's binding of the item: its handle there, its merge base, and
     * the divergence it is waiting on.
     *
     * <p>A source holding one identity twice (a double delivery, a retried
     * append, a restore, a migration) never reaches here as one binding: the
     * engine mints a second link id for the second copy, so the two are two
     * items and each binds its own handle. What does move a handle is the one
     * licensed rebind, a placeholder reconciled to its assigned handle or a
     * spine rebuilt onto a new handle space, and the batch says so by
     * superseding the old handle in the same write.
     */
    private void writeBinding(SQLiteDatabase db, String collection, String source, String linkId,
            String handle, JSONObject placement, Set<String> superseded) throws JSONException {
        JSONObject base = placement.optJSONObject("base");
        String baseFlags = base == null || base.optJSONArray("flags") == null
                ? null
                : base.getJSONArray("flags").toString();
        String baseObject = base == null || base.isNull("object")
                ? null
                : base.optString("object", null);
        String baseRevision = base == null || base.isNull("revision")
                ? null
                : base.optString("revision", null);
        boolean conflicted = "conflict".equals(placement.optString("status", "clean"));
        String conflictRevision = placement.isNull("conflictRevision")
                ? null
                : placement.optString("conflictRevision", null);
        String conflictObject = placement.isNull("conflictObject")
                ? null
                : placement.optString("conflictObject", null);

        String bound = handleOf(db, collection, linkId, source);
        if (bound != null && !bound.equals(handle)
                && !superseded.contains(collection + "\n" + source + "\n" + bound)) {
            throw new PimalayaException(
                    "Binding " + collection + "/" + linkId + " on " + source
                            + " is bound to " + bound + " and cannot be repointed to " + handle);
        }

        // NOTE: a base body and a captured conflicting body are references like
        // any other (SPEC.md §5), so the refcount has to move with them.
        // Counting only the current object would collect a body the merge base
        // still names, which is not a leak but a dangling reference: the foreign
        // key refuses it, and a store that got past it would have lost what the
        // next three-way merge diffs against.
        String previousBase = baseObjectOf(db, collection, linkId, source);
        String previousConflict = conflictObjectOf(db, collection, linkId, source);

        db.execSQL(
                "INSERT INTO bindings(collection, link_id, source, handle, base_flags,"
                        + " base_object, base_revision, base_present, conflicted,"
                        + " conflict_revision, conflict_object)"
                        + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(collection, link_id, source) DO UPDATE SET"
                        + " handle = excluded.handle, base_flags = excluded.base_flags,"
                        + " base_object = excluded.base_object,"
                        + " base_revision = excluded.base_revision,"
                        + " base_present = excluded.base_present,"
                        + " conflicted = excluded.conflicted,"
                        + " conflict_revision = excluded.conflict_revision,"
                        + " conflict_object = excluded.conflict_object",
                new Object[] {
                    collection, linkId, source, handle, baseFlags, baseObject, baseRevision,
                    base == null ? 0 : 1, conflicted ? 1 : 0, conflictRevision, conflictObject
                });
        adjustRefcount(db, previousBase, baseObject);
        adjustRefcount(db, previousConflict, conflictObject);
    }

    /**
     * Retires the placement the source no longer holds.
     *
     * <p>Retained, never deleted (SPEC.md §11): the store is the only holder of
     * a body a remote has expunged, so the row keeps its {@code object_hash},
     * the blob stays pinned, and only an explicit purge removes it.
     *
     * <p>{@code deleted} is what separates the item going from only this row
     * going: a superseded row drops its binding and leaves the item alone, so a
     * rebuilt spine is renumbered rather than retired.
     */
    private void applyDrop(
            SQLiteDatabase db, String engineCollection, String handle, boolean deleted) {
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        // A handle the source no longer holds is no longer probed either,
        // whichever of the two the store was carrying it as.
        deleteProbe(db, collection, source, handle);

        String linkId = linkOf(db, collection, source, handle);
        if (linkId == null) {
            return;
        }
        String base = baseObjectOf(db, collection, linkId, source);
        String conflict = conflictObjectOf(db, collection, linkId, source);
        db.execSQL(
                "DELETE FROM bindings WHERE collection = ? AND link_id = ? AND source = ?",
                new Object[] {collection, linkId, source});
        adjustRefcount(db, base, null);
        adjustRefcount(db, conflict, null);

        if (!deleted) {
            return;
        }

        boolean held;
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM bindings WHERE collection = ? AND link_id = ? LIMIT 1",
                        new String[] {collection, linkId})) {
            held = cursor.moveToFirst();
        }
        if (!held) {
            db.execSQL(
                    "UPDATE items SET deleted = 1,"
                            + " retained_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),"
                            + " retained_by = ? WHERE collection = ? AND link_id = ?",
                    new Object[] {source, collection, linkId});
        }
    }

    private void setCheckpoint(SQLiteDatabase db, JSONObject op) throws JSONException {
        String collection = collectionOf(op.getString("collection"));
        String source = sourceOf(op.getString("collection"));
        db.execSQL(PimdirSql.of("ENSURE_COLLECTION").replace(":collection", "?")
                        .replace(":account", "NULL"),
                new Object[] {collection, collection});
        // NOTE: the column is BLOB and the tables are STRICT, so a TEXT bind is
        // refused outright rather than coerced. A checkpoint is opaque to the
        // store anyway (a QRESYNC state, a JMAP state string, a DAV sync-token),
        // which is exactly why the schema types it as bytes.
        byte[] checkpoint =
                op.optString("checkpoint", "").getBytes(StandardCharsets.UTF_8);
        db.execSQL(
                "INSERT INTO sources(collection, source, checkpoint) VALUES(?, ?, ?)"
                        + " ON CONFLICT(collection, source) DO UPDATE SET"
                        + " checkpoint = excluded.checkpoint",
                new Object[] {collection, source, checkpoint});
    }

    // ---- the driver's own reads -------------------------------------------

    /**
     * The item behind an engine handle, for the push adapter: what a change
     * addresses and what it is conditioned on. Null when the handle is unknown.
     *
     * <p>{@code id} is the link id (the identity a backend addresses an item by)
     * and {@code uri} the handle this source holds it under; on CardDAV they
     * differ, elsewhere they are the same string.
     */
    JSONObject loadRow(String engineCollection, String handle) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        String linkId = linkFor(db, collection, source, handle);
        if (linkId == null) {
            return null;
        }

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.object_hash, i.deleted, b.handle, b.base_object, b.base_revision"
                                + " FROM items i"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ? AND i.link_id = ?",
                        new String[] {source, collection, linkId})) {
            if (!cursor.moveToFirst()) {
                return null;
            }

            JSONObject row = new JSONObject();
            row.put("id", linkId);
            row.put("uri", cursor.isNull(2) ? handle : cursor.getString(2));
            if (!cursor.isNull(4)) {
                row.put("etag", cursor.getString(4));
            }
            row.put("vcard", bodyOf(cursor.isNull(0) ? null : cursor.getString(0)));
            if (!cursor.isNull(3)) {
                row.put("baseVcard", bodyOf(cursor.getString(3)));
            }
            row.put("deleted", cursor.getInt(1) == 1);
            return row;
        }
    }

    /**
     * The three documents a manual conflict resolution needs: the staged local
     * body, the base both sides diverged from, and the captured remote one.
     * Null while no remote body has been captured, so the row is not resolvable.
     *
     * <p>The base falls back to the local body, which is what a create collision
     * leaves: with nothing previously agreed, the local side reads unchanged and
     * every remote change merges in cleanly.
     */
    JSONObject loadConflict(String engineCollection, String handle) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        String linkId = linkFor(db, collection, source, handle);
        if (linkId == null) {
            return null;
        }

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.object_hash, b.conflict_object, b.base_object FROM items i"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ? AND i.link_id = ?",
                        new String[] {source, collection, linkId})) {
            if (!cursor.moveToFirst() || cursor.isNull(1)) {
                return null;
            }

            String local = bodyOf(cursor.isNull(0) ? null : cursor.getString(0));
            JSONObject bodies = new JSONObject();
            bodies.put("local", local);
            bodies.put("base", cursor.isNull(2) ? local : bodyOf(cursor.getString(2)));
            bodies.put("remote", bodyOf(cursor.getString(1)));
            return bodies;
        }
    }

    /**
     * The handles of one collection the hydrate pass upgrades: those still
     * below the full detail level, plus the conflicted ones whose diverging
     * remote body has not landed yet.
     *
     * <p>A conflicted row reads as full and holds a body, just not the one it
     * lacks: the engine marks the divergence and fetches nothing, so a
     * conflict holding no {@code conflict_object} is the request for it, and
     * the upgrade pass is what answers. Leaving it out is what would make a
     * conflict unresolvable offline.
     */
    List<String> handlesBelowFull(String engineCollection) {
        SQLiteDatabase db = store.getReadableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.link_id, b.handle FROM items i"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ? AND i.deleted = 0"
                                + " AND i.retained_at IS NULL"
                                + " AND (i.level < 2 OR i.object_hash IS NULL"
                                + " OR (b.conflicted = 1 AND b.conflict_object IS NULL))",
                        new String[] {source, collection})) {
            List<String> handles = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                handles.add(
                        cursor.isNull(1) ? provisionalOf(cursor.getString(0)) : cursor.getString(1));
            }

            // A probe is the definition of below full: a handle the
            // enumeration reported and nothing has read an identity, let alone
            // a body, for. It holds no item row, so the join above cannot see
            // it, and leaving it out is leaving every freshly enumerated
            // member unfetched forever.
            try (Cursor probed =
                    db.rawQuery(
                            "SELECT handle FROM probes WHERE collection = ? AND source = ?",
                            new String[] {collection, source})) {
                while (probed.moveToNext()) {
                    handles.add(probed.getString(0));
                }
            }
            return handles;
        }
    }

    /**
     * One collection's unresolved conflicts, each with the three documents the
     * resolution merge needs (the staged local body, the base both sides
     * diverged from, and the diverging remote one) plus the remote revision
     * observed when the binding was marked conflicted.
     *
     * <p>A row whose remote body has not landed yet is left out rather than
     * offered half-resolvable: the hydrate pass asks for it
     * ({@link #handlesBelowFull}) and the next run sees it whole.
     */
    List<JSONObject> loadConflicts(String engineCollection) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.link_id, i.object_hash, b.handle, b.base_object,"
                                + " b.base_revision, b.conflict_revision, b.conflict_object"
                                + " FROM items i"
                                + " JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ? AND i.deleted = 0"
                                + " AND b.conflicted = 1 AND b.conflict_object IS NOT NULL",
                        new String[] {source, collection})) {
            List<JSONObject> conflicts = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                String linkId = cursor.getString(0);
                JSONObject conflict = new JSONObject();
                conflict.put("handle", cursor.isNull(2) ? linkId : cursor.getString(2));
                conflict.put("id", linkId);
                conflict.put("uri", cursor.isNull(2) ? linkId : cursor.getString(2));
                if (!cursor.isNull(4)) {
                    conflict.put("etag", cursor.getString(4));
                }
                conflict.put("vcard", bodyOf(cursor.isNull(1) ? null : cursor.getString(1)));
                if (!cursor.isNull(3)) {
                    conflict.put("baseVcard", bodyOf(cursor.getString(3)));
                }
                if (!cursor.isNull(5)) {
                    conflict.put("conflictRevision", cursor.getString(5));
                }
                conflict.put("remoteVcard", bodyOf(cursor.getString(6)));
                conflicts.add(conflict);
            }
            return conflicts;
        }
    }

    /**
     * Refreshes the remote revision a conflicted binding was marked against, so
     * the resolving push is conditioned on the state the merge reconciled with
     * rather than the one the conflict was first noticed at.
     */
    void setConflictRevision(String engineCollection, String handle, String revision) {
        SQLiteDatabase db = store.getWritableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        String linkId = linkFor(db, collection, source, handle);
        if (linkId == null) {
            return;
        }
        db.execSQL(
                "UPDATE bindings SET conflict_revision = ? WHERE collection = ?"
                        + " AND link_id = ? AND source = ?",
                new Object[] {revision, collection, linkId, source});
    }

    // ---- the quiet path ---------------------------------------------------

    /**
     * Whether the source has anything to reconcile: a handle it reported and
     * nothing has named, an unresolved conflict, an item it still holds that
     * the store has retired, or one whose body has moved past the base it last
     * agreed on.
     *
     * <p>This exists because ContactsContract has no per-account change token,
     * so the phone pass cannot ask "did anything happen". A few cheap queries
     * stand in for one, and this is the store's share of them: skipping a pass
     * that would reconcile nothing is the difference between a sync that costs
     * nothing and one that walks every contact on the device.
     */
    boolean pending(String engineCollection) {
        SQLiteDatabase db = store.getReadableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT EXISTS (SELECT 1 FROM probes"
                                + " WHERE collection = ? AND source = ?)"
                                + " OR EXISTS (SELECT 1 FROM items i"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ?"
                                + " AND (b.conflicted = 1"
                                + " OR ((i.deleted = 1 OR i.retained_at IS NOT NULL)"
                                + " AND b.handle IS NOT NULL)"
                                + " OR (i.deleted = 0 AND i.retained_at IS NULL"
                                + " AND i.object_hash IS NOT NULL"
                                + " AND (b.base_object IS NULL"
                                + " OR b.base_object <> i.object_hash))))",
                        new String[] {collection, source, source, collection})) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    /** The live, hydrated items a source should mirror (quiet-path count). */
    int memberCount(String engineCollection) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM items WHERE collection = ? AND deleted = 0"
                                + " AND retained_at IS NULL AND object_hash IS NOT NULL",
                        new String[] {collectionOf(engineCollection)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * The link id an engine handle names: the source's binding when it has one,
     * the identity the provisional handle spells otherwise.
     *
     * <p>The fallback is what covers a placement this source has never bound,
     * which is the ordinary state of every item on the phone spoke before it has
     * been projected once, and mirrors the load path handing that placement out
     * under its provisional handle in exactly that case.
     */
    private String linkFor(
            SQLiteDatabase db, String collection, String source, String handle) {
        String bound = linkOf(db, collection, source, handle);
        if (bound != null) {
            return bound;
        }
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT link_id FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, nameOf(handle)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    /** An object's body as text, empty when the item carries none. */
    private String bodyOf(String hash) throws JSONException {
        if (hash == null) {
            return "";
        }
        try {
            String body = blobs.getText(hash);
            if (body == null) {
                // NOTE: a referenced blob that is not on disk means the store
                // and the object directory disagree, which a caller cannot fix;
                // an empty body reads as "not hydrated yet", which the next
                // hydrate pass does fix.
                Log.w("pimalaya", "missing blob for " + hash);
                return "";
            }
            return body;
        } catch (IOException error) {
            throw new JSONException("Could not read the body " + hash + ": " + error.getMessage());
        }
    }

    /** The body one source last agreed on, null when it agreed on none. */
    private String baseObjectOf(
            SQLiteDatabase db, String collection, String linkId, String source) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT base_object FROM bindings WHERE collection = ?"
                                + " AND link_id = ? AND source = ?",
                        new String[] {collection, linkId, source})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** The diverging body this source's binding is waiting on, or null. */
    private String conflictObjectOf(
            SQLiteDatabase db, String collection, String linkId, String source) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT conflict_object FROM bindings WHERE collection = ?"
                                + " AND link_id = ? AND source = ?",
                        new String[] {collection, linkId, source})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }


    /**
     * The public id of a link, shared by every placement of it (SPEC.md §9.1),
     * drawn from the store-wide counter the first time one is inserted.
     */
    private long nextSeq(SQLiteDatabase db, String linkId) {
        try (Cursor cursor =
                db.rawQuery("SELECT seq FROM items WHERE link_id = ? LIMIT 1",
                        new String[] {linkId})) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        try (Cursor cursor =
                db.rawQuery("UPDATE store_meta SET next_seq = next_seq + 1 WHERE id = 1"
                        + " RETURNING next_seq - 1", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 1;
        }
    }

    /** The handle this source already binds the item under, or null. */
    private String handleOf(SQLiteDatabase db, String collection, String linkId, String source) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT handle FROM bindings WHERE collection = ? AND link_id = ?"
                                + " AND source = ?",
                        new String[] {collection, linkId, source})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private String linkOf(SQLiteDatabase db, String collection, String source, String handle) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT link_id FROM bindings WHERE collection = ? AND source = ?"
                                + " AND handle = ? LIMIT 1",
                        new String[] {collection, source, handle})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private String checkpointOf(SQLiteDatabase db, String collection, String source) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT checkpoint FROM sources WHERE collection = ? AND source = ?",
                        new String[] {collection, source})) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                byte[] checkpoint = cursor.getBlob(0);
                return checkpoint.length == 0
                        ? null
                        : new String(checkpoint, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Records a handle the source reported and nothing has named yet. */
    private static void upsertProbe(
            SQLiteDatabase db, String collection, String source, String handle, String flags) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("source", source);
        values.put("handle", handle);
        values.put("flags", flags);
        PimdirSql.Bound bound = PimdirSql.bind("UPSERT_PROBE", values);
        db.execSQL(bound.sql, bound.args);
    }

    /** Forgets a probe: the handle is named, dropped or superseded. */
    private static void deleteProbe(
            SQLiteDatabase db, String collection, String source, String handle) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("source", source);
        values.put("handle", handle);
        PimdirSql.Bound bound = PimdirSql.bind("DELETE_PROBE", values);
        db.execSQL(bound.sql, bound.args);
    }

    /** Moves the refcount by the difference this placement made, never globally. */
    private void adjustRefcount(SQLiteDatabase db, String before, String after) {
        if (sameObject(before, after)) {
            return;
        }
        if (before != null) {
            db.execSQL("UPDATE objects SET refcount = refcount - 1 WHERE hash = ?",
                    new Object[] {before});
        }
        if (after != null) {
            db.execSQL("UPDATE objects SET refcount = refcount + 1 WHERE hash = ?",
                    new Object[] {after});
        }
    }

    /** Drops unreferenced objects and unlinks their blobs. */
    private void collectGarbage(SQLiteDatabase db) {
        List<String> orphans = new ArrayList<>();
        try (Cursor cursor =
                db.rawQuery("SELECT hash FROM objects WHERE refcount <= 0", null)) {
            while (cursor.moveToNext()) {
                orphans.add(cursor.getString(0));
            }
        }
        for (String hash : orphans) {
            db.execSQL("DELETE FROM objects WHERE hash = ?", new Object[] {hash});
            // NOTE: the row goes inside the transaction, the file after it, so a
            // crash leaves an orphan blob rather than a row without its body.
            if (!blobs.remove(hash)) {
                Log.w("pimalaya", "could not unlink the orphan blob " + hash);
            }
        }
    }

    private static boolean sameObject(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** A stored JSON array column (markers, handles) as an array, empty when NULL. */
    private static JSONArray arrayOf(String json) throws JSONException {
        return json == null || json.isEmpty() ? new JSONArray() : new JSONArray(json);
    }

    /**
     * Whether the binding row records an agreed base.
     *
     * <p>Either witness: {@code base_present} is the fact, since a base of no
     * revision, no body and markers nobody has read is a real agreement its
     * three value columns cannot express, and reading presence off them alone
     * has such a placement come back as never-agreed so the sync re-derives the
     * same push every run. The value columns stay a witness for a row written
     * before the column existed.
     */
    private static boolean hasBase(Cursor cursor) {
        return (!cursor.isNull(9) && cursor.getInt(9) != 0)
                || !cursor.isNull(6)
                || !cursor.isNull(7)
                || !cursor.isNull(8);
    }

    private static JSONObject effect(String collection, String handle, String kind)
            throws JSONException {
        JSONObject effect = new JSONObject();
        effect.put("collection", collection);
        effect.put("handle", handle);
        effect.put("kind", kind);
        return effect;
    }

    /** The detail ladder as the schema stores it: 0 probed, 1 meta, 2 full. */
    private static int levelValue(String name) {
        switch (name) {
            case "meta":
                return 1;
            case "full":
                return 2;
            default:
                return 0;
        }
    }

    private static String levelName(int value) {
        switch (value) {
            case 1:
                return "meta";
            case 2:
                return "full";
            default:
                return "probed";
        }
    }
}
