package org.pimalaya.client;

/**
 * Raised when a server refuses one message for good.
 *
 * <p>Its own type because the outbox has two answers for a failed send
 * and no way to tell them apart from prose. A refusal is the server's
 * final word on this message (a 5yz reply, RFC 5321 section 4.2.1), so
 * the message is parked carrying what it said; every other failure is
 * the environment's, and leaves the message queued for the next drain.
 */
public final class SubmissionRefused extends PimalayaException {
    public SubmissionRefused(String message) {
        super(message);
    }
}
