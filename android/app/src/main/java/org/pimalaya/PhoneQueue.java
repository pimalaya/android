package org.pimalaya;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.pimalaya.client.PimalayaClient;

/**
 * The store's writes reaching the phone's contacts without a sync: a write
 * to a book queues its phone pass a second later, so a burst (an import, a
 * merge) coalesces into one pass per book.
 *
 * <p>Process-wide, on a thread of its own rather than the activity's, so a
 * pass queued just before the app is left still runs. No network and no
 * report; each pass takes its book's lock ({@link OfflineEngine}) and never
 * the process's {@link SyncLock}, so the mirror keeps up while a background
 * sync runs. A book the phone does not mirror has no Android account, and
 * its pass returns at once.
 */
final class PhoneQueue {
    /** How long a write waits for the ones following it. */
    static final long DELAY = 1000;

    /** The process's queue, once the app started it. */
    private static volatile PhoneQueue shared;

    private final Handler main;
    private final Executor runner;
    private final Consumer<String> pass;

    /** The books written since the last pass started. */
    private final Set<String> pending = new LinkedHashSet<>();

    private final Runnable flush = this::flush;

    PhoneQueue(Handler main, Executor runner, Consumer<String> pass) {
        this.main = main;
        this.runner = runner;
        this.pass = pass;
    }

    /** Starts the process's queue, once. */
    static synchronized void start(Context context) {
        if (shared != null) {
            return;
        }
        Context app = context.getApplicationContext();
        PimdirDb pimdir = new PimdirDb(app);
        CardStore base = new CardStore(app, pimdir);
        shared =
                new PhoneQueue(
                        new Handler(Looper.getMainLooper()),
                        Executors.newSingleThreadExecutor(),
                        url -> {
                            try {
                                new OfflineEngine(
                                                base, pimdir, new PimalayaClient(), null, null,
                                                app)
                                        .syncPhone(url, new OfflineEngine.Report());
                            } catch (Exception error) {
                                Log.w("pimalaya", "phone pass failed for " + url, error);
                            }
                        });
    }

    /** A book was written; nothing before the app started the queue. */
    static void written(String url) {
        PhoneQueue queue = shared;
        if (queue != null) {
            queue.queue(url);
        }
    }

    /** Queues the book's pass, the first write of a burst timing it. */
    void queue(String url) {
        synchronized (pending) {
            boolean first = pending.isEmpty();
            pending.add(url);
            if (!first) {
                return;
            }
        }
        main.postDelayed(flush, DELAY);
    }

    /**
     * Runs the pending passes. The set drains when the runner gets to it,
     * so a write landing while the task waits joins this round.
     */
    private void flush() {
        runner.execute(
                () -> {
                    List<String> urls;
                    synchronized (pending) {
                        urls = new ArrayList<>(pending);
                        pending.clear();
                    }
                    for (String url : urls) {
                        pass.accept(url);
                    }
                });
    }
}
