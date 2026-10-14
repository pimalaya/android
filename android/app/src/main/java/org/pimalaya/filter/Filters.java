package org.pimalaya.filter;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import org.pimalaya.R;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.file.FileStore;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;

/**
 * What the filter page lists, and what Apply does past keeping the ticks.
 *
 * <p>The rosters of accounts and collections, and the syncs a newly ticked
 * collection takes.
 */
public final class Filters {
    private final Context context;

    private final SyncPasses passes;

    /** The foreground sync, which runs what newly ticked collections take. */
    final SyncCoordinator sync;

    /** The stores the page labels mailboxes and file collections by. */
    final MailStore mail;

    final FileStore files;

    /** Every connected account: the activity's live list. */
    private final List<AccountEntry> accounts;

    /** The rosters and syncs over the activity's live account list. */
    public Filters(
            Context context,
            SyncPasses passes,
            SyncCoordinator sync,
            MailStore mail,
            FileStore files,
            List<AccountEntry> accounts) {
        this.context = context;
        this.passes = passes;
        this.sync = sync;
        this.mail = mail;
        this.files = files;
        this.accounts = accounts;
    }

    /**
     * What the filter page lists for Files.
     *
     * <p>The device's folders under the device, and each account that is on
     * with its attachments collection.
     */
    List<FilterPage.Account> filesRoster() {
        List<FilterPage.Account> roster = new ArrayList<>();
        List<PimdirCollections.Stored> folders = files.folders();
        if (!folders.isEmpty()) {
            roster.add(
                    new FilterPage.Account(
                            LocalBook.ACCOUNT,
                            context.getString(R.string.files_on_device),
                            folders));
        }
        for (PimdirCollections.Stored held : files.attachmentCollections()) {
            if (AccountActivation.enabled(context, held.accountEmail)) {
                roster.add(
                        new FilterPage.Account(held.accountEmail, held.accountEmail, List.of(held)));
            }
        }
        return roster;
    }

    /**
     * Keeps {@code ticks} in {@code kept} and syncs what they newly take.
     *
     * <p>{@code domain} is null for Files. A mailbox never listed takes its
     * first step, one listed before a pull, a newly ticked book or calendar a
     * pass. What they untick is hidden and no longer synced, its items kept.
     */
    void apply(PimDomain domain, MergedFilter kept, MergedFilter ticks, Runnable changed) {
        Map<String, List<String>> taken =
                domain == null
                        ? new LinkedHashMap<>()
                        : taken(roster(domain), kept::accepts, ticks::accepts);
        kept.apply(ticks);
        sync.replanBodies();
        changed.run();
        if (taken.isEmpty()) {
            return;
        }
        switch (domain) {
            case MAIL:
                sync.firstOfMailboxes(taken);
                break;
            case CALENDAR:
                sync.syncCalendars();
                break;
            default:
                sync.syncRemote();
                break;
        }
    }

    /**
     * The collections {@code ticks} newly takes over {@code kept}.
     *
     * <p>By account, in the roster's order.
     */
    static Map<String, List<String>> taken(
            List<FilterPage.Account> roster,
            BiPredicate<String, String> kept,
            BiPredicate<String, String> ticks) {
        Map<String, List<String>> taken = new LinkedHashMap<>();
        for (FilterPage.Account account : roster) {
            for (PimdirCollections.Stored collection : account.collections) {
                if (!kept.test(account.email, collection.id)
                        && ticks.test(account.email, collection.id)) {
                    taken.computeIfAbsent(account.email, email -> new ArrayList<>())
                            .add(collection.id);
                }
            }
        }
        return taken;
    }

    /**
     * What the filter page lists for a domain, account by account.
     *
     * <p>Every account that is on and covers it, with its collections: a mail
     * account's outbox among them, the on-device book as an account of its own.
     * An account holding none is left out.
     */
    List<FilterPage.Account> roster(PimDomain domain) {
        List<String> emails = new ArrayList<>();
        for (AccountEntry account : accounts) {
            if (account.covers(domain) && AccountActivation.enabled(context, account.email)) {
                emails.add(account.email);
            }
        }
        List<PimdirCollections.Stored> collections =
                domain == PimDomain.MAIL
                        ? mail.loadMailboxes(emails)
                        : passes.collectionsOf(domain);

        List<FilterPage.Account> roster = new ArrayList<>();
        for (String email : emails) {
            List<PimdirCollections.Stored> own = new ArrayList<>();
            for (PimdirCollections.Stored collection : collections) {
                if (collection.accountEmail.equals(email)) {
                    own.add(collection);
                }
            }
            if (!own.isEmpty()) {
                String label = LocalBook.is(email) ? context.getString(R.string.local_book) : email;
                roster.add(new FilterPage.Account(email, label, own));
            }
        }
        return roster;
    }
}
