package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One calendar object as the phone sees it, both ways across the bridge.
 *
 * <p>Mirrors rs/calendar/phone.rs {@code PhoneEvent} 173
 * (docs/calendar-mapping.md).
 *
 * @param uid the object's {@code UID}
 * @param master the master; null for an object holding no event, or
 *     overrides alone
 * @param overrides the overrides; null in an edit leaves them as they are
 * @param listed whether the master's recurrence is a list of its instances
 *     around now, the series being one the provider cannot show
 */
public record PhoneEvent(
        String uid, PhoneComponent master, List<PhoneComponent> overrides, boolean listed) {
    /** The view of no object: what an event created on the phone merges onto. */
    public static final PhoneEvent EMPTY = new PhoneEvent("", null, null, false);

    /** Every time the view carries, the master's first. */
    public List<PhoneTime> times() {
        List<PhoneTime> times = new ArrayList<>();
        if (master != null) {
            times.addAll(master.times());
        }
        if (overrides != null) {
            for (PhoneComponent override : overrides) {
                times.addAll(override.times());
            }
        }
        return times;
    }

    static PhoneEvent from(JSONObject event) {
        JSONObject master = event.optJSONObject("master");
        JSONArray listed = event.optJSONArray("overrides");
        List<PhoneComponent> overrides = null;
        if (listed != null) {
            overrides = new ArrayList<>(listed.length());
            for (int index = 0; index < listed.length(); index++) {
                overrides.add(PhoneComponent.from(Json.object(listed, index)));
            }
            overrides = List.copyOf(overrides);
        }
        return new PhoneEvent(
                event.optString("uid"),
                master == null ? null : PhoneComponent.from(master),
                overrides,
                event.optBoolean("listed"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("uid", uid);
        if (master != null) {
            json.put("master", master.toJson());
        }
        if (overrides != null) {
            JSONArray array = new JSONArray();
            for (PhoneComponent override : overrides) {
                array.put(override.toJson());
            }
            json.put("overrides", array);
        }
        json.put("listed", listed);
        return json;
    }
}
