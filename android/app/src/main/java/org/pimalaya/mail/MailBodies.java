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
import java.util.concurrent.ConcurrentHashMap;
import org.pimalaya.account.AccountEntry;

/**
 * Which bodies download without being opened, and the step that downloads
 * them: what each account holds from its frontier ({@link MailWindow}) on,
 * its mailboxes kept whole ({@link MailOffline}), and what is owed whatever
 * its date ({@link MailOwed}), carried out after each pass, step and fill
 * step while the app is open on Wi-Fi, never as periodic work.
 *
 * <p>A body is raised to {@code Full} through the engine's upgrade
 * ({@link MailEngine#download}), which links a body the store already holds
 * under the same link id rather than fetching it again. Newest first, the
 * shown mailboxes before the others.
 */
public final class MailBodies {
    private MailBodies() {}

    /** Bodies one step downloads per account, before checking it may go on. */
    static final int CHUNK = 24;

    /** How many times one run tries a body before marking it failed. */
    public static final int ATTEMPTS = 3;

    /**
     * The messages whose body failed every attempt of a run, by
     * {@link MailOwed#key}: their row says so rather than dimming silently,
     * and no run plans them again until their mailbox is listed anew
     * ({@link #relisted}), so a run never starts for them alone. Kept for
     * the process, so the next app start tries them again.
     */
    static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    /**
     * The messages the server answered gone when their body was asked for
     * (deleted since they were listed), by {@link MailOwed#key}: owed no
     * more, and planned by no run until their mailbox is listed anew
     * ({@link #relisted}), whose delta takes them away. Kept for the
     * process.
     */
    static final Set<String> GONE = ConcurrentHashMap.newKeySet();

    /**
     * Forgets what {@link #FAILED} and {@link #GONE} hold of one mailbox,
     * just listed: what is still there and still without its body is
     * planned again.
     */
    static void relisted(String collection) {
        String prefix = MailOwed.key(collection, "");
        FAILED.removeIf(key -> key.startsWith(prefix));
        GONE.removeIf(key -> key.startsWith(prefix));
    }

    /** One stored message, as the plan reads it. */
    public static final class Row {
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

        /**
         * Whether it downloads whatever its date: one a step brought, one
         * owed ({@link MailOwed}).
         */
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

        public Row(
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
     * Whether a mailbox's bodies download: all of them for one kept whole,
     * its window's for any other but the junk and the trash, whose mail is
     * not worth the bytes.
     */
    public static boolean downloads(String role, boolean whole) {
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
    public static List<Row> wanted(List<Row> newestFirst) {
        List<Row> wanted = new ArrayList<>();
        for (Row row : newestFirst) {
            if (!row.bodied) {
                wanted.add(row);
            }
        }
        return wanted;
    }

    /** Two newest-first lists as one, newest first. */
    public static List<Row> merged(List<Row> left, List<Row> right) {
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
        /**
         * Whether an account's bodies may download now: the app in the
         * foreground, no sync running, the account on, and Wi-Fi.
         */
        boolean allowed(String accountEmail);

        /**
         * Downloads some of an account's bodies, blocking until they are in,
         * and answers the rows whose body did not land, a message gone not
         * among them ({@link MailBodies#GONE}); throws when the download failed as a
         * whole (no session, an expired token).
         */
        List<Row> download(String accountEmail, List<Row> rows) throws Exception;

        /** How many of an account's bodies are left, 0 once it is done. */
        default void progress(String accountEmail, int left) {}
    }

    /** What is left to download, by account: one run's plan. */
    public static final class Run {
        final Map<String, Deque<Row>> left = new LinkedHashMap<>();

        /** How many times each row was tried this run, by {@link MailOwed#key}. */
        final Map<String, Integer> attempts = new HashMap<>();

        /** How many downloads in a row of each account failed as a whole. */
        final Map<String, Integer> broken = new HashMap<>();

        /**
         * Plans an account's bodies: the rows wanted, in the order given,
         * but those failed or gone ({@link MailBodies#FAILED}, {@link MailBodies#GONE}).
         * Answers how many it planned.
         */
        public int plan(String accountEmail, List<Row> wanted) {
            Deque<Row> rows = new ArrayDeque<>(wanted.size());
            for (Row row : wanted) {
                String key = MailOwed.key(row.collection, row.linkId);
                if (!FAILED.contains(key) && !GONE.contains(key)) {
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
        /** Nothing may download now (background, a sync, no Wi-Fi): resumed later. */
        PAUSED,
        /** Nothing is left of the plan. */
        DONE
    }

    /**
     * One step: up to {@link #CHUNK} bodies of every account allowed now.
     * A body that did not land goes back to the end of its account's plan,
     * tried again later in the run on the sessions the host reopens, at most
     * {@link #ATTEMPTS} times, then marked failed ({@link #FAILED}); one
     * the server answers gone is not tried again ({@link #GONE}). A
     * download failing as a whole puts its rows back the same way, so an
     * expired token is renewed and the run goes on; an account failing so
     * twice in a row leaves the run, the next one planning it again.
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
                    FAILED.add(key);
                }
            }
            for (Row row : chunk) {
                String key = MailOwed.key(row.collection, row.linkId);
                if (!retried.contains(key)) {
                    FAILED.remove(key);
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
