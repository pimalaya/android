package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Some messages read whole into files, and the ids gone.
 *
 * <p>Mirrors rs/mail/session.rs {@code MessageSources} 358.
 *
 * @param gone the ids of messages deleted since they were listed
 * @param items the messages read, one file each
 */
public record MessageSources(List<String> gone, List<MessageSource> items) {
    static MessageSources from(JSONObject reply) {
        JSONArray read = reply.optJSONArray("items");
        List<MessageSource> items = new ArrayList<>(read == null ? 0 : read.length());
        for (int index = 0; read != null && index < read.length(); index++) {
            JSONObject item = read.optJSONObject(index);
            if (item != null) {
                items.add(MessageSource.from(item));
            }
        }
        return new MessageSources(Json.strings(reply.optJSONArray("gone")), items);
    }
}
