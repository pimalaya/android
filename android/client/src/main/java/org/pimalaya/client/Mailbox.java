package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One mailbox an account holds, and what the server says it is for.
 *
 * <p>Mirrors rs/mail.rs {@code Mailbox} 157.
 *
 * @param name the collection key, hierarchical where the backend nests
 * @param role {@code trash} for the RFC 6154 attribute, else empty: stored per
 *     sync, so an offline delete knows whether it is a move or a marker
 */
public record Mailbox(String name, String role) {
    /** The role a mailbox holding the account's deleted messages carries. */
    public static final String TRASH = "trash";

    static Mailbox from(JSONObject mailbox) {
        return new Mailbox(Json.string(mailbox, "name"), mailbox.optString("role"));
    }

    /** Parses a mailbox-array reply. */
    static List<Mailbox> list(JSONArray reply) {
        List<Mailbox> mailboxes = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            mailboxes.add(from(Json.object(reply, index)));
        }
        return mailboxes;
    }
}
