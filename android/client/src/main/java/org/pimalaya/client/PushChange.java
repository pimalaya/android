package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One change of a batched push round.
 *
 * <p>Mirrors rs/contact/push.rs {@code PushChange} 13. A null component is
 * an absent key; an empty book list is absent too.
 *
 * @param ref the correlation key the outcome echoes back (the engine handle)
 * @param op what the change does
 * @param id the card id addressed by update, books and destroy
 * @param vcard the full vCard of create and update
 * @param baseVcard the state last synced, trimming update patches to the
 *     fields the edit changed
 * @param add the book ids a books op adds the card to
 * @param remove the book ids a books op removes the card from
 */
public record PushChange(
        String ref,
        PushOp op,
        String id,
        String vcard,
        String baseVcard,
        List<String> add,
        List<String> remove) {
    JSONObject toJson() {
        try {
            JSONObject change = new JSONObject();
            change.put("ref", ref);
            change.put("op", op.wire());
            change.put("id", id);
            change.put("vcard", vcard);
            change.put("baseVcard", baseVcard);
            if (!add.isEmpty()) {
                change.put("add", new JSONArray(add));
            }
            if (!remove.isEmpty()) {
                change.put("remove", new JSONArray(remove));
            }
            return change;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable push change: " + error.getMessage());
        }
    }
}
