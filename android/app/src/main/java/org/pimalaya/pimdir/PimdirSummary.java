package org.pimalaya.pimdir;

import android.util.Log;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.PimdirSql;

import java.util.Iterator;
import java.util.LinkedHashMap;
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
     * Writes one item's summary row and the addresses beside it.
     *
     * <p>A null summary keeps what is stored (SPEC.md §9.3), or a flag change
     * would blank the row. The summary object is the row, bound by name to the
     * canonical statements.
     */
    public static void write(SQLiteDatabase db, String collection, String linkId, JSONObject summary) {
        write(db, collection, linkId, summary, false);
    }

    /**
     * Writes a summary; {@code fresh} for an item this write just created.
     *
     * <p>A fresh item has no addresses to replace and its insert drew the
     * change stamp, so those two statements are skipped.
     */
    public static void write(
            SQLiteDatabase db, String collection, String linkId, JSONObject summary,
            boolean fresh) {
        if (summary == null) {
            return;
        }

        Iterator<String> kinds = summary.keys();
        String kind = kinds.hasNext() ? kinds.next() : null;
        JSONObject row = kind == null ? null : summary.optJSONObject(kind);
        if (row == null) {
            Log.w("pimalaya", "unreadable summary for " + linkId);
            return;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("collection", collection);
        values.put("link_id", linkId);
        JSONArray addresses = row.optJSONArray("addresses");
        for (Iterator<String> columns = row.keys(); columns.hasNext(); ) {
            String column = columns.next();
            if (!"addresses".equals(column)) {
                values.put(column, value(row.opt(column)));
            }
        }

        exec(db, "UPSERT_" + kind.toUpperCase(Locale.ROOT) + "_SUMMARY", values);
        writeAddresses(db, collection, linkId, addresses, fresh);
        String rule = ruleOf(kind);
        if (rule != null) {
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
     * The automatic-reference rule (STORAGE §14.2) a summary of this kind runs.
     *
     * <p>A mail refers to its sender's card, a card gathers its addresses'
     * mail, a calendar item the mail inviting to it (from the mail's side once
     * its body is stored, {@code MailStore.restateFromBody}).
     */
    private static String ruleOf(String kind) {
        switch (kind) {
            case "mail":
                return "LINK_SENDERS_OF";
            case "contact":
                return "LINK_MAIL_FROM";
            case "event":
            case "task":
                return "LINK_INVITATIONS_TO";
            default:
                return null;
        }
    }

    /** Runs one rule for one item, or for every item with a null link id. */
    public static void link(SQLiteDatabase db, String rule, String linkId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("link_id", linkId);
        exec(db, rule, values);
    }

    /** Runs every rule over the whole store, once, for an older store. */
    static void linkAll(SQLiteDatabase db) {
        for (String rule :
                new String[] {
                    "LINK_SENDERS_OF", "LINK_INVITATIONS_OF", "LINK_MAIL_FROM",
                    "LINK_INVITATIONS_TO"
                }) {
            link(db, rule, null);
        }
    }

    /**
     * Replaces the item's addresses with the ones its summary names.
     *
     * <p>As a set, which is what the canonical statement is for: the derivation
     * yields the whole list, and diffing a handful of rows buys nothing.
     */
    private static void writeAddresses(
            SQLiteDatabase db, String collection, String linkId, JSONArray addresses, boolean fresh) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("collection", collection);
        scope.put("link_id", linkId);
        if (!fresh) {
            exec(db, "REPLACE_ADDRESSES", scope);
        }

        for (int index = 0; addresses != null && index < addresses.length(); index++) {
            JSONObject address = addresses.optJSONObject(index);
            if (address == null) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>(scope);
            values.put("role", address.optString("role"));
            values.put("position", address.optInt("position"));
            values.put("address", address.optString("address"));
            values.put("name", value(address.opt("name")));
            exec(db, "INSERT_ADDRESS", values);
        }
    }

    /** Runs one canonical statement with its named parameters bound. */
    private static void exec(SQLiteDatabase db, String name, Map<String, Object> values) {
        PimdirSql.Bound bound = PimdirSql.bind(name, values);
        db.execSQL(bound.sql, bound.args);
    }

    /**
     * One JSON value as SQLite takes it.
     *
     * <p>Absent is NULL; a list is the JSON text the column holds ({@code
     * in_reply_to}).
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
            putIfSet(row, "message_id", bare(messageId));
            // NOTE: the column is NOT NULL and an envelope carries no
            // reference chain, so the value is the empty list.
            row.put("in_reply_to", new JSONArray());
            row.put("subject", subject == null ? "" : subject);
            putIfSet(row, "sender", canonical(fromAddress));
            putIfSet(row, "sender_name", fromName);
            putIfSet(row, "date", rfc3339(date));
            if (size > 0) {
                row.put("size", size);
            }
            row.put("attachment", attachment);

            JSONArray addresses = new JSONArray();
            address(addresses, "from", 0, canonical(fromAddress), fromName);
            address(addresses, "to", 0, canonical(to), null);
            row.put("addresses", addresses);

            return new JSONObject().put("mail", row);
        } catch (JSONException error) {
            Log.w("pimalaya", "mail summary failed", error);
            return null;
        }
    }

    /** A message's sort key, its date: newest first pages it descending. */
    public static String mailSortKey(String date) {
        return rfc3339(date);
    }

    /** Appends one {@code item_address} row when it names anybody. */
    private static void address(
            JSONArray addresses, String role, int position, String address, String name)
            throws JSONException {
        if (address == null || address.isEmpty()) {
            return;
        }
        JSONObject row = new JSONObject();
        row.put("role", role);
        row.put("position", position);
        row.put("address", address);
        putIfSet(row, "name", name);
        addresses.put(row);
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
