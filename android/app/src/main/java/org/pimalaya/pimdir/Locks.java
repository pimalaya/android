package org.pimalaya.pimdir;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/** The locks of one store ({@link PimdirDb#locks()}), the registry. */
public final class Locks {
    /**
     * The store's one writer: every storage yield of every driver holds it.
     *
     * <p>Mailboxes run side by side ({@code WorkerPool}) only to overlap the
     * network: one SQLite writer, each page's write landing whole.
     */
    public final Object store = new Object();

    /**
     * The search index's one writer ({@code PimdirIndexDb}): a refresh holds
     * it, so no two fold the store's feed at once (SEARCH §3).
     */
    public final Object index = new Object();

    /** Told each time a sync gives the sync lock back, null for nobody. */
    private volatile Runnable released;

    /**
     * One reentrant lock per synced collection, by kind and id.
     *
     * <p>The app, the background worker and the sync service may pass the same
     * collection; interleaved passes could double-push creates or clobber the
     * checkpoint. Reentrant so a pass's own phone passes nest under it.
     */
    private final ConcurrentHashMap<String, ReentrantLock> collections =
            new ConcurrentHashMap<>();

    /**
     * One sync at a time across the process.
     *
     * <p>The app's passes and the background job ({@code BackgroundJob})
     * write the same store.
     */
    private final Semaphore sync = new Semaphore(1);

    /**
     * The sync lock of one collection, by kind and id.
     *
     * <p>The kind is a {@link PimdirSummary} media type, so a book and a
     * calendar never share one.
     */
    public ReentrantLock collection(String kind, String id) {
        return collections.computeIfAbsent(kind + "\n" + id, key -> new ReentrantLock());
    }

    /** Takes the sync lock if nobody holds it. */
    public boolean takeSync() {
        return sync.tryAcquire();
    }

    /** Gives the sync lock back, then tells {@link #onRelease}'s listener. */
    public void releaseSync() {
        sync.release();
        Runnable listener = released;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Tells {@code listener} each time a sync ends, its pass written: what
     * refreshes the search index after each pass (SEARCH §4).
     */
    public void onRelease(Runnable listener) {
        released = listener;
    }
}
