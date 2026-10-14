package org.pimalaya.pimdir;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Message date parsing, enough to sort a merged mail list.
 *
 * <p>IMAP's envelope {@code Date} (loose RFC 5322) and JMAP's
 * {@code receivedAt} (RFC 3339) sort in one column, so both must parse; an
 * unreadable date sinks the message to the bottom, never fails the sync.
 */
public final class MailDate {
    /**
     * The spellings seen, most common first.
     *
     * <p>RFC 5322 (IMAP), then RFC 3339 (JMAP): neither parses the other's
     * shape, so the order is only cost.
     */
    private static final String[] PATTERNS = {
        "EEE, d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "d MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    };

    /** Epoch milliseconds, or 0 when the date cannot be read. */
    public static long toStamp(String raw) {
        if (raw == null) {
            return 0;
        }

        // NOTE: a trailing "(CEST)" style comment is legal and defeats
        // every pattern, so it goes before parsing.
        String cleaned = raw.trim();
        int comment = cleaned.indexOf('(');
        if (comment > 0) {
            cleaned = cleaned.substring(0, comment).trim();
        }
        if (cleaned.isEmpty()) {
            return 0;
        }

        for (String pattern : PATTERNS) {
            try {
                // NOTE: US locale on purpose. The month and day names in
                // a Date header are English by the RFC, whatever the
                // device language is.
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(true);
                Date parsed = format.parse(cleaned);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }

    private MailDate() {}
}
