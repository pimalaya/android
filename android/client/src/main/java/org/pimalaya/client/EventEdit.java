package org.pimalaya.client;

import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What one edit changes on a calendar component (rs/calendar/edit.rs
 * {@code EventEdit} 43). A null component is an absent key, left alone;
 * an empty text is present and removes its property, which is how the
 * entry page clears a value.
 *
 * @param summary the {@code SUMMARY}
 * @param description the {@code DESCRIPTION}
 * @param location the {@code LOCATION}
 * @param url the {@code URL}
 * @param status the {@code STATUS}
 * @param categories the {@code CATEGORIES}, comma-separated
 * @param priority the {@code PRIORITY}
 * @param start the {@code DTSTART}
 * @param end the {@code DTEND}
 * @param due the {@code DUE}
 * @param completed the {@code COMPLETED}, civil UTC
 * @param percentComplete the {@code PERCENT-COMPLETE}
 * @param stamp now, a UTC {@code YYYYMMDDTHHMMSSZ} stamp for {@code DTSTAMP}
 *     and {@code LAST-MODIFIED}
 * @param scope which occurrences of a series the edit applies to; null
 *     leaves the bridge's default, the whole series
 * @param recurrenceId the occurrence the page was opened on
 * @param recurrenceInstant that occurrence's instant, a UTC stamp
 * @param uid the {@code UID} of the series a split starts
 * @param vtimezone the {@code VTIMEZONE} of a zone the edit writes a date
 *     in, inserted when the object does not define it yet
 */
public record EventEdit(
        String summary,
        String description,
        String location,
        String url,
        String status,
        String categories,
        String priority,
        DateEdit start,
        DateEdit end,
        DateEdit due,
        DateEdit completed,
        String percentComplete,
        String stamp,
        EventScope scope,
        String recurrenceId,
        String recurrenceInstant,
        String uid,
        String vtimezone) {
    /** A text property an edit sets, by the key the bridge's edit object uses. */
    public enum Field {
        SUMMARY("summary"),
        DESCRIPTION("description"),
        LOCATION("location"),
        URL("url"),
        STATUS("status"),
        CATEGORIES("categories"),
        PRIORITY("priority"),
        PERCENT_COMPLETE("percentComplete");

        private final String wire;

        Field(String wire) {
            this.wire = wire;
        }

        /** The key as the bridge spells it. */
        public String wire() {
            return wire;
        }
    }

    /** A date an edit writes, by the key the bridge's edit object uses. */
    public enum DateField {
        START("start"),
        END("end"),
        DUE("due"),
        COMPLETED("completed");

        private final String wire;

        DateField(String wire) {
            this.wire = wire;
        }

        /** The key as the bridge spells it. */
        public String wire() {
            return wire;
        }
    }

    /**
     * The edit of the whole series setting {@code texts} and
     * {@code dates}, an absent one left alone, stamped at {@code stamp}.
     */
    public static EventEdit of(
            Map<Field, String> texts,
            Map<DateField, DateEdit> dates,
            String vtimezone,
            String stamp) {
        return new EventEdit(
                texts.get(Field.SUMMARY),
                texts.get(Field.DESCRIPTION),
                texts.get(Field.LOCATION),
                texts.get(Field.URL),
                texts.get(Field.STATUS),
                texts.get(Field.CATEGORIES),
                texts.get(Field.PRIORITY),
                dates.get(DateField.START),
                dates.get(DateField.END),
                dates.get(DateField.DUE),
                dates.get(DateField.COMPLETED),
                texts.get(Field.PERCENT_COMPLETE),
                stamp,
                null,
                null,
                null,
                null,
                vtimezone);
    }

    /**
     * This edit for the occurrences {@code scope} names, from the
     * occurrence {@code recurrenceId} at {@code recurrenceInstant}, a split
     * starting its series under {@code uid}.
     */
    public EventEdit scoped(
            EventScope scope, String recurrenceId, String recurrenceInstant, String uid) {
        return new EventEdit(
                summary,
                description,
                location,
                url,
                status,
                categories,
                priority,
                start,
                end,
                due,
                completed,
                percentComplete,
                stamp,
                scope,
                recurrenceId,
                recurrenceInstant,
                uid,
                vtimezone);
    }

    JSONObject toJson() {
        try {
            JSONObject edit = new JSONObject();
            edit.put("summary", summary);
            edit.put("description", description);
            edit.put("location", location);
            edit.put("url", url);
            edit.put("status", status);
            edit.put("categories", categories);
            edit.put("priority", priority);
            edit.put("start", start == null ? null : start.toJson());
            edit.put("end", end == null ? null : end.toJson());
            edit.put("due", due == null ? null : due.toJson());
            edit.put("completed", completed == null ? null : completed.toJson());
            edit.put("percentComplete", percentComplete);
            edit.put("stamp", stamp);
            edit.put("scope", scope == null ? null : scope.wire());
            edit.put("recurrenceId", recurrenceId);
            edit.put("recurrenceInstant", recurrenceInstant);
            edit.put("uid", uid);
            edit.put("vtimezone", vtimezone);
            return edit;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable event edit: " + error.getMessage());
        }
    }
}
