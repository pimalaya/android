package org.pimalaya.client;

/**
 * The backend call a {@link PushPlan} makes of a change.
 *
 * <p>Mirrors rs/contact/sync.rs 196.
 */
public enum PushAction {
    /** A membership patch on the account-level card. */
    MEMBERSHIP("membership"),
    /** A genuine create. */
    CREATE("create"),
    /** The card's deletion. */
    DELETE("delete");

    private final String wire;

    PushAction(String wire) {
        this.wire = wire;
    }

    /** The value as the bridge spells it. */
    public String wire() {
        return wire;
    }

    static PushAction of(String wire) {
        for (PushAction action : values()) {
            if (action.wire.equals(wire)) {
                return action;
            }
        }
        throw new PimalayaException("Unreadable bridge reply: unknown push action '" + wire + "'");
    }
}
