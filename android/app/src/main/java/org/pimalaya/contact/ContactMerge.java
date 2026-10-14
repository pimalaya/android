package org.pimalaya.contact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.pimalaya.account.LocalBook;

/**
 * The merge flow's and the addressbooks dialog's decisions over a
 * contact's replicas, apart from the dialogs and the store
 * ({@link ContactEditor} runs them).
 */
final class ContactMerge {
    private ContactMerge() {}

    /** The candidate survivors: the first replica of each addressbook, in order. */
    static List<Entry> candidates(List<Entry> replicas) {
        // The first replica of each addressbook is its candidate survivor.
        Map<String, Entry> byBook = new LinkedHashMap<>();
        for (Entry entry : replicas) {
            byBook.putIfAbsent(entry.book.url(), entry);
        }
        return new ArrayList<>(byBook.values());
    }

    /** Whether every card already carries the same content (one hash). */
    static boolean identical(List<Entry> replicas) {
        Set<String> hashes = new HashSet<>();
        for (Entry entry : replicas) {
            hashes.add(entry.hash);
        }
        return hashes.size() == 1;
    }

    /**
     * The cards a merge into the survivor removes, in order: every
     * physical card but the survivor's, each once.
     */
    static List<Entry> doomed(List<Entry> replicas, Entry survivor, Function<Entry, String> ref) {
        String survivorRef = ref.apply(survivor);
        Set<String> removed = new HashSet<>();
        List<Entry> doomed = new ArrayList<>();
        for (Entry entry : replicas) {
            String entryRef = ref.apply(entry);
            if (entryRef.equals(survivorRef) || !removed.add(entryRef)) {
                continue;
            }
            doomed.add(entry);
        }
        return doomed;
    }

    /**
     * The distinct documents behind the group, one per normalized
     * hash: one means a plain edit, several go through the union merge.
     */
    static List<String> documents(List<Entry> replicas) {
        List<String> docs = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        for (Entry entry : replicas) {
            if (hashes.add(entry.hash)) {
                docs.add(entry.card.vcard());
            }
        }
        return docs;
    }

    /**
     * The addressbooks dialog's ticks: the state picked earlier in this
     * session when there is one, else whether the contact lives there.
     */
    static boolean[] checked(
            List<BookEntry> books, List<Entry> replicas, Map<String, Boolean> pending) {
        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : replicas) {
            replicaByBook.put(entry.book.url(), entry);
        }

        boolean[] checked = new boolean[books.size()];
        for (int index = 0; index < books.size(); index++) {
            String url = books.get(index).book.url();
            Boolean state = pending == null ? null : pending.get(url);
            checked[index] = state != null ? state : replicaByBook.containsKey(url);
        }
        return checked;
    }

    /** The addressbooks dialog's desired state once confirmed. */
    static Map<String, Boolean> pending(List<BookEntry> books, boolean[] checked) {
        Map<String, Boolean> pending = new HashMap<>();
        boolean anyChecked = false;
        for (int index = 0; index < books.size(); index++) {
            pending.put(books.get(index).book.url(), checked[index]);
            anyChecked |= checked[index];
        }
        // Nothing checked leaves the contact unattached,
        // kept only in the hidden local book.
        pending.put(LocalBook.URL, !anyChecked);
        return pending;
    }
}
