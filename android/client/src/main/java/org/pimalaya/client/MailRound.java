package org.pimalaya.client;

import java.util.List;

/**
 * One mailbox enumerated: what moved, what went, and where the next
 * round resumes.
 *
 * <p>No envelopes. A spine says which messages a pass has to look at,
 * and looking at them is the fetch that follows, for those and no
 * others; a round that carried envelopes would be reading a mailbox to
 * find out that nothing in it changed.
 */
public final class MailRound {
    /** The messages that moved, each a UID and its markers. */
    public final List<MailRef> items;

    /** The UIDs the server reported expunged since the cursor. */
    public final List<String> vanished;

    /**
     * True when the round listed the whole window rather than a delta,
     * so the caller may retire what it did not mention.
     */
    public final boolean complete;

    /** Where the next round resumes, opaque to everyone above. */
    public final String checkpoint;

    public MailRound(
            List<MailRef> items, List<String> vanished, boolean complete, String checkpoint) {
        this.items = items;
        this.vanished = vanished;
        this.complete = complete;
        this.checkpoint = checkpoint;
    }
}
