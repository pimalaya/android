package org.pimalaya.client;

/**
 * Which occurrences of a series an edit applies to (rs/calendar/edit.rs
 * {@code EventScope} 97).
 */
public enum EventScope {
    /** The series itself, which is all an entry that does not recur has. */
    ALL("all"),
    /** The occurrence named, through an override or an {@code EXDATE}. */
    THIS("this"),
    /** The occurrence named and every later one, by splitting the series. */
    FOLLOWING("following");

    private final String wire;

    EventScope(String wire) {
        this.wire = wire;
    }

    /** The value as the bridge spells it. */
    public String wire() {
        return wire;
    }
}
