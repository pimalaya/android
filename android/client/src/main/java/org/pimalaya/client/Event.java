package org.pimalaya.client;

/**
 * One calendar object as the server stores it, iCalendar text and all.
 *
 * <p>Deliberately unparsed: what a recurring event renders as depends on
 * the window being shown, so decoding happens at
 * {@link PimalayaClient#expandEvent} where that window is known.
 */
public final class Event {
    /** Resource name with any {@code .ics} stripped. */
    public final String id;

    /** Entity tag guarding concurrent updates, or null. */
    public final String etag;

    /** Raw iCalendar text, one VCALENDAR. */
    public final String ical;

    public Event(String id, String etag, String ical) {
        this.id = id;
        this.etag = etag;
        this.ical = ical;
    }
}
