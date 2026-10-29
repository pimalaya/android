package org.pimalaya.pimdir;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDoneException;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Flags;
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
 * <p>Yields arrive as JSON, served on SQLite: a placement is an {@code items}
 * row plus a {@code bindings} row per source (handle, merge base). Writes are
 * one transaction in batch order (SYNC §10).
 */
public final class PimdirStorage {
    /** The source name this app syncs a server under. */
    public static final String SERVER = "server";

    /** The source name the phone's own contacts are reconciled under. */
    static final String PHONE = "phone";

    /** Marks an engine collection id as an addressbook's phone spoke. */
    private static final String PHONE_PREFIX = "phone:";

    /**
     * The prefix of a staged create's handle until a push assigns one.
     *
     * <p>{@code U+0001} then the link id (SYNC.md §2): no protocol hands it
     * out, and it is the engine's spelling, stripped wherever a handle resolves
     * or a resource is named.
     */
    private static final String PROVISIONAL = String.valueOf((char) 0x01);

    private final PimdirDb store;
    private final PimdirBlobs blobs;
    private final PimdirItems items;

    /** The seam over {@code store}. */
    public PimdirStorage(PimdirDb store) {
        this.store = store;
        this.blobs = new PimdirBlobs(store.blobs());
        this.items = new PimdirItems(store);
    }

    /** The phone collection id of an addressbook (the second engine spoke). */
    public static String phoneCollection(String collection) {
        return PHONE_PREFIX + collection;
    }

    /** True when the engine collection id addresses the phone spoke. */
    public static boolean isPhoneCollection(String collection) {
        return collection.startsWith(PHONE_PREFIX);
    }

    /**
     * The stored collection behind an engine collection id.
     *
     * <p>The engine names the server and phone spokes as two collections; the
     * store holds one, with a binding per source, so a phone-won write diverges
     * from the server's base by construction.
     */
    public static String collectionOf(String collection) {
        return isPhoneCollection(collection)
                ? collection.substring(PHONE_PREFIX.length())
                : collection;
    }

    /** Which of the two sources an engine collection id speaks for. */
    public static String sourceOf(String collection) {
        return isPhoneCollection(collection) ? PHONE : SERVER;
    }

    /** The handle a create of this identity is staged under (SYNC.md §2). */
    public static String provisionalOf(String linkId) {
        return PROVISIONAL + linkId;
    }

    /**
     * A row's engine handle: its source's resource name, else provisional.
     *
     * <p>A staged create waits under its provisional handle (SYNC §2), never a
     * guess at the server's name: it is pushed as {@code
     * CardStore.resourceName}, and the binding takes what the push assigns.
     */
    public static String rowHandle(String uri, String id) {
        return uri != null && !uri.isEmpty() ? uri : PimdirStorage.provisionalOf(id);
    }

    /**
     * The name behind a handle: a staged create's identity, else the handle.
     *
     * <p>What a create is pushed as: a provisional name would land as a
     * resource no reader could open.
     */
    public static String nameOf(String handle) {
        return handle.startsWith(PROVISIONAL) ? handle.substring(PROVISIONAL.length()) : handle;
    }

    /**
     * The collection's placements and sync cursor, replying to a load yield.
     *
     * <p>Retained rows are left out (SPEC.md §11), staged tombstones kept or
     * the remove never pushes. {@code scope} narrows to the rows touched
     * ({@link #scopeClause}): a floor, more stays correct.
     */
    public JSONObject loadCollection(String collection, JSONObject scope) throws JSONException {
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
        String narrowed = scopeClause(scope, stored, source, args);

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
        held(db, stored, placements, reply);
        return reply;
    }

    /**
     * Writes the loaded tombstones whose remove is held into a load reply
     * (STORAGE §15.3, SYNC §5): an add superseded them and the item it
     * created is still a pending create, so the engine pushes no delete of
     * them yet. Read by the canonical {@code list_superseded_pending}, by
     * link id, answered as the handles the placements carry.
     */
    private static void held(
            SQLiteDatabase db, String collection, JSONArray placements, JSONObject reply)
            throws JSONException {
        Set<String> superseded = new HashSet<>();
        PimdirSql.Bound bound =
                PimdirSql.bind("LIST_SUPERSEDED_PENDING", Map.of("collection", collection));
        try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                superseded.add(cursor.getString(0));
            }
        }
        if (superseded.isEmpty()) {
            return;
        }
        JSONArray held = new JSONArray();
        for (int index = 0; index < placements.length(); index++) {
            JSONObject placement = placements.getJSONObject(index);
            if (superseded.contains(placement.optString("linkId"))) {
                held.put(placement.getString("handle"));
            }
        }
        reply.put("held", held);
    }

    /**
     * Records that an add superseded an item (STORAGE §15.3): the item at
     * {@code collection} and {@code linkId}, the add's at {@code
     * byCollection} and {@code byLinkId}, through the canonical {@code
     * supersede_item}.
     *
     * <p>In the transaction staging the add and the item's tombstone
     * ({@link #atomically}): a load then holds the tombstone's delete
     * until the add is pushed.
     */
    public void supersede(
            String collection, String linkId, String byCollection, String byLinkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collectionOf(collection));
        values.put("link_id", linkId);
        values.put("by_collection", collectionOf(byCollection));
        values.put("by_link_id", byLinkId);
        PimdirSql.Bound bound = PimdirSql.bind("SUPERSEDE_ITEM", values);
        store.getWritableDatabase().execSQL(bound.sql, bound.args);
    }

    /** Writes to run as one, {@link #atomically}. */
    public interface Writes {
        void run() throws JSONException;
    }

    /**
     * Runs {@code writes} as one transaction under the store's writer.
     *
     * <p>The engine's own write batches nest in it, so several staged
     * mutations and the store's own rows land together or not at all; the
     * blobs a batch collected go once the outermost transaction ends.
     */
    public void atomically(Writes writes) throws JSONException {
        synchronized (store.locks().store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                writes.run();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                PimdirItems.unlinkCollected(store, db, blobs);
            }
        }
    }

    /**
     * Writes the source's sync state into a load reply; answers if one is open.
     *
     * <p>Checkpoint, last coverage (SYNC §5, STORAGE §4.3) and the open round,
     * read by the canonical {@code load_checkpoint} and {@code load_round}.
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
     * The open round's unstamped based bindings in its scope (SYNC §5).
     *
     * <p>What its last page finds absent unless listed; undated members count
     * only on a whole-scope round, a band round leaves them be.
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
     * An opaque token column as text, or null when empty or unset.
     *
     * <p>A checkpoint or resume cursor: BLOB in the STRICT schema, text on the
     * wire.
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
     * The load scope's extra {@code WHERE}, its binds appended to {@code args}.
     *
     * <p>Empty for a whole collection. The list binds once as JSON through
     * {@code json_each} (no variable limit); links seek the items' key, handles
     * {@code bindings_by_handle}.
     */
    private static String scopeClause(
            JSONObject scope, String collection, String source, List<String> args)
            throws JSONException {
        if (scope == null) {
            return "";
        }

        String kind = scope.optString("kind", "all");
        JSONArray values = scope.optJSONArray("handles".equals(kind) ? "handles" : "links");
        if (values == null || values.length() == 0) {
            return "";
        }

        if (!"handles".equals(kind)) {
            args.add(values.toString());
            return " AND i.link_id IN (SELECT value FROM json_each(?))";
        }

        // NOTE: an unbound row is placed under its provisional handle
        // (placementOf), so a handle scope has to reach it under the identity
        // that handle spells, or the row the engine staged and is now editing
        // comes back missing. A row that name reaches but whose binding holds
        // another handle is not one asked for.
        JSONArray names = new JSONArray();
        for (int index = 0; index < values.length(); index++) {
            names.put(nameOf(values.getString(index)));
        }
        args.add(collection);
        args.add(source);
        args.add(values.toString());
        args.add(names.toString());
        args.add(values.toString());
        return " AND i.link_id IN (SELECT link_id FROM bindings"
                + " WHERE collection = ? AND source = ?"
                + " AND handle IN (SELECT value FROM json_each(?))"
                + " UNION ALL SELECT value FROM json_each(?))"
                + " AND (b.handle IS NULL OR b.handle IN (SELECT value FROM json_each(?)))";
    }

    /**
     * One item-plus-binding row as the engine's placement shape.
     *
     * <p>The summary is not read back: a write carrying none keeps the stored
     * row (SPEC.md §9.3), and the engine only sets one from a derivation.
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
     * What a placement owes, derived from the row (SYNC §3), first rule first.
     *
     * <p>Never stored, per the standard. Without {@code dirty} a staged edit
     * loads as agreed; without {@code created} an unbound item reads as gone
     * and is retired: both silently lose the change.
     */
    private static String statusOf(Cursor cursor) {
        // NOTE: a divergence wins over every rule below.
        boolean conflicted =
                (!cursor.isNull(10) && cursor.getInt(10) == 1)
                        || (!cursor.isNull(14) && cursor.getInt(14) == 1);
        if (conflicted) {
            return "conflict";
        }

        // NOTE: a bound tombstone keeps its content, so a later edit still
        // beats the delete.
        boolean bound = !cursor.isNull(5);
        if (cursor.getInt(13) == 1 && bound) {
            return "tombstone";
        }

        // NOTE: a create is an item nothing agreed on here: bound without base,
        // or unbound (load projects one only where the store holds a body).
        if (!bound || !hasBase(cursor)) {
            return "created";
        }

        // NOTE: a placement holding no body owes no body, whatever its base.
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
            // NOTE: an unreadable set cannot disagree, or a push nobody asked
            // for would follow.
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
     * Where else this source holds the item, as its origin, or null.
     *
     * <p>Adding a contact to a second book is then a membership patch on
     * account-level backends, not a second card.
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

    /**
     * Which link ids already have a stored body, replying to a lookup yield.
     *
     * <p>Always empty for contacts: the engine's dedup assumes a link id names
     * immutable bytes, true of mail and calendar bodies, false of cards
     * (replicas sharing a UID diverge).
     */
    public JSONObject lookupObjects(JSONArray links) throws JSONException {
        return lookupObjects(links, null);
    }

    /**
     * {@link #lookupObjects(JSONArray)} within one account's collections.
     *
     * <p>STORAGE §14 binds the caller's account: across accounts a link id is
     * not a fact. A null account means every account's.
     */
    public JSONObject lookupObjects(JSONArray links, String account) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        JSONObject objects = new JSONObject();

        for (int index = 0; index < links.length(); index++) {
            String linkId = links.getString(index);
            try (Cursor cursor =
                    db.rawQuery(
                            "SELECT i.object_hash, o.size FROM items i"
                                    + " JOIN collections c ON c.id = i.collection"
                                    + " JOIN objects o ON o.hash = i.object_hash"
                                    + " WHERE i.link_id = ? AND c.kind <> ?"
                                    + (account == null ? "" : " AND c.account = ?")
                                    + " LIMIT 1",
                            account == null
                                    ? new String[] {linkId, PimdirSummary.CONTACT}
                                    : new String[] {linkId, PimdirSummary.CONTACT, account})) {
                if (cursor.moveToFirst() && blobs.has(cursor.getString(0))) {
                    // NOTE: the size is the witness an immutable link needs
                    // (SYNC §6): the engine records the object unread.
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

    /** Applies a batch of engine writes atomically; returns what it did. */
    public JSONArray applyWrites(JSONArray writes) throws JSONException {
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
            PimdirItems.unlinkCollected(store, db, blobs);
        }
    }

    /**
     * The handles this batch supersedes, by stored collection and source.
     *
     * <p>Read ahead: they license a rebind (an accepted add's provisional
     * handle, a rebuilt spine), which otherwise is one source holding an
     * identity twice.
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
     * Applies one drop in place, recording what the rest of the batch reads.
     *
     * <p>The link id its handle was bound to, continued by a later unnamed
     * upsert of that handle (as io-pimdir resolves it), and the staged mail
     * create it superseded.
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
        // NOTE: a body the driver filed as it fetched it (a message read
        // into a file) is in the blob store already: its row alone.
        // A row without its file would be a body lost behind a level that
        // says full: refused, the batch rolls back and the body stays owed.
        if (op.optBoolean("persisted")) {
            if (!blobs.has(hash)) {
                throw new PimalayaException("The body " + hash + " is not in the blob store");
            }
            db.execSQL(
                    "INSERT INTO objects(hash, size, refcount) VALUES(?, ?, 0)"
                            + " ON CONFLICT(hash) DO NOTHING",
                    new Object[] {hash, op.getLong("size")});
            return;
        }
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
     * Writes one placement; returns {@code created}, {@code changed} or null.
     *
     * <p>Null for bookkeeping only (flags, bases). {@code released} is what
     * earlier drops unbound, by handle; {@code fresh} collects the items
     * created.
     */
    private String applyUpsert(SQLiteDatabase db, JSONObject placement, Set<String> superseded,
            Set<String> links, Map<String, String> released, Set<String> fresh)
            throws JSONException {
        String engineCollection = placement.getString("collection");
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);
        String handle = placement.getString("handle");
        // NOTE: never file an unnamed handle as its own identity: that mints
        // an item the next fetch un-mints, two bindings on one handle. Use the
        // handle's bound identity (STORAGE §10), now or at the batch's start.
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
        // NOTE: a tombstone stays as a marked row until pushed, so the merge
        // keeps deriving the remove and a later edit beats it.
        boolean tombstone = "tombstone".equals(placement.optString("status", "clean"));

        // NOTE: nothing reaches the store unnamed (SYNC §10): a listing names
        // every member it carries, so an upsert naming no identity on a
        // handle no binding holds is a driver bug, refused rather than
        // filed under a key the next listing would have to un-mint.
        if (linkId == null) {
            throw new PimalayaException(
                    "Unnamed placement " + collection + "/" + handle + " on " + source);
        }

        // NOTE: a handle that named another identity (replaced in place, or
        // resolved by fetch) has that binding retired first, as a delete would
        // (STORAGE §10): a handle names one item per source.
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

        // NOTE: a message's body never changes under its link id, so an
        // upsert carrying none below the level the row holds is one the
        // engine loaded before the body was filed (a reader opening it, a
        // fill chunk landing) and merged while its network ran: it never
        // takes the body away. Releasing one is RELEASE_BEFORE's,
        // releaseCrossLinked's and the collectors' to do, a staged
        // removal's and a conflict's the engine's to say.
        String kept = null;
        if (mail && previousObject != null && object == null && !tombstone
                && !"conflict".equals(placement.optString("status", "clean"))) {
            int heldLevel =
                    Integer.parseInt(
                            one(
                                    db,
                                    "SELECT level FROM items WHERE collection = ?"
                                            + " AND link_id = ?",
                                    collection, linkId));
            if (level < heldLevel) {
                kept = previousObject;
                object = previousObject;
                level = heldLevel;
            }
        }

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
            // NOTE: a write not restating the key keeps it (SPEC.md §9.3); the
            // reference write replaces all, so blanking would reset ordering.
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

        // NOTE: a write without summary keeps the stored row (flag pushes,
        // pulled deletions). A message created from another (a landed create,
        // a copy, a move's target) takes that one's stored summary.
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
        writeBinding(db, collection, source, linkId, handle, placement, superseded, !exists, kept);
        adjustRefcount(db, previousObject, object);

        if (!exists) {
            fresh.add(collection + "\n" + linkId);
            return "created";
        }
        return sameObject(previousObject, object) ? null : "changed";
    }

    /**
     * Writes the source's binding: handle, merge base, pending divergence.
     *
     * <p>A duplicated identity is two link ids, never one binding; a handle
     * moves only on a rebind the batch supersedes. {@code kept} is what {@link
     * #applyUpsert} kept, or null.
     */
    private void writeBinding(SQLiteDatabase db, String collection, String source, String linkId,
            String handle, JSONObject placement, Set<String> superseded, boolean fresh,
            String kept) throws JSONException {
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

        // NOTE: a base and a captured conflict body are references (SPEC.md
        // §5): counting only the current object would collect a body the next
        // three-way merge diffs against.
        String previousBase = fresh ? null : baseObjectOf(db, collection, linkId, source);
        String previousConflict =
                fresh ? null : conflictObjectOf(db, collection, linkId, source);
        // NOTE: the body applyUpsert kept, the base agreed on it kept too.
        if (kept != null && baseObject == null && kept.equals(previousBase)) {
            baseObject = previousBase;
        }

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
     * <p>Retained, never deleted (SPEC.md §11): the body stays pinned until a
     * purge. Without {@code deleted} only the binding goes (a superseded row),
     * so a rebuilt spine is renumbered, not retired.
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
     * One SYNC §5 round op by its canonical statement.
     *
     * <p>Round opened, cursor landed, closed with coverage, or coverage
     * restated. {@code scope}'s absent bounds are open; cursor and checkpoint
     * bind as BLOBs.
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

    /** The collection row a source's sync state hangs off, made if missing. */
    private static void ensureCollection(SQLiteDatabase db, String collection) {
        db.execSQL(PimdirSql.of("ENSURE_COLLECTION").replace(":collection", "?")
                        .replace(":account", "NULL"),
                new Object[] {collection, collection});
    }

    private void setCheckpoint(SQLiteDatabase db, JSONObject op) throws JSONException {
        String collection = collectionOf(op.getString("collection"));
        String source = sourceOf(op.getString("collection"));
        ensureCollection(db, collection);
        // NOTE: the column is BLOB in a STRICT table, so a TEXT bind is
        // refused; a checkpoint (QRESYNC state, JMAP state, sync-token) is
        // opaque bytes to the store.
        byte[] checkpoint =
                op.optString("checkpoint", "").getBytes(StandardCharsets.UTF_8);
        db.execSQL(
                "INSERT INTO sources(collection, source, checkpoint) VALUES(?, ?, ?)"
                        + " ON CONFLICT(collection, source) DO UPDATE SET"
                        + " checkpoint = excluded.checkpoint",
                new Object[] {collection, source, checkpoint});
    }

    /**
     * The flag set this source last agreed on, or null for none.
     *
     * <p>What a marker push diffs against: a one-marker-at-a-time backend must
     * never get a whole set, which would strip keywords this app does not
     * model.
     */
    public Flags baseFlags(String engineCollection, String handle) throws JSONException {
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
            return Flags.parse(cursor.getString(0));
        }
    }

    /**
     * The item behind a handle for the push adapter, or null when unknown.
     *
     * <p>{@code id} is the link id a backend addresses, {@code uri} the
     * source's handle; they differ only on CardDAV.
     */
    public JSONObject loadRow(String engineCollection, String handle) throws JSONException {
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
     * A manual resolution's local, base and captured remote bodies.
     *
     * <p>Null until the remote body is captured. The base falls back to the
     * local body (a create collision), so remote changes merge in cleanly.
     */
    public JSONObject loadConflict(String engineCollection, String handle) throws JSONException {
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
     * The handles the hydrate pass upgrades: below full, or conflicted unfed.
     *
     * <p>A conflict without {@code conflict_object} is the request for the
     * remote body, which the engine never fetches itself; left out, the
     * conflict could not resolve offline.
     */
    public List<String> handlesBelowFull(String engineCollection) {
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

    /** Whether this source binds anything in a collection (one probe). */
    public boolean bindsAny(String engineCollection) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT EXISTS (SELECT 1 FROM bindings"
                                + " WHERE collection = ? AND source = ?)",
                        new String[] {
                            collectionOf(engineCollection), sourceOf(engineCollection)
                        })) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    /**
     * Which of some handles the source binds in some collections.
     *
     * <p>What a step leaves out of its page; one seek per handle on {@code
     * bindings_by_handle}.
     */
    public Set<String> heldOf(List<String> engineCollections, List<String> handles) {
        Set<String> held = new HashSet<>();
        if (engineCollections.isEmpty() || handles.isEmpty()) {
            return held;
        }
        JSONArray collections = new JSONArray();
        for (String engineCollection : engineCollections) {
            collections.put(collectionOf(engineCollection));
        }
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT DISTINCT handle FROM bindings"
                                + " WHERE collection IN (SELECT value FROM json_each(?))"
                                + " AND source = ? AND handle IN (SELECT value FROM json_each(?))",
                        new String[] {
                            collections.toString(),
                            sourceOf(engineCollections.get(0)),
                            new JSONArray(handles).toString()
                        })) {
            while (cursor.moveToNext()) {
                held.add(cursor.getString(0));
            }
        }
        return held;
    }

    /**
     * Each bound handle's link id and agreed revision (null if immutable).
     *
     * <p>Unbound handles are absent. Naming a listing (SYNC §4) reads bodies
     * only for new or changed members.
     */
    public Map<String, String[]> bound(String engineCollection, List<String> handles) {
        Map<String, String[]> bound = new HashMap<>();
        if (handles.isEmpty()) {
            return bound;
        }
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT handle, link_id, base_revision FROM bindings"
                                + " WHERE collection = ? AND source = ?"
                                + " AND handle IN (SELECT value FROM json_each(?))",
                        new String[] {
                            collectionOf(engineCollection),
                            sourceOf(engineCollection),
                            new JSONArray(handles).toString()
                        })) {
            while (cursor.moveToNext()) {
                bound.put(
                        cursor.getString(0),
                        new String[] {
                            cursor.getString(1), cursor.isNull(2) ? null : cursor.getString(2)
                        });
            }
        }
        return bound;
    }

    /**
     * One collection's unresolved conflicts, with their merge inputs.
     *
     * <p>Local, base and remote bodies plus the remote revision marked. A row
     * awaiting its remote body is left out until hydrate ({@link
     * #handlesBelowFull}) lands it.
     */
    public List<JSONObject> loadConflicts(String engineCollection) throws JSONException {
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
     * Refreshes the remote revision a conflicted binding was marked against.
     *
     * <p>So the resolving push is conditioned on the state the merge used.
     */
    public void setConflictRevision(String engineCollection, String handle, String revision) {
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

    /**
     * Whether the source has anything to reconcile.
     *
     * <p>A conflict, a held item the store retired, or a body moved past its
     * base. ContactsContract has no change token, so this stands in and skips
     * walking every phone contact.
     */
    public boolean pending(String engineCollection) {
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
     * Forgets one source's bindings in a collection, as superseded.
     *
     * <p>The items stay and the next pass recreates them. For members gone
     * outside a sync (the calendar provider deletes events with their
     * calendar).
     */
    public void forget(String engineCollection) throws JSONException {
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
    public int memberCount(String engineCollection) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM items WHERE collection = ? AND deleted = 0"
                                + " AND retained_at IS NULL AND object_hash IS NOT NULL",
                        new String[] {collectionOf(engineCollection)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /**
     * The handle this source addresses an item by.
     *
     * <p>The bound one, else its create's provisional handle (SYNC §2).
     */
    public String handleFor(String engineCollection, String linkId) {
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
     * The key a staged copy or move takes where the identity is already held.
     *
     * <p>{@code dup:}, the key, {@code #}, the provisional handle (STORAGE §9).
     */
    static String mintedOf(String linkId) {
        return "dup:" + linkId + "#" + provisionalOf(linkId);
    }

    /** The identities a pending create may copy: its key, and its mint's. */
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
     * Where a tombstone moves to (SYNC §3), as its origin, or null.
     *
     * <p>Another collection holding a pending create of that identity (own or
     * minted key), with that create's handle.
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

    /** The pending create a move staged here, own or minted key, or null. */
    public String pendingCreateOf(String engineCollection, String linkId) {
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
     * The staged move a pending create targets, or null.
     *
     * <p>As {@code [collection, handle]}: a still-bound tombstone of that
     * identity in another collection.
     */
    public String[] moveSourceOf(String engineCollection, String linkId) {
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

    /** Whether {@code linkId} is a pending create here: live, never agreed. */
    public boolean isPendingCreate(String engineCollection, String linkId) {
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
     * Withdraws a pending create outright, as if never staged.
     *
     * <p>For a move's target once delivered: the arrival comes with the next
     * listing. A row no longer a pending create is left alone.
     */
    public void withdrawCreate(String engineCollection, String linkId) {
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
            PimdirItems.unlinkCollected(store, db, blobs);
        }
    }

    /**
     * A collection's pending mail creates by bare {@code Message-ID}.
     *
     * <p>Lands a staged copy on the provider's own arrival (a sent message).
     * Read off the provisional handle range, so cost follows the creates, not
     * the mailbox.
     */
    public Map<String, String> pendingCreatesByMessageId(String engineCollection) {
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

    /** Whether a stored collection holds mail, read within a write batch. */
    private boolean isMail(SQLiteDatabase db, String collection) {
        return PimdirSummary.MAIL.equals(
                one(db, "SELECT kind FROM collections WHERE id = ?", collection));
    }

    /**
     * Gives a fresh message its source message's stored summary.
     *
     * <p>From the item staged as its create ({@code staged}), else its origin;
     * answers whether one was there.
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

    /**
     * The link id a handle names: the bound one, else the provisional's.
     *
     * <p>The fallback covers placements never bound, as every phone item before
     * its first projection, mirroring the load path.
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
     * A link's public id, shared by its placements (SPEC.md §9.1).
     *
     * <p>Drawn from the store-wide counter the first time one is inserted.
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

    /** The write batch's compiled single-value reads; null outside one. */
    private Map<String, SQLiteStatement> compiled;

    /**
     * The first column of a read's first row, null for none or NULL.
     *
     * <p>Compiled, not a cursor (a mail page runs several per message);
     * compiled once per write batch and rebound.
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

    /** Moves the refcount by the difference this placement made. */
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

    /** Drops unreferenced objects, blobs unlinked after the batch commits. */
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
        }
        // NOTE: the row goes inside the transaction, the file once it commits.
        PimdirItems.deferUnlink(store, orphans);
    }

    private static boolean sameObject(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** A stored JSON array column (markers, handles), empty when NULL. */
    private static JSONArray arrayOf(String json) throws JSONException {
        return json == null || json.isEmpty() ? new JSONArray() : new JSONArray(json);
    }

    /**
     * Whether the binding row records an agreed base.
     *
     * <p>{@code base_present} is the fact (an empty base is an agreement, or
     * the same push re-derives every run); the value columns witness rows
     * predating it.
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
     * The detail ladder as stored: 1 meta, 2 full (STORAGE §13).
     *
     * <p>An older draft's 0 (probed) reads as meta.
     */
    private static int levelValue(String name) {
        return "full".equals(name) ? 2 : 1;
    }

    private static String levelName(int value) {
        return value == 2 ? "full" : "meta";
    }
}
