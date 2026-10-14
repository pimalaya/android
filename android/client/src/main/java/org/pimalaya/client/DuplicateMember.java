package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One member of a duplicate review group.
 *
 * <p>Mirrors rs/contact/group.rs {@code DuplicateMember} 118. A null
 * component is an absent key.
 *
 * @param ref the replica reference
 * @param book the addressbook URL
 */
public record DuplicateMember(String ref, String book) {
    JSONObject toJson() {
        try {
            return new JSONObject().put("ref", ref).put("book", book);
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable duplicate member: " + error.getMessage());
        }
    }
}
