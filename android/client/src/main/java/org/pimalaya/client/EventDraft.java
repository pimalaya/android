package org.pimalaya.client;

/**
 * What a new calendar entry starts from.
 *
 * <p>Mirrors rs/calendar/edit.rs {@code EventDraft} 108.
 *
 * @param component {@code VEVENT}, {@code VTODO} or {@code VJOURNAL}
 * @param uid the {@code UID} identifying the entry
 * @param stamp the {@code DTSTAMP} saying when it was composed
 * @param start the {@code DTSTART}: a date, floating, or in {@link #tzid}
 * @param tzid the zone of the start, empty for none
 * @param vtimezone the {@code VTIMEZONE} defining {@link #tzid}, empty if none
 */
public record EventDraft(
        String component, String uid, String stamp, String start, String tzid, String vtimezone) {}
