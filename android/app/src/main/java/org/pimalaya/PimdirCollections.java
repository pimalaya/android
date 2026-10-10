package org.pimalaya;

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
 * <p>A collection has to exist before an item can point at it: every foreign
 * key onto {@code collections(id)} is enforced, so a sync writing into a
 * collection the store has never heard of fails outright rather than inventing
 * one. This class is what puts them there, from the rosters the app already
 * discovers: an account's address books, its mailboxes, its calendars.
 *
 * <p>The three columns that matter are the merged view's axes.
 * {@code account} groups (a stable id from {@link PimdirAccount}, never the
 * email), {@code kind} carries the media type the collection holds, and the id
 * is the collection's own address on its backend. Presentation ({@code name},
 * {@code description}, {@code color}) rides along so a listing needs no second
 * table.
 *
 * <p>The switches an address book also carries (subscribed, and the two sync
 * spokes) are deliberately <strong>not</strong> here: they are this app's
 * policy about a collection, not a property of it, and pimdir has no column for
 * them for exactly that reason.
 */
final class PimdirCollections {
    /** Where the collections the user may not write into are kept, by id. */
    private static final String READ_ONLY_PREFS = "collections-read-only";

    private static final String READ_ONLY = "ids";

    private final PimdirDb store;
    private final PimdirAccount accounts;
    private final Context context;

    PimdirCollections(PimdirDb store, Context context) {
        this.store = store;
        this.context = context;
        this.accounts = new PimdirAccount(context);
    }

    /**
     * Replaces one account's collections of a kind with the discovered set:
     * every listed collection is created or refreshed, and one the account no
     * longer has is removed.
     *
     * <p>Removal is a real delete, cascading the items and bindings it held,
     * because a collection absent from a complete listing is gone from the
     * backend: keeping its contents would leave a book the user cannot see and
     * no sync will ever touch again. It is scoped to the account and the kind,
     * so an account's calendars survive a contacts round.
     */
    void replace(String accountEmail, String kind, List<Stored> collections) {
        String account = accounts.idOf(accountEmail);
        SQLiteDatabase db = store.getWritableDatabase();

        db.beginTransaction();
        try {
            Set<String> listed = new HashSet<>();
            for (Stored collection : collections) {
                listed.add(collection.id);
                upsert(db, collection.id, account, kind, collection.name,
                        collection.description, collection.color);
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
     * Creates one collection if the store does not know it yet, refreshing only
     * its display name otherwise.
     *
     * <p>The name alone, because everything else about an existing collection
     * belongs to whatever wrote it: this is how the built-in on-device book is
     * seeded on every start without resetting anything each time.
     */
    void ensure(String id, String accountEmail, String kind, String name) {
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
     * <p>Also the shape a roster is handed over in, which is why it is one type
     * rather than three: an address book, a calendar and a mailbox differ in
     * what they contain, not in how they are listed, and giving each its own
     * roster type would fork this class three ways for nothing.
     */
    static final class Stored {
        final String id;
        final String accountEmail;
        final String name;
        final String description;
        final String color;

        /** What the source says the collection is for (pimdir's role), or null. */
        final String role;

        /** Whether the user may write into it, as its source last said. */
        final boolean writable;

        Stored(String id, String accountEmail, String name, String description, String color) {
            this(id, accountEmail, name, description, color, null, true);
        }

        Stored(
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
        boolean isDefault() {
            return Calendar.DEFAULT.equals(role);
        }
    }

    /**
     * Every collection of one kind, ordered by account then name: the roster a
     * listing or a subscription editor renders.
     *
     * <p>A collection whose account this device no longer knows keeps its store
     * id as its address, so it stays visible rather than silently dropping out
     * of a listing that is meant to show everything.
     */
    List<Stored> list(String kind) {
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
     * Restates what the source says of collections the store already holds:
     * the role (pimdir's, null where it says none) and whether the user may
     * write into them (the app's, pimdir keeping no such column).
     *
     * <p>Apart from {@link #replace} so a sync can refresh what an account's
     * books are for without replacing the roster the switches hang off.
     */
    void restate(List<Stored> collections) {
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
    static List<Stored> of(String accountEmail, List<Addressbook> books) {
        List<Stored> listed = new ArrayList<>(books.size());
        for (Addressbook book : books) {
            listed.add(
                    new Stored(
                            book.url,
                            accountEmail,
                            book.name,
                            book.description,
                            book.color,
                            book.role,
                            book.writable));
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
    private static void upsert(SQLiteDatabase db, String id, String account, String kind,
            String name, String description, String color) {
        db.execSQL(
                "INSERT INTO collections(id, account, kind, name, description, color)"
                        + " VALUES(?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT(id) DO UPDATE SET account = excluded.account,"
                        + " kind = excluded.kind, name = excluded.name,"
                        + " description = excluded.description, color = excluded.color",
                new Object[] {id, account, kind, name == null ? id : name, description, color});
    }
}
