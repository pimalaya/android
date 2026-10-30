package org.pimalaya.contact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the contacts filter reads off a card (cairn/changes/one-filter-panel):
 * whether it carries a property, its categories, its kind (RFC 6350).
 *
 * <p>Read off the unfolded text, a property named with or without a group
 * prefix ({@code item1.EMAIL}).
 */
final class CardFacts {
    private CardFacts() {}

    /** Whether {@code vcard} carries {@code property} (RFC 6350 3.3). */
    static boolean has(String vcard, String property) {
        return Pattern.compile(
                        "(?im)^(?:[A-Za-z0-9-]+\\.)?" + Pattern.quote(property) + "[;:]")
                .matcher(unfolded(vcard))
                .find();
    }

    /** Its {@code CATEGORIES}, every line's, trimmed, unescaped (6.7.1). */
    static List<String> categories(String vcard) {
        List<String> categories = new ArrayList<>();
        Matcher line =
                Pattern.compile("(?im)^(?:[A-Za-z0-9-]+\\.)?CATEGORIES(?:;[^:\\r\\n]*)?:(.*)$")
                        .matcher(unfolded(vcard));
        while (line.find()) {
            // NOTE: a comma escaped as \, belongs to the value.
            for (String value : line.group(1).split("(?<!\\\\),")) {
                String category = value.replace("\\,", ",").trim();
                if (!category.isEmpty()) {
                    categories.add(category);
                }
            }
        }
        return categories;
    }

    /** Its {@code KIND} lowercased (6.1.4), {@code individual} when unstated. */
    static String kind(String vcard) {
        Matcher line =
                Pattern.compile("(?im)^(?:[A-Za-z0-9-]+\\.)?KIND(?:;[^:\\r\\n]*)?:(.*)$")
                        .matcher(unfolded(vcard));
        return line.find() ? line.group(1).trim().toLowerCase(Locale.ROOT) : "individual";
    }

    /** The text with its folded lines joined back (RFC 6350 3.2). */
    private static String unfolded(String vcard) {
        return vcard.replaceAll("\\r?\\n[ \\t]", "");
    }
}
