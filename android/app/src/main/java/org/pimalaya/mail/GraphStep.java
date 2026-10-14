package org.pimalaya.mail;

import org.pimalaya.client.GraphStepItem;
import org.pimalaya.client.GraphStepReply;
import org.pimalaya.client.StepRequest;

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
    public Outcome run(MailEngine engine, StepRun run) {
        List<String> collections = run.collections();
        String only = run.only();

        Map<String, String> roles = engine.mail().roles();
        List<String> stepped = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String collection : collections) {
            if ((only == null || only.equals(collection))
                    && !MailOffline.whole(engine.context(), collection)
                    && MailBodies.downloads(roles.get(collection), false)) {
                stepped.add(collection);
                names.add(engine.mailboxOf(collection));
            }
        }
        if (stepped.isEmpty()) {
            return new Outcome(null, true, 0);
        }

        // NOTE: a frontier of all mail leaves nothing older to step.
        StepRequest request = engine.stepRequest(only, names);
        if (MailWindow.held(engine.context(), engine.accountId()) && request.frontier() == null) {
            return new Outcome(null, true, 0);
        }

        File dir = engine.scratch();
        try {
            GraphStepReply step = engine.graphStepRead(stepped, request, dir);
            List<GraphStepItem> items = step.items();
            boolean exhausted = step.exhausted();

            Map<String, List<GraphStepItem>> members = new LinkedHashMap<>();
            Map<String, File> files = new HashMap<>();
            List<Brought> brought = new ArrayList<>();
            String oldest = null;
            for (GraphStepItem item : items) {
                String sortKey = item.sortKey();
                Long received = item.received();
                brought.add(new Brought(sortKey, received));
                if (!sortKey.isEmpty() && (oldest == null || sortKey.compareTo(oldest) < 0)) {
                    oldest = sortKey;
                }
                if (item.path() != null) {
                    files.put(item.handle(), new File(item.path()));
                }
                List<String> mailboxes = item.mailboxes();
                String mailbox = mailboxes.isEmpty() ? null : mailboxes.get(0);
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
                for (Map.Entry<String, List<GraphStepItem>> mailbox : members.entrySet()) {
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
            return new Outcome(MailWindow.frontier(brought, false), exhausted, items.size());
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
            MailEngine engine, String collection, List<GraphStepItem> members) {
        List<String> handles = new ArrayList<>(members.size());
        for (GraphStepItem member : members) {
            handles.add(member.handle());
        }
        Map<String, String[]> bound;
        synchronized (engine.locks.store) {
            bound = engine.offline.bound(collection, handles);
        }
        List<MailBodies.Row> rows = new ArrayList<>(members.size());
        for (GraphStepItem member : members) {
            String[] link = bound.get(member.handle());
            if (link == null) {
                continue;
            }
            rows.add(
                    new MailBodies.Row(
                            collection,
                            link[0],
                            member.sortKey(),
                            false,
                            member.size(),
                            null,
                            true));
        }
        return rows;
    }
}
