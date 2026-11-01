package org.pimalaya.client;

/**
 * Consumer seam of the io-pimdir engine, served once per engine yield.
 *
 * <p>The bridge upcalls {@link #serve} with one JSON envelope per yield
 * (store reads and writes, remote enumerate, fetch, push) and takes its JSON
 * reply; a reply with an {@code error} field aborts the operation with it.
 */
public interface OfflineDriver {
    /** Services one engine yield, returning its JSON reply. */
    String serve(String yieldJson);

    /**
     * Whether the user accepted the absences a round of {@code collection}
     * finds, so one that would park closes instead (pimdir SYNC §5); asked
     * once per sync, before it runs.
     */
    default boolean acceptAbsences(String collection) {
        return false;
    }

    /** Told what one sync of {@code collection} did, once it returned. */
    default void synced(String collection, SyncReport report) {}
}
