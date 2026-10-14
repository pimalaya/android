package org.pimalaya.phone;

import android.content.Context;
import android.os.Handler;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The store's writes reaching the phone a second later, without a sync.
 *
 * <p>A burst (import, merge, split) coalesces into one pass per collection, on
 * a thread outliving the activity. A pass takes its collection's lock, never
 * the sync lock ({@code Locks.takeSync}), so it runs during a sync.
 */
public final class PhoneQueue {
    /** The process holding the queue, which the app starts. */
    public interface Owner {
        /** The process's queue, null until the app started it. */
        PhoneQueue phoneQueue();
    }

    /** How long a write waits for the ones following it. */
    static final long DELAY = 1000;

    private final Handler main;
    private final Executor runner;
    private final Consumer<String> book;
    private final Consumer<String> calendar;

    /** The books and the calendars written since the last pass started. */
    private final Set<String> books = new LinkedHashSet<>();
    private final Set<String> calendars = new LinkedHashSet<>();

    private final Runnable flush = this::flush;

    /**
     * A queue posting on {@code main} and running passes on {@code runner}.
     *
     * <p>{@code book} and {@code calendar} run one collection's phone pass.
     */
    public PhoneQueue(
            Handler main, Executor runner, Consumer<String> book, Consumer<String> calendar) {
        this.main = main;
        this.runner = runner;
        this.book = book;
        this.calendar = calendar;
    }

    /** A book was written; nothing before the app started the queue. */
    public static void written(Context context, String url) {
        PhoneQueue queue = of(context);
        if (queue != null) {
            queue.queue(url);
        }
    }

    /** A calendar was written; nothing before the app started the queue. */
    public static void calendarWritten(Context context, String collection) {
        PhoneQueue queue = of(context);
        if (queue != null) {
            queue.queueCalendar(collection);
        }
    }

    /**
     * The process's queue; null until started, or for an isolated test device.
     */
    private static PhoneQueue of(Context context) {
        Context app = context.getApplicationContext();
        return app instanceof Owner ? ((Owner) app).phoneQueue() : null;
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

    /** Runs the pending passes; a write landing meanwhile joins this round. */
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
