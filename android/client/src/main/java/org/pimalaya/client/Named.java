package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One listed message, named by its meta (pimdir SYNC 4).
 *
 * <p>Mirrors rs/mail/listing.rs {@code Named} 335. A Graph delta or a Gmail
 * round lists some by id and markers alone: no summary, which the driver
 * names before the page reaches the engine.
 *
 * @param flags its IMAP markers
 * @param handle its UID within its mailbox, or the provider's message id
 * @param linkId what the store files it under: the handle as listed, the
 *     bound or pending create's link id once named
 * @param sortKey its summary's sort key, empty when listed unnamed
 * @param summary its summary, opaque (the pimdir persistence format),
 *     read-only by contract; null when listed unnamed
 */
public record Named(
        List<String> flags, String handle, String linkId, String sortKey, JSONObject summary) {
    /** The same message filed under {@code linkId}. */
    public Named linked(String linkId) {
        return new Named(flags, handle, linkId, sortKey, summary);
    }

    /** The message on the engine's wire, no summary or sort key when none. */
    public JSONObject toJson() {
        try {
            JSONObject item =
                    new JSONObject()
                            .put("handle", handle)
                            .put("flags", new JSONArray(flags))
                            .put("linkId", linkId);
            if (summary != null) {
                item.put("summary", summary);
            }
            if (!sortKey.isEmpty()) {
                item.put("sortKey", sortKey);
            }
            return item;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable listed message: " + error.getMessage());
        }
    }

    /**
     * One listed message off the bridge.
     *
     * <p>An absent link id is the handle, as the engine reads it.
     */
    static Named from(JSONObject item) {
        String handle = Json.string(item, "handle");
        return new Named(
                Json.strings(item.optJSONArray("flags")),
                handle,
                item.isNull("linkId") ? handle : item.optString("linkId"),
                item.optString("sortKey"),
                item.optJSONObject("summary"));
    }

    static List<Named> list(JSONArray items) {
        List<Named> list = new ArrayList<>(items == null ? 0 : items.length());
        for (int index = 0; items != null && index < items.length(); index++) {
            list.add(from(Json.object(items, index)));
        }
        return list;
    }
}
