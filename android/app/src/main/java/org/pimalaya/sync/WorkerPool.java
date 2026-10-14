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
 * One account's sessions, its mailboxes or calendars run on them side by side.
 *
 * <p>Each worker takes keys in order on its own session ({@code S}, used from
 * one thread at a time, never shared); only the network overlaps, the store
 * staying one writer. Sessions open as needed and live until {@link #close}.
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
     * What a run does to one key on a worker's session (a pass, a widening).
     *
     * <p>Adds the nanoseconds its engines spent on the network to {@code
     * remote}.
     */
    public interface Step<S> {
        void run(S session, String key, AtomicLong remote) throws Exception;
    }

    /**
     * Has a worker's session join run number {@code run}, or leave it on 0.
     *
     * <p>A Gmail account's sessions so read its one listing once per run.
     */
    public interface Joiner<S> {
        void join(S session, long run);
    }

    /** One key a run could not take through. */
    public static final class Failure {
        /** The key, empty for a run that failed as a whole. */
        public final String key;
        /** Why it failed. */
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
     * A pool of up to {@code size} sessions, opened by {@code opener}.
     *
     * <p>{@code first}, when given, is the caller's session in slot 0, never
     * closed by the pool; {@code joiner}, when given, numbers each session per
     * run.
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
     * Runs {@code step} over {@code keys}, in order, on {@link #size} workers.
     *
     * <p>Blocks until all are done. A failing key leaves the others running;
     * every failure is answered, in the order taken. One run at a time, so a
     * session never serves two.
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
            // NOTE: a lone worker stays on the calling thread, so an
            // interrupt of the caller reaches its work.
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
     * Runs several pools' runs at once, one per account, outcomes in order.
     *
     * <p>A run that threw as a whole answers that failure alone. One run goes
     * on the calling thread.
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

    /** The session of a worker slot, opened the first time it is asked for. */
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
