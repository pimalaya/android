package org.pimalaya;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
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
 * <p>Writes are one transaction, applied in the batch's order (SYNC §10): a
 * batch naming one handle twice means what its order says, a tombstone then
 * its withdrawal, or a drop then the upsert that restores it.
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
    private final PimdirItems items;

    PimdirStorage(PimdirDb store) {
        this.store = store;
        this.blobs = new PimdirBlobs(store.blobs());
        this.items = new PimdirItems(store);
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
     * A staged removal is not one of those and <strong>is</strong> returned, as
     * the tombstone it is: the merge derives its remove push from the placement,
     * so hiding it would leave the delete local forever and let the next
     * enumerate read the member the server still holds as one to add back.
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

        boolean roundOpen = syncState(db, stored, source, reply);
        String kind = scope == null ? "all" : scope.optString("kind", "all");
        if (roundOpen && "all".equals(kind)) {
            reply.put("unstamped", unstamped(db, stored, source));
        }

        // NOTE: a handle scope naming none asks for the sync state alone,
        // which is what a sync reads before it lists (SYNC §5); answering it
        // with the whole collection would read a 100k-message mailbox to
        // learn its checkpoint.
        JSONArray named = scope == null ? null : scope.optJSONArray("handles");
        if ("handles".equals(kind) && (named == null || named.length() == 0)) {
            return reply;
        }

        List<String> args = new ArrayList<>(List.of(source, stored));
        String narrowed = scopeClause(scope, args);

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.link_id, i.flags, i.object_hash, i.sort_key, i.level,"
                                + " b.handle, b.base_flags, b.base_object, b.base_revision,"
                                + " b.base_present, b.conflicted, b.conflict_revision,"
                                + " b.conflict_object, i.deleted, i.conflicted, c.kind, s.date"
                                + " FROM items i"
                                + " JOIN collections c ON c.id = i.collection"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " LEFT JOIN mail_summary s ON s.collection = i.collection"
                                + " AND s.link_id = i.link_id"
                                + " WHERE i.collection = ?"
                                + " AND i.retained_at IS NULL" + narrowed,
                        args.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                // NOTE: one placement per item the source binds, and one
                // create per item it does not bind but the store holds a body
                // for (SYNC §3). An item that is neither is projected for
                // nobody: handing it over under a provisional handle would
                // have a complete round read it as a member the remote lost
                // and retire it, taking the body a reader had stored.
                if (cursor.isNull(5) && cursor.isNull(2)) {
                    continue;
                }
                // NOTE: nor is a removal this source already carried out,
                // waiting on another source: it is no create here.
                if (cursor.isNull(5) && cursor.getInt(13) == 1) {
                    continue;
                }
                placements.put(placementOf(collection, cursor));
            }
        }
        return reply;
    }

    /**
     * The source's sync state of a collection, written into a load reply:
     * its checkpoint, the coverage the last closed round left (SYNC §5,
     * STORAGE §4.3) and the round under way. Answers whether a round is
     * open.
     *
     * <p>Read through the canonical {@code load_checkpoint} and
     * {@code load_round}, so the columns stay the crate's.
     */
    private static boolean syncState(
            SQLiteDatabase db, String collection, String source, JSONObject reply)
            throws JSONException {
        Map<String, Object> key = new HashMap<>();
        key.put("collection", collection);
        key.put("source", source);

        PimdirSql.Bound checkpoint = PimdirSql.bind("LOAD_CHECKPOINT", key);
        try (Cursor cursor = db.rawQuery(checkpoint.sql, stringsOf(checkpoint.args))) {
            if (cursor.moveToFirst()) {
                String token = textOf(cursor, 0);
                if (token != null) {
                    reply.put("checkpoint", token);
                }
                // NOTE: covered_at NULL is never complete, and then the
                // coverage carries no bound.
                if (!cursor.isNull(3)) {
                    JSONObject coverage = new JSONObject();
                    coverage.putOpt("since", cursor.isNull(1) ? null : cursor.getString(1));
                    coverage.putOpt("until", cursor.isNull(2) ? null : cursor.getString(2));
                    coverage.put("at", cursor.getString(3));
                    reply.put("coverage", coverage);
                }
            }
        }

        PimdirSql.Bound round = PimdirSql.bind("LOAD_ROUND", key);
        try (Cursor cursor = db.rawQuery(round.sql, stringsOf(round.args))) {
            if (!cursor.moveToFirst() || cursor.isNull(1)) {
                return false;
            }
            JSONObject open = new JSONObject();
            open.put("startedAt", cursor.getString(1));
            open.putOpt("since", cursor.isNull(2) ? null : cursor.getString(2));
            open.putOpt("until", cursor.isNull(3) ? null : cursor.getString(3));
            open.putOpt("cursor", textOf(cursor, 4));
            open.putOpt("checkpoint", textOf(cursor, 5));
            // NOTE: the kind the round opened as, so the engine resumes a
            // band round as one and restarts a round of the other kind.
            open.put("band", cursor.getInt(6) != 0);
            reply.put("round", open);
            return true;
        }
    }

    /**
     * The based bindings of the source the open round has not stamped and
     * whose item's date is in its scope, or unknown on a round over its
     * whole scope: what the round's last page finds absent unless it lists
     * them (SYNC §5). A band round leaves an undated member be.
     */
    private static JSONArray unstamped(SQLiteDatabase db, String collection, String source) {
        Map<String, Object> key = new HashMap<>();
        key.put("collection", collection);
        key.put("source", source);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_UNSTAMPED_BINDINGS", key);

        JSONArray handles = new JSONArray();
        try (Cursor cursor = db.rawQuery(bound.sql, stringsOf(bound.args))) {
            while (cursor.moveToNext()) {
                handles.put(cursor.getString(0));
            }
        }
        return handles;
    }

    /**
     * An opaque token column (a checkpoint, a resume cursor) as the text
     * every connector here writes it as, or null when empty or unset.
     *
     * <p>BLOB in the schema, which is what the tables being STRICT makes
     * the store hold, and text on the wire.
     */
    private static String textOf(Cursor cursor, int column) {
        if (cursor.isNull(column)) {
            return null;
        }
        byte[] bytes = cursor.getType(column) == Cursor.FIELD_TYPE_BLOB
                ? cursor.getBlob(column)
                : cursor.getString(column).getBytes(StandardCharsets.UTF_8);
        return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
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
        // NOTE: full only when a body is actually there, whatever the stored
        // level claims (SYNC §3). An item whose body a remote change dropped
        // projects at most meta, which is what has the upgrade refetch it.
        placement.put(
                "level", cursor.isNull(2) ? cappedLevel(cursor.getInt(4)) : levelName(cursor.getInt(4)));
        // NOTE: a NULL column is a set nobody has read, which the wire says by
        // leaving the field out. Sending an empty array instead would push that
        // absence onto whichever side did know the markers.
        if (!cursor.isNull(1)) {
            placement.put("flags", arrayOf(cursor.getString(1)));
        }

        String status = statusOf(cursor);
        placement.put("status", status);
        // NOTE: a message's date and not its summary: what the engine reads
        // to tell a placement in a round's scope from one outside it (SYNC
        // section 5). Without it a round opened and closed by one page would
        // find every message it did not list absent, the ones above a
        // widening's band among them.
        if (cursor.getColumnCount() > 16 && !cursor.isNull(16)) {
            placement.put("date", cursor.getString(16));
        }
        if (!cursor.isNull(11)) {
            placement.put("conflictRevision", cursor.getString(11));
        }
        if (!cursor.isNull(12)) {
            placement.put("conflictObject", cursor.getString(12));
        }

        boolean bound = !cursor.isNull(5);
        if (bound && hasBase(cursor)) {
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
        } else if (!bound || "created".equals(status)) {
            // NOTE: no base means a pending create, unbound or staged under
            // its provisional handle, and a pending create of an item another
            // collection already holds on the same source is a membership
            // addition rather than a second upload. Naming where it comes
            // from is what lets the push adapter tell the two apart.
            JSONObject origin = originOf(collection, linkId);
            if (origin != null) {
                placement.put("origin", origin);
            }
        }
        // NOTE: a staged move is a tombstone beside the pending create it
        // derives its destination from (SYNC §3), so its removal pushes as a
        // relocation. Mail only: no other kind is moved.
        if ("tombstone".equals(status) && PimdirSummary.MAIL.equals(cursor.getString(15))) {
            JSONObject destination = destinationOf(collection, linkId);
            if (destination != null) {
                placement.put("origin", destination);
            }
        }
        return placement;
    }

    /**
     * What a placement owes, derived from the row (SYNC §3), first rule that
     * applies.
     *
     * <p>Derived and never stored, which is the standard's rule and not this
     * app's convenience: a status column would be a second copy of what the
     * bindings already say, and the two would part company the first time a
     * crash landed between them.
     *
     * <p>The two the store used to leave out were the two that carry a push.
     * Without {@code dirty} a staged edit is loaded back as agreed and the
     * merge finds nothing to send; without {@code created} an item no source
     * binds reads as a member the remote no longer has, and a complete round
     * retires it. Both are silent: the write lands, the sync reports success,
     * and the change is gone.
     */
    private static String statusOf(Cursor cursor) {
        // 1. A divergence, per source or across them, and neither is
        // downgraded by anything below.
        boolean conflicted =
                (!cursor.isNull(10) && cursor.getInt(10) == 1)
                        || (!cursor.isNull(14) && cursor.getInt(14) == 1);
        if (conflicted) {
            return "conflict";
        }

        // 2. A staged removal, while the source still binds the item: the
        // content is kept, so an edit after it still beats the delete.
        boolean bound = !cursor.isNull(5);
        if (cursor.getInt(13) == 1 && bound) {
            return "tombstone";
        }

        // 3. A create: nothing has agreed on this item here yet, either
        // because the source binds it with no base or because it does not
        // bind it at all, the load projecting one only where the store holds
        // a body to offer.
        if (!bound || !hasBase(cursor)) {
            return "created";
        }

        // 4. A pending push: markers both sides know and disagree on, or a
        // body that has moved past the one the base holds. A placement
        // holding no body owes no body, whatever its base names.
        boolean flagsMoved =
                !cursor.isNull(1)
                        && !cursor.isNull(6)
                        && !sameFlags(cursor.getString(1), cursor.getString(6));
        // NOTE: the body axis only where a kind has one to push. A message is
        // immutable, so the bytes a reader stored by opening it are not an
        // edit owing an upload; reading them as one would derive an update
        // per opened message, once a sync, that no mail backend would take.
        boolean bodyMoved =
                mutable(cursor.getString(15))
                        && !cursor.isNull(2)
                        && (cursor.isNull(7) || !cursor.getString(2).equals(cursor.getString(7)));
        return flagsMoved || bodyMoved ? "dirty" : "clean";
    }

    /** Whether a kind's items can be edited in place, so a body owes a push. */
    private static boolean mutable(String kind) {
        return !PimdirSummary.MAIL.equals(kind);
    }

    /** Whether two stored marker sets name the same markers, order aside. */
    private static boolean sameFlags(String one, String other) {
        try {
            Set<String> left = new HashSet<>(stringsOf(arrayOf(one)));
            Set<String> right = new HashSet<>(stringsOf(arrayOf(other)));
            return left.equals(right);
        } catch (JSONException error) {
            // An unreadable set says nothing, so it cannot disagree with
            // anything: the alternative is a push nobody asked for.
            return true;
        }
    }

    /** The strings of a JSON array, in order. */
    private static List<String> stringsOf(JSONArray values) throws JSONException {
        List<String> strings = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            strings.add(values.getString(index));
        }
        return strings;
    }

    /** The level a bodiless row projects at: never full (SYNC §3). */
    private static String cappedLevel(int stored) {
        return levelName(Math.min(stored, 1));
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
        compiled = new HashMap<>();
        try {
            Map<String, byte[]> bodies = new HashMap<>();
            List<JSONObject> stamps = new ArrayList<>();
            Set<String> links = new HashSet<>();
            Set<String> fresh = new HashSet<>();
            Set<String> staged = new HashSet<>();
            Map<String, String> released = new HashMap<>();
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
                        String kind =
                                applyUpsert(db, placement, superseded, links, released, fresh);
                        if (kind != null) {
                            effects.put(effect(placement.getString("collection"),
                                    placement.getString("handle"), kind));
                        }
                        break;
                    }
                    case "drop":
                        applyBatchDrop(db, op, staged, released, effects);
                        break;
                    case "setCheckpoint":
                        setCheckpoint(db, op);
                        break;
                    case "openRound":
                        ensureCollection(db, collectionOf(op.getString("collection")));
                        roundOp(db, "OPEN_ROUND", op, op.optJSONObject("scope"));
                        break;
                    case "stamp":
                        stamps.add(op);
                        break;
                    case "setRoundCursor":
                        roundOp(db, "SET_ROUND_CURSOR", op, null);
                        break;
                    case "closeRound":
                        roundOp(db, "CLOSE_ROUND", op, op.optJSONObject("coverage"));
                        break;
                    case "setCoverage":
                        roundOp(db, "SET_COVERAGE", op, op.optJSONObject("scope"));
                        break;
                    default:
                        throw new JSONException("Unknown write op " + op.getString("op"));
                }
            }

            // NOTE: an item's fate is the batch's outcome, as io-pimdir
            // settles it from the hub the whole batch folded into: a staged
            // mail create no upsert carried on goes with its provisional
            // handle, and an item the batch created and left unbound was
            // never stored.
            for (String key : staged) {
                if (!links.contains(key)) {
                    String[] parts = key.split("\n", 2);
                    items.remove(db, parts[0], parts[1]);
                }
            }
            for (String key : fresh) {
                String[] parts = key.split("\n", 2);
                if (!isBound(db, parts[0], parts[1])) {
                    items.remove(db, parts[0], parts[1]);
                }
            }

            // NOTE: after the upserts, so a binding the batch named is stamped
            // too (SYNC §5): the round's last page drops what no page stamped.
            for (JSONObject stamp : stamps) {
                stamp(db, stamp);
            }

            collectGarbage(db);
            db.setTransactionSuccessful();
            return effects;
        } finally {
            db.endTransaction();
            for (SQLiteStatement statement : compiled.values()) {
                statement.close();
            }
            compiled = null;
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

    /**
     * Applies one drop of a batch where it stands, recording what the rest of
     * the batch reads of it: the link id its handle was bound to, which an
     * unnamed upsert of the same handle later in the batch continues (as
     * io-pimdir resolves it against the store the batch started from), and
     * the staged mail create it superseded.
     */
    private void applyBatchDrop(SQLiteDatabase db, JSONObject drop, Set<String> staged,
            Map<String, String> released, JSONArray effects) throws JSONException {
        String collection = drop.getString("collection");
        String handle = drop.getString("handle");
        // NOTE: only a delete retires the item. A superseded drop is a
        // provisional handle an accepted add replaced and a rekeyed one a
        // handle a rebuild renumbered, so the row goes and the item stays;
        // reading either as a removal would retain what the same batch
        // renames, and propagate a delete nobody asked for.
        boolean deleted = "deleted".equals(drop.optString("reason", "deleted"));
        String stored = collectionOf(collection);
        String source = sourceOf(collection);
        String bound = linkOf(db, stored, source, handle);
        if (bound != null) {
            released.putIfAbsent(stored + "\n" + source + "\n" + handle, bound);
        }
        // NOTE: a message is filed under the handle its listing names
        // (applyUpsert), so a mail create whose provisional handle an accepted
        // push or a landing supersedes is no item of its own: the arrival is,
        // under its handle, whether this batch files it or the next listing
        // brings it. Settled at the batch's end, the upsert landing it reading
        // the summary it carries on.
        if (bound != null && !deleted && handle.startsWith(PROVISIONAL) && isMail(db, stored)) {
            staged.add(stored + "\n" + bound);
        }
        applyDrop(db, collection, handle, deleted);
        if (deleted) {
            effects.put(effect(collection, handle, "removed"));
        }
    }

    /** Whether any source binds the item. */
    private static boolean isBound(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM bindings WHERE collection = ? AND link_id = ? LIMIT 1",
                        new String[] {collection, linkId})) {
            return cursor.moveToFirst();
        }
    }

    /** Files a body under the hash the engine computed and indexes it. */
    private void storeObject(SQLiteDatabase db, JSONObject op, Map<String, byte[]> bodies)
            throws JSONException {
        String hash = op.getString("hash");
        // NOTE: a body that is not UTF-8 (a message's 8-bit parts) comes
        // as base64, so it is stored as the bytes it was fetched as.
        byte[] body =
                op.has("bodyBase64")
                        ? java.util.Base64.getDecoder().decode(op.getString("bodyBase64"))
                        : op.optString("body", "").getBytes(StandardCharsets.UTF_8);
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
     * bookkeeping-only upsert (flags, bases). {@code released} is what the
     * batch's earlier drops unbound, by handle, and {@code fresh} collects the
     * items this upsert creates.
     */
    private String applyUpsert(SQLiteDatabase db, JSONObject placement, Set<String> superseded,
            Set<String> links, Map<String, String> released, Set<String> fresh)
            throws JSONException {
        String engineCollection = placement.getString("collection");
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        String handle = placement.getString("handle");
        // The identity the placement names, else the one its handle is already
        // bound to (STORAGE §10), or was when the batch began. Never the handle
        // itself: an item is keyed by its link id, so filing an unnamed handle
        // as one mints an item the next fetch has to un-mint, leaving two
        // bindings on one handle.
        String bound = linkOf(db, collection, source, handle);
        String known =
                bound != null ? bound : released.get(collection + "\n" + source + "\n" + handle);
        String linkId = placement.isNull("linkId") ? known : placement.optString("linkId", known);
        // NOTE: a message is filed under its handle, the identity its
        // listing names it by, so a create landed onto the handle its
        // arrival is listed at takes that handle as its identity too; the
        // item it was staged as goes with its provisional handle.
        boolean mail = isMail(db, collection);
        String staged = null;
        if (mail && !handle.startsWith(PROVISIONAL) && linkId != null && !linkId.equals(handle)) {
            staged = linkId;
            linkId = handle;
        }
        String object = placement.isNull("object") ? null : placement.optString("object", null);
        JSONObject summary = placement.optJSONObject("summary");
        String sortKey = placement.optString("sortKey", "");
        int level = levelValue(placement.optString("level", "meta"));
        // NOTE: absent means nobody has read the markers, which the column says
        // as NULL. Storing "[]" for it would turn a never-read set into an
        // authoritative "carries none" and clear whatever the other side knew.
        String flags = placement.optJSONArray("flags") == null
                ? null
                : placement.getJSONArray("flags").toString();
        // A staged removal, which is a write like any other until a push has
        // carried it: the row stays, marked, so the merge keeps deriving the
        // remove and an edit after it still beats the delete.
        boolean tombstone = "tombstone".equals(placement.optString("status", "clean"));

        // NOTE: nothing reaches the store unnamed (SYNC §10): a listing names
        // every member it carries, so an upsert naming no identity on a
        // handle no binding holds is a driver bug, refused rather than
        // filed under a key the next listing would have to un-mint.
        if (linkId == null) {
            throw new PimalayaException(
                    "Unnamed placement " + collection + "/" + handle + " on " + source);
        }

        // The handle named another identity until now: a resource replaced in
        // place, or a spine whose fetch resolved what its enumeration could
        // not. Its binding is retired first, as a delete of that handle would
        // (STORAGE §10), since a handle names one item per source.
        if (bound != null && !bound.equals(linkId)) {
            applyDrop(db, engineCollection, handle, true);
        }

        // NOTE: an empty hash for a row holding none, so a missing row and a
        // bodiless one read apart.
        String held =
                one(
                        db,
                        "SELECT ifnull(object_hash, '') FROM items WHERE collection = ?"
                                + " AND link_id = ?",
                        collection, linkId);
        boolean exists = held != null;
        String previousObject = held == null || held.isEmpty() ? null : held;

        if (!exists) {
            db.execSQL(
                    "INSERT INTO items(collection, link_id, seq, flags, object_hash,"
                            + " sort_key, level, deleted) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {
                        collection,
                        linkId,
                        nextSeq(db, linkId),
                        flags,
                        object,
                        sortKey,
                        level,
                        tombstone ? 1 : 0
                    });
        } else {
            // A write that does not restate the key must preserve it (SPEC.md
            // §9.3): the reference write is a replace-all, so blanking it here
            // would reset the ordering of every item a sync touched.
            db.execSQL(
                    "UPDATE items SET flags = ?, object_hash = ?, level = ?,"
                            + " sort_key = CASE WHEN ? = '' THEN sort_key ELSE ? END,"
                            + " deleted = ?, retained_at = NULL, retained_by = NULL"
                            + " WHERE collection = ? AND link_id = ?",
                    new Object[] {
                        flags,
                        object,
                        level,
                        sortKey,
                        sortKey,
                        tombstone ? 1 : 0,
                        collection,
                        linkId
                    });
        }

        // On the same terms as the sort key: a write carrying no summary keeps
        // the stored row, which is what a flag push and a pulled deletion do.
        // NOTE: a message created from another one (a landed create, a
        // copy, a move's target) is that message: it takes the summary the
        // store holds of it, the engine having carried none.
        boolean carried =
                mail
                        && !exists
                        && summary == null
                        && carrySummary(
                                db, collection, source, staged, placement.optJSONObject("origin"),
                                linkId);
        if (!carried) {
            PimdirSummary.write(db, collection, linkId, summary, !exists);
        }
        links.add(collection + "\n" + linkId);
        writeBinding(db, collection, source, linkId, handle, placement, superseded, !exists);
        adjustRefcount(db, previousObject, object);

        if (!exists) {
            fresh.add(collection + "\n" + linkId);
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
            String handle, JSONObject placement, Set<String> superseded, boolean fresh)
            throws JSONException {
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

        // NOTE: an item this write created has no binding yet, a binding
        // cascading with its item, so there is nothing to read back.
        String bound = fresh ? null : handleOf(db, collection, linkId, source);
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
        String previousBase = fresh ? null : baseObjectOf(db, collection, linkId, source);
        String previousConflict =
                fresh ? null : conflictObjectOf(db, collection, linkId, source);

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

        if (!isBound(db, collection, linkId)) {
            db.execSQL(
                    "UPDATE items SET deleted = 1,"
                            + " retained_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'),"
                            + " retained_by = ? WHERE collection = ? AND link_id = ?",
                    new Object[] {source, collection, linkId});
            return;
        }

        // NOTE: another source still holds it, the server of a book or a
        // calendar the phone deleted from, or the phone of one the server
        // did: a tombstone there, which its next pass pushes as a removal,
        // the last of them retiring the item. Left live, the other source
        // would keep it and hand it back as a create.
        db.execSQL(
                "UPDATE items SET deleted = 1 WHERE collection = ? AND link_id = ?",
                new Object[] {collection, linkId});
    }

    /**
     * One round op of SYNC §5 through its canonical statement: the source's
     * round opened, its cursor landed, closed with its coverage, or the
     * coverage restated. {@code scope} is the op's scope or coverage, its
     * absent bounds open; the cursor and the checkpoint are bound as the
     * BLOBs the schema keeps them as.
     */
    private static void roundOp(SQLiteDatabase db, String statement, JSONObject op,
            JSONObject scope) throws JSONException {
        String engine = op.getString("collection");
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collectionOf(engine));
        values.put("source", sourceOf(engine));
        if (scope != null) {
            values.put("since", scope.isNull("since") ? null : scope.optString("since", null));
            values.put("until", scope.isNull("until") ? null : scope.optString("until", null));
        }
        values.put("cursor", blobOf(op, "cursor"));
        values.put("checkpoint", blobOf(op, "checkpoint"));
        // NOTE: bound as the INTEGER the column is; a band round's absence
        // infers no delete of an undated member (SYNC §5).
        values.put("band", op.optBoolean("band", false) ? 1L : 0L);

        PimdirSql.Bound bound = PimdirSql.bind(statement, values);
        db.execSQL(bound.sql, bound.args);
    }

    /** Stamps the bindings a page listed with the open round's id. */
    private static void stamp(SQLiteDatabase db, JSONObject op) throws JSONException {
        String engine = op.getString("collection");
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collectionOf(engine));
        values.put("source", sourceOf(engine));
        values.put("handles", op.getJSONArray("handles").toString());

        PimdirSql.Bound bound = PimdirSql.bind("STAMP_BINDINGS", values);
        db.execSQL(bound.sql, bound.args);
    }

    /** An opaque token field of a write op as the BLOB it is stored as. */
    private static byte[] blobOf(JSONObject op, String field) {
        if (op.isNull(field) || !op.has(field)) {
            return null;
        }
        return op.optString(field, "").getBytes(StandardCharsets.UTF_8);
    }

    /** The collection row a source's sync state hangs off, created when missing. */
    private static void ensureCollection(SQLiteDatabase db, String collection) {
        db.execSQL(PimdirSql.of("ENSURE_COLLECTION").replace(":collection", "?")
                        .replace(":account", "NULL"),
                new Object[] {collection, collection});
    }

    private void setCheckpoint(SQLiteDatabase db, JSONObject op) throws JSONException {
        String collection = collectionOf(op.getString("collection"));
        String source = sourceOf(op.getString("collection"));
        ensureCollection(db, collection);
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
     * The flag set this source last agreed on, or null when it agreed on
     * none (a placement it has never reconciled, or a backend that reports
     * no flags at all).
     *
     * <p>What a marker push diffs against. A backend whose write verb adds
     * and removes one marker at a time must never be handed a whole set:
     * replacing it would strip every keyword this app does not model, and
     * the base is what says which of them the user actually moved.
     */
    JSONArray baseFlags(String engineCollection, String handle) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        String linkId = linkFor(db, collection, source, handle);
        if (linkId == null) {
            return null;
        }

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT base_flags FROM bindings WHERE collection = ?"
                                + " AND link_id = ? AND source = ?",
                        new String[] {collection, linkId, source})) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return null;
            }
            return arrayOf(cursor.getString(0));
        }
    }

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
            return handles;
        }
    }

    /**
     * What this source holds of some handles: for each one it binds, the
     * link id it is filed under and the revision last agreed on (null for
     * an immutable member). A handle it binds nothing under is absent.
     *
     * <p>What naming a listing takes (SYNC §4): a bound member whose
     * revision has not moved is named by what the store already holds, and
     * only a new or changed one needs its body read to be named.
     */
    Map<String, String[]> bound(String engineCollection, List<String> handles) {
        SQLiteDatabase db = store.getReadableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);

        Map<String, String[]> bound = new HashMap<>();
        for (int from = 0; from < handles.size(); from += SCOPE_MAX) {
            List<String> chunk = handles.subList(from, Math.min(handles.size(), from + SCOPE_MAX));
            StringBuilder marks = new StringBuilder();
            List<String> args = new ArrayList<>(List.of(collection, source));
            for (String handle : chunk) {
                marks.append(marks.length() == 0 ? "?" : ",?");
                args.add(handle);
            }
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT handle, link_id, base_revision FROM bindings"
                                    + " WHERE collection = ? AND source = ? AND handle IN ("
                                    + marks + ")",
                            args.toArray(new String[0]))) {
                while (cursor.moveToNext()) {
                    bound.put(
                            cursor.getString(0),
                            new String[] {
                                cursor.getString(1), cursor.isNull(2) ? null : cursor.getString(2)
                            });
                }
            }
        }
        return bound;
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
     * Whether the source has anything to reconcile: an unresolved conflict,
     * an item it still holds that
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
                        "SELECT EXISTS (SELECT 1 FROM items i"
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
                        new String[] {source, collection})) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    /**
     * Forgets what one source holds of a collection: every binding it has
     * dropped as superseded, never as a removal, so the items stay and the
     * next pass of that source hands each one over again as a create.
     *
     * <p>For a source whose members went with something other than a sync:
     * the phone's calendar provider deletes a calendar's events with its row,
     * and a binding left behind would read them as still there.
     */
    void forget(String engineCollection) throws JSONException {
        JSONArray placements = loadCollection(engineCollection, null).optJSONArray("placements");
        JSONArray drops = new JSONArray();
        for (int index = 0; placements != null && index < placements.length(); index++) {
            String handle = placements.getJSONObject(index).getString("handle");
            if (!handle.startsWith(PROVISIONAL)) {
                drops.put(
                        new JSONObject()
                                .put("op", "drop")
                                .put("collection", engineCollection)
                                .put("handle", handle)
                                .put("reason", "superseded"));
            }
        }
        if (drops.length() > 0) {
            applyWrites(drops);
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

    // ---- staged moves and creates -----------------------------------------

    /** The link id an engine handle names on this source, or null. */
    String linkOfHandle(String engineCollection, String handle) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT link_id FROM bindings WHERE collection = ? AND source = ?"
                                + " AND handle = ?",
                        new String[] {
                            collectionOf(engineCollection), sourceOf(engineCollection), handle
                        })) {
            return cursor.moveToFirst() ? cursor.getString(0) : nameOf(handle);
        }
    }

    /**
     * The handle the engine addresses an item by on this source: the one it
     * binds, else the provisional handle its create waits under (SYNC §2).
     */
    String handleFor(String engineCollection, String linkId) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT handle FROM bindings WHERE collection = ? AND link_id = ?"
                                + " AND source = ?",
                        new String[] {
                            collectionOf(engineCollection), linkId, sourceOf(engineCollection)
                        })) {
            return cursor.moveToFirst() ? cursor.getString(0) : provisionalOf(linkId);
        }
    }

    /**
     * The key a staged copy or move of {@code linkId} takes in a collection
     * already holding that identity: {@code dup:}, the key, {@code #}, the
     * provisional handle it would have taken (STORAGE §9).
     */
    static String mintedOf(String linkId) {
        return "dup:" + linkId + "#" + provisionalOf(linkId);
    }

    /**
     * The identities a pending create keyed {@code linkId} may be the copy
     * of: its own key, and the key it was minted from when it is one.
     */
    private static List<String> identitiesOf(String linkId) {
        List<String> identities = new ArrayList<>(List.of(linkId));
        int length = linkId.length() - "dup:#".length() - PROVISIONAL.length();
        if (linkId.startsWith("dup:") && length > 0 && length % 2 == 0) {
            String key = linkId.substring(4, 4 + length / 2);
            if (mintedOf(key).equals(linkId)) {
                identities.add(key);
            }
        }
        return identities;
    }

    /**
     * Where a tombstone of {@code linkId} moves to, as the tombstone's origin
     * (SYNC §3): another collection this source holds a pending create of
     * that identity in, under its own key or the one minted from it, with
     * that create's handle, or null.
     */
    private JSONObject destinationOf(String engineCollection, String linkId)
            throws JSONException {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collectionOf(engineCollection));
        values.put("source", sourceOf(engineCollection));
        values.put("link_id", linkId);
        PimdirSql.Bound bound = PimdirSql.bind("DESTINATION_FOR_LINK", values);

        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor = db.rawQuery(bound.sql, stringsOf(bound.args))) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            JSONObject origin = new JSONObject();
            origin.put("collection", cursor.getString(0));
            origin.put("handle", cursor.getString(1));
            return origin;
        }
    }

    /**
     * The key of the pending create a move of {@code linkId} staged in
     * {@code engineCollection}, its own or a minted one, or null.
     */
    String pendingCreateOf(String engineCollection, String linkId) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.link_id FROM items i"
                                + " JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.link_id IN (?, ?) AND i.collection = ?"
                                + " AND i.deleted = 0 AND b.base_present = 0 LIMIT 1",
                        new String[] {
                            sourceOf(engineCollection),
                            linkId,
                            mintedOf(linkId),
                            collectionOf(engineCollection)
                        })) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    /**
     * The staged move a pending create keyed {@code linkId} is the target
     * of, as the source's {@code [collection, handle]}: a tombstone of the
     * same identity this source still binds in another collection. Null
     * when the create is no move's.
     */
    String[] moveSourceOf(String engineCollection, String linkId) {
        SQLiteDatabase db = store.getReadableDatabase();
        List<String> identities = identitiesOf(linkId);
        StringBuilder marks = new StringBuilder();
        List<String> args = new ArrayList<>(List.of(sourceOf(engineCollection)));
        for (String identity : identities) {
            marks.append(marks.length() == 0 ? "?" : ",?");
            args.add(identity);
        }
        args.add(collectionOf(engineCollection));
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.collection, b.handle FROM items i"
                                + " JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.link_id IN (" + marks + ") AND i.collection <> ?"
                                + " AND i.deleted = 1 AND i.retained_at IS NULL"
                                + " AND b.base_present = 1 LIMIT 1",
                        args.toArray(new String[0]))) {
            return cursor.moveToFirst()
                    ? new String[] {cursor.getString(0), cursor.getString(1)}
                    : null;
        }
    }

    /**
     * Whether {@code linkId} is still a pending create in the collection: a
     * live item no base of this source has agreed on.
     */
    boolean isPendingCreate(String engineCollection, String linkId) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM items i"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ? AND i.link_id = ? AND i.deleted = 0"
                                + " AND i.retained_at IS NULL"
                                + " AND (b.link_id IS NULL OR b.base_present = 0)",
                        new String[] {
                            sourceOf(engineCollection), collectionOf(engineCollection), linkId
                        })) {
            return cursor.moveToFirst();
        }
    }

    /**
     * Withdraws a pending create outright, its row and body reference
     * gone, as if it had never been staged: what a move's target holds once
     * the source's relocation delivered it, the arrival coming under its
     * own handle with the target's next listing. A row that is no pending
     * create any more is left alone.
     */
    void withdrawCreate(String engineCollection, String linkId) {
        if (!isPendingCreate(engineCollection, linkId)) {
            return;
        }
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            items.remove(db, collectionOf(engineCollection), linkId);
            collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * The pending mail creates of a collection by the bare {@code Message-ID}
     * they carry, the ones carrying none left out: what lands a staged copy
     * on the arrival the provider filed itself (a sent message).
     *
     * <p>Read off the provisional handles alone, a range of the handle
     * index, so a page of a large mailbox pays for its few creates rather
     * than for the mailbox.
     */
    Map<String, String> pendingCreatesByMessageId(String engineCollection) {
        SQLiteDatabase db = store.getReadableDatabase();
        Map<String, String> creates = new HashMap<>();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT b.link_id, s.message_id FROM bindings b"
                                + " JOIN items i ON i.collection = b.collection"
                                + " AND i.link_id = b.link_id"
                                + " JOIN mail_summary s ON s.collection = b.collection"
                                + " AND s.link_id = b.link_id"
                                + " WHERE b.collection = ? AND b.source = ?"
                                + " AND b.handle >= ? AND b.handle < ?"
                                + " AND b.base_present = 0 AND i.deleted = 0"
                                + " AND s.message_id IS NOT NULL",
                        new String[] {
                            collectionOf(engineCollection),
                            sourceOf(engineCollection),
                            PROVISIONAL,
                            String.valueOf((char) (PROVISIONAL.charAt(0) + 1))
                        })) {
            while (cursor.moveToNext()) {
                creates.put(cursor.getString(1), cursor.getString(0));
            }
        }
        return creates;
    }

    /**
     * Whether an item owes the source a push: no base agreed on it yet (a
     * create, a move's target), markers moved past the ones agreed, or a
     * body past the agreed one where the kind is edited in place. False for
     * an item the source does not know at all being no item of a synced
     * collection.
     */
    boolean unsynced(String engineCollection, String linkId) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.flags, i.object_hash, b.link_id, b.base_present,"
                                + " b.base_flags, b.base_object, c.kind"
                                + " FROM items i"
                                + " JOIN collections c ON c.id = i.collection"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ? AND i.link_id = ?",
                        new String[] {
                            sourceOf(engineCollection), collectionOf(engineCollection), linkId
                        })) {
            if (!cursor.moveToFirst()) {
                return false;
            }
            if (cursor.isNull(2) || cursor.getInt(3) == 0) {
                return true;
            }
            boolean flagsMoved =
                    !cursor.isNull(0)
                            && !cursor.isNull(4)
                            && !sameFlags(cursor.getString(0), cursor.getString(4));
            boolean bodyMoved =
                    mutable(cursor.getString(6))
                            && !cursor.isNull(1)
                            && (cursor.isNull(5)
                                    || !cursor.getString(1).equals(cursor.getString(5)));
            return flagsMoved || bodyMoved;
        }
    }

    /** Whether a stored collection holds mail, read within a write batch. */
    private boolean isMail(SQLiteDatabase db, String collection) {
        return PimdirSummary.MAIL.equals(
                one(db, "SELECT kind FROM collections WHERE id = ?", collection));
    }

    /**
     * Gives a fresh message the summary the store holds of the one it was
     * created from: the item its create was staged as ({@code staged}),
     * else its origin's. Answers whether one was there to take.
     */
    private boolean carrySummary(
            SQLiteDatabase db,
            String collection,
            String source,
            String staged,
            JSONObject origin,
            String linkId)
            throws JSONException {
        String fromCollection = collection;
        String fromLink = staged;
        if (fromLink == null && origin != null) {
            fromCollection = collectionOf(origin.getString("collection"));
            String handle = origin.getString("handle");
            fromLink = linkOf(db, fromCollection, source, handle);
            if (fromLink == null) {
                fromLink = nameOf(handle);
            }
        }
        if (fromLink == null) {
            return false;
        }
        // NOTE: the tables Annex A.1 files a message's summary in, copied
        // row for row whatever their columns.
        boolean copied = copyRows(db, "mail_summary", fromCollection, fromLink, collection, linkId);
        copyRows(db, "item_address", fromCollection, fromLink, collection, linkId);
        return copied;
    }

    /** Copies one item's rows of a summary table onto another item. */
    private static boolean copyRows(
            SQLiteDatabase db,
            String table,
            String fromCollection,
            String fromLink,
            String toCollection,
            String toLink) {
        List<ContentValues> rows = new ArrayList<>();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT * FROM " + table + " WHERE collection = ? AND link_id = ?",
                        new String[] {fromCollection, fromLink})) {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                for (int column = 0; column < cursor.getColumnCount(); column++) {
                    String name = cursor.getColumnName(column);
                    switch (cursor.getType(column)) {
                        case Cursor.FIELD_TYPE_NULL:
                            values.putNull(name);
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            values.put(name, cursor.getLong(column));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            values.put(name, cursor.getDouble(column));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            values.put(name, cursor.getBlob(column));
                            break;
                        default:
                            values.put(name, cursor.getString(column));
                    }
                }
                values.put("collection", toCollection);
                values.put("link_id", toLink);
                rows.add(values);
            }
        }
        for (ContentValues values : rows) {
            db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
        return !rows.isEmpty();
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
        return one(
                db,
                "SELECT base_object FROM bindings WHERE collection = ? AND link_id = ?"
                        + " AND source = ?",
                collection, linkId, source);
    }

    /** The diverging body this source's binding is waiting on, or null. */
    private String conflictObjectOf(
            SQLiteDatabase db, String collection, String linkId, String source) {
        return one(
                db,
                "SELECT conflict_object FROM bindings WHERE collection = ? AND link_id = ?"
                        + " AND source = ?",
                collection, linkId, source);
    }


    /**
     * The public id of a link, shared by every placement of it (SPEC.md §9.1),
     * drawn from the store-wide counter the first time one is inserted.
     */
    private long nextSeq(SQLiteDatabase db, String linkId) {
        String held = one(db, "SELECT seq FROM items WHERE link_id = ? LIMIT 1", linkId);
        if (held != null) {
            return Long.parseLong(held);
        }
        String drawn =
                one(db, "UPDATE store_meta SET next_seq = next_seq + 1 WHERE id = 1"
                        + " RETURNING next_seq - 1");
        return drawn == null ? 1 : Long.parseLong(drawn);
    }

    /** The handle this source already binds the item under, or null. */
    private String handleOf(SQLiteDatabase db, String collection, String linkId, String source) {
        return one(
                db,
                "SELECT handle FROM bindings WHERE collection = ? AND link_id = ? AND source = ?",
                collection, linkId, source);
    }

    private String linkOf(SQLiteDatabase db, String collection, String source, String handle) {
        return one(
                db,
                "SELECT link_id FROM bindings WHERE collection = ? AND source = ?"
                        + " AND handle = ? LIMIT 1",
                collection, source, handle);
    }

    /**
     * The compiled single-value reads of the write batch under way, by
     * statement; null outside one.
     */
    private Map<String, SQLiteStatement> compiled;

    /**
     * The first column of the first row a read answers, null for no row or a
     * NULL value.
     *
     * <p>A compiled statement rather than a cursor: a page of mail runs half a
     * dozen of these per message, and a cursor fills a window for each one.
     * Within a write batch each statement is compiled once and rebound.
     */
    private String one(SQLiteDatabase db, String sql, String... args) {
        SQLiteStatement statement = compiled == null ? null : compiled.get(sql);
        boolean kept = statement != null;
        if (statement == null) {
            statement = db.compileStatement(sql);
            if (compiled != null) {
                compiled.put(sql, statement);
                kept = true;
            }
        }
        try {
            statement.clearBindings();
            for (int index = 0; index < args.length; index++) {
                if (args[index] == null) {
                    statement.bindNull(index + 1);
                } else {
                    statement.bindString(index + 1, args[index]);
                }
            }
            return statement.simpleQueryForString();
        } catch (SQLiteDoneException none) {
            return null;
        } finally {
            if (!kept) {
                statement.close();
            }
        }
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

    /**
     * The detail ladder as the schema stores it: 1 meta, 2 full. 0 is what an
     * earlier draft wrote for a probed row; nothing writes it now and it reads
     * as meta (STORAGE §13).
     */
    private static int levelValue(String name) {
        return "full".equals(name) ? 2 : 1;
    }

    private static String levelName(int value) {
        return value == 2 ? "full" : "meta";
    }
}
