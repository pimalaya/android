package org.pimalaya.client;

/**
 * One rendered instance of an event: what a row in the agenda is.
 *
 * <p>Stamps are civil ({@code YYYYMMDDTHHMMSS}), with no zone and no
 * offset, because RFC 5545 expands recurrence on wall-clock time: an
 * event at 09:00 recurs at 09:00. Comparing them lexicographically is
 * comparing them chronologically, which is what lets the agenda slice a
 * window with plain string bounds.
 */
public final class Occurrence {
    public final String start;
    public final String end;
    public final String summary;
    public final String location;
    public final boolean allDay;

    public Occurrence(String start, String end, String summary, String location, boolean allDay) {
        this.start = start;
        this.end = end;
        this.summary = summary;
        this.location = location;
        this.allDay = allDay;
    }
}
