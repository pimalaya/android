package org.pimalaya.client;

/**
 * Raised when a stored body's bytes fail the name its row gives it: a part a
 * killed copy left, other bytes or a link (pimdir STORAGE §7).
 *
 * <p>Nothing of it was parsed nor written out. Its own type, from the
 * bridge's {@code failing} field, so no prose is matched: the owner moves the
 * file into quarantine/ and fetches the body again.
 */
public final class BodyFailing extends PimalayaException {
    /** A failure carrying the bridge's message, which names the body. */
    public BodyFailing(String message) {
        super(message);
    }
}
