package org.pimalaya.client;

/** Raised when a bridge operation fails (connection, auth, server error). */
public class PimalayaException extends RuntimeException {
    /**
     * HTTP status of the failed round, null when the failure was not HTTP.
     *
     * <p>Callers branch on it instead of the prose: 412 retries unguarded,
     * 404 converges a removal, 401 refreshes the token.
     */
    public final Integer status;

    /** A failure that is not an HTTP one. */
    public PimalayaException(String message) {
        this(message, null);
    }

    /** A failure with its HTTP status, null for none. */
    public PimalayaException(String message, Integer status) {
        super(message);
        this.status = status;
    }
}
