package org.pimalaya.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventDraft;
import org.pimalaya.client.EventTime;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.Occurrence;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Where a calendar time is, on the platform's database.
 *
 * <p>A time read in the wrong zone shows hours off with nothing to say so; a
 * wrong {@code VTIMEZONE} moves an entry for every other client. Robolectric
 * because the bridge expands entries and loads once per class loader.
 */
@RunWith(RobolectricTestRunner.class)
public class ZonesTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private static long at(String utc) {
        return Instant.parse(utc).toEpochMilli();
    }

    private static EventTime zoned(String time, String tzid) {
        return new EventTime(time, EventTimeKind.ZONED, tzid, null);
    }

    @Test
    public void aUtcTimeIsReadAsUtcAndShownAtTheReadersHour() {
        EventTime graph = new EventTime("20260706T080000", EventTimeKind.UTC, "", null);
        long instant = Zones.instant(graph, PARIS);

        assertEquals(at("2026-07-06T08:00:00Z"), instant);
        assertEquals(
                LocalDateTime.of(2026, 7, 6, 10, 0),
                Instant.ofEpochMilli(instant).atZone(PARIS).toLocalDateTime());
    }

    @Test
    public void aZonedTimeIsReadInItsZoneWhereverTheReaderIs() {
        assertEquals(
                at("2026-07-06T13:00:00Z"),
                Zones.instant(zoned("20260706T090000", "America/New_York"), PARIS));
        assertEquals(
                "winter, the offset the zone has then",
                at("2026-01-05T14:00:00Z"),
                Zones.instant(zoned("20260105T090000", "America/New_York"), PARIS));
    }

    @Test
    public void aWindowsNameIsReadAsTheZoneCldrMapsItTo() {
        assertEquals(PARIS, Zones.zoneOf("Romance Standard Time"));
        assertEquals(ZoneId.of("America/New_York"), Zones.zoneOf("Eastern Standard Time"));
        assertEquals(
                at("2026-07-06T07:00:00Z"),
                Zones.instant(zoned("20260706T090000", "W. Europe Standard Time"), PARIS));
    }

    @Test
    public void anIanaNameIsReadInAnyCaseTrimmedAndBehindAPath() {
        assertEquals(PARIS, Zones.zoneOf("europe/paris"));
        // NOTE: Outlook writes a trailing space.
        assertEquals(PARIS, Zones.zoneOf("Europe/Paris "));
        assertEquals(
                ZoneId.of("Europe/Vienna"),
                Zones.zoneOf("/freeassociation.sourceforge.net/Tzfile/Europe/Vienna"));
        assertEquals(
                ZoneId.of("America/Argentina/Buenos_Aires"),
                Zones.zoneOf("/mozilla.org/20050126_1/America/Argentina/Buenos_Aires"));
        assertNull(Zones.zoneOf("/example.org/Romance"));
        assertNull(Zones.zoneOf(""));
    }

    @Test
    public void aZoneTheDatabaseCannotNameIsReadAtTheOffsetItsObjectGave() {
        EventTime custom =
                new EventTime("20260706T090000", EventTimeKind.ZONED, "/example.org/Romance", 7200);

        assertEquals(at("2026-07-06T07:00:00Z"), Zones.instant(custom, ZoneOffset.UTC));
    }

    @Test
    public void aFloatingTimeAndADateAreReadOnTheReadersClock() {
        EventTime floating = new EventTime("20260706T090000", EventTimeKind.FLOATING, "", null);
        EventTime day = new EventTime("20260706", EventTimeKind.DATE, "", null);

        assertEquals(at("2026-07-06T07:00:00Z"), Zones.instant(floating, PARIS));
        assertEquals(at("2026-07-06T09:00:00Z"), Zones.instant(floating, ZoneOffset.UTC));
        assertEquals(at("2026-07-05T22:00:00Z"), Zones.instant(day, PARIS));
    }

    @Test
    public void aTimeTheClockSkipsOrRepeatsIsReadTheWayRfc5545Says() {
        // NOTE: RFC 5545 3.3.5: 02:30 on the night Paris springs forward never
        // happens and is read at the offset before the gap; on the night it
        // falls back it happens twice and is the first.
        assertEquals(
                at("2026-03-29T01:30:00Z"),
                Zones.instant(zoned("20260329T023000", "Europe/Paris"), ZoneOffset.UTC));
        assertEquals(
                at("2026-10-25T00:30:00Z"),
                Zones.instant(zoned("20261025T023000", "Europe/Paris"), ZoneOffset.UTC));
    }

    @Test
    public void aPickedMomentIsWrittenInTheZoneItsPropertyWasIn() {
        EventTime york = zoned("20260706T090000", "America/New_York");
        EventTime moved = Zones.at(at("2026-07-06T14:00:00Z"), york, PARIS);

        assertEquals("20260706T100000", moved.time());
        assertEquals(EventTimeKind.ZONED, moved.kind());
        assertEquals("America/New_York", moved.tzid());

        EventTime like = new EventTime("", EventTimeKind.UTC, "", null);
        EventTime utc = Zones.at(at("2026-07-06T14:00:00Z"), like, PARIS);
        assertEquals("20260706T140000", utc.time());
        assertEquals("20260706T140000Z", Zones.utc(at("2026-07-06T14:00:00Z")));
    }

    @Test
    public void aZoneThatKeepsItsClockIsOneOffset() {
        String zone = Zones.vtimezone(ZoneId.of("Asia/Tokyo"), at("2026-07-06T00:00:00Z"));

        assertTrue(zone.startsWith("BEGIN:VTIMEZONE\r\nTZID:Asia/Tokyo\r\n"));
        assertEquals(1, count(zone, "BEGIN:STANDARD"));
        assertFalse(zone.contains("DAYLIGHT"));
        assertTrue(zone.contains("TZOFFSETFROM:+0900\r\nTZOFFSETTO:+0900\r\n"));
        assertFalse(zone.contains("RRULE"));
    }

    @Test
    public void aZoneWithSummerTimeIsItsTwoYearlyRules() {
        String paris = Zones.vtimezone(PARIS, at("2026-07-06T00:00:00Z"));

        assertEquals(1, count(paris, "BEGIN:DAYLIGHT"));
        assertEquals(1, count(paris, "BEGIN:STANDARD"));
        assertTrue(paris.contains(
                "TZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n"));
        assertTrue(paris.contains(
                "TZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\n"
                        + "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"));

        String york = Zones.vtimezone(ZoneId.of("America/New_York"), at("2026-07-06T00:00:00Z"));
        assertTrue(york.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU\r\n"));
        assertTrue(york.contains("RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU\r\n"));
        assertTrue(york.contains("TZOFFSETFROM:-0500\r\nTZOFFSETTO:-0400\r\n"));
    }

    @Test
    public void everyZoneWrittenIsReadBackAtTheOffsetsThePlatformGives() {
        // NOTE: ical-rs reads the definition natively, with no database of its
        // own: times an hour and a day either side of every change over two
        // years, and at noon each month, must land at java.time's offset.
        Instant since = Instant.parse("2026-01-01T00:00:00Z");
        Instant until = Instant.parse("2028-01-01T00:00:00Z");

        for (String id : ZoneId.getAvailableZoneIds()) {
            ZoneId zone = ZoneId.of(id);
            ZoneRules rules = zone.getRules();

            List<Instant> samples = new ArrayList<>();
            for (int month = 0; month < 24; month++) {
                samples.add(since.plus(Duration.ofDays(30L * month + 14).plusHours(12)));
            }
            ZoneOffsetTransition change = rules.nextTransition(since);
            while (change != null && change.getInstant().isBefore(until)) {
                for (long hours : new long[] {-25, -1, 1, 25}) {
                    samples.add(change.getInstant().plus(Duration.ofHours(hours)));
                }
                change = rules.nextTransition(change.getInstant());
            }
            StringBuilder dates = new StringBuilder();
            for (Instant sample : samples) {
                dates.append(dates.length() == 0 ? "" : ",")
                        .append(Zones.stamp(sample.atZone(zone).toLocalDateTime(), false));
            }

            String ical =
                    CalendarClient.newEvent(
                            new EventDraft(
                                    "VEVENT",
                                    "z",
                                    "20260101T000000Z",
                                    "20260101T000000",
                                    id,
                                    Zones.vtimezone(zone, since.toEpochMilli())));
            String series =
                    ical.replace(
                            "DTSTART;TZID=" + id + ":20260101T000000\r\n",
                            "DTSTART;TZID=" + id + ":20260101T000000\r\nRDATE;TZID=" + id + ":"
                                    + dates + "\r\n");

            List<Occurrence> found =
                    CalendarClient.expandEvent(series, "20250101T000000", "20290101T000000");
            assertTrue(id, found.size() > samples.size() / 2);
            for (Occurrence occurrence : found) {
                LocalDateTime local = Zones.civil(occurrence.start().time());
                if (rules.getValidOffsets(local).size() != 1) {
                    continue;
                }
                assertEquals(
                        id + " " + occurrence.start().time(),
                        (Integer) rules.getOffset(local).getTotalSeconds(),
                        occurrence.start().offset());
            }
        }
    }

    @Test
    public void aZoneOnlyItsObjectDefinesIsNamedByTheZoneItsOffsetsMatch() {
        List<EventTime> span =
                List.of(
                        new EventTime("20260706T090000", EventTimeKind.ZONED, "Romance", 7200),
                        new EventTime("20261106T090000", EventTimeKind.ZONED, "Romance", 3600));
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");

        assertEquals(PARIS, Zones.zoneFor(span, PARIS));
        ZoneId matched = Zones.zoneFor(span, tokyo);
        assertTrue(matched.getId(), matched.getId().contains("/"));
        ZoneRules rules = matched.getRules();
        Instant summer = Instant.parse("2026-07-06T07:00:00Z");
        Instant winter = Instant.parse("2026-11-06T08:00:00Z");
        assertEquals(7200, rules.getOffset(summer).getTotalSeconds());
        assertEquals(3600, rules.getOffset(winter).getTotalSeconds());

        List<EventTime> odd =
                List.of(
                        new EventTime(
                                "20260706T090000", EventTimeKind.ZONED, "Odd", 7 * 3600 + 17 * 60));
        assertEquals(tokyo, Zones.zoneFor(odd, tokyo));

        assertEquals(
                ZoneId.of("America/New_York"),
                Zones.zoneFor(List.of(zoned("20260706T090000", "America/New_York")), tokyo));
        assertEquals(
                ZoneOffset.UTC,
                Zones.zoneFor(
                        List.of(new EventTime("20260706T090000", EventTimeKind.UTC, "", null)),
                        tokyo));
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
