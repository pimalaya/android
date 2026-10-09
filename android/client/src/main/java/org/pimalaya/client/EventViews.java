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

    /**
     * The view of a calendar object, {@code now} a UTC
     * {@code YYYYMMDDTHHMMSSZ} stamp: {@code {uid, master, overrides,
     * listed}}, each time a {@code {time, kind, tzid, offset}}.
     */
    public static JSONObject projectEvent(String ical, String now) {
        return PimalayaClient.object(Native.projectEvent(ical, now));
    }

    /**
     * The object with an edited view patched onto it: {@code edit} is
     * {@code {event, stamp, addresses, vtimezones}}, and the fields its
     * event carries that differ from what the object projects are all
     * that change. An empty object becomes a new one.
     */
    public static String applyEvent(String ical, JSONObject edit) {
        String written = Native.applyEvent(ical, edit.toString());

        // NOTE: the object itself, byte for byte, so not trimmed; a brace
        // is the bridge's error shape, since no calendar object opens on one.
        if (written.startsWith("{")) {
            PimalayaClient.object(written);
            throw new PimalayaException("Unreadable bridge reply: expected an object");
        }
        return written;
    }
}
