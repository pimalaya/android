package org.pimalaya;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * RFC 5322 date parsing, enough to sort a merged mail list.
 *
 * <p>The envelope {@code Date} arrives as the sender wrote it, and mail
 * in the wild is not disciplined about it: the day name is optional, the
 * zone may be an obsolete abbreviation, and the seconds may be missing.
 * Sorting is the only thing that depends on this, so an unreadable date
 * sinks the message to the bottom rather than failing the sync.
 */
final class MailDate {
    /** The spellings actually seen, most common first. */
    private static final String[] PATTERNS = {
        "EEE, d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "d MMM yyyy HH:mm:ss zzz",
    };

    /** Epoch milliseconds, or 0 when the date cannot be read. */
    static long toStamp(String raw) {
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
                // Try the next spelling.
            }
        }
        return 0;
    }

    private MailDate() {}
}
