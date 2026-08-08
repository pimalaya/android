package org.pimalaya;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The item summaries and sort keys this app writes into a pimdir store.
 *
 * <p>pimdir keeps {@code meta} opaque and {@code sort_key} written-never-derived
 * (SPEC.md §9.3, §13): the store defines the column, the ordering rule and the
 * paging statements, while what a summary contains and what a key means are the
 * writer's. So this class is the app's half of that contract, in one place
 * because three domains have to agree on it and because a key that differs
 * between two writers re-sorts the same item when it moves between them.
 *
 * <p>The keys are TEXT compared with SQLite's {@code BINARY} collation, so
 * <strong>byte order has to be the intended order</strong>. That is why a
 * timestamp is RFC 3339 in UTC at a fixed width rather than anything friendlier:
 * a local offset would sort {@code +02:00} apart from {@code Z} while naming the
 * same instant. Empty means unknown, which sorts to the end of a newest-first
 * mail listing and to the head of an A-to-Z contact listing, in both cases where
 * an unsummarised item belongs.
 */
final class PimdirMeta {
    /** The convention version every summary carries. */
    private static final int V = 1;

    /** Media type of a mail item. */
    static final String MAIL = "message/rfc822";

    /** Media type of a contact item. */
    static final String CONTACT = "text/vcard";

    /** Media type of a calendar item. */
    static final String CALENDAR = "text/calendar";

    // ---- message/rfc822 ---------------------------------------------------

    /**
     * The summary of one message, per SPEC.md §13. Flags are deliberately absent:
     * they are the item's own mutable state, not part of its summary.
     */
    static String mail(
            String messageId, String subject, String from, String to, String date, long size) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("v", V);
            putIfSet(meta, "message_id", bare(messageId));
            meta.put("subject", subject == null ? "" : subject);
            putIfSet(meta, "from", from);
            putIfSet(meta, "to", to);
            putIfSet(meta, "date", rfc3339(date));
            if (size > 0) {
                meta.put("size", size);
            }
            return meta.toString();
        } catch (JSONException error) {
            // A summary is a projection; failing to build one must not fail the
            // sync that produced the item.
            Log.w("pimalaya", "mail meta failed", error);
            return "";
        }
    }

    /**
     * The sort key of a message: its {@code Date}, normalised so byte order is
     * chronological order. Read descending for the usual newest-first listing.
     *
     * <p>Both derivations must agree. The summary path formats the envelope's
     * date and a full fetch formats the parsed body's, and a key that differed
     * between them would re-sort the same message the moment it was hydrated,
     * which is why both go through this one method.
     */
    static String mailSortKey(String date) {
        return rfc3339(date);
    }

    // ---- text/vcard -------------------------------------------------------

    /**
     * The summary of one contact, per SPEC.md §13, widened by the four fields
     * this app's list rows need.
     *
     * <p>{@code v}, {@code uid}, {@code fn}, {@code emails} and {@code size} are
     * the convention every pimdir reader understands. {@code phone},
     * {@code info} and {@code hash} are additions, and they are here rather than
     * in a table of the app's own because the alternative is parsing a vCard per
     * row at render time, which is the cost the summary exists to remove. An
     * unknown field is ignorable by construction, so a reader that has never
     * heard of them loses nothing.
     *
     * <p>{@code hash} is the <em>normalised</em> content hash from the card
     * index, not the object hash: the merged view compares replicas of one
     * contact, and two servers that agree on a card while ordering its
     * properties differently must not read as divergent.
     *
     * <p>Takes the index {@code Cards.indexCard} produces, so the summary and
     * the list row are derived once, together, from the same projection.
     */
    static String contact(JSONObject index, long size) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("v", V);
            putIfSet(meta, "uid", index.optString("uid"));
            meta.put("fn", index.optString("name"));
            JSONArray emails = index.optJSONArray("emails");
            if (emails != null && emails.length() > 0) {
                meta.put("emails", emails);
            }
            if (size > 0) {
                meta.put("size", size);
            }
            putIfSet(meta, "phone", index.optString("phone"));
            putIfSet(meta, "info", index.optString("info"));
            putIfSet(meta, "hash", index.optString("hash"));
            return meta.toString();
        } catch (JSONException error) {
            Log.w("pimalaya", "contact meta failed", error);
            return "";
        }
    }

    /**
     * The sort key of a contact: its display name, normalised for ordering
     * rather than for display, so two writers that disagree about casing do not
     * interleave {@code alice} and {@code Alice} in one address book.
     *
     * <p>Casefolded in {@link Locale#ROOT}: a device in a Turkish locale would
     * otherwise lower-case {@code I} to a dotless {@code ı} and file that
     * contact somewhere no other device agrees with.
     */
    static String contactSortKey(String displayName) {
        if (displayName == null) {
            return "";
        }
        return displayName.trim().toLowerCase(Locale.ROOT);
    }

    // ---- text/calendar ----------------------------------------------------

    /**
     * The summary of one calendar entry.
     *
     * <p>SPEC.md §13 leaves {@code text/calendar} to define its own {@code v: 1}
     * convention when first written, and says the sort key will be
     * {@code DTSTART} normalised exactly as mail's {@code Date} is. This is that
     * convention: the fields an agenda row renders, and nothing more, so a
     * listing never parses an iCalendar body.
     */
    static String calendar(String uid, String summary, String location, String start,
            String end, boolean allDay) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("v", V);
            putIfSet(meta, "uid", uid);
            meta.put("summary", summary == null ? "" : summary);
            putIfSet(meta, "location", location);
            putIfSet(meta, "start", start);
            putIfSet(meta, "end", end);
            if (allDay) {
                meta.put("all_day", true);
            }
            return meta.toString();
        } catch (JSONException error) {
            Log.w("pimalaya", "calendar meta failed", error);
            return "";
        }
    }

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

    private static void putIfSet(JSONObject meta, String key, String value) throws JSONException {
        if (value != null && !value.isEmpty()) {
            meta.put(key, value);
        }
    }

    private PimdirMeta() {}
}
