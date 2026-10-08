package org.pimalaya;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Which bodies download without being opened, and the step that downloads
 * them: the account's policy ({@link MailOffline}) carried out after each
 * pass and fill step while the app is open, never as periodic work.
 *
 * <p>A body is raised to {@code Full} through the engine's upgrade
 * ({@link MailEngine#download}), which links a body the store already holds
 * under the same link id rather than fetching it again. Newest first, the
 * shown mailboxes before the others, within each mailbox's bound: a message
 * dated below it is not downloaded, and an undated one only where nothing
 * bounds the mailbox.
 */
final class MailBodies {
    private MailBodies() {}

    /** Bodies one step downloads per account, before checking it may go on. */
    static final int CHUNK = 24;

    /** One stored message, as the plan reads it. */
    static final class Row {
        final String collection;
        final String linkId;

        /** The {@code Date} it sorts by, empty when it has none. */
        final String sortKey;

        /** Whether the store holds its body already. */
        final boolean bodied;

        Row(String collection, String linkId, String sortKey, boolean bodied) {
            this.collection = collection;
            this.linkId = linkId;
            this.sortKey = sortKey == null ? "" : sortKey;
            this.bodied = bodied;
        }
    }

    /**
     * The rows of {@code newestFirst} whose body is wanted: not held yet,
     * and within their mailbox's bound ({@code boundOf}, null for none).
     */
    static List<Row> wanted(List<Row> newestFirst, Function<String, String> boundOf) {
        List<Row> wanted = new ArrayList<>();
        for (Row row : newestFirst) {
            if (row.bodied) {
                continue;
            }
            String bound = boundOf.apply(row.collection);
            if (bound != null && (row.sortKey.isEmpty() || row.sortKey.compareTo(bound) < 0)) {
                continue;
            }
            wanted.add(row);
        }
        return wanted;
    }

    /**
     * Whether a network lets bodies download: one that is there, and not
     * metered unless the account allows a metered one ({@link MailOffline}).
     */
    static boolean networkAllows(boolean online, boolean metered, boolean meteredAllowed) {
        return online && (!metered || meteredAllowed);
    }

    /** What a step reaches for, so the step itself can be tested. */
    interface Host {
        /**
         * Whether an account's bodies may download now: the app in the
         * foreground, no sync running, and a network its setting allows
         * (unmetered, or metered where the account says so).
         */
        boolean allowed(String accountEmail);

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
     * is tried again by the next run rather than at once. An account whose
     * download fails as a whole leaves the run.
     */
    static Step step(Run run, Host host) {
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
