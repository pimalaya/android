package org.pimalaya.client;

import org.json.JSONObject;

/**
 * One account's live mail connection: the sockets, and the native session
 * that has already greeted and authenticated over them.
 *
 * <p>Held for a whole pass rather than a verb. IMAP is a conversation, so
 * reusing the socket without reusing the session would replay a greeting
 * the server has already answered and block until the read timed out; the
 * session is what makes the socket worth keeping. A pass that walks an
 * account and then writes three markers is one connection here, where it
 * was four.
 *
 * <p>The handle is a pointer into the bridge and carries a pointer's
 * contract: one owner, one thread at a time, freed once. This class is
 * that owner. {@link #close} frees it and closes the sockets, and is
 * idempotent, so try-with-resources is the only way it should be used.
 *
 * <p>A held connection is a hint and never a promise: a server's idle
 * timeout, a rebound NAT and a walk from wifi to cellular all end one
 * under the app. {@link #reopen} is what a caller runs when a verb fails
 * on a session it was holding, and {@link PimalayaClient} does it once
 * before reporting the failure.
 */
public final class MailSession implements AutoCloseable {
    private final Account account;
    private Transport transport;
    private long handle;
    private long run;
    private boolean expungesOne;

    MailSession(Account account, Transport transport, long handle, boolean expungesOne) {
        this.account = account;
        this.transport = transport;
        this.handle = handle;
        this.expungesOne = expungesOne;
    }

    /** The account this session reads and writes for. */
    public Account account() {
        return account;
    }

    /**
     * Whether a delete for good erases one message alone: every HTTP
     * backend, an IMAP server only with UIDPLUS (RFC 4315), whose plain
     * EXPUNGE would take every message marked deleted.
     */
    public boolean expungesOne() {
        return expungesOne;
    }

    /** The transport its sockets live in, for the verbs that run on it. */
    Transport transport() {
        return transport;
    }

    /** The bridge's session, for the verbs that run on it. */
    long handle() {
        return handle;
    }

    /**
     * Has the session work for the pool run numbered {@code run}, sharing
     * with the run's other sessions what it reads of a Gmail account; 0
     * leaves the run. Kept across {@link #reopen}.
     */
    public void join(long run) {
        this.run = run;
        if (handle != 0) {
            Native.joinMailRun(handle, run);
        }
    }

    /**
     * Drops the connection and opens another, for a caller that has just
     * been refused by a server that had already hung up.
     *
     * <p>The sockets go with it: a session on a dead socket and a socket
     * under a dead session are the same broken pair, and reopening half
     * of it would leave the other half claiming a conversation the server
     * never had.
     */
    void reopen() {
        release();
        MailSession opened = PimalayaClient.openMail(account);
        this.transport = opened.transport;
        this.handle = opened.handle;
        this.expungesOne = opened.expungesOne;
        // The freshly opened one must not free what this one now owns.
        opened.handle = 0;
        opened.transport = null;
        if (run != 0) {
            Native.joinMailRun(handle, run);
        }
    }

    @Override
    public void close() {
        release();
    }

    private void release() {
        if (handle != 0) {
            Native.closeMailSession(handle);
            handle = 0;
        }
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    /** The handle a bridge reply names, or a failure. */
    static long handleOf(JSONObject reply) {
        return reply.optLong("handle");
    }
}
