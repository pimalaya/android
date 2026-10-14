package org.pimalaya;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Google calendars prepared through the Calendar API (v3).
 *
 * <p>A scratch collection is a secondary calendar, deleted with its events
 * (asynchronously, minutes later); an event is imported under its own
 * `iCalUID`.
 */
final class E2eGoogleCalendarFixture implements E2eItemFixture {
    /**
     * The primary calendar, the one Google's CalDAV serves; tagged events only.
     */
    static final String PRIMARY = "primary";

    private static final String API = "https://www.googleapis.com/calendar/v3/";

    private final Callable<String> token;

    E2eGoogleCalendarFixture(Callable<String> token) {
        this.token = token;
    }

    @Override
    public void createCollection(String name) throws Exception {
        E2eHttp.json("POST", API + "calendars", token.call(), new JSONObject().put("summary", name));
    }

    @Override
    public void deleteCollection(String name) throws Exception {
        for (JSONObject calendar : calendars(name)) {
            if (name.equals(calendar.optString("summary"))) {
                delete(calendar.getString("id"));
            }
        }
    }

    @Override
    public void seed(String collection, String title) throws Exception {
        ZonedDateTime start =
                ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        E2eHttp.json(
                "POST",
                API + "calendars/" + encode(idOf(collection)) + "/events/import",
                token.call(),
                new JSONObject()
                        .put("iCalUID", UUID.randomUUID() + "@pimalaya-e2e.invalid")
                        .put("summary", title)
                        .put("start", when(start))
                        .put("end", when(start.plusHours(1))));
    }

    @Override
    public List<String> titles(String collection, String text) throws Exception {
        List<String> titles = new ArrayList<>();
        for (JSONObject event : events(collection)) {
            String summary = event.optString("summary");
            if (summary.contains(text) && !"cancelled".equals(event.optString("status"))) {
                titles.add(summary);
            }
        }
        return titles;
    }

    @Override
    public void sweep(String tag) throws Exception {
        for (JSONObject calendar : calendars(tag)) {
            delete(calendar.getString("id"));
        }
        for (JSONObject event : events(PRIMARY)) {
            if (event.optString("summary").contains(tag)) {
                deleteEvent(event.getString("id"));
            }
        }
    }

    @Override
    public void sweepStale(LocalDateTime before) throws Exception {
        for (JSONObject calendar : calendars(E2eFixture.PREFIX)) {
            if (E2eFixture.stale(calendar.optString("summary"), before)) {
                delete(calendar.getString("id"));
            }
        }
        for (JSONObject event : events(PRIMARY)) {
            String summary = event.optString("summary");
            if (summary.startsWith(E2eFixture.PREFIX) && E2eFixture.stale(summary, before)) {
                deleteEvent(event.getString("id"));
            }
        }
    }

    /** One calendar's events, cancelled ones included. */
    private List<JSONObject> events(String collection) throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONArray items =
                E2eHttp.json(
                                "GET",
                                API + "calendars/" + encode(idOf(collection)) + "/events?maxResults=2500",
                                token.call(),
                                null)
                        .optJSONArray("items");
        for (int index = 0; items != null && index < items.length(); index++) {
            events.add(items.getJSONObject(index));
        }
        return events;
    }

    private void deleteEvent(String id) throws Exception {
        try {
            E2eHttp.json("DELETE", API + "calendars/primary/events/" + encode(id), token.call(), null);
        } catch (IOException gone) {
            // NOTE: deleted by the app meanwhile.
        }
    }

    /** The user's calendars whose summary carries the text. */
    private List<JSONObject> calendars(String text) throws Exception {
        List<JSONObject> found = new ArrayList<>();
        JSONArray items =
                E2eHttp.json("GET", API + "users/me/calendarList?maxResults=250", token.call(), null)
                        .optJSONArray("items");
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject calendar = items.getJSONObject(index);
            if (calendar.optString("summary").contains(text)) {
                found.add(calendar);
            }
        }
        return found;
    }

    private String idOf(String name) throws Exception {
        if (PRIMARY.equals(name)) {
            return PRIMARY;
        }
        for (JSONObject calendar : calendars(name)) {
            if (name.equals(calendar.optString("summary"))) {
                return calendar.getString("id");
            }
        }
        throw new IOException("no Google calendar " + name);
    }

    private void delete(String id) throws Exception {
        try {
            E2eHttp.json("DELETE", API + "calendars/" + encode(id), token.call(), null);
        } catch (IOException gone) {
            // NOTE: a delete already under way answers 404 or 410.
        }
    }

    private static JSONObject when(ZonedDateTime instant) throws Exception {
        return new JSONObject()
                .put("dateTime", instant.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    private static String encode(String id) throws Exception {
        return URLEncoder.encode(id, "UTF-8");
    }
}
