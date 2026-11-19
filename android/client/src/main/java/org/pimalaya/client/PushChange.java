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
 * @param since the account state the listing read the card at, guarding an
 *     update where the backend checks it (JMAP): a card changed since is not
 *     written over
 * @param ifMatch the revision the card was last read at, guarding an update
 *     where the backend checks it (Graph, its changeKey): a contact changed
 *     since is not written over
 */
public record PushChange(
        String ref,
        PushOp op,
        String id,
        String vcard,
        String baseVcard,
        List<String> add,
        List<String> remove,
        String since,
        String ifMatch) {
    /** A change no listing state guards: every op but a guarded update. */
    public PushChange(
            String ref,
            PushOp op,
            String id,
            String vcard,
            String baseVcard,
            List<String> add,
            List<String> remove) {
        this(ref, op, id, vcard, baseVcard, add, remove, null, null);
    }

    JSONObject toJson() {
        try {
            JSONObject change = new JSONObject();
            change.put("ref", ref);
            change.put("op", op.wire());
            change.put("id", id);
            change.put("vcard", vcard);
            change.put("baseVcard", baseVcard);
            change.put("since", since);
            change.put("ifMatch", ifMatch);
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
