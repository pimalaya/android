package org.pimalaya.calendar;

import org.json.JSONArray;
import org.pimalaya.client.EventSplit;
import org.pimalaya.client.PimalayaClient;

import java.util.Map;

/**
 * What the entry page's save and delete stage, on the io executor: the
 * staging branches behind {@link EventView}'s decision, which stay the
 * page's (they read its state).
 */
final class EventWrites {
    private final PimalayaClient client;
    private final CalendarStager engine;

    EventWrites(PimalayaClient client, CalendarStager engine) {
        this.client = client;
        this.engine = engine;
    }

    /**
     * One save, as the page captured it on the main thread: the scope, the
     * scoped edit, the new series' UID, the entry and its calendar, whether
     * it is a create, whether anything beyond the stamp was edited, and the
     * conflict it settles with the sides picked.
     */
    record Save(
            String scope,
            String edited,
            String uid,
            EventStore.StoredEvent target,
            EventStore.StoredCalendar calendar,
            boolean isNew,
            boolean rewritten,
            EventStore.StoredConflict settling,
            Map<Integer, String> picks) {}

    /**
     * Stages a save: the series or the lone entry, one occurrence through
     * its override, or this and following by splitting the series, the new
     * series' create staged before the shortened series' edit. In conflict
     * mode the resolution is staged on the binding that conflicted, as it
     * is when nothing was edited beside it.
     */
    void save(Save save) throws Exception {
        String scope = save.scope();
        String edited = save.edited();
        String uid = save.uid();
        EventStore.StoredEvent target = save.target();
        EventStore.StoredCalendar collection = save.calendar();
        boolean isNew = save.isNew();
        boolean rewritten = save.rewritten();
        EventStore.StoredConflict settling = save.settling();
        Map<Integer, String> chosen = save.picks();

        String source = target.ical;
        String into = collection.id;
        String handle = target.handle;
        if (settling != null) {
            source =
                    client.resolveEvent(
                            settling.base, settling.local, settling.remote, chosen);
            into = settling.collection;
            handle = settling.handle;
        }

        // NOTE: no summary and no key. What an agenda row
        // shows needs the recurrence expansion, which
        // happens at render time against the window being
        // shown, so there is nothing to write here.
        if ("following".equals(scope)) {
            EventSplit split = client.splitEvent(source, edited);
            if (split.series != null) {
                engine.mutateAdd(
                        collection.id,
                        uid + ".ics",
                        split.series,
                        new JSONArray(),
                        null,
                        "");
            }
            engine.mutateEdit(into, handle, split.master, null, "");
        } else {
            String written =
                    settling != null && !rewritten
                            ? source
                            : client.writeEvent(source, edited);
            if (isNew) {
                engine.mutateAdd(
                        collection.id,
                        target.id,
                        written,
                        new JSONArray(),
                        null,
                        "");
            } else {
                engine.mutateEdit(into, handle, written, null, "");
            }
        }
    }

    /**
     * Stages a delete: all occurrences remove the entry, one or this and
     * following edit what is left of the series, unless nothing is. In
     * conflict mode both are staged on the binding that conflicted.
     */
    void delete(
            String scope,
            String edit,
            EventStore.StoredEvent target,
            EventStore.StoredCalendar collection,
            EventStore.StoredConflict settling,
            Map<Integer, String> chosen)
            throws Exception {
        String left = null;
        if (!"all".equals(scope)) {
            String source =
                    settling == null
                            ? target.ical
                            : client.resolveEvent(
                                    settling.base,
                                    settling.local,
                                    settling.remote,
                                    chosen);
            left = client.removeEvent(source, edit);
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
