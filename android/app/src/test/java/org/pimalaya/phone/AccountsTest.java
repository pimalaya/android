package org.pimalaya.phone;

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
import org.pimalaya.contact.BookEntry;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.Set;

/**
 * A mirrored book's account syncs automatically, so Contacts edits come back.
 *
 * <p>Turned on once per account (earlier builds' too), then the system setting
 * stands. Calendar accounts are of their own type, an earlier build's moved. An
 * address named by another app's account is elsewhere.
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
        Accounts.reconcile(context, BookEntry.accounts(books));

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

        Accounts.reconcile(context, BookEntry.accounts(books));

        assertTrue(automatic(account));
    }

    @Test
    public void aChoiceMadeInTheSystemSettingsStands() {
        Accounts.reconcile(context, BookEntry.accounts(books));
        Account account = Accounts.findByUrl(context, URL);
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false);

        Accounts.reconcile(context, BookEntry.accounts(books));

        assertFalse(automatic(account));
    }

    @Test
    public void aCalendarAccountSyncsCalendarsAlone() {
        Accounts.reconcileCalendars(context, Set.of(EMAIL));

        Account account = Accounts.findCalendars(context, EMAIL);
        assertEquals(new Account(EMAIL, Accounts.CALENDAR_TYPE), account);
        assertEquals(EMAIL, Accounts.calendarAddress(context, account));
        assertEquals(1, ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY));
        assertTrue(ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY));
    }

    @Test
    public void eachKindsReconcileLeavesTheOthersAccounts() {
        Accounts.reconcile(context, BookEntry.accounts(books));
        Accounts.reconcileCalendars(context, Set.of(EMAIL));

        Accounts.reconcile(context, BookEntry.accounts(List.of()));
        assertNull(Accounts.findByUrl(context, URL));
        assertNotNull(Accounts.findCalendars(context, EMAIL));

        Accounts.reconcile(context, BookEntry.accounts(books));
        Accounts.reconcileCalendars(context, Set.of());
        assertNull(Accounts.findCalendars(context, EMAIL));
        assertNotNull(Accounts.findByUrl(context, URL));
        assertEquals(1, manager.getAccountsByType(Accounts.TYPE).length);
    }

    @Test
    public void anEarlierBuildsCalendarAccountMovesToItsOwnType() {
        Account legacy = new Account(EMAIL, Accounts.TYPE);
        Bundle data = new Bundle();
        data.putString("calendars", EMAIL);
        manager.addAccountExplicitly(legacy, null, data);
        assertEquals(List.of(legacy), Accounts.legacyCalendarAccounts(context));

        Accounts.reconcileCalendars(context, Set.of(EMAIL));

        assertEquals(0, manager.getAccountsByType(Accounts.TYPE).length);
        assertTrue(Accounts.legacyCalendarAccounts(context).isEmpty());
        assertEquals(new Account(EMAIL, Accounts.CALENDAR_TYPE), Accounts.findCalendars(context, EMAIL));
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
