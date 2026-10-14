package org.pimalaya.sync;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One account's sessions, and its mailboxes or calendars run on them side
 * by side.
 *
 * <p>A pass is network-bound, mailbox after mailbox: on the owner's device a
 * first sync of twelve mailboxes spent 6.8 s waiting on Graph against a
 * quarter of a second in the bridge and the store. So the collections of
 * one account run concurrently, in the order the pass takes them (the inbox
 * first, {@code MailEngine.ordered}; the calendars as the account lists
 * them), each worker on a session of its own: a mail session's handle is a
 * pointer into the bridge and a transport holds sockets, each used from one
 * thread at a time, so no two workers ever share one. The store stays one
 * writer ({@code Locks.store}): only the network overlaps.
 *
 * <p>The size is the caller's, the domain's number for the backend
 * ({@code MailEngine.sessionsOf}, {@code CalendarEngine.WORKERS}).
 *
 * <p>The sessions are opened as the workers need them, the first worker
 * taking the caller's own session when it gives one, and kept between runs
 * until the pool is closed: the background fill runs a step at a time and
 * keeps its connections between steps.
 *
 * <p>Each run is numbered, and every session a worker runs it on joins that
 * number ({@link Joiner}) for its length: a Gmail account is one listing,
 * which the run's sessions then read once between them.
 *
 * @param <S> the session, a {@code MailSession} or a {@code Transport} in
 *     the app; anything closeable in a test.
 */
public final class WorkerPool<S extends AutoCloseable> implements AutoCloseable {
    /** The number the last run took; 0 is no run. */
    private static final AtomicLong RUNS = new AtomicLong();

    /** The workers every pool runs on; idle ones die after a minute. */
    private static final ExecutorService WORKERS =
            Executors.newCachedThreadPool(
                    task -> {
                        Thread thread = new Thread(task, "pimalaya-worker");
                        thread.setDaemon(true);
                        return thread;
                    });

    /** Opens one more session of the account. */
    public interface Opener<S> {
        S open() throws Exception;
    }

    /**
     * What a run does to one key on a worker's session (a pass, a widening
     * by a chunk), adding the nanoseconds its engines spent on the network
     * to {@code remote}.
     */
    public interface Step<S> {
        void run(S session, String key, AtomicLong remote) throws Exception;
    }

    /** Has a worker's session join the run numbered {@code run}, or leave it on 0. */
    public interface Joiner<S> {
        void join(S session, long run);
    }

    /** One key a run could not take through. */
    public static final class Failure {
        public final String key;
        public final Exception error;

        Failure(String key, Exception error) {
            this.key = key;
            this.error = error;
        }
    }

    /** What one run over an account's keys came to. */
    public static final class Outcome {
        /** The keys that failed, in the order the run took them. */
        public final List<Failure> failures = new ArrayList<>();

        /** Keys the run was given. */
        int keys;

        /** Sessions it ran them on. */
        public int sessions;

        /** Nanoseconds from its start to its last key. */
        public long wall;

        /** Nanoseconds every key spent on the network, summed. */
        public long remote;

        /** The first failure, or null when every key went through. */
        public Exception failure() {
            return failures.isEmpty() ? null : failures.get(0).error;
        }

        @Override
        public String toString() {
            return keys + " keys on " + sessions + " sessions in " + wall / 1_000_000
                    + " ms, remote summed " + remote / 1_000_000 + " ms"
                    + (failures.isEmpty() ? "" : ", " + failures.size() + " failed");
        }
    }

    private final int size;
    private final Opener<S> opener;
    private final Joiner<S> joiner;

    /** The sessions by worker slot, null where none is open yet. */
    private final List<S> sessions = new ArrayList<>();

    /** Whether slot 0 is the caller's, which the pool does not close. */
    private final boolean borrowed;

    /**
     * A pool of up to {@code size} sessions, the first one the caller's when
     * {@code first} is given (not closed with the pool), the others opened
     * by {@code opener} as workers need them; {@code joiner}, when given,
     * numbers each worker's session for the length of a run.
     */
    public WorkerPool(int size, S first, Opener<S> opener, Joiner<S> joiner) {
        this.size = Math.max(1, size);
        this.opener = opener;
        this.joiner = joiner;
        this.borrowed = first != null;
        for (int slot = 0; slot < this.size; slot++) {
            sessions.add(slot == 0 ? first : null);
        }
    }

    /** How many sessions this pool runs at once. */
    public int size() {
        return size;
    }

    /**
     * Runs {@code step} over every key of {@code keys}, taken in that order
     * by up to {@link #size} workers, each on its own session, and blocks
     * until all of them are done. A key that fails leaves the others
     * running; every failure is answered, in the order taken.
     *
     * <p>One run at a time per pool, so a session never serves two runs.
     */
    public synchronized Outcome run(String label, List<String> keys, Step<S> step) {
        Outcome outcome = new Outcome();
        outcome.keys = keys.size();
        int workers = Math.min(size, keys.size());
        outcome.sessions = workers;
        if (workers == 0) {
            return outcome;
        }

        long started = System.nanoTime();
        long number = RUNS.incrementAndGet();
        AtomicInteger next = new AtomicInteger();
        AtomicLong remote = new AtomicLong();
        AtomicReference<Exception> unopened = new AtomicReference<>();
        Exception[] errors = new Exception[keys.size()];
        boolean[] taken = new boolean[keys.size()];

        List<Future<?>> running = new ArrayList<>(workers);
        for (int worker = 0; worker < workers; worker++) {
            int slot = worker;
            Runnable work =
                    () -> {
                        S session;
                        try {
                            session = sessionAt(slot);
                        } catch (Exception failure) {
                            // NOTE: the keys go to the workers that have a
                            // session; only when none could open one is the
                            // run failed.
                            Log.w("pimalaya", "worker could not connect: " + label, failure);
                            unopened.compareAndSet(null, failure);
                            return;
                        }
                        if (joiner != null) {
                            joiner.join(session, number);
                        }
                        try {
                            int index;
                            while ((index = next.getAndIncrement()) < keys.size()) {
                                taken[index] = true;
                                try {
                                    step.run(session, keys.get(index), remote);
                                } catch (Exception failure) {
                                    errors[index] = failure;
                                }
                            }
                        } finally {
                            if (joiner != null) {
                                joiner.join(session, 0);
                            }
                        }
                    };
            // NOTE: a run of one key stays on the calling thread, as the
            // pass did before there was a pool.
            if (workers == 1) {
                work.run();
            } else {
                running.add(WORKERS.submit(work));
            }
        }
        for (Future<?> worker : running) {
            try {
                worker.get();
            } catch (Exception interrupted) {
                Log.w("pimalaya", "worker failed: " + label, interrupted);
                unopened.compareAndSet(null, interrupted);
            }
        }

        for (int index = 0; index < keys.size(); index++) {
            Exception error = taken[index] ? errors[index] : unopened.get();
            if (error != null) {
                outcome.failures.add(new Failure(keys.get(index), error));
            }
        }
        outcome.wall = System.nanoTime() - started;
        outcome.remote = remote.get();
        Log.d("pimalaya", label + ": " + outcome);
        return outcome;
    }

    /**
     * Runs several pools' runs at once, one per account, and answers their
     * outcomes in the order given; a run that threw as a whole answers that
     * failure alone. One run goes on the calling thread.
     */
    public static List<Outcome> together(List<Callable<Outcome>> runs) {
        List<Outcome> outcomes = new ArrayList<>(runs.size());
        if (runs.size() == 1) {
            try {
                outcomes.add(runs.get(0).call());
            } catch (Exception failure) {
                outcomes.add(failed(failure));
            }
            return outcomes;
        }
        List<Future<Outcome>> running = new ArrayList<>(runs.size());
        for (Callable<Outcome> run : runs) {
            running.add(WORKERS.submit(run));
        }
        for (Future<Outcome> run : running) {
            try {
                outcomes.add(run.get());
            } catch (Exception failure) {
                outcomes.add(failed(failure));
            }
        }
        return outcomes;
    }

    /** The outcome of a run that failed as a whole. */
    private static Outcome failed(Exception failure) {
        Outcome outcome = new Outcome();
        outcome.failures.add(new Failure("", failure));
        return outcome;
    }

    /** The session of one worker slot, opened the first time it is asked for. */
    private S sessionAt(int slot) throws Exception {
        synchronized (sessions) {
            S session = sessions.get(slot);
            if (session != null) {
                return session;
            }
        }
        // NOTE: opened outside the lock, so the workers connect side by side.
        S opened = opener.open();
        synchronized (sessions) {
            sessions.set(slot, opened);
        }
        return opened;
    }

    /** Closes every session the pool opened; the caller's own stays open. */
    @Override
    public void close() {
        synchronized (sessions) {
            for (int slot = borrowed ? 1 : 0; slot < sessions.size(); slot++) {
                S session = sessions.get(slot);
                if (session == null) {
                    continue;
                }
                try {
                    session.close();
                } catch (Exception ignored) {
                    // NOTE: best-effort; the session is dropped either way.
                }
                sessions.set(slot, null);
            }
        }
    }
}
