package org.pimalaya.mail;

import org.pimalaya.client.Account;
import org.pimalaya.client.PimalayaClient;

import java.util.List;

/**
 * One step of an account's mail: about {@link #COUNT} messages older than
 * what it holds, brought whole (headers and body, plain {@code Full}), on
 * the account's one session, small batches one after another. The list
 * shows what the steps brought ({@link MailWindow}), and its "Load more"
 * takes the next step; the first sync is one step.
 *
 * <p>Each backend has its own: what is "older than what it holds" and how
 * the bodies are batched are the backend's, the count approximate (new
 * arrivals between two steps are fine) and never an offset. A step leaves
 * a frontier ({@link Outcome#frontier}), which the caller moves the
 * account's back to.
 *
 * <p>The bridge's contract every backend shares: bodies cross as files,
 * {@code fetchMessageSources(handle, collection, ids, dir)} answering each
 * message's file ({@link MailEngine#sources}), stored as the object it is
 * ({@link MailEngine#download}); a step's messages land through the
 * engine as a listing's do ({@link MailEngine#land}).
 */
public interface MailStep {
    /** About how many messages a step brings an account. */
    int COUNT = 50;

    /** One message a step brought, as its frontier reads it ({@link MailWindow#frontier}). */
    final class Brought {
        /** Its {@code Date}, an RFC 3339 instant, null or empty for none. */
        final String date;

        /** When it was received, in milliseconds since the epoch, null where unknown. */
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

        /**
         * Whether nothing older is left in what it stepped: the account held
         * whole, or the one mailbox it was asked for.
         */
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
     * Runs one step of the account {@code accountEmail} on the engine's
     * session: {@code collections} its mailboxes in the order a pass takes
     * them ({@link MailEngine#ordered}), {@code only} the one mailbox a
     * view filtered to it steps (null for the account), {@code first} for
     * the first sync, which opens every mailbox.
     */
    Outcome run(
            MailEngine engine,
            String accountEmail,
            List<String> collections,
            String only,
            boolean first)
            throws Exception;

    /**
     * The step of an account's mail backend: Gmail and JMAP step through
     * the account as one listing, each message placed in every mailbox it
     * is filed in ({@link GmailStep}).
     */
    static MailStep of(Account server) {
        if (PimalayaClient.isGoogle(server) || PimalayaClient.isJmap(server)) {
            return new GmailStep();
        }
        return PimalayaClient.isGraph(server) ? new GraphStep() : new ImapStep();
    }
}
