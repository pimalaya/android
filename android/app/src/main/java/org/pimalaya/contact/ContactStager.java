package org.pimalaya.contact;

import org.json.JSONException;
import org.pimalaya.client.CardIndex;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
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
     * Stages a content edit on one card; a conflicted placement resolves.
     *
     * <p>The summary and key come off the document, so a rename moves the card
     * in the list.
     */
    public void edit(String url, String handle, String vcard) throws JSONException {
        edit(this, url, handle, vcard);
    }

    /** {@link #edit} on any driver: a pass stages its own resolutions. */
    static void edit(Stager on, String url, String handle, String vcard) throws JSONException {
        CardIndex index = ContactClient.indexCard(vcard);
        on.mutateEdit(
                url,
                handle,
                vcard,
                index.summary(),
                index.sortKey());
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
