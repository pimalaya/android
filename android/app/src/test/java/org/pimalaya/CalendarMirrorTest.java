package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.accounts.Account;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The calendar mirror end to end over a provider that behaves as the phone's
 * does where the mirror relies on it ({@link FakeCalendarProvider}), the
 * store and the engine real: the store's events reach the calendar's rows,
 * a calendar app's edit, creation, deletion and override come back as
 * patches of the object, a quiet pass writes nothing, a read-only calendar
 * is put back, and an edit both sides made stays conflicted for the triage.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarMirrorTest {
    private static final String COLLECTION = "acct/Work";
    private static final String SPOKE = PimdirStorage.phoneCollection(COLLECTION);
    private static final String OWNER = "jane@example.com";
    private static final Account ACCOUNT = new Account(OWNER, Accounts.TYPE);
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private static String object(String body) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + body
                + "END:VCALENDAR\r\n";
    }

    private static final String STANDUP =
            object(
                    "BEGIN:VEVENT\r\nUID:ev-1\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;TZID=Europe/Paris:20261012T090000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261012T100000\r\n"
                            + "SUMMARY:Standup\r\nX-VENDOR:kept\r\n"
                            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"
                            + "END:VEVENT\r\n");

    private static final String WEEKLY =
            object(
                    "BEGIN:VEVENT\r\nUID:ev-2\r\nDTSTAMP:20260101T000000Z\r\n"
                            + "DTSTART;TZID=Europe/Paris:20261005T090000\r\n"
                            + "DTEND;TZID=Europe/Paris:20261005T100000\r\n"
                            + "RRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:Weekly\r\nEND:VEVENT\r\n");

    private TimeZone zone;
    private FakeCalendarProvider provider;
    private ContentResolver resolver;
    private PimdirDb pimdir;
    private Server server;
    private long calendar;

    /** The server: a map of handle to (revision, body), taking what is pushed. */
    private static final class Server extends PimdirEngine {
        final Map<String, String[]> members = new LinkedHashMap<>();
        private int revision;

        Server(PimdirDb pimdir) {
            super(pimdir, new PimalayaClient());
        }

        void sync() {
            client.offlineSync(this, COLLECTION, false);
            hydrate(COLLECTION);
        }

        void put(String handle, String body) {
            members.put(handle, new String[] {"etag-" + ++revision, body});
        }

        @Override
        protected PimDomain domain() {
            return PimDomain.CALENDAR;
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            JSONArray listed = new JSONArray();
            for (Map.Entry<String, String[]> member : members.entrySet()) {
                listed.put(
                        new JSONObject()
                                .put("handle", member.getKey())
                                .put("revision", member.getValue()[0]));
            }
            return new JSONObject()
                    .put("items", listed)
                    .put("vanished", new JSONArray())
                    .put("complete", true);
        }

        @Override
        protected JSONObject fetch(JSONObject yielded) throws JSONException {
            JSONArray read = new JSONArray();
            for (String handle : stringsOf(yielded.getJSONArray("handles"))) {
                String[] member = members.get(handle);
                if (member != null) {
                    read.put(
                            new JSONObject()
                                    .put("handle", handle)
                                    .put("linkId", handle)
                                    .put("hash", PimdirHash.of(member[1]))
                                    .put("body", member[1])
                                    .put("sortKey", "")
                                    .put("revision", member[0]));
                }
            }
            return new JSONObject().put("items", read);
        }

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            JSONArray results = new JSONArray();
            JSONArray changes = yielded.getJSONArray("changes");
            for (int index = 0; index < changes.length(); index++) {
                JSONObject change = changes.getJSONObject(index);
                String handle = change.getString("handle");
                String name = PimdirStorage.nameOf(handle);
                if ("remove".equals(change.getString("op"))) {
                    members.remove(name);
                    results.put(result(handle, true, null, null));
                    continue;
                }
                put(name, offline.loadRow(COLLECTION, handle).getString("vcard"));
                results.put(
                        result(handle, true, "add".equals(change.getString("op")) ? name : null,
                                members.get(name)[0]));
            }
            return new JSONObject().put("results", results);
        }
    }

    @Before
    public void setUp() {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));

        Application context = RuntimeEnvironment.getApplication();
        shadowOf(context).grantPermissions(
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR);
        provider =
                Robolectric.setupContentProvider(
                        FakeCalendarProvider.class, CalendarContract.AUTHORITY);
        resolver = context.getContentResolver();

        pimdir = new PimdirDb(context);
        pimdir.getWritableDatabase()
                .execSQL(
                        "INSERT INTO collections(id, account, kind, name)"
                                + " VALUES('" + COLLECTION + "', 'acct', 'text/calendar', 'Work')");
        calendar = showCalendar(Calendars.CAL_ACCESS_OWNER);
        server = new Server(pimdir);
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(zone);
    }

    private long showCalendar(int access) {
        ContentValues values = new ContentValues();
        values.put(Calendars.ACCOUNT_NAME, OWNER);
        values.put(Calendars.ACCOUNT_TYPE, Accounts.TYPE);
        values.put(Calendars._SYNC_ID, COLLECTION);
        values.put(Calendars.CALENDAR_ACCESS_LEVEL, access);
        values.put(Calendars.OWNER_ACCOUNT, OWNER);
        return ContentUris.parseId(
                resolver.insert(CalendarRows.asSyncAdapter(Calendars.CONTENT_URI, ACCOUNT),
                        values));
    }

    private void phonePass() {
        new CalendarEngine(pimdir, new PimalayaClient(), null, null, null).syncPhone(COLLECTION);
    }

    /** The server's event, through the store, onto the phone. */
    private void projected(String handle, String body) {
        server.put(handle, body);
        server.sync();
        phonePass();
    }

    private List<Map<String, Object>> rows(Uri table, String selection) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Cursor cursor = resolver.query(table, null, selection, null, "_id")) {
            while (cursor.moveToNext()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 0; column < cursor.getColumnCount(); column++) {
                    row.put(cursor.getColumnName(column),
                            cursor.isNull(column) ? null : cursor.getString(column));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> events() {
        return rows(Events.CONTENT_URI, null);
    }

    /** A calendar app's write: no sync-adapter flag, so the row comes out dirty. */
    private void edit(long id, String column, Object value) {
        ContentValues values = new ContentValues();
        if (value instanceof Long) {
            values.put(column, (Long) value);
        } else if (value instanceof Integer) {
            values.put(column, (Integer) value);
        } else {
            values.put(column, (String) value);
        }
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), values, null, null);
    }

    private static long id(Map<String, Object> row) {
        return Long.parseLong(String.valueOf(row.get("_id")));
    }

    /** The bodies the store holds, by link id. */
    private Map<String, String> stored() {
        Map<String, String> bodies = new LinkedHashMap<>();
        PimdirItems items = new PimdirItems(pimdir);
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT link_id, object_hash FROM items"
                                        + " WHERE collection = ? AND deleted = 0"
                                        + " AND retained_at IS NULL AND object_hash IS NOT NULL",
                                new String[] {COLLECTION})) {
            while (cursor.moveToNext()) {
                bodies.put(cursor.getString(0), items.body(cursor.getString(1)));
            }
        }
        return bodies;
    }

    private static long at(String local, ZoneId zone) {
        return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli();
    }

    @Test
    public void aServerEventReachesThePhoneCalendar() {
        projected("ev-1.ics", STANDUP);

        List<Map<String, Object>> events = events();
        assertEquals(1, events.size());
        Map<String, Object> event = events.get(0);
        assertEquals("ev-1.ics", event.get(Events._SYNC_ID));
        assertEquals(String.valueOf(calendar), event.get(Events.CALENDAR_ID));
        assertEquals("Standup", event.get(Events.TITLE));
        assertEquals(String.valueOf(at("2026-10-12T09:00", PARIS)), event.get(Events.DTSTART));
        assertEquals("Europe/Paris", event.get(Events.EVENT_TIMEZONE));
        assertEquals("0", event.get(Events.DIRTY));
        assertEquals(PimdirHash.of(STANDUP), event.get(Events.SYNC_DATA1));
        assertEquals(event.get("_id"), event.get(Events.SYNC_DATA3));
        List<Map<String, Object>> reminders =
                rows(Reminders.CONTENT_URI, Reminders.EVENT_ID + " = " + id(event));
        assertEquals(1, reminders.size());
        assertEquals("15", reminders.get(0).get(Reminders.MINUTES));
    }

    @Test
    public void aSecondPassWritesNothing() {
        projected("ev-1.ics", STANDUP);
        int writes = provider.writes;

        phonePass();

        assertEquals(writes, provider.writes);
    }

    @Test
    public void aTitleEditedInACalendarAppComesBackWhole() {
        projected("ev-1.ics", STANDUP);
        edit(id(events().get(0)), Events.TITLE, "Retro");

        phonePass();

        String body = stored().get("ev-1.ics");
        assertTrue(body.contains("SUMMARY:Retro\r\n"));
        assertTrue(body.contains("X-VENDOR:kept\r\n"));
        assertTrue(body.contains("TRIGGER:-PT15M\r\n"));
        assertEquals("0", events().get(0).get(Events.DIRTY));

        server.sync();
        assertEquals(body, server.members.get("ev-1.ics")[1]);
        int writes = provider.writes;
        phonePass();
        assertEquals("content-quiet once both sides agree", writes, provider.writes);
    }

    @Test
    public void anEventCreatedInACalendarAppJoinsTheStore() {
        ContentValues values = new ContentValues();
        values.put(Events.CALENDAR_ID, calendar);
        values.put(Events.TITLE, "Lunch");
        values.put(Events.DTSTART, at("2026-10-12T12:00", PARIS));
        values.put(Events.DTEND, at("2026-10-12T13:00", PARIS));
        values.put(Events.EVENT_TIMEZONE, "Europe/Paris");
        resolver.insert(Events.CONTENT_URI, values);

        phonePass();

        Map<String, Object> event = events().get(0);
        String handle = String.valueOf(event.get(Events._SYNC_ID));
        assertTrue(handle.endsWith(".ics"));
        String body = stored().get(handle);
        assertNotNull(body);
        assertTrue(body.contains("SUMMARY:Lunch\r\n"));
        assertTrue(body.contains("UID:" + event.get(Events.UID_2445) + "\r\n"));
        assertTrue(body.contains("DTSTART;TZID=Europe/Paris:20261012T120000\r\n"));
        assertTrue(body.contains("BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\n"));
        assertEquals("0", event.get(Events.DIRTY));
    }


    @Test
    public void anOccurrenceMovedInACalendarAppBecomesAnOverride() {
        projected("ev-2.ics", WEEKLY);
        Map<String, Object> master = events().get(0);
        assertEquals("PT1H", master.get(Events.DURATION));
        ContentValues values = new ContentValues();
        values.put(Events.CALENDAR_ID, calendar);
        values.put(Events.ORIGINAL_SYNC_ID, "ev-2.ics");
        values.put(Events.ORIGINAL_ID, id(master));
        values.put(Events.ORIGINAL_INSTANCE_TIME, at("2026-10-12T09:00", PARIS));
        values.put(Events.ORIGINAL_ALL_DAY, 0);
        values.put(Events.TITLE, "Weekly");
        values.put(Events.DTSTART, at("2026-10-12T11:00", PARIS));
        values.put(Events.DTEND, at("2026-10-12T12:00", PARIS));
        values.put(Events.EVENT_TIMEZONE, "Europe/Paris");
        values.put(Events.STATUS, Events.STATUS_TENTATIVE);
        resolver.insert(Events.CONTENT_URI, values);

        phonePass();

        String body = stored().get("ev-2.ics");
        assertTrue(body.contains("RECURRENCE-ID;TZID=Europe/Paris:20261012T090000\r\n"));
        assertTrue(body.contains("DTSTART;TZID=Europe/Paris:20261012T110000\r\n"));
        assertFalse(body.contains("STATUS"));
        List<Map<String, Object>> rows = events();
        assertEquals(2, rows.size());
        assertEquals("1", rows.get(0).get(Events.SYNC_DATA2));
        for (Map<String, Object> row : rows) {
            assertEquals("0", row.get(Events.DIRTY));
        }
    }

    @Test
    public void aServerChangeUpdatesTheRowsInPlace() {
        projected("ev-1.ics", STANDUP);
        Map<String, Object> before = events().get(0);
        String reminder =
                String.valueOf(rows(Reminders.CONTENT_URI, null).get(0).get("_id"));

        server.put("ev-1.ics", STANDUP.replace("SUMMARY:Standup", "SUMMARY:Daily"));
        server.sync();
        phonePass();

        Map<String, Object> after = events().get(0);
        assertEquals(before.get("_id"), after.get("_id"));
        assertEquals("Daily", after.get(Events.TITLE));
        assertEquals(reminder, String.valueOf(rows(Reminders.CONTENT_URI, null).get(0).get("_id")));
    }

    @Test
    public void aReadOnlyCalendarPutsAPhoneEditBack() {
        ContentValues read = new ContentValues();
        read.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ);
        resolver.update(
                CalendarRows.asSyncAdapter(
                        ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar), ACCOUNT),
                read, null, null);
        projected("ev-1.ics", STANDUP);
        edit(id(events().get(0)), Events.TITLE, "Mine now");

        phonePass();

        assertEquals("Standup", events().get(0).get(Events.TITLE));
        assertEquals("0", events().get(0).get(Events.DIRTY));
        assertEquals(STANDUP, stored().get("ev-1.ics"));
    }

    @Test
    public void anEditBothSidesMadeStaysConflictedForTheTriage() throws Exception {
        projected("ev-1.ics", STANDUP);
        edit(id(events().get(0)), Events.TITLE, "Phone");
        server.put("ev-1.ics", STANDUP.replace("SUMMARY:Standup", "SUMMARY:Server"));
        server.sync();

        phonePass();
        phonePass();

        assertEquals(1, new PimdirStorage(pimdir).loadConflicts(SPOKE).size());
        assertEquals("Phone", events().get(0).get(Events.TITLE));
        assertTrue(stored().get("ev-1.ics").contains("SUMMARY:Server\r\n"));
    }


    @Test
    public void aFloatingEventFollowsTheDeviceZone() {
        String floating =
                object("BEGIN:VEVENT\r\nUID:f\r\nDTSTART:20261012T090000\r\n"
                        + "DTEND:20261012T100000\r\nEND:VEVENT\r\n");
        projected("f.ics", floating);
        assertEquals(String.valueOf(at("2026-10-12T09:00", PARIS)),
                events().get(0).get(Events.DTSTART));

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
        phonePass();

        ZoneId london = ZoneId.of("Europe/London");
        assertEquals(String.valueOf(at("2026-10-12T09:00", london)),
                events().get(0).get(Events.DTSTART));
        assertEquals("0", events().get(0).get(Events.DIRTY));
        assertEquals(floating, stored().get("f.ics"));
    }

    @Test
    public void aCalendarShownAgainIsProjectedWhole() throws Exception {
        projected("ev-1.ics", STANDUP);
        resolver.delete(CalendarRows.asSyncAdapter(Events.CONTENT_URI, ACCOUNT), null, null);
        new PimdirStorage(pimdir).forget(SPOKE);

        phonePass();

        assertEquals(1, events().size());
        assertEquals("Standup", events().get(0).get(Events.TITLE));
    }
    @Test
    public void anEventDeletedInACalendarAppLeavesTheServerToo() {
        projected("ev-1.ics", STANDUP);
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id(events().get(0))),
                null, null);
        assertEquals("1", events().get(0).get(Events.DELETED));

        phonePass();
        assertFalse(stored().containsKey("ev-1.ics"));
        assertTrue(events().isEmpty());

        server.sync();
        assertFalse(server.members.containsKey("ev-1.ics"));
        phonePass();
        assertTrue("never handed back", events().isEmpty());
    }

    @Test
    public void anEventTheServerDeletedLeavesThePhone() {
        projected("ev-1.ics", STANDUP);

        server.members.clear();
        server.sync();
        phonePass();
        server.sync();

        assertTrue(events().isEmpty());
        assertTrue(server.members.isEmpty());
        assertFalse(stored().containsKey("ev-1.ics"));
    }

    @Test
    public void aTaskShowsNothingAndThePassesSettle() {
        projected("todo.ics",
                object("BEGIN:VTODO\r\nUID:t\r\nDTSTART:20261012T090000Z\r\nEND:VTODO\r\n"));
        assertTrue(events().isEmpty());
        // NOTE: the next pass counts the task the calendar shows nothing of.
        phonePass();
        int writes = provider.writes;

        phonePass();

        assertEquals(writes, provider.writes);
    }

    @Test
    public void anOccurrenceRevertedInACalendarAppGoesBackToTheSeries() {
        String moved =
                WEEKLY.replace(
                        "END:VEVENT\r\nEND:VCALENDAR",
                        "END:VEVENT\r\nBEGIN:VEVENT\r\nUID:ev-2\r\n"
                                + "RECURRENCE-ID;TZID=Europe/Paris:20261012T090000\r\n"
                                + "DTSTART;TZID=Europe/Paris:20261012T110000\r\n"
                                + "DTEND;TZID=Europe/Paris:20261012T120000\r\n"
                                + "END:VEVENT\r\nEND:VCALENDAR");
        projected("ev-2.ics", moved);
        assertEquals(2, events().size());

        // NOTE: an app deletes an unstamped exception row outright, dirtying
        // nothing; the count stamped on its master is what tells.
        resolver.delete(
                CalendarRows.asSyncAdapter(
                        ContentUris.withAppendedId(Events.CONTENT_URI, id(events().get(1))),
                        ACCOUNT),
                null, null);

        phonePass();

        assertFalse(stored().get("ev-2.ics").contains("RECURRENCE-ID"));
        assertEquals("0", events().get(0).get(Events.SYNC_DATA2));
    }

    @Test
    public void theSeriesTheProvidersSplitLeavesBecomesAnObjectOfItsOwn() {
        projected("ev-2.ics", WEEKLY);
        Map<String, Object> master = events().get(0);
        // NOTE: the provider's own this-and-following path clones the
        // master's sync columns into the new series.
        ContentValues clone = new ContentValues();
        clone.put(Events.CALENDAR_ID, calendar);
        for (String column : new String[] {Events._SYNC_ID, Events.UID_2445, Events.SYNC_DATA1,
                Events.SYNC_DATA2, Events.SYNC_DATA3, Events.DURATION, Events.EVENT_TIMEZONE}) {
            clone.put(column, String.valueOf(master.get(column)));
        }
        clone.put(Events.TITLE, "Weekly, later");
        clone.put(Events.DTSTART, at("2026-10-19T09:00", PARIS));
        clone.put(Events.RRULE, "FREQ=WEEKLY;COUNT=2");
        resolver.insert(Events.CONTENT_URI, clone);

        phonePass();

        Map<String, String> bodies = stored();
        assertEquals(2, bodies.size());
        String split = null;
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            if (!body.getKey().equals("ev-2.ics")) {
                split = body.getValue();
            }
        }
        assertNotNull(split);
        assertTrue(split.contains("SUMMARY:Weekly\\, later\r\n"));
        assertFalse("a UID of its own", split.contains("UID:ev-2\r\n"));
        assertEquals(WEEKLY, bodies.get("ev-2.ics"));
    }

    @Test
    public void aListedSeriesPutsItsDatesBackAndTakesTheRest() {
        String listed =
                object("BEGIN:VEVENT\r\nUID:ev-3\r\nDTSTART:20261005T090000Z\r\n"
                        + "DTEND:20261005T100000Z\r\nRRULE:FREQ=YEARLY;BYWEEKNO=41;BYDAY=MO\r\n"
                        + "SUMMARY:Odd\r\nEND:VEVENT\r\n");
        projected("ev-3.ics", listed);
        Map<String, Object> row = events().get(0);
        assertEquals(null, row.get(Events.RRULE));
        long start = Long.parseLong(String.valueOf(row.get(Events.DTSTART)));
        edit(id(row), Events.DTSTART, start + 3_600_000L);
        edit(id(row), Events.TITLE, "Odd, renamed");

        phonePass();

        String body = stored().get("ev-3.ics");
        assertTrue(body.contains("SUMMARY:Odd\\, renamed\r\n"));
        assertTrue(body.contains("RRULE:FREQ=YEARLY;BYWEEKNO=41;BYDAY=MO\r\n"));
        assertEquals(String.valueOf(start), events().get(0).get(Events.DTSTART));
        assertEquals("0", events().get(0).get(Events.DIRTY));
    }
}
