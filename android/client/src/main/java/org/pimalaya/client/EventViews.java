package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The transport-free half of the bridge for calendar objects on the phone:
 * an object projected as the calendar provider carries it, and a view the
 * phone edited patched back (docs/calendar-mapping.md). Static, as
 * {@link Cards} is for vCards.
 */
public final class EventViews {
    private EventViews() {}

    /** Forwards to {@link CalendarClient#projectEvent}. */
    public static JSONObject projectEvent(String ical, String now) {
        return CalendarClient.projectEvent(ical, now);
    }

    /** Forwards to {@link CalendarClient#applyEvent}. */
    public static String applyEvent(String ical, JSONObject edit) {
        return CalendarClient.applyEvent(ical, edit);
    }
}
