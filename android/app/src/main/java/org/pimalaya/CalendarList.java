package org.pimalaya;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.pimalaya.client.EventTime;
import org.pimalaya.client.Occurrence;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The calendar screen: one agenda merging every calendar of
 * every account, the same merged view the contacts list gives contacts,
 * grouped into one card per day under the week.
 *
 * <p>Rows are <em>occurrences</em>, not stored events: a weekly meeting
 * is one row per week inside the window. Expansion runs through the
 * bridge (ical-rs, RFC 5545 complete) at load time rather than at sync
 * time, because what an event renders as depends on the week being
 * shown, and the week moves while the stored object does not.
 *
 * <p>The bridge expands on civil time, which is how RFC 5545 defines
 * recurrence, and hands each occurrence over with what it is civil in;
 * the rows are placed and labelled at the instant that names, in the
 * device's zone ({@link Zones}). A meeting at 09:00 New York shows at
 * 15:00 for a reader in Paris, and a Graph event stored in UTC at the
 * reader's hour.
 */
final class CalendarList {
    /** Seconds in a day, the threshold a length is told in days past. */
    private static final long DAY = 86400;

    private final MainActivity host;
    private final EventStore store;
    private final Adapter adapter = new Adapter();
    private final CardSections<Row> sections = new CardSections<>();

    /** The rows on screen, one per occurrence, earliest first. */
    private final List<Row> rows = new ArrayList<>();

    private ListHeader header;

    /**
     * The day the week card narrows the agenda to, as a civil `YYYYMMDD`
     * stamp; null for the whole shown week.
     */
    private String selectedDay;

    /** How many weeks from this one the week card shows, negative before it. */
    private int weekOffset;

    /** Whether the Default chip narrows the agenda to the shown accounts' default calendars. */
    private boolean defaultOnly;

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

        /** The instant it starts at, which orders and places the row. */
        final long start;

        Row(
                Occurrence occurrence,
                EventStore.StoredEvent event,
                EventStore.StoredCalendar calendar) {
            this.occurrence = occurrence;
            this.event = event;
            this.calendar = calendar;
            this.start = Zones.instant(occurrence.start);
        }
    }

    void setUp() {
        ListView list = host.findViewById(R.id.calendar_list);
        header = new ListHeader(host, list, MainActivity.PANEL_CALENDAR);
        header.empty(host.findViewById(R.id.calendar_empty));
        header.view
                .findViewById(R.id.header_week_previous)
                .setOnClickListener(view -> moveWeek(weekOffset - 1));
        header.view
                .findViewById(R.id.header_week_next)
                .setOnClickListener(view -> moveWeek(weekOffset + 1));
        // The week's number brings the card back to this week.
        header.view.findViewById(R.id.header_week_number).setOnClickListener(view -> moveWeek(0));
        Chips.add(
                host,
                header.chips(),
                R.string.chip_default,
                R.drawable.ic_star,
                on -> {
                    defaultOnly = on;
                    reload();
                });
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    Row row = sections.row(header.rowAt(position));
                    if (row != null) {
                        host.eventView.open(row.occurrence, row.event, row.calendar);
                    }
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
     * the shown week and dropping what the merged filter hides.
     */
    void reload() {
        // NOTE: the shown week alone, since the agenda never lists past it
        // and moving the week clears a picked day, and a day more either
        // side: the bridge compares the window with each start as written,
        // a start in another zone can fall a day off the reader's, and
        // render keeps the week by instant.
        String from = plusDays(shownWeek(today()), -1);
        String until = plusDays(from, 9);

        Map<String, EventStore.StoredCalendar> byCollection = new HashMap<>();
        for (EventStore.StoredCalendar calendar : store.loadCalendars()) {
            byCollection.put(calendar.id, calendar);
        }

        MergedFilter filter = host.filterOf(PimDomain.CALENDAR);
        Set<String> defaults = defaultOnly ? host.shownDefaults(PimDomain.CALENDAR) : null;
        rows.clear();
        for (EventStore.StoredEvent event : store.loadEvents()) {
            EventStore.StoredCalendar calendar = byCollection.get(event.collectionId);
            if (calendar == null) {
                continue;
            }
            if (!filter.accepts(calendar.accountEmail, calendar.id)
                    || defaults != null && !defaults.contains(calendar.id)) {
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

        rows.sort((left, right) -> Long.compare(left.start, right.start));
        render();
    }

    /**
     * Lays out what the week card lets through: the day picked in it,
     * or with none picked the seven days of the week it shows, this
     * week included.
     */
    private void render() {
        String today = today();
        String first = shownWeek(today);
        List<Row> shown = new ArrayList<>();
        for (Row row : rows) {
            if (kept(dayOf(row), selectedDay, first)) {
                shown.add(row);
            }
        }

        sections.fill(shown, row -> dayLabel(dayOf(row)));
        adapter.notifyDataSetChanged();
        header.meta(
                host.getResources()
                        .getQuantityString(R.plurals.calendar_meta, shown.size(), shown.size()));
        week(today);
        host.findViewById(R.id.calendar_empty)
                .setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * Whether the agenda lists a civil day: the picked day alone when
     * there is one, else the seven days from the shown week's first.
     */
    static boolean kept(String day, String selectedDay, String weekFirst) {
        if (selectedDay != null) {
            return day.equals(selectedDay);
        }
        return day.compareTo(weekFirst) >= 0 && day.compareTo(plusDays(weekFirst, 7)) < 0;
    }

    /**
     * The day an occurrence starts on, where the device is: an all-day
     * one on its date, which is in no zone, anything else on the day its
     * instant falls on.
     */
    private static String dayOf(Row row) {
        if (row.occurrence.start.isDate()) {
            return row.occurrence.start.time.substring(0, 8);
        }
        return Zones.stamp(
                Instant.ofEpochMilli(row.start).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                true);
    }

    /**
     * What a day's card is headed with: today and tomorrow by name, then
     * the weekday and the date, since an agenda is read by the calendar
     * and not by distance.
     */
    private String dayLabel(String day) {
        String today = today();
        if (day.equals(today)) {
            return host.getString(R.string.date_today);
        }
        if (day.equals(plusDays(today, 1))) {
            return host.getString(R.string.date_tomorrow);
        }
        return DateUtils.formatDateTime(
                host,
                stampOf(day),
                DateUtils.FORMAT_SHOW_WEEKDAY
                        | DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_NO_YEAR);
    }

    /**
     * Fills the week card: the shown week's seven days, each a weekday
     * over its number, under the month and year and the week's number.
     * Today takes the accent, and a day pressed takes a filled disc and
     * narrows the agenda to itself, pressing it again widening it back.
     */
    private void week(String today) {
        String first = shownWeek(today);
        // NOTE: the week's Thursday names its month and its number, which
        // is how ISO 8601 assigns a week straddling two of either.
        String middle = plusDays(first, 3);
        java.util.Calendar thursday = calendarOf(middle);
        thursday.setMinimalDaysInFirstWeek(4);

        LinearLayout days =
                header.week(
                        monthOf(stampOf(middle)),
                        host.getString(
                                R.string.week_number,
                                thursday.get(java.util.Calendar.WEEK_OF_YEAR)));
        days.removeAllViews();
        int accent = host.ui.resolveColor(android.R.attr.colorAccent);
        int primary = host.ui.resolveColor(android.R.attr.textColorPrimary);
        int secondary = host.ui.resolveColor(android.R.attr.textColorSecondary);
        for (int offset = 0; offset < 7; offset++) {
            String day = plusDays(first, offset);
            long stamp = stampOf(day);
            boolean isToday = day.equals(today);
            boolean selected = day.equals(selectedDay);

            TextView weekday = new TextView(host);
            weekday.setText(DateFormat.format("EEE", new Date(stamp)));
            weekday.setTextSize(12);
            weekday.setTypeface(null, android.graphics.Typeface.BOLD);
            weekday.setTextColor(isToday ? accent : secondary);
            weekday.setGravity(android.view.Gravity.CENTER);

            TextView number = new TextView(host);
            number.setText(DateFormat.format("d", new Date(stamp)));
            number.setTextSize(17);
            number.setTypeface(null, android.graphics.Typeface.BOLD);
            number.setGravity(android.view.Gravity.CENTER);
            if (selected) {
                android.graphics.drawable.GradientDrawable disc =
                        new android.graphics.drawable.GradientDrawable();
                disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                disc.setColor(isToday ? accent : primary);
                number.setBackground(disc);
                number.setTextColor(
                        isToday
                                ? host.accentContrast()
                                : host.ui.resolveColor(android.R.attr.colorBackground));
            } else {
                number.setTextColor(isToday ? accent : primary);
            }
            LinearLayout.LayoutParams numberParams =
                    new LinearLayout.LayoutParams(host.dp(38), host.dp(38));
            numberParams.topMargin = host.dp(8);

            LinearLayout cell = new LinearLayout(host);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.addView(
                    weekday,
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
            cell.addView(number, numberParams);
            cell.setOnClickListener(
                    view -> {
                        selectedDay = selected ? null : day;
                        render();
                        ListView list = host.findViewById(R.id.calendar_list);
                        list.setSelection(0);
                    });

            days.addView(
                    cell,
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /** The first day of the week the card shows. */
    private String shownWeek(String today) {
        return plusDays(firstOfWeek(today), 7 * weekOffset);
    }

    /** Moves the card by whole weeks, back to this one with zero. */
    private void moveWeek(int offset) {
        weekOffset = offset;
        selectedDay = null;
        reload();
        ListView list = host.findViewById(R.id.calendar_list);
        list.setSelection(0);
    }

    /** The first day of the week a day falls in, by the device's locale. */
    static String firstOfWeek(String day) {
        java.util.Calendar moment = calendarOf(day);
        int back =
                (moment.get(java.util.Calendar.DAY_OF_WEEK) - moment.getFirstDayOfWeek() + 7) % 7;
        return plusDays(day, -back);
    }

    /** The month in full, then its year in a lighter tone. */
    private CharSequence monthOf(long stamp) {
        String month = DateFormat.format("LLLL", new Date(stamp)).toString();
        if (!month.isEmpty()) {
            month = month.substring(0, 1).toUpperCase() + month.substring(1);
        }
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(month);
        text.setSpan(
                new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0,
                month.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = text.length();
        text.append(' ').append(DateFormat.format("yyyy", new Date(stamp)));
        text.setSpan(
                new android.text.style.ForegroundColorSpan(
                        host.ui.resolveColor(android.R.attr.textColorSecondary)),
                start,
                text.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    ListHeader header() {
        return header;
    }

    /** When an occurrence starts, as its card's leading column says it. */
    private String startLabel(Row row) {
        if (row.occurrence.allDay) {
            return host.getString(R.string.event_all_day);
        }
        return DateFormat.getTimeFormat(host).format(new Date(row.start));
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
        java.util.Calendar moment = calendarOf(stamp);
        moment.add(java.util.Calendar.DAY_OF_MONTH, days);
        return String.format(
                java.util.Locale.US,
                "%04d%02d%02d",
                moment.get(java.util.Calendar.YEAR),
                moment.get(java.util.Calendar.MONTH) + 1,
                moment.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** A civil `YYYYMMDD` stamp as a calendar in the device's zone and locale. */
    private static java.util.Calendar calendarOf(String stamp) {
        java.util.Calendar moment = java.util.Calendar.getInstance();
        moment.clear();
        moment.set(
                Integer.parseInt(stamp.substring(0, 4)),
                Integer.parseInt(stamp.substring(4, 6)) - 1,
                Integer.parseInt(stamp.substring(6, 8)));
        return moment;
    }

    /** Midnight opening a civil `YYYYMMDD` day, in the device's zone. */
    static long stampOf(String day) {
        return Zones.civil(day)
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    /**
     * The seconds an occurrence runs, never negative: between the
     * instants its ends name, so an event starting in one zone and ending
     * in another (a flight) is as long as it is.
     */
    static long secondsBetween(EventTime start, EventTime end) {
        long length = (Zones.instant(end) - Zones.instant(start)) / 1000;
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
     * <p>The entry page says it beside the exact moment. An all-day
     * entry counts in whole days, since a birthday is on a day and
     * reading it as five hours ago because the day started this morning
     * would be nonsense.
     */
    static String countdownLabel(Context context, Occurrence occurrence) {
        long stamp = Zones.instant(occurrence.start);
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
            return sections.size();
        }

        @Override
        public Object getItem(int position) {
            return sections.row(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return sections.isHeader(position) ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int position) {
            return !sections.isHeader(position);
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            if (sections.isHeader(position)) {
                return sections.headerView(position, recycled, parent);
            }

            View view = recycled;
            if (view == null) {
                view = LayoutInflater.from(host).inflate(R.layout.item_event, parent, false);
            }
            sections.shape(view, position);
            view.findViewById(R.id.event_divider)
                    .setVisibility(sections.opensCard(position) ? View.GONE : View.VISIBLE);

            Row row = sections.row(position);
            Occurrence occurrence = row.occurrence;

            ((TextView) view.findViewById(R.id.event_summary))
                    .setText(
                            occurrence.summary.isEmpty()
                                    ? host.getString(R.string.event_untitled)
                                    : occurrence.summary);
            ((TextView) view.findViewById(R.id.event_start)).setText(startLabel(row));
            // The warning flag of an entry both sides edited, the contact
            // row's, on every occurrence of it: the page any of them opens
            // asks every question the entry has.
            ImageView conflicted = view.findViewById(R.id.event_conflicted);
            conflicted.setImageTintList(
                    ColorStateList.valueOf(
                            host.ui.resolveColor(android.R.attr.colorError)));
            conflicted.setVisibility(row.event.conflicted ? View.VISIBLE : View.GONE);
            // What kind of entry it is, by name, then how long it runs.
            String kind = host.getString(componentName(occurrence.component));
            String duration = durationLabel(host, occurrence);
            ((TextView) view.findViewById(R.id.event_kind))
                    .setText(duration.isEmpty() ? kind : kind + " · " + duration);

            // Which calendar and account it is in, the way a mail row says
            // its mailbox and account: a merged agenda has to say where a
            // row came from.
            ((TextView) view.findViewById(R.id.event_origin))
                    .setText(row.calendar.name + " · " + row.calendar.accountEmail);
            host.ui.syncMark(
                    view.findViewById(R.id.event_unsynced), row.event.unsynced, row.event.refused);

            // The disc stands for the calendar, initial and colour both,
            // the way the mail row's disc stands for its sender; the
            // colour is keyed by the calendar's id, so renaming one
            // keeps the colour the eye learned.
            TextView avatar = view.findViewById(R.id.event_avatar);
            avatar.setText(Avatar.letter(row.calendar.name));
            avatar.setBackground(
                    Avatar.disc(host, Avatar.colorOf(row.calendar.color, row.calendar.id)));

            return view;
        }
    }
}
