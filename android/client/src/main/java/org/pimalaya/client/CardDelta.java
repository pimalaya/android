package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Incremental changes of one collection since a sync cursor.
 *
 * <p>Mirrors rs/contact.rs {@code CardDelta} 89.
 *
 * @param changed cards created or updated since the cursor, spine-only or
 *     full per backend
 * @param vanished resource names removed since the cursor
 * @param token the next checkpoint cursor, null when the server sent none
 * @param complete true when the round listed the whole member set: an
 *     initial round, or an expired cursor the bridge re-ran as one
 */
public record CardDelta(
        List<Card> changed, List<String> vanished, String token, boolean complete) {
    static CardDelta from(JSONObject parsed) {
        JSONArray rows = parsed.optJSONArray("changed");
        List<Card> changed = new ArrayList<>(rows == null ? 0 : rows.length());
        for (int index = 0; rows != null && index < rows.length(); index++) {
            changed.add(Card.from(Json.object(rows, index)));
        }

        JSONArray gone = parsed.optJSONArray("vanished");
        List<String> vanished = new ArrayList<>(gone == null ? 0 : gone.length());
        for (int index = 0; gone != null && index < gone.length(); index++) {
            vanished.add(gone.optString(index));
        }

        return new CardDelta(
                changed,
                vanished,
                Json.optString(parsed, "token"),
                parsed.optBoolean("complete"));
    }
}
