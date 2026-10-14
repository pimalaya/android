package org.pimalaya.mail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Which mailboxes the background fill widens next, a chunk at a time.
 *
 * <p>The list reaches the most recent floor, so that mailbox fills first, as a
 * k-way merge draws. A chunk is {@link MailEngine#FILL_CHUNK} messages, never a
 * time span; account-wide steps (Gmail) are not filled.
 */
public final class MailFill {
    private MailFill() {}

    /**
     * The mailbox the fill widens next, null once all are whole within bound.
     *
     * <p>Inbox and sent first, a never-listed mailbox before one lacking older
     * mail, then the most recent floor; the others after on the same terms.
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
     * The mailboxes one fill step widens side by side, empty once all whole.
     *
     * <p>The next in {@link #next}'s order, as many of an account as it runs
     * sessions at once ({@code room}).
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
            if (used < room.applyAsInt(edge.accountEmail)) {
                picked.add(edge);
                taken.put(edge.accountEmail, used + 1);
            }
        }
        return picked;
    }

    /** What a fill step reaches for, so the step itself can be tested. */
    public interface Host {
        /** Whether the fill may go on: foreground, unmetered, no other sync. */
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
         * Widens each mailbox by one fill chunk, blocking until all are done.
         *
         * <p>Every one is tried; the first failure is thrown after.
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
    public enum Step {
        /**
         * A chunk of each mailbox landed; another step follows while allowed.
         */
        AGAIN,
        /**
         * Not allowed now (background, metered, another sync): resumed later.
         */
        PAUSED,
        /** Every mailbox is whole within its bound. */
        DONE,
        /** A widening failed: stopped, resumed later. */
        FAILED,
    }

    /**
     * One fill step: the next mailboxes widened a chunk each ({@link #batch}).
     *
     * <p>Only while allowed. Nothing is held between steps but store coverage,
     * so a stopped fill resumes from the floors reached.
     */
    public static Step step(Host host) {
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
