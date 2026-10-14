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
 * The vCard file import and export behind the contacts list: it reads a
 * chosen file off the main thread and stores each card it holds in the
 * picked addressbook, and writes the active contacts view out as a
 * single vCard file, both with the add button inert. It reaches the
 * base, accounts, the io executor and the contacts list through the
 * host, which keeps the SAF pickers and the {@code onActivityResult}
 * router and calls in through {@link #importFile} and
 * {@link #exportFile}.
 */
public final class VcfTransfer {
    /** The request codes of the import and export document pickers. */
    public static final int REQUEST_IMPORT = 2;

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

    /** Prompts for a destination file, then exports the active view as a
     *  single vCard file. */
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
     * Picks the addressbook the file imports into: the hidden local book
     * when no real account is configured, the sole book when there is one,
     * otherwise a chooser (the local book is never offered explicitly).
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
     * Reads a vCard file off the main thread and stores each card it holds
     * in the chosen addressbook (a card in the local book stays unattached
     * and shows muted until placed).
     */
    private void importInto(Uri uri, BookEntry target) {
        // The add button goes inert while the file reads.
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
            String uid = editor.cardIndex(vcard).optString("uid");
            String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
            parts.contacts().save(target.book.url, new Card(id, null, null, vcard));
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
     * Writes the active contacts view (the currently listed, search-filtered
     * contacts) to the chosen file as one vCard file, each contact's raw
     * document appended verbatim, off the main thread with the add button inert.
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

    /** One vCard per contact (its primary replica's document), appended
     *  verbatim so the file round-trips through the importer. */
    private String buildVcf(List<Group> groups) {
        List<String> vcards = new ArrayList<>(groups.size());
        for (Group group : groups) {
            vcards.add(group.primary().card.vcard);
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
