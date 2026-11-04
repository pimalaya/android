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
import org.pimalaya.ui.Avatar;
import org.pimalaya.ui.CardSections;
import org.pimalaya.ui.FilterPanel;
import org.pimalaya.ui.Dates;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.ListFilter;
import org.pimalaya.ui.ListHeader;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.SectionsAdapter;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;

/**
 * The calendar screen: one agenda over every account, a card per day.
 *
 * <p>Rows are occurrences, read from the search index's occurrence table over
 * the window shown (pimdir SEARCH §7), each row's title and kind from its
 * item's summary: no body is read until a row opens. A row is placed at its
 * instant in the device's zone ({@link Zones}). With no index, or one
 * refused, the bridge expands every object instead, as it did before.
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

    /**
     * The panel's sections: the calendars and the times of day ticked, none
     * meaning any; the dates from and to (as {@code from:YYYYMMDD} and
     * {@code to:YYYYMMDD}); what a row must have.
     */
    private static final String CALENDARS = "calendars_on";

    private static final String WHEN = "when_dates";

    private static final String TIME = "time_on";

    private static final String HAS = "has";

    private static final String[] TIME_KEYS = {"allday", "timed"};

    private static final int[] TIME_LABELS = {R.string.filter_all_day, R.string.filter_timed};

    private static final String[] HAS_KEYS = {"location"};

    private static final int[] HAS_LABELS = {R.string.filter_location};

    /** The list's side of now, the first being the default: the list alone offers it. */
    private static final String TIMEFRAME = "timeframe";

    private static final String[] TIMEFRAME_KEYS = {"upcoming", "past"};

    private static final int[] TIMEFRAME_LABELS = {R.string.filter_upcoming, R.string.filter_past};

    /** How many days the list reaches from today, in its timeframe's direction. */
    static final int LIST_DAYS = 92;

    /** Where the mode is kept, the list being the default. */
    private static final String MODE_PREFERENCES = "calendar-mode";

    private static final String MODE_LIST = "list";

    /** Whether the agenda lists from now rather than showing the week card. */
    private boolean listing = true;

    /** What a past row is drawn at, as a done task. */
    private static final float PAST_ALPHA = 0.5f;

    /** The rows of the last render that are over, drawn dimmed. */
    private final Set<Row> past = new java.util.HashSet<>();

    /** What the agenda keeps of the calendars synced. */
    private ListFilter filter;

    private FilterPanel panel;

    /** The calendars the panel offers, by account then id, as last read. */
    private Map<String, Map<String, String>> offered = new LinkedHashMap<>();

    /** The settled search over the titles, lower-cased. */
    private String query = "";

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

        /**
         * Whether the index placed it, its occurrence then its instants and
         * its master's summary alone: the one it opens is read off the body.
         */
        final boolean indexed;

        Row(
                Occurrence occurrence,
                EventStore.StoredEvent event,
                EventStore.StoredCalendar calendar,
                boolean indexed) {
            this.occurrence = occurrence;
            this.event = event;
            this.calendar = calendar;
            this.start = Zones.instant(occurrence.start());
            this.indexed = indexed;
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
        header.search(
                R.string.calendar_search,
                settled -> {
                    query = settled;
                    render();
                });
        filter = new ListFilter(host, "calendar", CALENDARS, WHEN, TIME, HAS, TIMEFRAME);
        panel = new FilterPanel(host, header, this::render);
        listing = host.getSharedPreferences(MODE_PREFERENCES, 0).getBoolean(MODE_LIST, true);
        host.findViewById(R.id.calendar_mode_list).setOnClickListener(view -> setListing(true));
        host.findViewById(R.id.calendar_mode_week).setOnClickListener(view -> setListing(false));
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
                        eventView.open(opened(row), row.event, row.calendar);
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
     * Rebuilds the agenda: the window's occurrences read off the search
     * index, then filtered.
     *
     * <p>Off the main thread, the last reload winning. With no index the
     * objects are expanded instead, an expansion kept while window and object
     * stay, so a reload after a sync expands only what changed.
     */
    public void reload() {
        // NOTE: the shown week alone, since the agenda never lists past it
        // and moving the week clears a picked day, and a day more either
        // side: the bridge compares the window with each start as written,
        // a start in another zone can fall a day off the reader's, and
        // render keeps the week by instant.
        // A range set in When replaces the week: from its first day, eight
        // days on when it states no last, and back when it states no first.
        // The list reads its days with the same day of slack either side.
        String first = bound("from:");
        String last = bound("to:");
        String from;
        String until;
        if (listing) {
            String[] days = listDays(today(), showsPast(), first, last);
            from = plusDays(days[0], -1);
            // NOTE: an empty span still reads a window, which render
            // then keeps nothing of.
            until = plusDays(days[1].compareTo(days[0]) < 0 ? days[0] : days[1], 2);
        } else {
            from =
                    first != null
                            ? plusDays(first, -1)
                            : last != null
                                    ? plusDays(last, -8)
                                    : plusDays(shownWeek(today()), -1);
            until = last != null ? plusDays(last, 2) : plusDays(from, 9);
        }
        MergedFilter filter = MergedFilter.of(host, PimDomain.CALENDAR);
        int asked = ++generation;

        reads.execute(
                () -> {
                    Map<String, Map<String, String>> found = new LinkedHashMap<>();
                    Coverage seen = new Coverage();
                    List<Row> read = rowsOf(from, until, filter, found, seen);
                    host.postAlive(
                            () -> {
                                if (asked != generation) {
                                    return;
                                }
                                loaded = true;
                                rows.clear();
                                rows.addAll(read);
                                offered = found;
                                coverage = seen;
                                render();
                            });
                });
    }

    /** Counts the reloads asked, so only the last one's rows land. */
    private int generation;

    /**
     * Whether a reload has landed, before which no row means nothing read
     * yet rather than an empty calendar.
     */
    private boolean loaded;

    /** Where the agenda's rows are read, one reload after another. */
    private final ExecutorService reads =
            Executors.newSingleThreadExecutor(task -> new Thread(task, "calendar-list-reads"));

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

    /** What the last window read could not see (pimdir SEARCH §7, §8.2). */
    private static final class Coverage {
        /** Whether the window leaves the horizon the index holds. */
        boolean horizonLeft;

        /** How many series a bound cut before the window's end. */
        long cut;
    }

    /** What the shown window could not see, as last read. */
    private Coverage coverage = new Coverage();

    /**
     * The agenda's rows in {@code [from, until)}, earliest first: the index's
     * occurrences, else every object expanded when there is no index.
     *
     * <p>On {@link #reads}.
     */
    private List<Row> rowsOf(
            String from,
            String until,
            MergedFilter filter,
            Map<String, Map<String, String>> found,
            Coverage seen) {
        Map<String, EventStore.StoredCalendar> byCollection = new HashMap<>();
        for (EventStore.StoredCalendar calendar : store.loadCalendars()) {
            byCollection.put(calendar.id, calendar);
            if (filter.accepts(calendar.accountEmail, calendar.id)) {
                found.computeIfAbsent(calendar.accountEmail, key -> new LinkedHashMap<>())
                        .put(calendar.id, calendar.name);
            }
        }

        org.pimalaya.pimdir.PimdirIndexDb index = store.index();
        org.json.JSONObject agenda = null;
        try {
            agenda = index == null ? null : index.agenda(instantOf(from), instantOf(until));
        } catch (RuntimeException failed) {
            Log.w("pimalaya", "search index unanswered", failed);
        }
        if (agenda == null) {
            return expandedRows(from, until, filter, byCollection);
        }

        Set<String> conflicted = store.conflicted();
        seen.horizonLeft = agenda.optBoolean("horizonLeft");
        seen.cut = agenda.optLong("cut");
        ZoneId floating = zoneOf(agenda.optString("floatingZone"));
        org.json.JSONArray occurrences = agenda.optJSONArray("occurrences");
        List<Row> read = new ArrayList<>();
        Map<String, EventStore.StoredEvent> events = new HashMap<>();
        for (int at = 0; occurrences != null && at < occurrences.length(); at++) {
            org.json.JSONObject occurrence = occurrences.optJSONObject(at);
            String collection = occurrence.optString("collection");
            EventStore.StoredCalendar calendar = byCollection.get(collection);
            if (calendar == null || !filter.accepts(calendar.accountEmail, calendar.id)) {
                continue;
            }
            String id = occurrence.optString("linkId");
            String key = collection + "\n" + id;
            EventStore.StoredEvent event =
                    events.computeIfAbsent(
                            key,
                            any ->
                                    store.bodiless(
                                            collection,
                                            id,
                                            occurrence.isNull("handle")
                                                    ? null
                                                    : occurrence.optString("handle", null),
                                            occurrence.optString("object"),
                                            occurrence.optString("revision", ""),
                                            conflicted.contains(key)));
            read.add(new Row(occurrenceOf(occurrence, floating), event, calendar, true));
        }
        return read;
    }

    /**
     * One occurrence as the index wrote it (pimdir SEARCH §7): its instants
     * in UTC, a date's read in the zone the index read it in, so an all-day
     * entry keeps its day; its title, place and kind its master's.
     */
    static Occurrence occurrenceOf(org.json.JSONObject occurrence, ZoneId floating) {
        boolean allDay = occurrence.optBoolean("allDay");
        String component = occurrence.optString("component", "VEVENT");
        return new Occurrence(
                component.isEmpty() ? "VEVENT" : component,
                timeOf(occurrence.optString("start"), allDay, floating),
                timeOf(occurrence.optString("end"), allDay, floating),
                null,
                occurrence.optString("summary"),
                occurrence.optString("location"),
                allDay);
    }

    /** An RFC 3339 instant as a time: its day in {@code floating}, or in UTC. */
    private static EventTime timeOf(String instant, boolean date, ZoneId floating) {
        Instant moment = Instant.parse(instant);
        if (date) {
            return new EventTime(
                    Zones.stamp(moment.atZone(floating).toLocalDateTime(), true),
                    org.pimalaya.client.EventTimeKind.DATE,
                    "",
                    null);
        }
        return new EventTime(
                Zones.stamp(moment.atZone(java.time.ZoneOffset.UTC).toLocalDateTime(), false),
                org.pimalaya.client.EventTimeKind.UTC,
                "",
                null);
    }

    /** The zone the index names, UTC when it names none the device knows. */
    private static ZoneId zoneOf(String name) {
        try {
            return name.isEmpty() ? java.time.ZoneOffset.UTC : ZoneId.of(name);
        } catch (java.time.DateTimeException unknown) {
            return java.time.ZoneOffset.UTC;
        }
    }

    /** Midnight opening a civil {@code YYYYMMDD} day here, as RFC 3339 in UTC. */
    static String instantOf(String day) {
        return Instant.ofEpochMilli(stampOf(day))
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString();
    }

    /**
     * The occurrence a row opens: the one its body expands to at the row's
     * instant, with its own title and {@code RECURRENCE-ID}, for a row the
     * index placed; the row's own when the body expands to none there.
     */
    private Occurrence opened(Row row) {
        if (!row.indexed) {
            return row.occurrence;
        }
        String day = dayOf(row);
        try {
            for (Occurrence expanded :
                    CalendarClient.expandEvent(
                            row.event.ical(), plusDays(day, -1), plusDays(day, 2))) {
                if (Zones.instant(expanded.start()) == row.start) {
                    return expanded;
                }
            }
        } catch (Exception error) {
            Log.w("pimalaya", "event expand failed: " + row.event.id, error);
        }
        return row.occurrence;
    }

    /**
     * The agenda's rows in {@code [from, until)}, every object expanded by
     * the bridge, earliest first: the store's index absent or refused.
     *
     * <p>On {@link #reads}.
     */
    private List<Row> expandedRows(
            String from,
            String until,
            MergedFilter filter,
            Map<String, EventStore.StoredCalendar> byCollection) {
        if (!from.equals(expandedFrom)) {
            expanded.clear();
            expandedFrom = from;
        }
        List<EventStore.StoredEvent> events = store.loadEvents();

        List<Row> read = new ArrayList<>();
        Map<String, Expanded> kept = new HashMap<>();
        for (EventStore.StoredEvent event : events) {
            EventStore.StoredCalendar calendar = byCollection.get(event.collectionId);
            if (calendar == null) {
                continue;
            }
            if (!filter.accepts(calendar.accountEmail, calendar.id)) {
                continue;
            }
            String key = event.collectionId + "\n" + event.id;
            Expanded known = expanded.get(key);
            if (known == null || !known.ical.equals(event.ical())) {
                try {
                    known =
                            new Expanded(
                                    event.ical(),
                                    CalendarClient.expandEvent(event.ical(), from, until));
                } catch (Exception error) {
                    // NOTE: one unparseable object must not empty the whole
                    // agenda, so it is dropped with a log and the rest render.
                    Log.w("pimalaya", "event expand failed: " + event.id, error);
                    continue;
                }
            }
            kept.put(key, known);
            for (Occurrence occurrence : known.occurrences) {
                read.add(new Row(occurrence, event, calendar, false));
            }
        }
        expanded.clear();
        expanded.putAll(kept);

        read.sort((left, right) -> Long.compare(left.start, right.start));
        return read;
    }

    /**
     * The filter panel: the calendars synced, the dates from and to, the
     * times of day, what a row must have.
     */
    private void drawFilters() {
        ListFilter.State edited = filter.applied();
        Runnable changed =
                () -> {
                    filter.save();
                    render();
                };
        panel.begin();
        panel.collections(
                "calendars", R.string.filter_calendars, offered, edited.set(CALENDARS), changed);
        if (listing) {
            // NOTE: a reload rather than a render, the window turning
            // round with the timeframe.
            panel.choice(
                    "timeframe",
                    R.string.filter_timeframe,
                    TIMEFRAME_KEYS,
                    TIMEFRAME_LABELS,
                    edited.set(TIMEFRAME),
                    () -> {
                        filter.save();
                        reload();
                    });
        }
        drawWhen();
        panel.checks(
                "time", R.string.filter_time, TIME_KEYS, TIME_LABELS, edited.set(TIME), changed);
        panel.checks("has", R.string.filter_has, HAS_KEYS, HAS_LABELS, edited.set(HAS), changed);
        // NOTE: the week does not follow the timeframe, so a Past kept from
        // the list does not accent the button there.
        panel.end(
                !edited.set(CALENDARS).isEmpty()
                        || !edited.set(WHEN).isEmpty()
                        || !edited.set(TIME).isEmpty()
                        || !edited.set(HAS).isEmpty()
                        || showsPast());
    }

    /** Whether the list shows what is over, the week never doing so. */
    private boolean showsPast() {
        return listing && filter.applied().set(TIMEFRAME).contains("past");
    }

    /**
     * The first and last civil days the list shows, both kept.
     *
     * <p>Upcoming runs from today, or a later From, to {@link #LIST_DAYS} on,
     * or To instead, which narrows or widens it; Past runs back from today, or
     * an earlier To, to {@link #LIST_DAYS} back, or From instead. A To before
     * an Upcoming list's first day, or a From after a Past one's last, ends it
     * before it starts: it lists nothing.
     */
    static String[] listDays(String today, boolean past, String first, String last) {
        if (past) {
            return new String[] {
                first != null ? first : plusDays(today, -LIST_DAYS),
                last != null && last.compareTo(today) < 0 ? last : today
            };
        }
        return new String[] {
            first != null && first.compareTo(today) > 0 ? first : today,
            last != null ? last : plusDays(today, LIST_DAYS)
        };
    }

    /** Switches between the list and the week, the week opening on this one. */
    private void setListing(boolean on) {
        if (on == listing) {
            return;
        }
        listing = on;
        host.getSharedPreferences(MODE_PREFERENCES, 0).edit().putBoolean(MODE_LIST, on).apply();
        weekOffset = 0;
        selectedDay = null;
        reload();
        ListView list = host.findViewById(R.id.calendar_list);
        list.setSelection(0);
    }

    /** The bar's two modes, the one on drawn on the nav indicator. */
    private void drawModes() {
        int[] buttons = {R.id.calendar_mode_list, R.id.calendar_mode_week};
        for (int index = 0; index < buttons.length; index++) {
            boolean on = (index == 0) == listing;
            ImageView button = host.findViewById(buttons[index]);
            button.setBackgroundResource(on ? R.drawable.nav_indicator : R.drawable.ripple_circle);
            button.setImageTintList(
                    ColorStateList.valueOf(
                            host.ui.resolveColor(
                                    on
                                            ? android.R.attr.textColorPrimary
                                            : android.R.attr.textColorSecondary)));
            button.setSelected(on);
        }
    }

    /** When: a first day and a last, each a date or none, each a pill. */
    private void drawWhen() {
        String first = bound("from:");
        String last = bound("to:");
        String any = host.getString(R.string.filter_any_time);
        String from = host.getString(R.string.filter_from, first == null ? any : dateOf(first));
        String to = host.getString(R.string.filter_to, last == null ? any : dateOf(last));
        List<String> set = new ArrayList<>();
        if (first != null) {
            set.add(from);
        }
        if (last != null) {
            set.add(to);
        }
        panel.section(
                "when",
                R.string.filter_when,
                set.isEmpty() ? any : String.join(", ", set),
                !set.isEmpty(),
                list -> {
                    panel.action(
                            list,
                            from,
                            R.drawable.ic_calendar_today,
                            () -> pickBound("from:", first));
                    panel.action(
                            list, to, R.drawable.ic_calendar_today, () -> pickBound("to:", last));
                });
        if (first != null) {
            panel.pill(from, () -> setBound("from:", null));
        }
        if (last != null) {
            panel.pill(to, () -> setBound("to:", null));
        }
    }

    /** The day When holds under {@code prefix}, null for none. */
    private String bound(String prefix) {
        for (String held : filter.applied().set(WHEN)) {
            if (held.startsWith(prefix)) {
                return held.substring(prefix.length());
            }
        }
        return null;
    }

    /** Sets the day When holds under {@code prefix}, null to clear it, then reloads. */
    private void setBound(String prefix, String day) {
        filter.applied().set(WHEN).removeIf(held -> held.startsWith(prefix));
        if (day != null) {
            filter.applied().set(WHEN).add(prefix + day);
        }
        filter.save();
        reload();
    }

    /** Asks for a day, Clear leaving the bound unset. */
    private void pickBound(String prefix, String day) {
        java.time.LocalDate shown =
                day == null
                        ? java.time.LocalDate.now()
                        : java.time.LocalDate.parse(
                                day, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        android.app.DatePickerDialog dialog =
                new android.app.DatePickerDialog(
                        host,
                        (picker, year, month, date) ->
                                setBound(
                                        prefix,
                                        String.format(
                                                java.util.Locale.US,
                                                "%04d%02d%02d",
                                                year,
                                                month + 1,
                                                date)),
                        shown.getYear(),
                        shown.getMonthValue() - 1,
                        shown.getDayOfMonth());
        dialog.setButton(
                android.app.DatePickerDialog.BUTTON_NEUTRAL,
                host.getString(R.string.clear),
                (picker, which) -> setBound(prefix, null));
        dialog.show();
    }

    /** A civil day as the device spells a date. */
    private String dateOf(String day) {
        return Dates.date(host, stampOf(day));
    }

    /**
     * Lays out the list on its side of now, else the picked day or the seven
     * days of the shown week.
     */
    private void render() {
        String today = today();
        drawModes();
        drawFilters();
        ListFilter.State held = filter.applied();
        String first = shownWeek(today);
        String from = bound("from:");
        String to = bound("to:");
        boolean ranged = from != null || to != null;
        boolean pastSide = showsPast();
        String[] listed = listDays(today, pastSide, from, to);
        long now = System.currentTimeMillis();
        List<Row> upcoming = new ArrayList<>();
        List<Row> over = new ArrayList<>();
        for (Row row : rows) {
            Occurrence occurrence = row.occurrence;
            boolean ended = endOf(row) <= now;
            Set<String> calendars = held.set(CALENDARS);
            Set<String> times = held.set(TIME);
            if (!calendars.isEmpty() && !calendars.contains(row.calendar.id)
                    || !query.isEmpty()
                            && !occurrence
                                    .summary()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .contains(query)
                    || !times.isEmpty()
                            && !times.contains(occurrence.allDay() ? "allday" : "timed")
                    || held.set(HAS).contains("location") && occurrence.location().isEmpty()) {
                continue;
            }
            String day = dayOf(row);
            if (listing) {
                // NOTE: now parts the two sides, so a meeting running is
                // still upcoming and one over this morning is past.
                if (ended == pastSide
                        && day.compareTo(listed[0]) >= 0
                        && day.compareTo(listed[1]) <= 0) {
                    (ended ? over : upcoming).add(row);
                }
                continue;
            }
            // NOTE: a range set in When lists its days whatever the week.
            boolean kept =
                    ranged
                            ? (from == null || day.compareTo(from) >= 0)
                                    && (to == null || day.compareTo(to) <= 0)
                            : kept(day, selectedDay, first);
            if (kept) {
                (ended ? over : upcoming).add(row);
            }
        }

        // NOTE: what is over goes under the rest, the last first, as done
        // tasks go under open ones; the list's past side is only that, so
        // nothing there is dimmed.
        over.sort((left, right) -> Long.compare(right.start, left.start));
        past.clear();
        if (!listing) {
            past.addAll(over);
        }
        List<Row> shown = new ArrayList<>(upcoming);
        shown.addAll(over);
        // NOTE: the list heads each month other than today's with its name
        // in full, the week card naming the week's own.
        String month = today.substring(0, 6);
        sections.fill(
                shown,
                row ->
                        past.contains(row)
                                ? host.getString(R.string.calendar_past_day, dayLabel(dayOf(row)))
                                : dayLabel(dayOf(row)),
                row -> {
                    String of = dayOf(row).substring(0, 6);
                    return listing && !of.equals(month) ? of : null;
                },
                key -> monthOf(stampOf(key + "01")));
        adapter.notifyDataSetChanged();
        String meta =
                host.getResources()
                        .getQuantityString(
                                pastSide ? R.plurals.calendar_meta_past : R.plurals.calendar_meta,
                                shown.size(),
                                shown.size());
        // NOTE: what the index could not see says so (pimdir SEARCH §7):
        // occurrences outside its horizon, or past a series' cut.
        if (coverage.horizonLeft) {
            meta = host.getString(R.string.calendar_horizon_left, meta);
        }
        if (coverage.cut > 0) {
            meta =
                    host.getResources()
                            .getQuantityString(
                                    R.plurals.calendar_cut, (int) coverage.cut, meta, coverage.cut);
        }
        header.meta(meta);
        if (listing) {
            header.noWeek();
        } else {
            week(today);
        }
        // NOTE: a render before the first load (the search or the panel
        // redrawing) is no empty calendar, so the empty state waits.
        host.findViewById(R.id.calendar_empty)
                .setVisibility(loaded && shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** The instant a row ends, its start when it states no end. */
    private static long endOf(Row row) {
        EventTime end = row.occurrence.end();
        return end == null ? row.start : Zones.instant(end);
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
     * A day card's heading: today, tomorrow, yesterday, else the weekday and
     * date, the year with it when it is not this one.
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
        if (day.equals(plusDays(today, -1))) {
            return host.getString(R.string.date_yesterday);
        }
        boolean thisYear = day.substring(0, 4).equals(today.substring(0, 4));
        return DateUtils.formatDateTime(
                host,
                stampOf(day),
                DateUtils.FORMAT_SHOW_WEEKDAY
                        | DateUtils.FORMAT_SHOW_DATE
                        | (thisYear ? DateUtils.FORMAT_NO_YEAR : DateUtils.FORMAT_SHOW_YEAR));
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
            // NOTE: a row that is over steps back, the card behind it whole.
            ViewGroup card = view.findViewById(R.id.row_card);
            for (int index = 0; index < card.getChildCount(); index++) {
                card.getChildAt(index).setAlpha(past.contains(row) ? PAST_ALPHA : 1f);
            }

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
