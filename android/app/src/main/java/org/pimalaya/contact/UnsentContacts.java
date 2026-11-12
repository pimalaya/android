package org.pimalaya.contact;

import android.app.AlertDialog;
import android.content.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.R;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PushAction;
import org.pimalaya.client.PushPlan;
import org.pimalaya.client.PushPlanFacts;
import org.pimalaya.pimdir.PhoneSpoke;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.ui.Host;

/**
 * The contact changes waiting on this phone, said per contact and book: a
 * pending create its push holds back, planned from the store as the push
 * plans it, and a removal the store holds until the add its move goes to is
 * sent (pimdir STORAGE §15.3). Read from the store alone, so it holds across
 * a restart; shown when a sync ends, as parked rounds are, and again from the
 * contact's row marker.
 */
public final class UnsentContacts {
    /**
     * One change waiting in one book.
     *
     * @param linkId the card's link id
     * @param name the contact's name, as the placement states it
     * @param collection the book it waits in
     * @param target the book a held removal's move goes to; null otherwise
     * @param phone whether its copy was deleted on the phone, which waits
     */
    public record Row(
            String linkId, String name, String collection, String target, boolean phone) {
        /** A change the push holds back. */
        public Row(String linkId, String name, String collection, String target) {
            this(linkId, name, collection, target, false);
        }
    }

    private UnsentContacts() {}

    /** The changes waiting now, by book then contact. */
    public static List<Row> rows(PimdirDb pimdir, Context context) {
        return rows(pimdir, context, new PhoneRemote(context, pimdir));
    }

    /** The same, the phone's deletions read off {@code phone}. */
    static List<Row> rows(PimdirDb pimdir, Context context, PhoneSpoke phone) {
        PimdirStorage offline = new PimdirStorage(pimdir);
        PimdirContacts contacts = new PimdirContacts(pimdir);
        List<Row> rows = new ArrayList<>();
        try {
            for (PimdirCollections.Stored book :
                    new PimdirCollections(pimdir, context).listAll(PimdirSummary.CONTACT)) {
                Set<String> moving = offline.supersedingCreates(book.id);
                for (String[] pending : offline.pendingCreates(book.id)) {
                    if (held(offline, book.id, pending[0], pending[1], moving)) {
                        JSONObject row = live(offline, book.id, pending[0]);
                        if (row != null) {
                            rows.add(new Row(pending[0], nameOf(row), book.id, null));
                        }
                    }
                }
                for (Map.Entry<String, String> move : offline.heldRemovals(book.id).entrySet()) {
                    JSONObject row =
                            offline.loadRow(book.id, offline.handleFor(book.id, move.getKey()));
                    if (row != null) {
                        rows.add(new Row(move.getKey(), nameOf(row), book.id, move.getValue()));
                    }
                }
                String spoke = PimdirStorage.phoneCollection(book.id);
                if (phone.available(spoke)) {
                    for (String handle :
                            ContactPhonePass.waiting(
                                    offline, contacts, spoke, phone.deleted(spoke))) {
                        JSONObject row = offline.loadRow(spoke, handle);
                        rows.add(new Row(row.getString("id"), nameOf(row), book.id, null, true));
                    }
                }
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return rows;
    }

    /** The link ids of the contacts with a change waiting. */
    public static Set<String> linkIds(List<Row> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (Row row : rows) {
            ids.add(row.linkId());
        }
        return ids;
    }

    /** What a set of rows is, to tell whether it changed since shown. */
    public static List<String> keys(List<Row> rows) {
        List<String> keys = new ArrayList<>();
        for (Row row : rows) {
            keys.add(
                    row.collection() + "\n" + row.linkId() + "\n" + row.target() + "\n"
                            + row.phone());
        }
        return keys;
    }

    /** Says what waits, per contact and book. */
    public static void show(Host host, PimdirDb pimdir, List<Row> rows) {
        Map<String, PimdirCollections.Stored> books = new HashMap<>();
        for (PimdirCollections.Stored one :
                new PimdirCollections(pimdir, host).listAll(PimdirSummary.CONTACT)) {
            books.put(one.id, one);
        }
        StringBuilder text = new StringBuilder(host.getString(R.string.sync_unsent_body));
        for (Row row : rows) {
            PimdirCollections.Stored book = books.get(row.collection());
            String name = book == null ? row.collection() : book.name;
            String account = book == null ? "" : book.accountEmail;
            text.append("\n\n");
            if (row.phone()) {
                text.append(host.getString(R.string.sync_unsent_phone, row.name(), name, account));
            } else if (row.target() == null) {
                text.append(host.getString(R.string.sync_unsent_add, row.name(), name, account));
            } else {
                PimdirCollections.Stored to = books.get(row.target());
                text.append(
                        host.getString(
                                R.string.sync_unsent_move,
                                row.name(),
                                name,
                                account,
                                to == null ? row.target() : to.name));
            }
        }
        new AlertDialog.Builder(host)
                .setTitle(R.string.sync_unsent_title)
                .setMessage(text.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Whether the push of a pending create is held back: planned by the
     * bridge from the facts its push would carry (ContactEngine), its edit
     * carried by no other book.
     */
    private static boolean held(
            PimdirStorage offline,
            String collection,
            String linkId,
            String object,
            Set<String> moving)
            throws JSONException {
        PushPlan plan =
                OfflineClient.pushPlan(
                        new PushPlanFacts(
                                PushPlanFacts.Op.ADD,
                                collection,
                                null,
                                offline.hasCardOrigin(collection, linkId, object),
                                false,
                                offline.cardElsewhere(collection, linkId).getBoolean("bound"),
                                moving.contains(linkId)));
        return plan.action() == PushAction.HOLD && !offline.cardCarried(collection, linkId);
    }

    /** The placement's row while it is still live there, else null. */
    private static JSONObject live(PimdirStorage offline, String collection, String linkId)
            throws JSONException {
        JSONObject row = offline.loadRow(collection, offline.handleFor(collection, linkId));
        return row == null || row.optBoolean("deleted") ? null : row;
    }

    /** The contact's name as a placement's body states it. */
    private static String nameOf(JSONObject row) throws JSONException {
        return ContactClient.indexCard(row.getString("vcard")).name();
    }
}
