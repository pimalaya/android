package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * An account-wide delta projected onto one book.
 *
 * <p>Mirrors rs/contact/sync.rs {@code AccountSnapshot} 111.
 *
 * @param members the book's members, as indexes in the facts' {@code changed}
 * @param vanished the handles gone from the book: the delta's own, then the
 *     known cards that left it
 */
public record AccountSnapshot(List<Integer> members, List<String> vanished) {
    static AccountSnapshot from(JSONObject json) {
        return new AccountSnapshot(
                Json.integers(json.optJSONArray("members")),
                Json.strings(json.optJSONArray("vanished")));
    }
}
