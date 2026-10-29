package org.pimalaya.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.calendar.CalendarEngine;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.RemoteAccount;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.EventTarget;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Tasks tab over a CalDAV server: summaries as listed, a capture filed
 * elsewhere superseding its source, and calendars refusing tasks
 * (cairn/changes/gtd-hardening).
 *
 * <p>The real calendar driver, engine, bridge and store over a fake server.
 */
@RunWith(RobolectricTestRunner.class)
public class TaskSyncTest {
    private static final String EMAIL = "tasks@example.com";
    private static final String WORK = "https://dav.example.org/cal/work/";
    private static final String HOME = "https://dav.example.org/cal/home/";

    private final Account account = new Account("https://dav.example.org/", EMAIL, "secret");
    private final Dav dav = new Dav();

    private Context context;
    private PimdirDb pimdir;
    private EventStore events;
    private CalendarEngine engine;
    private TaskStore store;
    private TaskWrites writes;
    private Clients clients;

    /** A CalDAV home listed whole each round, by calendar URL. */
    private static final class Dav extends CalendarClient {
        /** The entries of each calendar by resource name, as (ETag, body). */
        final Map<String, Map<String, String[]>> calendars = new LinkedHashMap<>();
        /**
         * What a create answers instead of landing, by calendar URL or by
         * resource name: Fastmail answers 403 to a second resource of one
         * {@code UID}.
         */
        final Map<String, PimalayaException> refusing = new HashMap<>();
        /**
         * Whether a create of a {@code UID} another calendar holds is refused,
         * as Fastmail keeps one {@code UID} per account.
         */
        boolean oneUid;
        /** What a {@code MOVE} answers instead of moving, null to move. */
        PimalayaException unmoving;
        /**
         * Whether a delete guarded by an ETag of an object gone answers 412,
         * the If-Match being false (RFC 9110 13.1.1), as SabreDAV does.
         */
        boolean strict;
        /** What runs once the next listing is read, then never again. */
        Runnable listed;
        /** The creates asked, by resource name. */
        final List<String> creates = new ArrayList<>();
        /** The moves asked, as their source URL, resource name and ETag. */
        final List<String> moves = new ArrayList<>();
        /** The deletes asked, by resource name. */
        final List<String> deletes = new ArrayList<>();
        /** The sync token each round answers, null for none. */
        String token;
        int revisions;

        Map<String, String[]> of(String url) {
            return calendars.computeIfAbsent(url, any -> new LinkedHashMap<>());
        }

        @Override
        public EventDelta syncEvents(
                Transport transport, Account account, String calendarUrl, String cursor) {
            List<EventRef> listed = new ArrayList<>();
            for (Map.Entry<String, String[]> entry : of(calendarUrl).entrySet()) {
                listed.add(new EventRef(entry.getKey(), entry.getValue()[0]));
            }
            Runnable then = this.listed;
            this.listed = null;
            if (then != null) {
                then.run();
            }
            return new EventDelta(listed, List.of(), List.of(), token, true);
        }

        @Override
        public List<Event> multigetEvents(
                Transport transport, Account account, String calendarUrl, List<String> ids) {
            List<Event> read = new ArrayList<>();
            for (String id : ids) {
                String[] entry = of(calendarUrl).get(id);
                if (entry != null) {
                    read.add(new Event(id, entry[0], entry[1]));
                }
            }
            return read;
        }

        @Override
        public EventRef createEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            creates.add(target.id());
            PimalayaException refused = refusing.get(target.calendarUrl());
            if (refused == null) {
                refused = refusing.get(target.id());
            }
            if (refused != null) {
                throw refused;
            }
            if (oneUid) {
                int at = ical.indexOf("UID:");
                String uid = ical.substring(at, ical.indexOf("\r\n", at));
                for (Map<String, String[]> held : calendars.values()) {
                    for (String[] entry : held.values()) {
                        if (entry[1].contains(uid + "\r\n")) {
                            throw duplicateUid();
                        }
                    }
                }
            }
            String etag = "e" + ++revisions;
            of(target.calendarUrl()).put(target.id(), new String[] {etag, ical});
            return new EventRef(target.id(), etag);
        }

        @Override
        public String updateEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            String[] entry = of(target.calendarUrl()).get(target.id());
            if (entry == null) {
                throw new PimalayaException("not found: " + target.id(), 404);
            }
            entry[0] = "e" + ++revisions;
            entry[1] = ical;
            return entry[0];
        }

        @Override
        public EventRef moveEvent(
                Transport transport, Account account, EventTarget target, String targetUrl) {
            moves.add(target.calendarUrl() + " " + target.id() + " " + target.etag());
            if (unmoving != null) {
                throw unmoving;
            }
            String[] entry = of(target.calendarUrl()).get(target.id());
            if (entry == null) {
                throw new PimalayaException("not found: " + target.id(), 404);
            }
            if (target.etag() != null && !target.etag().equals(entry[0])) {
                throw new PimalayaException("WebDAV server returned HTTP 412", 412);
            }
            // NOTE: Overwrite: F.
            if (of(targetUrl).containsKey(target.id())) {
                throw new PimalayaException("WebDAV server returned HTTP 412", 412);
            }
            of(target.calendarUrl()).remove(target.id());
            of(targetUrl).put(target.id(), entry);
            // NOTE: most servers answer a MOVE with no ETag.
            return new EventRef(target.id(), null);
        }

        @Override
        public void deleteEvent(Transport transport, Account account, EventTarget target) {
            deletes.add(target.id());
            if (of(target.calendarUrl()).remove(target.id()) == null) {
                if (strict && target.etag() != null) {
                    throw new PimalayaException("WebDAV server returned HTTP 412", 412);
                }
                throw new PimalayaException("not found: " + target.id(), 404);
            }
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        events = new EventStore(context, pimdir);
        clients =
                new Clients(
                        new AccountClient(), new ContactClient(), dav, new MailClient(),
                        new OfflineClient());
        engine =
                new CalendarEngine(
                        pimdir,
                        clients,
                        new RemoteAccount(null, account, new PimdirAccount(context).idOf(EMAIL)));
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        store = new TaskStore(parts);
        writes = new TaskWrites(parts);
    }

    private void list(Calendar... calendars) {
        events.replaceCalendars(EMAIL, List.of(calendars));
    }

    private EventStore.StoredCalendar calendar(String name) {
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (calendar.name.equals(name)) {
                return calendar;
            }
        }
        throw new AssertionError("no calendar " + name);
    }

    private static String todo(String uid, String extra) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//other//EN\r\nBEGIN:VTODO\r\n"
                + "UID:" + uid + "\r\nDTSTAMP:20261020T080000Z\r\nSUMMARY:Fix the gate\r\n"
                + extra + "END:VTODO\r\nEND:VCALENDAR\r\n";
    }

    private String one(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    @Test
    public void aListedToDoCarriesItsSummary() {
        list(new Calendar("work", "Work", WORK, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:waiting,@home\r\nPRIORITY:2\r\n")});

        engine.sync(calendar("Work").id);

        // NOTE: what CalendarEngine listed with no summary before, the row a
        // reader lists tasks from (task_summary, Annex A.4).
        assertEquals("[\"waiting\",\"@home\"]", one("SELECT categories FROM task_summary"));
        assertEquals("2", one("SELECT priority FROM task_summary"));
        assertEquals("Fix the gate", one("SELECT summary FROM task_summary"));
    }

    @Test
    public void aCaptureFiledElsewhereLeavesItsSourceUntilTheAddIsPushed() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true),
                new Calendar("home", "Home", HOME, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:inbox\r\n")});
        engine.sync(calendar("Work").id);
        engine.sync(calendar("Home").id);
        TaskStore.Stored captured = store.load(context).get(0);
        // NOTE: refused for its UID, whether moved or uploaded.
        dav.refusing.put(HOME, duplicateUid());
        dav.unmoving = duplicateUid();

        writes.moveTo(
                captured, calendar("Home"), TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
        // NOTE: the source's calendar first, as a pass may sync it first: the
        // tombstone is held while the add superseding it is a pending create.
        engine.sync(calendar("Work").id);
        // NOTE: the refusal is the item's, not the pass's: the create stays
        // staged, as io-pimdir's engine keeps a rejected one.
        engine.sync(calendar("Home").id);
        engine.sync(calendar("Work").id);

        assertTrue("the capture stays on its server", dav.of(WORK).containsKey("gate.ics"));
        assertTrue(dav.of(HOME).isEmpty());
        List<TaskStore.Stored> tasks = store.load(context);
        assertEquals(1, tasks.size());
        assertEquals("Home", tasks.get(0).calendar.name);

        // NOTE: still staged, so the next pass the server takes it moves it.
        dav.refusing.clear();
        dav.unmoving = null;
        engine.sync(calendar("Home").id);
        engine.sync(calendar("Work").id);
        assertTrue(dav.of(HOME).containsKey("gate.ics"));
        assertFalse(dav.of(WORK).containsKey("gate.ics"));
    }

    /** Fastmail's refusal of a second resource of one {@code UID}. */
    private static PimalayaException duplicateUid() {
        return new PimalayaException(
                "WebDAV collection already holds a resource with the same UID (HTTP 403)", 403);
    }

    /**
     * A create refused as such is that item's: the calendar's other changes
     * are pushed, the pass succeeds, and the refused create stays staged for
     * the next one.
     */
    private void refusedCreate(PimalayaException refusal) throws Exception {
        list(new Calendar("work", "Work", WORK, "", null, "", true));
        engine.sync(calendar("Work").id);
        String refused = writes.capture(calendar("Work"), "Call Ann", null, null);
        String landed = writes.capture(calendar("Work"), "Fix the gate", null, null);
        dav.refusing.put(refused + ".ics", refusal);

        engine.sync(calendar("Work").id);

        assertTrue("the rest is pushed", dav.of(WORK).containsKey(landed + ".ics"));
        assertFalse(dav.of(WORK).containsKey(refused + ".ics"));
        assertEquals(2, store.load(context).size());
        dav.refusing.clear();
        engine.sync(calendar("Work").id);
        assertTrue("still staged", dav.of(WORK).containsKey(refused + ".ics"));
    }

    @Test
    public void aCreateRefusedForItsUidLeavesTheRestOfThePass() throws Exception {
        refusedCreate(duplicateUid());
    }

    @Test
    public void aCreateRefusedInConflictLeavesTheRestOfThePass() throws Exception {
        refusedCreate(new PimalayaException("WebDAV server returned HTTP 409", 409));
    }

    /**
     * A refused token and a throttle still fail the pass: a token is renewed
     * by the pass's failure, and a throttled server serves no other write.
     */
    @Test
    public void aRefusedTokenOrAThrottleStillFailsThePass() throws Exception {
        PimalayaException[] failures = {
            new PimalayaException("WebDAV server returned HTTP 401", 401),
            new PimalayaException("WebDAV server returned HTTP 403: Rate Limit Exceeded", 403),
        };
        list(new Calendar("work", "Work", WORK, "", null, "", true));
        engine.sync(calendar("Work").id);
        writes.capture(calendar("Work"), "Call Ann", null, null);
        for (PimalayaException failure : failures) {
            dav.refusing.put(WORK, failure);

            PimalayaException failed =
                    assertThrows(PimalayaException.class, () -> engine.sync(calendar("Work").id));

            assertEquals(failure.status, failed.status);
        }
    }

    @Test
    public void aCaptureFiledElsewhereMovesOnceTheAddIsPushed() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true),
                new Calendar("home", "Home", HOME, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:inbox\r\n")});
        engine.sync(calendar("Work").id);
        engine.sync(calendar("Home").id);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.moveTo(
                captured, calendar("Home"), TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
        engine.sync(calendar("Work").id);
        assertTrue("held until the add is pushed", dav.of(WORK).containsKey("gate.ics"));
        engine.sync(calendar("Home").id);
        engine.sync(calendar("Work").id);

        assertFalse(dav.of(WORK).containsKey("gate.ics"));
        // NOTE: under the source's name, its UID and its edit.
        String[] filed = dav.of(HOME).get("gate.ics");
        assertTrue(filed[1].contains("UID:gate\r\n"));
        assertFalse(filed[1].contains("inbox"));
        assertEquals(1, store.load(context).size());
    }

    /** An Inbox item in Work, both calendars synced, filed into Home. */
    private void fileGateIntoHome() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true),
                new Calendar("home", "Home", HOME, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:inbox\r\n")});
        engine.sync(calendar("Work").id);
        engine.sync(calendar("Home").id);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.moveTo(
                captured, calendar("Home"), TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
    }

    /**
     * On a server keeping one {@code UID} per account (Fastmail), filing a
     * task into another calendar moves its member there, gated on its ETag,
     * then writes the edit over it: an upload of that {@code UID} would be
     * refused while the source holds it (pimdir SYNC §4).
     */
    @Test
    public void aTaskFiledElsewhereIsMovedOnAServerKeepingOneUid() throws Exception {
        dav.oneUid = true;
        fileGateIntoHome();

        engine.sync(calendar("Work").id);
        engine.sync(calendar("Home").id);
        engine.sync(calendar("Work").id);

        assertEquals(List.of(WORK + " gate.ics e0"), dav.moves);
        assertTrue("never uploaded", dav.creates.isEmpty());
        assertFalse(dav.of(WORK).containsKey("gate.ics"));
        String[] filed = dav.of(HOME).get("gate.ics");
        assertTrue(filed[1].contains("UID:gate\r\n"));
        assertFalse("the edit written over it", filed[1].contains("inbox"));
        List<TaskStore.Stored> tasks = store.load(context);
        assertEquals(1, tasks.size());
        assertEquals("Home", tasks.get(0).calendar.name);

        // NOTE: settled: another pass moves and uploads nothing.
        engine.sync(calendar("Home").id);
        engine.sync(calendar("Work").id);
        assertEquals(1, dav.moves.size());
        assertTrue(dav.creates.isEmpty());
    }

    /** A move the server cannot do is delivered by an upload instead. */
    private void unmovable(PimalayaException answer) throws Exception {
        dav.unmoving = answer;
        fileGateIntoHome();

        engine.sync(calendar("Home").id);
        engine.sync(calendar("Work").id);

        assertEquals(1, dav.moves.size());
        assertEquals(List.of("gate.ics"), dav.creates);
        assertTrue(dav.of(HOME).containsKey("gate.ics"));
        assertFalse(dav.of(WORK).containsKey("gate.ics"));
        assertEquals(1, store.load(context).size());
    }

    @Test
    public void aMoveGoogleAnswersNotFoundIsAnUpload() throws Exception {
        unmovable(new PimalayaException("WebDAV server returned HTTP 404", 404));
    }

    @Test
    public void aMoveNotAllowedIsAnUpload() throws Exception {
        unmovable(new PimalayaException("WebDAV server returned HTTP 405", 405));
    }

    @Test
    public void aMoveTheBackendCannotDoIsAnUpload() throws Exception {
        unmovable(new PimalayaException("Cannot move gate.ics on this backend", 501));
    }

    /**
     * A move refused for the member's {@code UID} or its ETag refuses the
     * create, as a refused upload does: no upload follows, the pass goes on,
     * and the source stays on its server.
     */
    private void refusedMove(PimalayaException answer) throws Exception {
        dav.unmoving = answer;
        fileGateIntoHome();

        engine.sync(calendar("Home").id);
        engine.sync(calendar("Work").id);

        assertEquals(1, dav.moves.size());
        assertTrue("no upload", dav.creates.isEmpty());
        assertTrue(dav.of(WORK).containsKey("gate.ics"));
        assertTrue(dav.of(HOME).isEmpty());
        assertEquals(1, store.load(context).size());
    }

    @Test
    public void aMoveRefusedForItsUidRefusesTheCreate() throws Exception {
        refusedMove(duplicateUid());
    }

    @Test
    public void aMoveRefusedForItsETagRefusesTheCreate() throws Exception {
        refusedMove(new PimalayaException("WebDAV server returned HTTP 412", 412));
    }

    /**
     * The source's held delete, pushed once the move delivered the create,
     * finds the member gone and is accepted, even from a server answering
     * 412 to an If-Match on an object gone: the source's pass listed it
     * before the target's pass moved it.
     */
    @Test
    public void theHeldDeleteFindingTheMovedMemberGoneIsAccepted() throws Exception {
        dav.oneUid = true;
        dav.strict = true;
        fileGateIntoHome();
        CalendarEngine other =
                new CalendarEngine(
                        pimdir,
                        clients,
                        new RemoteAccount(null, account, new PimdirAccount(context).idOf(EMAIL)));
        String home = calendar("Home").id;
        dav.listed = () -> other.sync(home);

        engine.sync(calendar("Work").id);

        assertTrue(dav.of(HOME).containsKey("gate.ics"));
        assertFalse(dav.of(WORK).containsKey("gate.ics"));
        assertEquals("pushed after the listing", List.of("gate.ics"), dav.deletes);
        assertEquals(
                "the tombstone settled", "0",
                one("SELECT COUNT(*) FROM bindings WHERE collection = ?", calendar("Work").id));
    }

    /**
     * A delete of an object already gone converges, a 412 from a server
     * judging the If-Match false on an object gone (SabreDAV) included: the
     * object is read back, and none is there.
     */
    @Test
    public void aDeleteOfAnObjectGoneIsAcceptedOnAStrictServer() throws Exception {
        dav.strict = true;
        list(new Calendar("work", "Work", WORK, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:inbox\r\n")});
        engine.sync(calendar("Work").id);
        writes.remove(store.load(context).get(0));
        // NOTE: another client deletes it after this pass lists it.
        dav.listed = () -> dav.of(WORK).remove("gate.ics");

        engine.sync(calendar("Work").id);

        assertEquals(List.of("gate.ics"), dav.deletes);
        assertEquals(
                "0",
                one("SELECT COUNT(*) FROM bindings WHERE collection = ?", calendar("Work").id));
    }

    @Test
    public void aCalendarStatingNoToDoTakesNoCapture() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true, true),
                new Calendar("events", "Events", HOME, "", null, "", true, false));
        engine.sync(calendar("Work").id);
        engine.sync(calendar("Events").id);

        List<EventStore.StoredCalendar> targets = store.targets();

        // NOTE: Google's CalDAV calendars state VEVENT alone and answer a
        // VTODO PUT with 403 (x-vtodo none, pimdir STORAGE §15.6).
        assertEquals(1, targets.size());
        assertEquals("Work", targets.get(0).name);
        assertThrows(
                PimalayaException.class,
                () -> writes.capture(calendar("Events"), "Call Ann", null, null));
    }

    /**
     * A calendar listed and never synced is not offered for tasks when its
     * component set leaves VTODO out: the listing declares it and counts its
     * server as syncing it, so nothing waits for a first round.
     */
    @Test
    public void aCalendarListedButNeverSyncedStatingNoToDoTakesNoCapture() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true, true),
                new Calendar("events", "Events", HOME, "", null, "", true, false));

        List<EventStore.StoredCalendar> targets = store.targets();

        assertEquals(1, targets.size());
        assertEquals("Work", targets.get(0).name);
        assertThrows(
                PimalayaException.class,
                () -> writes.capture(calendar("Events"), "Call Ann", null, null));
    }

    /**
     * Filing into a calendar listed and never synced holds the source's
     * delete as well: the staged add is a binding with no base, which
     * {@code list_superseded_pending} reads as a pending create.
     */
    @Test
    public void aCaptureFiledIntoACalendarNeverSyncedKeepsItsSource() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true),
                new Calendar("home", "Home", HOME, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:inbox\r\n")});
        engine.sync(calendar("Work").id);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.moveTo(
                captured, calendar("Home"), TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
        engine.sync(calendar("Work").id);

        assertTrue("held until the add is pushed", dav.of(WORK).containsKey("gate.ics"));
    }

    /** A calendar listed again keeps the sync state its last round left. */
    @Test
    public void aListingKeepsTheCalendarsSyncToken() {
        list(new Calendar("work", "Work", WORK, "", null, "", true, true));
        dav.token = "token-1";
        engine.sync(calendar("Work").id);
        String synced = one("SELECT CAST(checkpoint AS TEXT) FROM sources");

        list(new Calendar("work", "Work", WORK, "", null, "", true, true));

        assertEquals("token-1", synced);
        assertEquals(synced, one("SELECT CAST(checkpoint AS TEXT) FROM sources"));
    }
}
