package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One calendar enumerated from a cursor: which events moved, which went,
 * and where the next round resumes (rs/calendar.rs {@code EventDelta}
 * 117).
 *
 * <p>The calendar twin of {@link CardDelta}, and no bodies, for the same
 * reason: a pass asks which members changed and reads the ones it has to.
 * Except where the round had to read them anyway: Google's and JMAP's
 * complete rounds list every event whole and hand the bodies over in
 * {@link #bodies}, rather than have each one read a second time.
 *
 * @param changed resource names created or updated since the cursor, with
 *     ETags
 * @param bodies the bodies the round already held, under the same names
 *     and at the same revisions as {@link #changed}; empty where it listed
 *     names alone (CalDAV, Graph)
 * @param vanished resource names removed since the cursor
 * @param token the next cursor, or null when the backend issued none
 * @param complete true when the round listed the complete member set: an
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
