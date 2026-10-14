package org.pimalaya.calendar;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.DateEdit;
import org.pimalaya.client.EventConflictKind;
import org.pimalaya.client.EventDetail;
import org.pimalaya.client.EventDraft;
import org.pimalaya.client.EventEdit;
import org.pimalaya.client.EventMerge;
import org.pimalaya.client.EventScope;
import org.pimalaya.client.EventSide;
import org.pimalaya.client.EventTime;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.Occurrence;
import org.pimalaya.pimdir.DefaultCollection;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.Dates;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Linked;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.Picker;
import org.pimalaya.ui.Sections;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The page an agenda row opens onto: the entry, and the form editing it.
 *
 * <p>Sections follow the component (RFC 5545). On a series occurrence, save and
 * delete ask the scope: override and {@code EXDATE}, split, or series. In
 * conflict mode both sides show as chips or rows, none dropped unseen.
 */
public final class EventView {
    /** What an event's STATUS may be (RFC 5545 3.8.1.11). */
    private static final String[] EVENT_STATUS = {"", "TENTATIVE", "CONFIRMED", "CANCELLED"};

    /** What a to-do's STATUS may be. */
    private static final String[] TODO_STATUS = {
        "", "NEEDS-ACTION", "IN-PROCESS", "COMPLETED", "CANCELLED",
    };

    /** What a journal entry's STATUS may be. */
    private static final String[] JOURNAL_STATUS = {"", "DRAFT", "FINAL", "CANCELLED"};

    /** An editable property: edit key, section, label, editor, components. */
    private static final class Field {
        final String key;
        final EventEdit.Field text;
        final int section;
        final int label;
        final Kind kind;
        final String[] components;

        Field(String key, int section, int label, Kind kind, String... components) {
            this.key = key;
            this.text = textOf(key);
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
    private static final EventScope[] SCOPES = {
        EventScope.THIS, EventScope.FOLLOWING, EventScope.ALL,
    };

    /** The dates a page edits, as the bridge's edit object keys them. */
    private static final String[] DATES = {"start", "end", "due", "completed"};

    /**
     * Every property the page can edit, in section order.
     *
     * <p>The placing date and all-day switch are not here: they are what a
     * component is, so they always show.
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

    private final Host host;
    private final CalendarParts parts;

    /** The Linked card the page shows its entry's references in. */
    private final Linked<ItemLinks.Endpoint> linked;

    /** The agenda a save or delete reloads; it binds itself here when built. */
    CalendarList list;

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
    private final Map<EventEdit.Field, String> model = new EnumMap<>(EventEdit.Field.class);

    /** Whether the entry is all-day, as the page has it now. */
    private boolean allDay;

    /** The working dates, each with the zone it is in; absent when unset. */
    private final Map<String, EventTime> times = new HashMap<>();

    /**
     * Values and dates as opened, so a save sends only what changed.
     *
     * <p>An untouched date keeps its line byte for byte; a series edit moves
     * only the dates that moved.
     */
    private final Map<EventEdit.Field, String> opened = new EnumMap<>(EventEdit.Field.class);

    private final Map<String, EventTime> openedTimes = new HashMap<>();

    /** Fields the user added this session, so their empty row stays. */
    private final Set<String> revealed = new HashSet<>();

    /** The conflict the page settles, null outside conflict mode. */
    private EventStore.StoredConflict conflict;

    /** Its merge: the object the page opened on, and what is left to ask. */
    private EventMerge merge;

    /** Each conflict's given side, by id; absent means the pre-filled one. */
    private final Map<Integer, EventSide> picks = new HashMap<>();

    /** The conflicts the user has reviewed, by id, which clears their glyph. */
    private final Set<Integer> reviewed = new HashSet<>();

    /** The entry page; {@code linked} draws its Linked card. */
    public EventView(Host host, CalendarParts parts, Linked<ItemLinks.Endpoint> linked) {
        this.host = host;
        this.parts = parts;
        this.linked = linked;
        this.sections =
                new Sections(host, (LinearLayout) host.findViewById(R.id.event_view_page));
    }

    /** What the bar calls the entry: its summary, else its kind. */
    public String title() {
        String summary = value(EventEdit.Field.SUMMARY);
        return summary.isEmpty()
                ? host.getString(CalendarList.componentName(component()))
                : summary;
    }

    /**
     * Opens the page on a new entry, in the given calendar.
     *
     * <p>Built up front, so the page has one shape. At the next whole hour, in
     * the device's zone named with its definition (RFC 5545 3.2.19): a floating
     * start would follow each reader.
     */
    public void compose(EventStore.StoredCalendar calendar) {
        String uid = UUID.randomUUID().toString();
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime start =
                LocalDateTime.now(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1);

        String ical;
        try {
            ical =
                    CalendarClient.newEvent(
                            new EventDraft(
                                    EVENT,
                                    uid,
                                    now(),
                                    Zones.stamp(start, false),
                                    zone.getId(),
                                    Zones.vtimezone(zone, System.currentTimeMillis())));
        } catch (Exception error) {
            Log.w("pimalaya", "new event failed", error);
            host.showError(error, R.string.event_save_failed);
            return;
        }

        String id = uid + ".ics";
        leaveConflict();
        open(
                null,
                new EventStore.StoredEvent(
                        calendar.id, id, PimdirStorage.rowHandle(null, id), ical, ""),
                calendar,
                true);
    }

    /**
     * Starts an entry in the default calendar of the account in view.
     *
     * <p>Asks only when there is none, as a new contact picks its book; a
     * read-only calendar is never offered.
     */
    public void composeNew() {
        Map<String, EventStore.StoredCalendar> byId = new HashMap<>();
        boolean readOnlyBackend = false;
        for (EventStore.StoredCalendar calendar : parts.events().loadCalendars()) {
            byId.put(calendar.id, calendar);
            readOnlyBackend |= !AccountClient.writesEvents(calendar.url);
        }
        List<PimdirCollections.Stored> collections =
                new PimdirCollections(parts.pimdir(), host).list(PimdirSummary.CALENDAR);
        // NOTE: a backend taking no event write lists as read only, and the
        // line for no calendar at all would leave its owner guessing why.
        if (readOnlyBackend && DefaultCollection.writable(collections).isEmpty()) {
            host.toast(host.getString(R.string.event_read_only));
            return;
        }
        DefaultCollection.Choice choice =
                DefaultCollection.choice(
                        host,
                        PimdirSummary.CALENDAR,
                        collections,
                        MergedFilter.of(host, PimDomain.CALENDAR)::accepts);
        Picker.choose(
                host,
                R.string.event_target_title,
                R.string.event_no_calendar,
                choice.direct,
                choice.offered,
                choice.preselected,
                collection -> host.ui.twoLine(collection.name, collection.accountEmail),
                calendar -> compose(byId.get(calendar.id)));
    }

    /** Opens the page on an agenda row, in conflict mode if conflicted. */
    public void open(
            Occurrence occurrence,
            EventStore.StoredEvent event,
            EventStore.StoredCalendar calendar) {
        leaveConflict();
        if (event.conflicted) {
            openConflict(occurrence, event, calendar);
        } else {
            open(occurrence, event, calendar, false);
        }
    }

    /**
     * Opens one calendar resource on its page, as its series.
     *
     * <p>Read off the store on the io executor; {@code gone} runs when the
     * store holds it no more.
     */
    public void openStored(String collection, String linkId, Runnable gone) {
        host.io.execute(
                () -> {
                    EventStore.StoredCalendar calendar = null;
                    for (EventStore.StoredCalendar held : parts.events().loadCalendars()) {
                        if (held.id.equals(collection)) {
                            calendar = held;
                        }
                    }
                    EventStore.StoredEvent found = null;
                    if (calendar != null) {
                        for (EventStore.StoredEvent event : parts.events().loadEvents()) {
                            if (event.collectionId.equals(collection)
                                    && event.id.equals(linkId)) {
                                found = event;
                            }
                        }
                    }
                    EventStore.StoredCalendar in = calendar;
                    EventStore.StoredEvent event = found;
                    host.postAlive(
                            () -> {
                                if (event == null) {
                                    gone.run();
                                } else {
                                    open(null, event, in);
                                }
                            });
                });
    }

    /**
     * Opens the page on an entry both sides edited, merged from the store.
     *
     * <p>Each collision's newer side pre-filled, both offered. A merge with
     * nothing to ask is staged on the spot, a toast its outcome; one that
     * cannot merge does not open: a save would settle it one-sided.
     */
    private void openConflict(
            Occurrence occurrence,
            EventStore.StoredEvent event,
            EventStore.StoredCalendar calendar) {
        EventStore.StoredConflict found;
        EventMerge merged;
        try {
            found = parts.events().conflictOf(event);
            merged =
                    found == null
                            ? null
                            : CalendarClient.mergeEvent(found.base, found.local, found.remote);
        } catch (Exception error) {
            Log.w("pimalaya", "event merge failed: " + event.id, error);
            host.showError(error, R.string.event_unreadable);
            return;
        }
        if (merged == null) {
            open(occurrence, event, calendar, false);
            return;
        }

        if (merged.resolved()) {
            EventStore.StoredConflict settled = found;
            String ical = merged.ical();
            host.io.execute(
                    () -> {
                        Exception failure = null;
                        try {
                            new CalendarStager(parts.pimdir(), parts.clients())
                                    .mutateEdit(settled.collection, settled.handle, ical, null, "");
                        } catch (Exception error) {
                            Log.w("pimalaya", "event merge failed: " + event.id, error);
                            failure = error;
                        }
                        Exception outcome = failure;
                        host.postAlive(
                                () -> {
                                    if (outcome != null) {
                                        host.showError(outcome, R.string.event_save_failed);
                                        return;
                                    }
                                    host.toast(host.getString(R.string.conflict_auto_resolved));
                                    list.reload();
                                });
                    });
            return;
        }

        conflict = found;
        merge = merged;
        open(
                occurrence,
                new EventStore.StoredEvent(
                        event.collectionId, event.id, event.handle, merged.ical(), event.etag),
                calendar,
                false);
    }

    /** Leaves conflict mode, before the page opens on anything else. */
    private void leaveConflict() {
        conflict = null;
        merge = null;
        picks.clear();
        reviewed.clear();
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

        model.clear();
        allDay = false;
        times.clear();
        revealed.clear();
        if (detail != null) {
            put(EventEdit.Field.SUMMARY, detail.summary());
            put(EventEdit.Field.DESCRIPTION, detail.description());
            put(EventEdit.Field.LOCATION, detail.location());
            put(EventEdit.Field.URL, detail.url());
            put(EventEdit.Field.STATUS, detail.status());
            put(EventEdit.Field.CATEGORIES, detail.categories());
            put(EventEdit.Field.PRIORITY, detail.priority());
            put(EventEdit.Field.PERCENT_COMPLETE, detail.percentComplete());
            allDay = detail.allDay();
            times.put("start", detail.start());
            times.put("end", detail.end());
            times.put("due", detail.due());
            times.put("completed", detail.completed());
            // NOTE: an override's own dates are its occurrence's, and in
            // conflict mode they are the merge's rather than the row's, which
            // was expanded from the side here.
            if (series() && (merge == null || detail.recurrenceId() == null)) {
                occurrenceDates();
            }
        }
        opened.clear();
        opened.putAll(model);
        openedTimes.clear();
        openedTimes.putAll(times);

        render();
        host.show(Panels.EVENT_VIEW);
    }

    /**
     * The opened occurrence's dates rather than its series'.
     *
     * <p>The start where it falls, and the end or due date it runs to; a to-do
     * placed by its due date alone falls at its due date.
     */
    private void occurrenceDates() {
        if (detail.start() == null && detail.due() != null) {
            times.put("due", occurrence.start());
            return;
        }
        times.put("start", occurrence.start());
        if (detail.end() != null) {
            times.put("end", occurrence.end());
        }
        if (detail.due() != null) {
            times.put("due", occurrence.end());
        }
    }

    /** Whether the page is on one occurrence of a series. */
    private boolean series() {
        return occurrence != null && occurrence.recurrenceId() != null;
    }

    /**
     * Whether the object could be read at all.
     *
     * <p>Nothing is editable otherwise: saving would clear what could not be
     * shown.
     */
    boolean readable() {
        return detail != null;
    }

    /** Whether the page may write back: read, on a writable calendar. */
    public boolean editable() {
        return readable() && writes();
    }

    /** Whether the calendar's backend takes event writes at all. */
    private boolean writes() {
        return AccountClient.writesEvents(calendar.url);
    }

    /** The component on the page, an event when nothing was read. */
    private String component() {
        return detail == null ? EVENT : detail.component();
    }

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

        conflicts();
        when();
        section(R.string.event_section_progress, R.drawable.ic_check);
        section(R.string.event_section_where, R.drawable.ic_section_location_on);
        section(R.string.event_section_details, R.drawable.ic_section_notes);
        people();
        origin();
        if (!creating) {
            linked.section(sections, EventStore.endpointOf(event), this::render);
        }

        host.updateBarTitle(title());
        if (host.screen == Panels.EVENT_VIEW) {
            gate();
        }
    }

    /** Gates the save: in conflict mode, until every conflict is reviewed. */
    public void gate() {
        host.gateSave(settled(), R.drawable.ic_save, R.string.event_save);
    }

    /** Whether nothing awaits review. */
    private boolean settled() {
        return merge == null || reviewed.size() == merge.conflicts().size();
    }

    /**
     * The conflicts the page cannot draw in place, each a row of its own.
     *
     * <p>Another occurrence's, one occurrence or the entry whole, a property
     * the page does not edit.
     */
    private void conflicts() {
        if (merge == null) {
            return;
        }

        List<View> rows = new ArrayList<>();
        for (EventMerge.Conflict one : merge.conflicts()) {
            if (inPlace(one)) {
                continue;
            }
            String title = fieldLabel(one);
            if (one.recurrenceId() != null) {
                title = title + " · " + moment(one.recurrenceId());
            }
            rows.add(
                    sections.row(
                            title,
                            choiceLabel(one, picked(one)),
                            warning(one),
                            () -> choose(one, null)));
        }

        sections.section(
                R.string.event_section_conflicts, R.drawable.ic_diverged, rows, null, false);
    }

    /**
     * When the entry is placed, the one section every component has.
     *
     * <p>The all-day switch, the placing date and the component's bounds,
     * closed by the occurrence's own moment, read-only since it is computed.
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

        // NOTE: the rule is read-only here, and a conflict on it the one way
        // the row is tapped: to pick a side.
        EventMerge.Conflict rule = conflictOn("recurrence");
        if (rule != null) {
            rows.add(
                    sections.row(
                            host.getString(R.string.event_field_repeats),
                            choiceLabel(rule, picked(rule)),
                            warning(rule),
                            () -> choose(rule, null)));
        } else if (!detail.recurrence().isEmpty()) {
            rows.add(sections.value(detail.recurrence(), R.string.event_field_repeats));
        }
        // NOTE: an occurrence an override moved says where its series had
        // it, the instance an edit of it alone is written against.
        if (series()
                && Zones.instant(occurrence.recurrenceId()) != Zones.instant(occurrence.start())) {
            rows.add(
                    sections.value(
                            moment(occurrence.recurrenceId()), R.string.event_field_originally));
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

    /** The rows of one section: set fields, plus those added this session. */
    private List<View> rowsOf(int section) {
        List<View> rows = new ArrayList<>();
        for (Field field : CATALOG) {
            if (field.section == section && field.of(component()) && shown(field)) {
                rows.add(row(field));
            }
        }
        return rows;
    }

    /** Whether a field's row shows: set, added this session, or conflicted. */
    private boolean shown(Field field) {
        boolean set =
                field.kind == Kind.DATE
                        ? times.get(field.key) != null
                        : !value(field.text).isEmpty();
        return set || revealed.contains(field.key) || conflictOn(field.key) != null;
    }

    /**
     * Who is on it, read-only.
     *
     * <p>An attendee list is a negotiation (invitations, replies), not a text
     * field.
     */
    private void people() {
        List<View> rows = new ArrayList<>();
        if (!detail.organizer().isEmpty()) {
            rows.add(sections.value(detail.organizer(), R.string.event_field_organizer));
        }
        for (EventDetail.Attendee attendee : detail.attendees()) {
            rows.add(sections.row(attendeeName(attendee), attendeeStanding(attendee), null, null));
        }

        sections.section(
                R.string.event_section_people, R.drawable.ic_section_group, rows, null, false);
    }

    private String attendeeName(EventDetail.Attendee attendee) {
        return attendee.name().isEmpty() ? attendee.address() : attendee.name();
    }

    private String attendeeStanding(EventDetail.Attendee attendee) {
        String standing =
                attendee.status().isEmpty()
                        ? host.getString(R.string.event_field_attendee)
                        : attendee.status();
        return attendee.name().isEmpty() ? standing : standing + " · " + attendee.address();
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
        if (!writes()) {
            rows.add(sections.row(host.getString(R.string.event_read_only), null, null, null));
        }

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
    public void addField() {
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

    private View row(Field field) {
        CharSequence shown =
                field.kind == Kind.DATE ? moment(times.get(field.key)) : value(field.text);
        return sections.row(
                host.getString(field.label),
                shown.length() == 0 ? host.getString(R.string.event_unset) : shown,
                warning(conflictOn(field.key)),
                () -> edit(field));
    }

    private View dateRow(String key, int label) {
        String shown = moment(times.get(key));
        EventMerge.Conflict conflicted = conflictOn(key);
        return sections.row(
                host.getString(label),
                shown.isEmpty() ? host.getString(R.string.event_unset) : shown,
                warning(conflicted),
                () -> {
                    if (conflicted != null) {
                        choose(conflicted, () -> editDate(key));
                    } else {
                        editDate(key);
                    }
                });
    }

    private void edit(Field field) {
        // NOTE: a conflicted date or status is picked rather than typed, the
        // free edit one tap further; a text keeps its input, chips under it.
        EventMerge.Conflict conflicted = conflictOn(field.key);
        if (conflicted != null && (field.kind == Kind.DATE || field.kind == Kind.STATUS)) {
            choose(conflicted, () -> editPlain(field));
            return;
        }
        editPlain(field);
    }

    private void editPlain(Field field) {
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

    /** A date as shown, at the reader's time; empty when unset. */
    private String moment(EventTime time) {
        if (time == null) {
            return "";
        }
        long stamp = Zones.instant(time);
        return time.isDate() ? Dates.date(host, stamp) : Dates.full(host, stamp);
    }

    private void editText(Field field) {
        boolean longText = field.kind == Kind.LONG_TEXT;
        EditText input = new EditText(host);
        input.setText(value(field.text));
        input.setInputType(
                longText
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        : InputType.TYPE_CLASS_TEXT);
        if (longText) {
            input.setMinLines(4);
            input.setGravity(Gravity.TOP);
        }

        typed(field, input);
    }

    private void editNumber(Field field) {
        EditText input = new EditText(host);
        input.setText(value(field.text));
        input.setHint(
                "priority".equals(field.key)
                        ? R.string.event_priority_hint
                        : R.string.event_percent_hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        typed(field, input);
    }

    /**
     * A typed field's dialog: its input, and a chip per side if conflicted.
     *
     * <p>A chip fills the input in. A value that is one side's on OK picks that
     * side; any other is an edit of its own, over whichever side is held.
     */
    private void typed(Field field, EditText input) {
        EventMerge.Conflict conflicted = conflictOn(field.key);
        if (conflicted == null) {
            dialog(field.label, input, () -> put(field.text, input.getText().toString().trim()));
            return;
        }

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(input);
        content.addView(chips(conflicted, choice -> input.setText(choice.value())));
        dialog(
                field.label,
                content,
                () -> {
                    String typed = input.getText().toString().trim();
                    for (EventMerge.Choice choice : conflicted.choices()) {
                        if (choice.value().trim().equals(typed)) {
                            pick(conflicted, choice);
                            return;
                        }
                    }
                    reviewed.add(conflicted.id());
                    put(field.text, typed);
                });
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
                            put(field.text, options[which]);
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

    /** A date, then a time unless all-day: the platform's pickers chained. */
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
                            // NOTE: a completion is a UTC moment whatever
                            // the entry is (RFC 5545 3.8.2.1), so it keeps
                            // its time.
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
     * A moment the reader picked, as the date a property writes.
     *
     * <p>A day, or a time in the property's zone, so 09:00 New York moved from
     * Paris stays in New York. A date that had none takes the start's zone,
     * else the device's.
     */
    private EventTime at(long instant, String key, boolean date) {
        ZoneId reader = ZoneId.systemDefault();
        if (date) {
            return Zones.at(instant, new EventTime("", EventTimeKind.DATE, "", null), reader);
        }
        if ("completed".equals(key)) {
            return Zones.at(instant, new EventTime("", EventTimeKind.UTC, "", null), reader);
        }

        EventTime like = times.get(key);
        if (like == null || like.isDate()) {
            like = times.get("start");
        }
        if (like == null || like.isDate()) {
            like = new EventTime("", EventTimeKind.ZONED, reader.getId(), null);
        }
        return Zones.at(instant, like, reader);
    }

    /**
     * Flips all-day, converting the dates both ways.
     *
     * <p>A time becomes the reader's day it falls on, a day the midnight
     * opening it in the device's zone. An all-day end is the day after the last
     * (RFC 5545 3.6.1), so one on its start's day moves a day on.
     */
    private void toggleAllDay() {
        boolean allDay = !allDay();
        this.allDay = allDay;

        for (String key : new String[] {"start", "end", "due"}) {
            EventTime current = times.get(key);
            if (current != null) {
                times.put(key, at(Zones.instant(current), key, allDay));
            }
        }

        EventTime start = times.get("start");
        EventTime end = times.get("end");
        if (allDay && start != null && end != null && end.time().compareTo(start.time()) <= 0) {
            LocalDateTime next = Zones.civil(start.time()).plusDays(1);
            times.put("end", new EventTime(Zones.stamp(next, true), EventTimeKind.DATE, "", null));
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

    /** The conflict drawn in place on a row, by its key; null when none. */
    private EventMerge.Conflict conflictOn(String key) {
        if (merge == null) {
            return null;
        }
        for (EventMerge.Conflict one : merge.conflicts()) {
            if (one.field().equals(key) && inPlace(one)) {
                return one;
            }
        }
        return null;
    }

    /**
     * Whether a conflict draws on a row: a page field of this component.
     *
     * <p>The series' rule is the series' row: in place only on a page showing
     * the series.
     */
    private boolean inPlace(EventMerge.Conflict one) {
        if (!sameOccurrence(one.recurrenceId(), detail.recurrenceId())) {
            return false;
        }
        // NOTE: an occurrence of a series shows its own dates, not the
        // series' a conflict on the series is about.
        if (series() && one.recurrenceId() == null && List.of(DATES).contains(one.field())) {
            return false;
        }
        if ("start".equals(one.field()) || "recurrence".equals(one.field())) {
            return true;
        }
        for (Field field : CATALOG) {
            if (field.key.equals(one.field()) && field.of(component())) {
                return true;
            }
        }
        return false;
    }

    /** Whether two occurrence identities name the same one, or none. */
    static boolean sameOccurrence(EventTime left, EventTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return Zones.instant(left) == Zones.instant(right);
    }

    /** The diverged glyph of a conflict not reviewed yet; null otherwise. */
    private View warning(EventMerge.Conflict one) {
        if (one == null || reviewed.contains(one.id())) {
            return null;
        }
        ImageView glyph = new ImageView(host);
        glyph.setImageResource(R.drawable.ic_diverged);
        glyph.setImageTintList(
                ColorStateList.valueOf(host.ui.resolveColor(android.R.attr.colorError)));
        glyph.setContentDescription(host.getString(R.string.contact_diverged));
        return glyph;
    }

    /** One chip per side of a conflict, the contact form's. */
    private View chips(EventMerge.Conflict one, Consumer<EventMerge.Choice> onPick) {
        LinearLayout chips = new LinearLayout(host);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (EventMerge.Choice choice : one.choices()) {
            Button chip = new Button(host, null, android.R.attr.buttonStyleSmall);
            chip.setText(choiceLabel(one, choice));
            chip.setAllCaps(false);
            chip.setMaxLines(3);
            chip.setMaxWidth(host.dp(240));
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setOnClickListener(view -> onPick.accept(choice));
            chips.addView(chip);
        }

        HorizontalScrollView scroll = new HorizontalScrollView(host);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(chips);
        return scroll;
    }

    /**
     * Asks which side a conflict takes, OK settling it.
     *
     * <p>The held side, then a chip per side. {@code other}, when given, is the
     * free edit a date or status leads to, which reviews the conflict too.
     */
    private void choose(EventMerge.Conflict one, Runnable other) {
        EventMerge.Choice[] chosen = {picked(one)};
        TextView shown = new TextView(host);
        shown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        shown.setPadding(0, host.dp(8), 0, host.dp(8));
        shown.setText(choiceLabel(one, chosen[0]));

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(host.dp(24), host.dp(8), host.dp(24), 0);
        content.addView(shown);
        content.addView(
                chips(
                        one,
                        choice -> {
                            chosen[0] = choice;
                            shown.setText(choiceLabel(one, choice));
                        }));

        AlertDialog.Builder dialog =
                new AlertDialog.Builder(host)
                        .setTitle(fieldLabel(one))
                        .setView(content)
                        .setPositiveButton(
                                android.R.string.ok,
                                (view, which) -> {
                                    pick(one, chosen[0]);
                                    render();
                                })
                        .setNegativeButton(android.R.string.cancel, null);
        if (other != null) {
            dialog.setNeutralButton(
                    R.string.event_conflict_other,
                    (view, which) -> {
                        reviewed.add(one.id());
                        other.run();
                    });
        }
        dialog.show();
    }

    /**
     * Gives a conflict one side: its row's value and the save's baseline.
     *
     * <p>So the side travels as the pick it is, not as an edit written over it.
     */
    private void pick(EventMerge.Conflict one, EventMerge.Choice choice) {
        picks.put(one.id(), choice.side());
        reviewed.add(one.id());
        if ("when".equals(one.field())
                && sameOccurrence(one.recurrenceId(), detail.recurrenceId())
                && !(series() && one.recurrenceId() == null)) {
            // NOTE: the end row follows only where the page has one: a span
            // run by a DURATION shows none.
            String end = TODO.equals(component()) ? "due" : "end";
            for (String key : new String[] {"start", end}) {
                EventTime time = "start".equals(key) ? choice.time() : choice.end();
                if ("start".equals(key) || openedTimes.get(key) != null) {
                    times.put(key, time);
                    openedTimes.put(key, time);
                }
            }
            return;
        }
        if (!inPlace(one)) {
            return;
        }

        EventEdit.Field text = textOf(one.field());
        if (List.of(DATES).contains(one.field())) {
            times.put(one.field(), choice.time());
            openedTimes.put(one.field(), choice.time());
        } else if (text != null) {
            put(text, choice.value());
            opened.put(text, choice.value());
        }
    }

    /** A conflict's current side: the picked one, else the pre-filled one. */
    private EventMerge.Choice picked(EventMerge.Conflict one) {
        EventSide side = picks.get(one.id());
        for (EventMerge.Choice choice : one.choices()) {
            if (choice.side().equals(side)) {
                return choice;
            }
        }
        return one.choices().get(0);
    }

    /**
     * What a side of a conflict reads as.
     *
     * <p>Its value, its moment for a date, and for an occurrence or the entry
     * whole, what keeping it means.
     */
    private String choiceLabel(EventMerge.Conflict one, EventMerge.Choice choice) {
        if (EventConflictKind.RECURRENCE.equals(one.kind())) {
            return host.getString(
                    choice.side().equals(one.series())
                            ? R.string.event_conflict_follow
                            : R.string.event_conflict_keep);
        }
        if ("occurrence".equals(one.field()) || "entry".equals(one.field())) {
            if (choice.value().isEmpty()) {
                return host.getString(R.string.event_conflict_removed);
            }
            return host.getString(
                    EventSide.LOCAL.equals(choice.side())
                            ? R.string.event_conflict_here
                            : R.string.event_conflict_there);
        }
        if ("when".equals(one.field())) {
            return moment(choice.time()) + "\n" + moment(choice.end());
        }
        if (choice.time() != null) {
            return moment(choice.time());
        }
        return choice.value().isEmpty() ? host.getString(R.string.value_not_set) : choice.value();
    }

    /** What a conflict is called: its row's label, else the property's name. */
    private String fieldLabel(EventMerge.Conflict one) {
        switch (one.field()) {
            case "start":
                return host.getString(startLabel());
            case "recurrence":
                return host.getString(R.string.event_field_repeats);
            case "when":
                return host.getString(R.string.event_section_when);
            case "occurrence":
                return host.getString(R.string.event_conflict_occurrence);
            case "entry":
                return host.getString(R.string.event_conflict_entry);
            default:
                for (Field field : CATALOG) {
                    if (field.key.equals(one.field())) {
                        return host.getString(field.label);
                    }
                }
                return one.field();
        }
    }

    private void put(EventEdit.Field key, String value) {
        model.put(key, value);
    }

    private String value(EventEdit.Field key) {
        return Objects.toString(model.get(key), "");
    }

    private boolean allDay() {
        return allDay;
    }

    /** The edit object's text field keyed {@code key}; null for others. */
    private static EventEdit.Field textOf(String key) {
        for (EventEdit.Field text : EventEdit.Field.values()) {
            if (text.wire().equals(key)) {
                return text;
            }
        }
        return null;
    }

    /**
     * The stored object's properties, or null when it cannot be read.
     *
     * <p>The agenda placed the row from it, so saying only what the row said
     * beats failing. On an occurrence an override replaces, the override's.
     */
    private EventDetail detailOf(EventStore.StoredEvent event) {
        try {
            return CalendarClient.readEvent(
                    event.ical, series() ? occurrence.recurrenceId().time() : null);
        } catch (Exception error) {
            Log.w("pimalaya", "event read failed: " + event.id, error);
            return null;
        }
    }

    /**
     * What the page changed, as an edit object: the differing fields alone.
     *
     * <p>A date carries its zone only when not its property's, so a date edited
     * in place keeps its line's {@code TZID} and {@code VALUE}; one in a zone
     * the object may not define brings its {@code VTIMEZONE}.
     */
    private EventEdit edited() {
        Map<EventEdit.Field, String> texts = new EnumMap<>(EventEdit.Field.class);
        for (EventEdit.Field key : EventEdit.Field.values()) {
            if (!value(key).equals(Objects.toString(opened.get(key), ""))) {
                texts.put(key, value(key));
            }
        }

        Map<EventEdit.DateField, DateEdit> dates = new EnumMap<>(EventEdit.DateField.class);
        String vtimezone = null;
        for (EventEdit.DateField key : EventEdit.DateField.values()) {
            EventTime now = times.get(key.wire());
            EventTime was = openedTimes.get(key.wire());
            if (same(now, was)) {
                continue;
            }

            String time = now == null ? "" : now.time();
            if (now != null && (was == null || !sameZone(now, was))) {
                ZoneId zone =
                        EventTimeKind.ZONED.equals(now.kind()) ? Zones.zoneOf(now.tzid()) : null;
                if (zone != null && vtimezone == null) {
                    vtimezone = Zones.vtimezone(zone, Zones.instant(now));
                }
                dates.put(key, new DateEdit(time, now.kind(), now.tzid()));
            } else {
                dates.put(key, new DateEdit(time, null, null));
            }
        }

        return EventEdit.of(texts, dates, vtimezone, now());
    }

    /**
     * Whether an edit changes anything beyond the stamp every save carries.
     *
     * <p>A field, or a date with the zone it may bring.
     */
    private static boolean beyondStamp(EventEdit edit) {
        return edit.summary() != null
                || edit.description() != null
                || edit.location() != null
                || edit.url() != null
                || edit.status() != null
                || edit.categories() != null
                || edit.priority() != null
                || edit.start() != null
                || edit.end() != null
                || edit.due() != null
                || edit.completed() != null
                || edit.percentComplete() != null
                || edit.vtimezone() != null;
    }

    private static boolean same(EventTime left, EventTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.time().equals(right.time()) && sameZone(left, right);
    }

    private static boolean sameZone(EventTime left, EventTime right) {
        return left.kind().equals(right.kind()) && Objects.equals(left.tzid(), right.tzid());
    }

    /** Now, as the UTC stamp DTSTAMP and LAST-MODIFIED are written in. */
    private static String now() {
        return Zones.utc(System.currentTimeMillis());
    }

    /**
     * Patches the object and stages it; the next sync pushes it.
     *
     * <p>The store alone, so it works offline. A create goes out guarded by
     * {@code If-None-Match}, an edit by the ETag it was read at, not whatever
     * arrived since. On a series occurrence it first asks the scope.
     */
    public void save() {
        if (detail == null) {
            host.toast(host.getString(R.string.event_unreadable));
            return;
        }
        if (!writes()) {
            host.toast(host.getString(R.string.event_read_only));
            return;
        }
        if (!settled()) {
            host.toast(host.getString(R.string.contact_diverged_pending));
            return;
        }
        // NOTE: a resolution with nothing edited beside it is one of the
        // whole object, so there is no occurrence to ask about.
        if (creating || !series() || merge != null && !changed()) {
            save(EventScope.ALL);
            return;
        }
        scope(
                R.string.event_scope_save,
                AccountClient.writesOverrides(calendar.url),
                this::save);
    }

    /** Whether the page edited anything beyond the stamp every save carries. */
    private boolean changed() {
        return beyondStamp(edited());
    }

    /**
     * Asks which occurrences of the series a change is for.
     *
     * <p>This occurrence alone is offered only where the calendar's push
     * carries it, as every calendar backend's does: offering it where one did
     * not would stage a change the server never sees.
     */
    private void scope(int title, boolean one, Consumer<EventScope> then) {
        List<EventScope> scopes = new ArrayList<>();
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
     * Saves for one scope: series or lone entry, one occurrence, or a split.
     *
     * <p>A split stages the new series' create, then the shortened one's edit:
     * a failure between leaves an occurrence twice, never none. In conflict
     * mode it applies to the resolution, on the conflicted binding.
     */
    private void save(EventScope scope) {
        EventEdit edits = edited();
        boolean changed = beyondStamp(edits);
        EventEdit edited = scoped(edits, scope);
        String uid = edited.uid() == null ? "" : edited.uid();
        EventStore.StoredEvent target = event;
        EventStore.StoredCalendar collection = calendar;
        boolean isNew = creating;
        boolean rewritten = changed;
        EventStore.StoredConflict settling = conflict;
        Map<Integer, EventSide> chosen = new HashMap<>(picks);

        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        new EventWrites(new CalendarStager(parts.pimdir(), parts.clients()))
                                .save(
                                        new EventWrites.Save(
                                                scope,
                                                edited,
                                                uid,
                                                target,
                                                collection,
                                                isNew,
                                                rewritten,
                                                settling,
                                                chosen));
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
                                list.reload();
                                host.showBack(Panels.CALENDAR);
                            });
                });
    }

    /**
     * An edit on the opened occurrence, for one scope.
     *
     * <p>Which instance it is, the instant a split ends the series before, and
     * the new series' UID.
     */
    private EventEdit scoped(EventEdit edit, EventScope scope) {
        String recurrenceId = null;
        String recurrenceInstant = null;
        if (series()) {
            recurrenceId = occurrence.recurrenceId().time();
            recurrenceInstant = Zones.utc(Zones.instant(occurrence.recurrenceId()));
        }
        String uid = null;
        if (scope == EventScope.FOLLOWING) {
            uid = UUID.randomUUID().toString();
        }
        return edit.scoped(scope, recurrenceId, recurrenceInstant, uid);
    }

    /**
     * Asks before deleting: an entry is one tap from being gone.
     *
     * <p>On a series occurrence, the question is which occurrences.
     */
    public void confirmDelete() {
        if (!writes()) {
            host.toast(host.getString(R.string.event_read_only));
            return;
        }
        if (!creating && series()) {
            scope(
                    R.string.event_scope_delete,
                    AccountClient.writesExdates(calendar.url),
                    scope -> {
                        if (scope == EventScope.ALL && merge != null) {
                            confirmWhole();
                        } else {
                            delete(scope);
                        }
                    });
            return;
        }
        confirmWhole();
    }

    /**
     * Asks before deleting the entry whole.
     *
     * <p>A conflicted one says the other side's version goes too: the page
     * showed it, so deleting decides the conflict, pushed against what the
     * source was seen to hold.
     */
    private void confirmWhole() {
        new AlertDialog.Builder(host)
                .setMessage(
                        merge != null
                                ? R.string.event_delete_conflict_confirm
                                : R.string.event_delete_confirm)
                .setPositiveButton(R.string.event_delete, (dialog, which) -> delete(EventScope.ALL))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Stages the removal and leaves; the next sync sends it, ETag-guarded.
     *
     * <p>One occurrence becomes an {@code EXDATE}, this and following ends the
     * series, unless nothing is left; an unsaved entry just closes. A conflict
     * stages on the conflicted binding, gated on its recorded revision.
     */
    private void delete(EventScope scope) {
        if (creating) {
            host.showBack(Panels.CALENDAR);
            return;
        }
        if (scope != EventScope.ALL && !settled()) {
            host.toast(host.getString(R.string.contact_diverged_pending));
            return;
        }

        EventEdit edit = scoped(EventEdit.of(Map.of(), Map.of(), null, now()), scope);

        EventStore.StoredEvent target = event;
        EventStore.StoredCalendar collection = calendar;
        EventStore.StoredConflict settling = conflict;
        Map<Integer, EventSide> chosen = new HashMap<>(picks);

        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        new EventWrites(new CalendarStager(parts.pimdir(), parts.clients()))
                                .delete(
                                        new EventWrites.Delete(
                                                scope, edit, target, collection, settling, chosen));
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
                                list.reload();
                                host.showBack(Panels.CALENDAR);
                            });
                });
    }
}
