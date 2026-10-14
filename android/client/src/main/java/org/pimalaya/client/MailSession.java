package org.pimalaya.client;

/**
 * One account's live mail connection: sockets plus an authenticated session.
 *
 * <p>Held for a pass, not a verb: reused sockets without the session would
 * replay an answered IMAP greeting. The handle is a bridge pointer: one owner,
 * one thread at a time, freed once by {@link #close} (try-with-resources).
 */
public final class MailSession implements AutoCloseable {
    private final Account account;
    private Transport transport;
    private long handle;
    private long run;
    private boolean expungesOne;

    /** The transport's losses when a verb last answered ({@link #answered}). */
    private int answeredLosses;

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
     * Whether a delete for good erases one message alone.
     *
     * <p>Every HTTP backend; IMAP only with UIDPLUS (RFC 4315), since a plain
     * EXPUNGE takes every message marked deleted.
     */
    public boolean expungesOne() {
        return expungesOne;
    }

    /** The transport its sockets live in, for the verbs that run on it. */
    Transport transport() {
        return transport;
    }

    /**
     * Whether its transport lost a connection since a verb last answered.
     *
     * <p>A failure since is the connection's ({@link Transport#losses}): an
     * IMAP session on a lost socket is dead whichever verb lost it.
     */
    boolean lostSinceAnswered() {
        return transport != null && transport.losses() != answeredLosses;
    }

    /** Notes that a verb answered, on whatever connections are held now. */
    void answered() {
        answeredLosses = transport == null ? 0 : transport.losses();
    }

    /** The bridge's session, for the verbs that run on it. */
    long handle() {
        return handle;
    }

    /**
     * Has the session work for the pool run numbered {@code run}; 0 leaves it.
     *
     * <p>The run's sessions share what they read of a Gmail account. Kept
     * across {@link #reopen}.
     */
    public void join(long run) {
        this.run = run;
        if (handle != 0) {
            Native.joinMailRun(handle, run);
        }
    }

    /**
     * Reopens the connection, as {@link MailClient} does once on a failed verb.
     *
     * <p>The sockets go too: a session and its sockets are one pair, and
     * reopening half would leave the other claiming a conversation the server
     * never had.
     */
    void reopen() {
        release();
        MailSession opened = MailClient.openMail(account);
        this.transport = opened.transport;
        this.handle = opened.handle;
        this.expungesOne = opened.expungesOne;
        this.answeredLosses = 0;
        // NOTE: the freshly opened one must not free what this one now owns.
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
}
