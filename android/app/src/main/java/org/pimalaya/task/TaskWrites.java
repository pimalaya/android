package org.pimalaya.task;

import org.json.JSONException;

import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.CalendarStager;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.Zones;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventDraft;
import org.pimalaya.client.Flags;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;

import java.util.UUID;

/**
 * The writes the Tasks tab makes, staged as the entry page's are.
 *
 * <p>A patch of the stored VTODO through the bridge, then a staged add or
 * edit the next calendar sync pushes: a capture is on the phone at once,
 * offline included. A state is a category ({@code inbox}, {@code waiting},
 * {@code someday}), so another client reads and sets it too.
 */
public final class TaskWrites {
    private final CalendarParts parts;

    /** The writes over the calendar store. */
    public TaskWrites(CalendarParts parts) {
        this.parts = parts;
    }

    /** Now, as the edit's {@code DTSTAMP} and {@code LAST-MODIFIED}. */
    public static String now() {
        return Zones.utc(System.currentTimeMillis());
    }

    /**
     * Files a new to-do in {@code calendar}, as {@code edit} sets it.
     *
     * <p>Dateless (RFC 5545 3.6.2): a capture is placed on no day until it is
     * clarified into one. Returns its {@code UID}.
     */
    public String create(EventStore.StoredCalendar calendar, TaskEdit edit)
            throws JSONException {
        String uid = UUID.randomUUID().toString();
        String ical =
                CalendarClient.newEvent(new EventDraft("VTODO", uid, edit.stamp(), "", "", ""));
        String written = CalendarClient.writeTask(ical, edit);
        stager().mutateAdd(calendar.id, uid + ".ics", written, Flags.none(), null, "");
        return uid;
    }

    /** Captures {@code title} into {@code calendar}: {@code CATEGORIES:inbox}. */
    public String capture(
            EventStore.StoredCalendar calendar, String title, String note, String source)
            throws JSONException {
        TaskEdit edit =
                TaskEdit.at(now())
                        .withList(Task.INBOX)
                        .withText(title, note == null || note.isEmpty() ? null : note);
        if (source != null && !source.isEmpty()) {
            edit = edit.withSource(source);
        }
        return create(calendar, edit);
    }

    /**
     * Files a Waiting For item on a mail just sent: {@code title}, waiting
     * on {@code name} at {@code address} ({@code CONTACT}), linked to the
     * sent message by {@code mid:}.
     */
    public String waitFor(
            EventStore.StoredCalendar calendar,
            String title,
            String name,
            String address,
            String messageId)
            throws JSONException {
        return create(
                calendar,
                TaskEdit.at(now())
                        .withList(Task.WAITING)
                        .withText(title, null)
                        .withContact(name, address)
                        .withSource(messageId));
    }

    /** Applies {@code edit} to a stored to-do. */
    public void edit(TaskStore.Stored stored, TaskEdit edit) throws JSONException {
        String written = CalendarClient.writeTask(stored.event.ical, edit);
        stager().mutateEdit(stored.event.collectionId, stored.event.handle, written, null, "");
    }

    /**
     * Applies {@code edit} to a stored to-do and files it in {@code target}.
     *
     * <p>An add there, then a removal here once the add was accepted (pimdir
     * GUIDE §17), rather than a move: a refused create throws before the
     * removal, so the capture is never lost. The {@code UID} stays.
     */
    public void moveTo(TaskStore.Stored stored, EventStore.StoredCalendar target, TaskEdit edit)
            throws JSONException {
        String written = CalendarClient.writeTask(stored.event.ical, edit);
        CalendarStager stager = stager();
        stager.mutateAdd(
                target.id, UUID.randomUUID() + ".ics", written, Flags.none(), null, "");
        stager.mutateRemove(stored.event.collectionId, stored.event.handle);
    }

    /**
     * Deletes a stored to-do: a tombstone the next sync pushes, the item
     * restorable from Deleted items until a purge.
     */
    public void remove(TaskStore.Stored stored) throws JSONException {
        stager().mutateRemove(stored.event.collectionId, stored.event.handle);
    }

    private CalendarStager stager() {
        return new CalendarStager(parts.pimdir(), parts.clients());
    }
}
