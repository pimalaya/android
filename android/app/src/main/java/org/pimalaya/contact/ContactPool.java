package org.pimalaya.contact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.ContactGroup;
import org.pimalaya.client.PoolReplica;
import org.pimalaya.client.ReplicaPool;

/**
 * The merged contacts pool behind the list (docs/merged-view.md): the
 * replicas of every subscribed book with their write-time index, and
 * the bridge grouping that merges them into logical contacts (UID
 * natural keys, detached refs and link clusters resolved by
 * groupContacts). Loading and grouping are blocking (a full table scan
 * plus one JNI round), so the callers run them off the main thread.
 */
public final class ContactPool {
    private final CardStore base;
    private final PimdirContacts contacts;

    /** The host's in-memory account cache, read on the main thread
     *  only ({@link #replicaRef}); the io-side grouping receives a
     *  snapshot instead. */
    private final List<AccountEntry> accounts;

    public ContactPool(CardStore base, PimdirContacts contacts, List<AccountEntry> accounts) {
        this.base = base;
        this.contacts = contacts;
        this.accounts = accounts;
    }

    /** Every subscribed book's replicas, in store order. */
    List<Entry> loadEntries() {
        List<Entry> entries = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            for (PimdirContacts.Indexed indexed : contacts.list(entry.book.url())) {
                entries.add(new Entry(entry.book, entry.accountEmail, indexed));
            }
        }
        return entries;
    }

    /**
     * Groups the replicas into merged rows through the bridge, the
     * link layer (manual links, detached refs) riding along. The
     * accounts snapshot resolves each replica's storage key without
     * touching the live cache off the main thread.
     */
    List<Group> group(List<Entry> entries, List<AccountEntry> accounts) {
        List<PoolReplica> replicas = new ArrayList<>();
        for (Entry entry : entries) {
            replicas.add(
                    new PoolReplica(
                            replicaRef(entry, accounts), entry.uid, entry.name, entry.card.id()));
        }
        ReplicaPool pool =
                new ReplicaPool(
                        replicas, base.loadLinks(), new ArrayList<>(base.loadDetached()));

        List<Group> groups = new ArrayList<>();
        for (ContactGroup row : ContactClient.groupContacts(pool)) {
            Group group = new Group(row.key());
            for (int index : row.replicas()) {
                group.replicas.add(entries.get(index));
            }
            groups.add(group);
        }
        return groups;
    }

    /** The replica's link-layer reference, over the live account cache. */
    public String replicaRef(Entry entry) {
        return replicaRef(entry, accounts);
    }

    /** The distinct physical cards behind a merged row's replicas. */
    public Set<String> distinctRefs(List<Entry> replicas) {
        Set<String> refs = new HashSet<>();
        for (Entry entry : replicas) {
            refs.add(replicaRef(entry));
        }
        return refs;
    }

    /**
     * The replica's storage key (docs/merged-view.md): the bare server
     * id on account-level backends, the collection-scoped key on
     * per-collection ones.
     */
    private static String cardKey(Account account, String bookUrl, String id) {
        return AccountClient.isAccountLevel(account) ? id : CardStore.key(bookUrl, id);
    }

    private static String replicaRef(Entry entry, List<AccountEntry> accounts) {
        AccountEntry account = null;
        for (AccountEntry candidate : accounts) {
            if (candidate.email.equals(entry.accountEmail)) {
                account = candidate;
            }
        }
        String key =
                account == null
                        ? CardStore.key(entry.book.url(), entry.card.id())
                        : cardKey(
                                account.server(PimDomain.CONTACTS),
                                entry.book.url(),
                                entry.card.id());
        return CardStore.replicaRef(entry.accountEmail, key);
    }
}
