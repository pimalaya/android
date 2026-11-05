package org.pimalaya.mail;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which mailbox the background fill lists next, a chunk at a time.
 *
 * <p>Every mailbox never listed first, so the account's first chunks all land
 * before any fill chunk; then the list reaches the most recent floor, so that
 * mailbox fills first, as a k-way merge draws. A chunk is {@link
 * MailEngine#FILL_CHUNK} messages, never a time span; account-wide steps
 * (Gmail, JMAP) are not filled.
 */
public final class MailFill {
    private MailFill() {}

    /**
     * The mailbox the fill lists next, null once all are whole within bound.
     *
     * <p>A mailbox never listed first, the inbox and the sent mail before the
     * others; then the inbox and the sent mail, then the others, each by the
     * most recent floor.
     */
    static MailStore.Edge next(List<MailStore.Edge> edges) {
        for (String role : new String[] {"inbox", "sent", null}) {
            for (MailStore.Edge edge : edges) {
                boolean kind = role == null ? !isLeading(edge.role) : role.equals(edge.role);
                if (kind && !edge.listed) {
                    return edge;
                }
            }
        }
        MailStore.Edge first = deepest(edges, true);
        return first != null ? first : deepest(edges, false);
    }

    /** The listed mailbox holding the most recent floor, of a kind. */
    private static MailStore.Edge deepest(List<MailStore.Edge> edges, boolean leading) {
        MailStore.Edge best = null;
        for (MailStore.Edge edge : edges) {
            if (leading != isLeading(edge.role) || !edge.listed || edge.limit == null) {
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

    /** What a fill queue reads, so the queue itself can be tested. */
    public interface Host {
        /** Whether the fill may go on: foreground, unmetered, no other sync. */
        boolean allowed();

        /** The account's mailboxes and how far their chunks reached, afresh. */
        List<MailStore.Edge> edges();
    }

    /** How a fill queue ended. */
    public enum Step {
        /** Not allowed now (background, metered, another sync): resumed later. */
        PAUSED,
        /** Every mailbox is whole within its bound. */
        DONE,
        /** A chunk failed: stopped, resumed later. */
        FAILED,
    }

    /**
     * One account's listing queue, which its listing jobs take from as each
     * is free (Older mail fills in behind).
     *
     * <p>A mailbox under way is never taken twice, and goes back in the queue
     * once its chunk is written, read afresh from the store's floors; a
     * failed chunk stops the queue. Nothing is held but store coverage, so a
     * stopped fill resumes from the floors reached.
     */
    public static final class Queue {
        private final Host host;

        /** The mailboxes a job holds, by collection. */
        private final Set<String> busy = new HashSet<>();

        private boolean failed;

        public Queue(Host host) {
            this.host = host;
        }

        /**
         * The mailbox a free job lists next, null once there is none for it:
         * the fill paused or failed, every other mailbox under way or whole.
         */
        public synchronized MailStore.Edge take() {
            if (failed || !host.allowed()) {
                return null;
            }
            List<MailStore.Edge> edges = host.edges();
            edges.removeIf(edge -> busy.contains(edge.collection));
            MailStore.Edge edge = next(edges);
            if (edge != null) {
                busy.add(edge.collection);
            }
            return edge;
        }

        /** A job's mailbox is back, its chunk written or failed. */
        public synchronized void done(String collection, boolean landed) {
            busy.remove(collection);
            if (!landed) {
                failed = true;
            }
        }

        /** How the queue ended, once its jobs are done. */
        public synchronized Step outcome() {
            if (failed) {
                return Step.FAILED;
            }
            if (!host.allowed()) {
                return Step.PAUSED;
            }
            return next(host.edges()) == null ? Step.DONE : Step.PAUSED;
        }
    }
}
