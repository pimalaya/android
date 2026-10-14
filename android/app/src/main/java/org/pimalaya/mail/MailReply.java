package org.pimalaya.mail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A reply's and a forward's text decisions, apart from {@link MessageView}. */
final class MailReply {
    private MailReply() {}

    /** Who a reply goes to and who it copies, comma separated. */
    record People(String to, String cc) {}

    /**
     * A reply to everyone on the message, the account's own address left out.
     *
     * <p>A message the account sent answers its recipients instead. The two
     * headers are null before the body loads; the reply then goes to the sender
     * alone.
     */
    static People replyAll(
            String accountEmail, String fromAddress, String toHeader, String ccHeader) {
        String self = accountEmail.toLowerCase(Locale.ROOT);
        Set<String> to = new LinkedHashSet<>();
        Set<String> cc = new LinkedHashSet<>();
        boolean own = fromAddress.equalsIgnoreCase(self);
        if (!own && !fromAddress.isEmpty()) {
            to.add(fromAddress);
        }
        if (toHeader != null) {
            for (String address : addressesOf(toHeader)) {
                if (!address.equalsIgnoreCase(self)) {
                    to.add(address);
                }
            }
            for (String address : addressesOf(ccHeader)) {
                if (!address.equalsIgnoreCase(self) && !to.contains(address)) {
                    cc.add(address);
                }
            }
        }
        return new People(String.join(", ", to), String.join(", ", cc));
    }

    /** A subject carrying a prefix once, however many rounds it went. */
    static String prefixed(String prefix, String subject) {
        return subject.regionMatches(true, 0, prefix, 0, prefix.length())
                ? subject
                : prefix + " " + subject;
    }

    /** The bare addresses a header value names, display names dropped. */
    static List<String> addressesOf(String header) {
        List<String> addresses = new ArrayList<>();
        Matcher matcher =
                Pattern.compile("[^\\s<>,;\"]+@[^\\s<>,;\"]+").matcher(header);
        while (matcher.find()) {
            addresses.add(matcher.group());
        }
        return addresses;
    }

    /** Every line marked as quoted, the way RFC 3676 section 4.5 reads it. */
    static String quoted(String text) {
        StringBuilder quote = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            quote.append(line.startsWith(">") ? ">" : "> ").append(line).append('\n');
        }
        return quote.toString();
    }

    /** The `In-Reply-To` of a reply in a thread: its last `Message-ID`. */
    static String inReplyTo(List<String> thread) {
        return thread.get(thread.size() - 1);
    }

    /** A threaded reply's `References`: every `Message-ID`, space separated. */
    static String references(List<String> thread) {
        return String.join(" ", thread);
    }
}
