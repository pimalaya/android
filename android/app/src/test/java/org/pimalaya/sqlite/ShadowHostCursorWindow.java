package org.pimalaya.sqlite;

import android.database.CharArrayBuffer;
import android.database.CursorWindow;
import android.os.Parcel;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * The platform's {@code CursorWindow} natives beside {@link
 * ShadowHostSqliteConnection}'s ({@link HostSqlite}): the window a query fills
 * is the one its cursor reads, in the same native layout.
 */
@Implements(CursorWindow.class)
public class ShadowHostCursorWindow {
    @Implementation
    protected static long nativeCreate(String name, int cursorWindowSize) {
        return HostSqlite.nativeCreate(name, cursorWindowSize);
    }

    @Implementation
    protected static long nativeCreateFromParcel(Parcel parcel) {
        throw new UnsupportedOperationException("no window crosses a process in the tests");
    }

    @Implementation
    protected static void nativeDispose(long window) {
        HostSqlite.nativeDispose(window);
    }

    @Implementation
    protected static void nativeWriteToParcel(long window, Parcel parcel) {
        throw new UnsupportedOperationException("no window crosses a process in the tests");
    }

    @Implementation
    protected static String nativeGetName(long window) {
        return HostSqlite.nativeGetName(window);
    }

    @Implementation
    protected static byte[] nativeGetBlob(long window, int row, int column) {
        return HostSqlite.nativeGetBlob(window, row, column);
    }

    @Implementation
    protected static String nativeGetString(long window, int row, int column) {
        return HostSqlite.nativeGetString(window, row, column);
    }

    @Implementation
    protected static void nativeCopyStringToBuffer(
            long window, int row, int column, CharArrayBuffer buffer) {
        HostSqlite.copyStringToBuffer(window, row, column, buffer);
    }

    @Implementation
    protected static boolean nativePutBlob(long window, byte[] value, int row, int column) {
        return HostSqlite.nativePutBlob(window, value, row, column);
    }

    @Implementation
    protected static boolean nativePutString(long window, String value, int row, int column) {
        return HostSqlite.nativePutString(window, value, row, column);
    }

    @Implementation
    protected static void nativeClear(long window) {
        HostSqlite.nativeClear(window);
    }

    @Implementation
    protected static int nativeGetNumRows(long window) {
        return HostSqlite.nativeGetNumRows(window);
    }

    @Implementation
    protected static boolean nativeSetNumColumns(long window, int columnNum) {
        return HostSqlite.nativeSetNumColumns(window, columnNum);
    }

    @Implementation
    protected static boolean nativeAllocRow(long window) {
        return HostSqlite.nativeAllocRow(window);
    }

    @Implementation
    protected static void nativeFreeLastRow(long window) {
        HostSqlite.nativeFreeLastRow(window);
    }

    @Implementation
    protected static int nativeGetType(long window, int row, int column) {
        return HostSqlite.nativeGetType(window, row, column);
    }

    @Implementation
    protected static long nativeGetLong(long window, int row, int column) {
        return HostSqlite.nativeGetLong(window, row, column);
    }

    @Implementation
    protected static double nativeGetDouble(long window, int row, int column) {
        return HostSqlite.nativeGetDouble(window, row, column);
    }

    @Implementation
    protected static boolean nativePutLong(long window, long value, int row, int column) {
        return HostSqlite.nativePutLong(window, value, row, column);
    }

    @Implementation
    protected static boolean nativePutDouble(long window, double value, int row, int column) {
        return HostSqlite.nativePutDouble(window, value, row, column);
    }

    @Implementation
    protected static boolean nativePutNull(long window, int row, int column) {
        return HostSqlite.nativePutNull(window, row, column);
    }
}
