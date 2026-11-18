package org.pimalaya.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One vCard of an addressbook, as stored on the server.
 *
 * <p>Mirrors rs/contact.rs {@code Card} 68.
 *
 * @param id display identifier (resource name with any .vcf stripped)
 * @param uri resource name as the server returned it, the key of updates and
 *     deletes (no .vcf suffix assumed); null until fetched from the server
 * @param etag entity tag guarding concurrent updates, or null when unknown
 * @param vcard the vCard as text, read lossily where its bytes are not
 *     UTF-8: what the phone is shown
 * @param vcardBase64 the bytes the server sent, as base64, where they are
 *     not UTF-8; null otherwise
 * @param books the card's addressbook ids on account-level backends (JMAP
 *     AddressBook, Google contact group); empty on CardDAV and Graph
 */
public record Card(
        String id,
        String uri,
        String etag,
        String vcard,
        String vcardBase64,
        List<String> books) {
    /** A card whose bytes are its text. */
    public Card(String id, String uri, String etag, String vcard, List<String> books) {
        this(id, uri, etag, vcard, null, books);
    }

    /** A card in no account-level book. */
    public Card(String id, String uri, String etag, String vcard) {
        this(id, uri, etag, vcard, null, Collections.emptyList());
    }

    /** The bytes the server sent: what the store keeps and hashes. */
    public byte[] bytes() {
        return vcardBase64 == null
                ? vcard.getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(vcardBase64);
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
                Json.optString(reply, "vcardBase64"),
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
