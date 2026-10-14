package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The duplicate review's facts of one group.
 *
 * <p>Mirrors rs/contact/group.rs {@code DuplicateGroup} 129.
 *
 * @param key the dismissal key, a frozen format the store persists
 * @param linkable whether Link may be offered: every card in its own book
 */
public record DuplicateGroup(String key, boolean linkable) {
    static DuplicateGroup from(JSONObject reply) {
        return new DuplicateGroup(reply.optString("key"), reply.optBoolean("linkable"));
    }
}
