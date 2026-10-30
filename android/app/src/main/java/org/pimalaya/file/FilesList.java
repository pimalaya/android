package org.pimalaya.file;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.pimalaya.R;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.LocalBook;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.ui.Avatar;
import org.pimalaya.ui.CardSections;
import org.pimalaya.ui.FilterPanel;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Linked;
import org.pimalaya.ui.ListFilter;
import org.pimalaya.ui.ListHeader;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.SectionsAdapter;

/**
 * The Files screen: device folders, then each mail account's attachments.
 *
 * <p>One card per collection the filter shows, read off the store on the io
 * executor: an account's attachments in one statement with their messages,
 * newest first, a folder's files A to Z.
 */
public final class FilesList {
    /** The cache prefix of an import's staged copy ({@code CacheSweep}). */
    public static final String STAGED = "import";

    /** The request code of the import document picker ({@link #imported}). */
    public static final int REQUEST_IMPORT = 5;

    /** The media type chips: images, PDFs, documents. */
    static final int IMAGES = 0;

    static final int PDFS = 1;

    private final Host host;

    private final FileStore files;

    /** What a file offers, also from its sheet here. */
    private final FileActions fileActions;

    /** The reader, which opens a file's message and reads a stand-in's part. */
    private final Origins origins;

    /** The Linked card a file's sheet opens. */
    private final Linked<ItemLinks.Endpoint> linked;

    private final CardSections<Row> sections = new CardSections<>();
    private final Adapter adapter = new Adapter();

    /** What tapping a folder's header does, by the header's label. */
    private final Map<String, PimdirCollections.Stored> folderHeaders = new HashMap<>();

    /** Every row the filter lets through, before the chips and the search. */
    private List<Row> rows = new ArrayList<>();

    private ListHeader header;

    /** The panel's sections: the folders shown, the kind, the media types. */
    private static final String FOLDERS_SHOWN = "folders_on";

    private static final String SHOW = "show";

    private static final String TYPES = "types";

    private static final String[] SHOWS = {"all", "folders", "attachments"};

    private static final int[] SHOW_LABELS = {
        R.string.filter_any, R.string.chip_folders, R.string.chip_attachments,
    };

    private static final String[] TYPE_KEYS = {"images", "pdfs", "documents"};

    private static final int[] TYPE_LABELS = {
        R.string.chip_images, R.string.chip_pdfs, R.string.chip_documents,
    };

    /** What the list keeps, drafted by the panel. */
    private ListFilter filter;

    private FilterPanel panel;

    /** The folders the panel offers, by account then id, as last read. */
    private Map<String, Map<String, String>> offered = new LinkedHashMap<>();

    /** The search, lowercased, empty for none. */
    private String query = "";

    /** The folder an import waits on the document picker for. */
    private PimdirCollections.Stored importing;

    /** Builds the Files screen. */
    public FilesList(
            Host host,
            FileStore files,
            FileActions fileActions,
            Origins origins,
            Linked<ItemLinks.Endpoint> linked) {
        this.host = host;
        this.files = files;
        this.fileActions = fileActions;
        this.origins = origins;
        this.linked = linked;
    }

    /** What a row asks of the message a file came from (the reader answers). */
    public interface Origins {
        /** Opens the message a file came from. */
        void openOrigin(FileStore.Origin origin);

        /** The file a stand-in is read from: its message's part written out. */
        java.io.File fileOf(FileStore.Origin origin, FileStore.StoredFile file);
    }

    /** One row: a file in its collection, or an empty folder's placeholder. */
    private static final class Row {
        final PimdirCollections.Stored collection;

        /** Its card: the folder's name, or an account's attachments. */
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

    /** Wires the list and its header. */
    public void setUp() {
        ListView list = host.findViewById(R.id.files_list);
        header = new ListHeader(host, list, Panels.FILES);
        header.empty(host.findViewById(R.id.files_empty));
        header.search(
                R.string.files_search,
                words -> {
                    query = words;
                    render();
                });
        filter = new ListFilter(host, "files", FOLDERS_SHOWN, SHOW, TYPES);
        panel = new FilterPanel(host, header, this::render);
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

    /** The list's header. */
    public ListHeader header() {
        return header;
    }

    /**
     * Reads every folder's and every account's attachments' files off the
     * store, then renders what the panel lets through.
     */
    public void reload() {
        host.io.execute(
                () -> {
                    List<Row> read = new ArrayList<>();
                    Map<String, Map<String, String>> found = new LinkedHashMap<>();
                    String device = host.getString(R.string.files_on_device);
                    for (PimdirCollections.Stored folder : files.folders()) {
                        found.computeIfAbsent(device, key -> new LinkedHashMap<>())
                                .put(folder.id, folder.name);
                        read.addAll(rowsOf(folder, folder.name));
                    }
                    for (PimdirCollections.Stored held : files.attachmentCollections()) {
                        if (!AccountActivation.enabled(host, held.accountEmail)) {
                            continue;
                        }
                        String label =
                                host.getString(R.string.files_attachments_of, held.accountEmail);
                        found.computeIfAbsent(held.accountEmail, key -> new LinkedHashMap<>())
                                .put(held.id, label);
                        read.addAll(rowsOf(held, label));
                    }
                    host.postAlive(
                            () -> {
                                rows = read;
                                offered = found;
                                render();
                            });
                });
    }

    /** The filter panel: the folders, then the kind, then the media types. */
    private void drawFilters() {
        ListFilter.State edited = filter.applied();
        Runnable changed =
                () -> {
                    filter.save();
                    render();
                };
        panel.begin();
        panel.collections(
                "folders", R.string.filter_folders, offered, edited.set(FOLDERS_SHOWN), changed);
        panel.choice("show", R.string.filter_show, SHOWS, SHOW_LABELS, edited.set(SHOW), changed);
        panel.checks(
                "types", R.string.filter_types, TYPE_KEYS, TYPE_LABELS, edited.set(TYPES), changed);
        panel.end(filter.applied().active());
    }

    /** One collection's rows, an empty folder standing as its placeholder. */
    private List<Row> rowsOf(PimdirCollections.Stored collection, String section) {
        List<Row> rows = new ArrayList<>();
        if (FileStore.isAttachments(collection)) {
            for (FileStore.Attached attached : files.attachmentsIn(collection.id)) {
                rows.add(new Row(collection, section, attached.file, attached.origin));
            }
            return rows;
        }
        for (FileStore.StoredFile file : files.files(collection.id)) {
            rows.add(new Row(collection, section, file, files.origin(file.linkId)));
        }
        if (rows.isEmpty()) {
            rows.add(new Row(collection, section, null, null));
        }
        return rows;
    }

    /** Lays out what the panel and the search let through. */
    private void render() {
        drawFilters();
        ListFilter.State kept = filter.applied();
        Set<String> show = kept.set(SHOW);
        Set<String> types = kept.set(TYPES);
        List<Row> shown = new ArrayList<>();
        int files = 0;
        folderHeaders.clear();
        for (Row row : rows) {
            if (!row.attachment()) {
                folderHeaders.put(row.section, row.collection);
            }
            Set<String> folders = kept.set(FOLDERS_SHOWN);
            if (!folders.isEmpty() && !folders.contains(row.collection.id)) {
                continue;
            }
            if (show.contains("folders") && row.attachment()
                    || show.contains("attachments") && !row.attachment()) {
                continue;
            }
            if (row.file == null) {
                if (types.isEmpty() && query.isEmpty()) {
                    shown.add(row);
                }
                continue;
            }
            if (!types.isEmpty() && !matchesAny(types, row.file.mediaType)) {
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

    /** Whether a media type falls under any of the types on, by key. */
    private static boolean matchesAny(Set<String> types, String mediaType) {
        for (int index = 0; index < TYPE_KEYS.length; index++) {
            if (types.contains(TYPE_KEYS[index]) && matches(index, mediaType)) {
                return true;
            }
        }
        return false;
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
     * Creates a folder under a name the user gives, unless one goes by it.
     *
     * <p>One card per name, so two folders of one name would read as one.
     */
    public void newFolder() {
        fileActions.askName(
                R.string.attachment_new_folder,
                R.string.attachment_create,
                "",
                name ->
                        host.io.execute(
                                () -> {
                                    String created = files.createFolder(name);
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
     * Opens a file's sheet from a link, read off the store with its message.
     */
    public void openFile(String collection, String linkId) {
        host.io.execute(
                () -> {
                    Row found = null;
                    for (FileStore.StoredFile file : files.files(collection)) {
                        if (file.linkId.equals(linkId)) {
                            PimdirCollections.Stored held = collectionOf(collection);
                            found =
                                    held == null
                                            ? null
                                            : new Row(
                                                    held,
                                                    held.name,
                                                    file,
                                                    files.origin(linkId));
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
        List<PimdirCollections.Stored> all = new ArrayList<>(files.folders());
        all.addAll(files.attachmentCollections());
        for (PimdirCollections.Stored collection : all) {
            if (collection.id.equals(id)) {
                return collection;
            }
        }
        return null;
    }

    /**
     * What a file offers: open, share, save, export, message, links, delete.
     */
    private void fileMenu(Row row) {
        FileStore.StoredFile file = row.file;
        FileActions.Source bytes = () -> fileOf(row);
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(host.getString(R.string.attachment_open));
        actions.add(() -> fileActions.open(file, bytes));
        labels.add(host.getString(R.string.files_share));
        actions.add(() -> fileActions.share(file, bytes));
        labels.add(host.getString(R.string.attachment_save));
        actions.add(() -> fileActions.saveToFolder(file, bytes));
        labels.add(host.getString(R.string.files_export));
        actions.add(() -> fileActions.export(file, bytes));
        if (row.origin != null) {
            labels.add(host.getString(R.string.files_show_message));
            actions.add(() -> origins.openOrigin(row.origin));
        }
        labels.add(host.getString(R.string.linked_title));
        actions.add(
                () -> linked.dialog(FileStore.endpointOf(file), fileActions.label(file), () -> {}));
        if (!row.attachment()) {
            labels.add(host.getString(R.string.files_delete));
            actions.add(() -> confirmDelete(file));
        }
        new AlertDialog.Builder(host)
                .setTitle(fileActions.label(file))
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (dialog, which) -> actions.get(which).run())
                .show();
    }

    /**
     * The file a row is read from: its body's blob, or its message's part.
     *
     * <p>A stand-in's part is written out of its message, fetched when the
     * phone does not hold it.
     */
    private java.io.File fileOf(Row row) {
        java.io.File saved = files.saved(row.file);
        if (saved != null) {
            return saved;
        }
        return origins.fileOf(row.origin, row.file);
    }

    /** Deletes a folder's copy of a file, once confirmed. */
    private void confirmDelete(FileStore.StoredFile file) {
        new AlertDialog.Builder(host)
                .setMessage(
                        host.getString(R.string.files_delete_confirm, fileActions.label(file)))
                .setPositiveButton(
                        R.string.files_delete,
                        (dialog, which) ->
                                host.io.execute(
                                        () -> {
                                            files.delete(file);
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
        fileActions.askName(
                R.string.files_rename,
                R.string.files_rename,
                folder.name,
                name -> {
                    if (name.equals(folder.name)) {
                        return;
                    }
                    host.io.execute(
                            () -> {
                                boolean renamed = files.renameFolder(folder.id, name);
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
                                            files.deleteFolder(folder.id);
                                            host.postAlive(this::reload);
                                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The key the folder an import waits on survives a recreation under. */
    private static final String IMPORT_FOLDER = "files.import.folder";

    private static final String IMPORT_NAME = "files.import.name";

    /** Keeps a waiting import across the activity's recreation. */
    public void save(android.os.Bundle state) {
        if (importing != null) {
            state.putString(IMPORT_FOLDER, importing.id);
            state.putString(IMPORT_NAME, importing.name);
        }
    }

    /** Restores a waiting import after the activity's recreation. */
    public void restore(android.os.Bundle state) {
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
                REQUEST_IMPORT);
    }

    /**
     * Imports the picked document into the folder that asked for it.
     *
     * <p>Copied into the cache a buffer at a time, then hashed and filed from
     * there.
     */
    public void imported(Uri source) {
        PimdirCollections.Stored folder = importing;
        importing = null;
        if (folder == null) {
            return;
        }
        host.io.execute(
                () -> {
                    java.io.File staged = null;
                    try {
                        staged = java.io.File.createTempFile(STAGED, null, host.getCacheDir());
                        try (InputStream in = host.getContentResolver().openInputStream(source);
                                java.io.OutputStream out = new java.io.FileOutputStream(staged)) {
                            if (in == null) {
                                throw new java.io.IOException("Unreadable " + source);
                            }
                            OpenedCopies.copy(in, out);
                        }
                        files.importFile(
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

    private final class Adapter extends SectionsAdapter<Row> {
        Adapter() {
            super(sections);
        }

        @Override
        public boolean isEnabled(int position) {
            return !sections.isHeader(position)
                    || folderHeaders.containsKey(sections.header(position));
        }

        @Override
        protected View rowView(int position, View recycled, ViewGroup parent) {
            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_event, parent, false);
            }
            sections.shape(view, position);
            view.findViewById(R.id.event_divider)
                    .setVisibility(sections.opensCard(position) ? View.GONE : View.VISIBLE);
            view.findViewById(R.id.event_conflicted).setVisibility(View.GONE);

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

            String name = fileActions.label(row.file);
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
                            ? files.label(row.collection)
                            : host.getString(
                                    R.string.files_from,
                                    row.origin.senderName.isEmpty()
                                            ? row.origin.sender
                                            : row.origin.senderName));
            return view;
        }
    }
}
