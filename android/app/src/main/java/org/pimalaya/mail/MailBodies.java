package org.pimalaya.mail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.account.AccountEntry;

/**
 * Which bodies download without being opened, and the step downloading them.
 *
 * <p>From the frontier ({@link MailWindow}), whole mailboxes, owed ones; after
 * each pass, step and fill step, open on Wi-Fi, never periodic. Raised through
 * {@link MailEngine#download}, which links a held body, newest first.
 */
public final class MailBodies {
    private MailBodies() {}

    /** Bodies one step downloads per account, before checking it may go on. */
    static final int CHUNK = 24;

    /** How many times one run tries a body before marking it failed. */
    static final int ATTEMPTS = 3;

    /** One stored message, as the plan reads it. */
    public static final class Row {
        /** The collection it is stored in. */
        public final String collection;
        final String linkId;

        /** The {@code Date} it sorts by, empty when it has none. */
        final String sortKey;

        /** Whether the store holds its body already. */
        final boolean bodied;

        /** Its size in bytes, null where the store does not know it. */
        final Long size;

        /** Its attachment mark: 1 with, 0 without, null never examined. */
        final Integer attachment;

        /** Whether it downloads whatever its date: stepped, or owed. */
        final boolean always;

        Row(
                String collection,
                String linkId,
                String sortKey,
                boolean bodied,
                Long size,
                Integer attachment) {
            this(collection, linkId, sortKey, bodied, size, attachment, false);
        }

        Row(
                String collection,
                String linkId,
                String sortKey,
                boolean bodied,
                Long size,
                Integer attachment,
                boolean always) {
            this.collection = collection;
            this.linkId = linkId;
            this.sortKey = sortKey == null ? "" : sortKey;
            this.bodied = bodied;
            this.size = size;
            this.attachment = attachment;
            this.always = always;
        }
    }

    /**
     * Whether a mailbox's bodies download: all when whole, else its window's.
     *
     * <p>Never junk or trash, whose mail is not worth the bytes.
     */
    public static boolean downloads(String role, boolean whole) {
        return whole || !("junk".equals(role) || "trash".equals(role));
    }

    /**
     * Whether the body step takes one message.
     *
     * <p>Dated from {@code window} (null for all mail, the only one taking
     * undated) in a downloading mailbox, or any in one kept whole.
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

    /** What a step reaches for, so the step itself can be tested. */
    public interface Host {
        /** Whether bodies may download now: foreground, no sync, on, Wi-Fi. */
        boolean allowed(String accountEmail);

        /**
         * Downloads some bodies, blocking; answers the rows that did not land.
         *
         * <p>Gone ones not among them ({@link MailState#gone}); throws when the
         * whole download failed (no session, an expired token).
         */
        List<Row> download(String accountEmail, List<Row> rows) throws Exception;

        /** How many of an account's bodies are left, 0 once it is done. */
        default void progress(String accountEmail, int left) {}
    }

    /** What is left to download, by account: one run's plan. */
    public static final class Run {
        /** The process's failed and gone bodies, read and marked by a run. */
        final MailState state;

        final Map<String, Deque<Row>> left = new LinkedHashMap<>();

        /** Tries per row this run, by {@link MailOwed#key}. */
        final Map<String, Integer> attempts = new HashMap<>();

        /** How many downloads in a row of each account failed as a whole. */
        final Map<String, Integer> broken = new HashMap<>();

        /** A run over the process's failed and gone bodies. */
        public Run(MailState state) {
            this.state = state;
        }

        /**
         * Plans the wanted rows in order, but failed or gone; answers how many.
         */
        public int plan(String accountEmail, List<Row> wanted) {
            Deque<Row> rows = new ArrayDeque<>(wanted.size());
            for (Row row : wanted) {
                String key = MailOwed.key(row.collection, row.linkId);
                if (!state.failed.contains(key) && !state.gone.contains(key)) {
                    rows.add(row);
                }
            }
            if (!rows.isEmpty()) {
                left.put(accountEmail, rows);
            }
            return rows.size();
        }

        /** Whether nothing is left to download. */
        public boolean drained() {
            return left.isEmpty();
        }
    }

    /** Why a step stopped, or that another should follow. */
    public enum Step {
        /** Some bodies landed or were tried; another step follows. */
        AGAIN,
        /**
         * Nothing may download now (background, sync, no Wi-Fi): resumed later.
         */
        PAUSED,
        /** Nothing is left of the plan. */
        DONE
    }

    /**
     * One step: up to {@link #CHUNK} bodies of every account allowed now.
     *
     * <p>Unlanded rows (or a whole failed download) requeue, {@link #ATTEMPTS}
     * times, then are failed; gone ones are dropped. An account failing whole
     * twice in a row leaves the run, so an expired token renews meanwhile.
     */
    public static Step step(Run run, Host host) {
        if (run.drained()) {
            return Step.DONE;
        }
        boolean moved = false;
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, Deque<Row>> account : run.left.entrySet()) {
            String email = account.getKey();
            if (!host.allowed(email)) {
                continue;
            }
            Deque<Row> rows = account.getValue();
            List<Row> chunk = new ArrayList<>(CHUNK);
            while (chunk.size() < CHUNK && !rows.isEmpty()) {
                chunk.add(rows.poll());
            }
            moved = true;
            List<Row> failed;
            try {
                failed = host.download(email, chunk);
                run.broken.remove(email);
            } catch (Exception failure) {
                android.util.Log.w("pimalaya", "mail bodies failed for " + AccountEntry.redacted(email), failure);
                if (run.broken.merge(email, 1, Integer::sum) >= 2) {
                    // NOTE: twice in a row (no session, the server down): the
                    // account leaves the run, the next run plans it again.
                    rows.clear();
                    done.add(email);
                    host.progress(email, 0);
                    continue;
                }
                failed = chunk;
            }
            Set<String> retried = new HashSet<>();
            for (Row row : failed) {
                String key = MailOwed.key(row.collection, row.linkId);
                retried.add(key);
                if (run.attempts.merge(key, 1, Integer::sum) < ATTEMPTS) {
                    rows.add(row);
                } else {
                    run.state.failed.add(key);
                }
            }
            for (Row row : chunk) {
                String key = MailOwed.key(row.collection, row.linkId);
                if (!retried.contains(key)) {
                    run.state.failed.remove(key);
                }
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
