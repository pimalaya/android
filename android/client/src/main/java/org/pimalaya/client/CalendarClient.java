package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The calendar verbs of the bridge (rs/ffi/calendar.rs): the calendar and
 * event operations over a transport, and the pure iCalendar computations,
 * static, that never open a socket. Every call that takes a transport
 * blocks, so callers must run it off the main thread.
 */
public class CalendarClient {
    /** Lists the account's calendars: the CalDAV discovery walk. */
    public List<Calendar> listCalendars(Transport transport, Account account) {
        JSONArray reply =
                Replies.array(
                        Native.listCalendars(
                                transport, account.baseUrl, account.login, account.password));

        List<Calendar> calendars = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject calendar = Json.object(reply, index);
            calendars.add(
                    new Calendar(
                            Json.string(calendar, "id"),
                            Json.string(calendar, "name"),
                            Json.string(calendar, "url"),
                            Json.optString(calendar, "description"),
                            Json.optString(calendar, "color"),
                            calendar.optString("role"),
                            calendar.optBoolean("writable", true)));
        }
        return calendars;
    }

    /**
     * Enumerates a calendar collection from the cursor the last pass
     * stored: which events moved and which went, and no bodies.
     *
     * <p>The bodies are {@link #multigetEvents}, for the events the merge
     * asks about. A round that carried them would be re-reading a whole
     * calendar to find out that nothing in it changed, which is what this
     * replaced; only a round that lists every event whole anyway (Google,
     * JMAP) hands them over, in {@link EventDelta#bodies}.
     */
    public EventDelta syncEvents(
            Transport transport, Account account, String calendarUrl, String cursor) {
        JSONObject reply =
                Replies.object(
                        Native.syncEvents(
                                transport,
                                account.baseUrl,
                                calendarUrl,
                                account.login,
                                account.password,
                                cursor == null ? "" : cursor));

        JSONArray listed = reply.optJSONArray("changed");
        List<EventRef> changed = new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject event = Json.object(listed, index);
            changed.add(new EventRef(Json.string(event, "id"), Json.optString(event, "etag")));
        }

        return new EventDelta(
                changed,
                events(reply.optJSONArray("bodies")),
                Json.strings(reply.optJSONArray("vanished")),
                Json.optString(reply, "token"),
                reply.optBoolean("complete"));
    }

    /** The iCalendar text of the named events, in one round. */
    public List<Event> multigetEvents(
            Transport transport, Account account, String calendarUrl, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return events(
                Replies.array(
                        Native.multigetEvents(
                                transport,
                                account.baseUrl,
                                calendarUrl,
                                account.login,
                                account.password,
                                new JSONArray(ids).toString())));
    }

    /** The events of a reply's array, none for a missing one. */
    private static List<Event> events(JSONArray reply) {
        if (reply == null) {
            return List.of();
        }
        List<Event> events = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject event = Json.object(reply, index);
            events.add(
                    new Event(
                            Json.string(event, "id"),
                            Json.optString(event, "etag"),
                            Json.string(event, "ical")));
        }
        return events;
    }

    /**
     * Expands one calendar object into the occurrences starting inside
     * {@code [windowStart, windowEnd)}, both civil stamps compared with
     * each start as written. Its recurrence set is RFC 5545 complete
     * (ical-rs): exceptions out, overrides in their instance's place. A
     * non-recurring event yields at most one occurrence.
     */
    public static List<Occurrence> expandEvent(String ical, String windowStart, String windowEnd) {
        JSONArray reply = Replies.array(Native.expandEvent(ical, windowStart, windowEnd));

        List<Occurrence> occurrences = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject occurrence = Json.object(reply, index);
            occurrences.add(
                    new Occurrence(
                            Json.string(occurrence, "component"),
                            eventTime(occurrence.optJSONObject("start")),
                            eventTime(occurrence.optJSONObject("end")),
                            eventTime(occurrence.optJSONObject("recurrenceId")),
                            Json.string(occurrence, "summary"),
                            Json.string(occurrence, "location"),
                            occurrence.optBoolean("allDay")));
        }
        return occurrences;
    }

    /** One time of a reply, null for a missing one. */
    private static EventTime eventTime(JSONObject time) {
        if (time == null) {
            return null;
        }
        return new EventTime(
                time.optString("time"),
                time.optString("kind"),
                time.optString("tzid"),
                time.isNull("offset") ? null : time.optInt("offset"));
    }

    /**
     * Reads one calendar object whole, for the page that shows it: the
     * series, or the override of the occurrence {@code recurrenceId}
     * names when the object holds one (null or empty for the series).
     * Pure computation, like the expansion beside it: no transport, no
     * account.
     */
    public static EventDetail readEvent(String ical, String recurrenceId) {
        JSONObject reply =
                Replies.object(Native.readEvent(ical, recurrenceId == null ? "" : recurrenceId));

        JSONArray listed = reply.optJSONArray("attendees");
        List<EventDetail.Attendee> attendees = new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject attendee = Json.object(listed, index);
            attendees.add(
                    new EventDetail.Attendee(
                            attendee.optString("name"),
                            attendee.optString("address"),
                            attendee.optString("status")));
        }

        return new EventDetail(
                reply.optString("component"),
                reply.optString("uid"),
                reply.optString("summary"),
                reply.optString("description"),
                reply.optString("location"),
                reply.optString("url"),
                reply.optString("status"),
                reply.optString("categories"),
                eventTime(reply.optJSONObject("start")),
                eventTime(reply.optJSONObject("end")),
                eventTime(reply.optJSONObject("due")),
                eventTime(reply.optJSONObject("completed")),
                reply.optBoolean("allDay"),
                reply.optString("recurrence"),
                reply.optString("priority"),
                reply.optString("percentComplete"),
                reply.optString("organizer"),
                attendees,
                reply.optString("created"),
                reply.optString("lastModified"),
                eventTime(reply.optJSONObject("recurrenceId")));
    }

    /**
     * Applies one edit to a calendar object, returning the new
     * iCalendar text. A patch through the object's concrete syntax
     * tree, so everything the edit does not name survives untouched.
     *
     * <p>{@code edit} is a JSON object of the properties to set; an
     * absent key is left alone and a present empty one removes its
     * property. Pure computation, no transport.
     */
    public static String writeEvent(String ical, String edit) {
        String written = Native.writeEvent(ical, edit).trim();

        // NOTE: the reply is the object itself, so an error has to be
        // told apart from a body. It is: no calendar object opens with
        // a brace, so a brace is the bridge's error shape.
        if (written.startsWith("{")) {
            Replies.object(written);
            throw new PimalayaException("Unreadable bridge reply: expected an object");
        }
        return written;
    }

    /**
     * Three-way merges a conflicted calendar object: the body staged here
     * and the one its source holds, against their base (null or empty
     * when none was agreed). Pure computation, no transport.
     */
    public static EventMerge mergeEvent(String base, String local, String remote) {
        JSONObject reply = Replies.object(Native.mergeEvent(base == null ? "" : base, local, remote));

        JSONArray listed = reply.optJSONArray("conflicts");
        List<EventMerge.Conflict> conflicts =
                new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject conflict = Json.object(listed, index);
            JSONArray offered = conflict.optJSONArray("choices");
            List<EventMerge.Choice> choices =
                    new ArrayList<>(offered == null ? 0 : offered.length());
            for (int at = 0; offered != null && at < offered.length(); at++) {
                JSONObject choice = Json.object(offered, at);
                choices.add(
                        new EventMerge.Choice(
                                choice.optString("side"),
                                choice.optString("value"),
                                eventTime(choice.optJSONObject("time")),
                                eventTime(choice.optJSONObject("end"))));
            }
            conflicts.add(
                    new EventMerge.Conflict(
                            conflict.optInt("id"),
                            conflict.optString("kind"),
                            conflict.optString("component"),
                            eventTime(conflict.optJSONObject("recurrenceId")),
                            conflict.optString("field"),
                            Json.optString(conflict, "series"),
                            choices));
        }

        return new EventMerge(Json.string(reply, "ical"), reply.optBoolean("resolved"), conflicts);
    }

    /**
     * The resolution of a conflicted calendar object: its merge, each
     * conflict {@code picks} names by its id taking that side
     * ({@link EventMerge#LOCAL} or {@link EventMerge#REMOTE}), every other
     * the side it was pre-filled with. Pure computation, no transport.
     */
    public static String resolveEvent(
            String base, String local, String remote, Map<Integer, String> picks) {
        JSONObject sides = new JSONObject();
        try {
            for (Map.Entry<Integer, String> pick : picks.entrySet()) {
                sides.put(String.valueOf(pick.getKey()), pick.getValue());
            }
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable picks: " + error.getMessage());
        }

        String resolved =
                Native.resolveEvent(base == null ? "" : base, local, remote, sides.toString())
                        .trim();
        // NOTE: an object is the bridge's error shape here, as in writeEvent.
        if (resolved.startsWith("{")) {
            Replies.object(resolved);
            throw new PimalayaException("Unreadable bridge reply: expected an object");
        }
        return resolved;
    }

    /**
     * Splits a series at the occurrence {@code edit} names, its scope
     * {@code following}: the series ended before it, and a new one under
     * the edit's {@code uid} carrying the edit from it on. Pure
     * computation, no transport.
     */
    public static EventSplit splitEvent(String ical, String edit) {
        JSONObject reply = Replies.object(Native.splitEvent(ical, edit));
        return new EventSplit(Json.string(reply, "master"), Json.optString(reply, "series"));
    }

    /**
     * Removes the occurrences {@code edit}'s scope names from a series:
     * one becomes an {@code EXDATE}, this and following ends the series
     * before it. Returns the object left, or null when nothing is and the
     * entry itself goes. Pure computation, no transport.
     */
    public static String removeEvent(String ical, String edit) {
        String left = Native.removeEvent(ical, edit).trim();

        // NOTE: an object is the bridge's error shape here too, for the
        // same reason it is in writeEvent.
        if (left.startsWith("{")) {
            Replies.object(left);
            throw new PimalayaException("Unreadable bridge reply: expected an object");
        }
        return left.isEmpty() ? null : left;
    }

    /**
     * Pushes an edited object back to the calendar it came from,
     * guarded by the ETag it was read with, and returns the new one.
     *
     * <p>Guarded on purpose: a calendar is shared, and an unguarded PUT
     * is how one client silently overwrites another's edit.
     *
     * <p>{@code address} is the account's: a backend that announces a
     * write to the attendees only when asked (Google, JMAP) asks for a
     * meeting it names the organizer of.
     */
    public String updateEvent(
            Transport transport,
            Account account,
            String calendarUrl,
            String id,
            String ical,
            String etag,
            String address) {
        String reply =
                Native.updateEvent(
                        transport,
                        account.baseUrl,
                        calendarUrl,
                        account.login,
                        account.password,
                        id,
                        ical,
                        etag == null ? "" : etag,
                        address == null ? "" : address);

        return Replies.etag(reply);
    }

    /**
     * The object a new calendar entry starts from: one component
     * carrying the identity, the composition stamp and its start, in the
     * zone {@code tzid} names with that zone's {@code vtimezone} (both
     * empty for a date or a floating start). Pure computation, no
     * transport.
     */
    public static String newEvent(
            String component,
            String uid,
            String stamp,
            String start,
            String tzid,
            String vtimezone) {
        String created = Native.newEvent(component, uid, stamp, start, tzid, vtimezone).trim();

        // NOTE: an object is the bridge's error shape here too, for the
        // same reason it is in writeEvent.
        if (created.startsWith("{")) {
            Replies.object(created);
            throw new PimalayaException("Unreadable bridge reply: expected an object");
        }
        return created;
    }

    /**
     * Files a new object in a calendar, guarded on the resource not
     * existing, and returns where it landed: the name asked for, or the
     * one a backend that names its own resources (Graph) gave it, with
     * its ETag. {@code address} is the account's, as
     * {@link #updateEvent} takes it.
     */
    public EventRef createEvent(
            Transport transport,
            Account account,
            String calendarUrl,
            String id,
            String ical,
            String address) {
        JSONObject created =
                Replies.object(
                        Native.createEvent(
                                transport,
                                account.baseUrl,
                                calendarUrl,
                                account.login,
                                account.password,
                                id,
                                ical,
                                address == null ? "" : address));
        try {
            return new EventRef(
                    created.getString("id"),
                    created.isNull("etag") ? null : created.getString("etag"));
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable created event: " + error.getMessage());
        }
    }

    /**
     * Removes one object from its calendar, guarded by the ETag it was
     * read with, for the same reason the update is. {@code address} is the
     * account's, as {@link #updateEvent} takes it.
     */
    public void deleteEvent(
            Transport transport,
            Account account,
            String calendarUrl,
            String id,
            String etag,
            String address) {
        Replies.object(
                Native.deleteEvent(
                        transport,
                        account.baseUrl,
                        calendarUrl,
                        account.login,
                        account.password,
                        id,
                        etag == null ? "" : etag,
                        address == null ? "" : address));
    }

    /**
     * The view of a calendar object, {@code now} a UTC
     * {@code YYYYMMDDTHHMMSSZ} stamp: {@code {uid, master, overrides,
     * listed}}, each time a {@code {time, kind, tzid, offset}}.
     */
    public static JSONObject projectEvent(String ical, String now) {
        return Replies.object(Native.projectEvent(ical, now));
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
            Replies.object(written);
            throw new PimalayaException("Unreadable bridge reply: expected an object");
        }
        return written;
    }
}
