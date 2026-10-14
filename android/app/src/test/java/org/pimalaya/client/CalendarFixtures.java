package org.pimalaya.client;

/**
 * Crafted calendar objects the calendar pins run through the real bridge,
 * one per shape a reply can take: each time kind, a zone the object
 * defines and one it does not, a series with an override, a to-do, a
 * journal entry, and the edits two sides of a conflict make.
 */
final class CalendarFixtures {
    private CalendarFixtures() {}

    /** A zone the object defines, under a name no database knows. */
    static final String ROMANCE =
            "BEGIN:VTIMEZONE\r\nTZID:/example.org/Romance\r\n"
                    + "BEGIN:DAYLIGHT\r\nDTSTART:19810329T020000\r\nTZOFFSETFROM:+0100\r\n"
                    + "TZOFFSETTO:+0200\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"
                    + "END:DAYLIGHT\r\n"
                    + "BEGIN:STANDARD\r\nDTSTART:19961027T030000\r\nTZOFFSETFROM:+0200\r\n"
                    + "TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
                    + "END:STANDARD\r\n"
                    + "END:VTIMEZONE\r\n";

    /** A fixed zone the object defines. */
    static final String FIXED =
            "BEGIN:VTIMEZONE\r\nTZID:/example.org/Fixed\r\n"
                    + "BEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0300\r\n"
                    + "TZOFFSETTO:+0300\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n";

    static String object(String body) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n"
                + body
                + "END:VCALENDAR\r\n";
    }

    /** A floating entry that does not recur. */
    static final String SINGLE =
            object(
                    "BEGIN:VEVENT\r\nUID:e\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n"
                            + "SUMMARY:Standup\r\nLOCATION:Room 1\r\nEND:VEVENT\r\n");

    /** The second Monday of {@link #weekly} moved to the Tuesday. */
    static final String MOVED =
            "BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260112T090000\r\n"
                    + "DTSTAMP:20260101T000000Z\r\nDTSTART:20260113T090000\r\n"
                    + "DTEND:20260113T100000\r\n"
                    + "SUMMARY:Standup, moved\r\nEND:VEVENT\r\n";

    /** A floating weekly series of six Mondays from 5 January 2026. */
    static String weekly(String overrides) {
        return object(
                "BEGIN:VEVENT\r\nUID:s\r\nDTSTAMP:20260101T000000Z\r\n"
                        + "DTSTART:20260105T090000\r\nDTEND:20260105T100000\r\nSUMMARY:Standup\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=6\r\nEND:VEVENT\r\n"
                        + overrides);
    }

    /**
     * A zoned series in a zone the object defines (an offset), three
     * occurrences, the second moved, with everything the page reads.
     */
    static final String ZONED =
            object(
                    ROMANCE
                            + "BEGIN:VEVENT\r\nUID:z\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "CREATED:20251201T080000Z\r\nLAST-MODIFIED:20251202T080000Z\r\n"
                            + "DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"
                            + "DTEND;TZID=/example.org/Romance:20260105T100000\r\n"
                            + "RRULE:FREQ=WEEKLY;COUNT=3\r\nSUMMARY:Review\r\n"
                            + "DESCRIPTION:Weekly review\r\nLOCATION:Room 2\r\n"
                            + "URL:https://example.org/review\r\nSTATUS:CONFIRMED\r\n"
                            + "CATEGORIES:WORK,TEAM\r\nPRIORITY:5\r\n"
                            + "ORGANIZER;CN=Ann:mailto:ann@example.org\r\n"
                            + "ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:bob@example.org\r\n"
                            + "ATTENDEE:mailto:cy@example.org\r\n"
                            + "END:VEVENT\r\n"
                            + "BEGIN:VEVENT\r\nUID:z\r\n"
                            + "RECURRENCE-ID;TZID=/example.org/Romance:20260112T090000\r\n"
                            + "DTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;TZID=/example.org/Romance:20260113T110000\r\n"
                            + "DTEND;TZID=/example.org/Romance:20260113T120000\r\n"
                            + "SUMMARY:Review, moved\r\nEND:VEVENT\r\n");

    /** Zoned in a zone the object does not define: no offset. */
    static final String UNDEFINED_ZONE =
            object(
                    "BEGIN:VEVENT\r\nUID:p\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;TZID=Europe/Paris:20260106T140000\r\n"
                            + "DTEND;TZID=Europe/Paris:20260106T150000\r\n"
                            + "SUMMARY:Paris\r\nEND:VEVENT\r\n");

    /** UTC times. */
    static final String UTC =
            object(
                    "BEGIN:VEVENT\r\nUID:u\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART:20260107T080000Z\r\nDTEND:20260107T083000Z\r\n"
                            + "SUMMARY:Call\r\nEND:VEVENT\r\n");

    /** An all-day entry. */
    static final String DAY =
            object(
                    "BEGIN:VEVENT\r\nUID:d\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;VALUE=DATE:20260108\r\nDTEND;VALUE=DATE:20260109\r\n"
                            + "SUMMARY:Holiday\r\nEND:VEVENT\r\n");

    /** A to-do placed by its due date, done. */
    static final String TODO =
            object(
                    "BEGIN:VTODO\r\nUID:t\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DUE:20260109T170000Z\r\nCOMPLETED:20260109T160000Z\r\n"
                            + "PERCENT-COMPLETE:100\r\nPRIORITY:1\r\nSTATUS:COMPLETED\r\n"
                            + "SUMMARY:File report\r\nEND:VTODO\r\n");

    /** A journal entry. */
    static final String JOURNAL =
            object(
                    "BEGIN:VJOURNAL\r\nUID:j\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;VALUE=DATE:20260110\r\nSUMMARY:Notes\r\n"
                            + "DESCRIPTION:Met the team\r\nEND:VJOURNAL\r\n");

    /** {@code ical} with the edit JSON written, stamped at {@code stamp}. */
    static String edited(String ical, String edit, String stamp) {
        return CalendarClient.writeEvent(
                ical, "{" + edit + (edit.isEmpty() ? "" : ",") + "\"stamp\":\"" + stamp + "\"}");
    }

    /** The same, on the occurrence of the 12th alone. */
    static String editedTwelfth(String ical, String edit, String stamp) {
        return edited(
                ical,
                edit + ",\"scope\":\"this\",\"recurrenceId\":\"20260112T090000\"",
                stamp);
    }

    /** A split edit: this and following from the occurrence {@code id}. */
    static String following(String id) {
        return "{\"scope\":\"following\",\"recurrenceId\":\""
                + id
                + "\",\"uid\":\"n\",\"summary\":\"Later\",\"stamp\":\"20260201T100000Z\"}";
    }
}
