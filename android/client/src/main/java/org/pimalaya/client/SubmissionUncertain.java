package org.pimalaya.client;

/**
 * Raised when a submission failed after the handover: the server may have it.
 *
 * <p>Resending could deliver twice, so the outbox parks the message instead
 * of leaving it queued; the user sends it again once they find it did not go.
 */
public final class SubmissionUncertain extends PimalayaException {
    /** An uncertain submission, carrying the failure. */
    public SubmissionUncertain(String message) {
        super(message);
    }
}
