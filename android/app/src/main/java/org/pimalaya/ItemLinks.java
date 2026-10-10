package org.pimalaya;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.pimalaya.client.PimdirSql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * References between items (pimdir STORAGE section 14.2), as the item pages
 * read and write them: what an item links to and is linked from, a person's
 * link added or any link removed, and a search across the four kinds for an
 * item to link to.
 *
 * <p>Which item stands for which endpoint is decided here and nowhere else
 * ({@link #ofMessage} and its siblings), so a rule about which keys may be
 * referenced (pimdir may stop referencing a message under a derived key) is
 * one change.
 */
final class ItemLinks {
    /** The role a person's link takes. */
    static final String RELATED = "related";

    /** The origin of a link a person made, as opposed to a rule's {@code auto}. */
    static final String USER = "user";

    /** How many results each kind gives a search. */
    private static final int FOUND = 10;

    /** How many rows a scan of a collection reads at a time. */
    private static final int PAGE = 500;

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
     * An endpoint, or null when its key may not be referenced: every key
     * may for now; a narrower rule pimdir settles lands here.
     */
    private static Endpoint endpoint(String kind, String linkId) {
        return linkId == null || linkId.isEmpty() ? null : new Endpoint(kind, linkId);
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
     * false when it records nothing (an endpoint the store does not hold).
     * Linking again changes nothing, save that a rule's link becomes the
     * person's.
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
        if (query(store.getWritableDatabase(), "ADD_REFERENCE", values)) {
            return true;
        }
        // NOTE: the statement answers nothing for a link already recorded
        // as the person's, which is linked all the same.
        for (Link link : links(from)) {
            if (link.from.same(from) && link.to.same(to) && RELATED.equals(link.role)) {
                return true;
            }
        }
        return false;
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

    private static Placement placement(SQLiteDatabase db, Endpoint endpoint) {
        Map<String, Object> values = new HashMap<>();
        values.put("link_id", endpoint.linkId);
        PimdirSql.Bound bound = PimdirSql.bind("LIST_LINK_PLACEMENTS", values);
        try (Cursor cursor = MailStore.typed(db, bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                String collection = cursor.getString(0);
                if (endpoint.kind.equals(kindOf(db, collection))) {
                    return titled(db, endpoint, collection, cursor.getLong(2));
                }
            }
        }
        return null;
    }

    /** A placement titled from its kind's summary. */
    private static Placement titled(
            SQLiteDatabase db, Endpoint endpoint, String collection, long seq) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("seq", seq);
        values.put("link_id", endpoint.linkId);
        String title = "";
        String detail = "";
        switch (endpoint.kind) {
            case PimdirSummary.MAIL:
                try (Cursor row = read(db, "GET_MAIL", values)) {
                    if (row.moveToFirst()) {
                        title = text(row, 8);
                        detail = text(row, 10).isEmpty() ? text(row, 9) : text(row, 10);
                    }
                }
                break;
            case PimdirSummary.CONTACT:
                title = column(db, "GET_CONTACT", values, 7);
                break;
            case PimdirSummary.CALENDAR:
                title = column(db, componentStatement(db, values), values, 7);
                break;
            default:
                title = column(db, "GET_FILE", values, 6);
                break;
        }
        return new Placement(endpoint, collection, seq, title, detail);
    }

    /** The statement reading a calendar resource's summary, by its component. */
    private static String componentStatement(SQLiteDatabase db, Map<String, Object> values) {
        try (Cursor row = read(db, "COMPONENT_OF", values)) {
            String component = row.moveToFirst() ? row.getString(0) : "VEVENT";
            switch (component) {
                case "VTODO":
                    return "GET_TASK";
                case "VJOURNAL":
                    return "GET_JOURNAL";
                default:
                    return "GET_EVENT";
            }
        }
    }

    /**
     * Items whose title holds {@code words}, a few of each kind: messages
     * through the mail search, contacts, calendar resources and files by
     * their summary's name.
     */
    List<Placement> search(String words) {
        String needle = words.trim().toLowerCase(Locale.ROOT);
        Map<String, Placement> found = new LinkedHashMap<>();
        if (needle.isEmpty()) {
            return new ArrayList<>();
        }

        MailStore mail = new MailStore(context, store);
        int messages = 0;
        for (MailStore.StoredMessage message :
                mail.page(mail.query((account, collection) -> true, false, false, words), null,
                        0, FOUND)) {
            Endpoint endpoint = ofMessage(message);
            if (endpoint != null && messages < FOUND) {
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
                messages++;
            }
        }

        SQLiteDatabase db = store.getReadableDatabase();
        scan(db, PimdirSummary.CONTACT, new String[] {"LIST_CONTACTS_PAGE_ASC"}, 7, needle, found);
        scan(
                db,
                PimdirSummary.CALENDAR,
                new String[] {
                    "LIST_EVENTS_PAGE_ASC", "LIST_TASKS_PAGE_ASC", "LIST_JOURNALS_PAGE_ASC"
                },
                7,
                needle,
                found);
        scan(db, PimdirSummary.FILE, new String[] {"LIST_FILES_PAGE_ASC"}, 6, needle, found);
        return new ArrayList<>(found.values());
    }

    /**
     * Scans every collection of a kind through its pages, keeping up to a
     * few items whose title column holds the needle.
     */
    private void scan(
            SQLiteDatabase db,
            String kind,
            String[] statements,
            int titleColumn,
            String needle,
            Map<String, Placement> found) {
        int kept = 0;
        for (PimdirCollections.Stored collection : collections.list(kind)) {
            for (String statement : statements) {
                Map<String, Object> values = new HashMap<>();
                values.put("collection", collection.id);
                values.put("after_key", "");
                values.put("after_seq", -1);
                values.put("limit", PAGE);
                while (kept < FOUND) {
                    int read = 0;
                    try (Cursor cursor = read(db, statement, values)) {
                        while (cursor.moveToNext() && kept < FOUND) {
                            read++;
                            values.put("after_key", text(cursor, 4));
                            values.put("after_seq", cursor.getLong(0));
                            String title = text(cursor, titleColumn);
                            Endpoint endpoint = endpoint(kind, cursor.getString(1));
                            if (endpoint != null
                                    && title.toLowerCase(Locale.ROOT).contains(needle)
                                    && !found.containsKey(key(endpoint))) {
                                found.put(
                                        key(endpoint),
                                        new Placement(
                                                endpoint,
                                                collection.id,
                                                cursor.getLong(0),
                                                title,
                                                ""));
                                kept++;
                            }
                        }
                    }
                    if (read < PAGE) {
                        break;
                    }
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

    private static String column(
            SQLiteDatabase db, String name, Map<String, Object> values, int column) {
        try (Cursor row = read(db, name, values)) {
            return row.moveToFirst() ? text(row, column) : "";
        }
    }

    private static String text(Cursor cursor, int column) {
        return cursor.isNull(column) ? "" : cursor.getString(column);
    }

    private static String kindOf(SQLiteDatabase db, String collection) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        return column(db, "LOAD_KIND", values, 0);
    }
}
