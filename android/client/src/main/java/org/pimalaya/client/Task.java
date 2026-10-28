package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One to-do as the Tasks tab lists it, read the GTD way.
 *
 * <p>Mirrors rs/calendar/task.rs {@code Task}. Its state is three reserved
 * {@code CATEGORIES} values, {@code inbox}, {@code waiting} and {@code
 * someday} (pimdir GUIDE §17); an open to-do carrying none is a next action,
 * as one another client created is.
 *
 * @param uid the {@code UID}
 * @param summary the {@code SUMMARY}, empty when none
 * @param description the {@code DESCRIPTION}, empty when none
 * @param status the {@code STATUS}, uppercased, empty when none
 * @param list the first state it carries: {@link #INBOX}, {@link #WAITING} or
 *     {@link #SOMEDAY}; else {@link #NEXT}
 * @param states every state it carries, lowercase, in that order
 * @param categories every other {@code CATEGORIES} value, the person's own
 * @param parent the {@code UID} of the task it sits under, null when none
 * @param priority the {@code PRIORITY}, 0 for undefined
 * @param start the {@code DTSTART}, null when none
 * @param due the {@code DUE}, null when none
 * @param completed the {@code COMPLETED}, null when none
 * @param contactName who a Waiting For item waits on, empty when none
 * @param contactAddress their address, empty when none
 * @param source the {@code Message-ID} of the mail it came from, bare, null
 *     when none
 * @param created when it was captured, a raw stamp, empty when unknown
 */
public record Task(
        String uid,
        String summary,
        String description,
        String status,
        String list,
        List<String> states,
        List<String> categories,
        String parent,
        int priority,
        EventTime start,
        EventTime due,
        EventTime completed,
        String contactName,
        String contactAddress,
        String source,
        String created) {
    /** Captured, not clarified yet: {@code CATEGORIES:inbox}. */
    public static final String INBOX = "INBOX";
    /** A next action: an open to-do carrying none of the states. */
    public static final String NEXT = "NEXT";
    /** Someone else owes it: {@code CATEGORIES:waiting}. */
    public static final String WAITING = "WAITING";
    /** Kept for the weekly review: {@code CATEGORIES:someday}. */
    public static final String SOMEDAY = "SOMEDAY";

    /** Whether it is done or dropped, out of every open list. */
    public boolean closed() {
        return "COMPLETED".equals(status) || "CANCELLED".equals(status);
    }

    /**
     * Whether it belongs in {@code list}, its state aside from openness.
     *
     * <p>A to-do another client marked with two states is in both lists, as
     * the views are one query each (pimdir GUIDE §17).
     */
    public boolean in(String list) {
        return NEXT.equals(list)
                ? states.isEmpty()
                : states.contains(list.toLowerCase(Locale.ROOT));
    }

    static Task from(JSONObject task) {
        JSONObject contact = task.optJSONObject("contact");
        return new Task(
                task.optString("uid"),
                task.optString("summary"),
                task.optString("description"),
                task.optString("status"),
                task.optString("list", NEXT),
                strings(task.optJSONArray("states")),
                strings(task.optJSONArray("categories")),
                task.isNull("parent") ? null : task.optString("parent"),
                task.optInt("priority"),
                EventTime.from(task.optJSONObject("start")),
                EventTime.from(task.optJSONObject("due")),
                EventTime.from(task.optJSONObject("completed")),
                contact == null ? "" : contact.optString("name"),
                contact == null ? "" : contact.optString("address"),
                task.isNull("source") ? null : task.optString("source"),
                task.optString("created"));
    }

    private static List<String> strings(JSONArray array) {
        List<String> names = new ArrayList<>();
        if (array != null) {
            names.addAll(Json.strings(array));
        }
        return List.copyOf(names);
    }
}
