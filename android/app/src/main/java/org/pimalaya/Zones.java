package org.pimalaya;

import org.pimalaya.client.EventTime;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a calendar time is: the instant an {@link EventTime} names, and
 * the {@code VTIMEZONE} a time in a zone owes the object it is written
 * in.
 *
 * <p>The platform's time-zone database answers both, through java.time:
 * Android ships the IANA database and keeps it current, so the native
 * library carries none (docs/pimalaya-android-plan.md). What the
 * database cannot name, the object's own {@code VTIMEZONE} answers on
 * the native side, which hands its offset over with the time.
 */
final class Zones {
    /**
     * Windows zone names, as Exchange and Outlook write them in a
     * {@code TZID}, and the IANA zone each stands for: the default
     * ({@code territory="001"}) mapping of CLDR's {@code windowsZones.xml}
     * (CLDR otherVersion 7e11800, typeVersion 2021a), the table io-msgraph
     * reads Graph's zones with.
     */
    private static final String[][] WINDOWS = {
        {"AUS Central Standard Time", "Australia/Darwin"},
        {"AUS Eastern Standard Time", "Australia/Sydney"},
        {"Afghanistan Standard Time", "Asia/Kabul"},
        {"Alaskan Standard Time", "America/Anchorage"},
        {"Aleutian Standard Time", "America/Adak"},
        {"Altai Standard Time", "Asia/Barnaul"},
        {"Arab Standard Time", "Asia/Riyadh"},
        {"Arabian Standard Time", "Asia/Dubai"},
        {"Arabic Standard Time", "Asia/Baghdad"},
        {"Argentina Standard Time", "America/Buenos_Aires"},
        {"Astrakhan Standard Time", "Europe/Astrakhan"},
        {"Atlantic Standard Time", "America/Halifax"},
        {"Aus Central W. Standard Time", "Australia/Eucla"},
        {"Azerbaijan Standard Time", "Asia/Baku"},
        {"Azores Standard Time", "Atlantic/Azores"},
        {"Bahia Standard Time", "America/Bahia"},
        {"Bangladesh Standard Time", "Asia/Dhaka"},
        {"Belarus Standard Time", "Europe/Minsk"},
        {"Bougainville Standard Time", "Pacific/Bougainville"},
        {"Canada Central Standard Time", "America/Regina"},
        {"Cape Verde Standard Time", "Atlantic/Cape_Verde"},
        {"Caucasus Standard Time", "Asia/Yerevan"},
        {"Cen. Australia Standard Time", "Australia/Adelaide"},
        {"Central America Standard Time", "America/Guatemala"},
        {"Central Asia Standard Time", "Asia/Bishkek"},
        {"Central Brazilian Standard Time", "America/Cuiaba"},
        {"Central Europe Standard Time", "Europe/Budapest"},
        {"Central European Standard Time", "Europe/Warsaw"},
        {"Central Pacific Standard Time", "Pacific/Guadalcanal"},
        {"Central Standard Time", "America/Chicago"},
        {"Central Standard Time (Mexico)", "America/Mexico_City"},
        {"Chatham Islands Standard Time", "Pacific/Chatham"},
        {"China Standard Time", "Asia/Shanghai"},
        {"Cuba Standard Time", "America/Havana"},
        {"Dateline Standard Time", "Etc/GMT+12"},
        {"E. Africa Standard Time", "Africa/Nairobi"},
        {"E. Australia Standard Time", "Australia/Brisbane"},
        {"E. Europe Standard Time", "Europe/Chisinau"},
        {"E. South America Standard Time", "America/Sao_Paulo"},
        {"Easter Island Standard Time", "Pacific/Easter"},
        {"Eastern Standard Time", "America/New_York"},
        {"Eastern Standard Time (Mexico)", "America/Cancun"},
        {"Egypt Standard Time", "Africa/Cairo"},
        {"Ekaterinburg Standard Time", "Asia/Yekaterinburg"},
        {"FLE Standard Time", "Europe/Kiev"},
        {"Fiji Standard Time", "Pacific/Fiji"},
        {"GMT Standard Time", "Europe/London"},
        {"GTB Standard Time", "Europe/Bucharest"},
        {"Georgian Standard Time", "Asia/Tbilisi"},
        {"Greenland Standard Time", "America/Godthab"},
        {"Greenwich Standard Time", "Atlantic/Reykjavik"},
        {"Haiti Standard Time", "America/Port-au-Prince"},
        {"Hawaiian Standard Time", "Pacific/Honolulu"},
        {"India Standard Time", "Asia/Calcutta"},
        {"Iran Standard Time", "Asia/Tehran"},
        {"Israel Standard Time", "Asia/Jerusalem"},
        {"Jordan Standard Time", "Asia/Amman"},
        {"Kaliningrad Standard Time", "Europe/Kaliningrad"},
        {"Korea Standard Time", "Asia/Seoul"},
        {"Libya Standard Time", "Africa/Tripoli"},
        {"Line Islands Standard Time", "Pacific/Kiritimati"},
        {"Lord Howe Standard Time", "Australia/Lord_Howe"},
        {"Magadan Standard Time", "Asia/Magadan"},
        {"Magallanes Standard Time", "America/Punta_Arenas"},
        {"Marquesas Standard Time", "Pacific/Marquesas"},
        {"Mauritius Standard Time", "Indian/Mauritius"},
        {"Middle East Standard Time", "Asia/Beirut"},
        {"Montevideo Standard Time", "America/Montevideo"},
        {"Morocco Standard Time", "Africa/Casablanca"},
        {"Mountain Standard Time", "America/Denver"},
        {"Mountain Standard Time (Mexico)", "America/Mazatlan"},
        {"Myanmar Standard Time", "Asia/Rangoon"},
        {"N. Central Asia Standard Time", "Asia/Novosibirsk"},
        {"Namibia Standard Time", "Africa/Windhoek"},
        {"Nepal Standard Time", "Asia/Katmandu"},
        {"New Zealand Standard Time", "Pacific/Auckland"},
        {"Newfoundland Standard Time", "America/St_Johns"},
        {"Norfolk Standard Time", "Pacific/Norfolk"},
        {"North Asia East Standard Time", "Asia/Irkutsk"},
        {"North Asia Standard Time", "Asia/Krasnoyarsk"},
        {"North Korea Standard Time", "Asia/Pyongyang"},
        {"Omsk Standard Time", "Asia/Omsk"},
        {"Pacific SA Standard Time", "America/Santiago"},
        {"Pacific Standard Time", "America/Los_Angeles"},
        {"Pacific Standard Time (Mexico)", "America/Tijuana"},
        {"Pakistan Standard Time", "Asia/Karachi"},
        {"Paraguay Standard Time", "America/Asuncion"},
        {"Qyzylorda Standard Time", "Asia/Qyzylorda"},
        {"Romance Standard Time", "Europe/Paris"},
        {"Russia Time Zone 10", "Asia/Srednekolymsk"},
        {"Russia Time Zone 11", "Asia/Kamchatka"},
        {"Russia Time Zone 3", "Europe/Samara"},
        {"Russian Standard Time", "Europe/Moscow"},
        {"SA Eastern Standard Time", "America/Cayenne"},
        {"SA Pacific Standard Time", "America/Bogota"},
        {"SA Western Standard Time", "America/La_Paz"},
        {"SE Asia Standard Time", "Asia/Bangkok"},
        {"Saint Pierre Standard Time", "America/Miquelon"},
        {"Sakhalin Standard Time", "Asia/Sakhalin"},
        {"Samoa Standard Time", "Pacific/Apia"},
        {"Sao Tome Standard Time", "Africa/Sao_Tome"},
        {"Saratov Standard Time", "Europe/Saratov"},
        {"Singapore Standard Time", "Asia/Singapore"},
        {"South Africa Standard Time", "Africa/Johannesburg"},
        {"South Sudan Standard Time", "Africa/Juba"},
        {"Sri Lanka Standard Time", "Asia/Colombo"},
        {"Sudan Standard Time", "Africa/Khartoum"},
        {"Syria Standard Time", "Asia/Damascus"},
        {"Taipei Standard Time", "Asia/Taipei"},
        {"Tasmania Standard Time", "Australia/Hobart"},
        {"Tocantins Standard Time", "America/Araguaina"},
        {"Tokyo Standard Time", "Asia/Tokyo"},
        {"Tomsk Standard Time", "Asia/Tomsk"},
        {"Tonga Standard Time", "Pacific/Tongatapu"},
        {"Transbaikal Standard Time", "Asia/Chita"},
        {"Turkey Standard Time", "Europe/Istanbul"},
        {"Turks And Caicos Standard Time", "America/Grand_Turk"},
        {"US Eastern Standard Time", "America/Indianapolis"},
        {"US Mountain Standard Time", "America/Phoenix"},
        {"UTC", "Etc/UTC"},
        {"UTC+12", "Etc/GMT-12"},
        {"UTC+13", "Etc/GMT-13"},
        {"UTC-02", "Etc/GMT+2"},
        {"UTC-08", "Etc/GMT+8"},
        {"UTC-09", "Etc/GMT+9"},
        {"UTC-11", "Etc/GMT+11"},
        {"Ulaanbaatar Standard Time", "Asia/Ulaanbaatar"},
        {"Venezuela Standard Time", "America/Caracas"},
        {"Vladivostok Standard Time", "Asia/Vladivostok"},
        {"Volgograd Standard Time", "Europe/Volgograd"},
        {"W. Australia Standard Time", "Australia/Perth"},
        {"W. Central Africa Standard Time", "Africa/Lagos"},
        {"W. Europe Standard Time", "Europe/Berlin"},
        {"W. Mongolia Standard Time", "Asia/Hovd"},
        {"West Asia Standard Time", "Asia/Tashkent"},
        {"West Bank Standard Time", "Asia/Hebron"},
        {"West Pacific Standard Time", "Pacific/Port_Moresby"},
        {"Yakutsk Standard Time", "Asia/Yakutsk"},
        {"Yukon Standard Time", "America/Whitehorse"},
    };

    /** The table above, keyed in lower case. */
    private static final Map<String, String> IANA_OF_WINDOWS = new HashMap<>();

    static {
        for (String[] pair : WINDOWS) {
            IANA_OF_WINDOWS.put(pair[0].toLowerCase(Locale.ROOT), pair[1]);
        }
    }

    /** Every IANA name the platform knows, keyed in lower case; built on first use. */
    private static Map<String, String> ianaNames;

    /** RFC 5545's weekday names, Monday first as {@link DayOfWeek} is. */
    private static final String[] WEEKDAYS = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};

    /**
     * The zone a {@code TZID} names, or null when the platform knows no
     * zone by it.
     *
     * <p>An IANA name as it is, trimmed and in any case (Outlook writes a
     * trailing space); a Windows one through the CLDR table; and an IANA
     * name behind a path, the way libical
     * ({@code /freeassociation.sourceforge.net/Tzfile/Europe/Vienna}) and
     * old Mozilla clients ({@code /mozilla.org/20050126_1/Europe/Paris})
     * spell theirs. A name the platform's database does not know, such
     * as a zone newer than the device, answers null like any other, and
     * the object's own definition places it.
     */
    static ZoneId zoneOf(String tzid) {
        if (tzid == null || tzid.trim().isEmpty()) {
            return null;
        }
        String name = tzid.trim();

        ZoneId zone = named(name);
        if (zone != null) {
            return zone;
        }

        String windows = IANA_OF_WINDOWS.get(name.toLowerCase(Locale.ROOT));
        if (windows != null) {
            return named(windows);
        }

        String[] parts = name.split("/");
        for (int count = 3; count >= 2; count--) {
            if (parts.length > count) {
                String[] tail = Arrays.copyOfRange(parts, parts.length - count, parts.length);
                zone = named(String.join("/", tail));
                if (zone != null) {
                    return zone;
                }
            }
        }
        return null;
    }

    /** The IANA zone a name is, in any case. */
    private static synchronized ZoneId named(String name) {
        if (ianaNames == null) {
            ianaNames = new HashMap<>();
            for (String id : ZoneId.getAvailableZoneIds()) {
                ianaNames.put(id.toLowerCase(Locale.ROOT), id);
            }
        }

        String id = ianaNames.get(name.toLowerCase(Locale.ROOT));
        try {
            return id == null ? null : ZoneId.of(id);
        } catch (DateTimeException error) {
            return null;
        }
    }

    /**
     * The zone to name a series by where a zone has to be named, as the
     * phone's calendar provider expands a rule in one: the zone its
     * {@code TZID} is, else the IANA zone whose rules give every time of
     * it the offset the object's own definition gave, else the device's.
     *
     * <p>Never UTC for a zoned series: a provider expanding a Friday
     * 23:00 series in UTC would move it to Saturdays. A fixed-offset
     * {@code Etc} zone is passed over for the same reason, since it would
     * match one season of a zone that changes its clocks. The device's
     * zone wins a tie, then the first name in order.
     */
    static ZoneId zoneFor(List<EventTime> span, ZoneId device) {
        EventTime first = span.isEmpty() ? null : span.get(0);
        if (first == null || !EventTime.ZONED.equals(first.kind)) {
            return first != null && EventTime.UTC.equals(first.kind) ? ZoneOffset.UTC : device;
        }

        ZoneId named = zoneOf(first.tzid);
        if (named != null) {
            return named;
        }

        if (matches(device, span)) {
            return device;
        }
        List<String> ids = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(ids);
        for (String id : ids) {
            if (id.indexOf('/') < 0 || id.startsWith("Etc/") || id.startsWith("SystemV/")) {
                continue;
            }
            ZoneId zone = named(id);
            if (zone != null && matches(zone, span)) {
                return zone;
            }
        }
        return device;
    }

    /** Whether a zone gives every time of a span the offset it carries. */
    private static boolean matches(ZoneId zone, List<EventTime> span) {
        boolean any = false;
        for (EventTime time : span) {
            if (time.offset == null || time.isDate()) {
                continue;
            }
            Instant instant = civil(time.time).toInstant(ZoneOffset.ofTotalSeconds(time.offset));
            if (zone.getRules().getOffset(instant).getTotalSeconds() != time.offset) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** The instant a time names, read where the device is when it says nothing else. */
    static long instant(EventTime time) {
        return instant(time, ZoneId.systemDefault());
    }

    /**
     * The instant a time names, in milliseconds: a date at its midnight
     * and a floating time on the reader's clock, a UTC one as it is, and
     * a zoned one in its zone.
     *
     * <p>A zoned time the clock skips takes the offset before the gap,
     * and one it repeats the earlier of its two, as RFC 5545 3.3.5 reads
     * a DATE-TIME; java.time resolves both that way. A zone the platform
     * cannot name is read at the offset the object's own definition puts
     * in force, and one nobody defined is read where the device is,
     * which is all a reader can do with it.
     */
    static long instant(EventTime time, ZoneId reader) {
        if (time.isDate()) {
            return civil(time.time).toLocalDate().atStartOfDay(reader).toInstant().toEpochMilli();
        }

        LocalDateTime local = civil(time.time);
        if (EventTime.UTC.equals(time.kind)) {
            return local.toInstant(ZoneOffset.UTC).toEpochMilli();
        }

        ZoneId zone = EventTime.ZONED.equals(time.kind) ? zoneOf(time.tzid) : null;
        if (zone == null && EventTime.ZONED.equals(time.kind) && time.offset != null) {
            zone = ZoneOffset.ofTotalSeconds(time.offset);
        }
        return ZonedDateTime.ofLocal(local, zone == null ? reader : zone, null)
                .toInstant()
                .toEpochMilli();
    }

    /**
     * An instant as a time relative to what another one is: the same
     * {@code TZID}, the same kind, so writing it back keeps its zone.
     */
    static EventTime at(long instant, EventTime like, ZoneId reader) {
        Instant moment = Instant.ofEpochMilli(instant);
        if (like.isDate()) {
            return new EventTime(stamp(moment.atZone(reader).toLocalDateTime(), true),
                    EventTime.DATE, "", null);
        }

        ZoneId zone = reader;
        if (EventTime.UTC.equals(like.kind)) {
            zone = ZoneOffset.UTC;
        } else if (EventTime.ZONED.equals(like.kind)) {
            ZoneId named = zoneOf(like.tzid);
            if (named != null) {
                zone = named;
            } else if (like.offset != null) {
                zone = ZoneOffset.ofTotalSeconds(like.offset);
            }
        }
        return new EventTime(
                stamp(moment.atZone(zone).toLocalDateTime(), false), like.kind, like.tzid, null);
    }

    /** An instant as a UTC {@code YYYYMMDDTHHMMSSZ} stamp. */
    static String utc(long instant) {
        LocalDateTime moment =
                Instant.ofEpochMilli(instant).atOffset(ZoneOffset.UTC).toLocalDateTime();
        return stamp(moment, false) + "Z";
    }

    /** A civil {@code YYYYMMDD} or {@code YYYYMMDDTHHMMSS} stamp, a date at midnight. */
    static LocalDateTime civil(String time) {
        LocalDateTime day =
                LocalDate.of(
                                Integer.parseInt(time.substring(0, 4)),
                                Integer.parseInt(time.substring(4, 6)),
                                Integer.parseInt(time.substring(6, 8)))
                        .atStartOfDay();
        if (time.length() < 15) {
            return day;
        }
        return day.withHour(Integer.parseInt(time.substring(9, 11)))
                .withMinute(Integer.parseInt(time.substring(11, 13)))
                .withSecond(Integer.parseInt(time.substring(13, 15)));
    }

    /** A civil moment as a stamp, its date alone or with its time. */
    static String stamp(LocalDateTime moment, boolean date) {
        String day =
                String.format(
                        Locale.US,
                        "%04d%02d%02d",
                        moment.getYear(),
                        moment.getMonthValue(),
                        moment.getDayOfMonth());
        if (date) {
            return day;
        }
        return day
                + String.format(
                        Locale.US,
                        "T%02d%02d%02d",
                        moment.getHour(),
                        moment.getMinute(),
                        moment.getSecond());
    }

    /**
     * The {@code VTIMEZONE} of a zone, written from the platform's rules
     * (RFC 5545 3.6.5), for an object that writes a time in it from
     * {@code since} on.
     *
     * <p>What the zone does from the year before that: every change the
     * database lists, grouped into one observance per pair of offsets
     * with the later ones as {@code RDATE}s, then the rules it keeps
     * yearly, each an {@code RRULE}. A zone that changes nothing in that
     * span is the offset it keeps. Its history before is left out, since
     * no date written from {@code since} on reads it, and a zone such as
     * Morocco's, whose changes are all listed decades ahead, still comes
     * out whole.
     */
    static String vtimezone(ZoneId zone, long since) {
        ZoneRules rules = zone.getRules();
        int from = Instant.ofEpochMilli(since).atZone(zone).getYear() - 1;
        Instant cutoff = LocalDate.of(from, 1, 1).atStartOfDay(zone).toInstant();

        StringBuilder out = new StringBuilder("BEGIN:VTIMEZONE\r\nTZID:")
                .append(zone.getId())
                .append("\r\n");

        Map<String, List<ZoneOffsetTransition>> listed = new LinkedHashMap<>();
        int last = from - 1;
        for (ZoneOffsetTransition transition : rules.getTransitions()) {
            if (transition.getInstant().isBefore(cutoff)) {
                continue;
            }
            String key = transition.getOffsetBefore() + " " + transition.getOffsetAfter();
            listed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(transition);
            last = transition.getDateTimeAfter().getYear();
        }
        for (List<ZoneOffsetTransition> group : listed.values()) {
            ZoneOffsetTransition first = group.get(0);
            StringBuilder dates = new StringBuilder();
            for (ZoneOffsetTransition transition : group.subList(1, group.size())) {
                dates.append(dates.length() == 0 ? "" : ",")
                        .append(stamp(transition.getDateTimeBefore(), false));
            }
            observance(
                    out,
                    rules.isDaylightSavings(first.getInstant()),
                    first.getDateTimeBefore(),
                    first.getOffsetBefore(),
                    first.getOffsetAfter(),
                    dates.length() == 0 ? null : "RDATE:" + dates);
        }

        int year = Math.max(from, last + 1);
        for (ZoneOffsetTransitionRule rule : rules.getTransitionRules()) {
            ZoneOffsetTransition first = rule.createTransition(year);
            observance(
                    out,
                    rule.getOffsetAfter().getTotalSeconds()
                            > rule.getStandardOffset().getTotalSeconds(),
                    first.getDateTimeBefore(),
                    rule.getOffsetBefore(),
                    rule.getOffsetAfter(),
                    "RRULE:" + rule(rule, first.getDateTimeBefore().toLocalDate(), year));
        }

        if (listed.isEmpty() && rules.getTransitionRules().isEmpty()) {
            ZoneOffset offset = rules.getOffset(cutoff);
            observance(out, false, LocalDateTime.of(1970, 1, 1, 0, 0), offset, offset, null);
        }

        return out.append("END:VTIMEZONE\r\n").toString();
    }

    private static void observance(
            StringBuilder out,
            boolean daylight,
            LocalDateTime onset,
            ZoneOffset from,
            ZoneOffset to,
            String recurrence) {
        String kind = daylight ? "DAYLIGHT" : "STANDARD";
        out.append("BEGIN:").append(kind).append("\r\n")
                .append("DTSTART:").append(stamp(onset, false)).append("\r\n")
                .append("TZOFFSETFROM:").append(offset(from)).append("\r\n")
                .append("TZOFFSETTO:").append(offset(to)).append("\r\n");
        if (recurrence != null) {
            // NOTE: folded at 75 octets (RFC 5545 3.1), which a zone's
            // list of changes runs past; the line is ASCII.
            for (int at = 0; at < recurrence.length(); at += at == 0 ? 75 : 74) {
                int end = Math.min(recurrence.length(), at + (at == 0 ? 75 : 74));
                out.append(at == 0 ? "" : " ").append(recurrence, at, end).append("\r\n");
            }
        }
        out.append("END:").append(kind).append("\r\n");
    }

    /** An offset as RFC 5545 3.3.14 spells it: {@code +HHMM}, seconds when it has some. */
    private static String offset(ZoneOffset offset) {
        int seconds = offset.getTotalSeconds();
        int magnitude = Math.abs(seconds);
        String text =
                String.format(
                        Locale.US,
                        "%s%02d%02d",
                        seconds < 0 ? "-" : "+",
                        magnitude / 3600,
                        magnitude / 60 % 60);
        return magnitude % 60 == 0 ? text : text + String.format(Locale.US, "%02d", magnitude % 60);
    }

    /**
     * The yearly {@code RRULE} a transition rule recurs on.
     *
     * <p>The rule names a day of the month, or the first weekday on or
     * after it, or the last on or before it. Its wall time can fall a day
     * off that (a rule kept in UTC or standard time, a change at 24:00),
     * which {@code onset} says: the day it actually falls on in
     * {@code year}, the weekday and the day moving with it.
     */
    private static String rule(ZoneOffsetTransitionRule rule, LocalDate onset, int year) {
        int indicator = rule.getDayOfMonthIndicator();
        DayOfWeek weekday = rule.getDayOfWeek();
        LocalDate nominal = nominal(rule, year);
        int shift = (int) ChronoUnit.DAYS.between(nominal, onset);

        String month = "FREQ=YEARLY;BYMONTH=" + onset.getMonthValue();
        if (weekday == null) {
            return month + ";BYMONTHDAY=" + onset.getDayOfMonth();
        }

        weekday = weekday.plus(shift);
        String day = WEEKDAYS[weekday.getValue() - 1];
        if (indicator > 0) {
            int from = indicator + shift;
            if ((from - 1) % 7 == 0) {
                return month + ";BYDAY=" + ((from - 1) / 7 + 1) + day;
            }
            // NOTE: java.time spells the last Sunday of March as the first
            // on or after the 25th, which is the last week of a month whose
            // length never changes.
            Month named = onset.getMonth();
            if (named.minLength() == named.maxLength() && from + 6 == named.maxLength()) {
                return month + ";BYDAY=-1" + day;
            }
            return month + ";BYDAY=" + day + ";BYMONTHDAY=" + days(from, 1);
        }

        int back = indicator + shift;
        if ((-back - 1) % 7 == 0) {
            return month + ";BYDAY=-" + ((-back - 1) / 7 + 1) + day;
        }
        return month + ";BYDAY=" + day + ";BYMONTHDAY=" + days(back, -1);
    }

    /** The day a rule names before its time is applied. */
    private static LocalDate nominal(ZoneOffsetTransitionRule rule, int year) {
        int indicator = rule.getDayOfMonthIndicator();
        LocalDate first = LocalDate.of(year, rule.getMonth(), 1);
        if (indicator > 0) {
            LocalDate day = first.withDayOfMonth(indicator);
            return rule.getDayOfWeek() == null
                    ? day
                    : day.with(TemporalAdjusters.nextOrSame(rule.getDayOfWeek()));
        }

        LocalDate day = first.withDayOfMonth(first.lengthOfMonth() + indicator + 1);
        return rule.getDayOfWeek() == null
                ? day
                : day.with(TemporalAdjusters.previousOrSame(rule.getDayOfWeek()));
    }

    /** Seven days of the month from one, onward or backward. */
    private static String days(int from, int step) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            int day = from + index * step;
            if (day == 0 || Math.abs(day) > 31) {
                break;
            }
            out.append(out.length() == 0 ? "" : ",").append(day);
        }
        return out.toString();
    }

    private Zones() {}
}
