package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.ContactsContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Addressbook;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * A mirrored book's Android account syncs automatically, so a Contacts-app
 * edit makes Android run the phone pass: turned on once per account, the
 * accounts of earlier builds (created with it off) included, after which a
 * choice made in the system's settings stands.
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
}
