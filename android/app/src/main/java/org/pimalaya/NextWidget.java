package org.pimalaya;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * The Next widget: the open next actions on the home screen, due first.
 *
 * <p>The list is the one the Tasks tab last drew, kept on the device as the
 * capture widget's count is, so drawing it reads no store. A row opens its
 * task (MainActivity.ACTION_TASK), the header the Next list.
 */
public final class NextWidget extends AppWidgetProvider {
    /** Where the last Next list is kept, beside the capture widget's count. */
    static final String NEXT = "next_items";

    /** How many next actions are kept, a home screen's worth. */
    private static final int KEPT = 30;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        draw(context, manager, ids);
    }

    /** Keeps the open next actions and redraws every placed widget. */
    public static void list(Context context, List<Task> next) {
        JSONArray kept = new JSONArray();
        try {
            for (Task task : next.subList(0, Math.min(KEPT, next.size()))) {
                kept.put(new JSONObject().put("uid", task.uid()).put("summary", task.summary()));
            }
        } catch (JSONException error) {
            return;
        }
        String json = kept.toString();
        android.content.SharedPreferences preferences =
                context.getSharedPreferences(CaptureWidget.PREFERENCES, 0);
        if (json.equals(preferences.getString(NEXT, null))) {
            return;
        }
        preferences.edit().putString(NEXT, json).apply();

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, NextWidget.class));
        if (ids.length > 0) {
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_next_list);
        }
    }

    private static void draw(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_next);
            // NOTE: the data URI tells one widget's adapter from another's,
            // as intents differing in extras alone are the same intent.
            Intent adapter =
                    new Intent(context, Rows.class)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .setData(Uri.parse("pimalaya-widget://next/" + id));
            views.setRemoteAdapter(R.id.widget_next_list, adapter);
            views.setEmptyView(R.id.widget_next_list, R.id.widget_next_empty);

            Intent task = new Intent(MainActivity.ACTION_TASK).setClass(context, MainActivity.class);
            task.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // NOTE: mutable, as each row fills in its UID.
            views.setPendingIntentTemplate(
                    R.id.widget_next_list,
                    PendingIntent.getActivity(
                            context,
                            2,
                            task,
                            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

            Intent nextIntent =
                    new Intent(MainActivity.ACTION_NEXT).setClass(context, MainActivity.class);
            nextIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            views.setOnClickPendingIntent(
                    R.id.widget_next_header,
                    PendingIntent.getActivity(
                            context,
                            3,
                            nextIntent,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

            manager.updateAppWidget(id, views);
        }
    }

    /** The widget's rows, read from the list the tab last kept. */
    public static final class Rows extends RemoteViewsService {
        @Override
        public RemoteViewsFactory onGetViewFactory(Intent intent) {
            return new Factory(getApplicationContext());
        }
    }

    private static final class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;

        /** The kept rows, each a UID and a title. */
        private final List<String[]> rows = new ArrayList<>();

        Factory(Context context) {
            this.context = context;
        }

        @Override
        public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            rows.clear();
            String json =
                    context.getSharedPreferences(CaptureWidget.PREFERENCES, 0)
                            .getString(NEXT, "[]");
            try {
                JSONArray kept = new JSONArray(json);
                for (int index = 0; index < kept.length(); index++) {
                    JSONObject row = kept.getJSONObject(index);
                    rows.add(new String[] {row.getString("uid"), row.optString("summary")});
                }
            } catch (JSONException error) {
                rows.clear();
            }
        }

        @Override
        public void onDestroy() {
            rows.clear();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_next_row);
            if (position >= rows.size()) {
                return row;
            }
            String[] kept = rows.get(position);
            row.setTextViewText(
                    R.id.widget_next_title,
                    kept[1].isEmpty() ? context.getString(R.string.event_untitled) : kept[1]);
            row.setOnClickFillInIntent(
                    R.id.widget_next_row, new Intent().putExtra(MainActivity.EXTRA_TASK, kept[0]));
            return row;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
