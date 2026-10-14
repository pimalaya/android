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
 * Contacts sync adapter running the phone spoke's engine pass through
 * Android's sync scheduler: the book's raw contacts reconcile two-way
 * with the store, so a contacts-app edit converges into the hub without
 * opening Pimalaya (the next remote sync pushes it upstream).
 * Registering it also associates Pimalaya's account type with the
 * contacts authority, and its CONTACTS_STRUCTURE meta-data is what makes
 * contacts apps list the accounts and allow editing their raw contacts.
 * Serves only the syncs the OS schedules itself: the upload sync after
 * an edit on our raw contacts (the accounts sync automatically), and the
 * per-account "sync now"; the app runs the same pass directly.
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
     * Whether a sync asked of the adapter is one it runs: a contacts sync of
     * one of the app's own accounts. The service is exported for the
     * system to bind, and on Android before the 2022 security patch any
     * app could ask it for a sync of any account and authority.
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

            String url = Accounts.url(context, account);
            Log.d("pimalaya", "phone sync for " + account.name + ", url " + url);
            if (url == null) {
                return;
            }

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
