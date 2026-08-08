package org.pimalaya;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.pimalaya.client.PimdirSql;

import java.io.File;

/**
 * The pimdir store's database, on Android's own SQLite.
 *
 * <p>The schema is not written here: it comes from io-pimdir over JNI
 * ({@link PimdirSql}), so the app runs the specification's bytes rather than a
 * transcription that would drift from it. What this class owns is the Android
 * side of the contract, which the crate deliberately does not: where the file
 * lives, when it is created, and the {@code objects/} directory beside it.
 *
 * <p>io-pimdir is taken without its {@code client} feature on purpose. Android
 * ships SQLite; compiling rusqlite in would put a second engine in every ABI of
 * a binary whose first design goal is to be small.
 *
 * <p>One store holds every account and every domain: {@code collections.kind}
 * carries the media type ({@code message/rfc822}, {@code text/vcard},
 * {@code text/calendar}) and {@code collections.account} groups by account, so
 * the merged view's two filter axes are columns rather than three databases.
 */
final class PimdirDb extends SQLiteOpenHelper {
    /** The store directory under the app's private files. */
    static final String DIRECTORY = "pimdir";

    private static final String DATABASE = "pimdir.db";

    private final File blobs;

    /**
     * The per-domain databases this store replaced.
     *
     * <p>They are deleted rather than left alone because they are pure caches of
     * a read-only sync: nothing in them is unrecoverable, and an install that
     * has been through the migration would otherwise carry a mail spine and a
     * calendar mirror that no code opens again. The contacts database survives,
     * since it still holds the app's own state about its books.
     */
    private static final String[] SUPERSEDED = {"events.db", "mail.db"};

    PimdirDb(Context context) {
        super(context, new File(storeDir(context), DATABASE).getAbsolutePath(), null,
                PimdirSql.version());
        this.blobs = new File(storeDir(context), "objects");
        for (String superseded : SUPERSEDED) {
            context.deleteDatabase(superseded);
        }
    }

    /** The store root: {@code <files>/pimdir/}, holding the db and the blobs. */
    static File storeDir(Context context) {
        File dir = new File(context.getFilesDir(), DIRECTORY);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create the pimdir store at " + dir);
        }
        return dir;
    }

    /** The content-addressed blob directory beside the database. */
    File blobs() {
        return blobs;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // NOTE: execSQL compiles one statement per call, and the canonical
        // schema is one script whose comments contain semicolons, so the split
        // has to strip comments first (PimdirSql.schema).
        for (String statement : PimdirSql.schema()) {
            db.execSQL(statement);
        }
        // NOTE: the recorded algorithm has to be the one the app actually
        // computes (PimdirHash), or every blob in the store is filed under a
        // name no other pimdir reader can verify.
        db.execSQL(
                "INSERT INTO store_meta(id, version, hash_algo, created_at)"
                        + " VALUES(1, ?, ?, strftime('%Y-%m-%dT%H:%M:%fZ','now'))",
                new Object[] {PimdirSql.version(), PimdirHash.ALGORITHM});
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // Foreign keys are off by default in SQLite and the schema leans on
        // them: bindings cascade with their item, items with their collection.
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        // NOTE: while the pimdir spec is draft, version 1 is edited in place
        // and a store written by an earlier draft is recreated rather than
        // migrated (SPEC.md Status). The store is a cache of a sync, so the
        // cost is one refetch.
        for (String table : new String[] {
            "queue", "bindings", "items", "objects", "sources", "collections", "store_meta"
        }) {
            db.execSQL("DROP TABLE IF EXISTS " + table);
        }
        onCreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int from, int to) {
        onUpgrade(db, from, to);
    }
}
