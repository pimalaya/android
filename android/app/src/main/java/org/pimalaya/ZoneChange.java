package org.pimalaya;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.pimalaya.calendar.CalendarRows;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirDb;

/**
 * The device's zone changed: reprojects floating events on the phone.
 *
 * <p>Runs the phone pass of every shown calendar, a sync-adapter write, never
 * an edit; its quiet path finds the rows projected for the old zone
 * (docs/calendar-mapping.md, zones).
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
                                PimdirDb pimdir = App.of(app).pimdir();
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
