package org.pimalaya;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What can be done with one file, wherever it is shown: open it, share it,
 * save it to a folder, export it. The reader's attachments and the Files tab
 * both come here, each saying where the file is read from.
 *
 * <p>A file travels as a file, never as an array: a blob, or the part a
 * message holds written out by the bridge, copied a buffer at a time, so an
 * attachment of any size opens. Every read and write runs on the io
 * executor, the outcome said in a toast.
 */
final class FileActions {
    /** Where one file's bytes are: its body's blob, or its message's part written out. */
    interface Source {
        File read() throws Exception;
    }

    /** The keys an export waiting on the document picker survives a recreation under. */
    private static final String EXPORT_PATH = "files.export.path";

    private static final String EXPORT_NAME = "files.export.name";

    private final MainActivity host;

    /** The file an export waits on the document picker for, null for none. */
    private String exportPath;

    private String exportName;

    FileActions(MainActivity host) {
        this.host = host;
    }

    /** Keeps a waiting export across the activity's recreation. */
    void save(Bundle state) {
        state.putString(EXPORT_PATH, exportPath);
        state.putString(EXPORT_NAME, exportName);
    }

    void restore(Bundle state) {
        exportPath = state.getString(EXPORT_PATH);
        exportName = state.getString(EXPORT_NAME);
    }

    /** A file's name, or what stands for none. */
    String label(FileStore.StoredFile file) {
        return file.name.isEmpty()
                ? host.getString(R.string.message_attachment_unnamed)
                : file.name;
    }

    /**
     * The media type a file is handed over as: the one stated, else the one
     * its name's extension says when none, or only the generic one, is.
     */
    static String typeOf(FileStore.StoredFile file) {
        if (file.mediaType != null && !PimdirSummary.FILE.equals(file.mediaType)) {
            return file.mediaType;
        }
        String guessed = OpenedFiles.typeOf(file.name);
        return guessed == null ? PimdirSummary.FILE : guessed;
    }

    /**
     * Opens a file in the app the phone picks for its type, through a copy
     * in the cache ({@link OpenedFiles}).
     */
    void open(FileStore.StoredFile file, Source source) {
        handOver(
                file,
                source,
                uri ->
                        new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, typeOf(file))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    /** Shares a file with the app the user picks. */
    void share(FileStore.StoredFile file, Source source) {
        handOver(
                file,
                source,
                uri ->
                        Intent.createChooser(
                                new Intent(Intent.ACTION_SEND)
                                        .setType(typeOf(file))
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                label(file)));
    }

    /** Copies a file into the cache, then starts what {@code intent} makes of its URI. */
    private void handOver(
            FileStore.StoredFile file, Source source, Function<Uri, Intent> intent) {
        host.io.execute(
                () -> {
                    Uri uri;
                    try {
                        uri = OpenedFiles.put(host, file.seq, label(file), source.read());
                    } catch (Exception error) {
                        failed(file, error);
                        return;
                    }
                    host.postAlive(
                            () -> {
                                try {
                                    host.startActivity(intent.apply(uri));
                                } catch (ActivityNotFoundException none) {
                                    host.toast(host.getString(R.string.attachment_no_app));
                                }
                            });
                });
    }

    /**
     * Reads the file first, then asks for a destination document, and writes
     * it there once picked ({@link #exported}).
     */
    void export(FileStore.StoredFile file, Source source) {
        host.io.execute(
                () -> {
                    File read;
                    try {
                        read = source.read();
                    } catch (Exception error) {
                        failed(file, error);
                        return;
                    }
                    host.postAlive(
                            () -> {
                                exportPath = read.getAbsolutePath();
                                exportName = label(file);
                                host.startActivityForResult(
                                        new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                                .addCategory(Intent.CATEGORY_OPENABLE)
                                                .setType(typeOf(file))
                                                .putExtra(Intent.EXTRA_TITLE, exportName),
                                        MainActivity.REQUEST_FILE_EXPORT);
                            });
                });
    }

    /**
     * Writes the file an export waited on into the document picked; a
     * document left half written, or with nothing to write, is deleted.
     */
    void exported(Uri target) {
        String path = exportPath;
        exportPath = null;
        exportName = null;
        host.io.execute(
                () -> {
                    try {
                        if (path == null) {
                            throw new IllegalStateException(
                                    host.getString(R.string.attachment_failed));
                        }
                        try (InputStream in = new FileInputStream(path);
                                OutputStream out =
                                        host.getContentResolver().openOutputStream(target)) {
                            OpenedFiles.copy(in, out);
                        }
                    } catch (Exception error) {
                        Log.w("pimalaya", "file not exported: " + path, error);
                        try {
                            DocumentsContract.deleteDocument(host.getContentResolver(), target);
                        } catch (Exception ignored) {
                            // NOTE: a provider refusing the delete leaves the
                            // empty document, which the toast explains.
                        }
                        host.postAlive(
                                () -> host.toast(host.message(error, R.string.attachment_failed)));
                        return;
                    }
                    host.postAlive(() -> host.toast(host.getString(R.string.files_exported)));
                });
    }

    /** Asks which folder to save a file into, or to create one. */
    void saveToFolder(FileStore.StoredFile file, Source source) {
        host.io.execute(
                () -> {
                    List<PimdirCollections.Stored> folders = host.files.folders();
                    host.postAlive(
                            () -> {
                                CharSequence[] labels = new CharSequence[folders.size() + 1];
                                for (int index = 0; index < folders.size(); index++) {
                                    labels[index] = folders.get(index).name;
                                }
                                labels[folders.size()] =
                                        host.getString(R.string.attachment_new_folder);
                                new AlertDialog.Builder(host)
                                        .setTitle(R.string.attachment_save)
                                        .setItems(
                                                labels,
                                                (dialog, which) -> {
                                                    if (which < folders.size()) {
                                                        PimdirCollections.Stored folder =
                                                                folders.get(which);
                                                        save(file, source, folder.id, folder.name);
                                                    } else {
                                                        askName(
                                                                R.string.attachment_new_folder,
                                                                R.string.attachment_create,
                                                                "",
                                                                name ->
                                                                        save(file, source, null,
                                                                                name));
                                                    }
                                                })
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                            });
                });
    }

    /**
     * Saves a file into a folder, creating it first when no id is given, and
     * says where it went, or that it was there already.
     */
    private void save(FileStore.StoredFile file, Source source, String folder, String name) {
        host.io.execute(
                () -> {
                    int said;
                    try {
                        String target = folder == null ? host.files.createFolder(name) : folder;
                        if (target == null) {
                            host.postAlive(
                                    () -> host.toast(host.getString(R.string.files_folder_exists)));
                            return;
                        }
                        said =
                                host.files.save(target, file, source.read())
                                        ? R.string.attachment_saved
                                        : R.string.files_already_saved;
                    } catch (Exception error) {
                        failed(file, error);
                        return;
                    }
                    host.postAlive(
                            () -> {
                                host.toast(host.getString(said, name));
                                host.filesList.reload();
                            });
                });
    }

    /** Asks for a folder's name, {@code initial} filled in, handing a non-blank one on. */
    void askName(int title, int positive, String initial, Consumer<String> done) {
        EditText name = new EditText(host);
        name.setHint(R.string.attachment_folder_name);
        name.setSingleLine(true);
        name.setText(initial);
        FrameLayout frame = new FrameLayout(host);
        frame.setPadding(host.dp(24), host.dp(8), host.dp(24), 0);
        frame.addView(name);
        new AlertDialog.Builder(host)
                .setTitle(title)
                .setView(frame)
                .setPositiveButton(
                        positive,
                        (dialog, which) -> {
                            String typed = name.getText().toString().trim();
                            if (!typed.isEmpty()) {
                                done.accept(typed);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void failed(FileStore.StoredFile file, Exception error) {
        Log.w("pimalaya", "file not read: " + file.linkId, error);
        host.postAlive(() -> host.toast(host.message(error, R.string.attachment_failed)));
    }
}
