package org.pimalaya.pimdir;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/** The locks of one store ({@link PimdirDb#locks}), the process's one registry. */
public final class Locks {
    /**
     * The store's one writer: every storage yield of every driver (a load,
     * a lookup, a write) is answered holding it.
     *
     * <p>A pass runs an account's mailboxes side by side ({@link MailPool}),
     * each on its own engine and session, and only their network is meant
     * to overlap: one SQLite writer, a page's write landing whole before the
     * next one starts, as when the mailboxes ran one after another.
     */
    public final Object store = new Object();

    /**
     * One lock per collection a pass syncs, by kind and id
     * ({@link #collection}): the in-app sync, the background worker and the
     * OS-scheduled sync service can each run an engine pass over the same
     * book or calendar, and a pass spans many store transactions, so
     * interleaved passes could double-push staged creates or clobber the
     * sync checkpoint. Reentrant, so a pass's own phone passes nest freely
     * under its hold.
     */
    private final ConcurrentHashMap<String, ReentrantLock> collections =
            new ConcurrentHashMap<>();

    /**
     * One sync at a time across the process: the app's own passes and the
     * background job ({@link BackgroundJob}) write the same store, and two
     * passes over one mailbox would only race each other.
     */
    private final Semaphore sync = new Semaphore(1);

    /**
     * The sync lock of one collection, keyed by its kind (a
     * {@link PimdirSummary} media type) and its id, so a book and a
     * calendar never share one.
     */
    public ReentrantLock collection(String kind, String id) {
        return collections.computeIfAbsent(kind + "\n" + id, key -> new ReentrantLock());
    }

    /** Takes the sync lock if nobody holds it. */
    public boolean takeSync() {
        return sync.tryAcquire();
    }

    /** Gives the sync lock back. */
    public void releaseSync() {
        sync.release();
    }
}
