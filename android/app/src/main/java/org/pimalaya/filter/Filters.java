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
 * What the filter page lists and what Apply does past the ticks: the
 * rosters of accounts and collections, and the syncs a newly ticked
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
     * What the filter page lists for Files: the device's folders under the
     * device, and each account that is on with its attachments collection.
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
     * Keeps the filter page's ticks ({@code ticks}) in {@code kept}, the
     * filter of {@code domain} (null for the Files tab), and syncs what they
     * newly take: the first step of each mailbox never listed, a later pull
     * catching up one listed before; a pass over the books or the
     * calendars when one is newly ticked. What they untick is hidden and
     * synced no more, its stored items kept.
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
     * The collections {@code ticks} takes that {@code kept} did not, by
     * account in the roster's order.
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
     * What the filter page lists for a domain: every account that is on and
     * covers it, each with its collections, the outbox among a mail
     * account's and the on-device book as an account of its own. An account
     * holding none is left out, having nothing to tick.
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
