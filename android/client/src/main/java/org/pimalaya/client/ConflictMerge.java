package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The three-way merge of a conflicted push.
 *
 * <p>Mirrors rs/contact/merge.rs {@code ConflictMerge} 54.
 *
 * @param conflicts the number of same-field collisions the local side won
 * @param vcard the merged card
 */
public record ConflictMerge(int conflicts, String vcard) {
    static ConflictMerge from(JSONObject reply) {
        return new ConflictMerge(Json.integer(reply, "conflicts"), Json.string(reply, "vcard"));
    }
}
