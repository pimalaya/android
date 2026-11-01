package org.pimalaya.contact;

import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.CardIndex;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ConflictForm;
import org.pimalaya.client.ContactClient;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.Stager;

/** The contacts driver that only stages: card writes, and the phone queue. */
public final class ContactStager extends Stager {
    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

    /** A stager over the store and the bridge. */
    public ContactStager(PimdirDb pimdir, Clients clients) {
        super(pimdir, clients);
        this.pimdir = pimdir;
    }

    /**
     * Stages an ordinary content edit on one card, which a conflict refuses
     * (SYNC §7): a conflicted card is settled by {@link #resolve} alone.
     *
     * <p>The summary and key come off the document, so a rename moves the card
     * in the list.
     */
    public void edit(String url, String handle, String vcard) throws JSONException {
        resolve(this, url, handle, vcard, null);
    }

    /**
     * Stages a conflict's resolution on one card, naming in {@code resolves}
     * the diverging bodies the form showed ({@link
     * PimdirStorage#resolvesOf}), then settles the item conflict it leaves
     * when its merge asks nothing (SYNC §9).
     *
     * <p>A server conflict resolved after the phone moved the card is recorded
     * as the item's conflict, the resolution its diverging body; the form
     * showed the moved card as local, so it merges clean, naming that body,
     * and the card no longer reads as conflicted once saved.
     */
    public void resolve(String url, String handle, String vcard, List<String> resolves)
            throws JSONException {
        resolve(this, url, handle, vcard, resolves);
        JSONObject left = offline.loadConflict(url, handle);
        if (left == null) {
            return;
        }
        ConflictForm again =
                ContactClient.mergeConflictForm(
                        left.optString("base"), left.optString("local"), left.optString("remote"));
        if (again.resolved()) {
            resolve(this, url, handle, again.vcard(), PimdirStorage.resolvesOf(left));
        }
    }

    /**
     * Stages a card's edit on any driver, a pass staging its own resolutions:
     * a resolution of the conflicts whose diverging bodies {@code resolves}
     * names, an ordinary edit when null.
     */
    static void resolve(Stager on, String url, String handle, String vcard,
            List<String> resolves) throws JSONException {
        CardIndex index = ContactClient.indexCard(vcard);
        on.mutateResolve(
                url,
                handle,
                vcard,
                index.summary(),
                index.sortKey(),
                resolves);
    }

    @Override
    protected void staged(String collection) {
        staged(pimdir, collection);
    }

    /**
     * Wakes the phone pass ({@link PhoneQueue}) after a write to a book.
     *
     * <p>A write to the phone collection is a phone pass's own, pushed by it.
     */
    static void staged(PimdirDb pimdir, String collection) {
        if (!CardStore.isPhoneCollection(collection)) {
            PhoneQueue.written(pimdir.context(), collection);
        }
    }
}
