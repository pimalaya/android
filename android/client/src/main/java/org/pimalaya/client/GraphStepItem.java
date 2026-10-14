package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One message of a Microsoft Graph step (rs/mail/session.rs
 * {@code GraphStepItem} 338): the mailbox it was stepped in and, where its
 * body was read, its file.
 *
 * @param handle its Graph id
 * @param mailboxes the one mailbox it was stepped in, by name; an element
 *     is null for a folder the step does not name
 * @param path the file its body was written to, null for a held message
 * @param received when it was received, in milliseconds since the epoch;
 *     null when unknown
 * @param size its size in octets, the file's where its body was read; null
 *     when unknown
 * @param sortKey its sort key, the {@code sentDateTime} it was stepped by
 */
public record GraphStepItem(
        String handle,
        List<String> mailboxes,
        String path,
        Long received,
        Long size,
        String sortKey) {
    static GraphStepItem from(JSONObject item) {
        JSONArray names = item.optJSONArray("mailboxes");
        List<String> mailboxes = new ArrayList<>(names == null ? 0 : names.length());
        for (int index = 0; names != null && index < names.length(); index++) {
            mailboxes.add(names.isNull(index) ? null : names.optString(index));
        }
        return new GraphStepItem(
                Json.string(item, "handle"),
                mailboxes,
                Json.optString(item, "path"),
                item.isNull("received") ? null : item.optLong("received"),
                item.isNull("size") ? null : item.optLong("size"),
                item.optString("sortKey"));
    }

    static List<GraphStepItem> list(JSONArray items) {
        List<GraphStepItem> list = new ArrayList<>(items.length());
        for (int index = 0; index < items.length(); index++) {
            list.add(from(Json.object(items, index)));
        }
        return list;
    }
}
