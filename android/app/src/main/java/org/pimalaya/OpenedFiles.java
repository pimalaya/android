package org.pimalaya;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import org.pimalaya.file.OpenedCopies;
import org.pimalaya.pimdir.PimdirSummary;

/**
 * Hands a file the app opens to the app that shows it, read-only.
 *
 * <p>The bytes are copied into the cache under the file's name and read back
 * through a granted {@code content://} URI. Its own provider, not AndroidX's
 * {@code FileProvider}: no library for one directory; each open copies afresh.
 */
public final class OpenedFiles extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(
                OpenedCopies.fileOf(getContext(), uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        String type = name == null ? null : OpenedCopies.typeOf(name);
        return type == null ? PimdirSummary.FILE : type;
    }

    /** The name and size an opener asks to title what it shows. */
    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        File file;
        try {
            file = OpenedCopies.fileOf(getContext(), uri);
        } catch (FileNotFoundException gone) {
            return null;
        }
        String[] columns = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(new Object[] {file.getName(), file.length()});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
