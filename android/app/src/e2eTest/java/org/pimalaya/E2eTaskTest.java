package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.task.TaskStore;
import org.pimalaya.task.TaskWrites;
import org.robolectric.ParameterizedRobolectricTestRunner;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The Tasks tab's writes on a CalDAV server: a capture, its clarification,
 * filing it elsewhere, and a Waiting For item as Send and wait files one
 * (cairn/changes/gtd-hardening).
 *
 * <p>Only in the run's scratch calendars, which the passes alone sync: the
 * account's own calendars are listed, never synced, read or written. Run it
 * granted a prefix of its own with {@code -Pe2e.prefix}.
 */
public class E2eTaskTest extends E2eCase {
    public E2eTaskTest(String target) {
        super(target);
        scratchOnly = true;
    }

    /** The CalDAV targets that take to-dos; Google's CalDAV refuses them. */
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> targets() {
        return E2eTarget.parameters(List.of("fastmail-caldav"));
    }

    @Test
    @E2eScenario({
        "tasks: A capture is on the phone at once / Capturing a mail",
        "tasks: A task write patches the object / Nothing to write",
        "tasks: Filing a capture elsewhere never loses it / The server refuses the create",
    })
    public void aTaskIsCapturedClarifiedFiledAndWaitedOn() throws Exception {
        // NOTE: task calendars: a Fastmail calendar made with no component
        // set states VEVENT alone, so it takes no task (x-vtodo none).
        String inbox = scratch(List.of("VEVENT", "VTODO"));
        String other = scratch(List.of("VEVENT", "VTODO"));
        connected();
        CalendarParts parts = phone.calendarParts();
        TaskStore store = new TaskStore(parts);
        TaskWrites writes = new TaskWrites(parts);
        EventStore.StoredCalendar work = calendarOf(phone, inbox);
        EventStore.StoredCalendar home = calendarOf(phone, other);
        assertTrue(
                "a scratch calendar takes tasks (writable " + work.writable + ", refuses tasks "
                        + work.refusesTasks + ", " + work.url + ")",
                TaskStore.takesTasks(work));

        // A capture: CATEGORIES:inbox, CREATED, linked to its mail.
        String mid = "capture." + tag + "@pimalaya-e2e.invalid";
        String uid = writes.capture(work, subject("captured"), null, mid);
        sync();
        String captured = settledObject(inbox, uid);
        assertTrue(captured, captured.contains("CATEGORIES:inbox\r\n"));
        assertTrue(captured, captured.contains("CREATED:"));
        assertTrue(captured, captured.contains("ATTACH:mid:" + mid + "\r\n"));

        // Clarified into Waiting For someone, then into a next action.
        writes.edit(
                taskOf(store, uid),
                TaskEdit.at(TaskWrites.now())
                        .withList(Task.WAITING)
                        .withContact("Ann Smith", "ann@pimalaya-e2e.invalid"));
        sync();
        settles(
                "the server holds the waiting task",
                () -> {
                    String held = objectOf(inbox, uid);
                    return held != null
                                    && held.contains("CATEGORIES:waiting\r\n")
                                    && held.contains(
                                            "CONTACT;ALTREP=\"mailto:ann@pimalaya-e2e.invalid\":Ann"
                                                    + " Smith\r\n")
                            ? null
                            : String.valueOf(held);
                });
        writes.edit(
                taskOf(store, uid),
                TaskEdit.at(TaskWrites.now()).withList(Task.NEXT).withCategories("@errands"));
        String before = taskOf(store, uid).event.ical;
        writes.edit(
                taskOf(store, uid),
                TaskEdit.at(TaskWrites.now()).withList(Task.NEXT).withCategories("@errands"));
        assertEquals("an edit the task holds stages nothing", before, taskOf(store, uid).event.ical);
        sync();
        settles(
                "the server holds the next action",
                () -> {
                    String held = objectOf(inbox, uid);
                    return held != null
                                    && held.contains("CATEGORIES:@errands\r\n")
                                    && !held.toLowerCase(java.util.Locale.ROOT).contains("waiting,")
                                    && !held.contains("CATEGORIES:waiting")
                            ? null
                            : String.valueOf(held);
                });

        // Send and wait: a Waiting For item on a mail just sent.
        String sent = "sent." + tag + "@pimalaya-e2e.invalid";
        String waiting =
                writes.waitFor(
                        work, subject("waiting"), "Ann Smith", "ann@pimalaya-e2e.invalid",
                        "<" + sent + ">");
        sync();
        String filed = settledObject(inbox, waiting);
        assertTrue(filed, filed.contains("CATEGORIES:waiting\r\n"));
        assertTrue(filed, filed.contains("ATTACH:mid:" + sent + "\r\n"));
        assertTrue(filed, filed.contains("CONTACT;ALTREP=\"mailto:ann@pimalaya-e2e.invalid\""));

        // Another device reads it back as the same Waiting For item.
        E2eDevice observer = observer();
        TaskStore.Stored seen = taskOf(new TaskStore(observer.calendarParts()), waiting, observer);
        assertTrue(seen.task.in(Task.WAITING));
        assertEquals("ann@pimalaya-e2e.invalid", seen.task.contactAddress());
        assertEquals(sent, seen.task.source());

        // Filed into the other calendar: one add superseding the source, whose
        // delete waits on it. A server holding one UID per calendar home
        // (Fastmail) refuses the add, and the capture stays where it was.
        writes.moveTo(taskOf(store, uid), home, TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
        Exception first = phone.syncCalendars(address);
        Exception second = phone.syncCalendars(address);
        String inSource = objectOf(inbox, uid);
        String inTarget = objectOf(other, uid);
        System.out.println(
                "e2e " + target.id + ": filing elsewhere left the task "
                        + (inTarget != null ? "in the target" : "in the source")
                        + (first == null ? "" : "; the pass answered " + first.getMessage())
                        + (second == null ? "" : "; the next " + second.getMessage()));
        assertTrue("the task is on the server somewhere", inSource != null || inTarget != null);
        if (inTarget == null) {
            assertNotNull("a refused add keeps the source", inSource);
        } else {
            settles(
                    "the source goes once the add is pushed",
                    () -> {
                        sync();
                        return objectOf(inbox, uid) == null ? null : "still in the source";
                    });
        }
    }

    /** A device's calendar of that name, which must be listed. */
    private EventStore.StoredCalendar calendarOf(E2eDevice device, String name) {
        EventStore.StoredCalendar calendar = device.calendar(address, name);
        assertNotNull("the calendar " + name + " listed", calendar);
        return calendar;
    }

    /** The phone's task of that UID, which must be loaded. */
    private TaskStore.Stored taskOf(TaskStore store, String uid) {
        return taskOf(store, uid, phone);
    }

    private TaskStore.Stored taskOf(TaskStore store, String uid, E2eDevice device) {
        for (TaskStore.Stored stored : store.load(device.context)) {
            if (stored.task.uid().equals(uid)) {
                return stored;
            }
        }
        throw new AssertionError("no task " + uid + " on " + device.context);
    }

    /** The object of a scratch calendar carrying {@code uid}, null for none. */
    private String objectOf(String calendar, String uid) throws Exception {
        for (Map.Entry<String, String> object : items.objects(calendar).entrySet()) {
            if (object.getValue().contains("UID:" + uid + "\r\n")) {
                return object.getValue().replaceAll("\r\n[ \t]", "");
            }
        }
        return null;
    }

    /** The object carrying {@code uid}, once the server holds it. */
    private String settledObject(String calendar, String uid) throws Exception {
        String[] held = new String[1];
        settles(
                "the server holds " + uid + " in " + calendar,
                () -> {
                    held[0] = objectOf(calendar, uid);
                    return held[0] != null ? null : "not yet";
                });
        return held[0];
    }

    /** One calendar pass on the phone, which must not fail. */
    private void sync() {
        Exception failure = phone.syncCalendars(address);
        if (failure != null) {
            throw new AssertionError("the pass failed: " + failure.getMessage(), failure);
        }
    }
}
