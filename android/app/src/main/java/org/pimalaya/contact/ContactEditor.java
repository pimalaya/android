package org.pimalaya.contact;

import android.app.AlertDialog;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardIndex;
import org.pimalaya.client.CardsMerge;
import org.pimalaya.client.ConflictForm;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.ContactFields;
import org.pimalaya.pimdir.DefaultCollection;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.Picker;

/**
 * The contact editor's flows over one working {@link EditSession}:
 * opening a merged row, a conflict or a new card into the form, merging
 * replicas, the addressbooks dialog and saving ({@link ContactWriter}).
 * The host keeps the
 * screen table and the chrome, which read the session through
 * {@link #edit} when they render.
 */
public final class ContactEditor {
    private final Host host;
    private final ContactParts parts;
    private final ContactForm form;

    /** Rebuilds the contacts list from the base (staged edits included). */
    private final Runnable reloadList;

    /** The contact save fan-out over the working edit session. */
    private final ContactWriter writer;

    /**
     * The contacts screen, whose selection a merge leaves; it binds itself
     * here when built, the two referencing each other.
     */
    ContactsList list;

    /** The editor's working state, replaced blank when it leaves. */
    private EditSession edit = new EditSession();

    public ContactEditor(Host host, ContactParts parts, ContactForm form, Runnable reloadList) {
        this.host = host;
        this.parts = parts;
        this.form = form;
        this.reloadList = reloadList;
        this.writer = new ContactWriter(host, parts, this, reloadList);
    }

    /** The current session, replaced on every open and on leaving. */
    public EditSession edit() {
        return edit;
    }

    /** The edit form the session renders into. */
    ContactForm form() {
        return form;
    }

    /**
     * Opens a merged row in the editor: one form for the whole contact
     * (docs/merged-view.md), the union of the replicas' documents with
     * per-field alternative chips when they diverge. Saving fans the
     * form out onto every replica.
     */
    public void openGroup(Group group) {
        if (group.conflicted()) {
            openConflict(group);
        } else {
            openMerged(group.replicas);
        }
    }

    /**
     * Opens the resolution form on a both-sides-edited conflict: the
     * bridge three-way merges the stored base, the local edit and the
     * captured remote (the newer side by REV pre-filled, both offered as
     * chips), and the editor loads it in conflict mode. A conflict with
     * nothing genuinely colliding (only list edits, which merge cleanly)
     * resolves straight away without a prompt.
     */
    private void openConflict(Group group) {
        Entry replica = null;
        for (Entry entry : group.replicas) {
            if (entry.conflicted) {
                replica = entry;
                break;
            }
        }
        if (replica == null) {
            openMerged(group.replicas);
            return;
        }

        String handle =
                PimdirStorage.rowHandle(replica.card.uri(), replica.card.id());
        JSONObject bodies;
        ConflictForm resolution;
        try {
            bodies = new PimdirStorage(parts.pimdir()).loadConflict(replica.book.url(), handle);
            if (bodies == null) {
                // NOTE: flagged but its remote is not captured yet (the
                // capturing sync has not run); edit it plainly.
                openMerged(group.replicas);
                return;
            }
            resolution =
                    ContactClient.mergeConflictForm(
                            bodies.optString("base"),
                            bodies.optString("local"),
                            bodies.optString("remote"));
        } catch (Exception error) {
            host.showError(error, R.string.contact_open_failed);
            return;
        }

        if (resolution.resolved()) {
            // Nothing needs the user: stage the clean merge and clear the
            // conflict. The toast is the tap's only visible outcome, so
            // without it the vanishing flag reads as a bug.
            try {
                new ContactStager(parts.pimdir(), parts.clients())
                        .edit(replica.book.url(), handle, resolution.vcard());
            } catch (Exception error) {
                host.showError(error, R.string.save_failed);
                return;
            }
            host.toast(host.getString(R.string.conflict_auto_resolved));
            reloadList.run();
            return;
        }

        edit = new EditSession();
        edit.book = replica.book;
        edit.accountEmail = replica.accountEmail;
        edit.card = replica.card;
        edit.replicas = new ArrayList<>(java.util.Collections.singletonList(replica));
        edit.resolvingConflict = true;
        edit.vcard = resolution.vcard();
        edit.title = replica.displayName();

        form.load(resolution.model(), resolution.alternatives(), resolution.changed());
        host.show(Panels.CONTACT);
    }

    /**
     * Starts a new contact in the default address book of the account in
     * view, asking only when there is none ({@link DefaultCollection}). With
     * no account the contact is created unattached (the hidden local book);
     * the local book is never offered as an explicit target.
     */
    public void addContact(MergedFilter shown) {
        Map<String, BookEntry> byUrl = new HashMap<>();
        BookEntry local = null;
        for (BookEntry entry : parts.cards().loadSubscribedAddressbooks()) {
            if (LocalBook.is(entry.accountEmail)) {
                local = entry;
            } else {
                byUrl.put(entry.book.url(), entry);
            }
        }

        // With no real account the contact is born unattached, in the
        // hidden local book.
        if (byUrl.isEmpty()) {
            if (local != null) {
                openNewContact(local.book, local.accountEmail);
            }
            return;
        }

        List<PimdirCollections.Stored> books = new ArrayList<>();
        for (PimdirCollections.Stored book :
                parts.cards().subscribedBooks(new PimdirCollections(parts.pimdir(), host))) {
            if (byUrl.containsKey(book.id)) {
                books.add(book);
            }
        }
        DefaultCollection.Choice choice =
                DefaultCollection.choice(host, PimdirSummary.CONTACT, books, shown::accepts);
        Picker.choose(
                host,
                R.string.save_target_title,
                R.string.contacts_no_book,
                choice.direct,
                choice.offered,
                choice.preselected,
                collection -> host.ui.twoLine(collection.name, collection.accountEmail),
                book -> openNewContact(byUrl.get(book.id).book, book.accountEmail));
    }

    /** One two-line label per addressbook of a picker dialog. */
    CharSequence[] bookLabels(List<BookEntry> books) {
        CharSequence[] labels = new CharSequence[books.size()];
        for (int index = 0; index < books.size(); index++) {
            BookEntry entry = books.get(index);
            // NOTE: the local book has no account email, so it shows on
            // one line.
            labels[index] =
                    LocalBook.is(entry.accountEmail)
                            ? host.getString(R.string.local_book)
                            : host.ui.twoLine(entry.book.name(), entry.accountEmail);
        }
        return labels;
    }

    /** Stages a copied card into the target addressbook. */
    void createCopy(BookEntry target, AccountEntry account, String id, String vcard) {
        parts.contacts().save(target.book.url(), new Card(id, null, null, vcard));
    }

    /** The survivor chooser over any replica list, then the merge. */
    void mergeReplicas(List<Entry> replicas) {
        if (parts.pool().distinctRefs(replicas).size() < 2) {
            return;
        }

        List<Entry> candidates = ContactMerge.candidates(replicas);
        if (candidates.size() == 1) {
            performMerge(replicas, candidates.get(0));
            return;
        }

        CharSequence[] labels = new CharSequence[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            Entry entry = candidates.get(index);
            labels[index] = host.ui.twoLine(entry.book.name(), entry.accountEmail);
        }

        new AlertDialog.Builder(host)
                .setTitle(R.string.merge_target_title)
                .setItems(
                        labels,
                        (dialog, which) -> performMerge(replicas, candidates.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Merges into the survivor: a straight absorb when every card
     * already carries the same content (the cards of one merged
     * contact, nothing to reconcile), the merge form otherwise.
     */
    private void performMerge(List<Entry> replicas, Entry survivor) {
        if (ContactMerge.identical(replicas)) {
            mergeDirect(replicas, survivor);
        } else {
            openMergeForm(replicas, survivor);
        }
    }

    /** Absorbs identical cards into the survivor, no form needed. */
    private void mergeDirect(List<Entry> replicas, Entry survivor) {
        list.exitSelection();

        for (Entry entry : ContactMerge.doomed(replicas, survivor, parts.pool()::replicaRef)) {
            parts.contacts().stageDelete(entry.book.url(), entry.card.id());
        }

        host.toast(host.getString(R.string.merge_done));
        reloadList.run();
    }

    /** Opens the merge form with a surviving replica armed. */
    private void openMergeForm(List<Entry> replicas, Entry survivor) {
        list.exitSelection();
        if (openMerged(replicas)) {
            edit.mergeSurvivor = survivor;
        }
    }

    /** Opens the edit form on a fresh card in the target addressbook. */
    private void openNewContact(Addressbook book, String accountEmail) {
        edit = new EditSession();
        edit.book = book;
        edit.accountEmail = accountEmail;
        edit.vcard = newVcard();
        edit.title = host.getString(R.string.contact_new);

        form.load(null, null, null);
        host.show(Panels.CONTACT);
    }

    /**
     * Opens the edit form on a merged contact: the single document when
     * the replicas agree, their union with per-field alternative chips
     * when they diverge. Saving stages the form onto every replica.
     * Returns false when the documents could not be read.
     */
    boolean openMerged(List<Entry> replicas) {
        Entry primary = replicas.get(0);
        edit = new EditSession();
        edit.book = primary.book;
        edit.accountEmail = primary.accountEmail;
        edit.card = primary.card;
        edit.replicas = new ArrayList<>(replicas);

        List<String> docs = ContactMerge.documents(replicas);

        ContactFields model;
        Map<String, List<String>> alternatives = null;
        List<String> changed = null;
        try {
            if (docs.size() == 1) {
                edit.vcard = primary.card.vcard();
                model = ContactClient.projectCard(primary.card.vcard());
            } else {
                CardsMerge merged = ContactClient.mergeCards(docs);
                edit.vcard = merged.vcard();
                model = merged.model();
                alternatives = merged.alternatives();
                // NOTE: a non-null changed set turns on the form's
                // conflict mode: only the disagreeing fields show.
                changed = merged.changed();
            }
        } catch (Exception error) {
            host.showError(error, R.string.contact_open_failed);
            return false;
        }

        edit.title = primary.displayName();

        form.load(model, alternatives, changed);
        host.show(Panels.CONTACT);
        return true;
    }

    /**
     * Leaves the editor without saving. The list still reloads: the
     * addressbooks dialog stages copies and removals it must reflect.
     * An unsaved Merge is abandoned.
     */
    public void close() {
        edit = new EditSession();
        reloadList.run();
        host.showBack(Panels.CONTACTS);
    }

    /**
     * The addressbooks dialog of the open contact, the one placement
     * gesture: checked means the contact exists there, through any of
     * its cards. Only real addressbooks are listed; unchecking every one
     * is allowed and leaves the contact unattached, living in the hidden
     * local book (it shows muted in the list, Delete is still the way to
     * remove it). The dialog only records the desired state (reopening
     * shows it); everything applies on SAVE: a staged membership when the
     * contact already has a card on that account-level backend, a copied
     * card sharing the vCard UID anywhere else, a membership removal or
     * the card's staged delete on uncheck.
     */
    public void manageBooks() {
        if (edit.replicas.isEmpty()) {
            return;
        }

        // NOTE: the hidden local book is not listed; it is the fallback
        // home when nothing is checked.
        List<BookEntry> books = new ArrayList<>();
        for (BookEntry entry : parts.cards().loadSubscribedAddressbooks()) {
            if (!LocalBook.is(entry.accountEmail)) {
                books.add(entry);
            }
        }
        boolean[] checked = ContactMerge.checked(books, edit.replicas, edit.pendingBookState);

        new AlertDialog.Builder(host)
                .setTitle(R.string.manage_books_title)
                .setMultiChoiceItems(
                        bookLabels(books),
                        checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) ->
                                edit.pendingBookState = ContactMerge.pending(books, checked))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Stages the edited contact in the base and returns to the list
     * (delegated to {@link ContactWriter}). The form model applies to
     * every replica's own document (docs/merged-view.md), fanning out
     * onto every replica, conflict and merge branches included.
     */
    public void save() {
        writer.save();
    }

    private static String newVcard() {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + UUID.randomUUID() + "\r\nEND:VCARD\r\n";
    }

    /**
     * The bridge's index of a vCard for one-off lookups (name, uid);
     * the hot paths read the same index from the store's columns
     * instead. Empty on a parse failure.
     */
    CardIndex cardIndex(String vcard) {
        try {
            return ContactClient.indexCard(vcard);
        } catch (Exception error) {
            Log.w("pimalaya", "card index failed", error);
            return new CardIndex("", List.of(), "", "", "", "", "", null, "");
        }
    }
}
