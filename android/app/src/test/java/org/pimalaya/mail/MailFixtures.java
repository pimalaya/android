package org.pimalaya.mail;

import java.util.ArrayList;
import java.util.List;

/** The mail store read back whole, for the tests. */
final class MailFixtures {
    private MailFixtures() {}

    /**
     * A message to queue from {@code from}, its {@code Message-ID}, subject and
     * date (RFC 5322, none when null) what the outbox lists it by.
     */
    static byte[] outgoing(String from, String messageId, String subject, String date) {
        StringBuilder message = new StringBuilder();
        if (date != null) {
            message.append("Date: ").append(date).append("\r\n");
        }
        message.append("Message-ID: <").append(messageId).append(">\r\n")
                .append("From: ").append(from).append("\r\n")
                .append("To: bob@example.org\r\n")
                .append("Subject: ").append(subject).append("\r\n\r\n")
                .append("the body\r\n");
        return message.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Queues {@link #outgoing} from {@code from} in its outbox. */
    static void queue(
            MailStore store, String from, String messageId, String subject, String date) {
        store.outbox().queueSubmission(from, outgoing(from, messageId, subject, date));
    }

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
