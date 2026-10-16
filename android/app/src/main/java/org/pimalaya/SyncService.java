package org.pimalaya;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.util.Log;
import org.pimalaya.client.Clients;
import org.pimalaya.contact.ContactEngine;
import org.pimalaya.contact.ContactPhonePass;
import org.pimalaya.phone.Accounts;
import org.pimalaya.pimdir.PimdirDb;

/**
 * Contacts sync adapter: a book's phone pass, raw contacts two-way.
 *
 * <p>A contacts-app edit reaches the store without opening the app. Its
 * CONTACTS_STRUCTURE meta-data makes contacts apps list and edit the accounts.
 * Serves only OS syncs (upload after an edit, "sync now"); the app runs it too.
 */
public class SyncService extends Service {
    private static final Object LOCK = new Object();
    private static Adapter adapter;

    @Override
    public void onCreate() {
        synchronized (LOCK) {
            if (adapter == null) {
                adapter = new Adapter(getApplicationContext());
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return adapter.getSyncAdapterBinder();
    }

    /**
     * Whether the adapter runs a sync: a contacts sync of one of our accounts.
     *
     * <p>The service is exported, and before the 2022 security patch any app
     * could ask it for any account and authority.
     */
    static boolean serves(Account account, String authority) {
        return account != null
                && Accounts.TYPE.equals(account.type)
                && ContactsContract.AUTHORITY.equals(authority);
    }

    private static final class Adapter extends AbstractThreadedSyncAdapter {
        Adapter(Context context) {
            super(context, true);
        }

        @Override
        public void onPerformSync(
                Account account,
                Bundle extras,
                String authority,
                ContentProviderClient provider,
                SyncResult result) {
            if (!serves(account, authority)) {
                return;
            }
            Context context = getContext();

            // NOTE: no book behind the account (an earlier build's calendar
            // account): its contacts reach nowhere, so say so.
            String url = Accounts.url(context, account);
            if (url == null) {
                Log.w("pimalaya", "phone sync on an account with no book, its contacts stay unsynced");
                result.stats.numSkippedEntries++;
                return;
            }
            Log.d("pimalaya", "phone sync for " + url);

            try {
                PimdirDb pimdir = App.of(context).pimdir();
                ContactPhonePass engine = new ContactPhonePass(pimdir, Clients.real());
                ContactEngine.Report report = new ContactEngine.Report();
                engine.sync(url, report);
                Log.d(
                        "pimalaya",
                        "phone sync done: " + report.localIn.size() + " in, "
                                + report.localOut.size() + " out, "
                                + report.localChanged.size() + " changed");
                if (!report.localIn.isEmpty()
                        || !report.localOut.isEmpty()
                        || !report.localChanged.isEmpty()) {
                    App.of(context).state.contactsIngested = System.currentTimeMillis();
                }
            } catch (Exception error) {
                Log.w("pimalaya", "phone sync failed: " + error);
                result.stats.numIoExceptions++;
            }
        }
    }
}
