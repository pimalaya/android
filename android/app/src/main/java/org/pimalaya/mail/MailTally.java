package org.pimalaya.mail;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * How many bodies a mail step or body run has landed of its plan, for the dock.
 *
 * <p>A body counts once its file is in the call's directory; a {@link Watch}
 * counts them while the call runs ({@link MailEngine#stepRead}), so the bar
 * moves with the reads. A run counts by chunk ({@link MailBodies#step}).
 */
public final class MailTally {
    /** Hears the count move: {@code landed} of {@code planned}, any thread. */
    public interface Listener {
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

    /** A tally telling {@code listener} as counts move. */
    public MailTally(Listener listener) {
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
     * Plans {@code more} bodies beyond what has landed.
     *
     * <p>A step's count, a run's plan, or 0 once a call answered, which closes
     * the plan on what it brought.
     */
    public void expect(int more) {
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

    /** Folds what is in flight into {@code done}, the bodies a chunk took. */
    public void settle(int done) {
        synchronized (this) {
            settled += Math.max(0, done);
            flight = 0;
        }
        tell();
    }

    /** Forgets every count, the run over. */
    public synchronized void reset() {
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
     * Counts files written into {@code dir} until closed, which counts last.
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
