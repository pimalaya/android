package org.pimalaya.client;

import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The facts a 412-rejected push's retry is decided over
 * (rs/contact/sync.rs {@code RetryFacts} 36). A null component is an
 * absent key.
 *
 * @param listed the last enumerate's ETag per handle, a null ETag sent as
 *     JSON null; null when nothing was listed
 * @param complete whether the last enumerate listed the whole collection
 * @param handle the handle of the rejected push
 * @param ifMatch the staged base revision the push was guarded with, or null
 */
public record RetryFacts(
        Map<String, String> listed, boolean complete, String handle, String ifMatch) {
    JSONObject toJson() {
        try {
            JSONObject facts = new JSONObject();
            if (listed != null) {
                JSONObject etags = new JSONObject();
                for (Map.Entry<String, String> etag : listed.entrySet()) {
                    etags.put(
                            etag.getKey(),
                            etag.getValue() == null ? JSONObject.NULL : etag.getValue());
                }
                facts.put("listed", etags);
            }
            facts.put("complete", complete);
            facts.put("handle", handle);
            facts.put("ifMatch", ifMatch);
            return facts;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable retry facts: " + error.getMessage());
        }
    }
}
