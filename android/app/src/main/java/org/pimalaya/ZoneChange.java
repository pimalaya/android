package org.pimalaya;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.pimalaya.calendar.CalendarRows;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirDb;

/**
 * The device's zone changed: reprojects floating events on the phone, and
 * reads them again in the search index.
 *
 * <p>Runs the phone pass of every shown calendar, a sync-adapter write, never
 * an edit; its quiet path finds the rows projected for the old zone
 * (docs/calendar-mapping.md, zones). The index's refresh states the new zone,
 * which re-expands every calendar item (pimdir SEARCH §7), so the agenda draws
 * a floating time at the wall time it states.
 */
public class ZoneChange extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())
                || App.of(context).storeRefused()) {
            return;
        }

        Context app = context.getApplicationContext();
        PimdirDb store = App.of(app).pimdir();
        if (store.index() != null) {
            store.index().refreshSoon();
        }
        if (!PhoneMirror.CALENDAR.granted(context)) {
            return;
        }
        PendingResult pending = goAsync();
        new Thread(
                        () -> {
                            try {
                                for (String collection : CalendarRows.shown(app)) {
                                    CalendarRows.phonePass(store, collection);
                                }
                            } finally {
                                pending.finish();
                            }
                        })
                .start();
    }
}
