package org.pimalaya.client;

/** A side of a calendar conflict (rs/calendar/conflict.rs {@code EventSide} 77). */
public enum EventSide {
    /** The body staged here. */
    LOCAL("local"),
    /** The body the source holds. */
    REMOTE("remote");

    private final String wire;

    EventSide(String wire) {
        this.wire = wire;
    }

    /** The value as the bridge spells it. */
    public String wire() {
        return wire;
    }

    static EventSide of(String wire) {
        for (EventSide side : values()) {
            if (side.wire.equals(wire)) {
                return side;
            }
        }
        throw new PimalayaException("Unreadable bridge reply: unknown event side '" + wire + "'");
    }
}
