package org.pimalaya;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One message a fixture seeds into a scratch mailbox, as RFC 5322 bytes.
 *
 * <p>From the account to itself, its subject carrying the tag, its `Message-ID`
 * under a domain nobody delivers to.
 */
final class E2eMessage {
    static final DateTimeFormatter RFC_5322 =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);

    final String address;
    final String subject;
    final String messageId;

    /** The `Date` header; now unless a test needs another. */
    ZonedDateTime date = ZonedDateTime.now();

    /** The header as written, overriding {@link #subject} (an encoded word). */
    String rawSubject;

    /** The top-level type; a `multipart/mixed` one carries no attachment. */
    boolean mixed;

    /** Seeded already read. */
    boolean seen;

    /** Keywords the app does not model, seeded where the target takes them. */
    final List<String> keywords = new ArrayList<>();

    String body = "Sent by the Pimalaya Android end-to-end suite.";

    E2eMessage(String address, String subject, String messageId) {
        this.address = address;
        this.subject = subject;
        this.messageId = messageId;
    }

    byte[] bytes() {
        StringBuilder message = new StringBuilder();
        message.append("Date: ").append(RFC_5322.format(date)).append("\r\n");
        message.append("From: Pimalaya e2e <").append(address).append(">\r\n");
        message.append("To: ").append(address).append("\r\n");
        message.append("Subject: ").append(rawSubject != null ? rawSubject : subject).append("\r\n");
        message.append("Message-ID: ").append(messageId).append("\r\n");
        message.append("MIME-Version: 1.0\r\n");
        if (mixed) {
            message.append("Content-Type: multipart/mixed; boundary=\"e2e\"\r\n\r\n");
            message.append("--e2e\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n");
            message.append(body).append("\r\n--e2e--\r\n");
        } else {
            message.append("Content-Type: text/plain; charset=utf-8\r\n\r\n");
            message.append(body).append("\r\n");
        }
        return message.toString().getBytes(StandardCharsets.UTF_8);
    }
}
