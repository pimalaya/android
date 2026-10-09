package org.pimalaya;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * Calendar sync adapter: the phone pass of an address's shown calendars
 * through Android's sync scheduler, so a calendar-app edit reaches the store
 * without opening Pimalaya (the next remote sync pushes it upstream).
 * Registering it also associates Pimalaya's account type with the calendar
 * authority, which the provider wants of the accounts its sync-adapter
 * writes name. Serves only the syncs the OS schedules itself: the upload
 * sync after an edit in our calendars (the accounts sync automatically), and
 * the per-account "sync now".
 */
public class CalendarSyncService extends Service {
    private static final Object LOCK = new Object();
    private static Adapter adapter;

    /** When a pass last brought a calendar-app edit in, for the app to reload. */
    static volatile long ingested;

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

            // NOTE: a book's account, asked whether it syncs calendars (an
            // initialization sync): it never does.
            if (Accounts.calendarAddress(context, account) == null) {
                ContentResolver.setIsSyncable(account, authority, 0);
                return;
            }
            if (!PhoneMirror.CALENDAR.granted(context)) {
                return;
            }

            try {
                PimdirDb pimdir = new PimdirDb(context);
                for (String collection : CalendarRows.rows(context, account).keySet()) {
                    if (CalendarRows.phonePass(pimdir, collection)) {
                        ingested = System.currentTimeMillis();
                    }
                }
            } catch (Exception error) {
                Log.w("pimalaya", "calendar phone sync failed for " + account.name + ": " + error);
                result.stats.numIoExceptions++;
            }
        }
    }
}
