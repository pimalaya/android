package org.pimalaya;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Microsoft Graph account's step ({@link MailStep}): the account's
 * messages newest {@code Date} first below its frontier, read by the
 * bridge in one listing ({@code /me/messages}) and a keyset under it,
 * about {@link #COUNT} of them with every one sharing the last one's
 * {@code Date}, those held at the frontier left out, and their bodies in
 * {@code $batch} calls one after another.
 *
 * <p>The headers come through the band rounds a widening lists: every
 * stepped mailbox is listed down to the oldest {@code Date} the step
 * brought, those holding its messages first, so their coverage floors
 * keep pace with the frontier and the merged list reaches it. The bodies
 * the step read are then stored from its files, never read again.
 *
 * <p>Junk, trash and mailboxes kept whole take no part; a view filtered to
 * one mailbox steps that mailbox.
 */
final class GraphStep implements MailStep {
    @Override
    public Outcome run(
            MailEngine engine,
            String accountEmail,
            List<String> collections,
            String only,
            boolean first)
            throws JSONException {
        Map<String, String> roles = engine.mail().roles();
        List<String> stepped = new ArrayList<>();
        JSONArray names = new JSONArray();
        for (String collection : collections) {
            if ((only == null || only.equals(collection))
                    && !MailOffline.whole(engine.context(), collection)
                    && MailBodies.downloads(roles.get(collection), false)) {
                stepped.add(collection);
                names.put(engine.mailboxOf(collection));
            }
        }
        if (stepped.isEmpty()) {
            return new Outcome(null, true, 0);
        }

        // NOTE: a frontier of all mail leaves nothing older to step.
        JSONObject request = engine.stepRequest(only);
        if (MailWindow.held(engine.context(), engine.accountId()) && request.isNull("frontier")) {
            return new Outcome(null, true, 0);
        }
        request.put("mailboxes", names);

        File dir = engine.scratch();
        try {
            JSONObject step =
                    engine.stepRead(
                            only == null ? null : engine.mailboxOf(only), stepped, request, dir);
            JSONArray items = step.getJSONArray("items");
            boolean exhausted = step.optBoolean("exhausted");

            Map<String, List<JSONObject>> members = new LinkedHashMap<>();
            Map<String, File> files = new HashMap<>();
            List<Brought> brought = new ArrayList<>();
            String oldest = null;
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.getJSONObject(index);
                String sortKey = item.optString("sortKey");
                Long received = item.isNull("received") ? null : item.optLong("received");
                brought.add(new Brought(sortKey, received));
                if (!sortKey.isEmpty() && (oldest == null || sortKey.compareTo(oldest) < 0)) {
                    oldest = sortKey;
                }
                if (item.has("path")) {
                    files.put(item.getString("handle"), new File(item.getString("path")));
                }
                JSONArray mailboxes = item.optJSONArray("mailboxes");
                String mailbox = mailboxes == null ? null : mailboxes.optString(0, null);
                if (mailbox != null) {
                    members.computeIfAbsent(engine.collectionOf(mailbox), key -> new ArrayList<>())
                            .add(item);
                }
            }

            // NOTE: down to the oldest the step brought, an import's date
            // included: held, it shows once the frontier passes it. All of
            // what is left once nothing older is.
            String reach =
                    exhausted ? MailScope.sinceOf(engine.context(), engine.accountId()) : oldest;
            if (oldest != null || exhausted) {
                List<String> order = new ArrayList<>(members.keySet());
                for (String collection : stepped) {
                    if (!order.contains(collection)) {
                        order.add(collection);
                    }
                }
                for (String collection : order) {
                    if (stepped.contains(collection)) {
                        engine.reach(collection, reach);
                    }
                }
            }

            engine.fetched(files);
            try {
                for (Map.Entry<String, List<JSONObject>> mailbox : members.entrySet()) {
                    List<MailBodies.Row> rows = rows(engine, mailbox.getKey(), mailbox.getValue());
                    if (rows.isEmpty()) {
                        continue;
                    }
                    // NOTE: a body that did not land is owed, an import's
                    // below the frontier included.
                    List<String> owed = new ArrayList<>();
                    for (MailBodies.Row failed : engine.download(mailbox.getKey(), rows)) {
                        owed.add(failed.linkId);
                    }
                    MailOwed.owe(engine.context(), mailbox.getKey(), owed);
                }
            } finally {
                engine.fetched(Map.of());
            }
            return new Outcome(MailWindow.frontier(brought, false), exhausted, items.length());
        } finally {
            MailEngine.discard(dir);
        }
    }

    /**
     * The bodies a mailbox's stepped members download, whatever their
     * date: under the link id their band round landed them with, one it
     * did not land (dated below the band) left out.
     */
    private static List<MailBodies.Row> rows(
            MailEngine engine, String collection, List<JSONObject> members) {
        List<String> handles = new ArrayList<>(members.size());
        for (JSONObject member : members) {
            handles.add(member.optString("handle"));
        }
        Map<String, String[]> bound;
        synchronized (PimdirEngine.STORE) {
            bound = engine.offline.bound(collection, handles);
        }
        List<MailBodies.Row> rows = new ArrayList<>(members.size());
        for (JSONObject member : members) {
            String[] link = bound.get(member.optString("handle"));
            if (link == null) {
                continue;
            }
            rows.add(
                    new MailBodies.Row(
                            collection,
                            link[0],
                            member.optString("sortKey"),
                            false,
                            member.isNull("size") ? null : member.optLong("size"),
                            null,
                            true));
        }
        return rows;
    }
}
