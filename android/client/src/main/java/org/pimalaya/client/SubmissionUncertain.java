package org.pimalaya.client;

/**
 * Raised when a submission failed after the whole message was handed over
 * and before any verdict came back: the server may have taken it.
 *
 * <p>Its own type because sending such a message again on its own could
 * deliver it twice, and leaving it queued would do exactly that: the
 * outbox parks it instead, for the user to send again once they find it
 * did not go.
 */
public final class SubmissionUncertain extends PimalayaException {
    public SubmissionUncertain(String message) {
        super(message);
    }
}
