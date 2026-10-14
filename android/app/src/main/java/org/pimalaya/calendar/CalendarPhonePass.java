package org.pimalaya.calendar;

import android.util.Log;

import org.json.JSONException;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.EventMerge;
import org.pimalaya.pimdir.PhonePass;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.pimdir.Stager;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The calendar's phone pass: the store reconciled with the calendar's rows.
 *
 * <p>Skipped when the phone hides the calendar or lost the permission, and on
 * the quiet path: no phone change, nothing staged, equal counts (no changes
 * token in CalendarContract). Tasks and journals show nothing.
 */
public final class CalendarPhonePass extends PhonePass {
    /** A phone pass over the store and the bridge. */
    public CalendarPhonePass(PimdirDb pimdir, Clients clients) {
        super(pimdir, clients, new CalendarRemote(pimdir.context(), pimdir), PimdirSummary.CALENDAR);
    }

    /**
     * One calendar's phone pass on its own, offline, outside a sync run.
     *
     * <p>Brings its calendar-app edits into the store and projects the store's
     * onto it ({@link CalendarRows#phonePass}). Answers whether it wrote into
     * the store.
     */
    public static boolean pass(PimdirDb pimdir, String collection) {
        return new CalendarPhonePass(pimdir, Clients.real()).side(collection);
    }

    /**
     * A phone pass a failing provider fails alone, never the sync around it.
     *
     * <p>Answers whether it wrote anything into the store.
     */
    boolean side(String collection) {
        try {
            sync(collection);
        } catch (RuntimeException | JSONException failure) {
            Log.w("pimalaya", "calendar phone pass failed for " + collection, failure);
        }
        return ingested();
    }

    @Override
    protected boolean settle(String spoke) {
        // NOTE: a phone binding both sides changed goes through the same
        // triage as the server's: what the merge settles is pushed to
        // the phone at once, a collision is left to the form.
        return triage(this, count -> step(Progress.STAGE_RESOLVE, count), spoke) > 0;
    }

    /**
     * {@link CalendarEngine#triage} on any driver.
     *
     * <p>{@code on} stages what the merge settles; {@code resolving} is told
     * how many conflicts it looks at.
     */
    static int triage(Stager on, IntConsumer resolving, String collection) {
        List<EventStore.StoredConflict> conflicts;
        synchronized (on.locks.store) {
            conflicts = EventStore.conflictsOf(on.offline, collection);
        }
        if (conflicts.isEmpty()) {
            return 0;
        }

        resolving.accept(conflicts.size());
        int settled = 0;
        for (EventStore.StoredConflict conflict : conflicts) {
            try {
                EventMerge merge =
                        CalendarClient.mergeEvent(conflict.base, conflict.local, conflict.remote);
                if (merge.resolved()) {
                    on.mutateEdit(collection, conflict.handle, merge.ical(), null, "");
                    settled += 1;
                }
            } catch (RuntimeException | JSONException failure) {
                // NOTE: one entry the merge cannot read stays conflicted
                // rather than taking the rest of the pass down.
                Log.w("pimalaya", "calendar triage failed: " + conflict.handle, failure);
            }
        }
        return settled;
    }

    @Override
    protected PimDomain domain() {
        return PimDomain.CALENDAR;
    }
}
