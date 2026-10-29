package org.pimalaya.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The calendar verbs of the bridge (rs/ffi/calendar.rs).
 *
 * <p>Operations over a transport block: run them off the main thread. The
 * static iCalendar computations are pure and never open a socket.
 */
public class CalendarClient {
    /** Lists the account's calendars: the CalDAV discovery walk. */
    public List<Calendar> listCalendars(Transport transport, Account account) {
        JSONArray reply =
                Replies.array(Native.listCalendars(transport, account.toJson().toString()));

        List<Calendar> calendars = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            calendars.add(Calendar.from(Json.object(reply, index)));
        }
        return calendars;
    }

    /**
     * Enumerates a calendar from the last pass's cursor: events moved and gone.
     *
     * <p>No bodies, so an unchanged calendar is not re-read: the merge asks
     * {@link #multigetEvents}. Rounds listing events whole anyway (Google,
     * JMAP) hand them over in {@link EventDelta#bodies}.
     */
    public EventDelta syncEvents(
            Transport transport, Account account, String calendarUrl, String cursor) {
        return EventDelta.from(
                Replies.object(
                        Native.syncEvents(
                                transport,
                                account.toJson().toString(),
                                calendarUrl,
                                cursor == null ? "" : cursor)));
    }

    /** The iCalendar text of the named events, in one round. */
    public List<Event> multigetEvents(
            Transport transport, Account account, String calendarUrl, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return Event.list(
                Replies.array(
                        Native.multigetEvents(
                                transport,
                                account.toJson().toString(),
                                calendarUrl,
                                new JSONArray(ids).toString())));
    }

    /**
     * Expands one object into the occurrences starting in the window.
     *
     * <p>{@code [windowStart, windowEnd)} are civil stamps compared with each
     * start as written. The RFC 5545 set is complete (ical-rs): exceptions
     * out, overrides in place; a non-recurring event yields at most one.
     */
    public static List<Occurrence> expandEvent(String ical, String windowStart, String windowEnd) {
        JSONArray reply = Replies.array(Native.expandEvent(ical, windowStart, windowEnd));

        List<Occurrence> occurrences = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            occurrences.add(Occurrence.from(Json.object(reply, index)));
        }
        return occurrences;
    }

    /**
     * Reads one calendar object whole, for the page that shows it.
     *
     * <p>The series, or the override {@code recurrenceId} names when the
     * object holds one (null or empty for the series). Pure computation.
     */
    public static EventDetail readEvent(String ical, String recurrenceId) {
        return EventDetail.from(
                Replies.object(Native.readEvent(ical, recurrenceId == null ? "" : recurrenceId)));
    }

    /**
     * Applies one edit to a calendar object, returning the new iCalendar text.
     *
     * <p>A patch through the object's syntax tree: what the edit does not name
     * survives untouched; a null property is left alone, an empty one
     * removed. Pure computation.
     */
    public static String writeEvent(String ical, EventEdit edit) {
        return EventIcal.from(Replies.object(Native.writeEvent(ical, edit.toJson().toString())))
                .ical()
                .trim();
    }

    /**
     * Applies one to-do edit, GTD properties included, returning the new text.
     *
     * <p>The same patch as {@link #writeEvent}. Pure computation.
     */
    public static String writeTask(String ical, TaskEdit edit) {
        return EventIcal.from(Replies.object(Native.writeEvent(ical, edit.toJson().toString())))
                .ical()
                .trim();
    }

    /**
     * Reads the to-dos among objects, keyed by the caller.
     *
     * <p>One call for a whole list; an object holding no VTODO, or one that
     * does not parse, is left out. Pure computation.
     */
    public static Map<String, Task> readTasks(Map<String, String> objects) {
        JSONArray input = new JSONArray();
        try {
            for (Map.Entry<String, String> object : objects.entrySet()) {
                input.put(new JSONObject().put("key", object.getKey()).put("ical", object.getValue()));
            }
        } catch (org.json.JSONException error) {
            throw new PimalayaException("Unwritable task listing: " + error.getMessage());
        }

        JSONArray reply = Replies.array(Native.readTasks(input.toString()));
        Map<String, Task> tasks = new LinkedHashMap<>();
        for (int index = 0; index < reply.length(); index++) {
            JSONObject row = Json.object(reply, index);
            tasks.put(row.optString("key"), Task.from(row.optJSONObject("task")));
        }
        return tasks;
    }

    /**
     * Three-way merges a conflicted object's local and remote bodies.
     *
     * <p>Against their base, null or empty when none was agreed. Pure
     * computation.
     */
    public static EventMerge mergeEvent(String base, String local, String remote) {
        return EventMerge.from(
                Replies.object(Native.mergeEvent(base == null ? "" : base, local, remote)));
    }

    /**
     * The resolution of a conflicted calendar object: its merge, sides picked.
     *
     * <p>Each conflict {@code picks} names by id takes that side, every other
     * its pre-filled one. Pure computation.
     */
    public static String resolveEvent(EventSides bodies, Map<Integer, EventSide> picks) {
        JSONObject sides = new JSONObject();
        try {
            for (Map.Entry<Integer, EventSide> pick : picks.entrySet()) {
                sides.put(String.valueOf(pick.getKey()), pick.getValue().wire());
            }
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable picks: " + error.getMessage());
        }

        return EventIcal.from(
                        Replies.object(
                                Native.resolveEvent(
                                        bodies.base() == null ? "" : bodies.base(),
                                        bodies.local(),
                                        bodies.remote(),
                                        sides.toString())))
                .ical()
                .trim();
    }

    /**
     * Splits a series at the occurrence {@code edit} names, scope following.
     *
     * <p>The series ends before it; a new one under the edit's {@code uid}
     * carries the edit from it on. Pure computation.
     */
    public static EventSplit splitEvent(String ical, EventEdit edit) {
        return EventSplit.from(Replies.object(Native.splitEvent(ical, edit.toJson().toString())));
    }

    /**
     * Removes the occurrences {@code edit}'s scope names; null if none is left.
     *
     * <p>One becomes an {@code EXDATE}, this and following ends the series
     * before it; null means the entry itself goes. Pure computation.
     */
    public static String removeEvent(String ical, EventEdit edit) {
        String left =
                EventIcal.from(Replies.object(Native.removeEvent(ical, edit.toJson().toString())))
                        .ical();
        return left == null || left.trim().isEmpty() ? null : left.trim();
    }

    /**
     * Pushes an edited object back, guarded by its ETag; returns the new ETag.
     *
     * <p>Guarded: an unguarded PUT silently overwrites another client's edit.
     * The account's {@link Account#address} makes Google and JMAP announce a
     * meeting it organizes to the attendees.
     */
    public String updateEvent(
            Transport transport, Account account, EventTarget target, String ical) {
        return EventEtag.from(
                        Replies.object(
                                Native.updateEvent(
                                        transport,
                                        account.toJson().toString(),
                                        target.calendarUrl(),
                                        target.id(),
                                        ical,
                                        target.etag() == null ? "" : target.etag())))
                .etag();
    }

    /**
     * The object a new calendar entry starts from, one component.
     *
     * <p>Carries the draft's identity, stamp and start, in {@code tzid} with
     * its {@code vtimezone} (both empty for a date or a floating start). Pure
     * computation.
     */
    public static String newEvent(EventDraft draft) {
        return EventIcal.from(
                        Replies.object(
                                Native.newEvent(
                                        draft.component(),
                                        draft.uid(),
                                        draft.stamp(),
                                        draft.start(),
                                        draft.tzid(),
                                        draft.vtimezone())))
                .ical()
                .trim();
    }

    /**
     * Files a new object, guarded on its absence; returns where it landed.
     *
     * <p>The name asked for, or the one Graph gave it, with its ETag. The
     * account's address is as {@link #updateEvent} takes it; the target's ETag
     * is not read.
     */
    public EventRef createEvent(
            Transport transport, Account account, EventTarget target, String ical) {
        JSONObject created =
                Replies.object(
                        Native.createEvent(
                                transport,
                                account.toJson().toString(),
                                target.calendarUrl(),
                                target.id(),
                                ical));
        try {
            return new EventRef(
                    created.getString("id"),
                    created.isNull("etag") ? null : created.getString("etag"));
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable created event: " + error.getMessage());
        }
    }

    /**
     * Moves one object into the calendar at {@code targetUrl}, guarded by its
     * ETag; returns where it landed, its ETag when the server sent one.
     *
     * <p>CalDAV alone moves a member: on another backend, and to a calendar on
     * another server, it fails with a 501, a move the backend cannot do.
     */
    public EventRef moveEvent(
            Transport transport, Account account, EventTarget target, String targetUrl) {
        JSONObject moved =
                Replies.object(
                        Native.moveEvent(
                                transport,
                                account.toJson().toString(),
                                target.calendarUrl(),
                                target.id(),
                                target.etag() == null ? "" : target.etag(),
                                targetUrl));
        try {
            return new EventRef(
                    moved.getString("id"), moved.isNull("etag") ? null : moved.getString("etag"));
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable moved event: " + error.getMessage());
        }
    }

    /**
     * Removes one object from its calendar, guarded by its ETag.
     *
     * <p>Guarded and addressed as {@link #updateEvent} is.
     */
    public void deleteEvent(Transport transport, Account account, EventTarget target) {
        Replies.object(
                Native.deleteEvent(
                        transport,
                        account.toJson().toString(),
                        target.calendarUrl(),
                        target.id(),
                        target.etag() == null ? "" : target.etag()));
    }

    /**
     * The phone view of an object, {@code now} a UTC {@code YYYYMMDDTHHMMSSZ}
     * placing the window a series the provider cannot show is listed over.
     */
    public static PhoneEvent projectEvent(String ical, String now) {
        return PhoneEvent.from(Replies.object(Native.projectEvent(ical, now)));
    }

    /**
     * The object with an edited phone view patched onto it.
     *
     * <p>Only the edit's fields differing from the projection change. An empty
     * object becomes a new one.
     */
    public static String applyEvent(String ical, PhoneEdit edit) {
        // NOTE: the object byte for byte, so not trimmed.
        return EventIcal.from(Replies.object(Native.applyEvent(ical, edit.toJson().toString())))
                .ical();
    }
}
