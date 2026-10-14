package org.pimalaya.pimdir;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.PimdirSql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The collection roster inside the pimdir store.
 *
 * <p>Items need their collection first (enforced foreign key). Rows group by
 * {@link PimdirAccount} id and media type kind; the app's book switches are
 * policy, not collection properties, and pimdir has no column for them.
 */
public final class PimdirCollections {
    /**
     * The hidden collection a Gmail account files every stepped message in.
     *
     * <p>All Mail: an archived message lives there alone, so no step reads one
     * twice. Never listed ({@link #list}); a control character, like the
     * outbox, so no server mailbox collides with it.
     */
    public static final String ALL_MAIL = "\u0001all";

    /** The collection name an account's stand-ins are filed under. */
    public static final String ATTACHMENTS = "\u0001attachments";

    /** The role an attachments collection carries (STORAGE section 14). */
    public static final String ATTACHMENTS_ROLE = "attachments";

    /** Where the collections the user may not write into are kept, by id. */
    private static final String READ_ONLY_PREFS = "collections-read-only";

    private static final String READ_ONLY = "ids";

    private final PimdirDb store;
    private final PimdirAccount accounts;
    private final Context context;

    /** The roster of {@code store}. */
    public PimdirCollections(PimdirDb store, Context context) {
        this.store = store;
        this.context = context;
        this.accounts = new PimdirAccount(context);
    }

    /**
     * Replaces one account's collections of a kind with the discovered set.
     *
     * <p>One absent from the complete listing is gone from the backend: really
     * deleted, cascading its items and bindings. Scoped to account and kind,
     * so calendars survive a contacts round.
     */
    public void replace(String accountEmail, String kind, List<Stored> collections) {
        String account = accounts.idOf(accountEmail);
        SQLiteDatabase db = store.getWritableDatabase();

        db.beginTransaction();
        try {
            Set<String> listed = new HashSet<>();
            for (Stored collection : collections) {
                listed.add(collection.id);
                upsert(db, collection, account, kind);
            }

            for (String stale : idsOf(db, account, kind)) {
                if (!listed.contains(stale)) {
                    db.execSQL("DELETE FROM collections WHERE id = ?", new Object[] {stale});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        // NOTE: a mailbox's role is the mail roster's own to write
        // (MailStore), from a richer vocabulary than a roster carries here.
        if (!PimdirSummary.MAIL.equals(kind)) {
            restate(collections);
        }
    }

    /**
     * Creates one collection if unknown, else refreshes its name only.
     *
     * <p>The rest belongs to whatever wrote it: the on-device book is seeded
     * on every start this way without resetting anything.
     */
    public void ensure(String id, String accountEmail, String kind, String name) {
        String account = accounts.idOf(accountEmail);
        SQLiteDatabase db = store.getWritableDatabase();

        db.execSQL(
                PimdirSql.of("SET_COLLECTION_KIND")
                        .replace(":collection", "?")
                        .replace(":account", "?")
                        .replace(":kind", "?"),
                new Object[] {id, account, kind, id});
        db.execSQL("UPDATE collections SET name = ? WHERE id = ?", new Object[] {name, id});
    }

    /**
     * One collection as the store holds it, with the account it belongs to.
     *
     * <p>Also the shape rosters are handed over in, one type for books,
     * calendars and mailboxes alike.
     */
    public static final class Stored {
        /** The collection's store id, namespaced by its account. */
        public final String id;
        /** The account it belongs to. */
        public final String accountEmail;
        /** Its display name. */
        public final String name;
        /** Its description, or null. */
        public final String description;
        /** Its colour, or null. */
        public final String color;

        /** What the source says the collection is for (its role), or null. */
        public final String role;

        /** Whether the user may write into it, as its source last said. */
        public final boolean writable;

        /** A writable collection with no role. */
        public Stored(String id, String accountEmail, String name, String description, String color) {
            this(id, accountEmail, name, description, color, null, true);
        }

        /** A collection with its role and writability. */
        public Stored(
                String id,
                String accountEmail,
                String name,
                String description,
                String color,
                String role,
                boolean writable) {
            this.id = id;
            this.accountEmail = accountEmail;
            this.name = name;
            this.description = description;
            this.color = color;
            this.role = role == null || role.isEmpty() ? null : role;
            this.writable = writable;
        }

        /** Whether its source names it the default of its kind. */
        public boolean isDefault() {
            return Calendar.DEFAULT.equals(role);
        }
    }

    /**
     * Every collection of one kind, ordered by account then name.
     *
     * <p>An unknown account's collection keeps its store id as address rather
     * than drop out. Gmail's All Mail ({@link #ALL_MAIL}) is never listed, see
     * {@link #listAll}.
     */
    public List<Stored> list(String kind) {
        List<Stored> shown = listAll(kind);
        shown.removeIf(stored -> ALL_MAIL.equals(stored.name));
        return shown;
    }

    /** Every collection of one kind, a Gmail account's All Mail among them. */
    public List<Stored> listAll(String kind) {
        // NOTE: pimdir's own roster read, LIST_COLLECTIONS: id, account,
        // kind, name, parent, color, description, sort order, generation,
        // role, then the coverage.
        Set<String> readOnly = readOnly();
        List<String[]> rows = new ArrayList<>();
        String sql = PimdirSql.split(PimdirSql.of("LIST_COLLECTIONS"))[0];
        try (Cursor cursor = store.getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                if (!kind.equals(cursor.getString(2))) {
                    continue;
                }
                rows.add(
                        new String[] {
                            cursor.getString(0),
                            cursor.isNull(1) ? null : cursor.getString(1),
                            cursor.getString(3),
                            cursor.isNull(6) ? null : cursor.getString(6),
                            cursor.isNull(5) ? null : cursor.getString(5),
                            cursor.isNull(9) ? null : cursor.getString(9),
                        });
            }
        }
        rows.sort(
                Comparator.comparing(
                                (String[] row) -> row[1],
                                Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                        .thenComparing(row -> row[2], String.CASE_INSENSITIVE_ORDER));

        List<Stored> stored = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            String email = row[1] == null ? null : accounts.emailOf(row[1]);
            stored.add(
                    new Stored(
                            row[0],
                            email == null ? row[0] : email,
                            row[2],
                            row[3],
                            row[4],
                            row[5],
                            !readOnly.contains(row[0])));
        }
        return stored;
    }

    /**
     * Restates the source's role and writability of known collections.
     *
     * <p>The role is pimdir's, writability the app's (no pimdir column). Apart
     * from {@link #replace} so the switches' roster stays.
     */
    public void restate(List<Stored> collections) {
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            // NOTE: the roles left first, so a default moving from one
            // collection to another is never refused for being held twice.
            for (Stored collection : collections) {
                if (collection.role == null) {
                    setRole(db, collection.id, null);
                }
            }
            for (Stored collection : collections) {
                if (collection.role != null) {
                    setRole(db, collection.id, collection.role);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        Set<String> readOnly = readOnly();
        for (Stored collection : collections) {
            if (collection.writable) {
                readOnly.remove(collection.id);
            } else {
                readOnly.add(collection.id);
            }
        }
        context.getSharedPreferences(READ_ONLY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(READ_ONLY, readOnly)
                .apply();
    }

    /** The collections the user may not write into, by id (a copy). */
    private Set<String> readOnly() {
        return new HashSet<>(
                context.getSharedPreferences(READ_ONLY_PREFS, Context.MODE_PRIVATE)
                        .getStringSet(READ_ONLY, new HashSet<>()));
    }

    /** pimdir's SET_COLLECTION_ROLE. */
    private static void setRole(SQLiteDatabase db, String collection, String role) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("role", role);
        PimdirSql.Bound bound = PimdirSql.bind("SET_COLLECTION_ROLE", values);
        db.execSQL(bound.sql, bound.args);
    }

    /** An address book roster in the shape {@link #replace} takes. */
    public static List<Stored> of(String accountEmail, List<Addressbook> books) {
        List<Stored> listed = new ArrayList<>(books.size());
        for (Addressbook book : books) {
            listed.add(
                    new Stored(
                            book.url(),
                            accountEmail,
                            book.name(),
                            book.description(),
                            book.color(),
                            book.role(),
                            book.writable()));
        }
        return listed;
    }

    /** The account's collections of one kind, by id. */
    private static List<String> idsOf(SQLiteDatabase db, String account, String kind) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT id FROM collections WHERE account = ? AND kind = ?",
                        new String[] {account, kind})) {
            List<String> ids = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
            }
            return ids;
        }
    }

    /**
     * Writes one collection row, leaving everything the store owns alone.
     *
     * <p>Notably the generation and the conflict policy: a re-listing is a
     * refresh of what the backend says, not a reset of what the store has
     * learned since.
     */
    private static void upsert(
            SQLiteDatabase db, Stored collection, String account, String kind) {
        String id = collection.id;
        String name = collection.name;
        String description = collection.description;
        String color = collection.color;

        db.execSQL(
                "INSERT INTO collections(id, account, kind, name, description, color)"
                        + " VALUES(?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(id) DO UPDATE SET account = excluded.account,"
                        + " kind = excluded.kind, name = excluded.name,"
                        + " description = excluded.description, color = excluded.color",
                new Object[] {id, account, kind, name == null ? id : name, description, color});
    }
}
