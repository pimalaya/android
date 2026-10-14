package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.Zones;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventEdit;
import org.pimalaya.client.Flags;
import org.robolectric.ParameterizedRobolectricTestRunner;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A calendar listed, and an event created, edited and deleted on the phone. */
public class E2eCalendarTest extends E2eCase {
    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public E2eCalendarTest(String target) {
        super(target);
    }

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> targets() {
        return E2eTarget.parameters(E2eTarget.CALENDAR);
    }

    @Test
    @E2eScenario({
        "calendar: A calendar entry can be created / A new entry",
        "calendar: A calendar entry can be edited / Editing an entry",
        "calendar: A calendar entry can be deleted / Deleting an entry",
        "calendar: A pass puts back the bodies it dropped / An entry the server changed",
        "offline-store: An action is a local write / An entry edited with the radio off",
        "offline-store: A staged removal is a tombstone the load hands back / The delete is derived",
    })
    public void anEventRoundTripsThroughAScratchCalendar() throws Exception {
        String name = scratchCalendar();
        items.seed(name, subject("seeded"));

        connected();
        EventStore.StoredCalendar calendar = calendarOf(phone, name);
        assertNotNull("the scratch calendar listed", calendar);
        assertEquals(
                "the seeded event synced into " + calendar.id,
                1,
                phone.events(calendar.id, subject("seeded")).size());

        String uid = UUID.randomUUID().toString();
        String ical = ical(uid, subject("created"));
        // NOTE: as the event page stages a new one, under `<uid>.ics`.
        phone.calendarStager(address)
                .mutateAdd(calendar.id, uid + ".ics", ical, Flags.none(), null, "");
        List<EventStore.StoredEvent> staged = phone.events(calendar.id, subject("created"));
        assertEquals("shown at once", 1, staged.size());
        assertTrue("pending", phone.owes(calendar.id, staged.get(0).id));

        sync();
        settles(
                "the server holds the created event",
                () -> {
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("created")) ? null : "titles " + titles;
                });

        EventStore.StoredEvent created = phone.events(calendar.id, subject("created")).get(0);
        String edited =
                CalendarClient.writeEvent(
                        created.ical,
                        EventEdit.of(
                                Map.of(EventEdit.Field.SUMMARY, subject("edited")),
                                Map.of(),
                                null,
                                Zones.utc(System.currentTimeMillis())));
        phone.calendarStager(address).mutateEdit(calendar.id, created.handle, edited, null, "");
        assertEquals("edited at once", 1, phone.events(calendar.id, subject("edited")).size());
        settles(
                "the server holds the edit",
                () -> {
                    sync();
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("edited")) && !titles.contains(subject("created"))
                            ? null
                            : "titles " + titles;
                });

        E2eDevice observer = observer();
        EventStore.StoredCalendar seen = calendarOf(observer, name);
        assertNotNull("another device lists the calendar", seen);
        assertEquals(
                "another device reads the edit",
                1,
                observer.events(seen.id, subject("edited")).size());

        EventStore.StoredEvent toDelete = phone.events(calendar.id, subject("edited")).get(0);
        phone.calendarStager(address).mutateRemove(calendar.id, toDelete.handle);
        assertTrue("gone at once", phone.events(calendar.id, subject("edited")).isEmpty());
        // NOTE: synced again while waiting: a delete the server refused stays
        // staged for the pass that follows.
        settles(
                "the server holds it no more",
                () -> {
                    sync();
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("edited"))
                            ? "server " + titles + ", phone "
                                    + phone.events(calendar.id, tag).size() + " tagged"
                            : null;
                });
        settles(
                "another device reads it gone",
                () -> {
                    Exception failure = observer.syncCalendars(address);
                    if (failure != null) {
                        return "the pass failed: " + failure.getMessage();
                    }
                    return observer.events(seen.id, subject("edited")).isEmpty() ? null : "still there";
                });
    }

    @Test
    @E2eScenario({
        "calendar: A calendar entry can be deleted / Deleting an entry",
        "offline-store: A staged removal is a tombstone the load hands back / The row is not resurrected",
    })
    public void anEventWrittenElsewhereIsDeleted() throws Exception {
        String name = scratchCalendar();
        items.seed(name, subject("elsewhere"));

        connected();
        EventStore.StoredCalendar calendar = calendarOf(phone, name);
        EventStore.StoredEvent seeded = phone.events(calendar.id, subject("elsewhere")).get(0);
        phone.calendarStager(address).mutateRemove(calendar.id, seeded.handle);
        assertTrue("gone at once", phone.events(calendar.id, subject("elsewhere")).isEmpty());
        settles(
                "the server holds it no more",
                () -> {
                    sync();
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("elsewhere"))
                            ? "server " + titles + ", phone "
                                    + phone.events(calendar.id, tag).size() + " tagged"
                            : null;
                });
    }

    @Test
    @E2eScenario({
        "offline-store: A staged removal is a tombstone the load hands back / The row is not resurrected",
    })
    public void anEventCreatedOnThePhoneIsDeleted() throws Exception {
        String name = scratchCalendar();
        connected();
        EventStore.StoredCalendar calendar = calendarOf(phone, name);
        String uid = UUID.randomUUID().toString();
        phone.calendarStager(address)
                .mutateAdd(calendar.id, uid + ".ics", ical(uid, subject("created")), Flags.none(), null, "");
        settles(
                "the server holds the created event",
                () -> {
                    sync();
                    return items.titles(name, tag).contains(subject("created")) ? null : "not yet";
                });
        EventStore.StoredEvent created = phone.events(calendar.id, subject("created")).get(0);
        phone.calendarStager(address).mutateRemove(calendar.id, created.handle);
        assertTrue("gone at once", phone.events(calendar.id, subject("created")).isEmpty());
        settles(
                "the server holds it no more",
                () -> {
                    sync();
                    List<String> titles = items.titles(name, tag);
                    return titles.contains(subject("created"))
                            ? "server " + titles + ", phone "
                                    + phone.events(calendar.id, tag).size() + " tagged"
                            : null;
                });
    }

    /** A one-hour event two days on, in UTC, as a minimal RFC 5545 object. */
    private static String ical(String uid, String summary) {
        ZonedDateTime start = ZonedDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Pimalaya//e2e//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:" + uid + "\r\n"
                + "DTSTAMP:" + UTC.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n"
                + "DTSTART:" + UTC.format(start) + "\r\n"
                + "DTEND:" + UTC.format(start.plusHours(1)) + "\r\n"
                + "SUMMARY:" + summary + "\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    /**
     * A scratch calendar, or Google's primary (its CalDAV home lists no other).
     *
     * <p>Only tagged events are written to the primary.
     */
    private String scratchCalendar() throws Exception {
        return target.id.equals("google-caldav") ? E2eGoogleCalendarFixture.PRIMARY : scratch();
    }

    /** A device's calendar of that name, which must be listed. */
    private EventStore.StoredCalendar calendarOf(E2eDevice device, String name) {
        EventStore.StoredCalendar calendar =
                name.equals(E2eGoogleCalendarFixture.PRIMARY)
                        ? device.calendars(address).get(0)
                        : device.calendar(address, name);
        assertNotNull("the calendar " + name + " listed", calendar);
        return calendar;
    }

    /** One calendar pass on the phone, which must not fail. */
    private void sync() {
        Exception failure = phone.syncCalendars(address);
        if (failure != null) {
            throw new AssertionError("the pass failed: " + failure.getMessage(), failure);
        }
    }
}
