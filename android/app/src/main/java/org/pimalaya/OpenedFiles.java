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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    /** The longest name a copy keeps, its extension included. */
    private static final int LONGEST = 120;

    /**
     * Copies one file into the cache, a buffer at a time, under a directory
     * named after its public id so two files of one name do not meet, and
     * answers the URI that reads it.
     */
    static Uri put(Context context, long seq, String name, File source) throws IOException {
        File directory = new File(new File(context.getCacheDir(), DIRECTORY), Long.toString(seq));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        String safe = safe(name);
        try (InputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(new File(directory, safe))) {
            copy(in, out);
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".files")
                .appendPath(Long.toString(seq))
                .appendPath(safe)
                .build();
    }

    /**
     * A name a file may go by: no separator, no leading dot, and at most
     * {@link #LONGEST} characters, the extension kept when it is cut.
     */
    static String safe(String name) {
        String safe = name.replace('/', '_').replace('\\', '_').trim();
        if (safe.isEmpty() || safe.startsWith(".")) {
            safe = "file" + safe;
        }
        if (safe.length() > LONGEST) {
            String extension = extensionOf(safe);
            String tail = extension.isEmpty() ? "" : "." + extension;
            safe = tail.length() >= LONGEST
                    ? safe.substring(0, LONGEST)
                    : safe.substring(0, LONGEST - tail.length()) + tail;
        }
        return safe;
    }

    /** The extension of a name, after its last dot, lowercased; empty for none. */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The media type a name's extension says, null when it says none. */
    static String typeOf(String name) {
        String extension = extensionOf(name);
        return extension.isEmpty()
                ? null
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }

    /** Copies a stream to another a buffer at a time. */
    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
        }
    }

    /** Where the bridge writes one attachment out of its message. */
    static File partFile(Context context, long seq) {
        File parts = new File(new File(context.getCacheDir(), DIRECTORY), "parts");
        if (!parts.isDirectory()) {
            parts.mkdirs();
        }
        return new File(parts, Long.toString(seq));
    }

    /** Drops every copy, on start and when an account goes. */
    static void clear(Context context) {
        delete(new File(context.getCacheDir(), DIRECTORY));
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
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
        String name = uri.getLastPathSegment();
        String type = name == null ? null : typeOf(name);
        return type == null ? PimdirSummary.FILE : type;
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
