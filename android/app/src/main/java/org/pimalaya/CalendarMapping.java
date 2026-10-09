package org.pimalaya;

import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.ExtendedProperties;
import android.provider.CalendarContract.Reminders;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.EventTime;

/**
 * The pure half of the calendar mirror, both directions: the phone's view of
 * a calendar object (what the native projection hands over) to
 * CalendarContract rows and back, as plain maps keyed by the provider's
 * column names, and the field-space merge of what the phone changed.
 * docs/calendar-mapping.md is the contract, CalendarMappingTest pins it.
 * Only compile-time Android constants are referenced, so all of it runs on
 * the JVM; {@link CalendarRemote} reads and writes the provider.
 */
final class CalendarMapping {
    private CalendarMapping() {}

    /** The extended property calendar apps edit an event's URL in, ical4android's. */
    static final String URL = "vnd.android.cursor.item/vnd.ical4android.url";

    /**
     * Joins an item's handle and an override's recurrence id into the
     * {@code _SYNC_ID} of the row standing for it, in an object without a
     * master: the unit separator, which no handle carries.
     */
    static final char STANDALONE = '\u001f';

    private static final long DAY = 86_400_000L;

    /** Where one object's rows land: its handle, its calendar's address, the device's zone. */
    static final class Phone {
        final String handle;

        /** The calendar's {@code OWNER_ACCOUNT}: the user's address. */
        final String owner;

        final ZoneId device;

        Phone(String handle, String owner, ZoneId device) {
            this.handle = handle;
            this.owner = owner;
            this.device = device;
        }
    }

    /**
     * One {@code Events} row with its {@code Reminders}, {@code Attendees}
     * and the URL property, as read or as written; a row read carries its
     * ids ({@code _id} in each sub-row).
     */
    static final class Row {
        final Map<String, Object> values = new LinkedHashMap<>();
        final List<Map<String, Object>> reminders = new ArrayList<>();
        final List<Map<String, Object>> attendees = new ArrayList<>();

        /** The URL property's value, null when the row has none. */
        String url;

        /** The row's {@code _ID}, -1 for one not written yet. */
        long id = -1;

        /** The URL property's row, -1 when there is none. */
        long urlId = -1;

        /** A row read: whether a calendar app changed it, or deleted it. */
        boolean dirty;
        boolean deleted;
    }

    // ---- view to rows -------------------------------------------------------

    /**
     * The rows an object's view projects: the master and an exception row per
     * override, or a standalone row per override of an object holding no
     * master. None for an object holding no event.
     */
    static List<Row> rows(JSONObject event, Phone phone) throws JSONException {
        List<Row> rows = new ArrayList<>();
        String uid = event.optString("uid");
        JSONObject master = event.optJSONObject("master");
        JSONArray overrides = event.optJSONArray("overrides");

        if (master != null) {
            EventTime start = time(master.optJSONObject("start"));
            if (start == null) {
                return rows;
            }
            Row row = row(master, master, uid, phone, event.optBoolean("listed"));
            row.values.put(Events._SYNC_ID, phone.handle);
            rows.add(row);
            for (int index = 0; overrides != null && index < overrides.length(); index++) {
                JSONObject over = overrides.getJSONObject(index);
                if (time(over.optJSONObject("start")) == null) {
                    continue;
                }
                Row exception = row(over, master, uid, phone, false);
                exception.values.put(Events.ORIGINAL_SYNC_ID, phone.handle);
                exception.values.put(
                        Events.ORIGINAL_INSTANCE_TIME,
                        instanceOf(time(over.optJSONObject("recurrenceId")), start, phone.device));
                exception.values.put(Events.ORIGINAL_ALL_DAY, start.isDate() ? 1 : 0);
                rows.add(exception);
            }
            return rows;
        }

        for (int index = 0; overrides != null && index < overrides.length(); index++) {
            JSONObject over = overrides.getJSONObject(index);
            if (time(over.optJSONObject("start")) == null) {
                continue;
            }
            Row standalone = row(over, over, uid, phone, false);
            standalone.values.put(
                    Events._SYNC_ID,
                    phone.handle + STANDALONE + standaloneKey(over.optJSONObject("recurrenceId")));
            rows.add(standalone);
        }
        return rows;
    }

    /** One component's row, its organizer and series read off {@code master}. */
    private static Row row(
            JSONObject component, JSONObject master, String uid, Phone phone, boolean listed)
            throws JSONException {
        Row row = new Row();
        Map<String, Object> values = row.values;
        values.put(Events.UID_2445, uid);
        values.put(Events.TITLE, component.optString("summary"));
        values.put(Events.DESCRIPTION, component.optString("description"));
        values.put(Events.EVENT_LOCATION, component.optString("location"));

        EventTime start = time(component.optJSONObject("start"));
        EventTime end = time(component.optJSONObject("end"));
        if (end == null) {
            end = start;
        }
        boolean allDay = start.isDate();
        boolean series = component == master && recurs(master);

        long from;
        long until;
        String zone;
        String endZone;
        if (allDay) {
            from = day(start.time);
            until = day(end.time);
            if (until <= from) {
                until = from + DAY;
            }
            zone = "UTC";
            endZone = "UTC";
        } else {
            zone = id(Zones.zoneFor(List.of(start, end), phone.device));
            from = Zones.instant(start, phone.device);
            until = Zones.instant(end, phone.device);
            endZone =
                    end.kind.equals(start.kind) && end.tzid.equals(start.tzid)
                            ? zone
                            : id(Zones.zoneFor(List.of(end), phone.device));
        }

        if (series && listed) {
            // NOTE: the window's first instance stands as the start, so the
            // provider shows no instance the list does not hold.
            List<Long> dates = aligned(component.optJSONArray("rdates"), start, phone.device);
            if (!dates.isEmpty()) {
                until = dates.get(0) + (until - from);
                from = dates.get(0);
            }
        }

        values.put(Events.DTSTART, from);
        values.put(Events.EVENT_TIMEZONE, zone);
        values.put(Events.ALL_DAY, allDay ? 1 : 0);
        if (series) {
            values.put(Events.DTEND, null);
            values.put(Events.EVENT_END_TIMEZONE, null);
            values.put(Events.DURATION, duration((until - from) / 1000, allDay));
            List<Long> dates = aligned(component.optJSONArray("rdates"), start, phone.device);
            if (!dates.isEmpty() && !listed) {
                dates.add(from);
            }
            values.put(Events.RRULE, listed ? null : joined(component.optJSONArray("rrules")));
            values.put(Events.RDATE, cell(dates));
            values.put(
                    Events.EXDATE,
                    listed ? null : cell(aligned(component.optJSONArray("exdates"), start,
                            phone.device)));
            values.put(Events.EXRULE, listed ? null : joined(component.optJSONArray("exrules")));
        } else {
            values.put(Events.DTEND, until);
            values.put(Events.EVENT_END_TIMEZONE, endZone);
            values.put(Events.DURATION, null);
            values.put(Events.RRULE, null);
            values.put(Events.RDATE, null);
            values.put(Events.EXDATE, null);
            values.put(Events.EXRULE, null);
        }

        values.put(Events.STATUS, status(component.optString("status")));
        values.put(
                Events.AVAILABILITY,
                "TRANSPARENT".equalsIgnoreCase(component.optString("transp"))
                        ? Events.AVAILABILITY_FREE
                        : Events.AVAILABILITY_BUSY);
        values.put(Events.ACCESS_LEVEL, access(component.optString("class")));
        values.put(Events.EVENT_COLOR_KEY, colorKey(component.optString("color")));

        String organizer = master.optString("organizer");
        if (!organizer.isEmpty()) {
            values.put(Events.ORGANIZER, organizer);
        }
        values.put(
                Events.IS_ORGANIZER,
                organizer.isEmpty() || organizer.equalsIgnoreCase(phone.owner) ? 1 : 0);
        values.put(Events.HAS_ATTENDEE_DATA, 1);

        JSONArray alarms = component.optJSONArray("alarms");
        for (int index = 0; alarms != null && index < alarms.length(); index++) {
            Map<String, Object> reminder = new LinkedHashMap<>();
            reminder.put(Reminders.MINUTES, minutes(alarms.getJSONObject(index), from));
            reminder.put(Reminders.METHOD, Reminders.METHOD_ALERT);
            row.reminders.add(reminder);
        }

        JSONArray attendees = component.optJSONArray("attendees");
        for (int index = 0; attendees != null && index < attendees.length(); index++) {
            JSONObject attendee = attendees.getJSONObject(index);
            String email = attendee.optString("email");
            Map<String, Object> entry = new LinkedHashMap<>();
            // NOTE: the provider derives the user's own answer from the row
            // spelled exactly as OWNER_ACCOUNT.
            entry.put(
                    Attendees.ATTENDEE_EMAIL,
                    email.equalsIgnoreCase(phone.owner) ? phone.owner : email);
            entry.put(Attendees.ATTENDEE_NAME, attendee.optString("name"));
            entry.put(
                    Attendees.ATTENDEE_TYPE,
                    attendeeType(attendee.optString("role"), attendee.optString("cutype")));
            entry.put(Attendees.ATTENDEE_STATUS, attendeeStatus(attendee.optString("partstat")));
            entry.put(
                    Attendees.ATTENDEE_RELATIONSHIP,
                    email.equalsIgnoreCase(organizer)
                            ? Attendees.RELATIONSHIP_ORGANIZER
                            : Attendees.RELATIONSHIP_ATTENDEE);
            row.attendees.add(entry);
        }

        String url = component.optString("url");
        row.url = url.isEmpty() ? null : url;
        return row;
    }

    /** Whether a master repeats: a rule or a date of its own. */
    private static boolean recurs(JSONObject master) {
        JSONArray rules = master.optJSONArray("rrules");
        JSONArray dates = master.optJSONArray("rdates");
        return (rules != null && rules.length() > 0) || (dates != null && dates.length() > 0);
    }

    /**
     * The instant an override's row names its instance by
     * ({@code ORIGINAL_INSTANCE_TIME}): its recurrence id aligned to the
     * series' start, in whole seconds, which is what the provider pairs
     * an override with its instance at.
     */
    static long instanceOf(EventTime recurrenceId, EventTime start, ZoneId device) {
        return Math.floorDiv(aligned(recurrenceId, start, device), 1000L) * 1000L;
    }

    /**
     * The instant a date of a series names, aligned to its start's type: a
     * UTC midnight on an all-day series, a date at the start's time of day
     * on a timed one (the provider removes nothing with a date there).
     */
    static long aligned(EventTime value, EventTime start, ZoneId device) {
        if (start.isDate()) {
            return day(value.time);
        }
        if (value.isDate()) {
            EventTime at =
                    new EventTime(value.time + start.time.substring(8), start.kind, start.tzid,
                            start.offset);
            return Zones.instant(at, device);
        }
        return Zones.instant(value, device);
    }

    private static List<Long> aligned(JSONArray values, EventTime start, ZoneId device)
            throws JSONException {
        List<Long> dates = new ArrayList<>();
        for (int index = 0; values != null && index < values.length(); index++) {
            dates.add(aligned(time(values.getJSONObject(index)), start, device));
        }
        return dates;
    }

    /**
     * A provider {@code RDATE} or {@code EXDATE} cell: every instant in UTC,
     * which the provider reads whatever else the row says; null for none.
     */
    static String cell(List<Long> instants) {
        if (instants.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (long instant : new TreeSet<>(instants)) {
            out.append(out.length() == 0 ? "" : ",").append(Zones.utc(instant));
        }
        return out.toString();
    }

    /**
     * The instants a provider cell names: lines of {@code [TZID;]v1,v2}, a
     * bare value read as UTC, a date as its UTC midnight.
     */
    static List<Long> instants(String cell) {
        List<Long> instants = new ArrayList<>();
        if (cell == null) {
            return instants;
        }
        for (String line : cell.split("\n")) {
            String values = line.trim();
            ZoneId zone = ZoneOffset.UTC;
            int semi = values.indexOf(';');
            if (semi >= 0) {
                String tzid = values.substring(0, semi);
                tzid = tzid.regionMatches(true, 0, "TZID=", 0, 5) ? tzid.substring(5) : tzid;
                ZoneId named = Zones.zoneOf(tzid);
                zone = named == null ? zone : named;
                values = values.substring(semi + 1);
            }
            int colon = values.indexOf(':');
            if (colon >= 0) {
                values = values.substring(colon + 1);
            }
            for (String value : values.split(",")) {
                value = value.trim();
                if (value.length() < 8) {
                    continue;
                }
                try {
                    if (value.length() == 8) {
                        instants.add(day(value));
                    } else if (value.endsWith("Z") || value.endsWith("z")) {
                        instants.add(
                                Zones.civil(value.substring(0, value.length() - 1))
                                        .toInstant(ZoneOffset.UTC)
                                        .toEpochMilli());
                    } else {
                        instants.add(Zones.civil(value).atZone(zone).toInstant().toEpochMilli());
                    }
                } catch (RuntimeException unreadable) {
                    // NOTE: a value the provider would not read either.
                }
            }
        }
        return instants;
    }

    /**
     * A length as a provider {@code DURATION}: RFC 5545 form, always
     * {@code P<n>D} on an all-day row, which the provider's all-day fix-up
     * reads without crashing.
     */
    static String duration(long seconds, boolean allDay) {
        if (allDay) {
            return "P" + Math.max(1, seconds / 86_400) + "D";
        }
        StringBuilder out = new StringBuilder(seconds < 0 ? "-P" : "P");
        long left = Math.abs(seconds);
        long days = left / 86_400;
        left %= 86_400;
        if (days > 0) {
            out.append(days).append('D');
        }
        if (left > 0 || days == 0) {
            out.append('T');
            long hours = left / 3_600;
            long minutes = left % 3_600 / 60;
            long rest = left % 60;
            if (hours > 0) {
                out.append(hours).append('H');
            }
            if (minutes > 0) {
                out.append(minutes).append('M');
            }
            if (rest > 0 || (hours == 0 && minutes == 0)) {
                out.append(rest).append('S');
            }
        }
        return out.toString();
    }

    /** The seconds a provider {@code DURATION} spans: RFC 5545 form, or Etar's {@code P<n>S}. */
    static long seconds(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 0;
        }
        String span = duration.trim().toUpperCase(Locale.ROOT);
        int sign = 1;
        if (span.startsWith("-")) {
            sign = -1;
            span = span.substring(1);
        } else if (span.startsWith("+")) {
            span = span.substring(1);
        }
        if (!span.startsWith("P")) {
            return 0;
        }
        long total = 0;
        long amount = 0;
        for (char character : span.substring(1).toCharArray()) {
            if (Character.isDigit(character)) {
                amount = amount * 10 + (character - '0');
                continue;
            }
            switch (character) {
                case 'W':
                    total += amount * 604_800;
                    break;
                case 'D':
                    total += amount * 86_400;
                    break;
                case 'H':
                    total += amount * 3_600;
                    break;
                case 'M':
                    total += amount * 60;
                    break;
                case 'S':
                    total += amount;
                    break;
                default:
                    break;
            }
            amount = 0;
        }
        return sign * total;
    }

    /** {@code STATUS}: never null, a missing one confirmed, an unknown one tentative. */
    static int status(String status) {
        switch (status.toUpperCase(Locale.ROOT)) {
            case "":
            case "CONFIRMED":
                return Events.STATUS_CONFIRMED;
            case "CANCELLED":
                return Events.STATUS_CANCELED;
            default:
                return Events.STATUS_TENTATIVE;
        }
    }

    /** {@code CLASS} as {@code ACCESS_LEVEL}, an unknown one private (RFC 5545 3.8.1.3). */
    static int access(String access) {
        switch (access.toUpperCase(Locale.ROOT)) {
            case "":
                return Events.ACCESS_DEFAULT;
            case "PUBLIC":
                return Events.ACCESS_PUBLIC;
            case "CONFIDENTIAL":
                return Events.ACCESS_CONFIDENTIAL;
            default:
                return Events.ACCESS_PRIVATE;
        }
    }

    /** The phone's colour key for a {@code COLOR}: the CSS name, by exact name, else none. */
    static String colorKey(String color) {
        String name = color.trim().toLowerCase(Locale.ROOT);
        return CssColors.NAMED.containsKey(name) ? name : null;
    }

    /** {@code ROLE} and {@code CUTYPE} as {@code ATTENDEE_TYPE}. */
    static int attendeeType(String role, String cutype) {
        if ("RESOURCE".equalsIgnoreCase(cutype) || "ROOM".equalsIgnoreCase(cutype)) {
            return Attendees.TYPE_RESOURCE;
        }
        switch (role.toUpperCase(Locale.ROOT)) {
            case "REQ-PARTICIPANT":
            case "CHAIR":
                return Attendees.TYPE_REQUIRED;
            case "OPT-PARTICIPANT":
                return Attendees.TYPE_OPTIONAL;
            default:
                return Attendees.TYPE_NONE;
        }
    }

    /** {@code PARTSTAT} as {@code ATTENDEE_STATUS}, a missing one awaiting an answer. */
    static int attendeeStatus(String partstat) {
        switch (partstat.toUpperCase(Locale.ROOT)) {
            case "ACCEPTED":
                return Attendees.ATTENDEE_STATUS_ACCEPTED;
            case "DECLINED":
                return Attendees.ATTENDEE_STATUS_DECLINED;
            case "TENTATIVE":
                return Attendees.ATTENDEE_STATUS_TENTATIVE;
            case "":
            case "NEEDS-ACTION":
                return Attendees.ATTENDEE_STATUS_INVITED;
            default:
                return Attendees.ATTENDEE_STATUS_NONE;
        }
    }

    /** An alarm as minutes before a start at {@code start}, after it when negative. */
    private static long minutes(JSONObject alarm, long start) {
        if (alarm.has("minutes")) {
            return alarm.optLong("minutes");
        }
        String absolute = alarm.optString("absolute");
        String civil =
                absolute.endsWith("Z") ? absolute.substring(0, absolute.length() - 1) : absolute;
        long at = Zones.civil(civil).toInstant(ZoneOffset.UTC).toEpochMilli();
        return (start - at) / 60_000L;
    }

    /** The {@code _SYNC_ID} suffix of a standalone override's row. */
    static String standaloneKey(JSONObject recurrenceId) {
        if (recurrenceId == null) {
            return "";
        }
        String tzid = recurrenceId.optString("tzid");
        String time = recurrenceId.optString("time");
        String wire = EventTime.UTC.equals(recurrenceId.optString("kind")) ? time + "Z" : time;
        return tzid.isEmpty() ? wire : tzid + ":" + wire;
    }

    /** The item a row's {@code _SYNC_ID} belongs to, a standalone override's suffix dropped. */
    static String itemOf(String syncId) {
        int at = syncId.indexOf(STANDALONE);
        return at < 0 ? syncId : syncId.substring(0, at);
    }

    // ---- rows back to a view: the merge -------------------------------------

    /**
     * The phone's edit of an object, in field space: each field is taken from
     * the phone's rows only when it differs from what the base view itself
     * projects, so mapping lossiness never reads as an edit. Both sides go
     * through the same mapping to rows, a time compared civil in its row's
     * zone (an instant in a zone only the object defines), a duration as a
     * length, rules, dates, reminders and attendees as sets.
     *
     * <p>Returns the view to patch onto the base: the base's components with
     * the taken fields replaced, a new override carrying its taken fields
     * alone, a removed one left out. Null when nothing is taken: the caller
     * keeps the base's exact bytes.
     *
     * <p>A master listed instance by instance keeps its dates and rules
     * whatever the phone did to them: those are reverted, not taken.
     */
    static JSONObject merge(JSONObject base, List<Row> phone, Phone context)
            throws JSONException {
        List<Row> held = rows(base, context);
        JSONObject master = base.optJSONObject("master");
        boolean listed = base.optBoolean("listed");
        boolean edited = false;

        JSONObject merged = new JSONObject();
        merged.put("uid", base.optString("uid"));

        Row phoneMaster = master(phone);
        if (phoneMaster != null && (master != null || held.isEmpty())) {
            JSONObject component = copy(master);
            String uid = text(phoneMaster, Events.UID_2445);
            if (master == null && !uid.isEmpty()) {
                merged.put("uid", uid);
            }
            edited |= component(component, master(held), phoneMaster, master, context, true,
                    listed, false);
            merged.put("master", component);
        }

        JSONArray overrides = base.optJSONArray("overrides");
        JSONArray out = new JSONArray();
        if (master != null) {
            EventTime start = time(master.optJSONObject("start"));
            Map<Long, Row> heldBy = exceptions(held);
            Map<Long, Row> phoneBy = exceptions(phone);
            Set<Long> exdates =
                    new TreeSet<>(aligned(master.optJSONArray("exdates"), start, context.device));
            // NOTE: an instance a listed series no longer lists is one the
            // object already excludes.
            Set<Long> instances =
                    listed
                            ? new TreeSet<>(aligned(master.optJSONArray("rdates"), start,
                                    context.device))
                            : null;
            for (int index = 0; overrides != null && index < overrides.length(); index++) {
                JSONObject over = overrides.getJSONObject(index);
                long key = instanceOf(time(over.optJSONObject("recurrenceId")), start,
                        context.device);
                Row row = phoneBy.remove(key);
                if (row == null) {
                    edited = true;
                    continue;
                }
                JSONObject component = copy(over);
                edited |= component(component, heldBy.get(key), row, over, context, false, false,
                        false);
                out.put(component);
            }
            for (Map.Entry<Long, Row> entry : phoneBy.entrySet()) {
                Row row = entry.getValue();
                boolean cancelled = number(row, Events.STATUS, -1) == Events.STATUS_CANCELED;
                if (cancelled && (exdates.contains(entry.getKey())
                        || (instances != null && !instances.contains(entry.getKey())))) {
                    continue;
                }
                JSONObject instance = instance(master, entry.getKey(), context.device);
                JSONObject component = new JSONObject();
                component.put("recurrenceId", instance.get("recurrenceId"));
                if (cancelled) {
                    component.put("status", "CANCELLED");
                } else {
                    Row shifted = rows(withOverride(base, instance), context).get(1);
                    component(component, shifted, row, instance, context, false, false, true);
                }
                out.put(component);
                edited = true;
            }
        } else {
            Map<String, Row> phoneBy = new HashMap<>();
            for (Row row : phone) {
                phoneBy.put(text(row, Events._SYNC_ID), row);
            }
            for (int index = 0; overrides != null && index < overrides.length(); index++) {
                JSONObject over = overrides.getJSONObject(index);
                String syncId = context.handle + STANDALONE
                        + standaloneKey(over.optJSONObject("recurrenceId"));
                Row row = phoneBy.get(syncId);
                if (row == null) {
                    edited = true;
                    continue;
                }
                JSONObject component = copy(over);
                Row heldRow = null;
                for (Row candidate : held) {
                    if (syncId.equals(text(candidate, Events._SYNC_ID))) {
                        heldRow = candidate;
                    }
                }
                edited |= component(component, heldRow, row, over, context, false, false, false);
                out.put(component);
            }
        }
        merged.put("overrides", out);

        return edited ? merged : null;
    }

    /**
     * Whether the phone moved the dates or the recurrence of a master listed
     * instance by instance, which the merge does not take: its rows are put
     * back as the base projects them.
     */
    static boolean reverts(JSONObject base, List<Row> phone, Phone context)
            throws JSONException {
        JSONObject master = base.optJSONObject("master");
        Row held = master(rows(base, context));
        Row row = master(phone);
        if (!base.optBoolean("listed") || master == null || held == null || row == null) {
            return false;
        }
        JSONObject start = master.optJSONObject("start");
        return !startKey(held, start).equals(startKey(row, start))
                || !endKey(held, master.optJSONObject("end")).equals(
                        endKey(row, master.optJSONObject("end")))
                || !text(held, Events.RRULE).equals(text(row, Events.RRULE))
                || !days(instants(text(held, Events.RDATE)), false)
                        .equals(days(instants(text(row, Events.RDATE)), false))
                || !text(held, Events.EXDATE).equals(text(row, Events.EXDATE));
    }

    /**
     * Takes into {@code into} each field the phone's row changes against the
     * held one (none: a created event, every field taken), and answers whether
     * any was. {@code like} is the base component the times are relative to;
     * {@code master} brings the series fields in, {@code listed} keeps them and
     * the dates out, {@code fresh} reads the row as a new override, whose
     * status the provider defaulted.
     */
    private static boolean component(
            JSONObject into,
            Row held,
            Row phone,
            JSONObject like,
            Phone context,
            boolean master,
            boolean listed,
            boolean fresh)
            throws JSONException {
        Row base = held == null ? neutral() : held;
        boolean edited = false;

        for (String[] pair :
                new String[][] {
                    {Events.TITLE, "summary"},
                    {Events.DESCRIPTION, "description"},
                    {Events.EVENT_LOCATION, "location"},
                }) {
            if (!text(base, pair[0]).equals(text(phone, pair[0]))) {
                into.put(pair[1], text(phone, pair[0]));
                edited = true;
            }
        }
        if (!Objects.equals(base.url == null ? "" : base.url, phone.url == null ? "" : phone.url)) {
            into.put("url", phone.url == null ? "" : phone.url);
            edited = true;
        }

        int status = number(phone, Events.STATUS, Events.STATUS_CONFIRMED);
        if (!fresh && number(base, Events.STATUS, Events.STATUS_CONFIRMED) != status) {
            into.put(
                    "status",
                    status == Events.STATUS_CANCELED
                            ? "CANCELLED"
                            : status == Events.STATUS_CONFIRMED ? "CONFIRMED" : "TENTATIVE");
            edited = true;
        }
        int free = number(phone, Events.AVAILABILITY, Events.AVAILABILITY_BUSY);
        if (busy(number(base, Events.AVAILABILITY, Events.AVAILABILITY_BUSY)) != busy(free)) {
            into.put("transp", busy(free) ? "OPAQUE" : "TRANSPARENT");
            edited = true;
        }
        int access = number(phone, Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT);
        if (number(base, Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT) != access) {
            into.put("class", access == Events.ACCESS_PUBLIC ? "PUBLIC"
                    : access == Events.ACCESS_CONFIDENTIAL ? "CONFIDENTIAL"
                    : access == Events.ACCESS_PRIVATE ? "PRIVATE" : "");
            edited = true;
        }
        if (!text(base, Events.EVENT_COLOR_KEY).equals(text(phone, Events.EVENT_COLOR_KEY))) {
            into.put("color", text(phone, Events.EVENT_COLOR_KEY));
            edited = true;
        }

        // NOTE: the provider fills the owner into an event written without
        // an organizer, so the phone's reaches the object only with guests.
        List<Map<String, Object>> guests = guests(phone, base);
        String organizer = text(phone, Events.ORGANIZER);
        String heldOrganizer = text(base, Events.ORGANIZER);
        if ((!heldOrganizer.isEmpty() || !guests.isEmpty())
                && !heldOrganizer.equalsIgnoreCase(organizer)) {
            into.put("organizer", organizer);
            edited = true;
        }

        if (!listed) {
            edited |= times(into, held, phone, like, context);
        }
        if (master && !listed) {
            edited |= series(into, held, phone, like, context);
        }

        List<Long> heldMinutes = minutes(base);
        List<Long> phoneMinutes = minutes(phone);
        if (!heldMinutes.equals(phoneMinutes)) {
            into.put("alarms", alarms(like, base, phone));
            edited = true;
        }

        if (!guestKeys(guests(base, base)).equals(guestKeys(guests))) {
            into.put("attendees", attendees(like, base, guests));
            edited = true;
        }
        return edited;
    }

    /** The start and the end, taken together when the phone moved either. */
    private static boolean times(
            JSONObject into, Row held, Row phone, JSONObject like, Phone context)
            throws JSONException {
        JSONObject start = like == null ? null : like.optJSONObject("start");
        JSONObject end = like == null ? null : like.optJSONObject("end");
        boolean moved =
                held == null
                        || !startKey(held, start).equals(startKey(phone, start))
                        || !endKey(held, end).equals(endKey(phone, end));
        if (!moved) {
            return false;
        }

        boolean allDay = number(phone, Events.ALL_DAY, 0) == 1;
        long from = millis(phone, Events.DTSTART);
        long until = endOf(phone);
        String zone = text(phone, Events.EVENT_TIMEZONE);
        String endZone = text(phone, Events.EVENT_END_TIMEZONE);
        if (endZone.isEmpty() || isSeries(phone)) {
            endZone = zone;
        }
        String heldZone = held == null ? "" : text(held, Events.EVENT_TIMEZONE);
        String heldEndZone = held == null ? "" : text(held, Events.EVENT_END_TIMEZONE);
        if (heldEndZone.isEmpty()) {
            heldEndZone = heldZone;
        }
        into.put("start", timeOf(from, zone, allDay, start, heldZone, context.device));
        into.put("end", timeOf(until, endZone, allDay, end, heldEndZone, context.device));
        return true;
    }

    /** The master's rules and dates, each compared parsed as a set. */
    private static boolean series(
            JSONObject into, Row held, Row phone, JSONObject like, Phone context)
            throws JSONException {
        Row base = held == null ? neutral() : held;
        JSONObject startLike = like == null ? null : like.optJSONObject("start");
        // NOTE: a date the phone adds is written in the start the merge keeps,
        // which is the phone's own on a created event.
        JSONObject newStart = into.has("start") ? into.optJSONObject("start") : startLike;
        if (startLike == null) {
            startLike = newStart;
        }
        boolean allDay = number(phone, Events.ALL_DAY, 0) == 1;
        boolean edited = false;

        if (!rules(text(base, Events.RRULE), startLike, allDay, context.device)
                .equals(rules(text(phone, Events.RRULE), startLike, allDay, context.device))) {
            JSONArray rules = new JSONArray();
            for (String rule : lines(text(phone, Events.RRULE))) {
                rules.put(coerced(rule, startLike, allDay, context.device));
            }
            into.put("rrules", rules);
            edited = true;
        }

        EventTime start = time(startLike);
        long phoneStart = millis(phone, Events.DTSTART);
        String[][] lists = {{Events.RDATE, "rdates"}, {Events.EXDATE, "exdates"}};
        for (String[] pair : lists) {
            Set<Long> was = days(instants(text(base, pair[0])), allDay);
            Set<Long> now = days(instants(text(phone, pair[0])), allDay);
            if (was.equals(now)) {
                continue;
            }
            JSONArray dates = new JSONArray();
            JSONArray kept = like == null ? null : like.optJSONArray(pair[1]);
            Set<Long> covered = new TreeSet<>();
            for (int index = 0; kept != null && start != null && index < kept.length(); index++) {
                long at = day(aligned(time(kept.getJSONObject(index)), start, context.device),
                        allDay);
                if (now.contains(at)) {
                    dates.put(kept.getJSONObject(index));
                    covered.add(at);
                }
            }
            for (long at : now) {
                if (covered.contains(at) || was.contains(at)
                        || (Events.RDATE.equals(pair[0]) && at == day(phoneStart, allDay))) {
                    continue;
                }
                dates.put(dateOf(at, allDay, newStart, context.device));
            }
            into.put(pair[1], dates);
            edited = true;
        }
        return edited;
    }

    /** The phone's alarms, each the base's own where the base held one at that offset. */
    private static JSONArray alarms(JSONObject like, Row base, Row phone) throws JSONException {
        JSONArray held = like == null ? null : like.optJSONArray("alarms");
        List<Long> heldMinutes = new ArrayList<>();
        for (Map<String, Object> reminder : base.reminders) {
            heldMinutes.add(longOf(reminder.get(Reminders.MINUTES)));
        }
        boolean[] used = new boolean[heldMinutes.size()];

        JSONArray alarms = new JSONArray();
        for (long minutes : minutes(phone)) {
            int match = -1;
            for (int index = 0; index < heldMinutes.size(); index++) {
                if (!used[index] && heldMinutes.get(index) == minutes) {
                    match = index;
                    break;
                }
            }
            if (match >= 0 && held != null && match < held.length()) {
                used[match] = true;
                alarms.put(held.getJSONObject(match));
            } else {
                alarms.put(new JSONObject().put("minutes", minutes));
            }
        }
        return alarms;
    }

    /** The phone's attendees, each the base's own where the base held it the same. */
    private static JSONArray attendees(JSONObject like, Row base, List<Map<String, Object>> guests)
            throws JSONException {
        JSONArray held = like == null ? null : like.optJSONArray("attendees");
        List<String> heldKeys = new ArrayList<>();
        for (Map<String, Object> guest : base.attendees) {
            heldKeys.add(guestKey(guest));
        }

        JSONArray attendees = new JSONArray();
        for (Map<String, Object> guest : guests) {
            int match = heldKeys.indexOf(guestKey(guest));
            if (match >= 0 && held != null && match < held.length()) {
                attendees.put(held.getJSONObject(match));
                continue;
            }
            int type = number(guest, Attendees.ATTENDEE_TYPE, Attendees.TYPE_NONE);
            int status = number(guest, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_NONE);
            JSONObject attendee = new JSONObject();
            attendee.put("email", text(guest, Attendees.ATTENDEE_EMAIL));
            attendee.put("name", text(guest, Attendees.ATTENDEE_NAME));
            attendee.put(
                    "role",
                    type == Attendees.TYPE_REQUIRED ? "REQ-PARTICIPANT"
                    : type == Attendees.TYPE_OPTIONAL ? "OPT-PARTICIPANT" : "");
            attendee.put("cutype", type == Attendees.TYPE_RESOURCE ? "RESOURCE" : "");
            attendee.put(
                    "partstat",
                    status == Attendees.ATTENDEE_STATUS_ACCEPTED ? "ACCEPTED"
                    : status == Attendees.ATTENDEE_STATUS_DECLINED ? "DECLINED"
                    : status == Attendees.ATTENDEE_STATUS_TENTATIVE ? "TENTATIVE"
                    : "NEEDS-ACTION");
            attendees.put(attendee);
        }
        return attendees;
    }

    /**
     * A row's attendees as the object takes them: the organizer's own row
     * alone, which Etar adds beside the attendees it invites, adds nothing
     * to an object that does not list them.
     */
    private static List<Map<String, Object>> guests(Row row, Row base) {
        if (row.attendees.size() != 1) {
            return row.attendees;
        }
        Map<String, Object> only = row.attendees.get(0);
        String email = text(only, Attendees.ATTENDEE_EMAIL);
        boolean organizer =
                number(only, Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
                                == Attendees.RELATIONSHIP_ORGANIZER
                        || email.equalsIgnoreCase(text(row, Events.ORGANIZER));
        for (Map<String, Object> held : base.attendees) {
            if (email.equalsIgnoreCase(text(held, Attendees.ATTENDEE_EMAIL))) {
                return row.attendees;
            }
        }
        return organizer ? List.of() : row.attendees;
    }

    private static List<String> guestKeys(List<Map<String, Object>> guests) {
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> guest : guests) {
            keys.add(guestKey(guest));
        }
        Collections.sort(keys);
        return keys;
    }

    /** An attendee compared: the address in any case, the name, the type, the answer. */
    private static String guestKey(Map<String, Object> guest) {
        int status = number(guest, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_NONE);
        // NOTE: none is Etar's default for an attendee it adds, and reads
        // as awaiting an answer.
        if (status == Attendees.ATTENDEE_STATUS_NONE) {
            status = Attendees.ATTENDEE_STATUS_INVITED;
        }
        return text(guest, Attendees.ATTENDEE_EMAIL).toLowerCase(Locale.ROOT)
                + "\n" + text(guest, Attendees.ATTENDEE_NAME)
                + "\n" + number(guest, Attendees.ATTENDEE_TYPE, Attendees.TYPE_NONE)
                + "\n" + status;
    }

    /**
     * A row's reminders the object takes, sorted: the alert ones and those
     * left to the default, which is an alert; an email reminder stays the
     * phone's.
     */
    private static List<Long> minutes(Row row) {
        List<Long> minutes = new ArrayList<>();
        for (Map<String, Object> reminder : row.reminders) {
            int method = number(reminder, Reminders.METHOD, Reminders.METHOD_DEFAULT);
            if (method == Reminders.METHOD_ALERT || method == Reminders.METHOD_DEFAULT) {
                minutes.add(longOf(reminder.get(Reminders.MINUTES)));
            }
        }
        Collections.sort(minutes);
        return minutes;
    }

    /** A row with nothing in it, what a created event is compared against. */
    private static Row neutral() {
        Row row = new Row();
        row.values.put(Events.STATUS, Events.STATUS_CONFIRMED);
        row.values.put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY);
        row.values.put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT);
        return row;
    }

    private static boolean busy(int availability) {
        return availability != Events.AVAILABILITY_FREE;
    }

    /**
     * Whether a row repeats: a master with a rule or a date list. An override
     * row's recurrence columns are ignored, Etar copying the master's there.
     */
    private static boolean isSeries(Row row) {
        return row.values.get(Events.ORIGINAL_INSTANCE_TIME) == null
                && (!text(row, Events.RRULE).isEmpty() || !text(row, Events.RDATE).isEmpty());
    }

    /**
     * Where a row starts, as compared: the day of an all-day row, the civil
     * time in its zone with the zone, the civil time alone for a floating
     * base (the device's zone being the phone's business), the instant for a
     * zone only the object defines.
     */
    private static String startKey(Row row, JSONObject like) {
        return momentKey(
                millis(row, Events.DTSTART),
                text(row, Events.EVENT_TIMEZONE),
                number(row, Events.ALL_DAY, 0) == 1,
                like);
    }

    /** Where a row ends, as compared: a length on a repeating row, as its start otherwise. */
    private static String endKey(Row row, JSONObject like) {
        boolean allDay = number(row, Events.ALL_DAY, 0) == 1;
        if (isSeries(row)) {
            return "length " + (endOf(row) - millis(row, Events.DTSTART)) / 1000;
        }
        String zone = text(row, Events.EVENT_END_TIMEZONE);
        return momentKey(
                endOf(row), zone.isEmpty() ? text(row, Events.EVENT_TIMEZONE) : zone, allDay, like);
    }

    private static String momentKey(long millis, String tz, boolean allDay, JSONObject like) {
        if (allDay) {
            return "day " + Zones.stamp(local(millis, ZoneOffset.UTC), true);
        }
        String kind = like == null ? "" : like.optString("kind");
        String civil = Zones.stamp(local(millis, zoneNamed(tz, ZoneOffset.UTC)), false);
        if (EventTime.FLOATING.equals(kind)) {
            return "civil " + civil;
        }
        if (EventTime.ZONED.equals(kind) && Zones.zoneOf(like.optString("tzid")) == null) {
            return "instant " + Math.floorDiv(millis, 1000L);
        }
        return "civil " + civil + " " + id(zoneNamed(tz, ZoneOffset.UTC));
    }

    /**
     * Where a row ends: its {@code DTEND}, or its start plus its
     * {@code DURATION} on a repeating row (the provider writes 0 into a
     * recurring row's {@code DTEND} after a tzdata update), an all-day end
     * not after its start one day on.
     */
    private static long endOf(Row row) {
        long start = millis(row, Events.DTSTART);
        boolean allDay = number(row, Events.ALL_DAY, 0) == 1;
        long end;
        if (isSeries(row) || row.values.get(Events.DTEND) == null) {
            end = start + seconds(text(row, Events.DURATION)) * 1000;
        } else {
            end = millis(row, Events.DTEND);
        }
        if (allDay && end <= start) {
            end = start + DAY;
        }
        return end;
    }

    /**
     * A time read off a row as the object writes it: in the zone the object
     * had for it when the phone kept the row's zone, its IANA name when the
     * phone changed it; a time in a zone only the object defines rides with
     * its instant, which the native side places.
     */
    static JSONObject timeOf(
            long millis, String tz, boolean allDay, JSONObject like, String likeZone,
            ZoneId device) throws JSONException {
        if (allDay) {
            return json(new EventTime(Zones.stamp(local(millis, ZoneOffset.UTC), true),
                    EventTime.DATE, "", null));
        }
        ZoneId zone = zoneNamed(tz, device);
        String civil = Zones.stamp(local(millis, zone), false);
        String kind = like == null ? "" : like.optString("kind");
        boolean kept = tz.equals(likeZone);

        if (EventTime.FLOATING.equals(kind) && (kept || zone.equals(device))) {
            return json(new EventTime(civil, EventTime.FLOATING, "", null));
        }
        if (EventTime.ZONED.equals(kind) && kept) {
            String tzid = like.optString("tzid");
            JSONObject time = json(new EventTime(civil, EventTime.ZONED, tzid, null));
            if (Zones.zoneOf(tzid) == null) {
                time.put("instant", Zones.utc(millis));
            }
            return time;
        }
        if (isUtc(zone)) {
            return json(new EventTime(
                    Zones.stamp(local(millis, ZoneOffset.UTC), false), EventTime.UTC, "", null));
        }
        return json(new EventTime(civil, EventTime.ZONED, zone.getId(), null));
    }

    /** An instant of a series' dates, in the series' start's zone and kind. */
    private static JSONObject dateOf(long instant, boolean allDay, JSONObject start, ZoneId device)
            throws JSONException {
        if (allDay || start == null) {
            return json(new EventTime(Zones.stamp(local(instant, ZoneOffset.UTC), true),
                    EventTime.DATE, "", null));
        }
        EventTime like = time(start);
        EventTime at = Zones.at(instant, like, device);
        JSONObject time = json(new EventTime(at.time, at.kind, at.tzid, like.offset));
        if (EventTime.ZONED.equals(like.kind) && Zones.zoneOf(like.tzid) == null) {
            time.put("instant", Zones.utc(instant));
        }
        return time;
    }

    /**
     * The master carried to one of its instances, for a new override's row to
     * be compared against: the master's fields, the instance's dates, and the
     * recurrence id naming it in the master's zone.
     */
    static JSONObject instance(JSONObject master, long instant, ZoneId device)
            throws JSONException {
        EventTime start = time(master.optJSONObject("start"));
        EventTime end = time(master.optJSONObject("end"));
        long length =
                end == null
                        ? 0
                        : start.isDate()
                                ? day(end.time) - day(start.time)
                                : Zones.instant(end, device) - Zones.instant(start, device);

        JSONObject instance = copy(master);
        for (String series : new String[] {"rrules", "exrules", "rdates", "exdates"}) {
            instance.put(series, new JSONArray());
        }
        JSONObject id = dateOf(instant, start.isDate(), master.optJSONObject("start"), device);
        instance.put("recurrenceId", id);
        instance.put("start", id);
        instance.put(
                "end",
                dateOf(instant + length, start.isDate(),
                        end == null ? master.optJSONObject("start") : master.optJSONObject("end"),
                        device));
        return instance;
    }

    /** The base view with one override added, for the row it projects. */
    private static JSONObject withOverride(JSONObject base, JSONObject over) throws JSONException {
        JSONObject view = copy(base);
        view.put("overrides", new JSONArray().put(over));
        return view;
    }

    /** A provider rule cell compared: each rule's parts as a set, its end coerced. */
    private static Set<String> rules(String cell, JSONObject start, boolean allDay, ZoneId device) {
        Set<String> rules = new TreeSet<>();
        for (String rule : lines(cell)) {
            Map<String, String> parts = new TreeMap<>();
            for (String part : coerced(rule, start, allDay, device).split(";")) {
                int equals = part.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String name = part.substring(0, equals).trim().toUpperCase(Locale.ROOT);
                String value = part.substring(equals + 1).trim().toUpperCase(Locale.ROOT);
                if (("INTERVAL".equals(name) && "1".equals(value))
                        || ("WKST".equals(name) && "MO".equals(value))) {
                    continue;
                }
                if (name.startsWith("BY")) {
                    String[] items = value.split(",");
                    Arrays.sort(items);
                    value = String.join(",", items);
                }
                parts.put(name, value);
            }
            rules.add(parts.toString());
        }
        return rules;
    }

    /**
     * A rule with its {@code UNTIL} coerced to the start's value type, in UTC
     * when the start is zoned (RFC 5545 3.3.10): Etar writes a date-time one
     * on all-day series.
     */
    static String coerced(String rule, JSONObject start, boolean allDay, ZoneId device) {
        StringBuilder out = new StringBuilder();
        for (String part : rule.split(";")) {
            int equals = part.indexOf('=');
            if (equals >= 0 && "UNTIL".equalsIgnoreCase(part.substring(0, equals).trim())) {
                part = part.substring(0, equals + 1)
                        + until(part.substring(equals + 1).trim(), start, allDay, device);
            }
            out.append(out.length() == 0 ? "" : ";").append(part);
        }
        return out.toString();
    }

    private static String until(String value, JSONObject start, boolean allDay, ZoneId device) {
        if (value.length() < 8) {
            return value;
        }
        boolean utc = value.endsWith("Z") || value.endsWith("z");
        String civil = utc ? value.substring(0, value.length() - 1) : value;
        if (allDay) {
            return civil.substring(0, 8);
        }
        String kind = start == null ? EventTime.UTC : start.optString("kind");
        // NOTE: a date bounds every instance of its day.
        if (civil.length() == 8) {
            civil = civil + "T235959";
            utc = false;
        }
        if (EventTime.FLOATING.equals(kind)) {
            return utc ? Zones.stamp(local(utcMillis(civil), device), false) : civil;
        }
        if (utc) {
            return civil + "Z";
        }
        EventTime local =
                new EventTime(civil, kind, start == null ? "" : start.optString("tzid"),
                        start == null || start.isNull("offset") ? null : start.optInt("offset"));
        return Zones.utc(Zones.instant(local, device));
    }

    private static long utcMillis(String civil) {
        return Zones.civil(civil).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** A master's exception rows, by the instance each replaces. */
    private static Map<Long, Row> exceptions(List<Row> rows) {
        Map<Long, Row> exceptions = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.values.get(Events.ORIGINAL_INSTANCE_TIME) != null) {
                exceptions.put(
                        Math.floorDiv(millis(row, Events.ORIGINAL_INSTANCE_TIME), 1000L) * 1000L,
                        row);
            }
        }
        return exceptions;
    }

    /** The master among an object's rows: the one with no original. */
    static Row master(List<Row> rows) {
        for (Row row : rows) {
            if (isMaster(row.values)) {
                return row;
            }
        }
        return null;
    }

    /** Whether a row is a master: no original instance, no link to one (the contract's test). */
    static boolean isMaster(Map<String, Object> values) {
        return values.get(Events.ORIGINAL_ID) == null
                && values.get(Events.ORIGINAL_SYNC_ID) == null
                && values.get(Events.ORIGINAL_INSTANCE_TIME) == null
                && (values.get(Events._SYNC_ID) == null
                        || String.valueOf(values.get(Events._SYNC_ID)).indexOf(STANDALONE) < 0);
    }

    // ---- writes -------------------------------------------------------------

    /** One write a push makes, as plain values: {@link CalendarRemote} builds the operation. */
    static final class Write {
        static final String EVENTS = "events";
        static final String REMINDERS = "reminders";
        static final String ATTENDEES = "attendees";
        static final String PROPERTIES = "properties";

        final String table;

        /** {@code insert}, {@code update} or {@code delete}. */
        final String op;

        /** The row an update or a delete addresses, -1 for an insert. */
        final long id;

        /** A sub-row's event: the write inserting it, or -1 for an existing one. */
        final int parent;

        /** A sub-row's existing event, -1 when {@link #parent} inserts it. */
        final long event;

        final Map<String, Object> values;

        Write(String table, String op, long id, int parent, long event,
                Map<String, Object> values) {
            this.table = table;
            this.op = op;
            this.id = id;
            this.parent = parent;
            this.event = event;
            this.values = values;
        }
    }

    /**
     * The writes bringing an object's rows from {@code existing} to
     * {@code desired}, in place: event rows matched (the master, each
     * override by its instance, each standalone row by its id) and updated,
     * the missing inserted and the extra deleted; reminders matched by
     * offset, attendees by address and the URL property updated, inserted or
     * deleted one by one, so what apps keep beside them survives. An email
     * reminder is the phone's and never deleted, and a property this mirror
     * did not write is never touched.
     */
    static List<Write> plan(List<Row> existing, List<Row> desired) {
        List<Write> writes = new ArrayList<>();
        Map<String, List<Row>> held = new LinkedHashMap<>();
        for (Row row : existing) {
            held.computeIfAbsent(key(row), ignored -> new ArrayList<>()).add(row);
        }

        int master = -1;
        long masterId = -1;
        for (Row row : desired) {
            List<Row> candidates = held.get(key(row));
            Row match = candidates == null || candidates.isEmpty() ? null : candidates.remove(0);
            Map<String, Object> values = new LinkedHashMap<>(row.values);
            boolean isMaster = isMaster(row.values);
            if (!isMaster && masterId >= 0) {
                values.put(Events.ORIGINAL_ID, masterId);
            }

            int parent = -1;
            long event = -1;
            if (match == null) {
                parent = writes.size();
                writes.add(new Write(Write.EVENTS, "insert", -1, isMaster ? -1 : master, -1,
                        values));
            } else {
                event = match.id;
                writes.add(new Write(Write.EVENTS, "update", match.id, -1, -1, values));
            }
            if (isMaster) {
                master = parent;
                masterId = event;
            }

            Row was = match == null ? new Row() : match;
            subRows(writes, Write.REMINDERS, was.reminders, row.reminders, parent, event,
                    CalendarMapping::reminderKey);
            subRows(writes, Write.ATTENDEES, was.attendees, row.attendees, parent, event,
                    guest -> text(guest, Attendees.ATTENDEE_EMAIL).toLowerCase(Locale.ROOT));
            if (row.url == null && was.urlId >= 0) {
                writes.add(new Write(Write.PROPERTIES, "delete", was.urlId, -1, event, Map.of()));
            } else if (row.url != null && !row.url.equals(was.url)) {
                Map<String, Object> property = new LinkedHashMap<>();
                property.put(ExtendedProperties.NAME, URL);
                property.put(ExtendedProperties.VALUE, row.url);
                writes.add(was.urlId >= 0
                        ? new Write(Write.PROPERTIES, "update", was.urlId, -1, event, property)
                        : new Write(Write.PROPERTIES, "insert", -1, parent, event, property));
            }
        }

        for (List<Row> extra : held.values()) {
            for (Row row : extra) {
                writes.add(new Write(Write.EVENTS, "delete", row.id, -1, -1, Map.of()));
            }
        }
        return writes;
    }

    /**
     * One table of sub-rows brought in line: matched by a key, an unchanged
     * match left alone, a changed one updated, the rest inserted or deleted.
     */
    private static void subRows(
            List<Write> writes,
            String table,
            List<Map<String, Object>> was,
            List<Map<String, Object>> now,
            int parent,
            long event,
            java.util.function.Function<Map<String, Object>, String> keyOf) {
        List<Map<String, Object>> left = new ArrayList<>(was);
        for (Map<String, Object> row : now) {
            Map<String, Object> match = null;
            for (Iterator<Map<String, Object>> rows = left.iterator(); rows.hasNext(); ) {
                Map<String, Object> candidate = rows.next();
                if (keyOf.apply(candidate).equals(keyOf.apply(row))) {
                    match = candidate;
                    rows.remove();
                    break;
                }
            }
            if (match == null) {
                writes.add(new Write(table, "insert", -1, parent, event, row));
            } else if (!same(match, row)) {
                writes.add(new Write(table, "update", longOf(match.get("_id")), -1, event, row));
            }
        }
        for (Map<String, Object> row : left) {
            if (Write.REMINDERS.equals(table)
                    && number(row, Reminders.METHOD, Reminders.METHOD_DEFAULT)
                            == Reminders.METHOD_EMAIL) {
                continue;
            }
            writes.add(new Write(table, "delete", longOf(row.get("_id")), -1, event, Map.of()));
        }
    }

    /** A reminder matched: its offset, the email ones apart. */
    private static String reminderKey(Map<String, Object> reminder) {
        int method = number(reminder, Reminders.METHOD, Reminders.METHOD_DEFAULT);
        boolean email = method == Reminders.METHOD_EMAIL;
        return (email ? "email " : "alert ") + longOf(reminder.get(Reminders.MINUTES));
    }

    /** Whether a row read already holds every value a row to write carries. */
    static boolean same(Map<String, Object> held, Map<String, Object> wanted) {
        for (Map.Entry<String, Object> entry : wanted.entrySet()) {
            Object value = held.get(entry.getKey());
            String was = value == null ? null : String.valueOf(value);
            String now = entry.getValue() == null ? null : String.valueOf(entry.getValue());
            if (!Objects.equals(was, now)) {
                return false;
            }
        }
        return true;
    }

    /** How an event row is matched: the master, an override by its instance, a standalone by id. */
    private static String key(Row row) {
        Object instance = row.values.get(Events.ORIGINAL_INSTANCE_TIME);
        if (instance != null) {
            return "instance " + Math.floorDiv(longOf(instance), 1000L);
        }
        String syncId = text(row, Events._SYNC_ID);
        return syncId.indexOf(STANDALONE) >= 0 ? "standalone " + syncId : "master";
    }

    /**
     * A digest of what an object's rows carry, for the revision an edited
     * object is listed at: two reads of the same rows agree, any change of
     * theirs moves it.
     */
    static String fingerprint(List<Row> rows) {
        List<String> parts = new ArrayList<>();
        for (Row row : rows) {
            StringBuilder part = new StringBuilder(new TreeMap<>(row.values).toString());
            List<String> subs = new ArrayList<>();
            for (Map<String, Object> reminder : row.reminders) {
                subs.add("r" + reminderKey(reminder));
            }
            for (Map<String, Object> guest : row.attendees) {
                subs.add("a" + new TreeMap<>(guest));
            }
            Collections.sort(subs);
            part.append(subs).append(row.url);
            parts.add(part.toString());
        }
        Collections.sort(parts);
        return PimdirHash.of(String.join("\n", parts));
    }

    // ---- helpers ------------------------------------------------------------

    /** A view's time as an {@link EventTime}, null for none. */
    static EventTime time(JSONObject time) {
        if (time == null) {
            return null;
        }
        return new EventTime(
                time.optString("time"),
                time.optString("kind"),
                time.optString("tzid"),
                time.has("offset") && !time.isNull("offset") ? time.optInt("offset") : null);
    }

    static JSONObject json(EventTime time) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("time", time.time);
        json.put("kind", time.kind);
        json.put("tzid", time.tzid);
        if (time.offset != null) {
            json.put("offset", time.offset);
        }
        return json;
    }

    private static JSONObject copy(JSONObject object) throws JSONException {
        return object == null ? new JSONObject() : new JSONObject(object.toString());
    }

    /** A date's UTC midnight. */
    private static long day(String time) {
        return Zones.civil(time.substring(0, 8)).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** An instant down to its UTC day on an all-day series, to the second otherwise. */
    private static long day(long instant, boolean allDay) {
        long unit = allDay ? DAY : 1000L;
        return Math.floorDiv(instant, unit) * unit;
    }

    private static Set<Long> days(List<Long> instants, boolean allDay) {
        Set<Long> days = new TreeSet<>();
        for (long instant : instants) {
            days.add(day(instant, allDay));
        }
        return days;
    }

    private static List<String> lines(String cell) {
        List<String> lines = new ArrayList<>();
        for (String line : cell.split("\n")) {
            if (!line.trim().isEmpty()) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    private static String joined(JSONArray values) {
        if (values == null || values.length() == 0) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            lines.add(values.optString(index));
        }
        return String.join("\n", lines);
    }

    private static LocalDateTime local(long millis, ZoneId zone) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone);
    }

    /** The zone a provider names, the fallback for one this device does not know. */
    private static ZoneId zoneNamed(String tz, ZoneId fallback) {
        if ("UTC".equalsIgnoreCase(tz) || "Etc/UTC".equalsIgnoreCase(tz)
                || "GMT".equalsIgnoreCase(tz)) {
            return ZoneOffset.UTC;
        }
        ZoneId zone = Zones.zoneOf(tz);
        return zone == null ? fallback : zone;
    }

    private static boolean isUtc(ZoneId zone) {
        return zone.normalized().equals(ZoneOffset.UTC);
    }

    /** A zone as the provider names it, UTC by that name. */
    private static String id(ZoneId zone) {
        return isUtc(zone) ? "UTC" : zone.getId();
    }

    private static String text(Row row, String column) {
        return text(row.values, column);
    }

    private static String text(Map<String, Object> values, String column) {
        Object value = values.get(column);
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Row row, String column, int fallback) {
        return number(row.values, column, fallback);
    }

    private static int number(Map<String, Object> values, String column, int fallback) {
        Object value = values.get(column);
        if (value == null) {
            return fallback;
        }
        try {
            return (int) longOf(value);
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    private static long millis(Row row, String column) {
        Object value = row.values.get(column);
        return value == null ? 0 : longOf(value);
    }

    private static long longOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return value == null ? 0 : Long.parseLong(String.valueOf(value).trim());
    }
}
