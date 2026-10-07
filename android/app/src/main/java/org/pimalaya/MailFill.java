package org.pimalaya;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which mailboxes widen next, a chunk at a time: what a scroll past the
 * merged list's floor asks for, and what the background fill runs.
 *
 * <p>The merged list reaches down to the most recent floor among the
 * mailboxes it shows: below it, one mailbox's older mail would be listed
 * while another's of the same days is not stored yet. So a widening takes
 * the mailboxes whose floor is that limiting one, the way a k-way merge
 * draws from the input whose head is next, and the list reaches down to
 * whichever floor limits it after them.
 *
 * <p>A chunk is a number of messages, never a span of time: a scroll widens
 * each limiting mailbox by {@link MailEngine#FIRST_CHUNK}, a step of the
 * fill by {@link MailEngine#FILL_CHUNK}.
 */
final class MailFill {
    private MailFill() {}

    /**
     * The mailboxes a scroll past the list's floor widens: every shown one
     * whose floor is the most recent of the shown floors. Empty when no
     * shown mailbox limits the list.
     */
    static List<MailStore.Edge> limiting(List<MailStore.Edge> edges, Predicate<String> shown) {
        String limit = null;
        for (MailStore.Edge edge : edges) {
            if (shown.test(edge.collection)
                    && edge.limit != null
                    && (limit == null || edge.limit.compareTo(limit) > 0)) {
                limit = edge.limit;
            }
        }
        List<MailStore.Edge> limiting = new ArrayList<>();
        for (MailStore.Edge edge : edges) {
            if (limit != null && shown.test(edge.collection) && limit.equals(edge.limit)) {
                limiting.add(edge);
            }
        }
        return limiting;
    }

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

    /** What a fill step reaches for, so the step itself can be tested. */
    interface Host {
        /** Whether the fill may go on: foreground, unmetered, nothing else syncing. */
        boolean allowed();

        /** Every mailbox and how far its chunks have reached, read afresh. */
        List<MailStore.Edge> edges();

        /** Widens one mailbox by one fill chunk, on the calling thread. */
        void widen(MailStore.Edge edge) throws Exception;
    }

    /** Why a fill step stopped, or that another should follow. */
    enum Step {
        /** One chunk landed; another step follows while allowed. */
        AGAIN,
        /** Not allowed now (background, metered, another sync): resumed later. */
        PAUSED,
        /** Every mailbox is whole within its bound. */
        DONE,
        /** A widening failed: stopped, resumed later. */
        FAILED,
    }

    /**
     * One step of the background fill: the next mailbox widened by one
     * chunk, when the fill is still allowed. Nothing is held between steps
     * but what the store covers, so a fill stopped anywhere resumes from
     * the floors it reached.
     */
    static Step step(Host host) {
        if (!host.allowed()) {
            return Step.PAUSED;
        }
        MailStore.Edge edge = next(host.edges());
        if (edge == null) {
            return Step.DONE;
        }
        try {
            host.widen(edge);
        } catch (Exception failure) {
            android.util.Log.w("pimalaya", "mail fill stopped: " + edge.collection, failure);
            return Step.FAILED;
        }
        return Step.AGAIN;
    }
}
