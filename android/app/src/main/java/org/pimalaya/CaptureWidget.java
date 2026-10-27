package org.pimalaya;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * The capture widget: a bar opening the capture sheet, the Inbox count beside.
 *
 * <p>A widget takes no typed text (RemoteViews), so a tap opens the sheet over
 * the home screen (MainActivity.ACTION_CAPTURE). The count is the one the
 * Tasks tab last read, kept on the device, so drawing it reads no store.
 */
public final class CaptureWidget extends AppWidgetProvider {
    /** Where the last Inbox count is kept. */
    static final String PREFERENCES = "tasks";

    static final String INBOX = "inbox_count";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        draw(context, manager, ids);
    }

    /** Keeps the Inbox count and redraws every placed widget. */
    public static void count(Context context, int inbox) {
        context.getSharedPreferences(PREFERENCES, 0).edit().putInt(INBOX, inbox).apply();
        refresh(context);
    }

    /** Redraws every placed widget with the count last kept. */
    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, CaptureWidget.class));
        if (ids.length > 0) {
            draw(context, manager, ids);
        }
    }

    private static void draw(Context context, AppWidgetManager manager, int[] ids) {
        int inbox = context.getSharedPreferences(PREFERENCES, 0).getInt(INBOX, 0);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_capture);
        views.setTextViewText(R.id.widget_inbox, context.getString(R.string.widget_inbox, inbox));

        Intent capture = new Intent(MainActivity.ACTION_CAPTURE).setClass(context, MainActivity.class);
        capture.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        views.setOnClickPendingIntent(
                R.id.widget_capture,
                PendingIntent.getActivity(
                        context,
                        0,
                        capture,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        Intent inboxIntent =
                new Intent(MainActivity.ACTION_INBOX).setClass(context, MainActivity.class);
        inboxIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        views.setOnClickPendingIntent(
                R.id.widget_inbox,
                PendingIntent.getActivity(
                        context,
                        1,
                        inboxIntent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        manager.updateAppWidget(ids, views);
    }
}
