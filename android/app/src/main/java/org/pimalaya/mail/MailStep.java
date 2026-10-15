package org.pimalaya.mail;

import org.pimalaya.client.Account;

import java.util.List;

/**
 * One step of an account's mail: about {@link #COUNT} older messages, whole.
 *
 * <p>Per backend, on the account's session, never an offset; "Load more" and
 * the first sync each take one. Bodies cross as files
 * ({@code MailEngine.sources}), stored by {@link MailEngine#download}.
 */
public interface MailStep {
    /** About how many messages a step brings an account. */
    int COUNT = 50;

    /** One message a step brought, as {@link MailWindow#frontier} reads it. */
    final class Brought {
        /** Its {@code Date}, an RFC 3339 instant, null or empty for none. */
        final String date;

        /** When it was received, epoch milliseconds, null where unknown. */
        final Long received;

        Brought(String date, Long received) {
            this.date = date;
            this.received = received;
        }
    }

    /** What a step left. */
    final class Outcome {
        /** The frontier it leaves, null where it brought nothing to move it. */
        public final String frontier;

        /** Whether nothing older is left in the account or mailbox stepped. */
        public final boolean exhausted;

        /** How many messages it brought. */
        public final int brought;

        Outcome(String frontier, boolean exhausted, int brought) {
            this.frontier = frontier;
            this.exhausted = exhausted;
            this.brought = brought;
        }
    }

    /**
     * Runs one step on the engine's session over the mailboxes of {@code run}.
     */
    Outcome run(MailEngine engine, StepRun run) throws Exception;

    /**
     * The step of an account's mail backend, as its facts say it walks.
     *
     * <p>Gmail and JMAP step through the account as one listing
     * ({@link GmailStep}).
     */
    static MailStep of(Account server) {
        switch (server.info().mailStep()) {
            case ACCOUNT:
                return new GmailStep();
            case MAILBOXES:
                return new GraphStep();
            default:
                return new ImapStep();
        }
    }
}
