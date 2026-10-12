package org.pimalaya;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * How many bodies a mail step or a body run has landed out of what it
 * planned, for the dock's bar.
 *
 * <p>A body counts once its file is written: every backend writes one file
 * per message into the directory a bridge call is given ({@link
 * MailEngine#stepRead}, {@link MailEngine#sources}), Gmail a batch at a
 * time, Graph a {@code $batch} at a time, IMAP as each streamed body
 * starts, JMAP one blob at a time. A {@link Watch} counts them while the
 * call runs, so the bar moves with the reads rather than once the call
 * answers.
 *
 * <p>The counts are the files in flight, those of the calls under way or
 * done, and what a body run settled: a run counts by chunk ({@link
 * MailBodies#step}), a chunk's files then folded into what it took.
 */
final class MailTally {
    /** Hears the count move: {@code landed} of {@code planned}, any thread. */
    interface Listener {
        void told(int landed, int planned);
    }

    /** How often a watch reads its directory. */
    static final long POLL_MS = 250;

    /** The one thread every watch reads its directory on. */
    private static final ScheduledExecutorService POLLS =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "mail-tally");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final Listener listener;

    private int planned;

    private int settled;

    private int flight;

    MailTally(Listener listener) {
        this.listener = listener;
    }

    /** What has landed, settled and in flight. */
    synchronized int landed() {
        return settled + flight;
    }

    synchronized int planned() {
        return planned;
    }

    /**
     * Plans {@code more} bodies beyond what has landed: a step's count, a
     * run's plan, or 0 once a call answered, which closes the plan on what
     * it brought.
     */
    void expect(int more) {
        synchronized (this) {
            planned = settled + flight + Math.max(0, more);
        }
        tell();
    }

    /** Counts {@code files} more written. */
    void land(int files) {
        if (files <= 0) {
            return;
        }
        synchronized (this) {
            flight += files;
        }
        tell();
    }

    /**
     * Folds what is in flight into {@code done}, the bodies a chunk of a
     * run took, its files counted already or linked without one.
     */
    void settle(int done) {
        synchronized (this) {
            settled += Math.max(0, done);
            flight = 0;
        }
        tell();
    }

    /** Forgets every count, the run over. */
    synchronized void reset() {
        planned = 0;
        settled = 0;
        flight = 0;
    }

    private void tell() {
        int landed;
        int of;
        synchronized (this) {
            of = planned;
            landed = Math.min(settled + flight, of);
        }
        listener.told(landed, of);
    }

    /**
     * Starts counting the files written into {@code dir} while a bridge
     * call runs; closing it counts them one last time and stops.
     */
    Watch watch(File dir) {
        return new Watch(this, dir);
    }

    /** Counts the files of one directory as they appear. */
    static final class Watch implements AutoCloseable {
        private final MailTally tally;
        private final File dir;
        private final ScheduledFuture<?> polls;
        private int seen;

        Watch(MailTally tally, File dir) {
            this.tally = tally;
            this.dir = dir;
            this.seen = count(dir);
            this.polls =
                    POLLS.scheduleWithFixedDelay(
                            this::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        }

        /** Counts what appeared since the last reading. */
        synchronized void poll() {
            int now = count(dir);
            if (now > seen) {
                tally.land(now - seen);
                seen = now;
            }
        }

        @Override
        public void close() {
            polls.cancel(false);
            poll();
        }

        private static int count(File dir) {
            String[] names = dir.list();
            return names == null ? 0 : names.length;
        }
    }
}
