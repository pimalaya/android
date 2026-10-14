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
import android.provider.CalendarContract;
import android.util.Log;
import org.pimalaya.calendar.CalendarRows;
import org.pimalaya.phone.Accounts;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirDb;

/**
 * Calendar sync adapter: the phone pass of an address's shown calendars.
 *
 * <p>A calendar-app edit reaches the store without opening the app; it also
 * ties the account type to the calendar authority, as the provider wants of
 * sync-adapter writes. Serves only OS syncs: upload after an edit, "sync now".
 */
public class CalendarSyncService extends Service {
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
     * Whether the adapter runs a sync: a calendar sync of one of our accounts.
     *
     * <p>The service is exported, and before the 2022 security patch any app
     * could ask it for any account and authority, which would have turned
     * another authority's sync off for a book.
     */
    static boolean serves(Account account, String authority) {
        return account != null
                && Accounts.TYPE.equals(account.type)
                && CalendarContract.AUTHORITY.equals(authority);
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
                PimdirDb pimdir = App.of(context).pimdir();
                for (String collection : CalendarRows.rows(context, account).keySet()) {
                    if (CalendarRows.phonePass(pimdir, collection)) {
                        App.of(context).state.calendarsIngested = System.currentTimeMillis();
                    }
                }
            } catch (Exception error) {
                Log.w("pimalaya", "calendar phone sync failed: " + error);
                result.stats.numIoExceptions++;
            }
        }
    }
}
