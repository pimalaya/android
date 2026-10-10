package org.pimalaya;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Files screen: one card per file collection the filter shows, the
 * device's folders first, then each mail account's attachments.
 *
 * <p>Read off the store on the io executor: an account's attachments in one
 * statement with their messages, newest first, a folder's files A to Z.
 */
final class FilesList {
    /** The kind chips: folders, attachments. */
    private static final int FOLDERS = 0;

    private static final int ATTACHMENTS = 1;

    /** The media type chips: images, PDFs, documents. */
    static final int IMAGES = 0;

    static final int PDFS = 1;

    static final int DOCUMENTS = 2;

    private final MainActivity host;
    private final Adapter adapter = new Adapter();
    private final CardSections<Row> sections = new CardSections<>();

    /** What tapping a folder's header does, by the header's label. */
    private final Map<String, PimdirCollections.Stored> folderHeaders = new HashMap<>();

    /** Every row the filter lets through, before the chips and the search. */
    private List<Row> rows = new ArrayList<>();

    private ListHeader header;

    /** The kind chip on, -1 for none. */
    private int kind = -1;

    /** The media type chip on, -1 for none. */
    private int type = -1;

    /** The search, lowercased, empty for none. */
    private String query = "";

    /** The folder an import waits on the document picker for. */
    private PimdirCollections.Stored importing;

    FilesList(MainActivity host) {
        this.host = host;
    }

    /** One row: a file in its collection, or the placeholder of an empty folder. */
    private static final class Row {
        final PimdirCollections.Stored collection;

        /** The card it sits in: the folder's name, or an account's attachments. */
        final String section;

        /** Null for an empty folder's placeholder. */
        final FileStore.StoredFile file;

        /** The message it came from, null when none references it. */
        final FileStore.Origin origin;

        Row(
                PimdirCollections.Stored collection,
                String section,
                FileStore.StoredFile file,
                FileStore.Origin origin) {
            this.collection = collection;
            this.section = section;
            this.file = file;
            this.origin = origin;
        }

        boolean attachment() {
            return FileStore.isAttachments(collection);
        }
    }

    void setUp() {
        ListView list = host.findViewById(R.id.files_list);
        header = new ListHeader(host, list, MainActivity.PANEL_FILES);
        header.empty(host.findViewById(R.id.files_empty));
        header.search(
                R.string.files_search,
                words -> {
                    query = words;
                    render();
                });
        Chips.exclusive(
                host,
                header.chips(),
                new int[] {R.string.chip_folders, R.string.chip_attachments},
                new int[] {R.drawable.ic_folder_open, R.drawable.ic_attach_file},
                picked -> {
                    kind = picked;
                    render();
                });
        Chips.exclusive(
                host,
                header.chips(),
                new int[] {R.string.chip_images, R.string.chip_pdfs, R.string.chip_documents},
                new int[] {
                    R.drawable.ic_visibility, R.drawable.ic_component_journal,
                    R.drawable.ic_section_notes
                },
                picked -> {
                    type = picked;
                    render();
                });
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    int at = header.rowAt(position);
                    if (sections.isHeader(at)) {
                        PimdirCollections.Stored folder = folderHeaders.get(sections.header(at));
                        if (folder != null) {
                            folderMenu(folder);
                        }
                        return;
                    }
                    Row row = sections.row(at);
                    if (row == null) {
                        return;
                    }
                    if (row.file == null) {
                        folderMenu(row.collection);
                    } else {
                        fileMenu(row);
                    }
                });
    }

    ListHeader header() {
        return header;
    }

    /** Reads every shown collection's files off the store, then renders them. */
    void reload() {
        MergedFilter filter = host.filesFilter();
        host.io.execute(
                () -> {
                    List<Row> read = new ArrayList<>();
                    for (PimdirCollections.Stored folder : host.files.folders()) {
                        if (filter.accepts(folder.accountEmail, folder.id)) {
                            read.addAll(rowsOf(folder, folder.name));
                        }
                    }
                    for (PimdirCollections.Stored held : host.files.attachmentCollections()) {
                        if (filter.accepts(held.accountEmail, held.id)) {
                            read.addAll(
                                    rowsOf(
                                            held,
                                            host.getString(
                                                    R.string.files_attachments_of,
                                                    held.accountEmail)));
                        }
                    }
                    host.postAlive(
                            () -> {
                                rows = read;
                                render();
                            });
                });
    }

    /** One collection's rows, an empty folder standing as its placeholder. */
    private List<Row> rowsOf(PimdirCollections.Stored collection, String section) {
        List<Row> rows = new ArrayList<>();
        if (FileStore.isAttachments(collection)) {
            for (FileStore.Attached attached : host.files.attachmentsIn(collection.id)) {
                rows.add(new Row(collection, section, attached.file, attached.origin));
            }
            return rows;
        }
        for (FileStore.StoredFile file : host.files.files(collection.id)) {
            rows.add(new Row(collection, section, file, host.files.origin(file.linkId)));
        }
        if (rows.isEmpty()) {
            rows.add(new Row(collection, section, null, null));
        }
        return rows;
    }

    /** Lays out what the chips and the search let through. */
    private void render() {
        List<Row> shown = new ArrayList<>();
        int files = 0;
        folderHeaders.clear();
        for (Row row : rows) {
            if (!row.attachment()) {
                folderHeaders.put(row.section, row.collection);
            }
            if (kind == FOLDERS && row.attachment() || kind == ATTACHMENTS && !row.attachment()) {
                continue;
            }
            if (row.file == null) {
                if (type == -1 && query.isEmpty()) {
                    shown.add(row);
                }
                continue;
            }
            if (type != -1 && !matches(type, row.file.mediaType)) {
                continue;
            }
            if (!query.isEmpty() && !searched(row, query)) {
                continue;
            }
            shown.add(row);
            files++;
        }
        sections.fill(shown, row -> row.section);
        adapter.notifyDataSetChanged();
        header.meta(host.getResources().getQuantityString(R.plurals.files_meta, files, files));
        host.findViewById(R.id.files_empty)
                .setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Whether a media type falls under one of the type chips. */
    static boolean matches(int type, String mediaType) {
        String media = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        switch (type) {
            case IMAGES:
                return media.startsWith("image/");
            case PDFS:
                return media.equals("application/pdf");
            default:
                return media.startsWith("text/")
                        || media.equals("application/msword")
                        || media.equals("application/rtf")
                        || media.startsWith("application/vnd.ms-")
                        || media.startsWith("application/vnd.openxmlformats-officedocument.")
                        || media.startsWith("application/vnd.oasis.opendocument.");
        }
    }

    /** Whether the search finds a row by its file name or its sender. */
    private static boolean searched(Row row, String query) {
        if (row.file.name.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return row.origin != null
                && (row.origin.sender.toLowerCase(Locale.ROOT).contains(query)
                        || row.origin.senderName.toLowerCase(Locale.ROOT).contains(query));
    }

    /**
     * Creates a folder under a name the user gives, unless a folder already
     * goes by it: one card per name, so two folders of one name would read as
     * one.
     */
    void newFolder() {
        host.fileActions.askName(
                R.string.attachment_new_folder,
                R.string.attachment_create,
                "",
                name ->
                        host.io.execute(
                                () -> {
                                    String created = host.files.createFolder(name);
                                    host.postAlive(
                                            () -> {
                                                if (created == null) {
                                                    host.toast(
                                                            host.getString(
                                                                    R.string.files_folder_exists));
                                                }
                                                reload();
                                            });
                                }));
    }

    /**
     * Opens a file's sheet from elsewhere (a link), read off the store with
     * the message it came from.
     */
    void openFile(String collection, String linkId) {
        host.io.execute(
                () -> {
                    Row found = null;
                    for (FileStore.StoredFile file : host.files.files(collection)) {
                        if (file.linkId.equals(linkId)) {
                            PimdirCollections.Stored held = collectionOf(collection);
                            found =
                                    held == null
                                            ? null
                                            : new Row(
                                                    held,
                                                    held.name,
                                                    file,
                                                    host.files.origin(linkId));
                        }
                    }
                    Row row = found;
                    host.postAlive(
                            () -> {
                                if (row == null) {
                                    host.toast(host.getString(R.string.linked_gone));
                                } else {
                                    fileMenu(row);
                                }
                            });
                });
    }

    /** A file collection by id, folder or attachments; null when gone. */
    private PimdirCollections.Stored collectionOf(String id) {
        List<PimdirCollections.Stored> all = new ArrayList<>(host.files.folders());
        all.addAll(host.files.attachmentCollections());
        for (PimdirCollections.Stored collection : all) {
            if (collection.id.equals(id)) {
                return collection;
            }
        }
        return null;
    }

    /** What a file offers: open, share, save, export, its message, its links, delete. */
    private void fileMenu(Row row) {
        FileStore.StoredFile file = row.file;
        FileActions.Source bytes = () -> fileOf(row);
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(host.getString(R.string.attachment_open));
        actions.add(() -> host.fileActions.open(file, bytes));
        labels.add(host.getString(R.string.files_share));
        actions.add(() -> host.fileActions.share(file, bytes));
        labels.add(host.getString(R.string.attachment_save));
        actions.add(() -> host.fileActions.saveToFolder(file, bytes));
        labels.add(host.getString(R.string.files_export));
        actions.add(() -> host.fileActions.export(file, bytes));
        if (row.origin != null) {
            labels.add(host.getString(R.string.files_show_message));
            actions.add(() -> showMessage(row.origin));
        }
        labels.add(host.getString(R.string.linked_title));
        actions.add(
                () -> host.linked.dialog(ItemLinks.ofFile(file), host.fileActions.label(file)));
        if (!row.attachment()) {
            labels.add(host.getString(R.string.files_delete));
            actions.add(() -> confirmDelete(file));
        }
        new AlertDialog.Builder(host)
                .setTitle(host.fileActions.label(file))
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (dialog, which) -> actions.get(which).run())
                .show();
    }

    /**
     * The file a row is read from: its own body's blob, or for a stand-in
     * the part its message holds written out, the message fetched when the
     * phone does not hold it.
     */
    private java.io.File fileOf(Row row) {
        java.io.File saved = host.files.saved(row.file);
        if (saved != null) {
            return saved;
        }
        MailStore.StoredMessage message =
                row.origin == null
                        ? null
                        : host.mail.message(row.origin.collection, row.origin.linkId);
        if (message == null || row.file.part == null) {
            throw new IllegalStateException(host.getString(R.string.files_no_message));
        }
        return host.messageView.fileOf(message, row.file);
    }

    /** Opens the message a file came from in the reader. */
    private void showMessage(FileStore.Origin origin) {
        host.io.execute(
                () -> {
                    MailStore.StoredMessage message =
                            host.mail.message(origin.collection, origin.linkId);
                    host.postAlive(
                            () -> {
                                if (message == null) {
                                    host.toast(host.getString(R.string.files_no_message));
                                } else {
                                    host.messageView.open(message);
                                }
                            });
                });
    }

    /** Deletes a folder's copy of a file, once confirmed. */
    private void confirmDelete(FileStore.StoredFile file) {
        new AlertDialog.Builder(host)
                .setMessage(
                        host.getString(R.string.files_delete_confirm, host.fileActions.label(file)))
                .setPositiveButton(
                        R.string.files_delete,
                        (dialog, which) ->
                                host.io.execute(
                                        () -> {
                                            host.files.delete(file);
                                            host.postAlive(this::reload);
                                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** What a folder offers: import a file into it, rename it, delete it. */
    private void folderMenu(PimdirCollections.Stored folder) {
        CharSequence[] labels = {
            host.getString(R.string.files_import),
            host.getString(R.string.files_rename),
            host.getString(R.string.files_delete_folder)
        };
        new AlertDialog.Builder(host)
                .setTitle(folder.name)
                .setItems(
                        labels,
                        (dialog, which) -> {
                            if (which == 0) {
                                startImport(folder);
                            } else if (which == 1) {
                                rename(folder);
                            } else {
                                confirmDeleteFolder(folder);
                            }
                        })
                .show();
    }

    private void rename(PimdirCollections.Stored folder) {
        host.fileActions.askName(
                R.string.files_rename,
                R.string.files_rename,
                folder.name,
                name -> {
                    if (name.equals(folder.name)) {
                        return;
                    }
                    host.io.execute(
                            () -> {
                                boolean renamed = host.files.renameFolder(folder.id, name);
                                host.postAlive(
                                        () -> {
                                            if (!renamed) {
                                                host.toast(
                                                        host.getString(
                                                                R.string.files_folder_exists));
                                            }
                                            reload();
                                        });
                            });
                });
    }

    private void confirmDeleteFolder(PimdirCollections.Stored folder) {
        new AlertDialog.Builder(host)
                .setMessage(host.getString(R.string.files_delete_folder_confirm, folder.name))
                .setPositiveButton(
                        R.string.files_delete,
                        (dialog, which) ->
                                host.io.execute(
                                        () -> {
                                            host.files.deleteFolder(folder.id);
                                            host.postAlive(this::reload);
                                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The key the folder an import waits on survives a recreation under. */
    private static final String IMPORT_FOLDER = "files.import.folder";

    private static final String IMPORT_NAME = "files.import.name";

    /** Keeps a waiting import across the activity's recreation. */
    void save(android.os.Bundle state) {
        if (importing != null) {
            state.putString(IMPORT_FOLDER, importing.id);
            state.putString(IMPORT_NAME, importing.name);
        }
    }

    void restore(android.os.Bundle state) {
        String id = state.getString(IMPORT_FOLDER);
        if (id != null) {
            importing =
                    new PimdirCollections.Stored(
                            id, LocalBook.ACCOUNT, state.getString(IMPORT_NAME), null, null);
        }
    }

    /** Asks for a document to import into a folder ({@link #imported}). */
    private void startImport(PimdirCollections.Stored folder) {
        importing = folder;
        host.startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                MainActivity.REQUEST_FILE_IMPORT);
    }

    /**
     * Imports the document picked into the folder that asked for it, copied
     * into the cache a buffer at a time, then hashed and filed from there.
     */
    void imported(Uri source) {
        PimdirCollections.Stored folder = importing;
        importing = null;
        if (folder == null) {
            return;
        }
        host.io.execute(
                () -> {
                    java.io.File staged = null;
                    try {
                        staged = java.io.File.createTempFile("import", null, host.getCacheDir());
                        try (InputStream in = host.getContentResolver().openInputStream(source);
                                java.io.OutputStream out = new java.io.FileOutputStream(staged)) {
                            if (in == null) {
                                throw new java.io.IOException("Unreadable " + source);
                            }
                            OpenedFiles.copy(in, out);
                        }
                        host.files.importFile(
                                folder.id,
                                displayName(source),
                                host.getContentResolver().getType(source),
                                staged);
                    } catch (Exception error) {
                        Log.w("pimalaya", "file not imported: " + source, error);
                        host.postAlive(
                                () -> host.toast(host.message(error, R.string.attachment_failed)));
                        return;
                    } finally {
                        if (staged != null) {
                            staged.delete();
                        }
                    }
                    host.postAlive(
                            () -> {
                                host.toast(host.getString(R.string.files_imported, folder.name));
                                reload();
                            });
                });
    }

    /** The name a document goes by, empty when its provider says none. */
    private String displayName(Uri source) {
        try (Cursor cursor =
                host.getContentResolver()
                        .query(source, new String[] {OpenableColumns.DISPLAY_NAME}, null, null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        }
        String last = source.getLastPathSegment();
        return last == null ? "" : last;
    }

    /** A byte count in the largest unit that keeps it under a thousand. */
    private String size(Long bytes) {
        if (bytes == null) {
            return "";
        }
        if (bytes < 1024) {
            return host.getString(R.string.size_bytes, bytes);
        }
        if (bytes < 1024 * 1024) {
            return host.getString(R.string.size_kilobytes, bytes / 1024);
        }
        return host.getString(R.string.size_megabytes, bytes / (1024 * 1024));
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return sections.size();
        }

        @Override
        public Object getItem(int position) {
            return sections.row(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return sections.isHeader(position) ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int position) {
            return !sections.isHeader(position)
                    || folderHeaders.containsKey(sections.header(position));
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            if (sections.isHeader(position)) {
                return sections.headerView(position, recycled, parent);
            }

            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_event, parent, false);
            }
            sections.shape(view, position);
            view.findViewById(R.id.event_divider)
                    .setVisibility(sections.opensCard(position) ? View.GONE : View.VISIBLE);
            view.findViewById(R.id.event_conflicted).setVisibility(View.GONE);
            view.findViewById(R.id.event_unsynced).setVisibility(View.GONE);

            Row row = sections.row(position);
            TextView avatar = view.findViewById(R.id.event_avatar);
            TextView summary = view.findViewById(R.id.event_summary);
            TextView start = view.findViewById(R.id.event_start);
            TextView kind = view.findViewById(R.id.event_kind);
            TextView origin = view.findViewById(R.id.event_origin);
            if (row.file == null) {
                avatar.setText("");
                avatar.setBackground(Avatar.circle(host, row.collection.id));
                summary.setText(R.string.files_folder_empty);
                start.setText("");
                kind.setText("");
                origin.setText(row.collection.name);
                return view;
            }

            String name = host.fileActions.label(row.file);
            avatar.setText(Avatar.letter(name));
            avatar.setBackground(Avatar.circle(host, row.file.linkId));
            summary.setText(name);
            start.setText(size(row.file.size));
            String type =
                    row.file.mediaType == null
                            ? host.getString(R.string.files_file)
                            : row.file.mediaType;
            kind.setText(type);
            origin.setText(
                    row.origin == null
                            ? host.files.label(row.collection)
                            : host.getString(
                                    R.string.files_from,
                                    row.origin.senderName.isEmpty()
                                            ? row.origin.sender
                                            : row.origin.senderName));
            return view;
        }
    }
}
