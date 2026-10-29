package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.pimalaya.client.CalendarFixtures.DAY;
import static org.pimalaya.client.CalendarFixtures.FIXED;
import static org.pimalaya.client.CalendarFixtures.JOURNAL;
import static org.pimalaya.client.CalendarFixtures.MOVED;
import static org.pimalaya.client.CalendarFixtures.ROMANCE;
import static org.pimalaya.client.CalendarFixtures.SINGLE;
import static org.pimalaya.client.CalendarFixtures.TODO;
import static org.pimalaya.client.CalendarFixtures.UNDEFINED_ZONE;
import static org.pimalaya.client.CalendarFixtures.UTC;
import static org.pimalaya.client.CalendarFixtures.ZONED;
import static org.pimalaya.client.CalendarFixtures.edited;
import static org.pimalaya.client.CalendarFixtures.editedTwelfth;
import static org.pimalaya.client.CalendarFixtures.following;
import static org.pimalaya.client.CalendarFixtures.ical;
import static org.pimalaya.client.CalendarFixtures.followingEdit;
import static org.pimalaya.client.CalendarFixtures.weekly;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.assertSameJson;
import static org.pimalaya.client.ReplyShape.elements;
import static org.pimalaya.client.ReplyShape.field;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the calendar records on the real bridge, against captured literals.
 *
 * <p>Objects expanded, read, merged and split; every enum value and reply
 * shape. Network verbs refuse a loopback's plain http: literal replies instead
 * (rs/calendar.rs 77, 101, 117, 138).
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarClientTest {
    private static final String ROMANCE_ID = "/example.org/Romance";

    private static EventTime zoned(String time, String tzid, Integer offset) {
        return new EventTime(time, EventTimeKind.ZONED, tzid, offset);
    }

    private static EventTime floating(String time) {
        return new EventTime(time, EventTimeKind.FLOATING, "", null);
    }

    private static EventTime utc(String time) {
        return new EventTime(time, EventTimeKind.UTC, "", null);
    }

    private static EventTime date(String time) {
        return new EventTime(time, EventTimeKind.DATE, "", null);
    }

    private static EventTime romance(String time) {
        return zoned(time, ROMANCE_ID, 3600);
    }

    @Test
    public void aSeriesExpandsToItsOccurrencesInTheZoneItDefines() {
        String raw = Native.expandEvent(ZONED, "20260101", "20260301");
        String first = elements(raw).get(0);
        assertMirrors(Occurrence.class, first);
        assertMirrors(EventTime.class, field(first, "start"));

        assertEquals(
                List.of(
                        new Occurrence(
                                "VEVENT",
                                romance("20260105T090000"),
                                romance("20260105T100000"),
                                romance("20260105T090000"),
                                "Review",
                                "Room 2",
                                false),
                        new Occurrence(
                                "VEVENT",
                                romance("20260113T110000"),
                                romance("20260113T120000"),
                                romance("20260112T090000"),
                                "Review, moved",
                                "",
                                false),
                        new Occurrence(
                                "VEVENT",
                                romance("20260119T090000"),
                                romance("20260119T100000"),
                                romance("20260119T090000"),
                                "Review",
                                "Room 2",
                                false)),
                CalendarClient.expandEvent(ZONED, "20260101", "20260301"));
    }

    @Test
    public void everyTimeKindComesFromTheBridge() {
        assertEquals(
                List.of(
                        new Occurrence(
                                "VEVENT",
                                zoned("20260106T140000", "Europe/Paris", null),
                                zoned("20260106T150000", "Europe/Paris", null),
                                null,
                                "Paris",
                                "",
                                false)),
                CalendarClient.expandEvent(UNDEFINED_ZONE, "20260101", "20260301"));
        assertEquals(
                List.of(
                        new Occurrence(
                                "VEVENT",
                                utc("20260107T080000"),
                                utc("20260107T083000"),
                                null,
                                "Call",
                                "",
                                false)),
                CalendarClient.expandEvent(UTC, "20260101", "20260301"));
        assertEquals(
                List.of(
                        new Occurrence(
                                "VEVENT", date("20260108"), date("20260109"), null, "Holiday", "",
                                true)),
                CalendarClient.expandEvent(DAY, "20260101", "20260301"));
        assertEquals(
                List.of(
                        new Occurrence(
                                "VTODO",
                                utc("20260109T170000"),
                                utc("20260109T170000"),
                                null,
                                "File report",
                                "",
                                false)),
                CalendarClient.expandEvent(TODO, "20260101", "20260301"));
        assertEquals(
                List.of(
                        new Occurrence(
                                "VJOURNAL", date("20260110"), date("20260110"), null, "Notes", "",
                                true)),
                CalendarClient.expandEvent(JOURNAL, "20260101", "20260301"));
        assertEquals(
                floating("20260112T090000"),
                CalendarClient.expandEvent(weekly(MOVED), "20260101", "20260301")
                        .get(1)
                        .recurrenceId());
    }

    @Test
    public void anEventReadsWhole() {
        String raw = Native.readEvent(ZONED, "");
        assertMirrors(EventDetail.class, raw);
        assertMirrors(EventDetail.Attendee.class, elements(field(raw, "attendees")).get(0));

        assertEquals(
                new EventDetail(
                        "VEVENT",
                        "z",
                        "Review",
                        "Weekly review",
                        "Room 2",
                        "https://example.org/review",
                        "CONFIRMED",
                        "WORK, TEAM",
                        romance("20260105T090000"),
                        romance("20260105T100000"),
                        null,
                        null,
                        false,
                        "FREQ=WEEKLY;COUNT=3",
                        "5",
                        "",
                        "ann@example.org",
                        List.of(
                                new EventDetail.Attendee("Bob", "bob@example.org", "ACCEPTED"),
                                new EventDetail.Attendee("", "cy@example.org", "")),
                        "20251201T080000Z",
                        "20251202T080000Z",
                        null),
                CalendarClient.readEvent(ZONED, null));
    }

    @Test
    public void anOverrideReadsWithItsRecurrenceId() {
        assertEquals(
                new EventDetail(
                        "VEVENT",
                        "z",
                        "Review, moved",
                        "",
                        "",
                        "",
                        "",
                        "",
                        romance("20260113T110000"),
                        romance("20260113T120000"),
                        null,
                        null,
                        false,
                        "FREQ=WEEKLY;COUNT=3",
                        "",
                        "",
                        "",
                        List.of(),
                        "",
                        "",
                        romance("20260112T090000")),
                CalendarClient.readEvent(ZONED, "20260112T090000"));
    }

    @Test
    public void aToDoAndAJournalEntryRead() {
        assertEquals(
                new EventDetail(
                        "VTODO",
                        "t",
                        "File report",
                        "",
                        "",
                        "",
                        "COMPLETED",
                        "",
                        null,
                        null,
                        utc("20260109T170000"),
                        utc("20260109T160000"),
                        false,
                        "",
                        "1",
                        "100",
                        "",
                        List.of(),
                        "",
                        "",
                        null),
                CalendarClient.readEvent(TODO, ""));
        assertEquals(
                new EventDetail(
                        "VJOURNAL",
                        "j",
                        "Notes",
                        "Met the team",
                        "",
                        "",
                        "",
                        "",
                        date("20260110"),
                        null,
                        null,
                        null,
                        true,
                        "",
                        "",
                        "",
                        "",
                        List.of(),
                        "",
                        "",
                        null),
                CalendarClient.readEvent(JOURNAL, null));
    }

    @Test
    public void twoTitlesAreAFieldConflict() throws Exception {
        String here = edited(SINGLE, "\"summary\":\"Here\"", "20260201T100000Z");
        String there = edited(SINGLE, "\"summary\":\"There\"", "20260201T110000Z");
        String raw = Native.mergeEvent(SINGLE, here, there);
        assertMirrors(EventMerge.class, raw);
        String conflict = elements(field(raw, "conflicts")).get(0);
        assertMirrors(EventMerge.Conflict.class, conflict);
        assertMirrors(EventMerge.Choice.class, elements(field(conflict, "choices")).get(0));

        assertEquals(
                new EventMerge(
                        new JSONObject(raw).getString("ical"),
                        false,
                        List.of(
                                new EventMerge.Conflict(
                                        0,
                                        EventConflictKind.FIELD,
                                        "VEVENT",
                                        null,
                                        "summary",
                                        null,
                                        List.of(
                                                new EventMerge.Choice(
                                                        EventSide.REMOTE, "There", null, null),
                                                new EventMerge.Choice(
                                                        EventSide.LOCAL, "Here", null, null))))),
                CalendarClient.mergeEvent(SINGLE, here, there));
    }

    @Test
    public void twoZonedStartsAreAskedWithTheirZones() throws Exception {
        String fixed = zonedStart("20260105T080000", "/example.org/Fixed", FIXED, "11");
        String romance = zonedStart("20260105T100000", ROMANCE_ID, ROMANCE, "10");

        assertEquals(
                new EventMerge(
                        new JSONObject(Native.mergeEvent(SINGLE, fixed, romance)).getString("ical"),
                        false,
                        List.of(
                                new EventMerge.Conflict(
                                        0,
                                        EventConflictKind.FIELD,
                                        "VEVENT",
                                        null,
                                        "start",
                                        null,
                                        List.of(
                                                new EventMerge.Choice(
                                                        EventSide.LOCAL,
                                                        "20260105T080000",
                                                        zoned(
                                                                "20260105T080000",
                                                                "/example.org/Fixed",
                                                                10800),
                                                        null),
                                                new EventMerge.Choice(
                                                        EventSide.REMOTE,
                                                        "20260105T100000",
                                                        romance("20260105T100000"),
                                                        null))))),
                CalendarClient.mergeEvent(SINGLE, fixed, romance));
    }

    private static String zonedStart(String time, String tzid, String vtimezone, String hour) {
        return edited(
                SINGLE,
                "\"start\":{\"time\":\""
                        + time
                        + "\",\"kind\":\"zoned\",\"tzid\":\""
                        + tzid
                        + "\"},\"vtimezone\":"
                        + JSONObject.quote(vtimezone),
                "20260201T" + hour + "0000Z");
    }

    @Test
    public void aSpanIsAskedWhole() throws Exception {
        String early =
                edited(SINGLE, "\"start\":{\"time\":\"20260105T094500\"}", "20260201T100000Z");
        String brief = edited(SINGLE, "\"end\":{\"time\":\"20260105T093000\"}", "20260201T110000Z");

        assertEquals(
                new EventMerge(
                        new JSONObject(Native.mergeEvent(SINGLE, early, brief)).getString("ical"),
                        false,
                        List.of(
                                new EventMerge.Conflict(
                                        0,
                                        EventConflictKind.FIELD,
                                        "VEVENT",
                                        null,
                                        "when",
                                        null,
                                        List.of(
                                                new EventMerge.Choice(
                                                        EventSide.REMOTE,
                                                        "",
                                                        floating("20260105T090000"),
                                                        floating("20260105T093000")),
                                                new EventMerge.Choice(
                                                        EventSide.LOCAL,
                                                        "",
                                                        floating("20260105T094500"),
                                                        floating("20260105T100000")))))),
                CalendarClient.mergeEvent(SINGLE, early, brief));

        // NOTE: without a base, an absent one reaches the bridge empty.
        assertEquals(
                new EventMerge(
                        new JSONObject(Native.mergeEvent("", early, brief)).getString("ical"),
                        false,
                        List.of(
                                new EventMerge.Conflict(
                                        0,
                                        EventConflictKind.FIELD,
                                        "VEVENT",
                                        null,
                                        "start",
                                        null,
                                        List.of(
                                                new EventMerge.Choice(
                                                        EventSide.REMOTE,
                                                        "20260105T090000",
                                                        floating("20260105T090000"),
                                                        null),
                                                new EventMerge.Choice(
                                                        EventSide.LOCAL,
                                                        "20260105T094500",
                                                        floating("20260105T094500"),
                                                        null))),
                                new EventMerge.Conflict(
                                        1,
                                        EventConflictKind.FIELD,
                                        "VEVENT",
                                        null,
                                        "end",
                                        null,
                                        List.of(
                                                new EventMerge.Choice(
                                                        EventSide.REMOTE,
                                                        "20260105T093000",
                                                        floating("20260105T093000"),
                                                        null),
                                                new EventMerge.Choice(
                                                        EventSide.LOCAL,
                                                        "20260105T100000",
                                                        floating("20260105T100000"),
                                                        null))))),
                CalendarClient.mergeEvent(null, early, brief));
    }

    @Test
    public void aRuleAgainstAnOccurrenceIsARecurrenceConflictOfEitherSide() throws Exception {
        String base = weekly(MOVED);
        String ruled =
                base.replace("RRULE:FREQ=WEEKLY;COUNT=6", "RRULE:FREQ=WEEKLY;COUNT=3")
                        .replaceFirst("DTSTAMP:20260101T000000Z", "DTSTAMP:20260201T090000Z");
        String twelfth = editedTwelfth(base, "\"summary\":\"There\"", "20260201T100000Z");
        String edited =
                "BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20260112T090000\r\n"
                        + "DTSTART:20260113T090000\r\nDTEND:20260113T100000\r\nSUMMARY:There\r\n"
                        + "DTSTAMP:20260201T100000Z\r\nLAST-MODIFIED:20260201T100000Z\r\n"
                        + "END:VEVENT\r\n";

        for (EventSide series : EventSide.values()) {
            String local = series == EventSide.LOCAL ? ruled : twelfth;
            String remote = series == EventSide.LOCAL ? twelfth : ruled;
            EventSide other = series == EventSide.LOCAL ? EventSide.REMOTE : EventSide.LOCAL;
            assertEquals(
                    new EventMerge(
                            new JSONObject(Native.mergeEvent(base, local, remote))
                                    .getString("ical"),
                            false,
                            List.of(
                                    new EventMerge.Conflict(
                                            0,
                                            EventConflictKind.RECURRENCE,
                                            "VEVENT",
                                            floating("20260112T090000"),
                                            "occurrence",
                                            series,
                                            List.of(
                                                    new EventMerge.Choice(
                                                            other, edited, null, null),
                                                    new EventMerge.Choice(
                                                            series, MOVED, null, null))))),
                    CalendarClient.mergeEvent(base, local, remote));
        }
    }

    @Test
    public void aCleanMergeIsResolved() throws Exception {
        String here = edited(SINGLE, "\"summary\":\"Here\"", "20260201T100000Z");
        String place = edited(SINGLE, "\"location\":\"There\"", "20260201T110000Z");

        assertEquals(
                new EventMerge(
                        new JSONObject(Native.mergeEvent(SINGLE, here, place)).getString("ical"),
                        true,
                        List.of()),
                CalendarClient.mergeEvent(SINGLE, here, place));
    }

    @Test
    public void picksReachTheBridgeAsTheyDidAsStrings() {
        String here = edited(SINGLE, "\"summary\":\"Here\"", "20260201T100000Z");
        String there = edited(SINGLE, "\"summary\":\"There\"", "20260201T110000Z");
        EventSides sides = new EventSides(SINGLE, here, there);

        assertEquals(
                ical(Native.resolveEvent(SINGLE, here, there, "{\"0\":\"local\"}")),
                CalendarClient.resolveEvent(sides, Map.of(0, EventSide.LOCAL)));
        assertEquals(
                ical(Native.resolveEvent(SINGLE, here, there, "{\"0\":\"remote\"}")),
                CalendarClient.resolveEvent(sides, Map.of(0, EventSide.REMOTE)));
        assertEquals(
                ical(Native.resolveEvent("", here, there, "{}")),
                CalendarClient.resolveEvent(new EventSides(null, here, there), Map.of()));
    }

    /**
     * The text verbs answer {@code {ical}}, or the bridge's error object.
     *
     * <p>A removal leaving nothing answers a null object.
     */
    @Test
    public void textVerbsAnswerTheirObjectOrTheBridgeError() {
        String here = edited(SINGLE, "\"summary\":\"Here\"", "20260201T100000Z");
        String all = following("20260105T090000");
        String renamed = "{\"summary\":\"R\",\"stamp\":\"20260201T100000Z\"}";
        for (String raw :
                List.of(
                        Native.writeEvent(SINGLE, renamed),
                        Native.removeEvent(weekly(""), all),
                        Native.resolveEvent(SINGLE, here, here, "{}"),
                        Native.newEvent("VTODO", "n", "20260201T100000Z", "20260105", "", ""),
                        Native.applyEvent(SINGLE, "{\"x\":1}"))) {
            assertMirrors(EventIcal.class, raw);
        }
        assertNull(CalendarClient.removeEvent(weekly(""), followingEdit("20260105T090000")));

        PimalayaException error =
                assertThrows(
                        PimalayaException.class,
                        () -> CalendarClient.writeEvent("garbage", followingEdit("x")));
        assertEquals("Content line is missing a value separator: garbage", error.getMessage());
        assertNull(error.status);
        error =
                assertThrows(
                        PimalayaException.class,
                        () ->
                                CalendarClient.newEvent(
                                        new EventDraft("VNOPE", "n", "", "", "", "")));
        assertEquals("Cannot create a `VNOPE`", error.getMessage());
    }

    @Test
    public void aSplitCarriesTheNewSeriesUnlessItStartsTheOld() throws Exception {
        String raw = Native.splitEvent(ZONED, following("20260112T090000"));
        assertMirrors(EventSplit.class, raw);
        JSONObject reply = new JSONObject(raw);
        assertEquals(
                new EventSplit(reply.getString("master"), reply.getString("series")),
                CalendarClient.splitEvent(ZONED, followingEdit("20260112T090000")));

        String first = weekly("");
        assertEquals(
                new EventSplit(
                        new JSONObject(Native.splitEvent(first, following("20260105T090000")))
                                .getString("master"),
                        null),
                CalendarClient.splitEvent(first, followingEdit("20260105T090000")));
    }

    @Test
    public void aDraftReachesTheBridgeAsItsSixStrings() {
        EventDraft draft =
                new EventDraft(
                        "VEVENT", "n1", "20260201T100000Z", "20260105T090000", ROMANCE_ID,
                        ROMANCE);
        assertEquals(
                ical(
                        Native.newEvent(
                                "VEVENT", "n1", "20260201T100000Z", "20260105T090000",
                                ROMANCE_ID, ROMANCE)),
                CalendarClient.newEvent(draft));
    }

    @Test
    public void anUnknownEnumValueIsUnreadable() {
        assertThrows(
                PimalayaException.class,
                () ->
                        EventTime.from(
                                new JSONObject(
                                        "{\"time\":\"20260105\",\"kind\":\"lunar\","
                                                + "\"tzid\":\"\",\"offset\":null}")));
        assertThrows(PimalayaException.class, () -> EventSide.of("both"));
        assertThrows(PimalayaException.class, () -> EventConflictKind.of("all"));
    }

    @Test
    public void aCalendarReadsAsTheBridgeListsIt() throws Exception {
        String raw =
                "{\"id\":\"work\",\"name\":\"Work\",\"url\":\"https://dav.example.org/cal/work/\","
                        + "\"description\":\"Team\",\"color\":\"#ff0000\",\"role\":\"default\","
                        + "\"writable\":false,\"vtodo\":false}";
        assertMirrors(Calendar.class, raw);
        assertEquals(
                new Calendar(
                        "work", "Work", "https://dav.example.org/cal/work/", "Team", "#ff0000",
                        Calendar.DEFAULT, false, false),
                Calendar.from(new JSONObject(raw)));

        // NOTE: what the source does not say is no description or colour,
        // no role, writable, and no component set (vtodo null).
        assertEquals(
                new Calendar(
                        "bare", "bare", "https://dav.example.org/cal/bare/", null, null, "", true),
                Calendar.from(
                        new JSONObject(
                                "{\"id\":\"bare\",\"name\":\"bare\","
                                        + "\"url\":\"https://dav.example.org/cal/bare/\","
                                        + "\"description\":null,\"color\":null}")));
    }

    @Test
    public void aDeltaReadsAsTheBridgeListsIt() throws Exception {
        String raw =
                "{\"changed\":[{\"id\":\"a\",\"etag\":\"\\\"e1\\\"\"},"
                        + "{\"id\":\"b\",\"etag\":null}],"
                        + "\"bodies\":[{\"id\":\"a\",\"etag\":\"\\\"e1\\\"\",\"ical\":\"A\"}],"
                        + "\"vanished\":[\"gone\"],\"token\":\"t2\",\"complete\":true}";
        assertMirrors(EventDelta.class, raw);
        assertMirrors(EventRef.class, elements(field(raw, "changed")).get(0));
        assertMirrors(Event.class, elements(field(raw, "bodies")).get(0));
        assertEquals(
                new EventDelta(
                        List.of(new EventRef("a", "\"e1\""), new EventRef("b", null)),
                        List.of(new Event("a", "\"e1\"", "A")),
                        List.of("gone"),
                        "t2",
                        true),
                EventDelta.from(new JSONObject(raw)));

        // NOTE: a round listing names alone sends no bodies at all.
        EventDelta names =
                EventDelta.from(
                        new JSONObject(
                                "{\"changed\":[],\"vanished\":[],\"token\":null,"
                                        + "\"complete\":false}"));
        assertEquals(new EventDelta(List.of(), List.of(), List.of(), null, false), names);
        assertNull(names.token());
    }

    @Test
    public void eventsReadAsAMultigetListsThem() throws Exception {
        assertEquals(
                List.of(new Event("a", "\"e1\"", "A"), new Event("b", null, "B")),
                Event.list(
                        new JSONArray(
                                "[{\"id\":\"a\",\"etag\":\"\\\"e1\\\"\",\"ical\":\"A\"},"
                                        + "{\"id\":\"b\",\"etag\":null,\"ical\":\"B\"}]")));
        assertEquals(List.of(), Event.list(null));
    }

    @Test
    public void thePhoneViewReadsAndWritesEveryKeyTheBridgeSends() throws Exception {
        String ical =
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + ROMANCE
                        + "BEGIN:VEVENT\r\nUID:p\r\nDTSTAMP:20260101T000000Z\r\n"
                        + "DTSTART;TZID=/example.org/Romance:20260105T090000\r\n"
                        + "DTEND;TZID=/example.org/Romance:20260105T100000\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=5\r\nEXRULE:FREQ=MONTHLY;COUNT=1\r\n"
                        + "RDATE:20260108T090000Z\r\nEXDATE;VALUE=DATE:20260112\r\n"
                        + "SUMMARY:S\r\nDESCRIPTION:D\r\nLOCATION:L\r\nURL:https://u\r\n"
                        + "STATUS:TENTATIVE\r\nTRANSP:TRANSPARENT\r\nCLASS:PRIVATE\r\n"
                        + "COLOR:teal\r\nORGANIZER:mailto:o@example.com\r\n"
                        + "ATTENDEE;CN=A;ROLE=CHAIR;CUTYPE=ROOM;PARTSTAT=ACCEPTED:"
                        + "mailto:a@example.com\r\n"
                        + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT5M\r\nEND:VALARM\r\n"
                        + "END:VEVENT\r\nBEGIN:VEVENT\r\nUID:p\r\nDTSTAMP:20260101T000000Z\r\n"
                        + "RECURRENCE-ID;TZID=/example.org/Romance:20260119T090000\r\n"
                        + "DTSTART:20260119T120000\r\nSUMMARY:O\r\n"
                        + "BEGIN:VALARM\r\nACTION:DISPLAY\r\n"
                        + "TRIGGER;VALUE=DATE-TIME:20260119T100000Z\r\nEND:VALARM\r\n"
                        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
        String raw = Native.projectEvent(ical, "20260101T000000Z");

        PhoneEvent view = PhoneEvent.from(new JSONObject(raw));

        // NOTE: a null on the wire is an absent field, which writes no key.
        assertSameJson(withoutNulls(new JSONObject(raw)).toString(), view.toJson().toString());
        PhoneComponent master = view.master();
        assertEquals("PRIVATE", master.classification());
        assertEquals(new PhoneTime("20260105T090000", EventTimeKind.ZONED, ROMANCE_ID, 3600,
                null), master.start());
        assertEquals(new PhoneAlarm(5L, null), master.alarms().get(0));
        assertEquals(
                new PhoneAttendee("a@example.com", "A", "CHAIR", "ROOM", "ACCEPTED"),
                master.attendees().get(0));
        assertEquals(
                new PhoneAlarm(null, "20260119T100000Z"),
                view.overrides().get(0).alarms().get(0));
    }

    private static JSONObject withoutNulls(JSONObject object) throws Exception {
        JSONObject out = new JSONObject();
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = object.get(key);
            if (value != JSONObject.NULL) {
                out.put(key, withoutNulls(value));
            }
        }
        return out;
    }

    private static Object withoutNulls(Object value) throws Exception {
        if (value instanceof JSONObject) {
            return withoutNulls((JSONObject) value);
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            JSONArray out = new JSONArray();
            for (int index = 0; index < array.length(); index++) {
                out.put(withoutNulls(array.get(index)));
            }
            return out;
        }
        return value;
    }
}
