package org.pimalaya.contact;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.pimdir.PhonePass;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;

/**
 * The phone collection's engine pass, shaped like the server one.
 *
 * <p>Reconciles the hub with the book's raw contacts, hydrates, resolves by
 * three-way merge. Skipped when the spoke is unavailable (no permission, or the
 * book is not mirrored).
 */
public final class ContactPhonePass extends PhonePass {
    /** The phone spoke's adapter; its rows are a conflict's remote side. */
    private final PhoneRemote phone;

    /** The report the running sync tallies into; null outside syncs. */
    private ContactEngine.Report tally;

    /** A phone pass over the store and the bridge. */
    public ContactPhonePass(PimdirDb pimdir, Clients clients) {
        this(pimdir, clients, new PhoneRemote(pimdir.context(), pimdir));
    }

    private ContactPhonePass(PimdirDb pimdir, Clients clients, PhoneRemote phone) {
        super(pimdir, clients, phone, PimdirSummary.CONTACT);
        this.phone = phone;
    }

    /**
     * The book's phone pass, tallying into {@code report}; true if it wrote.
     */
    public boolean sync(String url, ContactEngine.Report report) throws JSONException {
        tally = report;
        return sync(url);
    }

    @Override
    protected boolean settle(String collection) throws JSONException {
        List<JSONObject> conflicts = offline.loadConflicts(collection);
        if (conflicts.isEmpty()) {
            return false;
        }

        step(Progress.STAGE_RESOLVE, conflicts.size());
        for (JSONObject conflict : conflicts) {
            resolvePhoneConflict(collection, conflict);
        }
        return true;
    }

    /**
     * Resolves one phone-conflicted row, always: the three-way result stands.
     *
     * <p>The phone has no field the merge cannot settle. The revision comes
     * from the raw contact, so the push is conditioned on the merged state.
     */
    private void resolvePhoneConflict(String collection, JSONObject conflict)
            throws JSONException {
        String handle = conflict.getString("handle");
        JSONObject remote = phone.read(collection, handle);
        if (remote == null) {
            // NOTE: raw contact vanished mid-conflict; next sync
            // reconciles the removal.
            return;
        }

        String merged =
                ContactClient.mergeCardChanges(
                        conflict.isNull("baseVcard") ? "" : conflict.getString("baseVcard"),
                        conflict.getString("vcard"),
                        remote.getString("body")).vcard();

        if (!remote.isNull("revision")) {
            offline.setConflictRevision(collection, handle, remote.getString("revision"));
        }
        ContactStager.edit(this, collection, handle, merged);
    }

    @Override
    protected void applied(JSONArray effects) throws JSONException {
        super.applied(effects);
        ContactEngine.Report.tallyEffects(tally, effects);
    }

    @Override
    protected void pushed(String spoke, JSONArray changes, JSONArray results)
            throws JSONException {
        ContactEngine.Report.tallyPushes(tally, spoke, changes, results);
    }

    @Override
    protected PimDomain domain() {
        return PimDomain.CONTACTS;
    }
}
