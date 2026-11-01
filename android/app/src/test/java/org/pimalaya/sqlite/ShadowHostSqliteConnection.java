package org.pimalaya.sqlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/**
 * The platform's {@code SQLiteConnection} natives on Android 8.0's SQLite
 * 3.18.2 ({@link HostSqlite}), in place of Robolectric's own SQLite.
 *
 * <p>Everything above the natives is the platform's: the helper, the
 * connection pool and its readers under write-ahead logging, the sessions and
 * their transactions, the cursors and their windows. Declared for every test
 * by robolectric.properties.
 *
 * <p>Where the platform's natives grew after Android 8.0, the shadow keeps
 * Android 8.0's behaviour, the oldest the app runs on: a pragma run by {@code
 * execSQL} answering a row is refused, functions registered from Java do not
 * exist, and no statement counts as writing only temporary tables.
 */
@Implements(className = "android.database.sqlite.SQLiteConnection", isInAndroidSdk = false)
public class ShadowHostSqliteConnection {
    // NOTE: the platform's open flags (SQLiteDatabase), turned into SQLite's
    // as its own nativeOpen does (frameworks/base,
    // core/jni/android_database_SQLiteConnection.cpp).
    private static final int OPEN_READONLY = 0x00000001;
    private static final int CREATE_IF_NECESSARY = 0x10000000;
    private static final int SQLITE_OPEN_READONLY = 0x00000001;
    private static final int SQLITE_OPEN_READWRITE = 0x00000002;
    private static final int SQLITE_OPEN_CREATE = 0x00000004;

    /** The path of every connection open, by its pointer. */
    private static final Map<Long, String> OPEN = new HashMap<>();

    /**
     * The path of every connection opened beside another to the same file
     * since the test began, in order.
     */
    private static final List<String> BESIDE = new ArrayList<>();

    /**
     * How many connections to {@code path} were opened beside another since
     * the test began: a pool's readers, which it opens beside its writer.
     * Counted as opened, not as open: a pool closes a reader it no longer
     * needs, and the platform reopens its writer when logging turns on.
     */
    public static synchronized int openedBeside(String path) {
        int opened = 0;
        for (String each : BESIDE) {
            if (each.equals(path)) {
                opened++;
            }
        }
        return opened;
    }

    /**
     * The journal mode every statement setting one asked, with the path of
     * its connection, in order since the test began.
     */
    private static final List<String[]> JOURNAL_MODES = new ArrayList<>();

    /** How a statement setting the journal mode reads, the mode its group. */
    private static final java.util.regex.Pattern JOURNAL_MODE =
            java.util.regex.Pattern.compile(
                    "(?is)^\\s*PRAGMA\\s+(?:\\w+\\.)?journal_mode\\s*=\\s*'?(\\w+)'?.*");

    /**
     * Every journal mode a statement on a connection to {@code path} set
     * since the test began (or since {@link #reset}), upper-cased, in order:
     * the platform's own {@code PRAGMA journal_mode=…} as it configures a
     * connection, the store's and the helper's.
     */
    public static synchronized List<String> journalModesSet(String path) {
        List<String> modes = new ArrayList<>();
        for (String[] set : JOURNAL_MODES) {
            if (set[0].equals(path)) {
                modes.add(set[1]);
            }
        }
        return modes;
    }

    @Resetter
    public static synchronized void reset() {
        OPEN.clear();
        BESIDE.clear();
        JOURNAL_MODES.clear();
    }

    private static int sqliteFlags(int openFlags) {
        if ((openFlags & CREATE_IF_NECESSARY) != 0) {
            return SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE;
        }
        if ((openFlags & OPEN_READONLY) != 0) {
            return SQLITE_OPEN_READONLY;
        }
        return SQLITE_OPEN_READWRITE;
    }

    @Implementation
    protected static long nativeOpen(
            String path, int openFlags, String label, boolean enableTrace, boolean enableProfile) {
        long connection =
                HostSqlite.nativeOpen(
                        path, sqliteFlags(openFlags), label, enableTrace, enableProfile);
        synchronized (ShadowHostSqliteConnection.class) {
            if (OPEN.containsValue(path)) {
                BESIDE.add(path);
            }
            OPEN.put(connection, path);
        }
        return connection;
    }

    /** The lookaside sizes only tune memory, and are left to SQLite. */
    @Implementation
    protected static long nativeOpen(
            String path,
            int openFlags,
            String label,
            boolean enableTrace,
            boolean enableProfile,
            int lookasideSlotSize,
            int lookasideSlotCount) {
        return nativeOpen(path, openFlags, label, enableTrace, enableProfile);
    }

    @Implementation
    protected static void nativeClose(long connection) {
        HostSqlite.nativeClose(connection);
        synchronized (ShadowHostSqliteConnection.class) {
            OPEN.remove(connection);
        }
    }

    @Implementation
    protected static void nativeClose(long connection, boolean fast) {
        nativeClose(connection);
    }

    @Implementation
    protected static void nativeRegisterCustomScalarFunction(
            long connection, String name, UnaryOperator<String> function) {
        throw new UnsupportedOperationException(
                "SQL functions registered from Java exist from API 30 only");
    }

    @Implementation
    protected static void nativeRegisterCustomAggregateFunction(
            long connection, String name, BinaryOperator<String> function) {
        throw new UnsupportedOperationException(
                "SQL functions registered from Java exist from API 30 only");
    }

    @Implementation
    protected static void nativeRegisterLocalizedCollators(long connection, String locale) {
        HostSqlite.nativeRegisterLocalizedCollators(connection, locale);
    }

    @Implementation
    protected static long nativePrepareStatement(long connection, String sql) {
        java.util.regex.Matcher mode = JOURNAL_MODE.matcher(sql);
        if (mode.matches()) {
            synchronized (ShadowHostSqliteConnection.class) {
                String path = OPEN.get(connection);
                JOURNAL_MODES.add(
                        new String[] {
                            path == null ? "" : path,
                            mode.group(1).toUpperCase(java.util.Locale.ROOT)
                        });
            }
        }
        return HostSqlite.nativePrepareStatement(connection, sql);
    }

    @Implementation
    protected static void nativeFinalizeStatement(long connection, long statement) {
        HostSqlite.nativeFinalizeStatement(connection, statement);
    }

    @Implementation
    protected static int nativeGetParameterCount(long connection, long statement) {
        return HostSqlite.nativeGetParameterCount(connection, statement);
    }

    @Implementation
    protected static boolean nativeIsReadOnly(long connection, long statement) {
        return HostSqlite.nativeIsReadOnly(connection, statement);
    }

    /** API 35's, which Android 8.0 has no notion of. */
    @Implementation
    protected static boolean nativeUpdatesTempOnly(long connection, long statement) {
        return false;
    }

    @Implementation
    protected static int nativeGetColumnCount(long connection, long statement) {
        return HostSqlite.nativeGetColumnCount(connection, statement);
    }

    @Implementation
    protected static String nativeGetColumnName(long connection, long statement, int index) {
        return HostSqlite.nativeGetColumnName(connection, statement, index);
    }

    @Implementation
    protected static void nativeBindNull(long connection, long statement, int index) {
        HostSqlite.nativeBindNull(connection, statement, index);
    }

    @Implementation
    protected static void nativeBindLong(long connection, long statement, int index, long value) {
        HostSqlite.nativeBindLong(connection, statement, index, value);
    }

    @Implementation
    protected static void nativeBindDouble(
            long connection, long statement, int index, double value) {
        HostSqlite.nativeBindDouble(connection, statement, index, value);
    }

    @Implementation
    protected static void nativeBindString(
            long connection, long statement, int index, String value) {
        HostSqlite.nativeBindString(connection, statement, index, value);
    }

    @Implementation
    protected static void nativeBindBlob(
            long connection, long statement, int index, byte[] value) {
        HostSqlite.nativeBindBlob(connection, statement, index, value);
    }

    @Implementation
    protected static void nativeResetStatementAndClearBindings(long connection, long statement) {
        HostSqlite.nativeResetStatementAndClearBindings(connection, statement);
    }

    @Implementation
    protected static void nativeExecute(long connection, long statement) {
        HostSqlite.nativeExecute(connection, statement);
    }

    /**
     * Android 14's signature; a pragma answering a row stays refused, as on
     * Android 8.0.
     */
    @Implementation
    protected static void nativeExecute(long connection, long statement, boolean isPragmaStmt) {
        HostSqlite.nativeExecute(connection, statement);
    }

    @Implementation
    protected static long nativeExecuteForLong(long connection, long statement) {
        return HostSqlite.nativeExecuteForLong(connection, statement);
    }

    @Implementation
    protected static String nativeExecuteForString(long connection, long statement) {
        return HostSqlite.nativeExecuteForString(connection, statement);
    }

    @Implementation
    protected static int nativeExecuteForBlobFileDescriptor(long connection, long statement) {
        return HostSqlite.nativeExecuteForBlobFileDescriptor(connection, statement);
    }

    @Implementation
    protected static int nativeExecuteForChangedRowCount(long connection, long statement) {
        return HostSqlite.nativeExecuteForChangedRowCount(connection, statement);
    }

    @Implementation
    protected static long nativeExecuteForLastInsertedRowId(long connection, long statement) {
        return HostSqlite.nativeExecuteForLastInsertedRowId(connection, statement);
    }

    @Implementation
    protected static long nativeExecuteForCursorWindow(
            long connection,
            long statement,
            long window,
            int startPos,
            int requiredPos,
            boolean countAllRows) {
        return HostSqlite.nativeExecuteForCursorWindow(
                connection, statement, window, startPos, requiredPos, countAllRows);
    }

    @Implementation
    protected static int nativeGetDbLookaside(long connection) {
        return HostSqlite.nativeGetDbLookaside(connection);
    }

    @Implementation
    protected static void nativeCancel(long connection) {
        HostSqlite.nativeCancel(connection);
    }

    @Implementation
    protected static void nativeResetCancel(long connection, boolean cancelable) {
        HostSqlite.nativeResetCancel(connection, cancelable);
    }

    @Implementation
    protected static int nativeLastInsertRowId(long connection) {
        return (int) HostSqlite.nativeLastInsertRowId(connection);
    }

    @Implementation
    protected static long nativeChanges(long connection) {
        return HostSqlite.nativeChanges(connection);
    }

    @Implementation
    protected static long nativeTotalChanges(long connection) {
        return HostSqlite.nativeTotalChanges(connection);
    }
}
