package org.pimalaya;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A step taken from the store ({@link MailStep}), for a backend that does
 * not step itself yet (Graph, JMAP) and for IMAP's later steps
 * ({@link ImapStep}): while fewer than
 * {@link #COUNT} stored messages lie between the account's frontier and
 * the most recent floor among its mailboxes, the mailbox holding that
 * floor widens by {@link #COUNT}; the frontier then moves to the
 * {@code Date} of the {@link #COUNT}th newest stored message below it, ties
 * included. Their bodies download behind it ({@link MailBodies}).
 *
 * <p>The first sync lists each mailbox's first chunk, one after another on
 * the account's session, and leaves the frontier those chunks reach
 * ({@link MailStore#firstWindow}).
 */
final class WidenStep implements MailStep {
    /** The most mailboxes one step widens, so short chunks cannot hold it. */
    private static final int WIDENINGS = 8;

    @Override
    public Outcome run(
            MailEngine engine,
            String accountEmail,
            List<String> collections,
            String only,
            boolean first) {
        MailStore store = engine.mail();
        if (first) {
            RuntimeException refusal = null;
            int opened = 0;
            for (String collection : collections) {
                try {
                    engine.sync(collection);
                    opened++;
                } catch (RuntimeException failure) {
                    MailEngine.skip(collection, failure);
                    refusal = refusal == null ? failure : refusal;
                }
            }
            if (refusal != null && opened == 0) {
                throw refusal;
            }
            String frontier = store.firstWindow(accountEmail);
            return new Outcome(frontier, frontier == null, 0);
        }

        String window =
                only == null
                        ? MailWindow.since(engine.context(), engine.accountId())
                        : MailWindow.since(engine.context(), engine.accountId(), only);
        if (window == null) {
            return new Outcome(null, true, 0);
        }
        List<String> stepped = new ArrayList<>();
        Map<String, String> roles = store.roles();
        for (String collection : collections) {
            if ((only == null || only.equals(collection))
                    && !MailOffline.whole(engine.context(), collection)
                    && MailBodies.downloads(roles.get(collection), false)) {
                stepped.add(collection);
            }
        }
        MailStore.Query query =
                store.query((account, collection) -> stepped.contains(collection), false, false, "");

        // NOTE: a mailbox the server refuses to open is widened no more in
        // this step (MailEngine.skip), unless every one is.
        List<String> widenable = new ArrayList<>(stepped);
        MailStore.Edge limiting = limiting(store.edges(), widenable);
        for (int widened = 0; widened < WIDENINGS && limiting != null; widened++) {
            if (within(store.datesBelow(query, window, COUNT), limiting.limit) >= COUNT) {
                break;
            }
            try {
                if (!engine.widen(limiting.collection, COUNT)) {
                    break;
                }
            } catch (RuntimeException failure) {
                MailEngine.skip(limiting.collection, failure);
                widenable.remove(limiting.collection);
                if (widenable.isEmpty()) {
                    throw failure;
                }
            }
            limiting = limiting(store.edges(), widenable);
        }

        String floor = limiting == null ? null : limiting.limit;
        List<String> below = store.datesBelow(query, window, COUNT);
        int count = within(below, floor);
        String frontier = count == 0 ? null : below.get(count - 1);
        boolean whole = limiting == null && widenable.size() == stepped.size();
        return new Outcome(frontier, whole && count < COUNT, count);
    }

    /**
     * The mailbox a step widens next, null once every one is listed whole
     * within its bound: one never listed first, else the one holding the
     * most recent floor, below which the list cannot show the others.
     */
    static MailStore.Edge limiting(List<MailStore.Edge> edges, List<String> stepped) {
        MailStore.Edge best = null;
        for (MailStore.Edge edge : edges) {
            if (!stepped.contains(edge.collection)) {
                continue;
            }
            if (!edge.listed) {
                return edge;
            }
            if (edge.limit != null && (best == null || edge.limit.compareTo(best.limit) > 0)) {
                best = edge;
            }
        }
        return best;
    }

    /**
     * How many of some sort keys, newest first, lie at or above
     * {@code floor} (all of them for none): the messages the list shows
     * once the frontier reaches them.
     */
    static int within(List<String> newestFirst, String floor) {
        int count = 0;
        for (String key : newestFirst) {
            if (floor != null && key.compareTo(floor) < 0) {
                break;
            }
            count++;
        }
        return count;
    }
}
