package org.pimalaya.mail;

import java.util.ArrayList;
import java.util.List;

/** The mail store read back whole, for the tests. */
final class MailFixtures {
    private MailFixtures() {}

    /**
     * Every account's messages: the waiting ones on top, the rest newest first.
     *
     * <p>Waiting messages come from the queue (STORAGE section 15.4), the rest
     * by sort key, at most {@code limit} of them.
     */
    static List<MailStore.StoredMessage> merged(MailStore store, int limit) {
        List<MailStore.StoredMessage> messages = new ArrayList<>(store.outbox().outgoing());
        messages.addAll(
                store.page(
                        store.query((account, mailbox) -> true, false, false, ""),
                        null,
                        0,
                        limit));
        return messages;
    }
}
