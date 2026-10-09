package org.pimalaya.client;

/**
 * One date or date-time of a calendar component, as its property spells
 * it: civil, plus what it is civil in.
 *
 * <p>Civil because RFC 5545 recurs on the wall clock of a start, so that
 * is what the expansion produces; what it is relative to rides along so
 * the app can make it an instant with the platform's time-zone database,
 * which the native library does not carry.
 */
public final class EventTime {
    /** A {@code DATE}: a day, in no zone at all. */
    public static final String DATE = "date";

    /** A local time with no zone, read in the reader's own. */
    public static final String FLOATING = "floating";

    /** A {@code Z}-suffixed UTC time. */
    public static final String UTC = "utc";

    /** A local time in the zone its {@code TZID} names. */
    public static final String ZONED = "zoned";

    /** {@code YYYYMMDD} for a date, {@code YYYYMMDDTHHMMSS} otherwise, never {@code Z}. */
    public final String time;

    /** One of {@link #DATE}, {@link #FLOATING}, {@link #UTC}, {@link #ZONED}. */
    public final String kind;

    /** The {@code TZID}, verbatim; empty unless zoned. */
    public final String tzid;

    /**
     * Seconds east of UTC, as the object's own {@code VTIMEZONE} puts them
     * in force at this time; null when the object does not define the zone.
     */
    public final Integer offset;

    public EventTime(String time, String kind, String tzid, Integer offset) {
        this.time = time;
        this.kind = kind;
        this.tzid = tzid;
        this.offset = offset;
    }

    /** Whether this is a day rather than a moment. */
    public boolean isDate() {
        return DATE.equals(kind);
    }
}
