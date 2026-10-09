package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.ContactsContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Addressbook;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.Set;

/**
 * A mirrored book's Android account syncs automatically, so a Contacts-app
 * edit makes Android run the phone pass: turned on once per account, the
 * accounts of earlier builds (created with it off) included, after which a
 * choice made in the system's settings stands. A calendar account, one per
 * address, syncs calendars alone and a book's contacts alone, and neither
 * kind's reconcile touches the other's accounts. The phone holds an address
 * elsewhere when another app's account is named by it, in any case.
 */
@RunWith(RobolectricTestRunner.class)
public class AccountsTest {
    private static final String EMAIL = "jane@example.com";
    private static final String URL = "https://dav.example.com/books/b1/";

    private Context context;
    private AccountManager manager;
    private List<BookEntry> books;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = AccountManager.get(context);
        books =
                List.of(
                        new BookEntry(
                                new Addressbook("b1", "Book One", URL, null, null),
                                EMAIL,
                                true,
                                true,
                                true));
    }

    private boolean automatic(Account account) {
        return ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY);
    }

    @Test
    public void aNewAccountSyncsAutomatically() {
        Accounts.reconcile(context, books);

        Account account = Accounts.findByUrl(context, URL);
        assertTrue(automatic(account));
        assertEquals(1, ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY));
    }

    @Test
    public void anAccountOfAnEarlierBuildIsTurnedOn() {
        Account account = new Account("Book One (" + EMAIL + ")", Accounts.TYPE);
        Bundle data = new Bundle();
        data.putString("url", URL);
        manager.addAccountExplicitly(account, null, data);
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false);

        Accounts.reconcile(context, books);

        assertTrue(automatic(account));
    }

    @Test
    public void aChoiceMadeInTheSystemSettingsStands() {
        Accounts.reconcile(context, books);
        Account account = Accounts.findByUrl(context, URL);
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false);

        Accounts.reconcile(context, books);

        assertFalse(automatic(account));
    }

    @Test
    public void aCalendarAccountSyncsCalendarsAlone() {
        Accounts.reconcileCalendars(context, Set.of(EMAIL));

        Account account = Accounts.findCalendars(context, EMAIL);
        assertEquals(new Account(EMAIL, Accounts.TYPE), account);
        assertEquals(EMAIL, Accounts.calendarAddress(context, account));
        assertEquals(1, ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY));
        assertEquals(0, ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY));
        assertTrue(ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY));
    }

    @Test
    public void aBookAccountNeverSyncsCalendars() {
        Accounts.reconcile(context, books);

        Account account = Accounts.findByUrl(context, URL);
        assertNull(Accounts.calendarAddress(context, account));
        assertEquals(0, ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY));
    }

    @Test
    public void eachKindsReconcileLeavesTheOthersAccounts() {
        Accounts.reconcile(context, books);
        Accounts.reconcileCalendars(context, Set.of(EMAIL));

        Accounts.reconcile(context, List.of());
        assertNull(Accounts.findByUrl(context, URL));
        assertNotNull(Accounts.findCalendars(context, EMAIL));

        Accounts.reconcile(context, books);
        Accounts.reconcileCalendars(context, Set.of());
        assertNull(Accounts.findCalendars(context, EMAIL));
        assertNotNull(Accounts.findByUrl(context, URL));
        assertEquals(1, manager.getAccountsByType(Accounts.TYPE).length);
    }

    @Test
    public void aCalendarChoiceMadeInTheSystemSettingsStands() {
        Accounts.reconcileCalendars(context, Set.of(EMAIL));
        Account account = Accounts.findCalendars(context, EMAIL);
        ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, false);

        Accounts.reconcileCalendars(context, Set.of(EMAIL));

        assertFalse(ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY));
    }

    @Test
    public void anotherAppHoldsTheAddressWhateverItsCase() {
        assertTrue(Accounts.names("Jane@Example.com", List.of("Work", "jane@example.com")));
        assertFalse(Accounts.names(EMAIL, List.of("john@example.com", "Jane")));
        assertFalse(Accounts.names(EMAIL, List.of()));
    }
}
