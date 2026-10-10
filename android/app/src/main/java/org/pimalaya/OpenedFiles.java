package org.pimalaya;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Hands a file the app opens to the app that shows it: the bytes are copied
 * into the cache under the file's name, and read back through a
 * {@code content://} URI the opener is granted, read-only.
 *
 * <p>A provider of its own rather than AndroidX's {@code FileProvider}, which
 * would bring a library for one directory. The cache is the platform's to
 * clear, and each open writes its copy afresh.
 */
public final class OpenedFiles extends ContentProvider {
    /** The cache directory the copies are written under. */
    private static final String DIRECTORY = "opened";

    /**
     * Copies one file's bytes into the cache, under a directory named after
     * its public id so two files of one name do not meet, and answers the URI
     * that reads it.
     */
    static Uri put(Context context, long seq, String name, byte[] bytes) throws IOException {
        File directory = new File(new File(context.getCacheDir(), DIRECTORY), Long.toString(seq));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        String safe = name.replace('/', '_').replace('\\', '_').trim();
        if (safe.isEmpty() || safe.startsWith(".")) {
            safe = "file" + safe;
        }
        try (FileOutputStream out = new FileOutputStream(new File(directory, safe))) {
            out.write(bytes);
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".files")
                .appendPath(Long.toString(seq))
                .appendPath(safe)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(uri.getLastPathSegment());
        String type =
                MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }

    /** The name and size an opener asks to title what it shows. */
    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        File file;
        try {
            file = fileOf(uri);
        } catch (FileNotFoundException gone) {
            return null;
        }
        String[] columns = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(new Object[] {file.getName(), file.length()});
        return cursor;
    }

    /**
     * The cached copy a URI names, refused when it is not one: a path that
     * climbs out of the directory reads nothing.
     */
    private File fileOf(Uri uri) throws FileNotFoundException {
        File root = new File(getContext().getCacheDir(), DIRECTORY);
        try {
            File file = new File(root, uri.getPath()).getCanonicalFile();
            if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator)
                    || !file.isFile()) {
                throw new FileNotFoundException(uri.toString());
            }
            return file;
        } catch (IOException error) {
            throw new FileNotFoundException(uri.toString());
        }
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
