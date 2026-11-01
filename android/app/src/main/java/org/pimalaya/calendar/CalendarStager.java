package org.pimalaya.calendar;

import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Derived;
import org.pimalaya.client.Flags;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.Stager;

/** The calendar driver that only stages: event writes, then the phone queue. */
public final class CalendarStager extends Stager {
    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

    /** A driver that only stages, over the store and the bridge. */
    public CalendarStager(PimdirDb pimdir, Clients clients) {
        super(pimdir, clients);
        this.pimdir = pimdir;
    }

    /**
     * Stages an edit, the summary and sort key its body derives when the
     * caller states none (spec/offline-store.md): a staged to-do is in
     * {@code task_summary} as it is on the phone. An ordinary edit ({@link
     * #mutateEdit}) comes here too, stating no {@code resolves}.
     */
    @Override
    public void mutateResolve(String collection, String handle, String body,
            JSONObject summary, String sortKey, java.util.List<String> resolves)
            throws JSONException {
        Derived derived = summary == null ? CalendarSummaries.of(body) : null;
        super.mutateResolve(
                collection,
                handle,
                body,
                derived == null ? summary : derived.summary(),
                derived == null ? sortKey : derived.sortKey(),
                resolves);
    }

    /** Stages a create, as {@link #mutateResolve} derives its summary. */
    @Override
    public void mutateAdd(
            String collection,
            String linkId,
            String body,
            Flags flags,
            JSONObject summary,
            String sortKey)
            throws JSONException {
        Derived derived = summary == null ? CalendarSummaries.of(body) : null;
        super.mutateAdd(
                collection,
                linkId,
                body,
                flags,
                derived == null ? summary : derived.summary(),
                derived == null ? sortKey : derived.sortKey());
    }

    @Override
    protected void staged(String collection) {
        staged(pimdir, collection);
    }

    /**
     * A write to a calendar reaches the phone's calendar within a second.
     *
     * <p>Through {@link PhoneQueue}; a write to the phone collection is a phone
     * pass's own, pushed by that pass.
     */
    static void staged(PimdirDb pimdir, String collection) {
        if (!PimdirStorage.isPhoneCollection(collection)) {
            PhoneQueue.calendarWritten(pimdir.context(), collection);
        }
    }
}
