package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The push change a {@link PushPlan} is planned for.
 *
 * <p>Mirrors rs/contact/sync.rs {@code PushPlanFacts} 169. A null component
 * is an absent key.
 *
 * @param op what the change does
 * @param collection the collection URL the change targets
 * @param bookId the book id behind the collection, null when unknown
 * @param origin whether a pending create carries an origin (body on account)
 * @param deleted whether a staged removal deletes the card itself: no other
 *     collection of the account holds it, staged ones included
 * @param bound whether the source already binds the card in another
 *     collection of the account, a staged removal there included
 * @param supersedes whether a pending create supersedes a member of its card
 *     the source binds with a base in another collection: a move it may
 *     deliver by moving that member
 */
public record PushPlanFacts(
        Op op,
        String collection,
        String bookId,
        boolean origin,
        boolean deleted,
        boolean bound,
        boolean supersedes) {
    /** The facts of a change superseding no member. */
    public PushPlanFacts(
            Op op,
            String collection,
            String bookId,
            boolean origin,
            boolean deleted,
            boolean bound) {
        this(op, collection, bookId, origin, deleted, bound, false);
    }

    /** The operation of the change (rs/contact/sync.rs {@code PushOp} 194). */
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
            facts.put("bound", bound);
            facts.put("supersedes", supersedes);
            return facts;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable push plan facts: " + error.getMessage());
        }
    }
}
