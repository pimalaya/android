package org.pimalaya.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    static JSONObject object(JSONObject object, String key) {
        try {
            return object.getJSONObject(key);
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

    static int integer(JSONObject object, String key) {
        try {
            return object.getInt(key);
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    static long longInteger(JSONObject object, String key) {
        try {
            return object.getLong(key);
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

    /** The string lists of a JSON object by key, in key order, empty when there is none. */
    static Map<String, List<String>> stringLists(JSONObject object) {
        Map<String, List<String>> lists = new TreeMap<>();
        if (object != null) {
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                lists.put(key, List.copyOf(strings(object.optJSONArray(key))));
            }
        }
        return Collections.unmodifiableMap(lists);
    }

    /** The integers of a JSON array, empty when there is none. */
    static List<Integer> integers(JSONArray values) {
        List<Integer> integers = new ArrayList<>(values == null ? 0 : values.length());
        for (int index = 0; values != null && index < values.length(); index++) {
            integers.add(values.optInt(index));
        }
        return integers;
    }
}
