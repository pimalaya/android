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
 * The store's writes reaching the phone's contacts and calendars without a
 * sync: a write to a book or a calendar queues its phone pass a second
 * later, so a burst (an import, a merge, a series split) coalesces into one
 * pass per collection.
 *
 * <p>Process-wide, on a thread of its own rather than the activity's, so a
 * pass queued just before the app is left still runs. No network and no
 * report; each pass takes its collection's lock ({@link OfflineEngine},
 * {@link CalendarEngine}) and never the process's {@link SyncLock}, so the
 * mirror keeps up while a background sync runs. A collection the phone does
 * not mirror has no Android account or calendar row, and its pass returns at
 * once.
 */
final class PhoneQueue {
    /** How long a write waits for the ones following it. */
    static final long DELAY = 1000;

    /** The process's queue, once the app started it. */
    private static volatile PhoneQueue shared;

    private final Handler main;
    private final Executor runner;
    private final Consumer<String> book;
    private final Consumer<String> calendar;

    /** The books and the calendars written since the last pass started. */
    private final Set<String> books = new LinkedHashSet<>();
    private final Set<String> calendars = new LinkedHashSet<>();

    private final Runnable flush = this::flush;

    PhoneQueue(Handler main, Executor runner, Consumer<String> book, Consumer<String> calendar) {
        this.main = main;
        this.runner = runner;
        this.book = book;
        this.calendar = calendar;
    }

    /** Starts the process's queue, once. */
    static synchronized void start(Context context) {
        if (shared != null) {
            return;
        }
        Context app = context.getApplicationContext();
        PimdirDb pimdir = PimdirDb.shared(app);
        CardStore base = CardStore.shared(app);
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
                        },
                        collection -> CalendarEngine.phonePass(pimdir, collection));
    }

    /** A book was written; nothing before the app started the queue. */
    static void written(String url) {
        PhoneQueue queue = shared;
        if (queue != null) {
            queue.queue(url);
        }
    }

    /** A calendar was written; nothing before the app started the queue. */
    static void calendarWritten(String collection) {
        PhoneQueue queue = shared;
        if (queue != null) {
            queue.queueCalendar(collection);
        }
    }

    /** Queues the book's pass, the first write of a burst timing it. */
    void queue(String url) {
        queue(books, url);
    }

    /** Queues the calendar's pass, the same way. */
    void queueCalendar(String collection) {
        queue(calendars, collection);
    }

    private void queue(Set<String> pending, String id) {
        synchronized (books) {
            boolean first = books.isEmpty() && calendars.isEmpty();
            pending.add(id);
            if (!first) {
                return;
            }
        }
        main.postDelayed(flush, DELAY);
    }

    /**
     * Runs the pending passes. The sets drain when the runner gets to them,
     * so a write landing while the task waits joins this round.
     */
    private void flush() {
        runner.execute(
                () -> {
                    List<String> urls;
                    List<String> collections;
                    synchronized (books) {
                        urls = new ArrayList<>(books);
                        collections = new ArrayList<>(calendars);
                        books.clear();
                        calendars.clear();
                    }
                    for (String url : urls) {
                        book.accept(url);
                    }
                    for (String collection : collections) {
                        calendar.accept(collection);
                    }
                });
    }
}
