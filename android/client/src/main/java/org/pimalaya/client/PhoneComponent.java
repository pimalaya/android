package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One component of the phone view: the master or an override.
 *
 * <p>Mirrors rs/calendar/phone.rs {@code PhoneComponent} 145. A null field
 * is absent ({@code None}): a projection fills every one but the times it
 * lacks, an edit leaves out what it does not change.
 *
 * @param recurrenceId the instance an override replaces; null on a master
 * @param summary its {@code SUMMARY}
 * @param description its {@code DESCRIPTION}
 * @param location its {@code LOCATION}
 * @param url its {@code URL}
 * @param start its {@code DTSTART}
 * @param end {@code DTEND}, or the end {@code DURATION} gives, or the one
 *     RFC 5545 3.6.1 implies
 * @param rrules every {@code RRULE}, as written
 * @param exrules every {@code EXRULE}, as written
 * @param rdates every {@code RDATE}
 * @param exdates every {@code EXDATE}
 * @param status its {@code STATUS}, as written
 * @param transp its {@code TRANSP}, as written
 * @param classification its {@code CLASS}, as written (RFC 5545 3.8.1.3)
 * @param color its {@code COLOR} (RFC 7986 5.9)
 * @param organizer the {@code ORGANIZER} address without {@code mailto:}
 * @param alarms its alarms the phone carries
 * @param attendees every {@code ATTENDEE} with an address
 */
public record PhoneComponent(
        PhoneTime recurrenceId,
        String summary,
        String description,
        String location,
        String url,
        PhoneTime start,
        PhoneTime end,
        List<String> rrules,
        List<String> exrules,
        List<PhoneTime> rdates,
        List<PhoneTime> exdates,
        String status,
        String transp,
        String classification,
        String color,
        String organizer,
        List<PhoneAlarm> alarms,
        List<PhoneAttendee> attendees) {
    /** A mutable component: what the phone mapping merges an edit into. */
    public static final class Builder {
        public PhoneTime recurrenceId;
        public String summary;
        public String description;
        public String location;
        public String url;
        public PhoneTime start;
        public PhoneTime end;
        public List<String> rrules;
        public List<String> exrules;
        public List<PhoneTime> rdates;
        public List<PhoneTime> exdates;
        public String status;
        public String transp;
        public String classification;
        public String color;
        public String organizer;
        public List<PhoneAlarm> alarms;
        public List<PhoneAttendee> attendees;

        /** An empty component: every field absent. */
        public Builder() {}

        /** A working copy of {@code component}. */
        public Builder(PhoneComponent component) {
            recurrenceId = component.recurrenceId;
            summary = component.summary;
            description = component.description;
            location = component.location;
            url = component.url;
            start = component.start;
            end = component.end;
            rrules = component.rrules;
            exrules = component.exrules;
            rdates = component.rdates;
            exdates = component.exdates;
            status = component.status;
            transp = component.transp;
            classification = component.classification;
            color = component.color;
            organizer = component.organizer;
            alarms = component.alarms;
            attendees = component.attendees;
        }

        /** The immutable component, its lists copied. */
        public PhoneComponent build() {
            return new PhoneComponent(
                    recurrenceId,
                    summary,
                    description,
                    location,
                    url,
                    start,
                    end,
                    copy(rrules),
                    copy(exrules),
                    copy(rdates),
                    copy(exdates),
                    status,
                    transp,
                    classification,
                    color,
                    organizer,
                    copy(alarms),
                    copy(attendees));
        }

        private static <T> List<T> copy(List<T> list) {
            return list == null ? null : List.copyOf(list);
        }
    }

    /** Every time the component carries, in field order. */
    public List<PhoneTime> times() {
        List<PhoneTime> times = new ArrayList<>();
        for (PhoneTime time : new PhoneTime[] {recurrenceId, start, end}) {
            if (time != null) {
                times.add(time);
            }
        }
        if (rdates != null) {
            times.addAll(rdates);
        }
        if (exdates != null) {
            times.addAll(exdates);
        }
        return times;
    }

    /** One component of a view; a key left out or null reads as absent. */
    static PhoneComponent from(JSONObject component) {
        return new PhoneComponent(
                PhoneTime.from(component.optJSONObject("recurrenceId")),
                Json.optString(component, "summary"),
                Json.optString(component, "description"),
                Json.optString(component, "location"),
                Json.optString(component, "url"),
                PhoneTime.from(component.optJSONObject("start")),
                PhoneTime.from(component.optJSONObject("end")),
                strings(component.optJSONArray("rrules")),
                strings(component.optJSONArray("exrules")),
                entries(component.optJSONArray("rdates"), PhoneTime::from),
                entries(component.optJSONArray("exdates"), PhoneTime::from),
                Json.optString(component, "status"),
                Json.optString(component, "transp"),
                Json.optString(component, "class"),
                Json.optString(component, "color"),
                Json.optString(component, "organizer"),
                entries(component.optJSONArray("alarms"), PhoneAlarm::from),
                entries(component.optJSONArray("attendees"), PhoneAttendee::from));
    }

    /** The component on the wire: absent field, absent key. */
    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (recurrenceId != null) {
            json.put("recurrenceId", recurrenceId.toJson());
        }
        putText(json, "summary", summary);
        putText(json, "description", description);
        putText(json, "location", location);
        putText(json, "url", url);
        if (start != null) {
            json.put("start", start.toJson());
        }
        if (end != null) {
            json.put("end", end.toJson());
        }
        if (rrules != null) {
            json.put("rrules", new JSONArray(rrules));
        }
        if (exrules != null) {
            json.put("exrules", new JSONArray(exrules));
        }
        if (rdates != null) {
            json.put("rdates", times(rdates));
        }
        if (exdates != null) {
            json.put("exdates", times(exdates));
        }
        putText(json, "status", status);
        putText(json, "transp", transp);
        putText(json, "class", classification);
        putText(json, "color", color);
        putText(json, "organizer", organizer);
        if (alarms != null) {
            JSONArray array = new JSONArray();
            for (PhoneAlarm alarm : alarms) {
                array.put(alarm.toJson());
            }
            json.put("alarms", array);
        }
        if (attendees != null) {
            JSONArray array = new JSONArray();
            for (PhoneAttendee attendee : attendees) {
                array.put(attendee.toJson());
            }
            json.put("attendees", array);
        }
        return json;
    }

    private static void putText(JSONObject json, String key, String value)
            throws JSONException {
        if (value != null) {
            json.put(key, value);
        }
    }

    private static JSONArray times(List<PhoneTime> times) throws JSONException {
        JSONArray array = new JSONArray();
        for (PhoneTime time : times) {
            array.put(time.toJson());
        }
        return array;
    }

    private static List<String> strings(JSONArray values) {
        return values == null ? null : List.copyOf(Json.strings(values));
    }

    private static <T> List<T> entries(JSONArray values, Function<JSONObject, T> entry) {
        if (values == null) {
            return null;
        }
        List<T> entries = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            entries.add(entry.apply(Json.object(values, index)));
        }
        return List.copyOf(entries);
    }
}
