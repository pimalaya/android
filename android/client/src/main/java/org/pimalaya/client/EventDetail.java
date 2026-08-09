package org.pimalaya.client;

import java.util.List;

/**
 * One calendar component read whole, for the page that shows it.
 *
 * <p>A projection rather than the object: everything RFC 5545 lets a
 * VEVENT, VTODO or VJOURNAL carry that a reader would look for, in the
 * one shape all three share, with the properties only one of them has
 * left empty by the other two. One class and not three, because the page
 * differs by which sections it draws and not by what it can be handed.
 */
public final class EventDetail {
    /** The component's wire name: {@code VEVENT}. */
    public static final String EVENT = "VEVENT";

    /** The component's wire name: {@code VTODO}. */
    public static final String TODO = "VTODO";

    /** The component's wire name: {@code VJOURNAL}. */
    public static final String JOURNAL = "VJOURNAL";

    /** Which of the three this is. */
    public final String component;

    /** {@code UID}, the identity every replica of this shares. */
    public final String uid;

    public final String summary;
    public final String description;
    public final String location;
    public final String url;

    /** {@code STATUS}, as written: the vocabulary differs per component. */
    public final String status;

    /** {@code CATEGORIES}, comma separated. */
    public final String categories;

    /** {@code DTSTART}, raw; empty on a to-do carrying only a {@code DUE}. */
    public final String start;

    /** {@code DTEND}, raw; only an event has one. */
    public final String end;

    /** {@code DUE}, raw; only a to-do has one. */
    public final String due;

    /** {@code COMPLETED}, raw; only a to-do has one. */
    public final String completed;

    /** Whether the placing date is a DATE rather than a DATE-TIME. */
    public final boolean allDay;

    /** {@code RRULE}, raw, empty when it does not repeat. */
    public final String recurrence;

    public final String priority;

    /** {@code PERCENT-COMPLETE}, as written; only a to-do has one. */
    public final String percentComplete;

    /** {@code ORGANIZER}, the address alone. */
    public final String organizer;

    /** Every {@code ATTENDEE}, in the order the object lists them. */
    public final List<Attendee> attendees;

    public final String created;
    public final String lastModified;

    public EventDetail(
            String component,
            String uid,
            String summary,
            String description,
            String location,
            String url,
            String status,
            String categories,
            String start,
            String end,
            String due,
            String completed,
            boolean allDay,
            String recurrence,
            String priority,
            String percentComplete,
            String organizer,
            List<Attendee> attendees,
            String created,
            String lastModified) {
        this.component = component;
        this.uid = uid;
        this.summary = summary;
        this.description = description;
        this.location = location;
        this.url = url;
        this.status = status;
        this.categories = categories;
        this.start = start;
        this.end = end;
        this.due = due;
        this.completed = completed;
        this.allDay = allDay;
        this.recurrence = recurrence;
        this.priority = priority;
        this.percentComplete = percentComplete;
        this.organizer = organizer;
        this.attendees = attendees;
        this.created = created;
        this.lastModified = lastModified;
    }

    /** One attendee of a component: who, and where they stand. */
    public static final class Attendee {
        /** The {@code CN} parameter, empty when there is none. */
        public final String name;

        /** The calendar user address, {@code mailto:} stripped. */
        public final String address;

        /** The {@code PARTSTAT} parameter, empty when there is none. */
        public final String status;

        public Attendee(String name, String address, String status) {
            this.name = name;
            this.address = address;
            this.status = status;
        }
    }
}
