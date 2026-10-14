package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * One message read whole into a file.
 *
 * <p>Mirrors rs/mail/session.rs {@code MessageSource} 368: Gmail's
 * {@code Labelled}, any other backend's {@code Plain} (no labels, no time).
 *
 * @param id the message's id on its backend
 * @param labels its Gmail label ids, null for any other backend
 * @param path the file it was written to
 * @param received Gmail's reception time in epoch milliseconds; else null
 * @param size the file's size in octets
 */
public record MessageSource(
        String id, List<String> labels, String path, Long received, long size) {
    static MessageSource from(JSONObject item) {
        return new MessageSource(
                item.optString("id"),
                item.has("labels") ? Json.strings(item.optJSONArray("labels")) : null,
                item.optString("path"),
                item.isNull("received") ? null : item.optLong("received"),
                item.optLong("size"));
    }
}
