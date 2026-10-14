package org.pimalaya.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One vCard of an addressbook, as stored on the server (rs/contact.rs
 * {@code Card} 68).
 *
 * @param id display identifier (resource name with any .vcf stripped)
 * @param uri resource name exactly as the server returned it, the
 *     addressing key of updates and deletes (servers need not suffix
 *     .vcf); null until the card has been fetched from the server
 * @param etag entity tag guarding concurrent updates, or null when unknown
 * @param vcard raw vCard text
 * @param books ids of the addressbooks the card is a member of, on the
 *     backends whose cards are account-level with m:n memberships (JMAP
 *     AddressBook ids, Google contact group ids); empty on the backends
 *     whose cards live in the one collection they were listed from
 *     (CardDAV, Graph)
 */
public record Card(String id, String uri, String etag, String vcard, List<String> books) {
    public Card(String id, String uri, String etag, String vcard) {
        this(id, uri, etag, vcard, Collections.emptyList());
    }

    /** Parses a `{id, uri, etag, vcard, books?}` reply into a card. */
    static Card from(JSONObject reply) {
        List<String> books = new ArrayList<>();
        JSONArray parsed = reply.optJSONArray("books");
        if (parsed != null) {
            for (int index = 0; index < parsed.length(); index++) {
                books.add(parsed.optString(index));
            }
        }

        return new Card(
                Json.string(reply, "id"),
                Json.string(reply, "uri"),
                Json.optString(reply, "etag"),
                Json.string(reply, "vcard"),
                books);
    }

    /** Parses a card-array reply. */
    static List<Card> list(JSONArray parsed) {
        List<Card> cards = new ArrayList<>(parsed.length());
        for (int index = 0; index < parsed.length(); index++) {
            cards.add(from(Json.object(parsed, index)));
        }
        return cards;
    }
}
