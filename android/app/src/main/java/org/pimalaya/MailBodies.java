package org.pimalaya;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which bodies download without being opened, and the step that downloads
 * them: each account's window ({@link MailWindow}) and its mailboxes kept
 * whole ({@link MailOffline}), carried out after each pass and fill step
 * while the app is open, never as periodic work.
 *
 * <p>A body is raised to {@code Full} through the engine's upgrade
 * ({@link MailEngine#download}), which links a body the store already holds
 * under the same link id rather than fetching it again. Newest first, the
 * shown mailboxes before the others. On a metered network only bodies up to
 * {@link #CAP} download; the others wait for an unmetered one.
 */
final class MailBodies {
    private MailBodies() {}

    /** Bodies one step downloads per account, before checking it may go on. */
    static final int CHUNK = 24;

    /**
     * The largest body a metered network downloads, in bytes: enough for
     * the text of recent mail, a guess to measure on real accounts.
     */
    static final long CAP = 256 * 1024;

    /** One stored message, as the plan reads it. */
    static final class Row {
        final String collection;
        final String linkId;

        /** The {@code Date} it sorts by, empty when it has none. */
        final String sortKey;

        /** Whether the store holds its body already. */
        final boolean bodied;

        /** Its size in bytes, null where the store does not know it. */
        final Long size;

        /** Its attachment mark: 1 with, 0 without, null never examined. */
        final Integer attachment;

        Row(
                String collection,
                String linkId,
                String sortKey,
                boolean bodied,
                Long size,
                Integer attachment) {
            this.collection = collection;
            this.linkId = linkId;
            this.sortKey = sortKey == null ? "" : sortKey;
            this.bodied = bodied;
            this.size = size;
            this.attachment = attachment;
        }
    }

    /**
     * Whether a mailbox's bodies download: all of them for one kept whole,
     * its window's for any other but the junk and the trash, whose mail is
     * not worth the bytes.
     */
    static boolean downloads(String role, boolean whole) {
        return whole || !("junk".equals(role) || "trash".equals(role));
    }

    /**
     * Whether the body step takes one message: dated on or after its
     * account's window ({@code window}, null for all mail; an undated one
     * only then) in a mailbox it downloads, or in a mailbox kept whole.
     */
    static boolean takes(String sortKey, String window, String role, boolean whole) {
        return whole
                || (downloads(role, false)
                        && (window == null
                                || (!sortKey.isEmpty() && sortKey.compareTo(window) >= 0)));
    }

    /** The rows of {@code newestFirst} whose body is not held yet. */
    static List<Row> wanted(List<Row> newestFirst) {
        List<Row> wanted = new ArrayList<>();
        for (Row row : newestFirst) {
            if (!row.bodied) {
                wanted.add(row);
            }
        }
        return wanted;
    }

    /** Two newest-first lists as one, newest first. */
    static List<Row> merged(List<Row> left, List<Row> right) {
        List<Row> merged = new ArrayList<>(left.size() + right.size());
        int l = 0;
        int r = 0;
        while (l < left.size() || r < right.size()) {
            boolean takeLeft =
                    r == right.size()
                            || (l < left.size()
                                    && left.get(l).sortKey.compareTo(right.get(r).sortKey) >= 0);
            merged.add(takeLeft ? left.get(l++) : right.get(r++));
        }
        return merged;
    }

    /**
     * Whether a body a metered network may download: one of known size up
     * to {@link #CAP}, or of unknown size whose attachment mark says it
     * carries none.
     */
    static boolean fits(Long size, Integer attachment) {
        return size != null ? size <= CAP : attachment != null && attachment == 0;
    }

    /** What a step reaches for, so the step itself can be tested. */
    interface Host {
        /**
         * Whether an account's bodies may download now: the app in the
         * foreground, no sync running, the account on, and a network.
         */
        boolean allowed(String accountEmail);

        /** Whether the network is metered, holding the larger bodies back. */
        boolean metered();

        /** Downloads some of an account's bodies, blocking until they are in. */
        void download(String accountEmail, List<Row> rows) throws Exception;

        /** How many of an account's bodies are left, 0 once it is done. */
        default void progress(String accountEmail, int left) {}
    }

    /** What is left to download, by account: one run's plan. */
    static final class Run {
        final Map<String, Deque<Row>> left = new LinkedHashMap<>();

        /** Plans an account's bodies: the rows wanted, in the order given. */
        void plan(String accountEmail, List<Row> wanted) {
            if (!wanted.isEmpty()) {
                left.put(accountEmail, new ArrayDeque<>(wanted));
            }
        }

        /** Whether nothing is left to download. */
        boolean drained() {
            return left.isEmpty();
        }
    }

    /** Why a step stopped, or that another should follow. */
    enum Step {
        /** Some bodies landed or were tried; another step follows. */
        AGAIN,
        /** Nothing may download now (background, a sync, the network): resumed later. */
        PAUSED,
        /** Nothing is left of the plan. */
        DONE
    }

    /**
     * One step: up to {@link #CHUNK} bodies of every account allowed now,
     * taken off the plan whether they landed or not, so a body that fails
     * is tried again by the next run rather than at once. On a metered
     * network the larger bodies stay in the plan, an account left with
     * those alone waiting. An account whose download fails as a whole
     * leaves the run.
     */
    static Step step(Run run, Host host) {
        if (run.drained()) {
            return Step.DONE;
        }
        boolean metered = host.metered();
        boolean moved = false;
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, Deque<Row>> account : run.left.entrySet()) {
            String email = account.getKey();
            if (!host.allowed(email)) {
                continue;
            }
            Deque<Row> rows = account.getValue();
            List<Row> chunk = new ArrayList<>(CHUNK);
            for (Iterator<Row> next = rows.iterator(); chunk.size() < CHUNK && next.hasNext(); ) {
                Row row = next.next();
                if (!metered || fits(row.size, row.attachment)) {
                    chunk.add(row);
                    next.remove();
                }
            }
            if (chunk.isEmpty()) {
                continue;
            }
            moved = true;
            try {
                host.download(email, chunk);
            } catch (Exception failure) {
                // NOTE: the account is left for this run (no session, a
                // revoked token); the next run plans it again.
                android.util.Log.w("pimalaya", "mail bodies stopped for " + email, failure);
                rows.clear();
            }
            host.progress(email, rows.size());
            if (rows.isEmpty()) {
                done.add(email);
            }
        }
        for (String email : done) {
            run.left.remove(email);
        }
        if (run.drained()) {
            return Step.DONE;
        }
        return moved ? Step.AGAIN : Step.PAUSED;
    }
}
