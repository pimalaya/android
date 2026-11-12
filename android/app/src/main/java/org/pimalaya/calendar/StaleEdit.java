package org.pimalaya.calendar;

import org.json.JSONException;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.EventMerge;

/**
 * A write a sync made stale, merged three ways onto what the sync left
 * (spec/conflicts.md): the body the page read the base, the body its write
 * would have staged the local side, the entry as the store holds it now the
 * remote one.
 *
 * <p>Lists merge as sets and a change only one side made flows in (the
 * bridge's merge, rs/calendar/conflict.rs), so only a field both sides set
 * apart is asked, on the entry page's conflict mode.
 */
public final class StaleEdit {
    /** The entry as the store holds it now. */
    public final EventStore.StoredEvent fresh;

    /** The calendar holding it. */
    final EventStore.StoredCalendar calendar;

    /** The three sides, settled by an edit made over the fresh body. */
    final EventStore.StoredConflict sides;

    /** Their merge: the object a page opens on, and what is left to ask. */
    final EventMerge merge;

    private StaleEdit(
            EventStore.StoredEvent fresh,
            EventStore.StoredCalendar calendar,
            EventStore.StoredConflict sides,
            EventMerge merge) {
        this.fresh = fresh;
        this.calendar = calendar;
        this.sides = sides;
        this.merge = merge;
    }

    /**
     * {@code written}, made over {@code read}, merged onto the entry as the
     * store holds it now; null when it holds it no more. Off the main thread.
     */
    public static StaleEdit of(
            EventStore events,
            EventStore.StoredEvent read,
            EventStore.StoredCalendar calendar,
            String written) {
        EventStore.StoredEvent fresh = events.loadEvent(read.collectionId, read.id);
        if (fresh == null) {
            return null;
        }
        String remote = fresh.ical();
        EventMerge merge = CalendarClient.mergeEvent(read.ical(), written, remote);
        EventStore.StoredConflict sides =
                new EventStore.StoredConflict(
                        fresh.collectionId,
                        fresh.handle,
                        fresh.id,
                        read.ical(),
                        written,
                        remote,
                        null,
                        fresh.object);
        return new StaleEdit(fresh, calendar, sides, merge);
    }

    /** Whether the merge asks nothing: {@link #stage} writes it whole. */
    public boolean clean() {
        return merge.resolved();
    }

    /** The merged object, a clean merge's body. */
    public String merged() {
        return merge.ical();
    }

    /** Stages the clean merge, made over the body the store holds now. */
    public void stage(CalendarStager on) throws JSONException {
        String collection = fresh.collectionId;
        on.mutateEdit(
                collection,
                on.offline.handleFor(collection, fresh.id),
                merge.ical(),
                null,
                "",
                fresh.object);
    }
}
