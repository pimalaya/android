package org.pimalaya.client;

/** What a calendar conflict is (rs/calendar/conflict.rs {@code EventConflictKind} 87). */
public enum EventConflictKind {
    /** A conflict of one field both sides changed differently. */
    FIELD("field"),
    /** A conflict of a series one side changed and an occurrence the other did. */
    RECURRENCE("recurrence");

    private final String wire;

    EventConflictKind(String wire) {
        this.wire = wire;
    }

    /** The value as the bridge spells it. */
    public String wire() {
        return wire;
    }

    static EventConflictKind of(String wire) {
        for (EventConflictKind kind : values()) {
            if (kind.wire.equals(wire)) {
                return kind;
            }
        }
        throw new PimalayaException(
                "Unreadable bridge reply: unknown event conflict kind '" + wire + "'");
    }
}
