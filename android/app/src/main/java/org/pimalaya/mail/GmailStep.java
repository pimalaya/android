package org.pimalaya.mail;

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
 * A Gmail account's step ({@link MailStep}): the ids of the account's
 * listing (spam and trash left out), or of the one label a view shows,
 * that its store does not hold, newest received first, read whole in
 * batches by the bridge; each message lands in every mailbox its labels
 * name, its body read once and linked into the others.
 *
 * <p>A set difference rather than a date: an imported message dated
 * years before it arrived, a gap a failure left, a message stored from a
 * search, none of them throws the step off. The frontier it leaves skips
 * a message dated a day or more before its reception ({@link
 * MailWindow#frontier}), and the oldest reception it brought is where a
 * round over a label lists from once the history has expired ({@link
 * MailWindow#reach}).
 *
 * <p>A JMAP account steps through here too, one {@code Email/query} over the
 * account whose {@code mailboxIds} place each message; its query starts after
 * the anchors the last step left ({@link MailWindow#anchors}), kept once
 * the step landed.
 */
final class GmailStep implements MailStep {
    @Override
    public Outcome run(
            MailEngine engine,
            String accountEmail,
            List<String> collections,
            String only,
            boolean first)
            throws JSONException {
        // NOTE: an account step names the mailboxes it takes, which a JMAP
        // account's query keeps to (Gmail lists All Mail whatever).
        JSONObject request = engine.stepRequest(only);
        if (only == null) {
            JSONArray names = new JSONArray();
            for (String collection : collections) {
                if (!collection.equals(MailStore.allMailOf(engine.accountId()))) {
                    names.put(engine.mailboxOf(collection));
                }
            }
            request.put("mailboxes", names);
        }
        File dir = engine.scratch();
        try {
            JSONObject step =
                    engine.stepRead(
                            only == null ? null : engine.mailboxOf(only),
                            collections,
                            request,
                            dir);
            JSONArray items = step.getJSONArray("items");
            Map<String, JSONArray> members = new LinkedHashMap<>();
            for (String collection : collections) {
                members.put(collection, new JSONArray());
            }
            // NOTE: every message lands in All Mail where the step holds by
            // it, an archived one there alone, so the next step finds it
            // held and does not read it again (PimdirCollections.ALL_MAIL).
            String allMail = MailStore.allMailOf(engine.accountId());
            JSONArray all = members.get(allMail);
            Map<String, File> files = new HashMap<>();
            Map<String, Long> sizes = new HashMap<>();
            List<Brought> brought = new ArrayList<>();
            Long reach = null;
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.getJSONObject(index);
                String handle = item.getString("handle");
                files.put(handle, new File(item.getString("path")));
                sizes.put(handle, item.optLong("size"));
                Long received = item.isNull("received") ? null : item.getLong("received");
                brought.add(new Brought(item.optString("sortKey"), received));
                if (received != null && (reach == null || received < reach)) {
                    reach = received;
                }
                if (all != null) {
                    all.put(named(item));
                }
                JSONArray mailboxes = item.optJSONArray("mailboxes");
                for (int at = 0; mailboxes != null && at < mailboxes.length(); at++) {
                    JSONArray into = members.get(engine.collectionOf(mailboxes.getString(at)));
                    if (into != null) {
                        into.put(named(item));
                    }
                }
            }

            // NOTE: the first step opens every mailbox, the trash and the
            // junk included, so the next pass is a delta in each.
            String checkpoint = step.getString("historyId");
            for (Map.Entry<String, JSONArray> mailbox : members.entrySet()) {
                if (first || mailbox.getValue().length() > 0) {
                    engine.land(mailbox.getKey(), mailbox.getValue(), checkpoint);
                }
            }
            engine.fetched(files);
            try {
                for (Map.Entry<String, JSONArray> mailbox : members.entrySet()) {
                    // NOTE: All Mail holds no body, being shown nowhere.
                    if (mailbox.getKey().equals(allMail)) {
                        continue;
                    }
                    List<MailBodies.Row> rows = rows(mailbox.getKey(), mailbox.getValue(), sizes);
                    if (rows.isEmpty()) {
                        continue;
                    }
                    // NOTE: a body that did not land is owed, so the body
                    // step brings it whatever its date.
                    List<String> owed = new ArrayList<>();
                    for (MailBodies.Row failed : engine.download(mailbox.getKey(), rows)) {
                        owed.add(failed.linkId);
                    }
                    MailOwed.owe(engine.context(), mailbox.getKey(), owed);
                }
            } finally {
                engine.fetched(Map.of());
            }

            if (reach != null) {
                MailWindow.reach(engine.context(), engine.accountId(), reach);
            }
            MailWindow.anchor(
                    engine.context(),
                    engine.accountId(),
                    only == null ? null : engine.mailboxOf(only),
                    step.optJSONArray("anchors"));
            return new Outcome(
                    MailWindow.frontier(brought, step.optBoolean("byReception")),
                    step.optBoolean("exhausted"),
                    items.length());
        } finally {
            MailEngine.discard(dir);
        }
    }

    /** A step's item as a listing names it, its file and labels left out. */
    private static JSONObject named(JSONObject item) throws JSONException {
        JSONObject named = new JSONObject();
        for (String key : new String[] {"handle", "flags", "linkId", "summary", "sortKey"}) {
            if (item.has(key)) {
                named.put(key, item.get(key));
            }
        }
        return named;
    }

    /**
     * The bodies a mailbox's members download, whatever their date: under
     * the link id they landed with, a sent copy's being its create's.
     */
    private static List<MailBodies.Row> rows(
            String collection, JSONArray members, Map<String, Long> sizes)
            throws JSONException {
        List<MailBodies.Row> rows = new ArrayList<>(members.length());
        for (int index = 0; index < members.length(); index++) {
            JSONObject member = members.getJSONObject(index);
            rows.add(
                    new MailBodies.Row(
                            collection,
                            member.getString("linkId"),
                            member.optString("sortKey"),
                            false,
                            sizes.get(member.getString("handle")),
                            null,
                            true));
        }
        return rows;
    }
}
