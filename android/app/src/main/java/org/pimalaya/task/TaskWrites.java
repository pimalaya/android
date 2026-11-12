package org.pimalaya.task;

import org.json.JSONException;

import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.CalendarStager;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.StaleEdit;
import org.pimalaya.calendar.Zones;
import org.pimalaya.client.BodyReplaced;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventDraft;
import org.pimalaya.client.Flags;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;

import java.util.List;
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
        return add(calendar, title, note, source, Task.INBOX, List.of());
    }

    /**
     * Adds {@code title} to {@code calendar} in {@code state}, {@link
     * Task#NEXT} for none, carrying the person's {@code categories}.
     */
    public String add(
            EventStore.StoredCalendar calendar,
            String title,
            String note,
            String source,
            String state,
            List<String> categories)
            throws JSONException {
        TaskEdit edit =
                TaskEdit.at(now())
                        .withList(state)
                        .withText(title, note == null || note.isEmpty() ? null : note);
        if (!categories.isEmpty()) {
            edit = edit.withCategories(String.join(",", categories));
        }
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
     * (pimdir GUIDE §17): the bridge hands its bytes back as they were. The
     * write reaches the to-do by the handle it holds now and names the body
     * it was read as: one a sync replaced since is refused ({@link
     * BodyReplaced}), nothing staged, and {@link #merge} goes on with it.
     */
    public void edit(TaskStore.Stored stored, TaskEdit edit) throws JSONException {
        String read = bodyOf(stored);
        String written = CalendarClient.writeTask(read, edit);
        if (written.equals(read)) {
            return;
        }
        CalendarStager stager = stager();
        String collection = stored.event.collectionId;
        stager.mutateEdit(
                collection,
                stager.offline.handleFor(collection, stored.event.id),
                written,
                null,
                "",
                stored.event.object);
    }

    /**
     * After {@link #edit} was refused: {@code edit} merged three ways onto the
     * to-do as the sync left it ({@link StaleEdit}), staged when the merge
     * asks nothing, else for the entry page's conflict mode; null when the
     * store holds the to-do no more. Off the main thread.
     */
    public StaleEdit merge(TaskStore.Stored stored, TaskEdit edit) throws JSONException {
        StaleEdit stale =
                StaleEdit.of(
                        parts.events(),
                        stored.event,
                        stored.calendar,
                        CalendarClient.writeTask(bodyOf(stored), edit));
        if (stale != null && stale.clean()) {
            stale.stage(stager());
        }
        return stale;
    }

    /**
     * Applies {@code edit} to a stored to-do and files it in {@code target}.
     *
     * <p>One add there superseding the source item (pimdir STORAGE §15.3,
     * GUIDE §17), staged in one transaction with the source's tombstone and
     * the pair: a load holds the tombstone's delete while the add is a
     * pending create, so a create the server refuses leaves the capture on
     * it. The {@code UID} and the link id stay, the link id being the name
     * the target's server is asked to file it by. Refused as {@link #edit}
     * is when a sync replaced the body it was read as.
     */
    public void moveTo(TaskStore.Stored stored, EventStore.StoredCalendar target, TaskEdit edit)
            throws JSONException {
        refuseUnlessTasks(target);
        move(stored.event, target, CalendarClient.writeTask(bodyOf(stored), edit));
    }

    /**
     * After {@link #moveTo} was refused: the edit merged onto the to-do as
     * the sync left it, moved when the merge asks nothing, as {@link #merge}
     * does otherwise.
     */
    public StaleEdit mergeMove(
            TaskStore.Stored stored, EventStore.StoredCalendar target, TaskEdit edit)
            throws JSONException {
        refuseUnlessTasks(target);
        StaleEdit stale =
                StaleEdit.of(
                        parts.events(),
                        stored.event,
                        stored.calendar,
                        CalendarClient.writeTask(bodyOf(stored), edit));
        if (stale != null && stale.clean()) {
            move(stale.fresh, target, stale.merged());
        }
        return stale;
    }

    /** Moves {@code read} into {@code target} as {@code written}, in one go. */
    private void move(EventStore.StoredEvent read, EventStore.StoredCalendar target,
            String written) throws JSONException {
        String collection = read.collectionId;
        String linkId = read.id;
        CalendarStager stager = stager();
        stager.offline.atomically(
                () -> {
                    // NOTE: an add and a remove name no body they were made
                    // over, so the move checks its own, in its transaction.
                    stager.requireHeld(collection, linkId, read.object);
                    stager.mutateAdd(target.id, linkId, written, Flags.none(), null, "");
                    // NOTE: before the remove, both items then being rows: a
                    // source never pushed is withdrawn rather than marked,
                    // and the pair goes with it, there being nothing to hold.
                    stager.offline.supersede(collection, linkId, target.id, linkId);
                    stager.mutateRemove(collection, stager.offline.handleFor(collection, linkId));
                });
    }

    /**
     * The body {@code stored} was read as; refused ({@link BodyReplaced})
     * when the store holds it no more, a list row a sync replaced the body
     * of since it was loaded.
     */
    private static String bodyOf(TaskStore.Stored stored) {
        String read = stored.event.ical();
        if (read.isEmpty() && stored.event.object != null) {
            throw new BodyReplaced(
                    "The task " + stored.event.id + " no longer holds " + stored.event.object);
        }
        return read;
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
     *
     * <p>By the handle it holds now, refused as {@link #edit} is when a sync
     * replaced the body it was read as: the person deletes what they saw.
     */
    public void remove(TaskStore.Stored stored) throws JSONException {
        stager().mutateRemoveOver(
                stored.event.collectionId, stored.event.id, stored.event.object);
    }

    private CalendarStager stager() {
        return new CalendarStager(parts.pimdir(), parts.clients());
    }
}
