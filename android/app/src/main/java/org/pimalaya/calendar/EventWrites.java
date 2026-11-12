package org.pimalaya.calendar;

import org.json.JSONException;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventAlarm;
import org.pimalaya.client.EventEdit;
import org.pimalaya.client.EventMerge;
import org.pimalaya.client.EventScope;
import org.pimalaya.client.EventSide;
import org.pimalaya.client.EventSides;
import org.pimalaya.client.EventSplit;
import org.pimalaya.client.Flags;

import java.util.List;
import java.util.Map;

/**
 * What the entry page's save and delete stage, on the io executor.
 *
 * <p>The staging branches behind {@link EventView}'s decision, which stays the
 * page's since it reads the page's state.
 */
final class EventWrites {
    private final CalendarStager engine;

    EventWrites(CalendarStager engine) {
        this.engine = engine;
    }

    /**
     * One save, as the page captured it on the main thread.
     *
     * <p>The scope, the scoped edit, the new series' UID, the entry and its
     * calendar, whether it creates, whether anything beyond the stamp was
     * edited, the conflict it settles with the sides picked, and the alarms it
     * sets, null when it leaves them.
     */
    record Save(
            EventScope scope,
            EventEdit edited,
            String uid,
            EventStore.StoredEvent target,
            EventStore.StoredCalendar calendar,
            boolean isNew,
            boolean rewritten,
            EventStore.StoredConflict settling,
            Map<Integer, EventSide> picks,
            List<EventAlarm> alarms) {}

    /**
     * Stages a save: the series or lone entry, one occurrence, or a split.
     *
     * <p>One occurrence goes through its override; this and following stages
     * the new series' create and the shortened series' edit in one
     * transaction. A conflict's resolution lands on the binding that
     * conflicted, edited or not, or, for sides of a write a sync made stale
     * ({@link StaleEdit}), on the entry as an ordinary edit made over the
     * body the store held. Any other edit reaches the entry by the handle it
     * holds now and names the body it was read as: one a sync replaced since
     * is refused ({@link org.pimalaya.client.BodyReplaced}), nothing staged.
     */
    void save(Save save) throws Exception {
        EventScope scope = save.scope();
        EventEdit edited = save.edited();
        String uid = save.uid();
        EventStore.StoredEvent target = save.target();
        EventStore.StoredCalendar collection = save.calendar();
        boolean isNew = save.isNew();
        EventStore.StoredConflict settling = save.settling();

        String source = sourceOf(save);
        String into = collection.id;
        String handle = target.handle;
        String read = target.object;
        if (settling != null) {
            into = settling.collection;
            handle = settling.handle;
            read = settling.ifMatch;
        } else if (!isNew) {
            handle = engine.offline.handleFor(into, target.id);
        }

        // NOTE: no summary and no key. What an agenda row
        // shows needs the recurrence expansion, which
        // happens at render time against the window being
        // shown, so there is nothing to write here.
        if (scope == EventScope.FOLLOWING) {
            EventSplit split = CalendarClient.splitEvent(source, edited);
            String master = into;
            String held = handle;
            String over = read;
            engine.offline.atomically(
                    () -> {
                        if (split.series() != null) {
                            engine.mutateAdd(
                                    collection.id,
                                    uid + ".ics",
                                    withAlarms(split.series(), save.alarms()),
                                    Flags.none(),
                                    null,
                                    "");
                        }
                        engine.mutateResolve(
                                master,
                                held,
                                withAlarms(split.master(), save.alarms()),
                                null,
                                "",
                                settling == null ? null : settling.resolves,
                                over);
                    });
        } else {
            String written = written(save);
            if (isNew) {
                engine.mutateAdd(
                        collection.id,
                        target.id,
                        written,
                        Flags.none(),
                        null,
                        "");
            } else {
                engine.mutateResolve(
                        into,
                        handle,
                        written,
                        null,
                        "",
                        settling == null ? null : settling.resolves,
                        read);
            }
        }
        if (settling != null && settling.ifMatch == null) {
            settleLeft(engine, settling);
        }
    }

    /**
     * The body a save of the series or lone entry, or of one occurrence,
     * writes: the merge with its sides picked in conflict mode, the page's
     * edit on it, the alarms set. What a page merges onto what a sync left
     * when the store refused the save ({@link StaleEdit}).
     */
    String written(Save save) {
        String source = sourceOf(save);
        String written =
                save.settling() != null && !save.rewritten()
                        ? source
                        : CalendarClient.writeEvent(source, save.edited());
        return withAlarms(written, save.alarms());
    }

    /** The object a save edits: the entry read, or its sides as picked. */
    private static String sourceOf(Save save) {
        EventStore.StoredConflict settling = save.settling();
        if (settling == null) {
            return save.target().ical();
        }
        return CalendarClient.resolveEvent(
                new EventSides(settling.base, settling.local, settling.remote), save.picks());
    }

    /**
     * Stages a resolution the merge settled on its own, as the page's tap
     * does, then what it leaves ({@link #settleLeft}).
     */
    static void settle(CalendarStager on, EventStore.StoredConflict conflict, String ical)
            throws JSONException {
        on.mutateResolve(conflict.collection, conflict.handle, ical, null, "", conflict.resolves);
        settleLeft(on, conflict);
    }

    /**
     * Settles the item conflict a resolution left, when its merge asks
     * nothing (SYNC §9): a source's conflict resolved after another source
     * moved the entry is recorded as the item's, the resolution its diverging
     * body. The page showed that entry as local, so it merges clean, and the
     * entry no longer reads as conflicted once saved.
     */
    private static void settleLeft(CalendarStager on, EventStore.StoredConflict settled)
            throws JSONException {
        for (EventStore.StoredConflict left :
                EventStore.conflictsOf(on.offline, settled.collection)) {
            if (!left.linkId.equals(settled.linkId)) {
                continue;
            }
            EventMerge merge = CalendarClient.mergeEvent(left.base, left.local, left.remote);
            if (merge.resolved()) {
                on.mutateResolve(
                        left.collection, left.handle, merge.ical(), null, "", left.resolves);
            }
        }
    }

    /**
     * {@code ical} with its master's alarms set to {@code alarms}, untouched
     * when null.
     *
     * <p>Alarm by alarm (rs/calendar/alarm.rs): one kept stays byte for byte,
     * one changed has only what moved rewritten, one left out goes.
     */
    private static String withAlarms(String ical, List<EventAlarm> alarms) {
        if (alarms == null) {
            return ical;
        }
        return CalendarClient.applyAlarms(ical, alarms, Zones.utc(System.currentTimeMillis()));
    }

    /**
     * One delete, as the page captured it on the main thread.
     *
     * <p>The scope, the scoped edit naming what goes, the entry and its
     * calendar, and the conflict it settles with the sides picked.
     */
    record Delete(
            EventScope scope,
            EventEdit edit,
            EventStore.StoredEvent target,
            EventStore.StoredCalendar calendar,
            EventStore.StoredConflict settling,
            Map<Integer, EventSide> picks) {}

    /**
     * Stages a delete: all occurrences remove the entry, others trim a series.
     *
     * <p>One or this and following edit what is left of the series, unless
     * nothing is. In conflict mode both land on the binding that conflicted.
     */
    void delete(Delete delete) throws Exception {
        EventScope scope = delete.scope();
        EventEdit edit = delete.edit();
        EventStore.StoredEvent target = delete.target();
        EventStore.StoredCalendar collection = delete.calendar();
        EventStore.StoredConflict settling = delete.settling();
        Map<Integer, EventSide> chosen = delete.picks();

        String left = null;
        if (scope != EventScope.ALL) {
            String source =
                    settling == null
                            ? target.ical()
                            : CalendarClient.resolveEvent(
                                    new EventSides(
                                            settling.base, settling.local, settling.remote),
                                    chosen);
            left = CalendarClient.removeEvent(source, edit);
        }
        // NOTE: a removal staged on the binding that
        // conflicted is the decision on it: the engine
        // gates the delete on the revision it recorded.
        if (left == null && settling != null && settling.ifMatch != null) {
            engine.mutateRemoveOver(settling.collection, settling.linkId, settling.ifMatch);
        } else if (left == null && settling != null) {
            engine.mutateRemove(settling.collection, settling.handle);
        } else if (left == null) {
            deleteWhole(target, collection);
        } else if (settling != null) {
            engine.mutateResolve(
                    settling.collection,
                    settling.handle,
                    left,
                    null,
                    "",
                    settling.resolves,
                    settling.ifMatch);
        } else {
            engine.mutateEdit(
                    collection.id,
                    engine.offline.handleFor(collection.id, target.id),
                    left,
                    null,
                    "",
                    target.object);
        }
    }

    /**
     * Stages an entry's removal, whole: a series goes with every occurrence.
     *
     * <p>Also the list's batch delete, which never holds a conflicted entry:
     * only its page settles one. By the handle the entry holds now, refused
     * when a sync replaced the body it was read as.
     */
    void deleteWhole(EventStore.StoredEvent target, EventStore.StoredCalendar calendar)
            throws Exception {
        engine.mutateRemoveOver(calendar.id, target.id, target.object);
    }
}
