package org.pimalaya;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import org.pimalaya.client.Occurrence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The calendar screen: one agenda merging every calendar of
 * every account, the same merged view the contacts list gives contacts.
 *
 * <p>Rows are <em>occurrences</em>, not stored events: a weekly meeting
 * is one row per week inside the window. Expansion runs through the
 * bridge (ical-rs, RFC 5545 complete) at load time rather than at sync
 * time, because what an event renders as depends on the window being
 * shown, and the window moves while the stored object does not.
 *
 * <p>Everything here is civil time. The window bounds, the stamps the
 * bridge returns and the comparisons between them are all wall-clock,
 * with no offset resolved anywhere, which is exactly how RFC 5545
 * defines recurrence.
 */
final class CalendarList {
    /** How far ahead the agenda reaches, in days. */
    private static final int WINDOW_DAYS = 120;

    private final MainActivity host;
    private final EventStore store;
    private final Adapter adapter = new Adapter();

    /** The rows on screen: day headers interleaved with occurrences. */
    private final List<Row> rows = new ArrayList<>();

    CalendarList(MainActivity host, EventStore store) {
        this.host = host;
        this.store = store;
    }

    /** One agenda row: a day header, or an occurrence under one. */
    private static final class Row {
        final String day;
        final Occurrence occurrence;
        final String calendar;

        Row(String day) {
            this.day = day;
            this.occurrence = null;
            this.calendar = null;
        }

        Row(Occurrence occurrence, String calendar) {
            this.day = null;
            this.occurrence = occurrence;
            this.calendar = calendar;
        }

        boolean isHeader() {
            return occurrence == null;
        }
    }

    void setUp() {
        ListView list = host.findViewById(R.id.calendar_list);
        list.setAdapter(adapter);

        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                host.findViewById(R.id.calendar_refresh);
        refresh.setColorSchemeColors(host.ui.resolveColor(android.R.attr.colorAccent));
        refresh.setOnRefreshListener(
                () -> {
                    refresh.setRefreshing(false);
                    host.syncCalendars();
                });
    }

    /**
     * Rebuilds the agenda off the stored objects, expanding each into
     * the window and dropping what the merged filter hides.
     */
    void reload() {
        String from = today();
        String until = plusDays(from, WINDOW_DAYS);

        Map<String, EventStore.StoredCalendar> byCollection = new HashMap<>();
        for (EventStore.StoredCalendar calendar : store.loadCalendars()) {
            byCollection.put(calendar.id, calendar);
        }

        List<Row> occurrences = new ArrayList<>();
        for (EventStore.StoredEvent event : store.loadEvents()) {
            EventStore.StoredCalendar calendar = byCollection.get(event.collectionId);
            if (calendar == null) {
                continue;
            }
            if (!host.filter.accepts(calendar.accountEmail, calendar.id)) {
                continue;
            }
            try {
                for (Occurrence occurrence :
                        host.client.expandEvent(event.ical, from, until)) {
                    occurrences.add(new Row(occurrence, calendar.name));
                }
            } catch (Exception error) {
                // NOTE: one unparseable object must not empty the whole
                // agenda, so it is dropped with a log and the rest render.
                Log.w("pimalaya", "event expand failed: " + event.id, error);
            }
        }

        occurrences.sort((left, right) -> left.occurrence.start.compareTo(right.occurrence.start));

        rows.clear();
        String day = "";
        for (Row row : occurrences) {
            String rowDay = row.occurrence.start.substring(0, 8);
            if (!rowDay.equals(day)) {
                day = rowDay;
                rows.add(new Row(rowDay));
            }
            rows.add(row);
        }

        adapter.notifyDataSetChanged();
        host.findViewById(R.id.calendar_empty)
                .setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Today as a civil `YYYYMMDD` stamp, in the device's own zone. */
    private static String today() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        return String.format(
                java.util.Locale.US,
                "%04d%02d%02d",
                now.get(java.util.Calendar.YEAR),
                now.get(java.util.Calendar.MONTH) + 1,
                now.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private static String plusDays(String stamp, int days) {
        java.util.Calendar moment = java.util.Calendar.getInstance();
        moment.set(
                Integer.parseInt(stamp.substring(0, 4)),
                Integer.parseInt(stamp.substring(4, 6)) - 1,
                Integer.parseInt(stamp.substring(6, 8)));
        moment.add(java.util.Calendar.DAY_OF_MONTH, days);
        return String.format(
                java.util.Locale.US,
                "%04d%02d%02d",
                moment.get(java.util.Calendar.YEAR),
                moment.get(java.util.Calendar.MONTH) + 1,
                moment.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** `YYYYMMDD` to the device's medium date format. */
    private String dayLabel(String stamp) {
        java.util.Calendar moment = java.util.Calendar.getInstance();
        moment.set(
                Integer.parseInt(stamp.substring(0, 4)),
                Integer.parseInt(stamp.substring(4, 6)) - 1,
                Integer.parseInt(stamp.substring(6, 8)));
        return android.text.format.DateFormat.getMediumDateFormat(host)
                .format(moment.getTime());
    }

    /** The `HHMM` half of a civil stamp, as `HH:MM`. */
    private static String timeLabel(String stamp) {
        return stamp.substring(9, 11) + ":" + stamp.substring(11, 13);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return !rows.get(position).isHeader();
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_event, parent, false);
            }

            Row row = rows.get(position);
            TextView day = view.findViewById(R.id.event_day);
            TextView time = view.findViewById(R.id.event_time);
            View body = view.findViewById(R.id.event_body);

            if (row.isHeader()) {
                day.setVisibility(View.VISIBLE);
                day.setText(dayLabel(row.day));
                time.setVisibility(View.GONE);
                body.setVisibility(View.GONE);
                return view;
            }

            day.setVisibility(View.GONE);
            time.setVisibility(View.VISIBLE);
            body.setVisibility(View.VISIBLE);
            time.setText(
                    row.occurrence.allDay
                            ? host.getString(R.string.event_all_day)
                            : timeLabel(row.occurrence.start));

            ((TextView) view.findViewById(R.id.event_summary))
                    .setText(
                            row.occurrence.summary.isEmpty()
                                    ? host.getString(R.string.event_untitled)
                                    : row.occurrence.summary);

            // The subtitle carries the collection, which is what makes a
            // merged agenda readable: a row has to say where it came from.
            TextView subtitle = view.findViewById(R.id.event_subtitle);
            String detail =
                    row.occurrence.location.isEmpty()
                            ? row.calendar
                            : row.calendar + " · " + row.occurrence.location;
            subtitle.setText(detail);
            subtitle.setVisibility(View.VISIBLE);

            return view;
        }
    }
}
