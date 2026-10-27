package org.pimalaya.calendar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventTime;
import org.pimalaya.client.Occurrence;
import org.pimalaya.pimdir.DefaultCollection;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.Avatar;
import org.pimalaya.ui.CardSections;
import org.pimalaya.ui.Chips;
import org.pimalaya.ui.Dates;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.ListHeader;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.SectionsAdapter;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;

/**
 * The calendar screen: one agenda over every account, a card per day.
 *
 * <p>Rows are occurrences, expanded by the bridge at load time: the shown week
 * moves, the object does not. Expansion is civil (RFC 5545), a row placed at
 * its instant in the device's zone ({@link Zones}).
 */
public final class CalendarList {
    /** Seconds in a day, the threshold a length is told in days past. */
    private static final long DAY = 86400;

    private final Host host;
    private final CalendarParts parts;
    private final EventStore store;

    /** The page an agenda row opens onto. */
    private final EventView eventView;

    /** The pull-down's sync of the calendars. */
    private final Runnable pull;
    private final CardSections<Row> sections = new CardSections<>();
    private final Adapter adapter = new Adapter();

    /** The rows on screen, one per occurrence, earliest first. */
    private final List<Row> rows = new ArrayList<>();

    private ListHeader header;

    /**
     * The day the week card narrows the agenda to; null for the whole week.
     *
     * <p>A civil {@code YYYYMMDD} stamp.
     */
    private String selectedDay;

    /** How many weeks from this one the week card shows, negative before it. */
    private int weekOffset;

    /** Whether the Default chip narrows to the accounts' default calendars. */
    private boolean defaultOnly;

    /**
     * Opens a to-do row on the task page, false when it did not, which
     * leaves the row to the entry page.
     *
     * <p>Set by the activity, as the calendar does not know the Tasks tab.
     */
    public BiPredicate<EventStore.StoredEvent, EventStore.StoredCalendar> todoOpened;

    /** Builds the agenda; {@code pull} runs a pull to refresh. */
    public CalendarList(Host host, CalendarParts parts, EventView eventView, Runnable pull) {
        this.host = host;
        this.parts = parts;
        this.store = parts.events();
        this.eventView = eventView;
        this.pull = pull;
        eventView.list = this;
    }

    /**
     * One agenda row: an occurrence, its object, the calendar holding it.
     *
     * <p>The object rides along because a row opens the component behind it,
     * and a recurrence's fifteenth Monday is computed: only its object can be
     * read for the rest.
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
            this.start = Zones.instant(occurrence.start());
        }
    }

    /** Wires the list, its header and the week card's buttons. */
    public void setUp() {
        ListView list = host.findViewById(R.id.calendar_list);
        header = new ListHeader(host, list, Panels.CALENDAR);
        header.empty(host.findViewById(R.id.calendar_empty));
        header.view
                .findViewById(R.id.header_week_previous)
                .setOnClickListener(view -> moveWeek(weekOffset - 1));
        header.view
                .findViewById(R.id.header_week_next)
                .setOnClickListener(view -> moveWeek(weekOffset + 1));
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
                    if (row == null) {
                        return;
                    }
                    // NOTE: a conflicted to-do keeps the entry page, which
                    // alone resolves a conflict.
                    boolean todo =
                            "VTODO".equals(row.occurrence.component()) && !row.event.conflicted;
                    if (!todo || todoOpened == null || !todoOpened.test(row.event, row.calendar)) {
                        eventView.open(row.occurrence, row.event, row.calendar);
                    }
                });

        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                host.findViewById(R.id.calendar_refresh);
        refresh.setColorSchemeColors(host.ui.resolveColor(android.R.attr.colorAccent));
        refresh.setOnRefreshListener(
                () -> {
                    refresh.setRefreshing(false);
                    pull.run();
                });
    }

    /**
     * Rebuilds the agenda: objects expanded into the week, then filtered.
     *
     * <p>Off the main thread, the last reload winning: thousands of expansions
     * would freeze it. An expansion is kept while week and object stay, so a
     * reload after a sync expands only what changed.
     */
    public void reload() {
        // NOTE: the shown week alone, since the agenda never lists past it
        // and moving the week clears a picked day, and a day more either
        // side: the bridge compares the window with each start as written,
        // a start in another zone can fall a day off the reader's, and
        // render keeps the week by instant.
        String from = plusDays(shownWeek(today()), -1);
        String until = plusDays(from, 9);
        MergedFilter filter = MergedFilter.of(host, PimDomain.CALENDAR);
        Set<String> defaults =
                defaultOnly
                        ? DefaultCollection.shown(
                                host,
                                PimdirSummary.CALENDAR,
                                new PimdirCollections(parts.pimdir(), host)
                                        .list(PimdirSummary.CALENDAR))
                        : null;
        int asked = ++generation;

        reads.execute(
                () -> {
                    List<Row> read = rowsOf(from, until, filter, defaults);
                    host.postAlive(
                            () -> {
                                if (asked != generation) {
                                    return;
                                }
                                rows.clear();
                                rows.addAll(read);
                                render();
                            });
                });
    }

    /** Counts the reloads asked, so only the last one's rows land. */
    private int generation;

    /** Where the agenda's rows are read, one reload after another. */
    private final ExecutorService reads = Executors.newSingleThreadExecutor();

    /**
     * Each entry's last expanded occurrences, with their object.
     *
     * <p>By collection and id; read on {@link #reads} alone.
     */
    private final Map<String, Expanded> expanded = new HashMap<>();

    /** The window {@link #expanded} holds occurrences of. */
    private String expandedFrom;

    /** One entry's occurrences in a window, and the object behind them. */
    private static final class Expanded {
        final String ical;
        final List<Occurrence> occurrences;

        Expanded(String ical, List<Occurrence> occurrences) {
            this.ical = ical;
            this.occurrences = occurrences;
        }
    }

    /**
     * The agenda's rows in {@code [from, until)}, earliest first.
     *
     * <p>On {@link #reads}.
     */
    private List<Row> rowsOf(
            String from, String until, MergedFilter filter, Set<String> defaults) {
        if (!from.equals(expandedFrom)) {
            expanded.clear();
            expandedFrom = from;
        }
        Map<String, EventStore.StoredCalendar> byCollection = new HashMap<>();
        for (EventStore.StoredCalendar calendar : store.loadCalendars()) {
            byCollection.put(calendar.id, calendar);
        }

        List<Row> read = new ArrayList<>();
        Map<String, Expanded> kept = new HashMap<>();
        for (EventStore.StoredEvent event : store.loadEvents()) {
            EventStore.StoredCalendar calendar = byCollection.get(event.collectionId);
            if (calendar == null) {
                continue;
            }
            if (!filter.accepts(calendar.accountEmail, calendar.id)
                    || defaults != null && !defaults.contains(calendar.id)) {
                continue;
            }
            String key = event.collectionId + "\n" + event.id;
            Expanded known = expanded.get(key);
            if (known == null || !known.ical.equals(event.ical)) {
                try {
                    known =
                            new Expanded(
                                    event.ical,
                                    CalendarClient.expandEvent(event.ical, from, until));
                } catch (Exception error) {
                    // NOTE: one unparseable object must not empty the whole
                    // agenda, so it is dropped with a log and the rest render.
                    Log.w("pimalaya", "event expand failed: " + event.id, error);
                    continue;
                }
            }
            kept.put(key, known);
            for (Occurrence occurrence : known.occurrences) {
                read.add(new Row(occurrence, event, calendar));
            }
        }
        expanded.clear();
        expanded.putAll(kept);

        read.sort((left, right) -> Long.compare(left.start, right.start));
        return read;
    }

    /** Lays out the picked day, else the seven days of the shown week. */
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
     * Whether the agenda lists a civil day.
     *
     * <p>The picked day alone when there is one, else the seven days from the
     * shown week's first.
     */
    static boolean kept(String day, String selectedDay, String weekFirst) {
        if (selectedDay != null) {
            return day.equals(selectedDay);
        }
        return day.compareTo(weekFirst) >= 0 && day.compareTo(plusDays(weekFirst, 7)) < 0;
    }

    /**
     * The day an occurrence starts on, where the device is.
     *
     * <p>An all-day one on its date, which is in no zone; anything else on the
     * day its instant falls on.
     */
    private static String dayOf(Row row) {
        if (row.occurrence.start().isDate()) {
            return row.occurrence.start().time().substring(0, 8);
        }
        return Zones.stamp(
                Instant.ofEpochMilli(row.start).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                true);
    }

    /**
     * A day card's heading: today, tomorrow, else the weekday and date.
     *
     * <p>An agenda is read by the calendar, not by distance.
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
     * Fills the week card: seven days under month, year and week number.
     *
     * <p>Today takes the accent; a pressed day takes a filled disc and narrows
     * the agenda to itself, pressed again widening it back.
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
            weekday.setText(Dates.weekday(stamp));
            weekday.setTextSize(12);
            weekday.setTypeface(null, android.graphics.Typeface.BOLD);
            weekday.setTextColor(isToday ? accent : secondary);
            weekday.setGravity(android.view.Gravity.CENTER);

            TextView number = new TextView(host);
            number.setText(Dates.dayOfMonth(stamp));
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
        String month = Dates.month(stamp).toString();
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
        text.append(' ').append(Dates.year(stamp));
        text.setSpan(
                new android.text.style.ForegroundColorSpan(
                        host.ui.resolveColor(android.R.attr.textColorSecondary)),
                start,
                text.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** The list's header, with the week card. */
    public ListHeader header() {
        return header;
    }

    /** When an occurrence starts, as its card's leading column says it. */
    private String startLabel(Row row) {
        if (row.occurrence.allDay()) {
            return host.getString(R.string.event_all_day);
        }
        return Dates.time(host, row.start);
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

    /** A {@code YYYYMMDD} stamp as a Calendar in the device zone and locale. */
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
     * The seconds an occurrence runs, never negative.
     *
     * <p>Between the instants its ends name, so a flight starting in one zone
     * and ending in another is as long as it is.
     */
    static long secondsBetween(EventTime start, EventTime end) {
        long length = (Zones.instant(end) - Zones.instant(start)) / 1000;
        return Math.max(length, 0);
    }

    /**
     * How long an occurrence runs, in the coarsest honest unit.
     *
     * <p>Days past a day, whole hours when whole, minutes below the hour. Empty
     * for a moment rather than a span, as every journal entry and most to-dos
     * are.
     */
    static String durationLabel(Context context, Occurrence occurrence) {
        if (occurrence.allDay()) {
            return context.getString(R.string.event_all_day);
        }

        long seconds = secondsBetween(occurrence.start(), occurrence.end());
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
     * How far off an occurrence is: in 20 minutes, in 3 days, 2 hours ago.
     *
     * <p>Said beside the exact moment on the entry page. An all-day entry
     * counts whole days: a birthday is on a day, never five hours ago.
     */
    static String countdownLabel(Context context, Occurrence occurrence) {
        long stamp = Zones.instant(occurrence.start());
        return occurrence.allDay()
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

    private final class Adapter extends SectionsAdapter<Row> {
        Adapter() {
            super(sections);
        }

        @Override
        public boolean isEnabled(int position) {
            return !sections.isHeader(position);
        }

        @Override
        protected View rowView(int position, View recycled, ViewGroup parent) {
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
                            occurrence.summary().isEmpty()
                                    ? host.getString(R.string.event_untitled)
                                    : occurrence.summary());
            ((TextView) view.findViewById(R.id.event_start)).setText(startLabel(row));
            // NOTE: every occurrence of an entry both sides edited is flagged:
            // the page any of them opens asks every question the entry has.
            ImageView conflicted = view.findViewById(R.id.event_conflicted);
            conflicted.setImageTintList(
                    ColorStateList.valueOf(
                            host.ui.resolveColor(android.R.attr.colorError)));
            conflicted.setVisibility(row.event.conflicted ? View.VISIBLE : View.GONE);
            String kind = host.getString(componentName(occurrence.component()));
            String duration = durationLabel(host, occurrence);
            ((TextView) view.findViewById(R.id.event_kind))
                    .setText(duration.isEmpty() ? kind : kind + " · " + duration);

            // NOTE: a merged agenda has to say where a row came from, as a
            // mail row says its mailbox and account.
            ((TextView) view.findViewById(R.id.event_origin))
                    .setText(row.calendar.name + " · " + row.calendar.accountEmail);

            // NOTE: the colour is keyed by the calendar's id, so renaming one
            // keeps the colour the eye learned.
            TextView avatar = view.findViewById(R.id.event_avatar);
            avatar.setText(Avatar.letter(row.calendar.name));
            avatar.setBackground(
                    Avatar.disc(host, Avatar.colorOf(row.calendar.color, row.calendar.id)));

            return view;
        }
    }
}
