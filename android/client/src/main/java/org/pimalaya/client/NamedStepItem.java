package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One message of a Gmail or JMAP step, named as a listed one, with its file.
 *
 * <p>Mirrors rs/mail/session.rs {@code NamedStepItem} 311.
 *
 * @param flags its IMAP markers
 * @param handle its provider id
 * @param linkId what the store files it under, the handle
 * @param mailboxes the mailboxes it is a member of, by name
 * @param path the file its body was written to
 * @param received reception time in epoch milliseconds; null when unknown
 * @param size the file's size in octets
 * @param sortKey its summary's sort key, empty when it has none
 * @param summary its summary, opaque (the pimdir persistence format),
 *     read-only by contract; null where it has none
 */
public record NamedStepItem(
        List<String> flags,
        String handle,
        String linkId,
        List<String> mailboxes,
        String path,
        Long received,
        long size,
        String sortKey,
        JSONObject summary) {
    static NamedStepItem from(JSONObject item) {
        return new NamedStepItem(
                Json.strings(item.optJSONArray("flags")),
                Json.string(item, "handle"),
                Json.string(item, "linkId"),
                Json.strings(item.optJSONArray("mailboxes")),
                Json.string(item, "path"),
                item.isNull("received") ? null : item.optLong("received"),
                item.optLong("size"),
                item.optString("sortKey"),
                item.optJSONObject("summary"));
    }

    static List<NamedStepItem> list(JSONArray items) {
        List<NamedStepItem> list = new ArrayList<>(items.length());
        for (int index = 0; index < items.length(); index++) {
            list.add(from(Json.object(items, index)));
        }
        return list;
    }
}
