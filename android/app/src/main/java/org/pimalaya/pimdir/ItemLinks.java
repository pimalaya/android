package org.pimalaya.pimdir;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.pimalaya.client.PimdirSql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * References between items (pimdir STORAGE §14.2), as item pages use them.
 *
 * <p>Links to and from an item, add and remove, and a search across contacts,
 * calendars and files. Which key may be an endpoint is decided in {@link #of}
 * alone (none under a writer-derived key).
 */
public final class ItemLinks {
    /** The role a person's link takes. */
    static final String RELATED = "related";

    /** The origin of a person's link, as opposed to a rule's {@code auto}. */
    static final String USER = "user";

    /** How many results each kind gives a search. */
    public static final int FOUND = 10;

    private final PimdirDb store;
    private final PimdirCollections collections;

    /** Links over {@code store}'s items and collections. */
    public ItemLinks(Context context, PimdirDb store) {
        this.store = store;
        this.collections = new PimdirCollections(store, context);
    }

    /** One end of a reference: a kind and a key, never a collection. */
    public static final class Endpoint {
        /** The endpoint's media type. */
        public final String kind;
        /** The endpoint's identity. */
        public final String linkId;

        Endpoint(String kind, String linkId) {
            this.kind = kind;
            this.linkId = linkId;
        }

        /** Whether both name the same item. */
        public boolean same(Endpoint other) {
            return kind.equals(other.kind) && linkId.equals(other.linkId);
        }
    }

    /** One live placement of an endpoint: where it opens, what it is called. */
    public static final class Placement {
        /** The item placed. */
        public final Endpoint endpoint;
        /** The collection it is placed in. */
        public final String collection;
        final long seq;
        /** Its title as a list shows it. */
        public final String title;

        /** A second line: a message's sender; empty for the rest. */
        public final String detail;

        /** A placement as a search lists it. */
        public Placement(
                Endpoint endpoint, String collection, long seq, String title, String detail) {
            this.endpoint = endpoint;
            this.collection = collection;
            this.seq = seq;
            this.title = title;
            this.detail = detail;
        }
    }

    /** One reference as an item's page shows it, from that item's side. */
    public static final class Link {
        /** The referring item. */
        public final Endpoint from;
        /** The referred item. */
        public final Endpoint to;
        /** The reference's role (STORAGE §14.2). */
        public final String role;
        final String origin;

        /** The other item, null when it has no live placement left. */
        public final Placement other;

        Link(Endpoint from, Endpoint to, String role, String origin, Placement other) {
            this.from = from;
            this.to = to;
            this.role = role;
            this.origin = origin;
            this.other = other;
        }

        /** Whether a rule made it rather than a person. */
        public boolean automatic() {
            return !USER.equals(origin);
        }
    }

    /** A card's endpoint, by the key its replicas share. */
    public static Endpoint ofContact(String linkId) {
        return of(PimdirSummary.CONTACT, linkId);
    }

    /**
     * An endpoint, or null when its key may not be referenced.
     *
     * <p>A writer-derived key names no identity (STORAGE §9.1, §14.2).
     */
    public static Endpoint of(String kind, String linkId) {
        return linkId == null || linkId.isEmpty() || derived(linkId)
                ? null
                : new Endpoint(kind, linkId);
    }

    /**
     * Whether a key is writer-derived, which pimdir never references.
     *
     * <p>An {@code alt:}, {@code dup:} or {@code hash:} key, or a part of a
     * message under one.
     */
    public static boolean derived(String linkId) {
        String key = linkId.startsWith("part:") ? linkId.substring(5) : linkId;
        return key.startsWith("alt:") || key.startsWith("dup:") || key.startsWith("hash:");
    }

    /**
     * The references an item makes and receives, with the other's placement.
     *
     * <p>Sorted by the other item's kind (messages, contacts, events, files)
     * then title.
     */
    public List<Link> links(Endpoint self) {
        SQLiteDatabase db = store.getReadableDatabase();
        List<Link> links = new ArrayList<>();
        for (String statement : new String[] {"REFERENCES_FROM", "REFERENCES_TO"}) {
            Map<String, Object> values = new HashMap<>();
            values.put("kind", self.kind);
            values.put("link_id", self.linkId);
            PimdirSql.Bound bound = PimdirSql.bind(statement, values);
            try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
                while (cursor.moveToNext()) {
                    Endpoint from = new Endpoint(cursor.getString(0), cursor.getString(1));
                    Endpoint to = new Endpoint(cursor.getString(2), cursor.getString(3));
                    Endpoint other = from.same(self) ? to : from;
                    links.add(
                            new Link(
                                    from,
                                    to,
                                    cursor.getString(4),
                                    cursor.getString(5),
                                    placement(db, other)));
                }
            }
        }
        links.sort(
                (left, right) -> {
                    Endpoint a = left.from.same(self) ? left.to : left.from;
                    Endpoint b = right.from.same(self) ? right.to : right.from;
                    int kind = Integer.compare(order(a.kind), order(b.kind));
                    return kind != 0 ? kind : titleOf(left).compareToIgnoreCase(titleOf(right));
                });
        return links;
    }

    private static String titleOf(Link link) {
        return link.other == null ? "" : link.other.title;
    }

    /** Where a kind sorts among the four. */
    static int order(String kind) {
        switch (kind) {
            case PimdirSummary.MAIL:
                return 0;
            case PimdirSummary.CONTACT:
                return 1;
            case PimdirSummary.CALENDAR:
                return 2;
            default:
                return 3;
        }
    }

    /**
     * Records a person's {@code related} link; false when it records nothing.
     *
     * <p>Nothing for an endpoint not held or under a derived key. Linking again
     * only turns a rule's link into the person's.
     */
    public boolean add(Endpoint from, Endpoint to) {
        if (from.same(to)) {
            return false;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("from_kind", from.kind);
        values.put("from_link_id", from.linkId);
        values.put("to_kind", to.kind);
        values.put("to_link_id", to.linkId);
        values.put("role", RELATED);
        values.put("origin", USER);
        return query(store.getWritableDatabase(), "ADD_REFERENCE", values);
    }

    /** Removes one reference, whatever its origin; a rule may add it anew. */
    public void remove(Link link) {
        Map<String, Object> values = new HashMap<>();
        values.put("from_kind", link.from.kind);
        values.put("from_link_id", link.from.linkId);
        values.put("to_kind", link.to.kind);
        values.put("to_link_id", link.to.linkId);
        values.put("role", link.role);
        query(store.getWritableDatabase(), "REMOVE_REFERENCE", values);
    }

    /**
     * Runs a statement answering rows, and answers whether it had any.
     *
     * <p>Android refuses such a statement to execSQL.
     */
    private static boolean query(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
            return cursor.moveToFirst();
        }
    }
    /** An endpoint's first live placement in its kind, or null for none. */
    /** An endpoint's first live placement in a collection of its kind. */
    Placement placement(Endpoint endpoint) {
        return placement(store.getReadableDatabase(), endpoint);
    }

    /** {@code describe_endpoint}: the first live placement and its title. */
    private static Placement placement(SQLiteDatabase db, Endpoint endpoint) {
        Map<String, Object> values = new HashMap<>();
        values.put("kind", endpoint.kind);
        values.put("link_id", endpoint.linkId);
        try (Cursor row = read(db, "DESCRIBE_ENDPOINT", values)) {
            return row.moveToFirst()
                    ? new Placement(endpoint, row.getString(0), row.getLong(1), text(row, 2), "")
                    : null;
        }
    }

    /**
     * Items whose title holds {@code words}, a few of each kind.
     *
     * <p>Contacts by name or address, events by summary, files by name;
     * messages come from the mail search ({@code MailStore.linkPlacements}).
     */
    public List<Placement> search(String words) {
        Map<String, Placement> found = new LinkedHashMap<>();
        if (words.trim().isEmpty()) {
            return new ArrayList<>();
        }
        SQLiteDatabase db = store.getReadableDatabase();
        String pattern = PimdirDb.likePattern(words);
        search(db, new KindSearch(PimdirSummary.CONTACT, "SEARCH_CONTACTS", 8), pattern, found);
        search(db, new KindSearch(PimdirSummary.CALENDAR, "SEARCH_CALENDAR", 8), pattern, found);
        search(db, new KindSearch(PimdirSummary.FILE, "SEARCH_FILES", 7), pattern, found);
        return new ArrayList<>(found.values());
    }

    /** One kind's search: its kind, its statement, the title's column there. */
    private record KindSearch(String kind, String statement, int titleColumn) {}

    /** One kind's search over all its collections, a page of a few. */
    private void search(
            SQLiteDatabase db, KindSearch search, String pattern, Map<String, Placement> found) {
        String kind = search.kind();
        String statement = search.statement();
        int titleColumn = search.titleColumn();

        List<String> ids = new ArrayList<>();
        for (PimdirCollections.Stored collection : collections.list(kind)) {
            ids.add(collection.id);
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("collections", new JSONArray(ids).toString());
        values.put("pattern", pattern);
        values.put("after_key", null);
        values.put("limit", FOUND);
        try (Cursor cursor = read(db, statement, values)) {
            while (cursor.moveToNext()) {
                Endpoint endpoint = of(kind, cursor.getString(2));
                if (endpoint != null) {
                    found.putIfAbsent(
                            key(endpoint),
                            new Placement(
                                    endpoint,
                                    cursor.getString(0),
                                    cursor.getLong(1),
                                    text(cursor, titleColumn),
                                    ""));
                }
            }
        }
    }

    private static String key(Endpoint endpoint) {
        return endpoint.kind + "\u0000" + endpoint.linkId;
    }

    private static Cursor read(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        return PimdirDb.typed(db, bound.sql, bound.args);
    }

    private static String text(Cursor cursor, int column) {
        return cursor.isNull(column) ? "" : cursor.getString(column);
    }
}
