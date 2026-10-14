package org.pimalaya.client;

import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * The union of several cards, the merge form's inputs (rs/contact/merge.rs
 * {@code CardsMerge} 196).
 *
 * @param alternatives every distinct value of every conflicted single
 *     field, by dot path in key order, the prefilled one included
 * @param changed the list sections the cards do not agree on, order aside
 * @param model the union's field model, the merge form prefill
 * @param vcard the union document
 */
public record CardsMerge(
        Map<String, List<String>> alternatives,
        List<String> changed,
        ContactFields model,
        String vcard) {
    static CardsMerge from(JSONObject reply) {
        return new CardsMerge(
                Json.stringLists(reply.optJSONObject("alternatives")),
                List.copyOf(Json.strings(reply.optJSONArray("changed"))),
                ContactFields.from(Json.object(reply, "model")),
                Json.string(reply, "vcard"));
    }
}
