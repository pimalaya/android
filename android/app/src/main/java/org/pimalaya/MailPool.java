package org.pimalaya;

import android.util.Log;

import org.pimalaya.client.Account;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.PimalayaClient;

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
 * One account's mail sessions, and its mailboxes run on them side by side.
 *
 * <p>A pass is network-bound, mailbox after mailbox: on the owner's device a
 * first sync of twelve mailboxes spent 6.8 s waiting on Graph against a
 * quarter of a second in the bridge and the store. So the mailboxes of one
 * account run concurrently, in the order the pass takes them (the inbox
 * first, {@link MailEngine#ordered}), each worker on a session of its own:
 * a session's handle is a pointer into the bridge, used from one thread at a
 * time, so no two workers ever share one. The store stays one writer
 * ({@link PimdirEngine#STORE}): only the network overlaps.
 *
 * <p>The size is the backend's: four for Graph and JMAP, which are HTTP
 * requests and nothing else; three for IMAP, each worker its own connection
 * and login; two for Gmail, whose reads are paced across the whole process
 * anyway, so more workers would only queue on the same bucket.
 *
 * <p>The sessions are opened as the workers need them, the first worker
 * taking the caller's own session when it gives one, and kept between runs
 * until the pool is closed: the background fill runs a step at a time and
 * keeps its connections between steps.
 *
 * <p>Each run is numbered, and every session a worker runs it on joins that
 * number ({@link MailSession#join}) for its length: a Gmail account is one
 * listing, which the run's sessions then read once between them.
 *
 * @param <S> the session, a {@link MailSession} in the app; anything
 *     closeable in a test.
 */
final class MailPool<S extends AutoCloseable> implements AutoCloseable {
    /** Graph sessions an account runs at once: HTTP requests, nothing held. */
    static final int GRAPH = 4;

    /** JMAP sessions an account runs at once: HTTP requests, nothing held. */
    static final int JMAP = 4;

    /** IMAP sessions an account runs at once: a connection and a login each. */
    static final int IMAP = 3;

    /**
     * Gmail sessions an account runs at once: the account is one listing,
     * read once per run, and its metadata batches spread over the sessions
     * under one pacing bucket.
     */
    static final int GMAIL = 2;

    /** The number the last run took; 0 is no run. */
    private static final AtomicLong RUNS = new AtomicLong();

    /** The workers every pool runs on; idle ones die after a minute. */
    private static final ExecutorService WORKERS =
            Executors.newCachedThreadPool(
                    task -> {
                        Thread thread = new Thread(task, "pimalaya-mail");
                        thread.setDaemon(true);
                        return thread;
                    });

    /** How many sessions one account of this backend runs at once. */
    static int sizeOf(Account account) {
        if (PimalayaClient.isGraph(account)) {
            return GRAPH;
        }
        if (PimalayaClient.isJmap(account)) {
            return JMAP;
        }
        if (PimalayaClient.isGoogle(account)) {
            return GMAIL;
        }
        return IMAP;
    }

    /** Opens one more session of the account. */
    interface Opener<S> {
        S open() throws Exception;
    }

    /** The driver a worker runs one mailbox with, on that worker's session. */
    interface Engines<S> {
        MailEngine on(S session);
    }

    /** What a run does to one mailbox: a pass, a widening by a chunk. */
    interface Step {
        void run(MailEngine engine, String collection) throws Exception;
    }

    /** Told as each mailbox lands, on the worker that ran it. */
    interface Landed {
        void landed(int done, int total);
    }

    /** One mailbox a run could not take through. */
    static final class Failure {
        final String collection;
        final Exception error;

        Failure(String collection, Exception error) {
            this.collection = collection;
            this.error = error;
        }
    }

    /** What one run over an account's mailboxes came to. */
    static final class Outcome {
        /** The mailboxes that failed, in the order the run took them. */
        final List<Failure> failures = new ArrayList<>();

        /** Mailboxes the run was given. */
        int mailboxes;

        /** Sessions it ran them on. */
        int sessions;

        /** Nanoseconds from its start to its last mailbox. */
        long wall;

        /** Nanoseconds every mailbox spent on the network, summed. */
        long remote;

        /** The first failure, or null when every mailbox went through. */
        Exception failure() {
            return failures.isEmpty() ? null : failures.get(0).error;
        }

        @Override
        public String toString() {
            return mailboxes + " mailboxes on " + sessions + " sessions in " + wall / 1_000_000
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
    MailPool(int size, S first, Opener<S> opener) {
        this.size = Math.max(1, size);
        this.opener = opener;
        this.borrowed = first != null;
        for (int slot = 0; slot < this.size; slot++) {
            sessions.add(slot == 0 ? first : null);
        }
    }

    /** How many sessions this pool runs at once. */
    int size() {
        return size;
    }

    /**
     * Runs {@code step} over every mailbox of {@code collections}, taken in
     * that order by up to {@link #size} workers, each on its own session,
     * and blocks until all of them are done. A mailbox that fails leaves the
     * others running; every failure is answered, in the order taken.
     *
     * <p>One run at a time per pool, so a session never serves two runs.
     */
    synchronized Outcome run(
            String label,
            List<String> collections,
            Engines<S> engines,
            Step step,
            Landed landed) {
        Outcome outcome = new Outcome();
        outcome.mailboxes = collections.size();
        int workers = Math.min(size, collections.size());
        outcome.sessions = workers;
        if (workers == 0) {
            return outcome;
        }

        long started = System.nanoTime();
        long number = RUNS.incrementAndGet();
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicLong remote = new AtomicLong();
        AtomicReference<Exception> unopened = new AtomicReference<>();
        Exception[] errors = new Exception[collections.size()];
        boolean[] taken = new boolean[collections.size()];

        List<Future<?>> running = new ArrayList<>(workers);
        for (int worker = 0; worker < workers; worker++) {
            int slot = worker;
            running.add(
                    WORKERS.submit(
                            () -> {
                                S session;
                                try {
                                    session = sessionAt(slot);
                                } catch (Exception failure) {
                                    // NOTE: the mailboxes go to the workers
                                    // that have a session; only when none
                                    // could open one is the run failed.
                                    Log.w("pimalaya", "mail worker could not connect: " + label,
                                            failure);
                                    unopened.compareAndSet(null, failure);
                                    return;
                                }
                                join(session, number);
                                try {
                                    int index;
                                    while ((index = next.getAndIncrement())
                                            < collections.size()) {
                                        taken[index] = true;
                                        MailEngine engine = engines.on(session);
                                        try {
                                            step.run(engine, collections.get(index));
                                        } catch (Exception failure) {
                                            errors[index] = failure;
                                        } finally {
                                            remote.addAndGet(engine.remoteSoFar());
                                        }
                                        if (landed != null) {
                                            landed.landed(
                                                    done.incrementAndGet(), collections.size());
                                        }
                                    }
                                } finally {
                                    join(session, 0);
                                }
                            }));
        }
        for (Future<?> worker : running) {
            try {
                worker.get();
            } catch (Exception interrupted) {
                Log.w("pimalaya", "mail worker failed: " + label, interrupted);
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
        Log.d("pimalaya", "mail pass " + label + ": " + outcome);
        return outcome;
    }

    /**
     * Runs several pools' runs at once, one per account, and answers their
     * outcomes in the order given; a run that threw as a whole answers that
     * failure alone. One run goes on the calling thread.
     */
    static List<Outcome> together(List<Callable<Outcome>> runs) {
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

    /** Has a worker's session join the run numbered {@code run}, or leave it on 0. */
    private static void join(Object session, long run) {
        if (session instanceof MailSession) {
            ((MailSession) session).join(run);
        }
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
