package org.pimalaya;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Prepares and cleans one contacts or calendar target's server through the
 * provider's own API, never through the app: the scratch collections a test
 * works in where the provider lets one be made, the items it seeds, and the
 * sweep that removes whatever carries its tag. Every write names a scratch
 * collection or a tagged item.
 */
interface E2eItemFixture {
    /**
     * Creates a scratch collection; a provider with one fixed collection
     * (Google's contacts) creates none, the test working in that one.
     */
    void createCollection(String name) throws Exception;

    /** Deletes a scratch collection and what it holds; quiet when it is gone. */
    void deleteCollection(String name) throws Exception;

    /** Seeds one item titled {@code title}: a contact's name, an event's summary. */
    void seed(String collection, String title) throws Exception;

    /** The titles of the items a collection holds that carry {@code text}. */
    List<String> titles(String collection, String text) throws Exception;

    /** Deletes for good every item and collection carrying the tag. */
    void sweep(String tag) throws Exception;

    /** Deletes what a run older than {@code before} left behind. */
    void sweepStale(LocalDateTime before) throws Exception;
}
