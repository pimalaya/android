package org.pimalaya.sqlite;

import android.database.CharArrayBuffer;

/**
 * The natives of the platform's {@code SQLiteConnection} and {@code
 * CursorWindow} on the host, over Android 8.0's SQLite 3.18.2.
 *
 * <p>libsqlitehost.so (flake.nix {@code sqliteHost}, on the test JVM's library
 * path through {@code PIMALAYA_SQLITE_HOST}) registers them on this class:
 * the AOSP JNI the platform runs on a device, as requery's binding carries
 * it. {@link ShadowHostSqliteConnection} and {@link ShadowHostCursorWindow}
 * hand the platform's calls here.
 *
 * <p>Loaded once per JVM, by the one Robolectric sandbox the tests share: a
 * second sandbox (another SDK or another SQLite mode) would fail to load it.
 */
final class HostSqlite {
    static {
        System.loadLibrary("sqlitehost");
    }

    private HostSqlite() {}

    // SQLiteConnection, as requery declares them.

    static native long nativeOpen(
            String path, int openFlags, String label, boolean enableTrace, boolean enableProfile);

    static native void nativeClose(long connection);

    static native void nativeRegisterLocalizedCollators(long connection, String locale);

    static native long nativePrepareStatement(long connection, String sql);

    static native void nativeFinalizeStatement(long connection, long statement);

    static native int nativeGetParameterCount(long connection, long statement);

    static native boolean nativeIsReadOnly(long connection, long statement);

    static native int nativeGetColumnCount(long connection, long statement);

    static native String nativeGetColumnName(long connection, long statement, int index);

    static native void nativeBindNull(long connection, long statement, int index);

    static native void nativeBindLong(long connection, long statement, int index, long value);

    static native void nativeBindDouble(long connection, long statement, int index, double value);

    static native void nativeBindString(long connection, long statement, int index, String value);

    static native void nativeBindBlob(long connection, long statement, int index, byte[] value);

    static native void nativeResetStatementAndClearBindings(long connection, long statement);

    static native void nativeExecute(long connection, long statement);

    static native long nativeExecuteForLong(long connection, long statement);

    static native String nativeExecuteForString(long connection, long statement);

    static native int nativeExecuteForBlobFileDescriptor(long connection, long statement);

    static native int nativeExecuteForChangedRowCount(long connection, long statement);

    static native long nativeExecuteForLastInsertedRowId(long connection, long statement);

    static native long nativeExecuteForCursorWindow(
            long connection,
            long statement,
            long window,
            int startPos,
            int requiredPos,
            boolean countAllRows);

    static native int nativeGetDbLookaside(long connection);

    static native void nativeCancel(long connection);

    static native void nativeResetCancel(long connection, boolean cancelable);

    // SQLiteConnection, the platform's from API 35, written in flake.nix.

    static native long nativeLastInsertRowId(long connection);

    static native long nativeChanges(long connection);

    static native long nativeTotalChanges(long connection);

    // CursorWindow.

    static native long nativeCreate(String name, int cursorWindowSize);

    static native void nativeDispose(long window);

    static native String nativeGetName(long window);

    static native void nativeClear(long window);

    static native int nativeGetNumRows(long window);

    static native boolean nativeSetNumColumns(long window, int columnNum);

    static native boolean nativeAllocRow(long window);

    static native void nativeFreeLastRow(long window);

    static native int nativeGetType(long window, int row, int column);

    static native byte[] nativeGetBlob(long window, int row, int column);

    static native String nativeGetString(long window, int row, int column);

    static native long nativeGetLong(long window, int row, int column);

    static native double nativeGetDouble(long window, int row, int column);

    static native boolean nativePutBlob(long window, byte[] value, int row, int column);

    static native boolean nativePutString(long window, String value, int row, int column);

    static native boolean nativePutLong(long window, long value, int row, int column);

    static native boolean nativePutDouble(long window, double value, int row, int column);

    static native boolean nativePutNull(long window, int row, int column);

    /** {@code nativeCopyStringToBuffer}, which requery's JNI lacks. */
    static void copyStringToBuffer(long window, int row, int column, CharArrayBuffer buffer) {
        String value = nativeGetString(window, row, column);
        if (value == null) {
            buffer.sizeCopied = 0;
            return;
        }
        char[] chars = value.toCharArray();
        if (buffer.data == null || buffer.data.length < chars.length) {
            buffer.data = chars;
        } else {
            System.arraycopy(chars, 0, buffer.data, 0, chars.length);
        }
        buffer.sizeCopied = chars.length;
    }
}
