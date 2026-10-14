package org.pimalaya.client;

/**
 * Raised when a server refuses one message for good.
 *
 * <p>A refusal is the server's final word (a 5yz reply, RFC 5321 4.2.1): the
 * outbox parks the message with what it said, while any other failure leaves
 * it queued for the next drain. Its own type so no prose is matched.
 */
public final class SubmissionRefused extends PimalayaException {
    /** A refusal carrying what the server said. */
    public SubmissionRefused(String message) {
        super(message);
    }
}
