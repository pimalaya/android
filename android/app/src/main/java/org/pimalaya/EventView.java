package org.pimalaya;

import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

import org.pimalaya.client.EventDetail;
import org.pimalaya.client.Occurrence;

import java.util.ArrayList;
import java.util.List;

/**
 * The reader for one calendar entry, on the page the contact editor is
 * drawn on.
 *
 * <p>A contact and an event are the same page with different sections,
 * which is why they share {@link Sections}: the difference is what goes
 * in the rows, and the rows are the same rows. Read-only for now, so
 * nothing here is tappable.
 *
 * <p>The sections differ per component, and that is the point of
 * splitting them: an event is placed between two moments, a to-do is due
 * and partly done, a journal entry is written on a day and has neither.
 * Drawing all three from one table of every property would show a to-do
 * an end it does not have and an event a completion it cannot reach.
 *
 * <p>What the page shows is the <em>occurrence</em> the reader tapped,
 * not the stored component: the fifteenth Monday of a weekly meeting is
 * on its own day, and only the properties that do not move (the
 * description, the attendees, the rule itself) come off the object.
 */
final class EventView {
    private final MainActivity host;
    private final Sections sections;

    /** What is on screen, for the bar to title itself with. */
    private String title = "";

    EventView(MainActivity host) {
        this.host = host;
        this.sections =
                new Sections(host, (LinearLayout) host.findViewById(R.id.event_view_page));
    }

    /** What the bar calls the entry: its summary, else its kind. */
    String title() {
        return title;
    }

    /** Opens the reader on one agenda row. */
    void open(
            Occurrence occurrence,
            EventStore.StoredEvent event,
            EventStore.StoredCalendar calendar) {
        EventDetail detail = detailOf(event);
        title =
                occurrence.summary.isEmpty()
                        ? host.getString(CalendarList.componentName(occurrence.component))
                        : occurrence.summary;

        render(occurrence, detail, calendar);
        host.show(MainActivity.PANEL_EVENT_VIEW);
    }

    /**
     * The stored object's properties, or an empty projection when it
     * cannot be read: the agenda placed the row from the same object, so
     * a page that says only what the row said beats a page that fails.
     */
    private EventDetail detailOf(EventStore.StoredEvent event) {
        try {
            return host.client.readEvent(event.ical);
        } catch (Exception error) {
            Log.w("pimalaya", "event read failed: " + event.id, error);
            return null;
        }
    }

    private void render(
            Occurrence occurrence, EventDetail detail, EventStore.StoredCalendar calendar) {
        sections.clear();

        when(occurrence, detail);
        if (EventDetail.TODO.equals(occurrence.component)) {
            progress(detail);
        }
        where(occurrence);
        details(detail);
        people(detail);
        origin(occurrence, calendar);
    }

    /**
     * When it happens: the placing moment first, then whatever bounds it
     * on the other side, which is an end for an event, a due date for a
     * to-do and nothing at all for a journal entry.
     */
    private void when(Occurrence occurrence, EventDetail detail) {
        List<View> rows = new ArrayList<>();
        rows.add(sections.value(moment(occurrence.start, occurrence.allDay), startLabel(occurrence)));

        if (EventDetail.EVENT.equals(occurrence.component)
                && CalendarList.secondsBetween(occurrence.start, occurrence.end) > 0) {
            rows.add(
                    sections.value(
                            moment(occurrence.end, occurrence.allDay), R.string.event_field_end));
        }

        String duration = CalendarList.durationLabel(host, occurrence);
        if (!duration.isEmpty()) {
            rows.add(sections.value(duration, R.string.event_field_duration));
        }

        rows.add(
                sections.value(
                        CalendarList.countdownLabel(host, occurrence),
                        R.string.event_field_countdown));

        if (detail != null && !detail.recurrence.isEmpty()) {
            rows.add(sections.value(detail.recurrence, R.string.event_field_repeats));
        }

        sections.section(
                R.string.event_section_when,
                CalendarList.glyphOf(occurrence.component),
                rows,
                null,
                false);
    }

    /** How far along a to-do is, which is the only thing only it has. */
    private void progress(EventDetail detail) {
        if (detail == null) {
            return;
        }

        List<View> rows = new ArrayList<>();
        add(rows, detail.due, R.string.event_field_due);
        add(rows, detail.completed, R.string.event_field_completed);
        add(rows, percent(detail.percentComplete), R.string.event_field_progress);
        add(rows, detail.priority, R.string.event_field_priority);

        sections.section(
                R.string.event_section_progress, R.drawable.ic_check, rows, null, false);
    }

    /** A percentage as one, rather than as the bare number stored. */
    private String percent(String value) {
        return value.isEmpty() ? "" : host.getString(R.string.event_percent, value);
    }

    private void where(Occurrence occurrence) {
        List<View> rows = new ArrayList<>();
        add(rows, occurrence.location, R.string.event_field_location);

        sections.section(
                R.string.event_section_where,
                R.drawable.ic_section_location_on,
                rows,
                null,
                false);
    }

    private void details(EventDetail detail) {
        if (detail == null) {
            return;
        }

        List<View> rows = new ArrayList<>();
        add(rows, detail.description, R.string.event_field_description);
        add(rows, detail.categories, R.string.event_field_categories);
        add(rows, detail.status, R.string.event_field_status);
        add(rows, detail.url, R.string.event_field_url);

        sections.section(
                R.string.event_section_details, R.drawable.ic_section_notes, rows, null, false);
    }

    /** Who is on it: the organizer, then everyone invited. */
    private void people(EventDetail detail) {
        if (detail == null) {
            return;
        }

        List<View> rows = new ArrayList<>();
        add(rows, detail.organizer, R.string.event_field_organizer);
        for (EventDetail.Attendee attendee : detail.attendees) {
            rows.add(sections.row(attendeeName(attendee), attendeeStanding(attendee), null, null));
        }

        sections.section(
                R.string.event_section_people, R.drawable.ic_section_group, rows, null, false);
    }

    /** An attendee as they are named: their CN, else their address. */
    private String attendeeName(EventDetail.Attendee attendee) {
        return attendee.name.isEmpty() ? attendee.address : attendee.name;
    }

    /**
     * What sits under an attendee: their standing, and their address too
     * when the name above is not it.
     */
    private String attendeeStanding(EventDetail.Attendee attendee) {
        String standing =
                attendee.status.isEmpty()
                        ? host.getString(R.string.event_field_attendee)
                        : attendee.status;
        return attendee.name.isEmpty() ? standing : standing + " · " + attendee.address;
    }

    /** Which calendar of which account the entry lives in. */
    private void origin(Occurrence occurrence, EventStore.StoredCalendar calendar) {
        List<View> rows = new ArrayList<>();
        rows.add(sections.value(calendar.name, R.string.event_field_calendar));
        rows.add(sections.value(calendar.accountEmail, R.string.event_field_account));
        rows.add(
                sections.value(
                        host.getString(CalendarList.componentName(occurrence.component)),
                        R.string.event_field_kind));

        sections.section(
                R.string.event_section_calendar,
                R.drawable.ic_domain_calendar,
                rows,
                null,
                false);
    }

    /** Adds a row, unless the property it would show is unset. */
    private void add(List<View> rows, String value, int label) {
        if (!value.isEmpty()) {
            rows.add(sections.value(value, label));
        }
    }

    /** What a to-do's placing moment is called, against an event's. */
    private static int startLabel(Occurrence occurrence) {
        if (EventDetail.TODO.equals(occurrence.component)) {
            return R.string.event_field_scheduled;
        }
        return EventDetail.JOURNAL.equals(occurrence.component)
                ? R.string.event_field_written
                : R.string.event_field_start;
    }

    /** A civil stamp as a reader reads it: the full date, plus the time
     *  when the entry has one. */
    private String moment(String civil, boolean allDay) {
        long stamp = CalendarList.stampOf(civil);
        return allDay ? Dates.date(host, stamp) : Dates.full(host, stamp);
    }
}
