package org.pimalaya.contact;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.R;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Card;
import org.pimalaya.client.ContactClient;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;

/**
 * The contact save fan-out behind the edit form: it reads the form
 * model and the working {@link EditSession}, stages the edited contact
 * into the base through the offline engine (offline first, nothing
 * touches the network until the next sync), and applies the
 * addressbooks dialog's placement. It reaches the store, pool and
 * accounts through the host; the host keeps the navigation and calls in
 * through {@link #save}.
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
     * Stages the edited contact in the base (offline first: nothing
     * touches the network until the next sync pushes it) and returns to
     * the list. The form model applies to every replica's own document
     * (docs/merged-view.md): each keeps its UID and unmanaged
     * properties while the managed content converges; replicas already
     * carrying the resulting content are skipped.
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

        try {
            JSONObject model = editor.form().collect();

            if (editor.edit().card == null) {
                AccountEntry account = parts.accountFor(editor.edit().accountEmail);
                if (account == null) {
                    // The account was deleted while the editor was open.
                    host.toast(host.getString(R.string.save_failed));
                    return;
                }
                String vcard = ContactClient.applyCard(editor.edit().vcard, model);
                String uid = editor.cardIndex(vcard).optString("uid");
                String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
                parts.contacts().save(editor.edit().book.url(), new Card(id, null, null, vcard));
            } else if (editor.edit().resolvingConflict) {
                saveConflictResolution(model);
            } else if (editor.edit().mergeSurvivor != null) {
                saveMerge(model);
            } else {
                saveFanOut(model);
            }

            // The copies carry the just-saved content.
            if (editor.edit().card != null && editor.edit().pendingBookState != null) {
                applyBookState(ContactClient.applyCard(editor.edit().card.vcard(), model));
            }
        } catch (Exception error) {
            host.showError(error, R.string.save_failed);
            return;
        }

        reloadList.run();
        host.showBack(Panels.CONTACTS);
    }

    /**
     * The fan-out save: the form model applies to every card's own
     * document, each staged through the engine (editing a conflicted
     * replica resolves it).
     */
    private void saveFanOut(JSONObject model) throws JSONException {
        ContactStager engine = new ContactStager(parts.pimdir(), parts.clients());
        java.util.Set<String> staged = new java.util.HashSet<>();

        for (Entry entry : editor.edit().replicas) {
            // NOTE: an m:n replica appears once per book; stage it once.
            if (!staged.add(parts.pool().replicaRef(entry))) {
                continue;
            }

            String vcard = ContactClient.applyCard(entry.card.vcard(), model);
            if (editor.cardIndex(vcard).optString("hash").equals(entry.hash)) {
                continue;
            }
            engine.edit(
                    entry.book.url(),
                    PimdirStorage.rowHandle(entry.card.uri(), entry.card.id()),
                    vcard);
        }
    }

    /**
     * The conflict-resolution save: the form's picks apply onto the
     * captured three-way merge (so remote's non-conflicting changes and
     * unmanaged data survive), staged onto the one conflicted replica.
     * Editing the conflicted placement resolves it in the engine, and the
     * next sync pushes it guarded on the observed remote revision.
     */
    private void saveConflictResolution(JSONObject model) throws JSONException {
        Entry replica = editor.edit().replicas.get(0);
        String resolved = ContactClient.applyCard(editor.edit().vcard, model);
        new ContactStager(parts.pimdir(), parts.clients())
                .edit(
                        replica.book.url(),
                        PimdirStorage.rowHandle(replica.card.uri(), replica.card.id()),
                        resolved);
    }

    /**
     * The merge save: the form model applies to the surviving card and
     * every other card behind the form stages a delete.
     */
    private void saveMerge(JSONObject model) throws JSONException {
        Entry survivor = editor.edit().mergeSurvivor;
        editor.edit().mergeSurvivor = null;

        String vcard = ContactClient.applyCard(survivor.card.vcard(), model);
        if (!editor.cardIndex(vcard).optString("hash").equals(survivor.hash)) {
            new ContactStager(parts.pimdir(), parts.clients())
                    .edit(
                            survivor.book.url(),
                            PimdirStorage.rowHandle(survivor.card.uri(), survivor.card.id()),
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
            parts.contacts().stageDelete(entry.book.url(), entry.card.id());
        }

        host.toast(host.getString(R.string.merge_done));
    }

    /**
     * Applies the addressbooks dialog's desired state on save: staged
     * memberships, copies (carrying the just-saved content), removals.
     */
    private void applyBookState(String vcard) {
        Map<String, Boolean> desired = editor.edit().pendingBookState;
        editor.edit().pendingBookState = null;
        if (desired == null) {
            return;
        }

        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : editor.edit().replicas) {
            replicaByBook.put(entry.book.url(), entry);
        }

        for (BookEntry target : parts.cards().loadSubscribedAddressbooks()) {
            Boolean wanted = desired.get(target.book.url());
            if (wanted == null || wanted == replicaByBook.containsKey(target.book.url())) {
                continue;
            }
            AccountEntry account = parts.accountFor(target.accountEmail);
            if (account == null) {
                continue;
            }

            if (wanted) {
                addToBook(target, account, vcard);
            } else {
                removeFromBook(target, replicaByBook);
            }
        }
    }

    /**
     * Adds the contact to an addressbook: a staged membership when the
     * contact already has a card on that account-level backend, a
     * copied card sharing the vCard UID anywhere else.
     */
    private void addToBook(BookEntry target, AccountEntry account, String vcard) {
        if (AccountClient.isAccountLevel(account.server(PimDomain.CONTACTS))) {
            for (Entry entry : editor.edit().replicas) {
                if (entry.accountEmail.equals(target.accountEmail)) {
                    parts.contacts().stageMembership(
                            entry.book.url(), target.book.url(), entry.card.id(), true);
                    return;
                }
            }
        }

        String uid = editor.cardIndex(vcard).optString("uid");
        String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
        editor.createCopy(target, account, id, vcard);
    }

    /**
     * Removes the contact from an addressbook: a staged membership
     * removal when its card keeps other books, a staged delete of the
     * card otherwise.
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
        if (AccountClient.isAccountLevel(owner.server(PimDomain.CONTACTS))
                && parts.contacts().collectionsOf(replica.card.id()).size() > 1) {
            parts.contacts().stageMembership(
                    replica.book.url(), target.book.url(), replica.card.id(), false);
        } else {
            parts.contacts().stageDelete(replica.book.url(), replica.card.id());
        }
    }
}
