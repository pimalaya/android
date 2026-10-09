package org.pimalaya;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The device's zone changed: the phone pass of every calendar the phone
 * shows, so its events with a floating time are projected again at the new
 * wall clock. A sync-adapter write, never an edit; the pass's quiet path
 * finds the rows projected for the old zone (docs/calendar-mapping.md,
 * zones).
 */
public class ZoneChange extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())
                || !PhoneMirror.CALENDAR.granted(context)) {
            return;
        }

        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        new Thread(
                        () -> {
                            try {
                                PimdirDb pimdir = new PimdirDb(app);
                                for (String collection : CalendarRows.shown(app)) {
                                    CalendarRows.phonePass(pimdir, collection);
                                }
                            } finally {
                                pending.finish();
                            }
                        })
                .start();
    }
}
