package org.pimalaya.pimdir;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The actions a person staged on delivered creates (pimdir SYNC §2): a delete
 * for good, a restore or a move of a message its server took before listing
 * it, held until a listing names it. What the waiting notice tells.
 *
 * <p>Read off the store ({@code held_actions}), so a create waiting with
 * nothing staged on it, or one waiting for its scope, tells nothing. What was
 * told is the highest change stamp among them (STORAGE §4.5), kept with the
 * store's identity and incarnation: a restart tells nothing twice, and a
 * store rebuilt or restored starts afresh.
 */
public final class HeldActions {
    /**
     * One collection's held actions.
     *
     * @param collection the stored collection
     * @param actions how many
     */
    public record Held(String collection, int actions) {}

    private final PimdirDb pimdir;

    /** Where what was told is kept: the store's identity, then the stamp. */
    private final File told;

    HeldActions(PimdirDb pimdir, File told) {
        this.pimdir = pimdir;
        this.told = told;
    }

    /**
     * The collections holding an action staged since the last told, every
     * held one of theirs counted, marked told.
     */
    public synchronized List<Held> untold() {
        SQLiteDatabase db = pimdir.getReadableDatabase();
        String store = identity(db);
        long since = since(store);
        List<Held> fresh = new ArrayList<>();
        long latest = since;
        try (Cursor cursor = PimdirDb.query(db, "HELD_ACTIONS", Map.of())) {
            while (cursor.moveToNext()) {
                long changed = cursor.getLong(2);
                if (changed > since) {
                    fresh.add(new Held(cursor.getString(0), cursor.getInt(1)));
                }
                latest = Math.max(latest, changed);
            }
        }
        if (latest > since) {
            keep(store, latest);
        }
        return fresh;
    }

    /** The store's identity and incarnation: a rebuild or restore moves it. */
    private static String identity(SQLiteDatabase db) {
        try (Cursor cursor = PimdirDb.query(db, "LOAD_STORE_META", Map.of())) {
            return cursor.moveToFirst() ? cursor.getString(11) + ":" + cursor.getLong(12) : "";
        }
    }

    /** The stamp told up to in {@code store}, 0 for another store or none. */
    private long since(String store) {
        try {
            String[] kept =
                    new String(Files.readAllBytes(told.toPath()), StandardCharsets.UTF_8)
                            .trim()
                            .split(" ");
            return kept.length == 2 && kept[0].equals(store) ? Long.parseLong(kept[1]) : 0;
        } catch (IOException | NumberFormatException missing) {
            return 0;
        }
    }

    private void keep(String store, long stamp) {
        try {
            byte[] line = (store + " " + stamp + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(told.toPath(), line);
        } catch (IOException failure) {
            // NOTE: told again on the next start rather than never told.
            Log.w("pimalaya", "held actions told unkept: " + failure.getMessage());
        }
    }
}
