package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One to-do as the Tasks tab lists it, read the GTD way.
 *
 * <p>Mirrors rs/calendar/task.rs {@code Task}. The list is {@code
 * X-PIMALAYA-GTD}, {@link #NEXT} when the to-do carries none, as one another
 * client created does.
 *
 * @param uid the {@code UID}
 * @param summary the {@code SUMMARY}, empty when none
 * @param description the {@code DESCRIPTION}, empty when none
 * @param status the {@code STATUS}, uppercased, empty when none
 * @param list one of {@link #INBOX}, {@link #NEXT}, {@link #WAITING},
 *     {@link #SOMEDAY}, {@link #PROJECT}, {@link #REFERENCE}
 * @param contexts every {@code CATEGORIES} value, each a context
 * @param parent the {@code UID} of the project, null when none
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
        List<String> contexts,
        String parent,
        int priority,
        EventTime start,
        EventTime due,
        EventTime completed,
        String contactName,
        String contactAddress,
        String source,
        String created) {
    /** Captured, not clarified yet. */
    public static final String INBOX = "INBOX";
    /** A next action: what a to-do with no list is. */
    public static final String NEXT = "NEXT";
    /** Someone else owes it. */
    public static final String WAITING = "WAITING";
    /** Kept for the weekly review. */
    public static final String SOMEDAY = "SOMEDAY";
    /** An outcome needing more than one action. */
    public static final String PROJECT = "PROJECT";
    /** Not actionable, kept to be found again. */
    public static final String REFERENCE = "REFERENCE";

    /** Whether it is done or dropped, out of every open list. */
    public boolean closed() {
        return "COMPLETED".equals(status) || "CANCELLED".equals(status);
    }

    static Task from(JSONObject task) {
        JSONArray contexts = task.optJSONArray("contexts");
        List<String> names = new ArrayList<>();
        if (contexts != null) {
            names.addAll(Json.strings(contexts));
        }
        JSONObject contact = task.optJSONObject("contact");
        return new Task(
                task.optString("uid"),
                task.optString("summary"),
                task.optString("description"),
                task.optString("status"),
                task.optString("list", NEXT),
                List.copyOf(names),
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
}
