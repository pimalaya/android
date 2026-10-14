package org.pimalaya.contact;

import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONException;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardIndex;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Flags;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * The contacts store, on the pimdir tables.
 *
 * <p>A replica is an item keyed (collection, link_id); membership is placement
 * (rows sharing a link_id); dirty is derived (object_hash past the binding's
 * base). Every method stages (SYNC §7): {@link ContactEngine} pushes.
 */
public final class PimdirContacts {
    /** The source name a server-synced binding carries. */
    private static final String SERVER = PimdirStorage.SERVER;

    private final PimdirDb store;
    private final PimdirItems items;

    /**
     * A contacts driver that only stages: mutations, never row edits (SYNC §7).
     */
    private final ContactStager stager;

    /** The contacts of a store. */
    public PimdirContacts(PimdirDb store) {
        this.store = store;
        this.items = new PimdirItems(store);
        this.stager = new ContactStager(store, Clients.real());
    }

    /** One displayable replica: the card plus its summary. */
    public static final class Indexed {
        /** The card. */
        public final Card card;

        /** Display name (FN), empty when the card has none. */
        public final String name;

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

        /**
         * Both sides edited and a remote body is captured: the user settles.
         */
        final boolean conflicted;

        Indexed(Card card, CardIndex index, boolean conflicted) {
            this.card = card;
            this.name = index.name();
            this.email = index.email();
            this.phone = index.phone();
            this.info = index.info();
            this.uid = index.uid();
            this.hash = index.hash();
            this.conflicted = conflicted;
        }
    }

    /**
     * One address book's live cards with what a row renders, in store order.
     *
     * <p>Rows derive from the body: Annex A's summary lacks the first phone,
     * the info line and the content hash. Bodiless items (enumerated, not yet
     * hydrated) are skipped rather than shown as a broken nameless row.
     */
    public List<Indexed> list(String collection) {
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
                cards.add(new Indexed(card, indexOf(card.vcard()), conflicted));
            }
            return cards;
        }
    }

    /**
     * Stages a local create or edit as a pimdir mutation; the next sync pushes.
     *
     * <p>An edit leaves the base alone. On a conflicted card saving is the
     * resolution: the base adopts the remote state observed at conflict time,
     * so the push is conditioned on what it was merged against.
     */
    public void save(String collection, Card card) {
        CardIndex index = indexOf(card.vcard());
        try {
            if (held(collection, card.id())) {
                stager.mutateEdit(
                        collection,
                        stager.offline.handleFor(collection, card.id()),
                        card.vcard(),
                        index.summary(),
                        index.sortKey());
            } else {
                stager.mutateAdd(
                        collection,
                        card.id(),
                        card.vcard(),
                        Flags.none(),
                        index.summary(),
                        index.sortKey());
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * Stages a local delete as a pimdir mutation; the next sync pushes it.
     *
     * <p>A create never pushed is withdrawn by the engine instead.
     */
    public void stageDelete(String collection, String linkId) {
        if (!held(collection, linkId)) {
            return;
        }
        try {
            stager.mutateRemove(collection, stager.offline.handleFor(collection, linkId));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Whether the collection holds the card, staged removals included. */
    private boolean held(String collection, String linkId) {
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT 1 FROM items WHERE collection = ? AND link_id = ?"
                                        + " AND retained_at IS NULL",
                                new String[] {collection, linkId})) {
            return cursor.moveToFirst();
        }
    }

    /**
     * Drops the replica outright (a pushed delete, or a never-pushed create).
     */
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
     * Writes the summaries of contacts holding none; answers how many.
     *
     * <p>A summary is written, never derived (SPEC.md §9.3): a store filled
     * before its kind had one keeps none, and a sync re-fetches nothing. Reads
     * summary rows, loads only bodies it writes for (io-pimdir set_sort_key).
     */
    public int repairSummaries() {
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
                CardIndex index = indexOf(body);
                PimdirSummary.write(db, row[0], row[1], index.summary());
                db.execSQL(
                        "UPDATE items SET sort_key = ? WHERE collection = ? AND link_id = ?",
                        new Object[] {index.sortKey(), row[0], row[1]});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        Log.i("pimalaya", "repaired " + stale.size() + " contact summaries");
        return stale.size();
    }

    /** The card's collections, staged removals out and staged additions in. */
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
     * Stages a membership change on an account-level backend.
     *
     * <p>Placing is an item row sharing the link id, so its seq and object: no
     * copy, no new identity. Removing is the usual staged delete, so a round
     * trip cancels out rather than pushing twice.
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
        // NOTE: written past the engine, whose staging queues the phone
        // pass of every other write.
        PhoneQueue.written(store.context(), target);
    }

    /**
     * Moves an account's cards into the on-device book, dropping sync markers.
     *
     * <p>Several placements collapse to one local row. Bindings stay behind (no
     * server, so no agreement to claim) and staged deletes are not carried.
     */
    public void detachToLocal(List<String> collections, String local) {
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
        PhoneQueue.written(store.context(), local);
    }

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

        CardIndex index = indexOf(items.body(object));
        db.execSQL(
                "INSERT INTO items(collection, link_id, seq, flags, object_hash,"
                        + " sort_key, level) VALUES(?, ?, ?, ?, ?, ?, ?)",
                new Object[] {
                    target, linkId, items.seqFor(db, linkId), flags, object,
                    index.sortKey(), PimdirItems.FULL
                });
        PimdirSummary.write(db, target, linkId, index.summary());
        items.adjustRefcount(db, null, object);
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
    private static CardIndex indexOf(String vcard) {
        try {
            return ContactClient.indexCard(vcard);
        } catch (Exception error) {
            Log.w("pimalaya", "card index failed", error);
            return new CardIndex("", List.of(), "", "", "", "", "", null, "");
        }
    }

}
