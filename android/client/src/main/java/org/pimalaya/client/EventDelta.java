package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One calendar enumerated from a cursor: events moved, gone, next cursor.
 *
 * <p>Mirrors rs/calendar.rs {@code EventDelta} 117. Like {@link CardDelta}
 * it carries no bodies, except the ones Google's and JMAP's complete rounds
 * read anyway ({@link #bodies}), spared a second read.
 *
 * @param changed resource names created or updated since the cursor, with ETags
 * @param bodies bodies already held, keyed and revised as {@link #changed};
 *     empty where the round listed names alone (CalDAV, Graph)
 * @param vanished resource names removed since the cursor
 * @param token the next cursor, or null when the backend issued none
 * @param complete true when the round listed the whole member set: an
 *     initial round, or an expired cursor re-run as one
 */
public record EventDelta(
        List<EventRef> changed,
        List<Event> bodies,
        List<String> vanished,
        String token,
        boolean complete) {
    static EventDelta from(JSONObject reply) {
        JSONArray listed = reply.optJSONArray("changed");
        List<EventRef> changed = new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            changed.add(EventRef.from(Json.object(listed, index)));
        }

        return new EventDelta(
                changed,
                Event.list(reply.optJSONArray("bodies")),
                Json.strings(reply.optJSONArray("vanished")),
                Json.optString(reply, "token"),
                reply.optBoolean("complete"));
    }
}
