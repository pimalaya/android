package org.pimalaya.contact;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardIndex;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ConflictForm;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirIndexDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * The normalized content hashes computed so far, by the store's hash of
     * the body: a body is content-addressed, so an entry never goes stale,
     * and a reload parses only the bodies it never compared.
     */
    private static final Map<String, String> HASHES =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                            return size() > 10_000;
                        }
                    });

    /**
     * One displayable replica: what its row shows, read from the store's
     * summaries (STORAGE Annex A.2, A.6), its body read only when asked.
     */
    public static final class Indexed {
        /** Its link id. */
        public final String id;

        /** How its server addresses it, null until a sync binds it. */
        final String uri;

        /** The server's validator, null when unknown. */
        final String etag;

        /** The store's hash of its body. */
        final String object;

        /** The store account holding it, as a search hit names it. */
        final String account;

        /** Its seq, as a search hit names it. */
        final long seq;

        /**
         * Display name: its {@code FN}, else its first email, else its
         * organization; empty when the card has none.
         */
        public final String name;

        /** First email address, canonical (Annex A.6), empty when none. */
        final String email;

        /** Its organization, the row's fallback line, maybe empty. */
        final String info;

        /** vCard UID, the automatic link key; empty when absent. */
        final String uid;

        /** Its {@code KIND} lowercased, {@code individual} when unstated. */
        final String kind;

        /**
         * Both sides edited and a remote body is captured: the user settles.
         */
        final boolean conflicted;

        /** Reads its body, the first time it is asked for. */
        private final java.util.function.Supplier<String> body;

        private Card card;

        /** Its normalized content hash, once known. */
        private String hash;

        Indexed(
                java.util.function.Supplier<String> body,
                String id,
                String uri,
                String etag,
                String object,
                String account,
                long seq,
                String fn,
                String email,
                String org,
                String uid,
                String kind,
                boolean conflicted) {
            this.body = body;
            this.id = id;
            this.uri = uri;
            this.etag = etag;
            this.object = object;
            this.account = account;
            this.seq = seq;
            this.email = email;
            this.info = org;
            this.name = !fn.trim().isEmpty() ? fn.trim() : !email.isEmpty() ? email : org;
            this.uid = uid;
            this.kind = kind.isEmpty() ? "individual" : kind;
            this.conflicted = conflicted;
        }

        /**
         * A replica held in memory rather than read from the store: its row
         * the card's own index, its body the card's text.
         */
        Indexed(Card card, CardIndex index, boolean conflicted) {
            this(
                    card::vcard,
                    card.id(),
                    card.uri(),
                    card.etag(),
                    index.hash(),
                    "",
                    0,
                    index.name(),
                    index.email(),
                    index.info(),
                    index.uid(),
                    "",
                    conflicted);
            this.card = card;
            this.hash = index.hash();
        }

        /**
         * The card, its body read off the blob directory on first use: what
         * opening, merging or exporting it reads, never its row.
         */
        public synchronized Card card() {
            if (card == null) {
                card = new Card(id, uri, etag, body.get());
            }
            return card;
        }

        /**
         * The normalized content hash replicas compare by, parsed from the
         * body the first time a body is asked for it.
         */
        synchronized String hash() {
            if (hash == null) {
                hash = HASHES.get(object);
            }
            if (hash == null) {
                hash = indexOf(card().vcard()).hash();
                HASHES.put(object, hash);
            }
            return hash;
        }
    }

    /**
     * One address book's live cards with what a row renders, in store order.
     *
     * <p>Rows are the store's summaries (STORAGE Annex A.2), the first email
     * its first {@code email} address (A.6): no body is read. Bodiless items
     * (enumerated, not yet hydrated) are skipped rather than shown as a
     * broken nameless row.
     */
    public List<Indexed> list(String collection) {
        SQLiteDatabase db = items.readable();
        try (Cursor cursor =
                PimdirDb.query(
                        db,
                        "LIST_CARDS",
                        PimdirSql.values("source", SERVER, "collection", collection))) {
            List<Indexed> cards = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                // NOTE: the server binding's own conflict, or the item's two
                // sources left (SYNC §9), which the server's binding offers.
                boolean conflicted =
                        (cursor.getInt(2) == 1 && !cursor.isNull(3))
                                || (!cursor.isNull(4) && cursor.getInt(6) == 1
                                        && !cursor.isNull(7));
                String object = cursor.getString(1);
                cards.add(
                        new Indexed(
                                () -> items.body(object),
                                cursor.getString(0),
                                cursor.isNull(4) ? null : cursor.getString(4),
                                cursor.isNull(5) ? null : cursor.getString(5),
                                object,
                                text(cursor, 9),
                                cursor.getLong(8),
                                text(cursor, 11),
                                text(cursor, 14),
                                text(cursor, 13),
                                text(cursor, 10),
                                text(cursor, 12),
                                conflicted));
            }
            return cards;
        }
    }

    /** A column's text, empty for a NULL. */
    private static String text(Cursor cursor, int column) {
        return cursor.isNull(column) ? "" : cursor.getString(column);
    }

    /**
     * What the search index records of every card (pimdir SEARCH §6), by
     * collection and seq; null with no index, or one refused.
     */
    public Map<String, CardFacts.Indexed> facts() {
        PimdirIndexDb index = store.index();
        if (index == null) {
            return null;
        }
        try {
            return CardFacts.of(index.cardFacts());
        } catch (RuntimeException failed) {
            Log.w("pimalaya", "search index unanswered", failed);
            return null;
        }
    }

    /**
     * The cards a query finds in the search index (pimdir SEARCH §8), by
     * account and seq; null with no index, one refused, or a query the
     * language refuses.
     */
    public java.util.Set<String> found(String words) {
        PimdirIndexDb index = store.index();
        if (index == null || words.trim().isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject answer = index.hits("kind:contact (" + words.trim() + ")", "name");
            org.json.JSONArray hits = answer == null ? null : answer.optJSONArray("hits");
            if (hits == null) {
                return null;
            }
            java.util.Set<String> found = new java.util.HashSet<>();
            for (int position = 0; position < hits.length(); position++) {
                org.json.JSONObject hit = hits.getJSONObject(position);
                found.add(hit.optString("account") + "\n" + hit.getLong("seq"));
            }
            return found;
        } catch (JSONException | RuntimeException failed) {
            Log.w("pimalaya", "search index unanswered", failed);
            return null;
        }
    }

    /**
     * Stages a local create or edit as a pimdir mutation; the next sync pushes.
     *
     * <p>An edit leaves the base alone. An ordinary edit, it is refused over a
     * conflict, staging nothing (SYNC §7): a conflicted card is settled by its
     * conflict form ({@link ContactStager#resolve}), whose base adopts the
     * remote state observed at conflict time.
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

    /**
     * Stages the card's removal from a book it stays in elsewhere.
     *
     * <p>A move: when another book of the account only stages it, that add
     * supersedes this placement, in the transaction staging the tombstone
     * (pimdir STORAGE §15.3, as a task filed elsewhere is). The store then
     * holds the removal until the add is pushed, which takes the card out of
     * this book, and holds it as long as the add waits.
     */
    private void stageLeave(String collection, String linkId) {
        if (!held(collection, linkId)) {
            return;
        }
        try {
            JSONArray pending = stager.offline.cardElsewhere(collection, linkId)
                    .getJSONArray("pending");
            if (pending.length() == 0) {
                stageDelete(collection, linkId);
                return;
            }
            // NOTE: one add supersedes an item (the pair is keyed by it), the
            // first by collection id: once it lands the removal is a plain
            // patch, the other books still staging the card pushing their own.
            String target = pending.getString(0);
            stager.offline.atomically(
                    () -> {
                        stager.offline.supersede(collection, linkId, target, linkId);
                        stager.mutateRemove(
                                collection, stager.offline.handleFor(collection, linkId));
                    });
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * Whether the placement holds the only copy of an edit its server has not
     * got: what taking the card out of that book would leave in Deleted items
     * alone, which the editor asks about first.
     */
    public boolean unsent(String collection, String linkId) {
        return unsent(items.readable(), collection, linkId);
    }

    private static boolean unsent(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                PimdirDb.query(
                        db,
                        "CARD_UNSENT",
                        PimdirSql.values(
                                "collection", collection, "link_id", linkId, "source", SERVER))) {
            return cursor.moveToFirst();
        }
    }

    /**
     * A book the card stays in where keeping an edit meets one of its own,
     * with the merge the person resolves: nothing was written.
     *
     * @param collection the book the card stays in
     * @param form the three-way merge, its collisions as alternatives
     */
    public record KeepCollision(String collection, ConflictForm form) {}

    /**
     * Merges the placement's edit into every other book of its account that
     * keeps the card, before the editor takes it out of this one; null when
     * each merged cleanly and was staged, else the first collision, nothing
     * written then.
     *
     * <p>Three ways from the base the placement's own binding agreed on with
     * the server: what the server or the phone changed in a staying book
     * since stays, the edit's own changes come in, and a property both set
     * apart is the person's to choose. A placement never sent has no base,
     * so nothing is merged unseen: the form opens on the staying book, its
     * own body first and the edit's values beside. The edit then goes out
     * as an ordinary guarded update.
     */
    public KeepCollision keepEdit(String collection, String linkId) {
        try {
            JSONObject row = stager.offline.loadRow(
                    collection, stager.offline.handleFor(collection, linkId));
            if (row == null) {
                return null;
            }
            String body = row.getString("vcard");
            String base = row.optString("baseVcard", null);
            JSONArray staying =
                    stager.offline.cardElsewhere(collection, linkId).getJSONArray("staying");

            Map<String, String> merged = new java.util.LinkedHashMap<>();
            for (int index = 0; index < staying.length(); index++) {
                String book = staying.getString(index);
                JSONObject now =
                        stager.offline.loadRow(book, stager.offline.handleFor(book, linkId));
                if (now == null) {
                    continue;
                }
                String held = now.getString("vcard");
                if (base == null) {
                    return new KeepCollision(
                            book, ContactClient.mergeConflictForm("", held, body));
                }
                ConflictForm form = ContactClient.mergeConflictForm(base, body, held);
                if (!form.resolved()) {
                    return new KeepCollision(book, form);
                }
                if (!form.vcard().equals(held)) {
                    merged.put(book, form.vcard());
                }
            }

            for (Map.Entry<String, String> book : merged.entrySet()) {
                save(book.getKey(), new Card(linkId, null, null, book.getValue()));
            }
            return null;
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Restages a tick copy that follows a pull of its card. */
    void follow(String collection, String linkId, String body) {
        try {
            stager.edit(collection, stager.offline.handleFor(collection, linkId), body);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Whether the collection holds the card, staged removals included. */
    private boolean held(String collection, String linkId) {
        try (Cursor cursor =
                PimdirDb.query(
                        items.readable(),
                        "ITEM_UNRETAINED",
                        PimdirSql.values("collection", collection, "link_id", linkId))) {
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
                PimdirDb.query(
                        db,
                        "ITEMS_WITHOUT_SUMMARY",
                        PimdirSql.values("kind", PimdirSummary.CONTACT))) {
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
                PimdirDb.exec(
                        db,
                        "SET_SORT_KEY",
                        PimdirSql.values(
                                "sort_key", index.sortKey(),
                                "collection", row[0],
                                "link_id", row[1]));
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
                PimdirDb.query(db, "LIVE_COLLECTIONS_OF", PimdirSql.values("link_id", linkId))) {
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
     * trip cancels out rather than pushing twice; a book the card leaves for
     * one only staging it is superseded by that add ({@link #stageLeave}).
     */
    void stageMembership(String from, String target, String linkId, boolean added) {
        if (!added) {
            stageLeave(target, linkId);
            return;
        }

        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            if (items.knows(db, target, linkId)) {
                PimdirDb.exec(
                        db,
                        "REVIVE_ITEM",
                        PimdirSql.values("collection", target, "link_id", linkId));
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
     * <p>Several placements collapse to one local row: the one holding the
     * only copy of an edit never sent first ({@link #unsent}), else the first
     * by collection. Bindings stay behind (no server, so no agreement to
     * claim) and staged deletes are not carried.
     */
    public void detachToLocal(List<String> collections, String local) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            for (boolean first : new boolean[] {true, false}) {
                for (String collection : collections) {
                    for (String linkId : items.linkIdsOf(db, collection)) {
                        if (live(db, collection, linkId)
                                && !items.knows(db, local, linkId)
                                && (!first || unsent(db, collection, linkId))) {
                            copy(db, collection, local, linkId);
                        }
                    }
                }
            }
            for (String collection : collections) {
                PimdirDb.exec(
                        db, "DELETE_COLLECTION", PimdirSql.values("collection", collection));
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
        List<String> flags;
        try (Cursor cursor =
                PimdirDb.query(
                        db,
                        "ITEM_OBJECT_FLAGS",
                        PimdirSql.values("collection", from, "link_id", linkId))) {
            if (!cursor.moveToFirst()) {
                return;
            }
            object = cursor.isNull(0) ? null : cursor.getString(0);
            flags = cursor.isNull(1) ? List.of() : PimdirItems.flagsFrom(cursor.getString(1));
        }

        CardIndex index = indexOf(items.body(object));
        Map<String, Object> row = PimdirSql.values("collection", target, "link_id", linkId);
        row.put("seq", PimdirItems.seqFor(db, target, linkId));
        row.put("flags_known", 1);
        row.put("object_hash", object);
        row.put("sort_key", index.sortKey());
        row.put("level", PimdirItems.FULL);
        row.put("deleted", 0);
        row.put("conflicted", 0);
        // NOTE: no system flag yet: writeFlags sets the bits with the rows.
        row.put("marks", 0);
        PimdirDb.exec(db, "INSERT_ITEM", row);
        PimdirItems.writeFlags(db, target, linkId, flags);
        PimdirSummary.write(db, target, linkId, index.summary());
        items.adjustRefcount(db, null, object);
    }

    /** Whether the placement is neither staged for deletion nor retained. */
    private static boolean live(SQLiteDatabase db, String collection, String linkId) {
        try (Cursor cursor =
                PimdirDb.query(
                        db,
                        "ITEM_LIVE_HELD",
                        PimdirSql.values("collection", collection, "link_id", linkId))) {
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
