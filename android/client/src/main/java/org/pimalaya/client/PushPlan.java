package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One push change's plan (rs/contact/sync.rs {@code PushPlan} 184).
 *
 * @param action the backend call the change becomes
 * @param postCreateBooks the books a Google create patches its membership
 *     into right after; null otherwise
 */
public record PushPlan(PushAction action, List<String> postCreateBooks) {
    static PushPlan from(JSONObject json) {
        JSONArray books = json.optJSONArray("postCreateBooks");
        return new PushPlan(
                PushAction.of(Json.string(json, "action")),
                books == null ? null : Json.strings(books));
    }
}
