package org.pimalaya;

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
 * <p>pimdir fixes both (STORAGE.md Annex A, §9.3): a summary is one typed row
 * in its kind's table with the people the item names in {@code item_address},
 * and a sort key is written, never derived. The store defines the columns, the
 * ordering rule and the paging statements; producing the values and writing
 * them is the writer's, which is what this class is.
 *
 * <p>The derivations are io-pimdir's wherever the body is on hand: a card's
 * summary and sort key come back from {@code indexCard} beside the app's own
 * index, so two writers of one store cannot disagree about a property or a
 * spelling. The one summary built here is a message's, which the mail mirror
 * writes from an IMAP envelope with no body to read, exactly as Annex A allows.
 *
 * <p>The keys are TEXT compared with SQLite's {@code BINARY} collation, so
 * <strong>byte order has to be the intended order</strong>. That is why a
 * timestamp is RFC 3339 in UTC at a fixed width rather than anything friendlier:
 * a local offset would sort {@code +02:00} apart from {@code Z} while naming the
 * same instant. Empty means unknown, which sorts to the end of a newest-first
 * mail listing and to the head of an A-to-Z contact listing, in both cases where
 * an unsummarised item belongs.
 */
final class PimdirSummary {
    /** Media type of a mail item. */
    static final String MAIL = "message/rfc822";

    /** Media type of a contact item. */
    static final String CONTACT = "text/vcard";

    /** Media type of a calendar item. */
    static final String CALENDAR = "text/calendar";

    /** Media type of a file item: bytes, its own type in its summary (Annex A.7). */
    static final String FILE = "application/octet-stream";

    // ---- writing ----------------------------------------------------------

    /**
     * Writes one item's summary row and the addresses beside it.
     *
     * <p>A null summary keeps what is stored, on the same terms as the sort key
     * (SPEC.md §9.3): a write that does not restate a summary is not a write
     * that blanks it, or a flag change would strip the row it touched of
     * everything a listing renders.
     *
     * <p>The statements are the canonical ones, bound by name: the summary
     * object <em>is</em> the row, its keys the columns, so nothing here holds a
     * column list that the next spec revision could leave stale.
     */
    static void write(SQLiteDatabase db, String collection, String linkId, JSONObject summary) {
        write(db, collection, linkId, summary, false);
    }

    /**
     * The summary of an item the same write just created when {@code fresh}:
     * it has no addresses to replace, and its insert drew the change stamp
     * already, so the two statements that exist for a moved summary are left
     * out. A page of new mail runs this once per message.
     */
    static void write(
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

        // The change stamp is drawn by triggers on the item row, which cannot
        // see these tables (§4.5), so a summary that moved on its own asks for
        // one: otherwise a reader following the feed never learns the item is
        // no longer listed the way it was.
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("collection", collection);
        scope.put("link_id", linkId);
        exec(db, "STAMP_ITEM", scope);
    }

    /**
     * The automatic-reference rule (STORAGE section 14.2) a summary of this
     * kind runs once written: a mail refers to its sender's card, a card
     * gathers the mail from its addresses, a calendar item the mail inviting
     * to it. The invitation from the mail's side runs when its body is stored
     * ({@link MailStore#restateFromBody}), the summary holding none before.
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
    static void link(SQLiteDatabase db, String rule, String linkId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("link_id", linkId);
        exec(db, rule, values);
    }

    /**
     * Runs every rule over the whole store, once, for a store written before
     * automatic references.
     */
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
     * One JSON value as SQLite takes it: an absent one is NULL, and a list is
     * the JSON text the column holds ({@code in_reply_to}).
     */
    private static Object value(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        return value instanceof JSONArray ? value.toString() : value;
    }

    // ---- message/rfc822 ---------------------------------------------------

    /**
     * The summary of one message (Annex A.1), built from an envelope.
     *
     * <p>The mail mirror lists a mailbox without fetching it, so this is the
     * `Meta` tier of the same derivation io-pimdir performs on a body, and the
     * two have to agree: a message whose summary differs between tiers reads as
     * two different rows to anything paging the store.
     *
     * <p>{@code sender} is the first From addr-spec and {@code sender_name} its
     * display name; the two are separate because a row shows the name while the
     * avatar beside it is derived from the address, which is the half that
     * survives a rename. Flags are deliberately absent: they are the item's own
     * mutable state, not part of its summary.
     */
    static JSONObject mail(
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
            // Stated rather than left out: the column is NOT NULL and an
            // envelope this app reads carries no reference chain, so the
            // honest value is the empty list rather than a missing one.
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

    /**
     * The sort key of a message: its date, so a mailbox reads newest first by
     * paging the key descending.
     */
    static String mailSortKey(String date) {
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
     * The canonical form of an address (Annex A.6): the addr-spec alone,
     * lowercased whole, without display name, angle brackets or {@code mailto:}.
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

    // ---- text/calendar ----------------------------------------------------

    /**
     * The sort key of a calendar entry: its {@code DTSTART}, normalised like
     * mail's date, which is what lets a date-range read page a calendar through
     * the same statements as a mailbox.
     *
     * <p>Takes the civil {@code YYYYMMDDTHHMMSS} stamp the expander yields
     * (recurrence is expanded on wall-clock time, so an occurrence carries no
     * offset) and widens it to the fixed-width RFC 3339 shape the column wants.
     */
    static String calendarSortKey(String civilStamp) {
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

    // ---- shared -----------------------------------------------------------

    /**
     * An RFC 5322 date as the fixed-width RFC 3339 UTC stamp the column orders
     * on, or empty when it cannot be read.
     *
     * <p>Empty rather than a guess: the spec gives unknown a defined position
     * (the end of a newest-first listing), and inventing an epoch date would put
     * an unreadable message at the start of 1970 instead, which reads as data
     * rather than as absence.
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
     * <p>The inverse of {@link #rfc3339}, and here beside it for that reason: a
     * reader that formatted the key differently from the writer would render the
     * wrong date on every row, and the two are easiest to keep honest when they
     * are the same fixed shape in the same place.
     */
    static long stampOf(String key) {
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
    static String bare(String messageId) {
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
