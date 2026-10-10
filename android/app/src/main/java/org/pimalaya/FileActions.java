package org.pimalaya;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * What can be done with one file, wherever it is shown: open it, share it,
 * save it to a folder, export it. The reader's attachments and the Files tab
 * both come here, each saying where the bytes are read from.
 *
 * <p>Every read and write runs on the io executor, the outcome said in a
 * toast.
 */
final class FileActions {
    /** Where one file's bytes are read from: its body, or its message's. */
    interface Bytes {
        byte[] read() throws Exception;
    }

    private final MainActivity host;

    /** The file an export waits on the document picker for. */
    private FileStore.StoredFile exporting;

    private Bytes exportingBytes;

    FileActions(MainActivity host) {
        this.host = host;
    }

    /** A file's name, or what stands for none. */
    String label(FileStore.StoredFile file) {
        return file.name.isEmpty()
                ? host.getString(R.string.message_attachment_unnamed)
                : file.name;
    }

    /** The media type a file is handed over as. */
    static String typeOf(FileStore.StoredFile file) {
        return file.mediaType == null ? PimdirSummary.FILE : file.mediaType;
    }

    /**
     * Opens a file in the app the phone picks for its type, through a copy
     * in the cache ({@link OpenedFiles}).
     */
    void open(FileStore.StoredFile file, Bytes bytes) {
        handOver(
                file,
                bytes,
                uri ->
                        new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, typeOf(file))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    /** Shares a file with the app the user picks. */
    void share(FileStore.StoredFile file, Bytes bytes) {
        handOver(
                file,
                bytes,
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
            FileStore.StoredFile file,
            Bytes bytes,
            java.util.function.Function<Uri, Intent> intent) {
        host.io.execute(
                () -> {
                    Uri uri;
                    try {
                        uri = OpenedFiles.put(host, file.seq, label(file), bytes.read());
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

    /** Asks for a destination document, then writes the file there ({@link #exported}). */
    void export(FileStore.StoredFile file, Bytes bytes) {
        exporting = file;
        exportingBytes = bytes;
        host.startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(typeOf(file))
                        .putExtra(Intent.EXTRA_TITLE, label(file)),
                MainActivity.REQUEST_FILE_EXPORT);
    }

    /** Writes the file an export waited on into the document picked. */
    void exported(Uri target) {
        FileStore.StoredFile file = exporting;
        Bytes bytes = exportingBytes;
        exporting = null;
        exportingBytes = null;
        if (file == null) {
            return;
        }
        host.io.execute(
                () -> {
                    try (OutputStream out = host.getContentResolver().openOutputStream(target)) {
                        out.write(bytes.read());
                    } catch (Exception error) {
                        failed(file, error);
                        return;
                    }
                    host.postAlive(() -> host.toast(host.getString(R.string.files_exported)));
                });
    }

    /** Asks which folder to save a file into, or to create one. */
    void saveToFolder(FileStore.StoredFile file, Bytes bytes) {
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
                                                        save(file, bytes, folder.id, folder.name);
                                                    } else {
                                                        askName(
                                                                R.string.attachment_new_folder,
                                                                R.string.attachment_create,
                                                                "",
                                                                name -> save(file, bytes, null, name));
                                                    }
                                                })
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                            });
                });
    }

    /**
     * Saves a file into a folder, creating it first when no id is given, and
     * says where it went.
     */
    private void save(FileStore.StoredFile file, Bytes bytes, String folder, String name) {
        host.io.execute(
                () -> {
                    try {
                        byte[] read = bytes.read();
                        String target = folder == null ? host.files.createFolder(name) : folder;
                        host.files.save(target, file, read);
                    } catch (Exception error) {
                        failed(file, error);
                        return;
                    }
                    host.postAlive(
                            () -> {
                                host.toast(host.getString(R.string.attachment_saved, name));
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
