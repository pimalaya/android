package org.pimalaya.client;

import org.json.JSONObject;

/**
 * An addressbook listed from the server (rs/contact.rs {@code Addressbook}).
 *
 * @param id the last non-empty path segment of the collection URL
 * @param name the display name, falling back to the id
 * @param url absolute collection URL, the target of every card operation
 * @param description free-form description, or null
 * @param color display colour, or null
 * @param role pimdir's role: {@link Calendar#DEFAULT} for the default book
 *     (JMAP {@code isDefault}, Graph's default, Google's {@code myContacts})
 * @param writable whether cards may be written; true where the source is mute
 */
public record Addressbook(
        String id,
        String name,
        String url,
        String description,
        String color,
        String role,
        boolean writable) {
    /** A book without a role, writable. */
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
