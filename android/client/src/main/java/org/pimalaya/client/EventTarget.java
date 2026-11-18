package org.pimalaya.client;

/**
 * The calendar object a write addresses and its revision.
 *
 * @param calendarUrl the calendar collection's URL
 * @param id the object's resource name
 * @param etag the ETag guarding the write; null for a create or none read
 * @param zone the phone's zone as a {@code VTIMEZONE}, which Google and Graph
 *     read a floating time of the written object in; null for none
 */
public record EventTarget(String calendarUrl, String id, String etag, String zone) {
    /** A target read in no zone of the phone's. */
    public EventTarget(String calendarUrl, String id, String etag) {
        this(calendarUrl, id, etag, null);
    }
}
