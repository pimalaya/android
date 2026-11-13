package org.pimalaya.calendar;

import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.BodyReplaced;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Derived;
import org.pimalaya.client.Flags;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
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
            JSONObject summary, String sortKey, java.util.List<String> resolves,
            String ifMatch) throws JSONException {
        Derived derived = summary == null ? CalendarSummaries.of(body) : null;
        super.mutateResolve(
                collection,
                handle,
                body,
                derived == null ? summary : derived.summary(),
                derived == null ? sortKey : derived.sortKey(),
                resolves,
                ifMatch);
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

    /**
     * Stages the removal of the entry {@code linkId} by the handle it holds
     * now, refused as an edit is ({@link BodyReplaced}) when it no longer
     * holds {@code object}, the body it was read as; null checks nothing.
     */
    public void mutateRemoveOver(String collection, String linkId, String object)
            throws JSONException {
        mutateRemoveOver(collection, linkId, object, null);
    }

    /**
     * {@link #mutateRemoveOver(String, String, String)}, settling the
     * conflicts it meets when {@code resolves} names the diverging body of
     * each, a person's resolution by deleting (SYNC §7); null for none.
     */
    public void mutateRemoveOver(String collection, String linkId, String object,
            java.util.List<String> resolves) throws JSONException {
        offline.atomically(
                () ->
                        mutateRemove(
                                collection,
                                offline.handleFor(collection, linkId),
                                object,
                                resolves));
    }

    /**
     * Refuses ({@link BodyReplaced}) when the entry {@code linkId} no longer
     * holds {@code object}; null checks nothing.
     *
     * <p>For a write the engine takes no {@code if_match} on (a move's add):
     * called in the transaction that stages it.
     */
    public void requireHeld(String collection, String linkId, String object) {
        if (object == null) {
            return;
        }
        PimdirItems items = new PimdirItems(pimdir);
        String held = items.objectOf(items.readable(), collection, linkId);
        if (!object.equals(held)) {
            throw new BodyReplaced(
                    "The entry " + linkId + " was read as " + object + " and holds " + held);
        }
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
