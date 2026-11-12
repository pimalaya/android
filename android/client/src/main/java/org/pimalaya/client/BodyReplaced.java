package org.pimalaya.client;

/**
 * Raised when an edit names a body the item no longer holds: another write,
 * a sync's most often, replaced it since the edit's page read it (SYNC §7).
 *
 * <p>Nothing was staged. Its own type, from the engine's {@code
 * body-replaced} code, so no prose is matched.
 */
public final class BodyReplaced extends PimalayaException {
    /** The engine's code for the refusal. */
    static final String CODE = "body-replaced";

    /** A refusal carrying the engine's message. */
    public BodyReplaced(String message) {
        super(message);
    }
}
