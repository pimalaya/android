package org.pimalaya;

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
 * References between items (pimdir STORAGE section 14.2), as the item pages
 * read and write them: what an item links to and is linked from, a person's
 * link added or any link removed, and a search across the four kinds for an
 * item to link to.
 *
 * <p>Which item stands for which endpoint is decided here and nowhere else
 * ({@link #ofMessage} and its siblings), so a rule about which keys may be
 * referenced (none under a writer-derived key) is one change.
 */
final class ItemLinks {
    /** The role a person's link takes. */
    static final String RELATED = "related";

    /** The origin of a link a person made, as opposed to a rule's {@code auto}. */
    static final String USER = "user";

    /** How many results each kind gives a search. */
    private static final int FOUND = 10;

    private final Context context;
    private final PimdirDb store;
    private final PimdirCollections collections;

    ItemLinks(Context context, PimdirDb store) {
        this.context = context;
        this.store = store;
        this.collections = new PimdirCollections(store, context);
    }

    /** One end of a reference: a kind and a key, never a collection. */
    static final class Endpoint {
        final String kind;
        final String linkId;

        Endpoint(String kind, String linkId) {
            this.kind = kind;
            this.linkId = linkId;
        }

        boolean same(Endpoint other) {
            return kind.equals(other.kind) && linkId.equals(other.linkId);
        }
    }

    /** One live placement of an endpoint: where it opens, and what it is called. */
    static final class Placement {
        final Endpoint endpoint;
        final String collection;
        final long seq;
        final String title;

        /** A second line: a message's sender; empty for the rest. */
        final String detail;

        Placement(Endpoint endpoint, String collection, long seq, String title, String detail) {
            this.endpoint = endpoint;
            this.collection = collection;
            this.seq = seq;
            this.title = title;
            this.detail = detail;
        }
    }

    /** One reference as an item's page shows it, from that item's side. */
    static final class Link {
        final Endpoint from;
        final Endpoint to;
        final String role;
        final String origin;

        /** The other item, null when it has no live placement left. */
        final Placement other;

        Link(Endpoint from, Endpoint to, String role, String origin, Placement other) {
            this.from = from;
            this.to = to;
            this.role = role;
            this.origin = origin;
            this.other = other;
        }

        boolean automatic() {
            return !USER.equals(origin);
        }
    }

    /** A message's endpoint; null for one still in an outbox, which is no item. */
    static Endpoint ofMessage(MailStore.StoredMessage message) {
        return message.pending ? null : endpoint(PimdirSummary.MAIL, message.id);
    }

    /** A card's endpoint, by the key its replicas share. */
    static Endpoint ofContact(String linkId) {
        return endpoint(PimdirSummary.CONTACT, linkId);
    }

    static Endpoint ofEvent(EventStore.StoredEvent event) {
        return endpoint(PimdirSummary.CALENDAR, event.id);
    }

    static Endpoint ofFile(FileStore.StoredFile file) {
        return endpoint(PimdirSummary.FILE, file.linkId);
    }

    /**
     * An endpoint, or null when its key may not be referenced: a
     * writer-derived key names no identity (STORAGE sections 9.1, 14.2).
     */
    private static Endpoint endpoint(String kind, String linkId) {
        return linkId == null || linkId.isEmpty() || derived(linkId)
                ? null
                : new Endpoint(kind, linkId);
    }

    /**
     * Whether a key is one a writer derived (an {@code alt:}, {@code dup:}
     * or {@code hash:} key, or a part of a message under one), which pimdir
     * neither references nor keys a stand-in on.
     */
    static boolean derived(String linkId) {
        String key = linkId.startsWith("part:") ? linkId.substring(5) : linkId;
        return key.startsWith("alt:") || key.startsWith("dup:") || key.startsWith("hash:");
    }

    /**
     * The references an item makes and receives, each with the other item's
     * placement, sorted by the other item's kind (messages, contacts,
     * events, files) then title.
     */
    List<Link> links(Endpoint self) {
        SQLiteDatabase db = store.getReadableDatabase();
        List<Link> links = new ArrayList<>();
        for (String statement : new String[] {"REFERENCES_FROM", "REFERENCES_TO"}) {
            Map<String, Object> values = new HashMap<>();
            values.put("kind", self.kind);
            values.put("link_id", self.linkId);
            PimdirSql.Bound bound = PimdirSql.bind(statement, values);
            try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
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
     * Records a person's link from one item to another, role {@code related};
     * false when it records nothing (an endpoint the store does not hold, or
     * one under a derived key). Linking again changes nothing, save that a
     * rule's link becomes the person's: the statement answers the reference
     * standing either way.
     */
    boolean add(Endpoint from, Endpoint to) {
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

    /** Removes one reference, whatever its origin; a rule may record it anew. */
    void remove(Link link) {
        Map<String, Object> values = new HashMap<>();
        values.put("from_kind", link.from.kind);
        values.put("from_link_id", link.from.linkId);
        values.put("to_kind", link.to.kind);
        values.put("to_link_id", link.to.linkId);
        values.put("role", link.role);
        query(store.getWritableDatabase(), "REMOVE_REFERENCE", values);
    }

    /**
     * Runs a statement answering rows (Android refuses one to execSQL),
     * answering whether it answered any.
     */
    private static boolean query(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
            return cursor.moveToFirst();
        }
    }

    /** The first live placement of an endpoint in a collection of its kind, null for none. */
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
     * Items whose title holds {@code words}, a few of each kind: messages
     * through the mail search, contacts by name or address, calendar
     * resources by summary, files by name.
     */
    List<Placement> search(String words) {
        Map<String, Placement> found = new LinkedHashMap<>();
        if (words.trim().isEmpty()) {
            return new ArrayList<>();
        }

        MailStore mail = new MailStore(context, store);
        MailStore.Query query = mail.query((account, collection) -> true, false, false, words);
        for (MailStore.StoredMessage message : mail.page(query, null, 0, FOUND)) {
            Endpoint endpoint = ofMessage(message);
            if (endpoint != null) {
                found.putIfAbsent(
                        key(endpoint),
                        new Placement(
                                endpoint,
                                message.collection,
                                message.seq,
                                message.subject,
                                message.fromName.isEmpty()
                                        ? message.fromAddress
                                        : message.fromName));
            }
        }

        SQLiteDatabase db = store.getReadableDatabase();
        String pattern = MailStore.likePattern(words);
        search(db, PimdirSummary.CONTACT, "SEARCH_CONTACTS", 8, pattern, found);
        search(db, PimdirSummary.CALENDAR, "SEARCH_CALENDAR", 8, pattern, found);
        search(db, PimdirSummary.FILE, "SEARCH_FILES", 7, pattern, found);
        return new ArrayList<>(found.values());
    }

    /** One kind's search statement over every collection of the kind, a page of a few. */
    private void search(
            SQLiteDatabase db,
            String kind,
            String statement,
            int titleColumn,
            String pattern,
            Map<String, Placement> found) {
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
                Endpoint endpoint = endpoint(kind, cursor.getString(2));
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
        return MailStore.typed(db, bound.sql, bound.args);
    }

    private static String text(Cursor cursor, int column) {
        return cursor.isNull(column) ? "" : cursor.getString(column);
    }
}
