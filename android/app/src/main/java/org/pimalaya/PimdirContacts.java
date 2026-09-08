package org.pimalaya;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONObject;
import org.pimalaya.client.Card;
import org.pimalaya.client.Cards;

import java.util.ArrayList;
import java.util.List;

/**
 * The contacts store, on the pimdir tables.
 *
 * <p>What the bespoke schema spelled out in columns, pimdir expresses in its
 * own model, and the translation is the whole of this class:
 *
 * <ul>
 *   <li>a replica is an {@code items} row keyed {@code (collection, link_id)},
 *       its vCard a content-addressed object rather than a TEXT column
 *   <li><strong>membership is placement</strong>: a card in three address books
 *       is three rows sharing one {@code link_id} and therefore one {@code seq},
 *       which is what the old {@code membership} table encoded by hand
 *   <li><strong>dirty is derived, not stored</strong>: an item whose
 *       {@code object_hash} has moved past the {@code base_object} its server
 *       binding agreed on <em>is</em> a pending push, so a staged edit cannot
 *       drift out of sync with the flag that announces it
 *   <li>a staged create is an item with no binding at all, and a staged delete
 *       is {@code deleted = 1} on an item whose binding is still there: the
 *       binding is what says the server knows about it
  *   <li>the summary is the standard's typed row ({@link PimdirSummary}) and the
 *       ordering it never had becomes {@code sort_key}
 * </ul>
 *
 * <p>Nothing here talks to a server. Every method stages, and the next engine
 * pass ({@link OfflineEngine}) derives what to push from the state it finds,
 * which is why none of these take an ETag or a revision.
 */
final class PimdirContacts {
    /** The source name a server-synced binding carries. */
    private static final String SERVER = PimdirStorage.SERVER;

    private final PimdirItems items;

    PimdirContacts(PimdirDb store) {
        this.items = new PimdirItems(store);
    }

    /** One displayable replica: the card plus its summary. */
    static final class Indexed {
        final Card card;

        /** Display name (FN), empty when the card has none. */
        final String name;

        /** First email address, empty when the card has none. */
        final String email;

        /** First phone number, empty when the card has none. */
        final String phone;

        /** Fallback info line (organization, website, ...), maybe empty. */
        final String info;

        /** vCard UID, the automatic link key; empty when absent. */
        final String uid;

        /** Normalized content hash, the divergence marker. */
        final String hash;

        /** True when both sides edited and a captured remote body is on hand,
         *  so the divergence is the user's to settle. */
        final boolean conflicted;

        Indexed(Card card, JSONObject index, boolean conflicted) {
            this.card = card;
            this.name = index.optString("name");
            this.email = index.optString("email");
            this.phone = index.optString("phone");
            this.info = index.optString("info");
            this.uid = index.optString("uid");
            this.hash = index.optString("hash");
            this.conflicted = conflicted;
        }
    }

    /**
     * One address book's live cards with what a row renders, in the store's
     * order.
     *
     * <p>The row is derived from the body rather than read off the stored
     * summary. Annex A fixes what a summary carries and three of the fields a
     * row needs are not in it: the first phone number, the fallback info line
     * and the normalised content hash the merged view compares replicas by.
     * The list loads every body anyway, since a card opens from it, so
     * deriving costs one parse per row and never a second copy of derived data
     * to keep true.
     *
     * <p>Bodiless items are skipped: a spine the sync has enumerated but not yet
     * hydrated has nothing to show, and showing it as a nameless row would read
     * as a broken contact rather than as one still arriving.
     */
    List<Indexed> list(String collection) {
        SQLiteDatabase db = items.readable();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.link_id, i.object_hash, b.conflicted,"
                                + " b.conflict_object, b.handle, b.base_revision"
                                + " FROM items i"
                                + " LEFT JOIN bindings b ON b.collection = i.collection"
                                + " AND b.link_id = i.link_id AND b.source = ?"
                                + " WHERE i.collection = ? AND i.deleted = 0"
                                + " AND i.retained_at IS NULL AND i.object_hash IS NOT NULL"
                                + " ORDER BY i.sort_key",
                        new String[] {SERVER, collection})) {
            List<Indexed> cards = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                Card card =
                        new Card(
                                cursor.getString(0),
                                cursor.isNull(4) ? null : cursor.getString(4),
                                cursor.isNull(5) ? null : cursor.getString(5),
                                items.body(cursor.getString(1)));
                boolean conflicted = cursor.getInt(2) == 1 && !cursor.isNull(3);
                cards.add(new Indexed(card, indexOf(card.vcard), conflicted));
            }
            return cards;
        }
    }

    /**
     * Stages a local create or edit; the next sync pushes it.
     *
     * <p>The binding is deliberately untouched, because it is the base the push
     * diffs against: what the server last confirmed does not change because the
     * user typed something. The one exception is a conflicted card, where saving
     * <em>is</em> the resolution: the remote revision observed at conflict time
     * becomes the new base revision, so the resolving push is conditioned on the
     * state the resolution was actually merged against.
     */
    void save(String collection, Card card) {
        JSONObject index = indexOf(card.vcard);
        SQLiteDatabase db = items.writable();

        db.beginTransaction();
        try {
            items.put(
                    db,
                    collection,
                    new PimdirItems.Row(
                            card.id,
                            card.vcard,
                            index.optJSONObject("summary"),
                            index.optString("sortKey")));
            resolveConflict(db, collection, card.id);
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Stages a local delete; the next sync pushes it. A card the server has
     * never seen is dropped outright instead, since there is nothing to tell it
     * about.
     */
    void stageDelete(String collection, String linkId) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            if (bound(db, collection, linkId)) {
                db.execSQL(
                        "UPDATE items SET deleted = 1 WHERE collection = ? AND link_id = ?",
                        new Object[] {collection, linkId});
            } else {
                items.remove(db, collection, linkId);
                items.collectGarbage(db);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Drops the replica outright (a pushed delete, or a never-pushed create). */
    void remove(String collection, String linkId) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.remove(db, collection, linkId);
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Writes the summaries of contacts that hold none, returning how many were
     * repaired.
     *
     * <p>A summary is written, never derived (SPEC.md §9.3), so a store filled
     * before its kind had a summary table keeps no summary until something
     * rewrites it, and a sync will not: the bodies have not changed, so nothing
     * re-fetches them. Every row would then be invisible to any other reader of
     * the store, and unsorted here. io-pimdir anticipates exactly this with
     * {@code set_sort_key}, "for a store written before its kind had a
     * convention".
     *
     * <p>Cheap when there is nothing to do: it reads the summary rows, not the
     * bodies, and only loads a body for a row it is about to write. A card with
     * no {@code FN} is summarised as nameless and not revisited: what is
     * repaired is the missing row, not the empty name.
     */
    int repairSummaries() {
        SQLiteDatabase db = items.writable();
        List<String[]> stale = new ArrayList<>();

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.collection, i.link_id, i.object_hash FROM items i"
                                + " JOIN collections c ON c.id = i.collection"
                                + " LEFT JOIN contact_summary s ON s.collection = i.collection"
                                + " AND s.link_id = i.link_id"
                                + " WHERE c.kind = ? AND i.object_hash IS NOT NULL"
                                + " AND s.link_id IS NULL",
                        new String[] {PimdirSummary.CONTACT})) {
            while (cursor.moveToNext()) {
                stale.add(
                        new String[] {
                            cursor.getString(0), cursor.getString(1), cursor.getString(2)
                        });
            }
        }
        if (stale.isEmpty()) {
            return 0;
        }

        db.beginTransaction();
        try {
            for (String[] row : stale) {
                String body = items.body(row[2]);
                if (body.isEmpty()) {
                    continue;
                }
                JSONObject index = indexOf(body);
                PimdirSummary.write(db, row[0], row[1], index.optJSONObject("summary"));
                db.execSQL(
                        "UPDATE items SET sort_key = ? WHERE collection = ? AND link_id = ?",
                        new Object[] {index.optString("sortKey"), row[0], row[1]});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        Log.i("pimalaya", "repaired " + stale.size() + " contact summaries");
        return stale.size();
    }

    /**
     * The collections the card is placed in, staged removals excluded and
     * staged additions included: what "which address books is this contact in"
     * means while changes are pending.
     */
    List<String> collectionsOf(String linkId) {
        SQLiteDatabase db = items.readable();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT collection FROM items WHERE link_id = ? AND deleted = 0"
                                + " AND retained_at IS NULL",
                        new String[] {linkId})) {
            List<String> collections = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                collections.add(cursor.getString(0));
            }
            return collections;
        }
    }

    /**
     * Stages a membership change on an account-level backend, where one card
     * belongs to several address books at once.
     *
     * <p>Placing it into another collection is an ordinary item row carrying the
     * same link id, so it shares the {@code seq} and the object the card already
     * has: no body is copied and no second identity is minted. Removing it is
     * the same staged delete as anywhere else, which is why a round trip cancels
     * out rather than pushing twice: adding back a staged removal simply clears
     * the flag, and removing a staged addition drops a row nothing has bound.
     */
    void stageMembership(String from, String target, String linkId, boolean added) {
        if (!added) {
            stageDelete(target, linkId);
            return;
        }

        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            if (items.knows(db, target, linkId)) {
                db.execSQL(
                        "UPDATE items SET deleted = 0, retained_at = NULL, retained_by = NULL"
                                + " WHERE collection = ? AND link_id = ?",
                        new Object[] {target, linkId});
            } else {
                copy(db, from, target, linkId);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Moves an account's cards into the on-device book, keeping the contacts and
     * losing every sync marker: the account is going, its contacts are not.
     *
     * <p>A card placed in several of the account's books collapses to one local
     * row, since the books it was in are about to stop existing. Bindings are
     * left behind rather than carried, which is the point: the local book has no
     * server, so a carried base would claim an agreement with a remote that no
     * longer syncs it. A staged delete is not carried either, since the user
     * already asked for that card to go.
     */
    void detachToLocal(List<String> collections, String local) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            for (String collection : collections) {
                for (String linkId : items.linkIdsOf(db, collection)) {
                    if (live(db, collection, linkId) && !items.knows(db, local, linkId)) {
                        copy(db, collection, local, linkId);
                    }
                }
                db.execSQL("DELETE FROM collections WHERE id = ?", new Object[] {collection});
            }
            items.collectGarbage(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Places an existing item into another collection, body and summary alike.
     *
     * <p>The summary is derived again from the body rather than copied row by
     * row: it is one parse against five typed tables to read back, and a
     * derivation cannot copy a row wrong.
     */
    private void copy(SQLiteDatabase db, String from, String target, String linkId) {
        String object;
        String flags;
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT object_hash, flags FROM items"
                                + " WHERE collection = ? AND link_id = ?",
                        new String[] {from, linkId})) {
            if (!cursor.moveToFirst()) {
                return;
            }
            object = cursor.isNull(0) ? null : cursor.getString(0);
            flags = cursor.isNull(1) ? "[]" : cursor.getString(1);
        }

        JSONObject index = indexOf(items.body(object));
        db.execSQL(
                "INSERT INTO items(collection, link_id, seq, flags, object_hash,"
                        + " sort_key, level) VALUES(?, ?, ?, ?, ?, ?, ?)",
                new Object[] {
                    target, linkId, items.seqFor(db, linkId), flags, object,
                    index.optString("sortKey"), PimdirItems.FULL
                });
        PimdirSummary.write(db, target, linkId, index.optJSONObject("summary"));
        items.adjustRefcount(db, null, object);
    }

    /**
     * Turns a conflicted card's diverging state into the base its resolution
     * pushes against, and releases the remote body the form no longer needs.
     *
     * <p>The whole remote state becomes the base, revision <em>and</em> body,
     * not the revision alone: they were observed together, and a base adopting
     * one while keeping the other contradicts itself. A resolution that keeps
     * the remote body would then read as a pending push against a base holding
     * the body it discarded, and one that adopts it would read as clean while
     * the server still holds something else.
     */
    private void resolveConflict(SQLiteDatabase db, String collection, String linkId) {
        String revision;
        String diverging;
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT conflict_revision, conflict_object FROM bindings"
                                + " WHERE collection = ? AND link_id = ? AND source = ?"
                                + " AND conflicted = 1",
                        new String[] {collection, linkId, SERVER})) {
            if (!cursor.moveToFirst()) {
                return;
            }
            revision = cursor.isNull(0) ? null : cursor.getString(0);
            diverging = cursor.isNull(1) ? null : cursor.getString(1);
        }

        String base = baseObjectOf(db, collection, linkId);
        db.execSQL(
                "UPDATE bindings SET conflicted = 0, conflict_revision = NULL,"
                        + " conflict_object = NULL,"
                        + " base_revision = COALESCE(?, base_revision),"
                        + " base_object = COALESCE(?, base_object)"
                        + " WHERE collection = ? AND link_id = ? AND source = ?",
                new Object[] {revision, diverging, collection, linkId, SERVER});

        if (diverging != null) {
            // NOTE: the diverging body moves from the conflict's pin to the
            // base's, so it stays exactly as referenced as it was; what the
            // move releases is the body it displaced.
            items.adjustRefcount(db, base, null);
        }
    }

    /** The body this source's binding last agreed on, or null. */
    private static String baseObjectOf(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT base_object FROM bindings WHERE collection = ?"
                                + " AND link_id = ? AND source = ?",
                        new String[] {collection, linkId, SERVER})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** Whether the server has ever confirmed this placement. */
    private static boolean bound(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM bindings WHERE collection = ? AND link_id = ?"
                                + " AND source = ?",
                        new String[] {collection, linkId, SERVER})) {
            return cursor.moveToFirst();
        }
    }

    /** Whether the placement is neither staged for deletion nor retained. */
    private static boolean live(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM items WHERE collection = ? AND link_id = ?"
                                + " AND deleted = 0 AND retained_at IS NULL"
                                + " AND object_hash IS NOT NULL",
                        new String[] {collection, linkId})) {
            return cursor.moveToFirst();
        }
    }

    /** The card index, empty on a parse failure rather than a lost save. */
    private static JSONObject indexOf(String vcard) {
        try {
            return Cards.indexCard(vcard);
        } catch (Exception error) {
            Log.w("pimalaya", "card index failed", error);
            return new JSONObject();
        }
    }

}
