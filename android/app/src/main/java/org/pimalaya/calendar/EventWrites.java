package org.pimalaya.calendar;

import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventEdit;
import org.pimalaya.client.EventScope;
import org.pimalaya.client.EventSide;
import org.pimalaya.client.EventSides;
import org.pimalaya.client.EventSplit;
import org.pimalaya.client.Flags;

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
     * edited, and the conflict it settles with the sides picked.
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
            Map<Integer, EventSide> picks) {}

    /**
     * Stages a save: the series or lone entry, one occurrence, or a split.
     *
     * <p>One occurrence goes through its override; this and following stages
     * the new series' create before the shortened series' edit. A conflict's
     * resolution lands on the binding that conflicted, edited or not.
     */
    void save(Save save) throws Exception {
        EventScope scope = save.scope();
        EventEdit edited = save.edited();
        String uid = save.uid();
        EventStore.StoredEvent target = save.target();
        EventStore.StoredCalendar collection = save.calendar();
        boolean isNew = save.isNew();
        boolean rewritten = save.rewritten();
        EventStore.StoredConflict settling = save.settling();
        Map<Integer, EventSide> chosen = save.picks();

        String source = target.ical;
        String into = collection.id;
        String handle = target.handle;
        if (settling != null) {
            source =
                    CalendarClient.resolveEvent(
                            new EventSides(settling.base, settling.local, settling.remote),
                            chosen);
            into = settling.collection;
            handle = settling.handle;
        }

        // NOTE: no summary and no key. What an agenda row
        // shows needs the recurrence expansion, which
        // happens at render time against the window being
        // shown, so there is nothing to write here.
        if (scope == EventScope.FOLLOWING) {
            EventSplit split = CalendarClient.splitEvent(source, edited);
            if (split.series() != null) {
                engine.mutateAdd(
                        collection.id,
                        uid + ".ics",
                        split.series(),
                        Flags.none(),
                        null,
                        "");
            }
            engine.mutateEdit(into, handle, split.master(), null, "");
        } else {
            String written =
                    settling != null && !rewritten
                            ? source
                            : CalendarClient.writeEvent(source, edited);
            if (isNew) {
                engine.mutateAdd(
                        collection.id,
                        target.id,
                        written,
                        Flags.none(),
                        null,
                        "");
            } else {
                engine.mutateEdit(into, handle, written, null, "");
            }
        }
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
                            ? target.ical
                            : CalendarClient.resolveEvent(
                                    new EventSides(
                                            settling.base, settling.local, settling.remote),
                                    chosen);
            left = CalendarClient.removeEvent(source, edit);
        }
        // NOTE: a removal staged on the binding that
        // conflicted is the decision on it: the engine
        // gates the delete on the revision it recorded.
        if (left == null && settling != null) {
            engine.mutateRemove(settling.collection, settling.handle);
        } else if (left == null) {
            engine.mutateRemove(collection.id, target.handle);
        } else if (settling != null) {
            engine.mutateEdit(
                    settling.collection, settling.handle, left, null, "");
        } else {
            engine.mutateEdit(collection.id, target.handle, left, null, "");
        }
    }
}
