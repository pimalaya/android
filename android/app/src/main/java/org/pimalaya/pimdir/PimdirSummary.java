package org.pimalaya.pimdir;

import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.PimdirSql;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The item summaries and sort keys this app writes into a pimdir store.
 *
 * <p>STORAGE.md Annex A, §9.3; only a mail's summary is built here. Keys
 * compare as BINARY TEXT: times are fixed-width RFC 3339 UTC, empty (unknown)
 * sorts last in mail, first in contacts.
 */
public final class PimdirSummary {
    /** Media type of a mail item. */
    public static final String MAIL = "message/rfc822";

    /** Media type of a contact item. */
    public static final String CONTACT = "text/vcard";

    /** Media type of a calendar item. */
    public static final String CALENDAR = "text/calendar";

    /** Media type of a file item; its own type is in its summary (A.7). */
    public static final String FILE = "application/octet-stream";

    /**
     * Writes one item's summary row and the values beside it.
     *
     * <p>A null summary keeps what is stored (SPEC.md §9.3), or a flag change
     * would blank the row. The summary object is the row (STORAGE Annex A),
     * bound by name to {@code upsert_summary}; its {@code values} are the
     * {@code summary_value} rows.
     */
    public static void write(SQLiteDatabase db, String collection, String linkId, JSONObject summary) {
        write(db, collection, linkId, summary, false);
    }

    /**
     * Writes a summary; {@code fresh} for an item this write just created.
     *
     * <p>A fresh item has no values to replace and its insert drew the change
     * stamp, so those two statements are skipped.
     */
    public static void write(
            SQLiteDatabase db, String collection, String linkId, JSONObject summary,
            boolean fresh) {
        if (summary == null) {
            return;
        }
        String component = summary.optString("component", null);
        if (component == null) {
            Log.w("pimalaya", "unreadable summary for " + linkId);
            return;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("collection", collection);
        values.put("link_id", linkId);
        for (Iterator<String> columns = summary.keys(); columns.hasNext(); ) {
            String column = columns.next();
            if (!"values".equals(column)) {
                values.put(column, value(summary.opt(column)));
            }
        }
        exec(db, "UPSERT_SUMMARY", values);
        writeValues(db, collection, linkId, summary.optJSONArray("values"), fresh);
        for (String rule : rulesOf(component)) {
            link(db, rule, linkId);
        }
        if (fresh) {
            return;
        }

        // NOTE: item-row triggers draw the change stamp and cannot see these
        // tables (§4.5): a summary moved alone asks for one, or a feed reader
        // never learns the item is listed differently.
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("collection", collection);
        scope.put("link_id", linkId);
        exec(db, "STAMP_ITEM", scope);
    }

    /**
     * The automatic-reference rules (STORAGE §14.2) a summary of its kind runs.
     *
     * <p>A mail refers to its sender's card, a card gathers its addresses'
     * mail, a calendar item the mail inviting to it (from the mail's side once
     * its body is stored, {@code MailStore.restateFromBody}), a task the mail
     * its {@code mid} names, whichever is written last.
     */
    private static List<String> rulesOf(String component) {
        switch (component) {
            case "mail":
                return List.of("LINK_SENDERS_OF", "LINK_SOURCES_TO");
            case "contact":
                return List.of("LINK_MAIL_FROM");
            case "event":
                return List.of("LINK_INVITATIONS_TO");
            case "task":
                return List.of("LINK_INVITATIONS_TO", "LINK_SOURCES_OF");
            default:
                return List.of();
        }
    }

    /** Runs one rule for one item, or for every item with a null link id. */
    public static void link(SQLiteDatabase db, String rule, String linkId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("link_id", linkId);
        exec(db, rule, values);
    }

    /**
     * Replaces the item's values with the ones its summary names: its
     * people, a message's In-Reply-To ids, a task's categories.
     *
     * <p>As a set, which is what the canonical statement is for: the derivation
     * yields every list whole, and diffing a handful of rows buys nothing.
     */
    private static void writeValues(
            SQLiteDatabase db, String collection, String linkId, JSONArray rows, boolean fresh) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("collection", collection);
        scope.put("link_id", linkId);
        if (!fresh) {
            Map<String, Object> replace = new LinkedHashMap<>(scope);
            replace.put("lists", 1);
            exec(db, "REPLACE_VALUES", replace);
        }

        for (int index = 0; rows != null && index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>(scope);
            values.put("role", row.optString("role"));
            values.put("position", row.optInt("position"));
            values.put("value", row.optString("value"));
            values.put("name", value(row.opt("name")));
            exec(db, "INSERT_VALUE", values);
        }
    }

    /** Runs one canonical statement with its named parameters bound. */
    private static void exec(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        PimdirDb.changed(db, bound);
    }

    /**
     * What a mail summary states of the fields an identity is checked against
     * (STORAGE §9): its {@code subject}, {@code date} and {@code sender}, and
     * its {@code size}, each null when absent; null for a summary of another
     * kind or none.
     */
    public static Map<String, Object> witnesses(JSONObject summary) {
        JSONObject mail = mailOf(summary);
        if (mail == null) {
            return null;
        }
        Map<String, Object> witnesses = new LinkedHashMap<>();
        witnesses.put("subject", value(mail.opt("title")));
        for (String witness : new String[] {"date", "sender", "size"}) {
            witnesses.put(witness, value(mail.opt(witness)));
        }
        return witnesses;
    }

    /** A summary's row when it is a message's (Annex A.1), else null. */
    public static JSONObject mailOf(JSONObject summary) {
        return summary != null && "mail".equals(summary.optString("component"))
                ? summary
                : null;
    }

    /** A message summary's subject, empty when it states none. */
    public static String subjectOf(JSONObject mail) {
        return mail == null || mail.isNull("title") ? "" : mail.optString("title");
    }

    /** A message summary's bare {@code Message-ID}, null when it states none. */
    public static String messageIdOf(JSONObject mail) {
        return mail == null ? null : mail.optString("uid", null);
    }

    /**
     * One JSON value as SQLite takes it: absent is NULL.
     */
    private static Object value(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        return value instanceof JSONArray ? value.toString() : value;
    }

    /**
     * The summary of one message (Annex A.1), built from an envelope.
     *
     * <p>Must match io-pimdir's body-derived one, or the tiers page as two
     * rows. {@code sender} is the first From addr-spec; flags are not summary.
     */
    public static JSONObject mail(
            String messageId,
            String subject,
            String fromName,
            String fromAddress,
            String to,
            String date,
            long size,
            boolean attachment) {
        try {
            JSONObject row = new JSONObject();
            row.put("component", "mail");
            putIfSet(row, "uid", bare(messageId));
            row.put("title", subject == null ? "" : subject);
            putIfSet(row, "sender", canonical(fromAddress));
            putIfSet(row, "sender_name", fromName);
            putIfSet(row, "date", rfc3339(date));
            if (size > 0) {
                row.put("size", size);
            }
            row.put("attachment", attachment);

            // NOTE: an envelope carries no reference chain, so the message
            // states no In-Reply-To value.
            JSONArray values = new JSONArray();
            address(values, "from", 0, canonical(fromAddress), fromName);
            address(values, "to", 0, canonical(to), null);
            row.put("values", values);

            return row;
        } catch (JSONException error) {
            Log.w("pimalaya", "mail summary failed", error);
            return null;
        }
    }

    /** A message's sort key, its date: newest first pages it descending. */
    public static String mailSortKey(String date) {
        return rfc3339(date);
    }

    /** Appends one address's {@code summary_value} row when it names anybody. */
    private static void address(
            JSONArray values, String role, int position, String address, String name)
            throws JSONException {
        if (address == null || address.isEmpty()) {
            return;
        }
        JSONObject row = new JSONObject();
        row.put("role", role);
        row.put("position", position);
        row.put("value", address);
        putIfSet(row, "name", name);
        values.put(row);
    }

    /**
     * A file's summary row (Annex A.7): its name in its collection, then its
     * media type, size and part, each left out when null, which binds NULL.
     */
    public static JSONObject file(String name, String mediaType, Long size, String part) {
        try {
            JSONObject row = new JSONObject();
            row.put("component", "file");
            row.put("title", name == null ? "" : name);
            putIfSet(row, "media_type", mediaType);
            if (size != null) {
                row.put("size", size.longValue());
            }
            putIfSet(row, "part", part);
            return row;
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * The canonical form of an address (Annex A.6).
     *
     * <p>The addr-spec alone, lowercased, without display name, angle brackets
     * or {@code mailto:}.
     */
    static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String inner = raw.trim();
        int open = inner.indexOf('<');
        int close = inner.lastIndexOf('>');
        if (open >= 0 && close > open) {
            inner = inner.substring(open + 1, close);
        }
        inner = inner.trim();
        if (inner.length() >= 7 && inner.substring(0, 7).equalsIgnoreCase("mailto:")) {
            inner = inner.substring(7);
        }
        return inner.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A calendar entry's sort key: its {@code DTSTART}, normalised like mail's.
     *
     * <p>Takes the expander's civil {@code YYYYMMDDTHHMMSS} stamp (wall-clock,
     * no offset) and widens it to the column's fixed-width RFC 3339 shape.
     */
    public static String calendarSortKey(String civilStamp) {
        if (civilStamp == null || civilStamp.length() < 8) {
            return "";
        }
        String date =
                civilStamp.substring(0, 4)
                        + '-'
                        + civilStamp.substring(4, 6)
                        + '-'
                        + civilStamp.substring(6, 8);
        if (civilStamp.length() < 15) {
            return date + "T00:00:00Z";
        }
        String time =
                civilStamp.substring(9, 11)
                        + ':'
                        + civilStamp.substring(11, 13)
                        + ':'
                        + civilStamp.substring(13, 15);
        return date + 'T' + time + 'Z';
    }

    /**
     * An RFC 5322 date as the column's fixed-width RFC 3339 UTC stamp.
     *
     * <p>Empty when unreadable, never a guess: unknown has a defined position,
     * an epoch date would read as data.
     */
    static String rfc3339(String date) {
        long stamp = MailDate.toStamp(date);
        if (stamp <= 0) {
            return "";
        }
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date(stamp));
    }

    /**
     * The instant behind a stored key, or 0 when it carries none.
     *
     * <p>The inverse of {@link #rfc3339}, kept beside it so reader and writer
     * share one shape.
     */
    public static long stampOf(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            java.util.Date parsed = format.parse(key);
            return parsed == null ? 0 : parsed.getTime();
        } catch (java.text.ParseException error) {
            return 0;
        }
    }

    /** The bare form of a {@code Message-ID}, without its angle brackets. */
    public static String bare(String messageId) {
        if (messageId == null) {
            return null;
        }
        String trimmed = messageId.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length() > 1) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void putIfSet(JSONObject row, String key, String value) throws JSONException {
        if (value != null && !value.isEmpty()) {
            row.put(key, value);
        }
    }

    private PimdirSummary() {}
}
