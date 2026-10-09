package org.pimalaya.client;

/**
 * A series split at one occurrence: the series ended before it, and the
 * new series carrying the edit from it on.
 */
public final class EventSplit {
    /** The series' object, ended before the occurrence. */
    public final String master;

    /** The new series' object, or null when the occurrence was the first. */
    public final String series;

    public EventSplit(String master, String series) {
        this.master = master;
        this.series = series;
    }
}
