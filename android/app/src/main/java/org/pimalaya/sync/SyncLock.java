package org.pimalaya.sync;

import java.util.concurrent.Semaphore;

/**
 * One sync at a time across the process: the app's own passes and the
 * background job ({@link BackgroundJob}) write the same store, and two
 * passes over one mailbox would only race each other.
 */
public final class SyncLock {
    private static final Semaphore HELD = new Semaphore(1);

    private SyncLock() {}

    /** Takes the lock if nobody holds it. */
    public static boolean take() {
        return HELD.tryAcquire();
    }

    /** Gives the lock back. */
    public static void release() {
        HELD.release();
    }
}
