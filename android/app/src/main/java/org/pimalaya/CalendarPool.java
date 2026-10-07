package org.pimalaya;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One account's calendars run side by side, each worker on a connection of
 * its own.
 *
 * <p>A calendar pass is network-bound, calendar after calendar, exactly as
 * a mail pass was ({@link MailPool}): nothing in one calendar's pass
 * depends on another's, so an account's calendars run concurrently, in the
 * order the account lists them, each worker on its own transport (a
 * transport holds sockets and is used from one thread at a time). The store
 * stays one writer ({@link PimdirEngine#STORE}): only the network overlaps.
 *
 * <p>Three workers on every backend: an account rarely has more calendars
 * than that worth waiting on, a CalDAV worker is a connection of its own,
 * and Graph and Google count requests per user.
 *
 * @param <S> the session, a {@link org.pimalaya.client.Transport} in the
 *     app; anything closeable in a test.
 */
final class CalendarPool<S extends AutoCloseable> implements AutoCloseable {
    /** Calendars an account syncs at once. */
    static final int SIZE = 3;

    /** The workers every pool runs on; idle ones die after a minute. */
    private static final ExecutorService WORKERS =
            Executors.newCachedThreadPool(
                    task -> {
                        Thread thread = new Thread(task, "pimalaya-calendar");
                        thread.setDaemon(true);
                        return thread;
                    });

    /** Opens one more session of the account. */
    interface Opener<S> {
        S open() throws Exception;
    }

    /**
     * What a run does to one calendar on a worker's session, adding the
     * nanoseconds its engines spent on the network to {@code remote}.
     */
    interface Step<S> {
        void run(S session, String collection, AtomicLong remote) throws Exception;
    }

    /** One calendar a run could not take through. */
    static final class Failure {
        final String collection;
        final Exception error;

        Failure(String collection, Exception error) {
            this.collection = collection;
            this.error = error;
        }
    }

    /** What one run over an account's calendars came to. */
    static final class Outcome {
        /** The calendars that failed, in the order the run took them. */
        final List<Failure> failures = new ArrayList<>();

        /** Calendars the run was given. */
        int calendars;

        /** Sessions it ran them on. */
        int sessions;

        /** Nanoseconds from its start to its last calendar. */
        long wall;

        /** Nanoseconds every calendar spent on the network, summed. */
        long remote;

        /** The first failure, or null when every calendar went through. */
        Exception failure() {
            return failures.isEmpty() ? null : failures.get(0).error;
        }

        @Override
        public String toString() {
            return calendars + " calendars on " + sessions + " sessions in " + wall / 1_000_000
                    + " ms, remote summed " + remote / 1_000_000 + " ms"
                    + (failures.isEmpty() ? "" : ", " + failures.size() + " failed");
        }
    }

    private final int size;
    private final Opener<S> opener;

    /** The sessions by worker slot, null where none is open yet. */
    private final List<S> sessions = new ArrayList<>();

    /** Whether slot 0 is the caller's, which the pool does not close. */
    private final boolean borrowed;

    /**
     * A pool of up to {@code size} sessions, the first one the caller's when
     * {@code first} is given (not closed with the pool), the others opened
     * by {@code opener} as workers need them.
     */
    CalendarPool(int size, S first, Opener<S> opener) {
        this.size = Math.max(1, size);
        this.opener = opener;
        this.borrowed = first != null;
        for (int slot = 0; slot < this.size; slot++) {
            sessions.add(slot == 0 ? first : null);
        }
    }

    /**
     * Runs {@code step} over every calendar of {@code collections}, taken in
     * that order by up to {@link #size} workers, each on its own session,
     * and blocks until all of them are done. A calendar that fails leaves
     * the others running; every failure is answered, in the order taken.
     *
     * <p>One run at a time per pool, so a session never serves two runs.
     */
    synchronized Outcome run(String label, List<String> collections, Step<S> step) {
        Outcome outcome = new Outcome();
        outcome.calendars = collections.size();
        int workers = Math.min(size, collections.size());
        outcome.sessions = workers;
        if (workers == 0) {
            return outcome;
        }

        long started = System.nanoTime();
        AtomicInteger next = new AtomicInteger();
        AtomicLong remote = new AtomicLong();
        AtomicReference<Exception> unopened = new AtomicReference<>();
        Exception[] errors = new Exception[collections.size()];
        boolean[] taken = new boolean[collections.size()];

        List<Future<?>> running = new ArrayList<>(workers);
        for (int worker = 0; worker < workers; worker++) {
            int slot = worker;
            Runnable work =
                    () -> {
                        S session;
                        try {
                            session = sessionAt(slot);
                        } catch (Exception failure) {
                            // NOTE: the calendars go to the workers that
                            // have a session; only when none could open one
                            // is the run failed.
                            Log.w("pimalaya", "calendar worker could not connect: " + label,
                                    failure);
                            unopened.compareAndSet(null, failure);
                            return;
                        }
                        int index;
                        while ((index = next.getAndIncrement()) < collections.size()) {
                            taken[index] = true;
                            try {
                                step.run(session, collections.get(index), remote);
                            } catch (Exception failure) {
                                errors[index] = failure;
                            }
                        }
                    };
            // NOTE: a run of one calendar stays on the calling thread, as
            // the pass did before there was a pool.
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
                Log.w("pimalaya", "calendar worker failed: " + label, interrupted);
                unopened.compareAndSet(null, interrupted);
            }
        }

        for (int index = 0; index < collections.size(); index++) {
            Exception error = taken[index] ? errors[index] : unopened.get();
            if (error != null) {
                outcome.failures.add(new Failure(collections.get(index), error));
            }
        }
        outcome.wall = System.nanoTime() - started;
        outcome.remote = remote.get();
        Log.d("pimalaya", "calendar pass " + label + ": " + outcome);
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
