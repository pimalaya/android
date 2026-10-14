package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One changed card of an {@link AccountSnapshotFacts} delta.
 *
 * <p>Mirrors rs/contact/sync.rs {@code AccountSnapshotCard} 99.
 *
 * @param handle the card's handle, null (absent) until it was fetched
 * @param books the ids of the books listing the card
 * @param known whether the card is held locally
 */
public record SnapshotCard(String handle, List<String> books, boolean known) {
    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("handle", handle)
                .put("books", new JSONArray(books))
                .put("known", known);
    }
}
