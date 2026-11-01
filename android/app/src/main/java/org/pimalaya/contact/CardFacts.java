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

    /**
     * What a card holds of the properties the search index records (pimdir
     * SEARCH §6): {@code TEL}, {@code ADR}, {@code BDAY}, {@code PHOTO},
     * and its categories folded.
     */
    static final class Indexed {
        /** The properties held, as {@link #has} names them. */
        final java.util.Set<String> has;

        /** Its categories, lowercased. */
        final List<String> categories;

        Indexed(java.util.Set<String> has, List<String> categories) {
            this.has = has;
            this.categories = categories;
        }
    }

    /** The properties the index records of a card's body. */
    static final String[] INDEXED = {"TEL", "ADR", "BDAY", "PHOTO"};

    /**
     * The index's answer read by collection and seq ({@link Entry#key}).
     */
    static java.util.Map<String, Indexed> of(org.json.JSONObject answer) {
        java.util.Map<String, Indexed> facts = new java.util.HashMap<>();
        org.json.JSONArray cards = answer == null ? null : answer.optJSONArray("cards");
        if (cards == null) {
            return null;
        }
        for (int position = 0; position < cards.length(); position++) {
            org.json.JSONObject card = cards.optJSONObject(position);
            java.util.Set<String> has = new java.util.HashSet<>();
            org.json.JSONArray held = card.optJSONArray("has");
            for (int at = 0; held != null && at < held.length(); at++) {
                has.add(held.optString(at));
            }
            List<String> categories = new ArrayList<>();
            org.json.JSONArray named = card.optJSONArray("categories");
            for (int at = 0; named != null && at < named.length(); at++) {
                categories.add(named.optString(at));
            }
            facts.put(
                    card.optString("collection") + "\n" + card.optLong("seq"),
                    new Indexed(has, categories));
        }
        return facts;
    }

    /**
     * The same facts read off each body, for a store whose index is refused
     * or absent: what the list read before the index answered it.
     */
    static java.util.Map<String, Indexed> fromBodies(List<Entry> entries) {
        java.util.Map<String, Indexed> facts = new java.util.HashMap<>();
        for (Entry entry : entries) {
            String vcard = entry.card().vcard();
            java.util.Set<String> has = new java.util.HashSet<>();
            for (String property : INDEXED) {
                if (has(vcard, property)) {
                    has.add(property);
                }
            }
            List<String> categories = new ArrayList<>();
            for (String category : categories(vcard)) {
                categories.add(category.toLowerCase(Locale.ROOT));
            }
            facts.put(entry.key(), new Indexed(has, categories));
        }
        return facts;
    }

    /** The text with its folded lines joined back (RFC 6350 3.2). */
    private static String unfolded(String vcard) {
        return vcard.replaceAll("\\r?\\n[ \\t]", "");
    }
}
