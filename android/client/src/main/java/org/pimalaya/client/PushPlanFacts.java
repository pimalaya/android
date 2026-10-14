package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The push change a {@link PushPlan} is planned for.
 *
 * <p>Mirrors rs/contact/sync.rs {@code PushPlanFacts} 153. A null component
 * is an absent key.
 *
 * @param op what the change does
 * @param collection the collection URL the change targets
 * @param bookId the book id behind the collection, null when unknown
 * @param origin whether a pending create carries an origin (body on account)
 * @param deleted whether a staged removal deletes the card itself
 */
public record PushPlanFacts(
        Op op, String collection, String bookId, boolean origin, boolean deleted) {
    /** The operation of the change (rs/contact/sync.rs {@code PushOp} 171). */
    public enum Op {
        /** A pending create. */
        ADD("add"),
        /** A staged removal. */
        REMOVE("remove");

        private final String wire;

        Op(String wire) {
            this.wire = wire;
        }

        /** The value as the bridge spells it. */
        public String wire() {
            return wire;
        }
    }

    JSONObject toJson() {
        try {
            JSONObject facts = new JSONObject();
            facts.put("op", op.wire());
            facts.put("collection", collection);
            facts.put("bookId", bookId);
            facts.put("origin", origin);
            facts.put("deleted", deleted);
            return facts;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable push plan facts: " + error.getMessage());
        }
    }
}
