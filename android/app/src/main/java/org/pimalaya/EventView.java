package org.pimalaya;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.EventDetail;
import org.pimalaya.client.EventSplit;
import org.pimalaya.client.EventTime;
import org.pimalaya.client.Occurrence;
import org.pimalaya.client.PimalayaClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The page one agenda row opens onto: what the entry is, and the form
 * that edits it, which are the same page.
 *
 * <p>The same page as a contact's, and in the same way: one scroll of
 * sections, each row tappable into a dialog, a bar button that adds a
 * property the entry does not have yet, and the FAB to save. A contact
 * has no separate reading screen either, and a calendar entry gains
 * nothing from one: everything worth reading is worth correcting where
 * it is read.
 *
 * <p>Which sections and which properties follow the component, which is
 * the honest difference between the three: an event runs between two
 * moments, a to-do is due and partly done, a journal entry is written on
 * a day and has neither. Offering a to-do an end would be offering to
 * write a property RFC 5545 does not give it.
 *
 * <p>Opened on an occurrence of a series, it shows and edits that
 * occurrence, and asks on save and on delete whether the change is for
 * this occurrence, this and following, or all of them: an override and
 * an {@code EXDATE}, a split series, or the series itself. Its dates
 * show at the reader's time and are written back in the zone they were
 * in.
 */
final class EventView {
    /** What an event's STATUS may be (RFC 5545 3.8.1.11). */
    private static final String[] EVENT_STATUS = {"", "TENTATIVE", "CONFIRMED", "CANCELLED"};

    /** What a to-do's STATUS may be. */
    private static final String[] TODO_STATUS = {
        "", "NEEDS-ACTION", "IN-PROCESS", "COMPLETED", "CANCELLED",
    };

    /** What a journal entry's STATUS may be. */
    private static final String[] JOURNAL_STATUS = {"", "DRAFT", "FINAL", "CANCELLED"};

    /**
     * One editable property: the key the bridge's edit object uses, the
     * section it belongs to, its label, how it is edited, and which
     * components have it at all.
     */
    private static final class Field {
        final String key;
        final int section;
        final int label;
        final Kind kind;
        final String[] components;

        Field(String key, int section, int label, Kind kind, String... components) {
            this.key = key;
            this.section = section;
            this.label = label;
            this.kind = kind;
            this.components = components;
        }

        boolean of(String component) {
            for (String candidate : components) {
                if (candidate.equals(component)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** How one field is edited. */
    private enum Kind {
        TEXT,
        LONG_TEXT,
        NUMBER,
        DATE,
        STATUS,
    }

    private static final String EVENT = EventDetail.EVENT;
    private static final String TODO = EventDetail.TODO;
    private static final String JOURNAL = EventDetail.JOURNAL;

    /** Which occurrences a change applies to, as the bridge names them. */
    private static final String[] SCOPES = {"this", "following", "all"};

    /** The dates a page edits, as the bridge's edit object keys them. */
    private static final String[] DATES = {"start", "end", "due", "completed"};

    /** The text fields, likewise. */
    private static final String[] TEXTS = {
        "summary", "description", "location", "url", "status", "categories", "priority",
        "percentComplete",
    };

    /**
     * Every property the page can edit, in the order its sections draw
     * them. The placing date and the all-day switch are not here: they
     * are what a component <em>is</em>, so they always show rather than
     * waiting to be added.
     */
    private static final Field[] CATALOG = {
        new Field("end", R.string.event_section_when, R.string.event_field_end, Kind.DATE, EVENT),
        new Field("due", R.string.event_section_when, R.string.event_field_due, Kind.DATE, TODO),
        new Field(
                "completed",
                R.string.event_section_progress,
                R.string.event_field_completed,
                Kind.DATE,
                TODO),
        new Field(
                "percentComplete",
                R.string.event_section_progress,
                R.string.event_field_progress,
                Kind.NUMBER,
                TODO),
        new Field(
                "priority",
                R.string.event_section_progress,
                R.string.event_field_priority,
                Kind.NUMBER,
                EVENT,
                TODO),
        new Field(
                "location",
                R.string.event_section_where,
                R.string.event_field_location,
                Kind.TEXT,
                EVENT,
                TODO),
        new Field(
                "summary",
                R.string.event_section_details,
                R.string.event_field_summary,
                Kind.TEXT,
                EVENT,
                TODO,
                JOURNAL),
        new Field(
                "description",
                R.string.event_section_details,
                R.string.event_field_description,
                Kind.LONG_TEXT,
                EVENT,
                TODO,
                JOURNAL),
        new Field(
                "categories",
                R.string.event_section_details,
                R.string.event_field_categories,
                Kind.TEXT,
                EVENT,
                TODO,
                JOURNAL),
        new Field(
                "status",
                R.string.event_section_details,
                R.string.event_field_status,
                Kind.STATUS,
                EVENT,
                TODO,
                JOURNAL),
        new Field(
                "url",
                R.string.event_section_details,
                R.string.event_field_url,
                Kind.TEXT,
                EVENT,
                TODO,
                JOURNAL),
    };

    private final MainActivity host;
    private final Sections sections;

    /** What is open: the row that was tapped, and where it lives. */
    private Occurrence occurrence;

    private EventStore.StoredEvent event;
    private EventStore.StoredCalendar calendar;

    /** The object as it was read, for the parts the form does not edit. */
    private EventDetail detail;

    /**
     * Whether the entry is one the server has never seen.
     *
     * <p>It decides the save's precondition and nothing else: a create
     * is guarded on the resource not existing, an edit on it not having
     * moved, and the page in between is the same page.
     */
    private boolean creating;

    /** The working values, keyed as the bridge's edit object keys them. */
    private JSONObject model = new JSONObject();

    /** The working dates, each with the zone it is in; absent when unset. */
    private final Map<String, EventTime> times = new HashMap<>();

    /**
     * The values and dates as the page opened, so a save sends only what
     * changed: a date it does not touch keeps its line byte for byte, and
     * an edit of a whole series moves only the dates that moved.
     */
    private JSONObject opened = new JSONObject();

    private final Map<String, EventTime> openedTimes = new HashMap<>();

    /** Fields the user added this session, so their empty row stays. */
    private final Set<String> revealed = new HashSet<>();

    EventView(MainActivity host) {
        this.host = host;
        this.sections =
                new Sections(host, (LinearLayout) host.findViewById(R.id.event_view_page));
    }

    /** What the bar calls the entry: its summary, else its kind. */
    String title() {
        String summary = value("summary");
        return summary.isEmpty()
                ? host.getString(CalendarList.componentName(component()))
                : summary;
    }

    /**
     * Opens the page on a new entry, in the given calendar.
     *
     * <p>The object is built before the page opens rather than at save
     * time, so what is edited is an object like any other and the page
     * has one shape: the entry exists locally the moment it is started,
     * and it is the save that decides whether the server has it yet.
     *
     * <p>It is placed at the next whole hour, which is the guess that
     * needs the least correcting: an entry started now is rarely for
     * now, and every other default (midnight, this exact minute) is
     * further from what the user then types.
     *
     * <p>In the device's zone, by its IANA name and with the definition
     * RFC 5545 3.2.19 wants beside it: a floating start would follow
     * whichever zone each reader is in, which no server or other client
     * takes as meant.
     */
    void compose(EventStore.StoredCalendar calendar) {
        String uid = UUID.randomUUID().toString();
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime start =
                LocalDateTime.now(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1);

        String ical;
        try {
            ical =
                    host.client.newEvent(
                            EVENT,
                            uid,
                            now(),
                            Zones.stamp(start, false),
                            zone.getId(),
                            Zones.vtimezone(zone, System.currentTimeMillis()));
        } catch (Exception error) {
            Log.w("pimalaya", "new event failed", error);
            host.showError(error, R.string.event_save_failed);
            return;
        }

        String id = uid + ".ics";
        open(
                null,
                new EventStore.StoredEvent(
                        calendar.id, id, CardStore.rowHandle(null, id), ical, ""),
                calendar,
                true);
    }

    /** Opens the page on one agenda row. */
    void open(
            Occurrence occurrence,
            EventStore.StoredEvent event,
            EventStore.StoredCalendar calendar) {
        open(occurrence, event, calendar, false);
    }

    private void open(
            Occurrence occurrence,
            EventStore.StoredEvent event,
            EventStore.StoredCalendar calendar,
            boolean creating) {
        this.occurrence = occurrence;
        this.event = event;
        this.calendar = calendar;
        this.creating = creating;
        this.detail = detailOf(event);

        model = new JSONObject();
        times.clear();
        revealed.clear();
        if (detail != null) {
            put("summary", detail.summary);
            put("description", detail.description);
            put("location", detail.location);
            put("url", detail.url);
            put("status", detail.status);
            put("categories", detail.categories);
            put("priority", detail.priority);
            put("percentComplete", detail.percentComplete);
            put("allDay", detail.allDay);
            times.put("start", detail.start);
            times.put("end", detail.end);
            times.put("due", detail.due);
            times.put("completed", detail.completed);
            if (series()) {
                occurrenceDates();
            }
        }
        opened = copy(model);
        openedTimes.clear();
        openedTimes.putAll(times);

        render();
        host.show(MainActivity.PANEL_EVENT_VIEW);
    }

    /**
     * The dates of the occurrence that was opened rather than its
     * series': the start where it falls, and the end or due date it runs
     * to. A to-do placed by its due date alone has no start to move, so
     * its due date is where it falls.
     */
    private void occurrenceDates() {
        if (detail.start == null && detail.due != null) {
            times.put("due", occurrence.start);
            return;
        }
        times.put("start", occurrence.start);
        if (detail.end != null) {
            times.put("end", occurrence.end);
        }
        if (detail.due != null) {
            times.put("due", occurrence.end);
        }
    }

    /** Whether the page is on one occurrence of a series. */
    private boolean series() {
        return occurrence != null && occurrence.recurrenceId != null;
    }

    /** Whether the object could be read at all: nothing is editable
     *  otherwise, and saving would clear what could not be shown. */
    boolean readable() {
        return detail != null;
    }

    /** The component on the page, an event when nothing was read. */
    private String component() {
        return detail == null ? EVENT : detail.component;
    }

    // ---- the page ---------------------------------------------------------

    private void render() {
        sections.clear();

        if (detail == null) {
            List<View> rows = new ArrayList<>();
            rows.add(sections.row(host.getString(R.string.event_unreadable), null, null, null));
            sections.section(
                    R.string.event_section_details,
                    R.drawable.ic_section_notes,
                    rows,
                    null,
                    true);
            return;
        }

        when();
        section(R.string.event_section_progress, R.drawable.ic_check);
        section(R.string.event_section_where, R.drawable.ic_section_location_on);
        section(R.string.event_section_details, R.drawable.ic_section_notes);
        people();
        origin();

        host.updateBarTitle(title());
    }

    /**
     * When the entry is placed, which is the one section every component
     * has: the all-day switch, the placing date, and whatever the
     * component bounds itself with. The occurrence's own moment closes
     * it, read-only, since it is computed rather than stored.
     */
    private void when() {
        List<View> rows = new ArrayList<>();
        rows.add(
                sections.row(
                        host.getString(R.string.event_field_all_day),
                        allDay() ? host.getString(R.string.yes) : host.getString(R.string.no),
                        null,
                        this::toggleAllDay));
        rows.add(dateRow("start", startLabel()));
        rows.addAll(rowsOf(R.string.event_section_when));

        if (!detail.recurrence.isEmpty()) {
            rows.add(sections.value(detail.recurrence, R.string.event_field_repeats));
        }
        // An occurrence an override moved says where its series had it,
        // which is the instance an edit of it alone is written against.
        if (series()
                && Zones.instant(occurrence.recurrenceId) != Zones.instant(occurrence.start)) {
            rows.add(
                    sections.value(
                            moment(occurrence.recurrenceId), R.string.event_field_originally));
        }
        // NOTE: the row above and the countdown below describe the
        // occurrence that was tapped, and an entry being composed was
        // tapped from nowhere: it is not placed until it is saved, so there
        // is no instance to name and nothing to count down to. The page is
        // the same page, and these are the two rows it cannot fill.
        if (occurrence != null) {
            rows.add(
                    sections.value(
                            CalendarList.countdownLabel(host, occurrence),
                            R.string.event_field_countdown));
        }

        sections.section(
                R.string.event_section_when,
                CalendarList.glyphOf(component()),
                rows,
                addIcon(R.string.event_section_when),
                true);
    }

    /** One catalog section: its set (or revealed) fields, and its add. */
    private void section(int title, int icon) {
        List<View> rows = rowsOf(title);
        sections.section(title, icon, rows, addIcon(title), false);
    }

    /** The rows of one section: every field of this component that is
     *  set, plus the ones added this session. */
    private List<View> rowsOf(int section) {
        List<View> rows = new ArrayList<>();
        for (Field field : CATALOG) {
            if (field.section == section && field.of(component()) && shown(field)) {
                rows.add(row(field));
            }
        }
        return rows;
    }

    private boolean shown(Field field) {
        boolean set =
                field.kind == Kind.DATE
                        ? times.get(field.key) != null
                        : !value(field.key).isEmpty();
        return set || revealed.contains(field.key);
    }

    /** Who is on it, read-only: an attendee list is a negotiation
     *  (invitations, replies), not a text field. */
    private void people() {
        List<View> rows = new ArrayList<>();
        if (!detail.organizer.isEmpty()) {
            rows.add(sections.value(detail.organizer, R.string.event_field_organizer));
        }
        for (EventDetail.Attendee attendee : detail.attendees) {
            rows.add(sections.row(attendeeName(attendee), attendeeStanding(attendee), null, null));
        }

        sections.section(
                R.string.event_section_people, R.drawable.ic_section_group, rows, null, false);
    }

    private String attendeeName(EventDetail.Attendee attendee) {
        return attendee.name.isEmpty() ? attendee.address : attendee.name;
    }

    private String attendeeStanding(EventDetail.Attendee attendee) {
        String standing =
                attendee.status.isEmpty()
                        ? host.getString(R.string.event_field_attendee)
                        : attendee.status;
        return attendee.name.isEmpty() ? standing : standing + " · " + attendee.address;
    }

    /** Which calendar of which account holds it, and what it is. */
    private void origin() {
        List<View> rows = new ArrayList<>();
        rows.add(sections.value(calendar.name, R.string.event_field_calendar));
        rows.add(sections.value(calendar.accountEmail, R.string.event_field_account));
        rows.add(
                sections.value(
                        host.getString(CalendarList.componentName(component())),
                        R.string.event_field_kind));

        sections.section(
                R.string.event_section_calendar,
                R.drawable.ic_domain_calendar,
                rows,
                null,
                false);
    }

    /** What a to-do's or a journal entry's placing date is called. */
    private int startLabel() {
        if (TODO.equals(component())) {
            return R.string.event_field_scheduled;
        }
        return JOURNAL.equals(component())
                ? R.string.event_field_written
                : R.string.event_field_start;
    }

    // ---- adding a field ---------------------------------------------------

    /** A section header's add action, filtered to that section. */
    private View addIcon(int section) {
        ImageView icon = new ImageView(host);
        icon.setImageResource(R.drawable.ic_add_entry);
        icon.setImageTintList(ColorStateList.valueOf(sections.accentColor));
        icon.setContentDescription(host.getString(R.string.contact_add_field));
        icon.setBackgroundResource(
                host.ui.resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        icon.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6));
        icon.setOnClickListener(view -> addField(section));
        return icon;
    }

    /** The bar's add: every property this component does not have yet. */
    void addField() {
        addField(0);
    }

    /** The same, filtered to one section when a header asked. */
    private void addField(int section) {
        List<Field> offerable = new ArrayList<>();
        for (Field field : CATALOG) {
            boolean mine = section == 0 || field.section == section;
            if (mine && field.of(component()) && !shown(field)) {
                offerable.add(field);
            }
        }

        if (offerable.isEmpty()) {
            host.toast(host.getString(R.string.event_nothing_to_add));
            return;
        }

        String[] labels = new String[offerable.size()];
        for (int index = 0; index < offerable.size(); index++) {
            labels[index] = host.getString(offerable.get(index).label);
        }

        new AlertDialog.Builder(host)
                .setTitle(R.string.contact_add_field)
                .setItems(
                        labels,
                        (dialog, which) -> {
                            Field field = offerable.get(which);
                            revealed.add(field.key);
                            render();
                            edit(field);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- the rows ---------------------------------------------------------

    private View row(Field field) {
        CharSequence shown =
                field.kind == Kind.DATE ? moment(times.get(field.key)) : value(field.key);
        return sections.row(
                host.getString(field.label),
                shown.length() == 0 ? host.getString(R.string.event_unset) : shown,
                null,
                () -> edit(field));
    }

    private View dateRow(String key, int label) {
        String shown = moment(times.get(key));
        return sections.row(
                host.getString(label),
                shown.isEmpty() ? host.getString(R.string.event_unset) : shown,
                null,
                () -> editDate(key));
    }

    private void edit(Field field) {
        switch (field.kind) {
            case DATE:
                editDate(field.key);
                break;
            case STATUS:
                editStatus(field);
                break;
            case NUMBER:
                editNumber(field);
                break;
            default:
                editText(field);
        }
    }

    /** A date as the page shows it, at the reader's time, or empty when unset. */
    private String moment(EventTime time) {
        if (time == null) {
            return "";
        }
        long stamp = Zones.instant(time);
        return time.isDate() ? Dates.date(host, stamp) : Dates.full(host, stamp);
    }

    // ---- the dialogs ------------------------------------------------------

    private void editText(Field field) {
        boolean longText = field.kind == Kind.LONG_TEXT;
        EditText input = new EditText(host);
        input.setText(value(field.key));
        input.setInputType(
                longText
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        : InputType.TYPE_CLASS_TEXT);
        if (longText) {
            input.setMinLines(4);
            input.setGravity(Gravity.TOP);
        }

        dialog(field.label, input, () -> put(field.key, input.getText().toString().trim()));
    }

    private void editNumber(Field field) {
        EditText input = new EditText(host);
        input.setText(value(field.key));
        input.setHint(
                "priority".equals(field.key)
                        ? R.string.event_priority_hint
                        : R.string.event_percent_hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        dialog(field.label, input, () -> put(field.key, input.getText().toString().trim()));
    }

    private void editStatus(Field field) {
        String[] options = statusOf(component());
        String[] labels = new String[options.length];
        for (int index = 0; index < options.length; index++) {
            labels[index] =
                    options[index].isEmpty() ? host.getString(R.string.event_unset) : options[index];
        }

        new AlertDialog.Builder(host)
                .setTitle(field.label)
                .setItems(
                        labels,
                        (dialog, which) -> {
                            put(field.key, options[which]);
                            render();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The STATUS vocabulary of one component (RFC 5545 3.8.1.11). */
    private static String[] statusOf(String component) {
        if (TODO.equals(component)) {
            return TODO_STATUS;
        }
        return JOURNAL.equals(component) ? JOURNAL_STATUS : EVENT_STATUS;
    }

    /**
     * A date, then a time unless the entry is all-day: the two pickers
     * chain, which is what the platform offers, and an all-day entry
     * stops after the first since a date it has no time for is the
     * whole point.
     */
    private void editDate(String key) {
        EventTime current = times.get(key);
        Calendar moment = Calendar.getInstance();
        if (current != null) {
            moment.setTimeInMillis(Zones.instant(current));
        }

        new DatePickerDialog(
                        host,
                        (view, year, month, day) -> {
                            moment.set(Calendar.YEAR, year);
                            moment.set(Calendar.MONTH, month);
                            moment.set(Calendar.DAY_OF_MONTH, day);
                            // NOTE: a completion is a UTC moment whatever the
                            // entry is (RFC 5545 3.8.2.1), so it keeps its time.
                            if (allDay() && !"completed".equals(key)) {
                                times.put(key, at(moment.getTimeInMillis(), key, true));
                                render();
                            } else {
                                editTime(key, moment);
                            }
                        },
                        moment.get(Calendar.YEAR),
                        moment.get(Calendar.MONTH),
                        moment.get(Calendar.DAY_OF_MONTH))
                .show();
    }

    private void editTime(String key, Calendar moment) {
        new TimePickerDialog(
                        host,
                        (view, hour, minute) -> {
                            moment.set(Calendar.HOUR_OF_DAY, hour);
                            moment.set(Calendar.MINUTE, minute);
                            moment.set(Calendar.SECOND, 0);
                            times.put(key, at(moment.getTimeInMillis(), key, false));
                            render();
                        },
                        moment.get(Calendar.HOUR_OF_DAY),
                        moment.get(Calendar.MINUTE),
                        android.text.format.DateFormat.is24HourFormat(host))
                .show();
    }

    /**
     * A moment the reader picked, as the date a property writes: a day,
     * or a time in the zone the property was in, so an event at 09:00
     * New York moved by a reader in Paris stays in New York. A date that
     * had none takes the start's zone, else the device's.
     */
    private EventTime at(long instant, String key, boolean date) {
        ZoneId reader = ZoneId.systemDefault();
        if (date) {
            return Zones.at(instant, new EventTime("", EventTime.DATE, "", null), reader);
        }
        if ("completed".equals(key)) {
            return Zones.at(instant, new EventTime("", EventTime.UTC, "", null), reader);
        }

        EventTime like = times.get(key);
        if (like == null || like.isDate()) {
            like = times.get("start");
        }
        if (like == null || like.isDate()) {
            like = new EventTime("", EventTime.ZONED, reader.getId(), null);
        }
        return Zones.at(instant, like, reader);
    }

    /**
     * Flips all-day: a time becomes the day it falls on for the reader,
     * a day the midnight opening it in the device's zone. An all-day end
     * is the day after the last one (RFC 5545 3.6.1), so one landing on
     * its start's day moves a day on.
     */
    private void toggleAllDay() {
        boolean allDay = !allDay();
        put("allDay", allDay);

        for (String key : new String[] {"start", "end", "due"}) {
            EventTime current = times.get(key);
            if (current != null) {
                times.put(key, at(Zones.instant(current), key, allDay));
            }
        }

        EventTime start = times.get("start");
        EventTime end = times.get("end");
        if (allDay && start != null && end != null && end.time.compareTo(start.time) <= 0) {
            LocalDateTime next = Zones.civil(start.time).plusDays(1);
            times.put("end", new EventTime(Zones.stamp(next, true), EventTime.DATE, "", null));
        }
        render();
    }

    private void dialog(int title, View content, Runnable onOk) {
        LinearLayout frame = new LinearLayout(host);
        frame.setPadding(host.dp(24), host.dp(8), host.dp(24), 0);
        frame.addView(content);

        new AlertDialog.Builder(host)
                .setTitle(title)
                .setView(frame)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            onOk.run();
                            render();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- the model --------------------------------------------------------

    private void put(String key, Object value) {
        try {
            model.put(key, value);
        } catch (JSONException error) {
            Log.w("pimalaya", "event field failed: " + key, error);
        }
    }

    private String value(String key) {
        return model.optString(key);
    }

    private boolean allDay() {
        return model.optBoolean("allDay");
    }

    private static JSONObject copy(JSONObject values) {
        try {
            return new JSONObject(values.toString());
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    /**
     * The stored object's properties, or null when it cannot be read:
     * the agenda placed the row from the same object, so a page saying
     * only what the row said beats a page that fails. Opened on an
     * occurrence an override replaces, the override's.
     */
    private EventDetail detailOf(EventStore.StoredEvent event) {
        try {
            return host.client.readEvent(
                    event.ical, series() ? occurrence.recurrenceId.time : null);
        } catch (Exception error) {
            Log.w("pimalaya", "event read failed: " + event.id, error);
            return null;
        }
    }

    /**
     * What the page changed, as the bridge's edit object: the fields that
     * differ from how the page opened, and nothing else.
     *
     * <p>A date carries the zone it is in only when that is not the zone
     * its property already had, so a date edited where it was keeps its
     * line's {@code TZID} and {@code VALUE}; one in a zone the object may
     * not define brings that zone's {@code VTIMEZONE}.
     */
    private JSONObject edited() throws JSONException {
        JSONObject edit = new JSONObject();
        for (String key : TEXTS) {
            if (!value(key).equals(opened.optString(key))) {
                edit.put(key, value(key));
            }
        }

        for (String key : DATES) {
            EventTime now = times.get(key);
            EventTime was = openedTimes.get(key);
            if (same(now, was)) {
                continue;
            }

            JSONObject date = new JSONObject();
            date.put("time", now == null ? "" : now.time);
            if (now != null && (was == null || !sameZone(now, was))) {
                date.put("kind", now.kind);
                date.put("tzid", now.tzid);
                ZoneId zone = EventTime.ZONED.equals(now.kind) ? Zones.zoneOf(now.tzid) : null;
                if (zone != null && !edit.has("vtimezone")) {
                    edit.put("vtimezone", Zones.vtimezone(zone, Zones.instant(now)));
                }
            }
            edit.put(key, date);
        }

        edit.put("stamp", now());
        return edit;
    }

    private static boolean same(EventTime left, EventTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.time.equals(right.time) && sameZone(left, right);
    }

    private static boolean sameZone(EventTime left, EventTime right) {
        return left.kind.equals(right.kind) && Objects.equals(left.tzid, right.tzid);
    }

    /** Now, as the UTC stamp DTSTAMP and LAST-MODIFIED are written in. */
    private static String now() {
        return Zones.utc(System.currentTimeMillis());
    }

    // ---- saving -----------------------------------------------------------

    /**
     * Patches the object and stages it; the next sync pushes it.
     *
     * <p>The store and nothing else, so an entry can be written and saved
     * with the radio off. A create is staged as one, guarded by
     * {@code If-None-Match} when it goes out, and an edit is staged
     * against the ETag the entry was read at, so the push that follows is
     * conditioned on the state the edit was made against rather than on
     * whatever arrived since.
     *
     * <p>On an occurrence of a series it first asks which occurrences
     * the edit is for.
     */
    void save() {
        if (detail == null) {
            host.toast(host.getString(R.string.event_unreadable));
            return;
        }
        if (creating || !series()) {
            save("all");
            return;
        }
        scope(
                R.string.event_scope_save,
                PimalayaClient.writesOverrides(calendar.url),
                this::save);
    }

    /**
     * Asks which occurrences of the series a change is for.
     *
     * <p>This occurrence alone is offered only where the calendar's push
     * carries it: Graph and Google take an occurrence through the instance
     * itself rather than through the object, so offering it there would
     * stage a change the server never sees.
     */
    private void scope(int title, boolean one, Consumer<String> then) {
        List<String> scopes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int[] names = {
            R.string.event_scope_this, R.string.event_scope_following, R.string.event_scope_all,
        };
        for (int index = one ? 0 : 1; index < SCOPES.length; index++) {
            scopes.add(SCOPES[index]);
            labels.add(host.getString(names[index]));
        }

        new AlertDialog.Builder(host)
                .setTitle(title)
                .setItems(
                        labels.toArray(new String[0]),
                        (dialog, which) -> then.accept(scopes.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Saves for one scope: the series or the lone entry, one occurrence
     * through its override, or this and following by splitting the
     * series, which stages two writes: the new series' create, then the
     * shortened series' edit, in that order so a failure between them
     * leaves an occurrence twice rather than none.
     */
    private void save(String scope) {
        JSONObject edit;
        try {
            edit = scoped(edited(), scope);
        } catch (JSONException error) {
            host.showError(error, R.string.event_save_failed);
            return;
        }

        String edited = edit.toString();
        String uid = edit.optString("uid");
        EventStore.StoredEvent target = event;
        EventStore.StoredCalendar collection = calendar;
        boolean isNew = creating;

        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        CalendarEngine engine = host.calendarEngine(collection.accountEmail);
                        // NOTE: no summary and no key. What an agenda row
                        // shows needs the recurrence expansion, which
                        // happens at render time against the window being
                        // shown, so there is nothing to write here.
                        if ("following".equals(scope)) {
                            EventSplit split = host.client.splitEvent(target.ical, edited);
                            if (split.series != null) {
                                engine.mutateAdd(
                                        collection.id,
                                        uid + ".ics",
                                        split.series,
                                        new JSONArray(),
                                        null,
                                        "");
                            }
                            engine.mutateEdit(collection.id, target.handle, split.master, null, "");
                        } else {
                            String written = host.client.writeEvent(target.ical, edited);
                            if (isNew) {
                                engine.mutateAdd(
                                        collection.id,
                                        target.id,
                                        written,
                                        new JSONArray(),
                                        null,
                                        "");
                            } else {
                                engine.mutateEdit(collection.id, target.handle, written, null, "");
                            }
                        }
                    } catch (Exception error) {
                        Log.w("pimalaya", "event save failed: " + target.id, error);
                        failure = error;
                    }

                    Exception outcome = failure;
                    host.postAlive(
                            () -> {
                                host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (outcome != null) {
                                    host.showError(outcome, R.string.event_save_failed);
                                    return;
                                }
                                creating = false;
                                host.calendarList.reload();
                                host.showBack(MainActivity.PANEL_CALENDAR);
                            });
                });
    }

    /**
     * An edit made on the occurrence the page is on, for one scope: which
     * instance it is, the instant a split ends the series before, and the
     * new series' UID.
     */
    private JSONObject scoped(JSONObject edit, String scope) throws JSONException {
        edit.put("scope", scope);
        if (series()) {
            edit.put("recurrenceId", occurrence.recurrenceId.time);
            edit.put("recurrenceInstant", Zones.utc(Zones.instant(occurrence.recurrenceId)));
        }
        if ("following".equals(scope)) {
            edit.put("uid", UUID.randomUUID().toString());
        }
        return edit;
    }

    /**
     * Asks before deleting: an entry is one tap from being gone. On an
     * occurrence of a series, the question is which occurrences.
     */
    void confirmDelete() {
        if (!creating && series()) {
            scope(
                    R.string.event_scope_delete,
                    PimalayaClient.writesExdates(calendar.url),
                    this::delete);
            return;
        }
        new AlertDialog.Builder(host)
                .setMessage(R.string.event_delete_confirm)
                .setPositiveButton(R.string.event_delete, (dialog, which) -> delete("all"))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Stages the removal and leaves the page; the next sync tells the
     * server, guarded by the ETag the entry was read at.
     *
     * <p>All occurrences remove the entry. One becomes an {@code EXDATE}
     * on its series and this and following ends the series before it,
     * both edits of the entry, unless nothing of the series is left.
     *
     * <p>An entry that was never saved is only ever on this screen, so its
     * delete is the page closing: there is nothing staged to withdraw and
     * nothing anywhere to tell about it.
     */
    private void delete(String scope) {
        if (creating) {
            host.showBack(MainActivity.PANEL_CALENDAR);
            return;
        }

        String edit;
        try {
            JSONObject stamped = new JSONObject();
            stamped.put("stamp", now());
            edit = scoped(stamped, scope).toString();
        } catch (JSONException error) {
            host.showError(error, R.string.event_delete_failed);
            return;
        }

        EventStore.StoredEvent target = event;
        EventStore.StoredCalendar collection = calendar;

        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        CalendarEngine engine = host.calendarEngine(collection.accountEmail);
                        String left =
                                "all".equals(scope)
                                        ? null
                                        : host.client.removeEvent(target.ical, edit);
                        if (left == null) {
                            engine.mutateRemove(collection.id, target.handle);
                        } else {
                            engine.mutateEdit(collection.id, target.handle, left, null, "");
                        }
                    } catch (Exception error) {
                        Log.w("pimalaya", "event delete failed: " + target.id, error);
                        failure = error;
                    }

                    Exception outcome = failure;
                    host.postAlive(
                            () -> {
                                host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (outcome != null) {
                                    host.showError(outcome, R.string.event_delete_failed);
                                    return;
                                }
                                host.calendarList.reload();
                                host.showBack(MainActivity.PANEL_CALENDAR);
                            });
                });
    }
}
