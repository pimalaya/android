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
import android.util.Log;
import org.pimalaya.client.PimalayaClient;

/**
 * Contacts sync adapter running the phone spoke's engine pass through
 * Android's sync scheduler: the book's raw contacts reconcile two-way
 * with the store, so a contacts-app edit converges into the hub without
 * opening Pimalaya (the next remote sync pushes it upstream).
 * Registering it also associates Pimalaya's account type with the
 * contacts authority, and its CONTACTS_STRUCTURE meta-data is what makes
 * contacts apps list the accounts and allow editing their raw contacts.
 * Serves only the syncs the OS schedules itself (the per-account "sync
 * now" and the upload syncs after edits on our raw contacts); in-app
 * actions run the same pass directly.
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
            Context context = getContext();

            String url = Accounts.url(context, account);
            Log.d("pimalaya", "phone sync for " + account.name + ", url " + url);
            if (url == null) {
                return;
            }

            try {
                PimdirDb pimdir = new PimdirDb(context);
                CardStore store = new CardStore(context, pimdir);
                OfflineEngine engine =
                        new OfflineEngine(store, pimdir, new PimalayaClient(), null, null, context);
                OfflineEngine.Report report = new OfflineEngine.Report();
                engine.syncPhone(url, report);
                Log.d(
                        "pimalaya",
                        "phone sync done: " + report.localIn.size() + " in, "
                                + report.localOut.size() + " out, "
                                + report.localChanged.size() + " changed");
            } catch (Exception error) {
                Log.w("pimalaya", "phone sync failed for " + account.name + ": " + error);
                result.stats.numIoExceptions++;
            }
        }
    }
}
