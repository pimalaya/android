package org.pimalaya.contact;

import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.Card;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.ContactFields;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;

/**
 * The contact save fan-out behind the edit form.
 *
 * <p>Reads the form model and the working {@link EditSession}, stages the edit
 * through {@link ContactStager} (offline first, pushed by the next sync) and
 * applies the addressbooks dialog's placement, over {@link ContactParts}.
 */
final class ContactWriter {
    private final Host host;
    private final ContactParts parts;
    private final ContactEditor editor;
    private final Runnable reloadList;

    ContactWriter(Host host, ContactParts parts, ContactEditor editor, Runnable reloadList) {
        this.host = host;
        this.parts = parts;
        this.editor = editor;
        this.reloadList = reloadList;
    }

    /**
     * Stages the edited contact (pushed by the next sync), back to the list.
     *
     * <p>The model applies to each replica's own document
     * (docs/merged-view.md): UIDs and unmanaged properties stay; replicas
     * already matching are skipped.
     */
    void save() {
        String birthday = editor.form().birthday();
        if (!birthday.isEmpty() && !birthday.matches("\\d{4}-\\d{2}-\\d{2}")) {
            host.toast(host.getString(R.string.invalid_birthday));
            return;
        }
        String anniversary = editor.form().anniversary();
        if (!anniversary.isEmpty() && !anniversary.matches("\\d{4}-\\d{2}-\\d{2}")) {
            host.toast(host.getString(R.string.invalid_anniversary));
            return;
        }

        Runnable done =
                () -> {
                    reloadList.run();
                    host.showBack(Panels.CONTACTS);
                };
        try {
            ContactFields model = editor.form().collect();

            if (editor.edit().card == null) {
                AccountEntry account = parts.accountFor(editor.edit().accountEmail);
                if (account == null) {
                    // NOTE: the account was deleted while the editor was open.
                    host.toast(host.getString(R.string.save_failed));
                    return;
                }
                String vcard = ContactClient.applyCard(editor.edit().vcard, model);
                String uid = editor.cardIndex(vcard).uid();
                String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
                parts.contacts().save(editor.edit().book.url(), new Card(id, null, null, vcard));
            } else if (editor.edit().resolvingConflict) {
                saveConflictResolution(model);
            } else if (editor.edit().mergeSurvivor != null) {
                saveMerge(model);
            } else {
                saveFanOut(model);
            }

            if (editor.edit().card != null && editor.edit().pendingBookState != null) {
                applyBookState(ContactClient.applyCard(editor.edit().card.vcard(), model), done);
                return;
            }
        } catch (Exception error) {
            host.showError(error, R.string.save_failed);
            return;
        }

        done.run();
    }

    /**
     * The fan-out save: the model onto every card's own document, each staged.
     *
     * <p>An ordinary edit each: a conflicted replica refuses it, staging
     * nothing, its conflict settled by the conflict form alone (SYNC §7).
     */
    private void saveFanOut(ContactFields model) throws JSONException {
        ContactStager engine = new ContactStager(parts.pimdir(), parts.clients());
        Map<String, String> first = new HashMap<>();

        for (Entry entry : editor.edit().replicas) {
            // NOTE: an m:n card's placements agreeing with the first take it.
            String agreed = first.putIfAbsent(parts.pool().replicaRef(entry), entry.object());
            if (agreed != null && !agreed.equals(entry.object())) {
                continue;
            }

            String vcard = ContactClient.applyCard(entry.card().vcard(), model);
            if (editor.cardIndex(vcard).hash().equals(entry.hash())) {
                continue;
            }
            engine.edit(
                    entry.book.url(),
                    PimdirStorage.rowHandle(entry.card().uri(), entry.id),
                    vcard);
        }
    }

    /**
     * The conflict save: the picks apply onto the captured three-way merge.
     *
     * <p>Remote's other changes and unmanaged data survive. Staged on the one
     * conflicted replica, naming the diverging body the form showed, it
     * resolves it; the push is guarded on the observed remote revision.
     */
    private void saveConflictResolution(ContactFields model) throws JSONException {
        Entry replica = editor.edit().replicas.get(0);
        String resolved = ContactClient.applyCard(editor.edit().vcard, model);
        new ContactStager(parts.pimdir(), parts.clients())
                .resolve(
                        replica.book.url(),
                        PimdirStorage.rowHandle(replica.card().uri(), replica.id),
                        resolved,
                        editor.edit().resolves);
    }

    /** The merge save: the model onto the survivor, deletes for the others. */
    private void saveMerge(ContactFields model) throws JSONException {
        Entry survivor = editor.edit().mergeSurvivor;
        editor.edit().mergeSurvivor = null;

        String vcard = ContactClient.applyCard(survivor.card().vcard(), model);
        if (!editor.cardIndex(vcard).hash().equals(survivor.hash())) {
            new ContactStager(parts.pimdir(), parts.clients())
                    .edit(
                            survivor.book.url(),
                            PimdirStorage.rowHandle(survivor.card().uri(), survivor.id),
                            vcard);
        }

        String survivorRef = parts.pool().replicaRef(survivor);
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (Entry entry : editor.edit().replicas) {
            String ref = parts.pool().replicaRef(entry);
            if (ref.equals(survivorRef) || !removed.add(ref)) {
                continue;
            }
            AccountEntry owner = parts.accountFor(entry.accountEmail);
            if (owner == null) {
                continue;
            }
            parts.contacts().stageDelete(entry.book.url(), entry.id);
        }

        host.toast(host.getString(R.string.merge_done));
    }

    /**
     * Applies the addressbooks dialog on save: memberships, copies, removals.
     *
     * <p>Every book is placed before any is left: a removal then sees the add
     * a move goes to, which supersedes it (pimdir STORAGE §15.3). A removal
     * that would drop the only copy of a change never sent asks first, then
     * {@code done} runs.
     */
    private void applyBookState(String vcard, Runnable done) {
        Map<String, Boolean> desired = editor.edit().pendingBookState;
        editor.edit().pendingBookState = null;
        if (desired == null) {
            done.run();
            return;
        }

        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : editor.edit().replicas) {
            replicaByBook.put(entry.book.url(), entry);
        }

        List<BookEntry> leaving = new ArrayList<>();
        for (BookEntry target : parts.cards().loadSubscribedAddressbooks()) {
            Boolean wanted = desired.get(target.book.url());
            if (wanted == null
                    || wanted == replicaByBook.containsKey(target.book.url())) {
                continue;
            }
            AccountEntry account = parts.accountFor(target.accountEmail);
            if (account == null) {
                continue;
            }
            if (wanted) {
                addToBook(target, account, vcard);
            } else {
                leaving.add(target);
            }
        }

        List<Entry> losing = new ArrayList<>();
        for (BookEntry target : leaving) {
            Entry replica = replicaByBook.get(target.book.url());
            AccountEntry owner = parts.accountFor(replica.accountEmail);
            if (owner != null
                    && accountLevel(owner)
                    && parts.contacts().unsent(replica.book.url(), replica.id)) {
                losing.add(replica);
            }
        }
        if (losing.isEmpty()) {
            leave(leaving, replicaByBook);
            done.run();
            return;
        }

        // NOTE: the card stays in its other books, so what such an untick
        // drops is the change alone, kept in Deleted items only: asked rather
        // than refused, a held change never leaving that book on its own.
        List<String> names = new ArrayList<>();
        for (Entry replica : losing) {
            names.add(replica.book.name());
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.contact_unsent_keep_title)
                .setMessage(
                        host.getString(
                                R.string.contact_unsent_keep_message,
                                String.join(", ", names),
                                losing.get(0).displayName()))
                .setCancelable(false)
                .setPositiveButton(
                        R.string.contact_unsent_keep,
                        (dialog, which) -> keepThen(losing, leaving, replicaByBook, done))
                .setNegativeButton(
                        R.string.contact_unsent_drop,
                        (dialog, which) -> leaveThen(leaving, replicaByBook, done))
                .show();
    }

    /**
     * Merges each change kept into the books the contact stays in, then
     * leaves; a collision opens its form instead, nothing written and no
     * book left, the person choosing before taking it out again.
     */
    private void keepThen(
            List<Entry> losing,
            List<BookEntry> leaving,
            Map<String, Entry> replicaByBook,
            Runnable done) {
        try {
            for (Entry replica : losing) {
                PimdirContacts.KeepCollision collision =
                        parts.contacts().keepEdit(replica.book.url(), replica.id);
                if (collision != null) {
                    editor.openKeepConflict(collision);
                    return;
                }
            }
        } catch (RuntimeException error) {
            host.showError(error, R.string.save_failed);
            return;
        }
        leaveThen(leaving, replicaByBook, done);
    }

    /** Takes the contact out of the books it leaves. */
    private void leave(List<BookEntry> leaving, Map<String, Entry> replicaByBook) {
        for (BookEntry target : leaving) {
            removeFromBook(target, replicaByBook);
        }
    }

    /** The same, from the dialog: a failure says so, else {@code done} runs. */
    private void leaveThen(
            List<BookEntry> leaving, Map<String, Entry> replicaByBook, Runnable done) {
        try {
            leave(leaving, replicaByBook);
        } catch (RuntimeException error) {
            host.showError(error, R.string.save_failed);
            return;
        }
        done.run();
    }

    /**
     * Adds the contact to a book, as a membership or a UID-sharing copy.
     *
     * <p>A membership when the account-level backend already holds a card.
     */
    private void addToBook(BookEntry target, AccountEntry account, String vcard) {
        if (accountLevel(account)) {
            for (Entry entry : editor.edit().replicas) {
                if (entry.accountEmail.equals(target.accountEmail)) {
                    parts.contacts().stageMembership(
                            entry.book.url(), target.book.url(), entry.id, true);
                    return;
                }
            }
        }

        String uid = editor.cardIndex(vcard).uid();
        String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
        editor.createCopy(target, account, id, vcard);
    }

    /**
     * Removes the contact from a book, as a membership or the card's delete.
     *
     * <p>A membership removal while its card keeps other books.
     */
    private void removeFromBook(BookEntry target, Map<String, Entry> replicaByBook) {
        Entry replica = replicaByBook.get(target.book.url());
        if (replica == null) {
            return;
        }
        AccountEntry owner = parts.accountFor(replica.accountEmail);
        if (owner == null) {
            return;
        }

        // NOTE: on an account-level backend the card outlives the book it is
        // being taken out of, as long as another book still holds it; anywhere
        // else the book is the only place it exists, so the card goes with it.
        // Both are the same staged delete, on the placement rather than on the
        // card: what differs is only whether another placement survives it.
        if (accountLevel(owner)
                && parts.contacts().collectionsOf(replica.id).size() > 1) {
            parts.contacts().stageMembership(
                    replica.book.url(), target.book.url(), replica.id, false);
        } else {
            parts.contacts().stageDelete(replica.book.url(), replica.id);
        }
    }

    /** Whether the account's cards are account-level, in m:n books. */
    private static boolean accountLevel(AccountEntry account) {
        Account server = account.server(PimDomain.CONTACTS);
        return server != null && server.info().accountLevel();
    }
}
