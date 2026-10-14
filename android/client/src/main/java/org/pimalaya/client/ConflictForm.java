package org.pimalaya.client;

import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * The conflict form's inputs for a both-sides-edited row, shaped as
 * {@link CardsMerge} (rs/contact/merge.rs {@code ConflictForm} 87).
 *
 * @param alternatives the two candidates of every conflicted single field,
 *     by dot path in key order, the winner's first
 * @param changed the changed list sections, always empty: it turns the
 *     form's conflict mode on
 * @param model the merged card's field model, the form prefill
 * @param resolved whether nothing needs the user
 * @param vcard the merged card
 */
public record ConflictForm(
        Map<String, List<String>> alternatives,
        List<String> changed,
        ContactFields model,
        boolean resolved,
        String vcard) {
    static ConflictForm from(JSONObject reply) {
        return new ConflictForm(
                Json.stringLists(reply.optJSONObject("alternatives")),
                List.copyOf(Json.strings(reply.optJSONArray("changed"))),
                ContactFields.from(Json.object(reply, "model")),
                reply.optBoolean("resolved"),
                Json.string(reply, "vcard"));
    }
}
