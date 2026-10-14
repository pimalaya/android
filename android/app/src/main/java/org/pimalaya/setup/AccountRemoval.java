package org.pimalaya.setup;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.pimalaya.account.AccountActivation;
import org.pimalaya.account.LocalBook;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.PhoneCalendars;
import org.pimalaya.contact.BookEntry;
import org.pimalaya.contact.CardStore;
import org.pimalaya.contact.PimdirContacts;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.SenderName;
import org.pimalaya.pimdir.DefaultCollection;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.sync.BackgroundCheck;
import org.pimalaya.sync.FirstSync;
import org.pimalaya.sync.SyncPasses;

/**
 * An account's deletion across every domain: its credentials, its contacts
 * moved to the on-device book, its mail and events, and the app state kept
 * per account (filters, defaults, switches).
 */
final class AccountRemoval {
    private final Context context;
    private final SecureStore store;
    private final CardStore base;
    private final MailStore mail;
    private final EventStore events;
    private final PimdirContacts contacts;

    /** Reads the domains' collections, as the activity's passes do. */
    private final SyncPasses passes;

    AccountRemoval(Context context, SyncPasses.Parts parts, PimdirContacts contacts) {
        this.context = context;
        this.store = parts.accounts();
        this.base = parts.cards();
        this.mail = parts.mail();
        this.events = parts.events();
        this.contacts = contacts;
        this.passes = new SyncPasses(context, parts, SyncPasses.HEADLESS);
    }

    /**
     * Removes an account's data and app state, on the calling thread (the
     * main one); answers its book URLs for the phone purge.
     */
    List<String> remove(String email) {
        // NOTE: remember the books' URLs to remove the phone accounts
        // that mirrored them.
        List<String> urls = new ArrayList<>();
        for (BookEntry entry : base.loadAllAddressbooks()) {
            if (entry.accountEmail.equals(email)) {
                urls.add(entry.book.url());
            }
        }
        List<String> calendars = new ArrayList<>();
        for (PimdirCollections.Stored calendar : passes.collectionsOf(PimDomain.CALENDAR)) {
            if (calendar.accountEmail.equals(email)) {
                calendars.add(calendar.id);
            }
        }
        // NOTE: before its collections go, which is what names them.
        forgetViews(email);
        PhoneCalendars.forget(context, email, calendars);

        // NOTE: the account and every domain it covered. The screen shows the
        // contacts side, but the user is deleting the account they see, and
        // leaving a mail or calendar connection behind under the same address
        // would be an invisible leftover.
        store.remove(email);
        // NOTE: the contacts outlive the account: they move into the on-device
        // book, cleared of every sync marker, and the account's collections go
        // with their bindings. The switches and the link exceptions are the
        // app's own state and are dropped separately.
        contacts.detachToLocal(urls, LocalBook.URL);
        base.forgetAccount(email);
        // NOTE: mail and events do not outlive it: they are the server's,
        // and left behind they would stay listed under an account the
        // filter no longer offers to hide.
        mail.forget(email);
        FirstSync.forget(context, email);
        SenderName.set(context, email, "");
        BackgroundCheck.forget(context, email);
        events.forget(email);
        return urls;
    }

    /**
     * Drops what the filters, the defaults and the account switch held of an
     * account being deleted, before its collections go.
     */
    private void forgetViews(String email) {
        for (PimDomain domain : PimDomain.values()) {
            List<PimdirCollections.Stored> collections =
                    domain == PimDomain.MAIL
                            ? mail.loadMailboxes(List.of(email))
                            : passes.collectionsOf(domain);
            List<String> ids = new ArrayList<>();
            for (PimdirCollections.Stored collection : collections) {
                if (collection.accountEmail.equals(email)) {
                    ids.add(collection.id);
                }
            }
            MergedFilter.of(context, domain).forget(email, ids);
        }
        DefaultCollection.forget(context, email);
        AccountActivation.set(context, email, true);
    }
}
