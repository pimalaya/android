package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * One message read whole into a file (rs/mail/session.rs
 * {@code MessageSource} 368): a Gmail one {@code Labelled}, any other
 * backend's {@code Plain}, which carries neither labels nor a reception
 * time.
 *
 * @param id the message's id on its backend
 * @param labels its Gmail label ids, null for any other backend
 * @param path the file it was written to
 * @param received when Gmail received it, in milliseconds since the epoch;
 *     null when unknown or for any other backend
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
