package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Field readers over a parsed bridge reply, a missing field thrown as unreadable. */
final class Json {
    private Json() {}

    static JSONObject object(JSONArray array, int index) {
        try {
            return array.getJSONObject(index);
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    static String string(JSONObject object, String key) {
        try {
            return object.getString(key);
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    static String optString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key);
    }

    /** The strings of a JSON array, empty when there is none. */
    static List<String> strings(JSONArray values) {
        List<String> strings = new ArrayList<>(values == null ? 0 : values.length());
        for (int index = 0; values != null && index < values.length(); index++) {
            strings.add(values.optString(index));
        }
        return strings;
    }
}
