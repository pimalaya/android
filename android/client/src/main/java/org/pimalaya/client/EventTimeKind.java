package org.pimalaya.client;

/**
 * What one date or date-time is relative to (RFC 5545 3.3.4, 3.3.5;
 * rs/calendar/zone.rs {@code EventTimeKind} 30).
 */
public enum EventTimeKind {
    /** A {@code DATE}: a day, in no zone at all. */
    DATE("date"),
    /** A local time with no zone, read in the reader's own. */
    FLOATING("floating"),
    /** A {@code Z}-suffixed UTC time. */
    UTC("utc"),
    /** A local time in the zone its {@code TZID} names. */
    ZONED("zoned");

    private final String wire;

    EventTimeKind(String wire) {
        this.wire = wire;
    }

    /** The value as the bridge spells it. */
    public String wire() {
        return wire;
    }

    /**
     * The kind the bridge spelled {@code wire}; public for the phone view,
     * which carries times as JSON until phase 8.
     */
    public static EventTimeKind of(String wire) {
        for (EventTimeKind kind : values()) {
            if (kind.wire.equals(wire)) {
                return kind;
            }
        }
        throw new PimalayaException("Unreadable bridge reply: unknown time kind '" + wire + "'");
    }
}
