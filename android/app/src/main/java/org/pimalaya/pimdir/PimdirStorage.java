package org.pimalaya.pimdir;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

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
     * The identity a create is pushed to its server as (STORAGE §9): {@link
     * #nameOf}, its one leading {@code esc:} stripped, the prefix a stated
     * {@code UID} beginning with a reserved one is filed under and no server
     * knows ({@code PimdirLinkId::stated}).
     */
    public static String statedOf(String handle) {
        String name = nameOf(handle);
        return name.startsWith(ESCAPED) ? name.substring(ESCAPED.length()) : name;
    }

    /** The prefix a stated identity beginning with a reserved one takes. */
    private static final String ESCAPED = "esc:";

    /**
     * The collection's placements and sync cursor, replying to a load yield.
     *
     * <p>Retained rows are left out (SPEC.md §11), staged tombstones kept or
     * the remove never pushes. {@code scope}, the engine's selection (STORAGE
     * §2), narrows to the rows touched ({@link #scopeClause}): a floor, more
     * stays correct. A round's last page ({@code lastPage}) loads them all
     * with the bindings the round finds absent.
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
        if (roundOpen && "lastPage".equals(kind)) {
            reply.put("unstamped", unstamped(db, stored, source, scope));
        }

        // NOTE: a handle scope naming none asks for the sync state alone,
        // which is what a sync reads before it lists (SYNC §5); answering it
        // with the whole collection would read a 100k-message mailbox to
        // learn its checkpoint.
        JSONArray named = scope == null ? null : scope.optJSONArray("handles");
        if ("handles".equals(kind) && (named == null || named.length() == 0)) {
            return reply;
        }

        try (Cursor cursor = PimdirDb.query(db, scoped(scope, stored, source))) {
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
        superseded(db, stored, source, placements, reply);
        return reply;
    }

    /**
     * Writes the member each loaded pending create may be moved from into a
     * load reply (SYNC §3): the create superseded an item of its own identity
     * in another collection, which this source binds there with a base. Read
     * by the canonical {@code list_superseded_bindings}, by link id, answered
     * by the create's handle with the member's engine collection, handle and
     * base revision, which a connector able to move delivers the create from.
     */
    private static void superseded(SQLiteDatabase db, String collection, String source,
            JSONArray placements, JSONObject reply) throws JSONException {
        Map<String, JSONObject> members = new HashMap<>();
        PimdirSql.Bound bound =
                PimdirSql.bind(
                        "LIST_SUPERSEDED_BINDINGS",
                        Map.of("collection", collection, "source", source));
        try (Cursor cursor = PimdirDb.query(db, bound)) {
            while (cursor.moveToNext()) {
                String from = cursor.getString(1);
                JSONObject member = new JSONObject();
                member.put("collection", PHONE.equals(source) ? phoneCollection(from) : from);
                member.put("handle", cursor.getString(2));
                if (!cursor.isNull(3)) {
                    member.put("ifMatch", cursor.getString(3));
                }
                members.put(cursor.getString(0), member);
            }
        }
        if (members.isEmpty()) {
            return;
        }
        JSONArray superseded = new JSONArray();
        for (int index = 0; index < placements.length(); index++) {
            JSONObject placement = placements.getJSONObject(index);
            JSONObject member = members.get(placement.optString("linkId"));
            if (member != null && "created".equals(placement.optString("status"))) {
                superseded.put(
                        new JSONObject()
                                .put("handle", placement.getString("handle"))
                                .put("supersedes", member));
            }
        }
        reply.put("superseded", superseded);
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
        try (Cursor cursor = PimdirDb.query(db, bound)) {
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
        PimdirDb.changed(store.getWritableDatabase(), bound);
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
     * only on a whole-scope round, a band round leaves them be. A round its
     * listing a date filter narrowed finds absent only a member dated before
     * it opened, or one the page's unfiltered enumeration lacks ({@code
     * :narrowed}, {@code :enumerated}, SYNC §4).
     */
    private static JSONArray unstamped(
            SQLiteDatabase db, String collection, String source, JSONObject selection)
            throws JSONException {
        JSONArray enumerated = selection.optJSONArray("enumerated");
        Map<String, Object> key = new HashMap<>();
        key.put("collection", collection);
        key.put("source", source);
        key.put("narrowed", selection.optBoolean("narrowed") ? 1 : 0);
        key.put("enumerated", enumerated == null ? 0 : 1);
        key.put("handles", enumerated == null ? new JSONArray() : enumerated);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_UNSTAMPED_BINDINGS", key);

        return PimdirDb.read(
                db,
                bound,
                cursor -> {
                    JSONArray handles = new JSONArray();
                    while (cursor.moveToNext()) {
                        handles.put(cursor.getString(0));
                    }
                    return handles;
                });
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
     * The read of a load's placements over its scope ({@code
     * load_placements}): the whole collection, or the link ids or handles the
     * scope names, bound as lists (no variable limit); links seek the items'
     * key, handles {@code bindings_by_handle}.
     */
    private static PimdirSql.Bound scoped(JSONObject scope, String collection, String source)
            throws JSONException {
        Map<String, Object> values = PimdirSql.values("collection", collection, "source", source);
        String kind = scope == null ? "all" : scope.optString("kind", "all");
        JSONArray named =
                scope == null
                        ? null
                        : scope.optJSONArray("handles".equals(kind) ? "handles" : "links");
        if (named == null || named.length() == 0) {
            return PimdirSql.bind("LOAD_PLACEMENTS", values);
        }

        if (!"handles".equals(kind)) {
            values.put("links", named);
            return PimdirSql.bind("LOAD_PLACEMENTS_BY_LINK", values);
        }

        // NOTE: an unbound row is placed under its provisional handle
        // (placementOf), so a handle scope has to reach it under the identity
        // that handle spells, or the row the engine staged and is now editing
        // comes back missing. A row that name reaches but whose binding holds
        // another handle is not one asked for.
        JSONArray names = new JSONArray();
        for (int index = 0; index < named.length(); index++) {
            names.put(nameOf(named.getString(index)));
        }
        values.put("handles", named);
        values.put("names", names);
        return PimdirSql.bind("LOAD_PLACEMENTS_BY_HANDLE", values);
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
            placement.put("flags", new JSONArray(PimdirItems.flagsFrom(cursor.getString(1))));
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
        // NOTE: and its subject and sender beside it: what the engine tells
        // a listed message reusing a create's Message-ID by, before it
        // lands the create on it (SYNC §6). Without them a pending create
        // never lands on its own arrival and is pushed a second time.
        if (cursor.getColumnCount() > 18 && !cursor.isNull(17)) {
            placement.put("subject", cursor.getString(17));
            if (!cursor.isNull(18)) {
                placement.put("sender", cursor.getString(18));
            }
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
                base.put("flags", new JSONArray(PimdirItems.flagsFrom(cursor.getString(6))));
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
        return new HashSet<>(PimdirItems.flagsFrom(one))
                .equals(new HashSet<>(PimdirItems.flagsFrom(other)));
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
                PimdirDb.query(
                        db,
                        "BOUND_ELSEWHERE",
                        PimdirSql.values(
                                "link_id", linkId, "source", source, "collection", stored))) {
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
                    PimdirDb.query(
                            db,
                            "HELD_OBJECT",
                            PimdirSql.values("link_id", linkId, "account", account))) {
                // NOTE: only a body read whole under its name links (STORAGE
                // §7); one failing it goes to quarantine and is fetched.
                if (cursor.moveToFirst() && blobs.verified(cursor.getString(0))) {
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
        rebinding = new HashMap<>();
        overruling = new HashSet<>();
        settling = new HashSet<>();
        try {
            Map<String, byte[]> bodies = new HashMap<>();
            List<JSONObject> stamps = new ArrayList<>();
            Set<String> links = new HashSet<>();
            Set<String> fresh = new HashSet<>();
            Set<String> staged = new HashSet<>();
            Map<String, String> released = new HashMap<>();
            JSONArray effects = new JSONArray();
            Set<String> superseded = supersededHandles(writes);
            syncVersion(db);

            for (int index = 0; index < writes.length(); index++) {
                JSONObject op = writes.getJSONObject(index);
                switch (op.getString("op")) {
                    case "storeObject":
                        storeObject(db, op, bodies);
                        break;
                    case "upsert": {
                        JSONObject placement = op.getJSONObject("placement");
                        String kind = applyUpsert(
                                db, placement, superseded, links, released, fresh, effects);
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
                    case "overrule":
                        overrule(db, op, effects);
                        overruling.add(op.getString("collection") + "\n" + op.getString("handle"));
                        break;
                    case "settle":
                        settling.add(op.getString("collection") + "\n" + op.getString("handle"));
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
            rebinding = null;
            overruling = null;
            settling = null;
            PimdirItems.unlinkCollected(store, db, blobs);
        }
    }

    /**
     * Records the engine's SYNC version on the first write of its state
     * (STORAGE §4.3, {@code set_sync_version}), refusing one another engine
     * version wrote ({@code sync-version}): its rounds and bases are not this
     * engine's to read.
     */
    private void syncVersion(SQLiteDatabase db) {
        String engine = PimdirSql.identity().optString("syncVersion");
        String stored = one(db, "STORE_SYNC_VERSION", Map.of());
        if (stored == null) {
            Map<String, Object> values = new HashMap<>();
            values.put("sync_version", engine);
            PimdirSql.Bound bound = PimdirSql.bind("SET_SYNC_VERSION", values);
            PimdirDb.changed(db, bound);
        } else if (!stored.equals(engine)) {
            throw new PimalayaException(
                    "sync-version: the store's sync state is " + stored + "'s, not "
                            + engine + "'s");
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

    /**
     * Settles the conflict the next upsert of a handle resolves by a rule
     * rather than a person (SYNC §7, §9): the diverging bodies it does not
     * adopt, the binding's {@code conflict_object} then the item's, are kept
     * as retained items keyed {@code dup:<link>#<handle>}, each an {@code
     * overruled} effect. Refused, the batch rolled back, while the conflict
     * holds a body nobody has read ({@code conflict-unread}).
     */
    private void overrule(SQLiteDatabase db, JSONObject op, JSONArray effects)
            throws JSONException {
        String engine = op.getString("collection");
        String collection = collectionOf(engine);
        String source = sourceOf(engine);
        String handle = op.getString("handle");
        String linkId = linkFor(db, collection, source, handle);
        if (linkId == null) {
            return;
        }

        String[] item = new String[5];
        String[] binding = new String[2];
        try (Cursor cursor =
                PimdirDb.query(db, "OVERRULED_STATE", binding(collection, linkId, source))) {
            if (!cursor.moveToFirst()) {
                return;
            }
            for (int index = 0; index < 5; index++) {
                item[index] = cursor.isNull(index) ? null : cursor.getString(index);
            }
            binding[0] = cursor.isNull(5) ? null : cursor.getString(5);
            binding[1] = cursor.isNull(6) ? null : cursor.getString(6);
        }
        boolean unread =
                ("1".equals(binding[0]) && binding[1] == null)
                        || ("1".equals(item[0]) && item[1] == null);
        if (unread) {
            throw new PimalayaException(
                    "conflict-unread: " + collection + "/" + handle
                            + " holds a diverging body nobody has read");
        }

        Set<String> kept = new HashSet<>();
        for (String lost : new String[] {binding[1], item[1]}) {
            if (lost == null || lost.equals(item[2]) || !kept.add(lost)) {
                continue;
            }
            String key = keepOverruled(db, collection, linkId, handle, source, lost, item[3],
                    item[4]);
            JSONObject effect = effect(engine, handle, "overruled");
            effect.put("linkId", key);
            effects.put(effect);
        }
    }

    /**
     * Keeps a body a rule did not adopt as a retained item beside the one it
     * lost to (SYNC §9, STORAGE §11), keyed by the identity minted over the
     * settling handle, minted again over a held key; answers its key.
     */
    private String keepOverruled(SQLiteDatabase db, String collection, String linkId,
            String handle, String source, String object, String sortKey, String flags) {
        String key = "dup:" + linkId + "#" + handle;
        while (one(db, "ITEM_EXISTS", PimdirItems.key(collection, key)) != null) {
            key = "dup:" + key + "#" + handle;
        }
        Map<String, Object> kept = PimdirItems.key(collection, key);
        kept.put("seq", PimdirItems.seqFor(db, collection, key));
        kept.put("flags_known", flags == null ? 0 : 1);
        kept.put("object_hash", object);
        kept.put("sort_key", sortKey == null ? "" : sortKey);
        kept.put("level", PimdirItems.FULL);
        kept.put("deleted", 1);
        kept.put("conflicted", 0);
        PimdirDb.exec(db, "INSERT_ITEM", kept);
        PimdirItems.writeFlags(db, collection, key, PimdirItems.flagsFrom(flags));
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("link_id", key);
        values.put("source", source);
        PimdirSql.Bound retain = PimdirSql.bind("RETAIN_ITEM", values);
        PimdirDb.changed(db, retain);
        adjustRefcount(db, null, object);
        return key;
    }

    /**
     * What a write batch recorded beyond its ops (SYNC §9), the engine's
     * {@code Written} reply: the bodies kept as retained items and the handles
     * whose upsert recorded a divergence on the item, from the {@code
     * overruled} and {@code conflicted} effects {@link #applyWrites} answered.
     */
    public static JSONObject written(JSONArray effects) throws JSONException {
        JSONArray overruled = new JSONArray();
        JSONArray conflicted = new JSONArray();
        for (int index = 0; index < effects.length(); index++) {
            JSONObject effect = effects.getJSONObject(index);
            if ("overruled".equals(effect.optString("kind"))) {
                overruled.put(
                        new JSONObject()
                                .put("handle", effect.getString("handle"))
                                .put("linkId", effect.getString("linkId")));
            } else if ("conflicted".equals(effect.optString("kind"))) {
                conflicted.put(effect.getString("handle"));
            }
        }
        JSONObject written = new JSONObject();
        if (overruled.length() > 0) {
            written.put("overruled", overruled);
        }
        if (conflicted.length() > 0) {
            written.put("conflicted", conflicted);
        }
        return written;
    }

    /**
     * Which bodies read whole under their name, replying to a verify yield
     * (STORAGE §7): a missing one is left out, a corrupt one moved into
     * quarantine/ and left out, so nothing failing its name is pushed.
     */
    public JSONObject verify(JSONArray hashes) throws JSONException {
        JSONArray verified = new JSONArray();
        for (int index = 0; index < hashes.length(); index++) {
            String hash = hashes.getString(index);
            if (blobs.verified(hash)) {
                verified.put(hash);
            }
        }
        return new JSONObject().put("verified", verified);
    }

    /** Whether any source binds the item. */
    private static boolean isBound(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                PimdirDb.query(db, "ITEM_BOUND", PimdirItems.key(collection, linkId))) {
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
            PimdirDb.exec(
                    db, "FILE_OBJECT", PimdirSql.values("hash", hash, "size", op.getLong("size")));
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
        PimdirDb.exec(db, "FILE_OBJECT", PimdirSql.values("hash", hash, "size", body.length));
    }

    /**
     * Writes one placement; returns {@code created}, {@code changed} or null.
     *
     * <p>Null for bookkeeping only (flags, bases). {@code released} is what
     * earlier drops unbound, by handle; {@code fresh} collects the items
     * created.
     */
    private String applyUpsert(SQLiteDatabase db, JSONObject placement, Set<String> superseded,
            Set<String> links, Map<String, String> released, Set<String> fresh,
            JSONArray effects) throws JSONException {
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
        List<String> flags = placement.optJSONArray("flags") == null
                ? null
                : stringsOf(placement.getJSONArray("flags"));
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
        // bodiless one read apart; then the item's conflict, which the
        // cross-source merge reads and settles (SYNC §9).
        String[] item = heldItem(db, collection, linkId);
        String held = item == null ? null : item[0];
        boolean exists = held != null;
        String previousObject = held == null || held.isEmpty() ? null : held;
        String previousConflict = item == null ? null : item[2];
        String incoming = object;

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
                            one(db, "ITEM_LEVEL", PimdirItems.key(collection, linkId)));
            if (level < heldLevel) {
                kept = previousObject;
                object = previousObject;
                level = heldLevel;
            }
        }

        // NOTE: a mutable kind several sources hold settles its body by
        // io-pimdir's cross-source merge (SYNC §9): a change one source made
        // flows in, two sources changing it diverge, the item keeping its
        // body, recording the incoming one and standing conflicted, and the
        // upsert reported in Written.conflicted. A message is one identity,
        // one body, and never diverges.
        boolean conflicted = item != null && "1".equals(item[1]);
        String conflictObject = previousConflict;
        String shared;
        if (mail) {
            shared = incoming != null && !conflicted
                    ? object
                    : exists ? sharedObjectOf(db, collection, linkId, source) : null;
        } else {
            String key = engineCollection + "\n" + handle;
            JSONObject merged = merge(db, collection, source, linkId, placement, item,
                    overruling != null && overruling.contains(key),
                    settling != null && settling.contains(key));
            object = merged.optString("object", null);
            conflicted = merged.optBoolean("conflicted");
            conflictObject = merged.optString("conflictObject", null);
            shared = merged.optString("sharedObject", null);
            if (merged.optBoolean("keptShared")) {
                // NOTE: the summary and the sort key describe the body the
                // item kept, so they stay too.
                summary = null;
                sortKey = "";
                level = object == null ? 1 : Math.max(level, Integer.parseInt(item[3]));
            }
            if (merged.optBoolean("diverged")) {
                effects.put(effect(engineCollection, handle, "conflicted"));
            }
        }

        if (!exists) {
            Map<String, Object> row = PimdirItems.key(collection, linkId);
            row.put("seq", PimdirItems.seqFor(db, collection, linkId));
            row.put("flags_known", flags == null ? 0 : 1);
            row.put("object_hash", object);
            row.put("sort_key", sortKey);
            row.put("level", level);
            row.put("deleted", tombstone ? 1 : 0);
            row.put("conflicted", 0);
            PimdirDb.exec(db, "INSERT_ITEM", row);
        } else {
            // NOTE: a write not restating the key keeps it (SPEC.md §9.3); the
            // reference write replaces all, so blanking would reset ordering.
            Map<String, Object> row = PimdirItems.key(collection, linkId);
            row.put("object_hash", object);
            row.put("level", level);
            row.put("sort_key", sortKey);
            row.put("deleted", tombstone ? 1 : 0);
            PimdirDb.exec(db, "RESTATE_ITEM", row);
        }
        // NOTE: the flags are rows (STORAGE §13), written after their item.
        PimdirItems.writeFlags(db, collection, linkId, flags);
        if (!mail) {
            Map<String, Object> conflict = PimdirItems.key(collection, linkId);
            conflict.put("conflicted", conflicted ? 1 : 0);
            conflict.put("conflict_object", conflictObject);
            PimdirDb.exec(db, "SET_ITEM_CONFLICT", conflict);
            adjustRefcount(db, previousConflict, conflictObject);
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
        writeBinding(db, collection, source, linkId, handle, placement, superseded, !exists, kept,
                shared);
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
     * #applyUpsert} kept, or null; {@code shared} the body the binding agrees
     * with the item on (SYNC §9), pinned as the base is (STORAGE §5).
     */
    private void writeBinding(SQLiteDatabase db, String collection, String source, String linkId,
            String handle, JSONObject placement, Set<String> superseded, boolean fresh,
            String kept, String shared) throws JSONException {
        JSONObject base = placement.optJSONObject("base");
        List<String> baseFlags = base == null || base.optJSONArray("flags") == null
                ? null
                : stringsOf(base.getJSONArray("flags"));
        String baseObject = base == null || base.isNull("object")
                ? null
                : base.optString("object", null);
        String baseRevision = base == null || base.isNull("revision")
                ? null
                : base.optString("revision", null);
        // NOTE: the binding's own divergence is the one the row carries, its
        // revision (SYNC §9), as io-pimdir writes it: a placement projected
        // Conflict for its item's conflict carries none, and reading its
        // status would leave the binding conflicted once the item settles.
        String conflictRevision = placement.isNull("conflictRevision")
                ? null
                : placement.optString("conflictRevision", null);
        boolean conflicted = conflictRevision != null;
        String conflictObject = !conflicted || placement.isNull("conflictObject")
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
        String previousShared = fresh ? null : sharedObjectOf(db, collection, linkId, source);
        // NOTE: the body applyUpsert kept, the base agreed on it kept too.
        if (kept != null && baseObject == null && kept.equals(previousBase)) {
            baseObject = previousBase;
        }

        Map<String, Object> row = binding(collection, linkId, source);
        row.put("handle", handle);
        row.put("base_flags_known", baseFlags == null ? 0 : 1);
        row.put("base_object", baseObject);
        row.put("base_revision", baseRevision);
        row.put("base_present", base == null ? 0 : 1);
        row.put("conflicted", conflicted ? 1 : 0);
        row.put("conflict_revision", conflictRevision);
        row.put("conflict_object", conflictObject);
        row.put("shared_object", shared);
        PimdirDb.exec(db, "WRITE_BINDING", row);
        PimdirItems.writeBaseFlags(db, collection, linkId, source, baseFlags);
        adjustRefcount(db, previousBase, baseObject);
        adjustRefcount(db, previousConflict, conflictObject);
        adjustRefcount(db, previousShared, shared);
    }

    /**
     * Retires the placement the source no longer holds.
     *
     * <p>Retained, never deleted (SPEC.md §11): the body stays pinned until a
     * purge. Without {@code deleted} only the binding goes (a superseded row),
     * so a rebuilt spine is renumbered, not retired.
     */
    private void applyDrop(
            SQLiteDatabase db, String engineCollection, String handle, boolean deleted)
            throws JSONException {
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);

        String linkId = linkOf(db, collection, source, handle);
        if (linkId == null) {
            return;
        }
        String base = baseObjectOf(db, collection, linkId, source);
        String conflict = conflictObjectOf(db, collection, linkId, source);
        String shared = sharedObjectOf(db, collection, linkId, source);
        if (!deleted && rebinding != null) {
            JSONObject released = bindingsOf(db, collection, linkId).optJSONObject(source);
            if (released != null) {
                rebinding.putIfAbsent(collection + "\n" + source + "\n" + linkId, released);
            }
        }
        PimdirDb.exec(db, "DELETE_BINDING", binding(collection, linkId, source));
        adjustRefcount(db, base, null);
        adjustRefcount(db, conflict, null);
        adjustRefcount(db, shared, null);

        if (!deleted) {
            return;
        }

        if (!isBound(db, collection, linkId)) {
            PimdirDb.exec(db, "RETAIN_ITEM", binding(collection, linkId, source));
            return;
        }

        // NOTE: another source still holds it, the server of a book or a
        // calendar the phone deleted from, or the phone of one the server
        // did: a tombstone there, which its next pass pushes as a removal,
        // the last of them retiring the item. Left live, the other source
        // would keep it and hand it back as a create.
        PimdirDb.exec(db, "TOMBSTONE_ITEM", PimdirItems.key(collection, linkId));
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
        PimdirDb.changed(db, bound);
    }

    /** Stamps the bindings a page listed with the open round's id. */
    private static void stamp(SQLiteDatabase db, JSONObject op) throws JSONException {
        String engine = op.getString("collection");
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collectionOf(engine));
        values.put("source", sourceOf(engine));
        values.put("handles", op.getJSONArray("handles"));

        PimdirDb.changed(db, PimdirSql.bind("STAMP_BINDINGS", values));
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
        PimdirDb.exec(
                db, "ENSURE_COLLECTION", PimdirSql.values("collection", collection, "account", null));
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
        PimdirDb.exec(
                db,
                "UPSERT_CHECKPOINT",
                PimdirSql.values(
                        "collection", collection, "source", source, "checkpoint", checkpoint));
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
                PimdirDb.query(db, "BASE_FLAGS", binding(collection, linkId, source))) {
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
                PimdirDb.query(db, "PUSH_ROW", binding(collection, linkId, source))) {
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
     * With no binding conflict, the server's binding reads the item's own
     * ({@link #itemConflicts}): its body local, the diverging one remote.
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
                PimdirDb.query(db, "CONFLICT_BODIES", binding(collection, linkId, source))) {
            if (!cursor.moveToFirst()) {
                return null;
            }

            String object = cursor.isNull(0) ? null : cursor.getString(0);
            String local = bodyOf(object);
            JSONObject bodies = new JSONObject();
            bodies.put("local", local);
            if (!cursor.isNull(1)) {
                bodies.put("base", cursor.isNull(2) ? local : bodyOf(cursor.getString(2)));
                bodies.put("remote", bodyOf(cursor.getString(1)));
                return bodies;
            }
            // NOTE: else the item's own conflict, offered on the server's
            // binding alone (loadConflicts).
            if (!SERVER.equals(source) || cursor.getInt(3) != 1 || cursor.isNull(4)
                    || object == null) {
                return null;
            }
            bodies.put("base", ancestorOf(db, collection, linkId, object, cursor.getString(4)));
            bodies.put("remote", bodyOf(cursor.getString(4)));
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
                PimdirDb.query(db, "HANDLES_BELOW_FULL", source(collection, source))) {
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
                PimdirDb.query(
                        db,
                        "BINDS_ANY",
                        source(collectionOf(engineCollection), sourceOf(engineCollection)))) {
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
        Map<String, Object> values =
                PimdirSql.values(
                        "collections", collections,
                        "source", sourceOf(engineCollections.get(0)),
                        "handles", handles);
        try (Cursor cursor = PimdirDb.query(db, "HELD_HANDLES", values)) {
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
        Map<String, Object> values =
                source(collectionOf(engineCollection), sourceOf(engineCollection));
        values.put("handles", handles);
        try (Cursor cursor = PimdirDb.query(db, "BOUND_HANDLES", values)) {
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
     * #handlesBelowFull}) lands it. The server's list ends with the items two
     * sources diverged on ({@link #itemConflicts}).
     */
    public List<JSONObject> loadConflicts(String engineCollection) throws JSONException {
        SQLiteDatabase db = store.getReadableDatabase();
        String collection = collectionOf(engineCollection);
        String source = sourceOf(engineCollection);

        List<JSONObject> conflicts = new ArrayList<>();
        try (Cursor cursor =
                PimdirDb.query(db, "BINDING_CONFLICTS", source(collection, source))) {
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
        }
        if (SERVER.equals(source)) {
            itemConflicts(db, collection, source, conflicts);
        }
        return conflicts;
    }

    /**
     * One collection's item conflicts (SYNC §9), the merge inputs of each in
     * {@link #loadConflicts}' shape: the item's body local, the diverging one
     * remote, the ancestor the disagreeing source last agreed on the base
     * (empty when none is known, so every field the two set apart is asked).
     *
     * <p>Every binding of a conflicted item projects the conflict, so it is
     * offered once, on the server's binding, and a resolution staged there
     * settles it for every source (SYNC §7). A binding's own conflict with
     * its body is offered first: settling it settles the item too.
     */
    private void itemConflicts(SQLiteDatabase db, String collection, String source,
            List<JSONObject> conflicts) throws JSONException {
        try (Cursor cursor =
                PimdirDb.query(db, "ITEM_CONFLICTS", source(collection, source))) {
            while (cursor.moveToNext()) {
                String linkId = cursor.getString(0);
                String object = cursor.getString(1);
                String diverging = cursor.getString(4);
                JSONObject conflict = new JSONObject();
                conflict.put("handle", cursor.getString(2));
                conflict.put("id", linkId);
                conflict.put("uri", cursor.getString(2));
                if (!cursor.isNull(3)) {
                    conflict.put("etag", cursor.getString(3));
                }
                conflict.put("vcard", bodyOf(object));
                conflict.put("baseVcard", ancestorOf(db, collection, linkId, object, diverging));
                conflict.put("remoteVcard", bodyOf(diverging));
                conflicts.add(conflict);
            }
        }
    }

    /**
     * The body two sources diverged from (SYNC §9): what a binding last
     * agreed with the item, its {@code shared_object} or else its base, where
     * that is neither side, so the source the item moved away from; empty
     * when no binding names one.
     */
    private String ancestorOf(SQLiteDatabase db, String collection, String linkId,
            String object, String diverging) throws JSONException {
        String agreed =
                one(
                        db,
                        "ANCESTOR_OBJECT",
                        PimdirSql.values(
                                "collection", collection,
                                "link_id", linkId,
                                "object", object,
                                "diverging", diverging));
        return agreed == null ? "" : bodyOf(agreed);
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
        Map<String, Object> values = binding(collection, linkId, source);
        values.put("conflict_revision", revision);
        PimdirDb.exec(db, "SET_CONFLICT_REVISION", values);
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
                PimdirDb.query(db, "SOURCE_PENDING", source(collection, source))) {
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
                PimdirDb.query(
                        db,
                        "COUNT_HELD_MEMBERS",
                        PimdirSql.values("collection", collectionOf(engineCollection)))) {
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
                PimdirDb.query(
                        db,
                        "HANDLE_FOR_LINK",
                        binding(
                                collectionOf(engineCollection),
                                linkId,
                                sourceOf(engineCollection)))) {
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
        Map<String, Object> values =
                binding(collectionOf(engineCollection), linkId, sourceOf(engineCollection));
        values.put("minted", mintedOf(linkId));
        try (Cursor cursor = PimdirDb.query(db, "PENDING_CREATE_OF", values)) {
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
        Map<String, Object> values =
                binding(collectionOf(engineCollection), linkId, sourceOf(engineCollection));
        values.put("key", identities.size() > 1 ? identities.get(1) : null);
        try (Cursor cursor = PimdirDb.query(db, "MOVE_SOURCE_OF", values)) {
            return cursor.moveToFirst()
                    ? new String[] {cursor.getString(0), cursor.getString(1)}
                    : null;
        }
    }

    /** Whether {@code linkId} is a pending create here: live, never agreed. */
    public boolean isPendingCreate(String engineCollection, String linkId) {
        SQLiteDatabase db = store.getReadableDatabase();
        try (Cursor cursor =
                PimdirDb.query(
                        db,
                        "IS_PENDING_CREATE",
                        binding(
                                collectionOf(engineCollection),
                                linkId,
                                sourceOf(engineCollection)))) {
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
        Map<String, Object> values =
                source(collectionOf(engineCollection), sourceOf(engineCollection));
        values.put("low", PROVISIONAL);
        values.put("high", String.valueOf((char) (PROVISIONAL.charAt(0) + 1)));
        try (Cursor cursor = PimdirDb.query(db, "PENDING_CREATES_BY_MESSAGE_ID", values)) {
            while (cursor.moveToNext()) {
                creates.put(cursor.getString(1), cursor.getString(0));
            }
        }
        return creates;
    }

    /** Whether a stored collection holds mail, read within a write batch. */
    private boolean isMail(SQLiteDatabase db, String collection) {
        return PimdirSummary.MAIL.equals(
                one(db, "LOAD_KIND", PimdirSql.values("collection", collection)));
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
        Map<String, Object> values =
                PimdirSql.values(
                        "from_collection", fromCollection,
                        "from_link_id", fromLink,
                        "to_collection", collection,
                        "to_link_id", linkId);
        boolean copied = PimdirDb.exec(db, "COPY_MAIL_SUMMARY", values) > 0;
        PimdirDb.exec(db, "COPY_ADDRESSES", values);
        return copied;
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
        String name = nameOf(handle);
        return one(db, "ITEM_EXISTS", PimdirItems.key(collection, name)) == null ? null : name;
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
        return one(db, "BINDING_BASE_OBJECT", binding(collection, linkId, source));
    }

    /**
     * One item's body (empty for none), conflict flag, diverging body and
     * level, in that order; null when the collection holds no such item.
     */
    private static String[] heldItem(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                PimdirDb.query(db, "HELD_ITEM", PimdirItems.key(collection, linkId))) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new String[] {
                cursor.getString(0),
                cursor.getString(1),
                cursor.isNull(2) ? null : cursor.getString(2),
                cursor.getString(3)
            };
        }
    }

    /**
     * The shared body this source's binding last agreed with (SYNC §9), null
     * until it has folded once.
     */
    private String sharedObjectOf(
            SQLiteDatabase db, String collection, String linkId, String source) {
        return one(db, "BINDING_SHARED_OBJECT", binding(collection, linkId, source));
    }

    /**
     * Every binding of one item by source, as {@link PimdirSql#merge} reads
     * them: the handle, the base's body and revision, the conflict and the
     * shared body, every absent value left out.
     */
    private static JSONObject bindingsOf(SQLiteDatabase db, String collection, String linkId)
            throws JSONException {
        JSONObject bindings = new JSONObject();
        try (Cursor cursor =
                PimdirDb.query(db, "ITEM_BINDINGS", PimdirItems.key(collection, linkId))) {
            while (cursor.moveToNext()) {
                JSONObject binding = new JSONObject();
                binding.put("handle", cursor.getString(1));
                if (cursor.getInt(2) == 1) {
                    JSONObject base = new JSONObject();
                    putText(base, "object", cursor, 3);
                    putText(base, "revision", cursor, 4);
                    binding.put("base", base);
                }
                binding.put("conflicted", cursor.getInt(5) == 1);
                putText(binding, "conflictRevision", cursor, 6);
                putText(binding, "conflictObject", cursor, 7);
                putText(binding, "sharedObject", cursor, 8);
                bindings.put(cursor.getString(0), binding);
            }
        }
        return bindings;
    }

    /** Puts a text column under {@code key}, leaving NULL out. */
    private static void putText(JSONObject json, String key, Cursor cursor, int column)
            throws JSONException {
        if (!cursor.isNull(column)) {
            json.put(key, cursor.getString(column));
        }
    }

    /**
     * The cross-source merge of one upsert of a mutable kind (SYNC §9),
     * io-pimdir's ({@link PimdirSql#merge}): read before the upsert writes
     * anything, over the item as stored ({@code held}: its body, conflict
     * flag and diverging body, null for a new item) and every binding it
     * holds, the one a drop of this batch released standing in for a source
     * holding none. {@code settled} says a person's edit settles the item's
     * conflict (SYNC §7), io-pimdir's {@code settle} op before it.
     */
    private JSONObject merge(SQLiteDatabase db, String collection, String source,
            String linkId, JSONObject placement, String[] held, boolean overruled,
            boolean settled)
            throws JSONException {
        JSONObject input = new JSONObject();
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        PimdirSql.Bound policy = PimdirSql.bind("LOAD_CONFLICT", values);
        try (Cursor cursor = db.rawQuery(policy.sql, stringsOf(policy.args))) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                input.put("policy", cursor.getString(0));
            }
        }
        input.put("source", source);
        if (held != null) {
            JSONObject item = new JSONObject();
            if (held[0] != null && !held[0].isEmpty()) {
                item.put("object", held[0]);
            }
            item.put("conflicted", "1".equals(held[1]));
            if (held[2] != null) {
                item.put("conflictObject", held[2]);
            }
            input.put("item", item);
        }
        JSONObject bindings = bindingsOf(db, collection, linkId);
        JSONObject released = rebinding == null
                ? null
                : rebinding.remove(collection + "\n" + source + "\n" + linkId);
        if (released != null && !bindings.has(source)) {
            bindings.put(source, released);
        }
        input.put("bindings", bindings);
        input.put("placement", new JSONObject(placement.toString()).put("linkId", linkId));
        if (overruled) {
            input.put("overruling", true);
        }
        if (settled) {
            input.put("settling", true);
        }
        return PimdirSql.merge(input);
    }

    /** The diverging body this source's binding is waiting on, or null. */
    private String conflictObjectOf(
            SQLiteDatabase db, String collection, String linkId, String source) {
        return one(db, "BINDING_CONFLICT_OBJECT", binding(collection, linkId, source));
    }


    /** The handle this source already binds the item under, or null. */
    private String handleOf(SQLiteDatabase db, String collection, String linkId, String source) {
        return one(db, "HANDLE_FOR_LINK", binding(collection, linkId, source));
    }

    private String linkOf(SQLiteDatabase db, String collection, String source, String handle) {
        Map<String, Object> values = source(collection, source);
        values.put("handle", handle);
        return one(db, "LINK_FOR_HANDLE", values);
    }

    /** The write batch's compiled single-value reads; null outside one. */
    private Map<String, SQLiteStatement> compiled;

    /**
     * The bindings a superseded or rekeyed drop released within the write
     * batch, by collection, source and link id, as {@link #merge} reads them:
     * the upsert moving the binding onto its new handle continues it (SYNC
     * §10). Null outside a batch.
     */
    private Map<String, JSONObject> rebinding;

    /**
     * The handles an {@code overrule} op named within the write batch, by
     * engine collection and handle: their next upsert is an overruling edit
     * (SYNC §7). Null outside a batch.
     */
    private Set<String> overruling;

    /**
     * The handles a {@code settle} op named within the write batch, by engine
     * collection and handle: their next upsert is a person's edit settling
     * the item's conflict (SYNC §7). Null outside a batch.
     */
    private Set<String> settling;

    /**
     * The first column of a read's first row, null for none or NULL.
     *
     * <p>Compiled, not a cursor (a mail page runs several per message);
     * compiled once per write batch and rebound.
     */
    private String one(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        String sql = bound.sql;
        Object[] args = bound.args;
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
                    statement.bindString(index + 1, args[index].toString());
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
        PimdirItems.adjust(db, before, -1);
        PimdirItems.adjust(db, after, 1);
    }

    /** Drops unreferenced objects, blobs unlinked after the batch commits. */
    private void collectGarbage(SQLiteDatabase db) {
        // NOTE: the row goes inside the transaction, the file once it commits.
        PimdirItems.deferUnlink(store, PimdirItems.collectGarbageRows(db));
    }

    /** A source's binding of one item as a statement's values. */
    private static Map<String, Object> binding(String collection, String linkId, String source) {
        Map<String, Object> values = PimdirItems.key(collection, linkId);
        values.put("source", source);
        return values;
    }

    /** A collection and a source as a statement's values. */
    private static Map<String, Object> source(String collection, String source) {
        return PimdirSql.values("collection", collection, "source", source);
    }

    private static boolean sameObject(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** The strings of a JSON array, in order. */
    private static List<String> stringsOf(JSONArray values) throws JSONException {
        List<String> strings = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            strings.add(values.getString(index));
        }
        return strings;
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
