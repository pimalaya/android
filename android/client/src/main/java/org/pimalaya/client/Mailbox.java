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
 * @param name the collection key, hierarchical where the backend nests, a
 *     folder's own {@code /} or {@code %} escaped on Graph and JMAP
 * @param label the name a person reads, the key with those escapes undone
 * @param role {@code trash} for the RFC 6154 attribute, else empty: stored per
 *     sync, so an offline delete knows whether it is a move or a marker
 */
public record Mailbox(String name, String label, String role) {
    /** The role a mailbox holding the account's deleted messages carries. */
    public static final String TRASH = "trash";

    /** A mailbox read under its key, as IMAP and Gmail name it. */
    public Mailbox(String name, String role) {
        this(name, name, role);
    }

    static Mailbox from(JSONObject mailbox) {
        String name = Json.string(mailbox, "name");
        return new Mailbox(name, mailbox.optString("label", name), mailbox.optString("role"));
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
