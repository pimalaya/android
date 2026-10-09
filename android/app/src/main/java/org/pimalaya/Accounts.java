package org.pimalaya;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.pimalaya.client.Addressbook;

/**
 * AccountManager plumbing, for the two kinds of Android account Pimalaya
 * keeps, both of its own type.
 *
 * <p>One per synced addressbook (Android has accounts, not addressbooks):
 * the addressbook collection URL lives in the account's user data and is
 * the matching key; the visible name is the addressbook name plus the
 * login. Removing an account makes ContactsProvider purge its raw
 * contacts, so deselecting an addressbook cleans the phone up by itself.
 *
 * <p>One per address whose calendars the phone shows, named by the address,
 * which is how calendar apps group them, the address in its user data:
 * calendars, unlike raw contacts, have a collection of their own
 * ({@code Calendars} rows, {@link CalendarRows}). Each kind is syncable for
 * its own authority alone, and each kind's reconcile leaves the other's
 * accounts alone.
 */
final class Accounts {
    /** Matches android:accountType in res/xml/authenticator.xml. */
    static final String TYPE = "org.pimalaya";

    private static final String DATA_URL = "url";

    /** A calendar account's address; never set on a book's account. */
    private static final String DATA_CALENDARS = "calendars";

    /** Set once the account's content trigger was turned on; the user's choice stands after. */
    private static final String DATA_AUTO = "auto";

    private Accounts() {}

    /**
     * Creates one Android account per subscribed addressbook (named after
     * its own mail account) and removes any that are no longer subscribed.
     * The full subscribed set must be passed at once: accounts absent from
     * it are purged. Calendar accounts are left alone.
     */
    static void reconcile(Context context, List<BookEntry> books) {
        AccountManager manager = AccountManager.get(context);

        for (Account account : manager.getAccountsByType(TYPE)) {
            if (manager.getUserData(account, DATA_CALENDARS) != null) {
                continue;
            }
            String url = manager.getUserData(account, DATA_URL);
            boolean wanted = false;
            for (BookEntry book : books) {
                wanted |= book.book.url.equals(url);
            }
            if (!wanted) {
                manager.removeAccountExplicitly(account);
            }
        }

        for (BookEntry book : books) {
            Account account = find(context, book.book);
            if (account == null) {
                account = new Account(name(book.accountEmail, book.book), TYPE);
                Bundle data = new Bundle();
                data.putString(DATA_URL, book.book.url);
                manager.addAccountExplicitly(account, null, data);
            }

            // NOTE: on, so a Contacts-app edit makes Android run the phone
            // pass (SyncService). Once per account, accounts from earlier
            // builds included, so a later choice in system settings stands.
            if (manager.getUserData(account, DATA_AUTO) == null) {
                ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true);
                manager.setUserData(account, DATA_AUTO, "1");
            }

            // NOTE: contacts apps only list accounts syncable for the
            // contacts authority; self-heals accounts from earlier
            // builds without touching anything already set.
            if (ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1);
            }
            if (ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY) != 0) {
                ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 0);
            }
        }
    }

    /**
     * Creates one Android account per address whose calendars the phone
     * shows and removes the other calendar accounts, which takes their
     * calendars off the phone: the caller runs their phone pass first. Book
     * accounts are left alone.
     */
    static void reconcileCalendars(Context context, Set<String> addresses) {
        AccountManager manager = AccountManager.get(context);

        for (Account account : manager.getAccountsByType(TYPE)) {
            String address = manager.getUserData(account, DATA_CALENDARS);
            if (address != null && !addresses.contains(address)) {
                manager.removeAccountExplicitly(account);
            }
        }

        for (String address : addresses) {
            Account account = findCalendars(context, address);
            if (account == null) {
                account = new Account(address, TYPE);
                Bundle data = new Bundle();
                data.putString(DATA_CALENDARS, address);
                if (!manager.addAccountExplicitly(account, null, data)) {
                    Log.w("pimalaya", "calendar account refused for " + address);
                    continue;
                }
            }

            // NOTE: on, so a calendar-app edit makes Android run the phone
            // pass (CalendarSyncService); once, as the books' accounts.
            if (manager.getUserData(account, DATA_AUTO) == null) {
                ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true);
                manager.setUserData(account, DATA_AUTO, "1");
            }

            // NOTE: the contacts adapter is always syncable for our type,
            // which would list a calendar account in the Contacts app.
            if (ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1);
            }
            if (ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY) != 0) {
                ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 0);
            }
        }
    }

    /**
     * Whether the phone's contacts already hold raw contacts of the address
     * under another app's account type (Google's own sync, DAVx5), so that
     * mirroring its books would show everyone twice. One provider query;
     * false without the contacts permission.
     *
     * <p>Our own accounts never match: they are named by the book, not by
     * the address.
     */
    static boolean elsewhere(Context context, String address) {
        if (!PhoneMirror.CONTACTS.granted(context)) {
            return false;
        }
        // NOTE: account names are addresses as each app spelled them, most
        // lowercased; the address as typed is tried beside its lowercase.
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                RawContacts.CONTENT_URI,
                                new String[] {RawContacts._ID},
                                RawContacts.ACCOUNT_NAME
                                        + " IN (?, ?) AND "
                                        + RawContacts.ACCOUNT_TYPE
                                        + " != ? AND "
                                        + RawContacts.DELETED
                                        + " = 0",
                                new String[] {address, address.toLowerCase(Locale.ROOT), TYPE},
                                null)) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    /**
     * Whether the phone's calendars already hold the address under another
     * app's account type (Google's own sync, DAVx5), so that mirroring its
     * calendars would show every event twice. One provider query over the
     * calendars of other types, a handful on any phone; false without the
     * calendar permission.
     */
    static boolean calendarsElsewhere(Context context, String address) {
        if (!PhoneMirror.CALENDAR.granted(context)) {
            return false;
        }
        List<String> names = new ArrayList<>();
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                Calendars.CONTENT_URI,
                                new String[] {Calendars.ACCOUNT_NAME},
                                Calendars.ACCOUNT_TYPE + " != ?",
                                new String[] {TYPE},
                                null)) {
            while (cursor != null && cursor.moveToNext()) {
                names.add(cursor.getString(0));
            }
        }
        return names(address, names);
    }

    /**
     * Whether one of the account names is the address, compared
     * case-insensitively: each app spells it its own way.
     */
    static boolean names(String address, Collection<String> names) {
        for (String name : names) {
            if (address.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** The addressbook collection URL backing the account, or null. */
    static String url(Context context, Account account) {
        return AccountManager.get(context).getUserData(account, DATA_URL);
    }

    /** The address whose calendars the account holds, null for a book's account. */
    static String calendarAddress(Context context, Account account) {
        return AccountManager.get(context).getUserData(account, DATA_CALENDARS);
    }

    /** The account holding the address's calendars, or null before reconcile. */
    static Account findCalendars(Context context, String address) {
        AccountManager manager = AccountManager.get(context);
        for (Account account : manager.getAccountsByType(TYPE)) {
            if (address.equals(manager.getUserData(account, DATA_CALENDARS))) {
                return account;
            }
        }
        return null;
    }

    /** Every calendar account, whichever address it holds. */
    static List<Account> calendarAccounts(Context context) {
        AccountManager manager = AccountManager.get(context);
        List<Account> accounts = new ArrayList<>();
        for (Account account : manager.getAccountsByType(TYPE)) {
            if (manager.getUserData(account, DATA_CALENDARS) != null) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    /** The account backing the addressbook, or null before reconcile. */
    static Account find(Context context, Addressbook book) {
        return findByUrl(context, book.url);
    }

    /**
     * Removes the Android account backing the addressbook URL, when one
     * exists; ContactsProvider purges its raw contacts with it.
     */
    static void remove(Context context, String url) {
        Account account = findByUrl(context, url);
        if (account != null) {
            AccountManager.get(context).removeAccountExplicitly(account);
        }
    }

    /** The account backing the addressbook URL, or null before reconcile. */
    static Account findByUrl(Context context, String url) {
        AccountManager manager = AccountManager.get(context);
        for (Account account : manager.getAccountsByType(TYPE)) {
            if (url.equals(manager.getUserData(account, DATA_URL))) {
                return account;
            }
        }
        return null;
    }

    private static String name(String login, Addressbook book) {
        // NOTE: the local book has no login to qualify it; its own name
        // stands alone in the phone's Contacts app.
        if (LocalBook.is(login)) {
            return book.name;
        }
        return book.name + " (" + login + ")";
    }
}
