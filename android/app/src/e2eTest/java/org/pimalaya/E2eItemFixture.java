package org.pimalaya;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Prepares and cleans a contacts or calendar target through the provider's API.
 *
 * <p>Never through the app: scratch collections where allowed, seeded items,
 * and the sweep of whatever carries the tag. Every write names a scratch
 * collection or a tagged item.
 */
interface E2eItemFixture {
    /** Creates a scratch collection; none where it is fixed (Google's). */
    void createCollection(String name) throws Exception;

    /** Deletes a scratch collection and its items; quiet when gone. */
    void deleteCollection(String name) throws Exception;

    /**
     * Seeds one item titled {@code title}: a contact's name, event's summary.
     */
    void seed(String collection, String title) throws Exception;

    /** The titles of the items a collection holds that carry {@code text}. */
    List<String> titles(String collection, String text) throws Exception;

    /** Deletes for good every item and collection carrying the tag. */
    void sweep(String tag) throws Exception;

    /** Deletes what a run older than {@code before} left behind. */
    void sweepStale(LocalDateTime before) throws Exception;
}
