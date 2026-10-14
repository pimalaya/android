package org.pimalaya.contact;

import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Cards;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.Stager;

/** The contacts driver that only stages: card writes, and the phone queue they reach. */
public final class ContactStager extends Stager {
    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

    public ContactStager(PimdirDb pimdir, PimalayaClient client) {
        super(pimdir, client);
        this.pimdir = pimdir;
    }

    /**
     * Stages a content edit on one card through the engine (the next sync
     * pushes it); editing a conflicted placement resolves it.
     *
     * <p>The summary and the key come off the document itself, so a
     * rename moves the card in the list rather than leaving it where the
     * old name put it.
     */
    public void edit(String url, String handle, String vcard) throws JSONException {
        edit(this, url, handle, vcard);
    }

    /** {@link #edit} on any driver: a pass stages its own conflict resolutions. */
    static void edit(Stager on, String url, String handle, String vcard) throws JSONException {
        JSONObject index = Cards.indexCard(vcard);
        on.mutateEdit(
                url,
                handle,
                vcard,
                index.optJSONObject("summary"),
                index.optString("sortKey"));
    }

    @Override
    protected void staged(String collection) {
        staged(pimdir, collection);
    }

    /**
     * A write to a book reaches the phone's contacts within a second
     * ({@link PhoneQueue}); a write to the phone collection is a phone
     * pass's own conflict resolution, pushed by that pass.
     */
    static void staged(PimdirDb pimdir, String collection) {
        if (!CardStore.isPhoneCollection(collection)) {
            PhoneQueue.written(pimdir.context(), collection);
        }
    }
}
