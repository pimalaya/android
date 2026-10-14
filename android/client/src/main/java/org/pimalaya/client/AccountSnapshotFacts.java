package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The account-wide delta (JMAP, Google) projected onto one book
 * (rs/contact/sync.rs {@code AccountSnapshotFacts} 85).
 *
 * @param bookId the book to project onto, null (absent) when unknown
 * @param complete whether the delta is a complete listing
 * @param changed the changed cards
 * @param vanished the handles the delta reports gone
 */
public record AccountSnapshotFacts(
        String bookId, boolean complete, List<SnapshotCard> changed, List<String> vanished) {
    JSONObject toJson() {
        try {
            JSONArray cards = new JSONArray();
            for (SnapshotCard card : changed) {
                cards.put(card.toJson());
            }
            JSONObject facts = new JSONObject();
            facts.put("bookId", bookId);
            facts.put("complete", complete);
            facts.put("changed", cards);
            facts.put("vanished", new JSONArray(vanished));
            return facts;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable snapshot facts: " + error.getMessage());
        }
    }
}
