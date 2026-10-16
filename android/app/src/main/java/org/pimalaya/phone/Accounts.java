package org.pimalaya.phone;

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
import java.util.Map;
import java.util.Set;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.LocalBook;
import org.pimalaya.client.Addressbook;

/**
 * AccountManager plumbing for the two kinds of Android account the app keeps.
 *
 * <p>One per synced book, keyed by its URL (removing it purges its contacts);
 * one per address whose calendars the phone shows ({@code CalendarRows}), of a
 * type of its own so contacts apps never list it. Each kind's reconcile leaves
 * the other's alone.
 */
public final class Accounts {
    /** The books' type; matches res/xml/authenticator.xml. */
    public static final String TYPE = "org.pimalaya";

    /** The calendars' type; matches res/xml/authenticator_calendar.xml. */
    public static final String CALENDAR_TYPE = "org.pimalaya.calendar";

    private static final String DATA_URL = "url";

    /**
     * A calendar account's address; never set on a book's account.
     *
     * <p>Earlier builds made calendar accounts of the books' type, told apart
     * by this alone; {@link #reconcileCalendars} moves them to their own.
     */
    private static final String DATA_CALENDARS = "calendars";

    /** Set once the content trigger was on; the user's choice stands after. */
    private static final String DATA_AUTO = "auto";

    private Accounts() {}

    /**
     * Creates an Android account per subscribed book, removes the others.
     *
     * <p>Pass the full subscribed set at once: accounts absent from it are
     * purged, unless they hold a phone edit not yet in ({@link #purge}).
     * Calendar accounts, an earlier build's included, are left alone.
     */
    public static void reconcile(Context context, Map<Addressbook, String> accountByBook) {
        AccountManager manager = AccountManager.get(context);

        for (Account account : manager.getAccountsByType(TYPE)) {
            if (manager.getUserData(account, DATA_CALENDARS) != null) {
                continue;
            }
            String url = manager.getUserData(account, DATA_URL);
            boolean wanted = false;
            for (Addressbook book : accountByBook.keySet()) {
                wanted |= book.url().equals(url);
            }
            if (!wanted) {
                purge(context, account);
            }
        }

        for (Map.Entry<Addressbook, String> entry : accountByBook.entrySet()) {
            Addressbook book = entry.getKey();
            Account account = find(context, book);
            if (account == null) {
                account = new Account(name(entry.getValue(), book), TYPE);
                Bundle data = new Bundle();
                data.putString(DATA_URL, book.url());
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
        }
    }

    /**
     * Creates an account per address with mirrored calendars, removing others.
     *
     * <p>Removing one takes its calendars off the phone: the caller runs their
     * phone pass first, the earlier builds' calendar accounts included, which
     * all go. Book accounts are left alone.
     */
    public static void reconcileCalendars(Context context, Set<String> addresses) {
        AccountManager manager = AccountManager.get(context);

        for (Account account : legacyCalendarAccounts(context)) {
            manager.removeAccountExplicitly(account);
        }
        for (Account account : manager.getAccountsByType(CALENDAR_TYPE)) {
            String address = manager.getUserData(account, DATA_CALENDARS);
            if (address == null || !addresses.contains(address)) {
                manager.removeAccountExplicitly(account);
            }
        }

        for (String address : addresses) {
            Account account = findCalendars(context, address);
            if (account == null) {
                account = new Account(address, CALENDAR_TYPE);
                Bundle data = new Bundle();
                data.putString(DATA_CALENDARS, address);
                if (!manager.addAccountExplicitly(account, null, data)) {
                    Log.w("pimalaya", "calendar account refused for " + AccountEntry.redacted(address));
                    continue;
                }
            }

            // NOTE: on, so a calendar-app edit makes Android run the phone
            // pass (CalendarSyncService); once, as the books' accounts.
            if (manager.getUserData(account, DATA_AUTO) == null) {
                ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true);
                manager.setUserData(account, DATA_AUTO, "1");
            }

            if (ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1);
            }
        }
    }

    /**
     * Whether another app's account (Google sync, DAVx5) holds the address.
     *
     * <p>Then mirroring its books would show everyone twice. One provider
     * query; false without the contacts permission. Our accounts never match:
     * they are named by the book.
     */
    public static boolean elsewhere(Context context, String address) {
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
     * Whether another app's calendars (Google sync, DAVx5) hold the address.
     *
     * <p>Then mirroring would show every event twice. One query over other
     * types' calendars; false without the calendar permission.
     */
    public static boolean calendarsElsewhere(Context context, String address) {
        if (!PhoneMirror.CALENDAR.granted(context)) {
            return false;
        }
        List<String> names = new ArrayList<>();
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                Calendars.CONTENT_URI,
                                new String[] {Calendars.ACCOUNT_NAME},
                                Calendars.ACCOUNT_TYPE + " NOT IN (?, ?)",
                                new String[] {TYPE, CALENDAR_TYPE},
                                null)) {
            while (cursor != null && cursor.moveToNext()) {
                names.add(cursor.getString(0));
            }
        }
        return names(address, names);
    }

    /** Whether one account name is the address, case-insensitively. */
    static boolean names(String address, Collection<String> names) {
        for (String name : names) {
            if (address.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** The addressbook collection URL backing the account, or null. */
    public static String url(Context context, Account account) {
        return AccountManager.get(context).getUserData(account, DATA_URL);
    }

    /** The address whose calendars it holds; null for a book's account. */
    public static String calendarAddress(Context context, Account account) {
        return AccountManager.get(context).getUserData(account, DATA_CALENDARS);
    }

    /**
     * The account holding the address's calendars, or null before reconcile.
     */
    public static Account findCalendars(Context context, String address) {
        AccountManager manager = AccountManager.get(context);
        for (Account account : manager.getAccountsByType(CALENDAR_TYPE)) {
            if (address.equals(manager.getUserData(account, DATA_CALENDARS))) {
                return account;
            }
        }
        return null;
    }

    /** Every calendar account, whichever address it holds. */
    public static List<Account> calendarAccounts(Context context) {
        AccountManager manager = AccountManager.get(context);
        List<Account> accounts = new ArrayList<>();
        for (Account account : manager.getAccountsByType(CALENDAR_TYPE)) {
            if (manager.getUserData(account, DATA_CALENDARS) != null) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    /**
     * The calendar accounts earlier builds made of the books' type.
     *
     * <p>Contacts apps list them as empty books; {@link #reconcileCalendars}
     * removes them once their calendars' phone pass ran.
     */
    public static List<Account> legacyCalendarAccounts(Context context) {
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
        return findByUrl(context, book.url());
    }

    /**
     * Removes the book URL's Android account; the provider purges its contacts.
     *
     * <p>Kept while it holds a phone edit not yet in ({@link #purge}).
     */
    public static void remove(Context context, String url) {
        Account account = findByUrl(context, url);
        if (account != null) {
            purge(context, account);
        }
    }

    /**
     * Removes a book's account unless it holds a phone edit the store lacks.
     *
     * <p>Android deletes an account's raw contacts with it, so one created or
     * edited in a contacts app and not yet brought in would be lost. Kept, it
     * waits for a phone pass; answers whether the account went.
     */
    static boolean purge(Context context, Account account) {
        if (unsynced(context, account)) {
            Log.w("pimalaya", "phone account kept: it holds contacts not yet synced");
            return false;
        }
        return AccountManager.get(context).removeAccountExplicitly(account);
    }

    /**
     * Whether a book's raw contacts hold a creation or an edit not yet in.
     *
     * <p>False without the contacts permission, which no phone pass could
     * read either. A pending deletion does not count: lost, the contact only
     * comes back.
     */
    static boolean unsynced(Context context, Account account) {
        if (!PhoneMirror.CONTACTS.granted(context)) {
            return false;
        }
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                RawContacts.CONTENT_URI,
                                new String[] {RawContacts._ID},
                                RawContacts.ACCOUNT_NAME
                                        + " = ? AND "
                                        + RawContacts.ACCOUNT_TYPE
                                        + " = ? AND "
                                        + RawContacts.DELETED
                                        + " = 0 AND ("
                                        + RawContacts.DIRTY
                                        + " = 1 OR "
                                        + RawContacts.SOURCE_ID
                                        + " IS NULL OR "
                                        + RawContacts.SOURCE_ID
                                        + " = '')",
                                new String[] {account.name, account.type},
                                null)) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    /** The account backing the addressbook URL, or null before reconcile. */
    public static Account findByUrl(Context context, String url) {
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
            return book.name();
        }
        return book.name() + " (" + login + ")";
    }
}
