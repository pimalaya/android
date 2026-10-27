package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What one edit changes on a to-do, its GTD properties included.
 *
 * <p>Mirrors the to-do half of rs/calendar/edit.rs {@code EventEdit}, sent
 * as the same edit object. A null component is an absent key, left alone; an
 * empty text removes its property.
 *
 * @param summary the {@code SUMMARY}
 * @param description the {@code DESCRIPTION}
 * @param status the {@code STATUS}
 * @param categories the contexts, comma-separated {@code CATEGORIES}
 * @param priority the {@code PRIORITY}
 * @param due the {@code DUE}
 * @param completed the {@code COMPLETED}, civil UTC
 * @param percentComplete the {@code PERCENT-COMPLETE}
 * @param list the {@code X-PIMALAYA-GTD} list; empty or {@link Task#NEXT}
 *     makes it a next action
 * @param parent the {@code UID} of the project; empty detaches it
 * @param contactName who a Waiting For item waits on; with an empty address,
 *     empty removes the {@code CONTACT}
 * @param contactAddress their address
 * @param source the {@code Message-ID} of the mail it came from; empty
 *     removes the link
 * @param stamp now, a UTC {@code YYYYMMDDTHHMMSSZ} stamp
 */
public record TaskEdit(
        String summary,
        String description,
        String status,
        String categories,
        String priority,
        DateEdit due,
        DateEdit completed,
        String percentComplete,
        String list,
        String parent,
        String contactName,
        String contactAddress,
        String source,
        String stamp) {
    /** An edit changing nothing yet, stamped at {@code stamp}. */
    public static TaskEdit at(String stamp) {
        return new TaskEdit(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                stamp);
    }

    /** This edit moving the to-do to {@code list}. */
    public TaskEdit withList(String list) {
        return new TaskEdit(
                summary, description, status, categories, priority, due, completed,
                percentComplete, list, parent, contactName, contactAddress, source, stamp);
    }

    /** This edit setting {@code SUMMARY} and {@code DESCRIPTION}. */
    public TaskEdit withText(String summary, String description) {
        return new TaskEdit(
                summary, description, status, categories, priority, due, completed,
                percentComplete, list, parent, contactName, contactAddress, source, stamp);
    }

    /** This edit setting the contexts, comma-separated. */
    public TaskEdit withContexts(String categories) {
        return new TaskEdit(
                summary, description, status, categories, priority, due, completed,
                percentComplete, list, parent, contactName, contactAddress, source, stamp);
    }

    /** This edit setting the project. */
    public TaskEdit withParent(String parent) {
        return new TaskEdit(
                summary, description, status, categories, priority, due, completed,
                percentComplete, list, parent, contactName, contactAddress, source, stamp);
    }

    /** This edit setting the due date. */
    public TaskEdit withDue(DateEdit due) {
        return new TaskEdit(
                summary, description, status, categories, priority, due, completed,
                percentComplete, list, parent, contactName, contactAddress, source, stamp);
    }

    /** This edit setting the priority. */
    public TaskEdit withPriority(String priority) {
        return new TaskEdit(
                summary, description, status, categories, priority, due, completed,
                percentComplete, list, parent, contactName, contactAddress, source, stamp);
    }

    /** This edit setting who it waits on. */
    public TaskEdit withContact(String name, String address) {
        return new TaskEdit(
                summary, description, status, categories, priority, due, completed,
                percentComplete, list, parent, name, address, source, stamp);
    }

    /** This edit linking the mail it came from. */
    public TaskEdit withSource(String source) {
        return new TaskEdit(
                summary, description, status, categories, priority, due, completed,
                percentComplete, list, parent, contactName, contactAddress, source, stamp);
    }

    /**
     * This edit closing the to-do as done, or reopening it.
     *
     * <p>Done is {@code STATUS:COMPLETED} with its {@code COMPLETED} time and
     * {@code PERCENT-COMPLETE:100} (RFC 5545 3.8.2.1); reopened clears all
     * three.
     */
    public TaskEdit withDone(boolean done) {
        String utc = stamp == null ? "" : stamp.replace("Z", "");
        return new TaskEdit(
                summary,
                description,
                done ? "COMPLETED" : "NEEDS-ACTION",
                categories,
                priority,
                due,
                new DateEdit(done ? utc : "", EventTimeKind.UTC, ""),
                done ? "100" : "",
                list,
                parent,
                contactName,
                contactAddress,
                source,
                stamp);
    }

    /** This edit dropping the to-do, kept but out of every list. */
    public TaskEdit withCancelled() {
        return new TaskEdit(
                summary, description, "CANCELLED", categories, priority, due, completed,
                percentComplete, list, parent, contactName, contactAddress, source, stamp);
    }

    JSONObject toJson() {
        try {
            JSONObject edit = new JSONObject();
            edit.put("summary", summary);
            edit.put("description", description);
            edit.put("status", status);
            edit.put("categories", categories);
            edit.put("priority", priority);
            edit.put("due", due == null ? null : due.toJson());
            edit.put("completed", completed == null ? null : completed.toJson());
            edit.put("percentComplete", percentComplete);
            edit.put(
                    "gtd",
                    list == null ? null : Task.NEXT.equals(list) ? "" : list);
            edit.put("parent", parent);
            if (contactName != null || contactAddress != null) {
                JSONObject contact = new JSONObject();
                contact.put("name", contactName == null ? "" : contactName);
                contact.put("address", contactAddress == null ? "" : contactAddress);
                edit.put("contact", contact);
            }
            edit.put("source", source);
            edit.put("stamp", stamp);
            return edit;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable task edit: " + error.getMessage());
        }
    }
}
