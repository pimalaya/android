package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One mailbox an account holds, and what the server says it is for
 * (rs/mail.rs {@code Mailbox} 157).
 *
 * @param name the name a collection is keyed by, hierarchical where the
 *     backend nests
 * @param role {@code trash} where the server marks the mailbox with the
 *     RFC 6154 attribute of that name, empty where it marks it another or
 *     none.
 *     <p>The one role a write needs: which mailbox a delete moves into. It
 *     is read once per sync and stored, because a delete has to decide
 *     between a move and a marker with no network to ask.
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
