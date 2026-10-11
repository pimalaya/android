package org.pimalaya;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Prepares and cleans one target's server through the provider's own API,
 * never through the app: the scratch mailboxes a test works in, the messages
 * it seeds, and the sweep that removes whatever carries its tag. Every write
 * names a scratch mailbox or a tagged message, so nothing else on the account
 * is ever touched.
 */
interface E2eFixture extends AutoCloseable {
    /** What every scratch mailbox name and message tag starts with. */
    String PREFIX = "pimalaya-e2e-";

    /** The timestamp a tag carries after the prefix. */
    DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    void createMailbox(String name) throws Exception;

    /** Deletes a scratch mailbox and what it holds; quiet when it is gone. */
    void deleteMailbox(String name) throws Exception;

    void seed(String mailbox, E2eMessage message) throws Exception;

    /** Seeds one message filed in several mailboxes at once: Gmail labels. */
    default void seed(List<String> mailboxes, E2eMessage message) throws Exception {
        throw new UnsupportedOperationException("no labels on this target");
    }

    /** The keywords the server holds on the message with this subject. */
    default List<String> keywords(String mailbox, String subject) throws Exception {
        throw new UnsupportedOperationException("no keywords on this target");
    }

    /** Takes a message out of a mailbox elsewhere, keeping it: a Gmail archive. */
    default void unfile(String mailbox, String subject) throws Exception {
        throw new UnsupportedOperationException("no labels on this target");
    }

    /**
     * Where the server files the messages with this subject, read through
     * the provider's own API: mailbox names, Gmail label names (a system
     * label named by its id, `TRASH`).
     */
    List<String> where(String subject) throws Exception;

    /** Deletes for good every message whose subject carries the tag, account-wide. */
    void sweep(String tag) throws Exception;

    /**
     * Deletes what a run older than {@code before} left behind: its scratch
     * mailboxes, named by their tag's timestamp, and its tagged messages.
     */
    void sweepStale(LocalDateTime before) throws Exception;

    @Override
    default void close() {}

    /** Whether a scratch name or tag was minted before {@code before}. */
    static boolean stale(String name, LocalDateTime before) {
        int at = name.lastIndexOf(PREFIX);
        if (at < 0 || name.length() < at + PREFIX.length() + 14) {
            return false;
        }
        String stamp = name.substring(at + PREFIX.length(), at + PREFIX.length() + 14);
        try {
            return LocalDateTime.parse(stamp, STAMP).isBefore(before);
        } catch (RuntimeException unparsable) {
            return false;
        }
    }
}
