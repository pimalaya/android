package org.pimalaya;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONException;
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
 *   <li>the write-time index becomes {@code meta} ({@link PimdirMeta}) and the
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

        Indexed(Card card, JSONObject meta, boolean conflicted) {
            this.card = card;
            this.name = meta.optString("fn");
            this.email = meta.optJSONArray("emails") == null
                    ? ""
                    : meta.optJSONArray("emails").optString(0);
            this.phone = meta.optString("phone");
            this.info = meta.optString("info");
            this.uid = meta.optString("uid");
            this.hash = meta.optString("hash");
            this.conflicted = conflicted;
        }
    }

    /**
     * One address book's live cards with their summaries, so the contacts list
     * renders without parsing a single vCard.
     *
     * <p>Bodiless items are skipped: a spine the sync has enumerated but not yet
     * hydrated has nothing to show, and showing it as a nameless row would read
     * as a broken contact rather than as one still arriving.
     */
    List<Indexed> list(String collection) {
        SQLiteDatabase db = items.readable();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.link_id, i.object_hash, i.meta, i.conflicted,"
                                + " i.conflict_object, b.handle, b.base_revision"
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
                                cursor.isNull(5) ? null : cursor.getString(5),
                                cursor.isNull(6) ? null : cursor.getString(6),
                                items.body(cursor.getString(1)));
                boolean conflicted = cursor.getInt(3) == 1 && !cursor.isNull(4);
                cards.add(new Indexed(card, metaOf(cursor.getString(2)), conflicted));
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
                            PimdirMeta.contact(index, card.vcard.length()),
                            PimdirMeta.contactSortKey(index.optString("name"))));
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
     * Recomputes the summaries of contacts whose stored one predates the
     * current convention, returning how many were repaired.
     *
     * <p>A summary is written, never derived (SPEC.md §9.3), so a store filled
     * by a writer that spelled it differently keeps that spelling until
     * something rewrites it, and a sync will not: the bodies have not changed,
     * so nothing re-fetches them. Every row would then render nameless and
     * unsorted forever. io-pimdir anticipates exactly this with
     * {@code set_sort_key}, "for a store written before its kind had a
     * convention".
     *
     * <p>Cheap when there is nothing to do: it reads the summaries, not the
     * bodies, and only loads a body for a row it is about to rewrite.
     */
    int repairSummaries() {
        SQLiteDatabase db = items.writable();
        List<String[]> stale = new ArrayList<>();

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.collection, i.link_id, i.object_hash, i.meta FROM items i"
                                + " JOIN collections c ON c.id = i.collection"
                                + " WHERE c.kind = ? AND i.object_hash IS NOT NULL",
                        new String[] {PimdirMeta.CONTACT})) {
            while (cursor.moveToNext()) {
                if (metaOf(cursor.getString(3)).optString("fn").isEmpty()) {
                    stale.add(
                            new String[] {
                                cursor.getString(0), cursor.getString(1), cursor.getString(2)
                            });
                }
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
                db.execSQL(
                        "UPDATE items SET meta = ?, sort_key = ?"
                                + " WHERE collection = ? AND link_id = ?",
                        new Object[] {
                            PimdirMeta.contact(index, body.length()),
                            PimdirMeta.contactSortKey(index.optString("name")),
                            row[0],
                            row[1]
                        });
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

    /** Places an existing item into another collection, body and summary alike. */
    private void copy(SQLiteDatabase db, String from, String target, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT object_hash, meta, sort_key, flags FROM items"
                                + " WHERE collection = ? AND link_id = ?",
                        new String[] {from, linkId})) {
            if (!cursor.moveToFirst()) {
                return;
            }
            String object = cursor.isNull(0) ? null : cursor.getString(0);
            db.execSQL(
                    "INSERT INTO items(collection, link_id, seq, flags, object_hash, meta,"
                            + " sort_key, level) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {
                        target, linkId, items.seqFor(db, linkId),
                        cursor.isNull(3) ? "[]" : cursor.getString(3), object,
                        cursor.isNull(1) ? null : cursor.getString(1),
                        cursor.isNull(2) ? "" : cursor.getString(2), PimdirItems.FULL
                    });
            items.adjustRefcount(db, null, object);
        }
    }

    /**
     * Turns a conflicted card's captured state into the base its resolution
     * pushes against, and releases the remote body the form no longer needs.
     */
    private void resolveConflict(SQLiteDatabase db, String collection, String linkId) {
        String revision;
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT conflict_revision FROM bindings WHERE collection = ?"
                                + " AND link_id = ? AND source = ? AND conflicted = 1",
                        new String[] {collection, linkId, SERVER})) {
            if (!cursor.moveToFirst()) {
                return;
            }
            revision = cursor.isNull(0) ? null : cursor.getString(0);
        }

        db.execSQL(
                "UPDATE bindings SET conflicted = 0, conflict_revision = NULL,"
                        + " base_revision = COALESCE(?, base_revision)"
                        + " WHERE collection = ? AND link_id = ? AND source = ?",
                new Object[] {revision, collection, linkId, SERVER});

        String captured = null;
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT conflict_object FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                captured = cursor.getString(0);
            }
        }
        db.execSQL(
                "UPDATE items SET conflicted = 0, conflict_object = NULL"
                        + " WHERE collection = ? AND link_id = ?",
                new Object[] {collection, linkId});
        items.adjustRefcount(db, captured, null);
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

    /** A stored summary, empty when the item has none yet. */
    private static JSONObject metaOf(String meta) {
        if (meta == null || meta.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(meta);
        } catch (JSONException error) {
            Log.w("pimalaya", "unreadable contact meta", error);
            return new JSONObject();
        }
    }
}
