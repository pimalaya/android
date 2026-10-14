package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * One group of likely-duplicate cards.
 *
 * <p>Mirrors rs/contact/dedup.rs {@code DuplicateSet} 32.
 *
 * @param reasons the match kinds joining it: {@code email}, {@code phone},
 *     {@code name}
 * @param refs the members' replica references, in input order
 */
public record DuplicateSet(List<String> reasons, List<String> refs) {
    static DuplicateSet from(JSONObject reply) {
        return new DuplicateSet(
                List.copyOf(Json.strings(reply.optJSONArray("reasons"))),
                List.copyOf(Json.strings(reply.optJSONArray("refs"))));
    }
}
