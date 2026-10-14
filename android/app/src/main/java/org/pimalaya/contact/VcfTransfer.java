package org.pimalaya.contact;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.client.Card;
import org.pimalaya.ui.Host;

/**
 * The vCard file import and export behind the contacts list.
 *
 * <p>Import stores a file's cards in the picked book, export writes the active
 * view as one file, both off the main thread with the add button inert. The
 * host's pickers call in through {@link #importFile} and {@link #exportFile}.
 */
public final class VcfTransfer {
    /** The import document picker's request code. */
    public static final int REQUEST_IMPORT = 2;

    /** The export document picker's request code. */
    public static final int REQUEST_EXPORT = 3;

    private final Host host;
    private final ContactParts parts;
    private final ContactEditor editor;
    private final ContactsList list;

    VcfTransfer(Host host, ContactParts parts, ContactEditor editor, ContactsList list) {
        this.host = host;
        this.parts = parts;
        this.editor = editor;
        this.list = list;
    }

    /** Prompts for a vCard file to import. */
    void importContacts() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        host.startActivityForResult(intent, REQUEST_IMPORT);
    }

    /** Prompts for a destination, then exports the active view as one file. */
    void exportContacts() {
        if (list.visibleGroups().isEmpty()) {
            host.toast(host.getString(R.string.export_empty));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/vcard");
        intent.putExtra(Intent.EXTRA_TITLE, "contacts.vcf");
        host.startActivityForResult(intent, REQUEST_EXPORT);
    }

    /**
     * Picks the book a file imports into, then imports it.
     *
     * <p>The hidden local book without accounts, the sole book if one, else a
     * chooser (never offering the local book).
     */
    public void importFile(Uri uri) {
        List<BookEntry> books = new ArrayList<>();
        BookEntry local = null;
        for (BookEntry entry : parts.cards().loadSubscribedAddressbooks()) {
            if (LocalBook.is(entry.accountEmail)) {
                local = entry;
            } else {
                books.add(entry);
            }
        }

        if (books.isEmpty()) {
            if (local != null) {
                importInto(uri, local);
            }
            return;
        }
        if (books.size() == 1) {
            importInto(uri, books.get(0));
            return;
        }

        List<BookEntry> targets = books;
        new AlertDialog.Builder(host)
                .setTitle(R.string.import_target_title)
                .setItems(
                        editor.bookLabels(targets),
                        (dialog, which) -> importInto(uri, targets.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Reads a vCard file off the main thread into the chosen book.
     *
     * <p>A card in the local book stays unattached and shows muted.
     */
    private void importInto(Uri uri, BookEntry target) {
        host.setListBusy(true);
        host.background(
                host.io,
                () -> importCards(readText(uri), target),
                imported -> {
                    host.setListBusy(false);
                    list.reload();
                    host.toast(host.getString(R.string.import_done, imported));
                },
                error -> {
                    host.setListBusy(false);
                    host.showError(error, R.string.import_failed);
                });
    }

    /** Splits the text into vCards and stores each in the target book. */
    private int importCards(String text, BookEntry target) {
        AccountEntry account = parts.accountFor(target.accountEmail);
        int count = 0;
        for (String vcard : Vcf.split(text)) {
            String uid = editor.cardIndex(vcard).uid();
            String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
            parts.contacts().save(target.book.url(), new Card(id, null, null, vcard));
            count++;
        }
        return count;
    }

    /** Reads a content Uri fully as UTF-8 text. */
    private String readText(Uri uri) throws java.io.IOException {
        try (java.io.InputStream in = host.getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    /**
     * Writes the listed, search-filtered contacts to the file as one vCard.
     *
     * <p>Documents appended verbatim, off the main thread, add button inert.
     */
    public void exportFile(Uri uri) {
        List<Group> snapshot = new ArrayList<>(list.visibleGroups());
        host.setListBusy(true);
        host.background(
                host.io,
                () -> {
                    writeText(uri, buildVcf(snapshot));
                    return null;
                },
                written -> {
                    host.setListBusy(false);
                    host.toast(host.getString(R.string.export_done, snapshot.size()));
                },
                error -> {
                    host.setListBusy(false);
                    host.showError(error, R.string.export_failed);
                });
    }

    /** One vCard per contact (primary replica), verbatim: it re-imports. */
    private String buildVcf(List<Group> groups) {
        List<String> vcards = new ArrayList<>(groups.size());
        for (Group group : groups) {
            vcards.add(group.primary().card.vcard());
        }
        return Vcf.join(vcards);
    }

    /** Writes text to a content Uri as UTF-8. */
    private void writeText(Uri uri, String text) throws java.io.IOException {
        try (java.io.OutputStream out = host.getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) {
                throw new java.io.IOException("Could not open the destination file");
            }
            out.write(text.getBytes("UTF-8"));
        }
    }
}
