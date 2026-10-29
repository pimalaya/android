package org.pimalaya.task;

import org.json.JSONException;

import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.CalendarStager;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.Zones;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventDraft;
import org.pimalaya.client.Flags;
import org.pimalaya.client.PimalayaException;
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
        refuseUnlessTasks(calendar);
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

    /**
     * Applies {@code edit} to a stored to-do.
     *
     * <p>Nothing is staged when the to-do already holds what the edit writes
     * (pimdir GUIDE §17): the bridge hands its bytes back as they were.
     */
    public void edit(TaskStore.Stored stored, TaskEdit edit) throws JSONException {
        String written = CalendarClient.writeTask(stored.event.ical, edit);
        if (written.equals(stored.event.ical)) {
            return;
        }
        stager().mutateEdit(stored.event.collectionId, stored.event.handle, written, null, "");
    }

    /**
     * Applies {@code edit} to a stored to-do and files it in {@code target}.
     *
     * <p>One add there superseding the source item (pimdir STORAGE §15.3,
     * GUIDE §17), staged in one transaction with the source's tombstone and
     * the pair: a load holds the tombstone's delete while the add is a
     * pending create, so a create the server refuses leaves the capture on
     * it. The {@code UID} and the link id stay, the link id being the name
     * the target's server is asked to file it by.
     */
    public void moveTo(TaskStore.Stored stored, EventStore.StoredCalendar target, TaskEdit edit)
            throws JSONException {
        refuseUnlessTasks(target);
        String written = CalendarClient.writeTask(stored.event.ical, edit);
        String linkId = stored.event.id;
        CalendarStager stager = stager();
        stager.offline.atomically(
                () -> {
                    stager.mutateAdd(target.id, linkId, written, Flags.none(), null, "");
                    // NOTE: before the remove, both items then being rows: a
                    // source never pushed is withdrawn rather than marked,
                    // and the pair goes with it, there being nothing to hold.
                    stager.offline.supersede(
                            stored.event.collectionId, stored.event.id, target.id, linkId);
                    stager.mutateRemove(stored.event.collectionId, stored.event.handle);
                });
    }

    /**
     * Refuses a write of a to-do into a calendar taking none (spec/tasks.md),
     * before anything is staged.
     */
    private static void refuseUnlessTasks(EventStore.StoredCalendar calendar) {
        if (!TaskStore.takesTasks(calendar)) {
            throw new PimalayaException("The calendar " + calendar.name + " takes no tasks");
        }
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
