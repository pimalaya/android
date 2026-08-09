package org.pimalaya;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
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

    /** Seconds in a day, the threshold a length is told in days past. */
    private static final long DAY = 86400;

    private final MainActivity host;
    private final EventStore store;
    private final Adapter adapter = new Adapter();

    /** The rows on screen, one per occurrence, earliest first. */
    private final List<Row> rows = new ArrayList<>();

    CalendarList(MainActivity host, EventStore store) {
        this.host = host;
        this.store = store;
    }

    /**
     * One agenda row: an occurrence, the object it was expanded from and
     * the calendar holding it.
     *
     * <p>The object rides along because opening a row opens the
     * component behind it, and a recurrence's fifteenth Monday is not
     * something the store holds: it is computed, and only the object it
     * came from can be read for everything the row does not show.
     */
    private static final class Row {
        final Occurrence occurrence;
        final EventStore.StoredEvent event;
        final EventStore.StoredCalendar calendar;

        Row(
                Occurrence occurrence,
                EventStore.StoredEvent event,
                EventStore.StoredCalendar calendar) {
            this.occurrence = occurrence;
            this.event = event;
            this.calendar = calendar;
        }
    }

    void setUp() {
        ListView list = host.findViewById(R.id.calendar_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    Row row = rows.get(position);
                    host.eventView.open(row.occurrence, row.event, row.calendar);
                });

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

        rows.clear();
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
                    rows.add(new Row(occurrence, event, calendar));
                }
            } catch (Exception error) {
                // NOTE: one unparseable object must not empty the whole
                // agenda, so it is dropped with a log and the rest render.
                Log.w("pimalaya", "event expand failed: " + event.id, error);
            }
        }

        rows.sort((left, right) -> left.occurrence.start.compareTo(right.occurrence.start));

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

    /**
     * The instant a civil `YYYYMMDDTHHMMSS` stamp names, read in the
     * device's zone.
     *
     * <p>Resolving an offset here is not a contradiction of the civil
     * expansion above: the stamp is already the wall-clock moment the
     * rule produced, and this only hands it to the platform formatter,
     * which speaks instants. Nothing compares the result to anything.
     */
    static long stampOf(String civil) {
        if (civil == null || civil.length() < 8) {
            return 0;
        }
        java.util.Calendar moment = java.util.Calendar.getInstance();
        moment.clear();
        moment.set(
                Integer.parseInt(civil.substring(0, 4)),
                Integer.parseInt(civil.substring(4, 6)) - 1,
                Integer.parseInt(civil.substring(6, 8)));
        if (civil.length() >= 15) {
            moment.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(civil.substring(9, 11)));
            moment.set(java.util.Calendar.MINUTE, Integer.parseInt(civil.substring(11, 13)));
            moment.set(java.util.Calendar.SECOND, Integer.parseInt(civil.substring(13, 15)));
        }
        return moment.getTimeInMillis();
    }

    /** The seconds between two civil stamps, never negative. */
    static long secondsBetween(String start, String end) {
        long length = (stampOf(end) - stampOf(start)) / 1000;
        return Math.max(length, 0);
    }

    /**
     * How long an occurrence runs, told in the coarsest unit that stays
     * honest: days past a day, whole hours when it is whole hours,
     * minutes below the hour. Empty when it is a moment rather than a
     * span, which every journal entry and most to-dos are.
     */
    static String durationLabel(Context context, Occurrence occurrence) {
        if (occurrence.allDay) {
            return context.getString(R.string.event_all_day);
        }

        long seconds = secondsBetween(occurrence.start, occurrence.end);
        if (seconds <= 0) {
            return "";
        }
        if (seconds >= DAY) {
            return context.getString(R.string.event_duration_days, seconds / DAY);
        }

        long minutes = seconds / 60;
        if (minutes < 60) {
            return context.getString(R.string.event_duration_minutes, minutes);
        }
        return minutes % 60 == 0
                ? context.getString(R.string.event_duration_hours, minutes / 60)
                : context.getString(
                        R.string.event_duration_hours_minutes, minutes / 60, minutes % 60);
    }

    /**
     * How far off an occurrence is, in the app's shared date vocabulary:
     * in 20 minutes, in 3 days, 2 hours ago.
     *
     * <p>It sits where a mail row puts its date, and for the same
     * reason: the agenda is one scrolling scale of time, and the
     * distance is what says where on it a row is. An all-day entry
     * counts in whole days, since a birthday is on a day and reading it
     * as five hours ago because the day started this morning would be
     * nonsense.
     */
    static String countdownLabel(Context context, Occurrence occurrence) {
        long stamp = stampOf(occurrence.start);
        return occurrence.allDay
                ? Dates.relativeDays(context, stamp)
                : Dates.relative(context, stamp);
    }

    /** The glyph one component is drawn with. */
    static int glyphOf(String component) {
        if ("VTODO".equals(component)) {
            return R.drawable.ic_component_todo;
        }
        return "VJOURNAL".equals(component)
                ? R.drawable.ic_component_journal
                : R.drawable.ic_component_event;
    }

    /** What a component is called. */
    static int componentName(String component) {
        if ("VTODO".equals(component)) {
            return R.string.event_component_todo;
        }
        return "VJOURNAL".equals(component)
                ? R.string.event_component_journal
                : R.string.event_component_event;
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
        public View getView(int position, View recycled, ViewGroup parent) {
            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_event, parent, false);
            }

            Row row = rows.get(position);
            Occurrence occurrence = row.occurrence;

            ((TextView) view.findViewById(R.id.event_summary))
                    .setText(
                            occurrence.summary.isEmpty()
                                    ? host.getString(R.string.event_untitled)
                                    : occurrence.summary);

            // How long it runs and which calendar it is in, the way a
            // mail row says its mailbox and account: a merged agenda has
            // to say where a row came from.
            String duration = durationLabel(host, occurrence);
            ((TextView) view.findViewById(R.id.event_origin))
                    .setText(
                            duration.isEmpty()
                                    ? row.calendar.name
                                    : duration + " · " + row.calendar.name);

            ((TextView) view.findViewById(R.id.event_countdown))
                    .setText(countdownLabel(host, occurrence));

            // The disc stands for the calendar, initial and colour both,
            // the way the mail row's disc stands for its sender; the
            // colour is keyed by the calendar's id, so renaming one
            // keeps the colour the eye learned. What kind of entry it is
            // leads the row instead, in its own column.
            TextView avatar = view.findViewById(R.id.event_avatar);
            avatar.setText(Avatar.letter(row.calendar.name));
            avatar.setBackground(Avatar.disc(Avatar.colorOf(row.calendar.color, row.calendar.id)));

            ImageView component = view.findViewById(R.id.event_component);
            component.setImageResource(glyphOf(occurrence.component));
            component.setContentDescription(host.getString(componentName(occurrence.component)));

            return view;
        }
    }
}
