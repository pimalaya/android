package io.requery.android.database.sqlite;

import java.util.ArrayList;
import java.util.List;

/**
 * What the binding's connection pools hold, for the tests: the connection
 * names of every open database, read through the binding's own statistics,
 * which it keeps to its package.
 */
public final class PoolProbe {
    private PoolProbe() {}

    /** The name of every open connection to {@code path}, writer first. */
    public static List<String> connections(String path) {
        List<String> names = new ArrayList<>();
        for (SQLiteDebug.DbStats stats : SQLiteDatabase.getDbStats()) {
            if (stats.dbName.startsWith(path)) {
                names.add(stats.dbName);
            }
        }
        return names;
    }
}
