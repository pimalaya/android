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
import org.pimalaya.client.Occurrence;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

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
 * <p>It edits the stored <em>component</em>, not the occurrence that was
 * tapped: changing one date of a repeating entry means writing an
 * override with its own RECURRENCE-ID, and until that exists an edit is
 * honestly an edit of the whole series. What the page shows of the
 * occurrence (when this one falls, how far off it is) is read-only for
 * the same reason.
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
     */
    void compose(EventStore.StoredCalendar calendar) {
        String uid = UUID.randomUUID().toString();

        Calendar start = Calendar.getInstance();
        start.add(Calendar.HOUR_OF_DAY, 1);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);

        String ical;
        try {
            ical = host.client.newEvent(EVENT, uid, now(), format(start, false, false));
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
        revealed.clear();
        if (detail != null) {
            put("summary", detail.summary);
            put("description", detail.description);
            put("location", detail.location);
            put("url", detail.url);
            put("status", detail.status);
            put("categories", detail.categories);
            put("priority", detail.priority);
            put("start", detail.start);
            put("end", detail.end);
            put("due", detail.due);
            put("completed", detail.completed);
            put("percentComplete", detail.percentComplete);
            put("allDay", detail.allDay);
        }

        render();
        host.show(MainActivity.PANEL_EVENT_VIEW);
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
            // The occurrence is one instance of the rule above, and the
            // rule is what an edit here changes, so this says which
            // instance was opened without pretending it can be moved.
            if (occurrence != null) {
                rows.add(
                        sections.value(
                                moment(occurrence.start, occurrence.allDay),
                                R.string.event_field_this_one));
            }
        }
        // NOTE: both of the rows above describe the occurrence that was
        // tapped, and an entry being composed was tapped from nowhere: it
        // is not placed until it is saved, so there is no instance to name
        // and nothing to count down to. The page is the same page, and
        // these are the two rows it cannot fill.
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
        return !value(field.key).isEmpty() || revealed.contains(field.key);
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
                field.kind == Kind.DATE ? moment(value(field.key), allDay()) : value(field.key);
        return sections.row(
                host.getString(field.label),
                shown.length() == 0 ? host.getString(R.string.event_unset) : shown,
                null,
                () -> edit(field));
    }

    private View dateRow(String key, int label) {
        String shown = moment(value(key), allDay());
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

    /** A civil stamp as the page shows it, or empty when unset. */
    private String moment(String civil, boolean allDay) {
        if (civil.isEmpty()) {
            return "";
        }
        long stamp = stampOf(civil);
        return allDay ? Dates.date(host, stamp) : Dates.full(host, stamp);
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
        String current = value(key);
        Calendar moment = Calendar.getInstance();
        if (!current.isEmpty()) {
            moment.setTimeInMillis(stampOf(current));
        }
        boolean utc = current.endsWith("Z");

        new DatePickerDialog(
                        host,
                        (view, year, month, day) -> {
                            moment.set(Calendar.YEAR, year);
                            moment.set(Calendar.MONTH, month);
                            moment.set(Calendar.DAY_OF_MONTH, day);
                            if (allDay()) {
                                put(key, format(moment, true, false));
                                render();
                            } else {
                                editTime(key, moment, utc);
                            }
                        },
                        moment.get(Calendar.YEAR),
                        moment.get(Calendar.MONTH),
                        moment.get(Calendar.DAY_OF_MONTH))
                .show();
    }

    private void editTime(String key, Calendar moment, boolean utc) {
        new TimePickerDialog(
                        host,
                        (view, hour, minute) -> {
                            moment.set(Calendar.HOUR_OF_DAY, hour);
                            moment.set(Calendar.MINUTE, minute);
                            moment.set(Calendar.SECOND, 0);
                            put(key, format(moment, false, utc));
                            render();
                        },
                        moment.get(Calendar.HOUR_OF_DAY),
                        moment.get(Calendar.MINUTE),
                        android.text.format.DateFormat.is24HourFormat(host))
                .show();
    }

    /** Flips all-day, dropping the times the dates no longer carry. */
    private void toggleAllDay() {
        boolean allDay = !allDay();
        put("allDay", allDay);

        for (String key : new String[] {"start", "end", "due"}) {
            String current = value(key);
            if (!current.isEmpty()) {
                Calendar moment = Calendar.getInstance();
                moment.setTimeInMillis(stampOf(current));
                put(key, format(moment, allDay, false));
            }
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

    /**
     * The stored object's properties, or null when it cannot be read:
     * the agenda placed the row from the same object, so a page saying
     * only what the row said beats a page that fails.
     */
    private EventDetail detailOf(EventStore.StoredEvent event) {
        try {
            return host.client.readEvent(event.ical);
        } catch (Exception error) {
            Log.w("pimalaya", "event read failed: " + event.id, error);
            return null;
        }
    }

    // ---- civil stamps -----------------------------------------------------

    /**
     * The instant a stored stamp names, read in UTC when it says so and
     * in the device's zone otherwise.
     *
     * <p>The agenda reads every stamp as wall-clock, which is right for
     * placing a recurrence; an edit cannot afford it, because writing a
     * UTC moment back without its Z would move the entry by the offset.
     */
    static long stampOf(String civil) {
        if (!civil.endsWith("Z")) {
            return CalendarList.stampOf(civil);
        }

        Calendar moment = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        moment.clear();
        moment.set(
                Integer.parseInt(civil.substring(0, 4)),
                Integer.parseInt(civil.substring(4, 6)) - 1,
                Integer.parseInt(civil.substring(6, 8)));
        if (civil.length() >= 15) {
            moment.set(Calendar.HOUR_OF_DAY, Integer.parseInt(civil.substring(9, 11)));
            moment.set(Calendar.MINUTE, Integer.parseInt(civil.substring(11, 13)));
            moment.set(Calendar.SECOND, Integer.parseInt(civil.substring(13, 15)));
        }
        return moment.getTimeInMillis();
    }

    /** A moment as the stamp the object stores: a date, a local
     *  wall-clock time, or a UTC one when that is what it was. */
    static String format(Calendar moment, boolean allDay, boolean utc) {
        Calendar shown = moment;
        if (utc) {
            shown = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            shown.setTimeInMillis(moment.getTimeInMillis());
        }

        String date =
                String.format(
                        Locale.US,
                        "%04d%02d%02d",
                        shown.get(Calendar.YEAR),
                        shown.get(Calendar.MONTH) + 1,
                        shown.get(Calendar.DAY_OF_MONTH));
        if (allDay) {
            return date;
        }

        return date
                + String.format(
                        Locale.US,
                        "T%02d%02d%02d",
                        shown.get(Calendar.HOUR_OF_DAY),
                        shown.get(Calendar.MINUTE),
                        shown.get(Calendar.SECOND))
                + (utc ? "Z" : "");
    }

    /** Now, as the UTC stamp DTSTAMP and LAST-MODIFIED are written in. */
    private static String now() {
        return format(Calendar.getInstance(), false, true);
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
     */
    void save() {
        if (detail == null) {
            host.toast(host.getString(R.string.event_unreadable));
            return;
        }

        put("stamp", now());
        String edited = model.toString();
        EventStore.StoredEvent target = event;
        EventStore.StoredCalendar collection = calendar;
        boolean isNew = creating;

        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        String written = host.client.writeEvent(target.ical, edited);
                        CalendarEngine engine = host.calendarEngine(collection.accountEmail);
                        if (isNew) {
                            // NOTE: no summary and no key. What an agenda row
                            // shows needs the recurrence expansion, which
                            // happens at render time against the window being
                            // shown, so there is nothing to write here.
                            engine.mutateAdd(
                                    collection.id, target.id, written, new JSONArray(), null, "");
                        } else {
                            engine.mutateEdit(collection.id, target.handle, written, null, "");
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

    /** Asks before deleting: an entry is one tap from being gone. */
    void confirmDelete() {
        new AlertDialog.Builder(host)
                .setMessage(R.string.event_delete_confirm)
                .setPositiveButton(R.string.event_delete, (dialog, which) -> delete())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Stages the entry's removal and leaves the page; the next sync
     * deletes it, guarded by the ETag it was read at.
     *
     * <p>An entry that was never saved is only ever on this screen, so its
     * delete is the page closing: there is nothing staged to withdraw and
     * nothing anywhere to tell about it.
     */
    private void delete() {
        if (creating) {
            host.showBack(MainActivity.PANEL_CALENDAR);
            return;
        }

        EventStore.StoredEvent target = event;
        EventStore.StoredCalendar collection = calendar;

        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        host.calendarEngine(collection.accountEmail)
                                .mutateRemove(collection.id, target.handle);
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
