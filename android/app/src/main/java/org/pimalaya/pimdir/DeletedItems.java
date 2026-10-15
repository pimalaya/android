package org.pimalaya.pimdir;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.pimalaya.client.Derived;
import org.pimalaya.client.Flags;
import org.pimalaya.client.PimdirSql;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The store's deleted items (pimdir STORAGE §11): listing, restore and purge.
 *
 * <p>io-pimdir's {@code client} feature is off, so its retention calls run
 * here through their canonical statements ({@link PimdirSql}). A row still
 * bound by a source (no {@code retained_at}) is neither restored nor purged.
 */
public final class DeletedItems {
    /** Rows one keyset read takes of a collection. */
    static final int PAGE = 256;

    /** One deleted item, as the page lists it. */
    public static final class Row {
        /** The collection it was deleted from. */
        public final PimdirCollections.Stored collection;
        /** Its media type. */
        public final String kind;
        final long seq;
        /** Its identity. */
        public final String linkId;
        final String flags;
        final String object;
        final String sortKey;
        final boolean full;

        /** When the store retained it (epoch millis), 0 while bound. */
        public final long retainedAt;

        private final boolean waiting;

        /** What its body weighs, 0 for none. */
        final long size;

        /** The summary's headline: subject, name or title, empty for none. */
        public String title = "";

        /** The summary's second fact: sender, organization, empty for none. */
        public String who = "";

        /** The summary's date (mail RFC 3339, event start), or empty. */
        public String when = "";

        Row(
                PimdirCollections.Stored collection,
                String kind,
                long seq,
                String linkId,
                String flags,
                String object,
                String sortKey,
                boolean full,
                String retainedAt,
                long size) {
            this.collection = collection;
            this.kind = kind;
            this.seq = seq;
            this.linkId = linkId;
            this.flags = flags;
            this.object = object;
            this.sortKey = sortKey;
            this.full = full;
            this.retainedAt = instantOf(retainedAt);
            this.waiting = retainedAt == null;
            this.size = size;
        }

        /** Whether a source still binds it, its delete not pushed yet. */
        public boolean waiting() {
            return waiting;
        }

        /** Whether the store holds its body, which a restore stages. */
        public boolean stored() {
            return full && object != null;
        }
    }

    /** Newest deletion first, the rows still waiting ahead of them. */
    static final Comparator<Row> NEWEST_FIRST =
            Comparator.comparing((Row row) -> !row.waiting)
                    .thenComparing(row -> row.retainedAt, Comparator.reverseOrder())
                    .thenComparing(row -> row.seq, Comparator.reverseOrder());

    private final PimdirDb store;
    private final Context context;
    private final PimdirItems items;

    /** Reads and purges the deleted items of {@code store}. */
    public DeletedItems(PimdirDb store, Context context) {
        this.store = store;
        this.context = context;
        this.items = new PimdirItems(store);
    }

    /**
     * Every deleted item of every collection, newest deletion first.
     *
     * <p>io-pimdir pages each collection's trash in {@code seq} order, so the
     * whole is sorted at the end: the index does not serve deletion time.
     */
    public List<Row> list() {
        SQLiteDatabase db = store.getReadableDatabase();
        PimdirCollections collections = new PimdirCollections(store, context);
        List<Row> rows = new ArrayList<>();
        for (String kind :
                new String[] {PimdirSummary.MAIL, PimdirSummary.CONTACT, PimdirSummary.CALENDAR}) {
            for (PimdirCollections.Stored collection : collections.list(kind)) {
                long after = 0;
                while (true) {
                    List<Row> page = page(db, collection, kind, after);
                    summarise(db, collection.id, kind, page);
                    rows.addAll(page);
                    if (page.size() < PAGE) {
                        break;
                    }
                    after = page.get(page.size() - 1).seq;
                }
            }
        }
        rows.sort(NEWEST_FIRST);
        return rows;
    }

    /** pimdir's {@code list_retained}: one keyset page of a trash. */
    private static List<Row> page(
            SQLiteDatabase db, PimdirCollections.Stored collection, String kind, long after) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection.id);
        values.put("after", after);
        values.put("limit", PAGE);
        List<Row> page = new ArrayList<>();
        try (Cursor cursor = query(db, "LIST_RETAINED_PAGE", values)) {
            int seq = cursor.getColumnIndexOrThrow("seq");
            int link = cursor.getColumnIndexOrThrow("link_id");
            int flags = cursor.getColumnIndexOrThrow("flags");
            int object = cursor.getColumnIndexOrThrow("object_hash");
            int sortKey = cursor.getColumnIndexOrThrow("sort_key");
            int level = cursor.getColumnIndexOrThrow("level");
            int retained = cursor.getColumnIndexOrThrow("retained_at");
            int size = cursor.getColumnIndexOrThrow("size");
            while (cursor.moveToNext()) {
                page.add(
                        new Row(
                                collection,
                                kind,
                                cursor.getLong(seq),
                                cursor.getString(link),
                                cursor.isNull(flags) ? null : cursor.getString(flags),
                                cursor.isNull(object) ? null : cursor.getString(object),
                                cursor.isNull(sortKey) ? "" : cursor.getString(sortKey),
                                cursor.getInt(level) == PimdirItems.FULL,
                                cursor.isNull(retained) ? null : cursor.getString(retained),
                                cursor.isNull(size) ? 0 : cursor.getLong(size)));
            }
        }
        return page;
    }

    /** Attaches each row's summary, read by its kind's canonical statement. */
    private static void summarise(
            SQLiteDatabase db, String collection, String kind, List<Row> page) {
        if (page.isEmpty()) {
            return;
        }
        Map<String, Row> byLink = new HashMap<>();
        JSONArray links = new JSONArray();
        for (Row row : page) {
            byLink.put(row.linkId, row);
            links.put(row.linkId);
        }
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("links", links.toString());

        String[] statements;
        switch (kind) {
            case PimdirSummary.MAIL:
                statements = new String[] {"LOAD_MAIL_SUMMARIES"};
                break;
            case PimdirSummary.CONTACT:
                statements = new String[] {"LOAD_CONTACT_SUMMARIES"};
                break;
            default:
                statements =
                        new String[] {
                            "LOAD_EVENT_SUMMARIES", "LOAD_TASK_SUMMARIES", "LOAD_JOURNAL_SUMMARIES"
                        };
        }
        for (String statement : statements) {
            try (Cursor cursor = query(db, statement, values)) {
                while (cursor.moveToNext()) {
                    Row row = byLink.get(text(cursor, "link_id"));
                    if (row == null) {
                        continue;
                    }
                    if (PimdirSummary.MAIL.equals(kind)) {
                        row.title = text(cursor, "subject");
                        String name = text(cursor, "sender_name");
                        row.who = name.isEmpty() ? text(cursor, "sender") : name;
                        row.when = text(cursor, "date");
                    } else if (PimdirSummary.CONTACT.equals(kind)) {
                        row.title = text(cursor, "fn");
                        row.who = text(cursor, "org");
                    } else {
                        row.title = text(cursor, "summary");
                        row.when = text(cursor, "dtstart");
                    }
                }
            }
        }
    }

    /** pimdir's {@code retained_bytes}: retention store-wide, bodies once. */
    public long retainedBytes() {
        try (Cursor cursor =
                query(store.getReadableDatabase(), "RETAINED_BYTES", new HashMap<>())) {
            return cursor.moveToFirst() ? Math.max(0, cursor.getLong(0)) : 0;
        }
    }

    /** Why a row cannot be restored. */
    public enum Refusal {
        /** A source still binds it. */
        WAITING,
        /** The store holds its summary alone. */
        NOT_STORED,
        /** The target holds the item already. */
        PRESENT
    }

    /** Refused restore. */
    public static final class RefusedException extends Exception {
        /** Why the restore was refused. */
        public final Refusal refusal;

        RefusedException(Refusal refusal) {
            super(refusal.name());
            this.refusal = refusal;
        }
    }

    /**
     * Stages the row's body into {@code target} as a local creation.
     *
     * <p>Into its last collection, the store revives the row and its public id
     * (STORAGE §11.1). Elsewhere: a contact or event keeps its UID, a mail is
     * named from its body (its handle names nothing in another mailbox).
     */
    public void restore(Stager engine, Row row, String target)
            throws RefusedException, JSONException {
        if (row.waiting()) {
            throw new RefusedException(Refusal.WAITING);
        }
        byte[] body = row.stored() ? items.objectBytes(row.collection.id, row.linkId) : null;
        if (body == null) {
            throw new RefusedException(Refusal.NOT_STORED);
        }

        Derived derived = PimdirSql.derive(row.kind, body);
        boolean home = target.equals(row.collection.id);
        String linkId =
                home || !PimdirSummary.MAIL.equals(row.kind)
                        ? row.linkId
                        : derived.linkId();
        if (live(target, linkId)) {
            throw new RefusedException(Refusal.PRESENT);
        }
        String sortKey = derived.sortKey();
        engine.mutateAdd(
                target,
                linkId,
                body,
                flagsOf(row).with(Flags.Marker.DELETED, false),
                derived.summary(),
                sortKey.isEmpty() ? row.sortKey : sortKey);
    }

    /** pimdir's {@code live_item_for_link}: the identity is live there. */
    private boolean live(String collection, String linkId) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("link_id", linkId);
        try (Cursor cursor = query(store.getReadableDatabase(), "LIVE_ITEM_FOR_LINK", values)) {
            return cursor.moveToFirst();
        }
    }

    private static Flags flagsOf(Row row) {
        try {
            return Flags.parse(row.flags);
        } catch (JSONException error) {
            Log.w("pimalaya", "unreadable flags of " + row.linkId, error);
            return Flags.none();
        }
    }

    /** An instant as the store stamps retention (RFC 3339, UTC, millis). */
    public static String stamp(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    /** The instant of a retention {@link #stamp}, 0 for none or unreadable. */
    private static long instantOf(String stamp) {
        if (stamp == null) {
            return 0;
        }
        try {
            return Instant.parse(stamp).toEpochMilli();
        } catch (DateTimeParseException error) {
            return 0;
        }
    }

    /**
     * Purges items retained before {@code cutoff} (RFC 3339); returns how many.
     *
     * <p>pimdir's {@code purge_retained_before} then {@code collect_garbage}.
     * Pins are recounted, not read off {@code RETURNING}: a cursor refill
     * would rerun the purge. A row a source still binds is never reached.
     */
    public int purgeBefore(String cutoff) {
        SQLiteDatabase db = store.getWritableDatabase();
        int purged = 0;
        db.beginTransaction();
        try {
            Map<String, Object> values = new HashMap<>();
            values.put("cutoff", cutoff);
            purged = PimdirDb.changed(db, PimdirSql.bind("PURGE_RETAINED_BEFORE", values));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        collectGarbage();
        return purged;
    }

    /**
     * pimdir's {@code collect_garbage}: counts recomputed, zeros reclaimed.
     *
     * <p>Blobs go after the commit, each only while its row is still gone (as
     * {@link PimdirItems#unlinkCollected}), so a rollback keeps its file.
     */
    void collectGarbage() {
        SQLiteDatabase db = store.getWritableDatabase();
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        db.beginTransaction();
        try {
            exec(db, "RECOMPUTE_REFCOUNTS", new HashMap<>());
            List<String> garbage = new ArrayList<>();
            try (Cursor cursor = query(db, "LIST_GARBAGE_OBJECTS", new HashMap<>())) {
                while (cursor.moveToNext()) {
                    garbage.add(cursor.getString(0));
                }
            }
            exec(db, "DELETE_GARBAGE_OBJECTS", new HashMap<>());
            PimdirItems.deferUnlink(store, garbage);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        PimdirItems.unlinkCollected(store, db, blobs);
    }

    private static Cursor query(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        return PimdirDb.typed(db, bound.sql, bound.args);
    }

    private static void exec(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        db.execSQL(bound.sql, bound.args);
    }

    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }
}
