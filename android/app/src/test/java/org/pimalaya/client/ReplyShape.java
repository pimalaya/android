package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Checks a record against the bridge reply it mirrors, and Java-built
 * inputs against literal JSON, on the raw text so neither depends on
 * which org.json re-prints them.
 */
final class ReplyShape {
    private ReplyShape() {}

    /**
     * The record's component names equal the raw reply's top-level keys,
     * in the order they appear in the text: a field added on the Rust
     * side, or one renamed, fails here.
     */
    static void assertMirrors(Class<? extends Record> record, String raw) {
        List<String> components = new ArrayList<>();
        for (var component : record.getRecordComponents()) {
            components.add(component.getName());
        }
        assertEquals(record.getSimpleName() + " against " + raw, components, keys(raw));
    }

    /** The top-level keys of a raw JSON object, in text order. */
    static List<String> keys(String raw) {
        List<String> keys = new ArrayList<>();
        int depth = 0;
        StringBuilder string = null;
        String last = null;
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (string != null) {
                if (current == '\\') {
                    string.append(raw.charAt(++index));
                } else if (current == '"') {
                    last = string.toString();
                    string = null;
                } else {
                    string.append(current);
                }
                continue;
            }
            if (current == '"') {
                string = new StringBuilder();
            } else if (current == '{' || current == '[') {
                depth++;
            } else if (current == '}' || current == ']') {
                depth--;
            } else if (current == ':' && depth == 1) {
                keys.add(last);
            }
        }
        return keys;
    }

    /**
     * Two JSON texts are the same value: same keys (absent distinct from
     * null), same values, key order ignored, array order kept.
     */
    static void assertSameJson(String expected, String actual) throws Exception {
        assertTrue(
                "expected " + expected + " but was " + actual,
                canonical(parse(expected)).equals(canonical(parse(actual))));
    }

    private static Object parse(String text) throws Exception {
        String trimmed = text.trim();
        return trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
    }

    /** A value as plain Java collections, objects sorted by key, null as a marker. */
    private static Object canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Map<String, Object> sorted = new TreeMap<>();
            for (Iterator<String> names = object.keys(); names.hasNext(); ) {
                String name = names.next();
                sorted.put(name, canonical(object.get(name)));
            }
            return sorted;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                list.add(canonical(array.get(index)));
            }
            return list;
        }
        if (value == null || JSONObject.NULL.equals(value)) {
            return "<null>";
        }
        if (value instanceof Number) {
            return ((Number) value).longValue() == ((Number) value).doubleValue()
                    ? (Object) ((Number) value).longValue()
                    : (Object) ((Number) value).doubleValue();
        }
        return value;
    }
}
