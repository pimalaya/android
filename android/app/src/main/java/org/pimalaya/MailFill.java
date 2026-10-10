package org.pimalaya;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Which mailboxes the background fill widens next, a chunk at a time.
 *
 * <p>The merged list reaches down to the most recent floor among the
 * mailboxes it shows: below it, one mailbox's older mail would be listed
 * while another's of the same days is not stored yet. So the fill takes
 * the mailbox whose floor is that limiting one first, the way a k-way merge
 * draws from the input whose head is next.
 *
 * <p>A chunk is a number of messages, never a span of time: a step of the
 * fill widens by {@link MailEngine#fillChunk}.
 */
final class MailFill {
    private MailFill() {}

    /**
     * The mailbox the background fill widens next, null once every one is
     * listed whole within its account's bound.
     *
     * <p>The inbox and the sent mail first, a mailbox never listed before
     * one that only lacks older mail, then the most recent floor among them
     * (the one limiting the list); every other mailbox after both are whole,
     * on the same terms.
     */
    static MailStore.Edge next(List<MailStore.Edge> edges) {
        MailStore.Edge first = pick(edges, true);
        return first != null ? first : pick(edges, false);
    }

    /** The next of the inbox and sent mail, or of every other mailbox. */
    private static MailStore.Edge pick(List<MailStore.Edge> edges, boolean leading) {
        MailStore.Edge best = null;
        for (MailStore.Edge edge : edges) {
            if (leading != isLeading(edge.role)) {
                continue;
            }
            if (!edge.listed) {
                if (best == null || best.listed) {
                    best = edge;
                }
                continue;
            }
            if (edge.limit == null || (best != null && !best.listed)) {
                continue;
            }
            if (best == null || edge.limit.compareTo(best.limit) > 0) {
                best = edge;
            }
        }
        return best;
    }

    private static boolean isLeading(String role) {
        return "inbox".equals(role) || "sent".equals(role);
    }

    /**
     * The mailboxes one step of the fill widens side by side: the next ones
     * in the fill's order ({@link #next}), as many of an account as it runs
     * sessions at once ({@code room}), every unfinished one of an account
     * listed account-wide, whose mailboxes widen one listing together.
     * Empty once every mailbox is whole.
     */
    static List<MailStore.Edge> batch(
            List<MailStore.Edge> edges, ToIntFunction<String> room) {
        List<MailStore.Edge> left = new ArrayList<>(edges);
        List<MailStore.Edge> picked = new ArrayList<>();
        Map<String, Integer> taken = new HashMap<>();
        MailStore.Edge edge;
        while ((edge = next(left)) != null) {
            left.remove(edge);
            int used = taken.getOrDefault(edge.accountEmail, 0);
            if (edge.accountWide || used < room.applyAsInt(edge.accountEmail)) {
                picked.add(edge);
                taken.put(edge.accountEmail, used + 1);
            }
        }
        return picked;
    }

    /** What a fill step reaches for, so the step itself can be tested. */
    interface Host {
        /** Whether the fill may go on: foreground, unmetered, nothing else syncing. */
        boolean allowed();

        /** Every mailbox and how far its chunks have reached, read afresh. */
        List<MailStore.Edge> edges();

        /** Widens one mailbox by one fill chunk, on the calling thread. */
        void widen(MailStore.Edge edge) throws Exception;

        /** How many of an account's mailboxes one step widens side by side. */
        default int room(String accountEmail) {
            return 1;
        }

        /**
         * Widens a step's mailboxes by one fill chunk each, blocking until
         * all of them are done: every one is tried, and the first failure is
         * thrown once they are.
         */
        default void widen(List<MailStore.Edge> edges) throws Exception {
            Exception failure = null;
            for (MailStore.Edge edge : edges) {
                try {
                    widen(edge);
                } catch (Exception error) {
                    android.util.Log.w("pimalaya", "mail fill stopped: " + edge.collection, error);
                    if (failure == null) {
                        failure = error;
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Why a fill step stopped, or that another should follow. */
    enum Step {
        /** One chunk of each mailbox the step took landed; another step follows while allowed. */
        AGAIN,
        /** Not allowed now (background, metered, another sync): resumed later. */
        PAUSED,
        /** Every mailbox is whole within its bound. */
        DONE,
        /** A widening failed: stopped, resumed later. */
        FAILED,
    }

    /**
     * One step of the background fill: the next mailboxes widened by one
     * chunk each, side by side ({@link #batch}), when the fill is still
     * allowed. Nothing is held between steps but what the store covers, so a
     * fill stopped anywhere resumes from the floors it reached.
     */
    static Step step(Host host) {
        if (!host.allowed()) {
            return Step.PAUSED;
        }
        List<MailStore.Edge> edges = batch(host.edges(), host::room);
        if (edges.isEmpty()) {
            return Step.DONE;
        }
        try {
            host.widen(edges);
        } catch (Exception failure) {
            return Step.FAILED;
        }
        return Step.AGAIN;
    }
}
