package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One page of a mailbox's listing, answering the engine's enumerate yield.
 *
 * <p>Mirrors rs/mail/listing.rs {@code MailPage} 270 (pimdir SYNC 4, 5): a
 * round's page or a delta, newest first.
 *
 * @param checkpoint the checkpoint, on the page where the source gives one;
 *     null elsewhere
 * @param complete whether the page belongs to a round, else to a delta
 * @param cursor where the round's next page resumes; null on the last
 * @param cursorRejected the source refused the cursor or the checkpoint: the
 *     engine restarts the round
 * @param items the members listed
 * @param last whether the page closes its listing
 * @param vanished the handles a delta reports gone
 */
public record MailPage(
        String checkpoint,
        boolean complete,
        String cursor,
        boolean cursorRejected,
        List<Named> items,
        boolean last,
        List<String> vanished) {
    /** The same page listing {@code items} instead. */
    public MailPage with(List<Named> items) {
        return new MailPage(checkpoint, complete, cursor, cursorRejected, items, last, vanished);
    }

    /** The page on the engine's wire, absent fields left out. */
    public JSONObject toJson() {
        try {
            JSONArray members = new JSONArray();
            for (Named item : items) {
                members.put(item.toJson());
            }
            JSONObject page =
                    new JSONObject()
                            .put("items", members)
                            .put("vanished", new JSONArray(vanished))
                            .put("complete", complete)
                            .put("last", last);
            if (cursorRejected) {
                page.put("cursorRejected", true);
            }
            if (cursor != null) {
                page.put("cursor", cursor);
            }
            if (checkpoint != null) {
                page.put("checkpoint", checkpoint);
            }
            return page;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable mail page: " + error.getMessage());
        }
    }

    /**
     * One page off the bridge.
     *
     * <p>Absent fields read as the engine reads them: a page without
     * {@code last} closes its listing.
     */
    static MailPage from(JSONObject page) {
        return new MailPage(
                Json.optString(page, "checkpoint"),
                page.optBoolean("complete"),
                Json.optString(page, "cursor"),
                page.optBoolean("cursorRejected"),
                Named.list(page.optJSONArray("items")),
                page.optBoolean("last", true),
                Json.strings(page.optJSONArray("vanished")));
    }
}
