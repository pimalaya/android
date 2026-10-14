package org.pimalaya.client;

import org.json.JSONObject;

/**
 * An addressbook listed from the server (rs/contact.rs {@code Addressbook}
 * 45).
 *
 * @param id the last non-empty path segment of the collection URL
 * @param name the display name, falling back to the id
 * @param url absolute collection URL, the target of every card operation
 * @param description free-form description, or null
 * @param color display colour, or null
 * @param role what the source says the book is for, in pimdir's role
 *     vocabulary: {@link Calendar#DEFAULT} for its default book (JMAP
 *     {@code isDefault}, Graph's default contacts folder, Google's
 *     {@code myContacts}), empty otherwise
 * @param writable whether the user may write cards into it; true where the
 *     source says nothing
 */
public record Addressbook(
        String id,
        String name,
        String url,
        String description,
        String color,
        String role,
        boolean writable) {
    public Addressbook(String id, String name, String url, String description, String color) {
        this(id, name, url, description, color, "", true);
    }

    static Addressbook from(JSONObject book) {
        return new Addressbook(
                Json.string(book, "id"),
                Json.string(book, "name"),
                Json.string(book, "url"),
                Json.optString(book, "description"),
                Json.optString(book, "color"),
                book.optString("role"),
                book.optBoolean("writable", true));
    }
}
