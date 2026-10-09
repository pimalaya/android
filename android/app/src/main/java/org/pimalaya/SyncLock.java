package org.pimalaya;

import java.util.concurrent.Semaphore;

/**
 * One sync at a time across the process: the app's own passes and the
 * background job ({@link BackgroundJob}) write the same store, and two
 * passes over one mailbox would only race each other.
 */
final class SyncLock {
    private static final Semaphore HELD = new Semaphore(1);

    private SyncLock() {}

    /** Takes the lock if nobody holds it. */
    static boolean take() {
        return HELD.tryAcquire();
    }

    /** Gives the lock back. */
    static void release() {
        HELD.release();
    }
}
