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
 * Checks records against the replies they mirror, inputs against literals.
 *
 * <p>On the raw text, so neither depends on which org.json re-prints them.
 */
final class ReplyShape {
    private ReplyShape() {}

    /**
     * The record's components equal the raw reply's top-level keys, in order.
     *
     * <p>A field added or renamed on the Rust side fails here.
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

    /** The raw text of each element of a raw JSON array, in order. */
    static List<String> elements(String raw) {
        List<String> elements = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean string = false;
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (string) {
                if (current == '\\') {
                    index++;
                } else if (current == '"') {
                    string = false;
                }
                continue;
            }
            if (current == '"') {
                string = true;
                if (depth == 1 && start < 0) {
                    start = index;
                }
            } else if (current == '{' || current == '[') {
                if (depth == 1 && start < 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}' || current == ']') {
                depth--;
                if (depth == 0 && start >= 0) {
                    elements.add(raw.substring(start, index).trim());
                }
            } else if (current == ',' && depth == 1) {
                elements.add(raw.substring(start, index).trim());
                start = -1;
            } else if (depth == 1 && start < 0 && !Character.isWhitespace(current)) {
                start = index;
            }
        }
        return elements;
    }

    /** The raw text of a raw JSON object's top-level {@code key}'s value. */
    static String field(String raw, String key) {
        List<String> keys = keys(raw);
        String object = raw.trim();
        List<String> values = elements("[" + object.substring(1, object.length() - 1) + "]");
        for (int index = 0; index < keys.size(); index++) {
            if (keys.get(index).equals(key)) {
                String pair = values.get(index);
                return pair.substring(pair.indexOf(':', pair.indexOf('"', 1) + 1) + 1).trim();
            }
        }
        throw new AssertionError("no " + key + " in " + raw);
    }

    /**
     * Two JSON texts are the same value.
     *
     * <p>Same keys (absent distinct from null), same values, key order ignored,
     * array order kept.
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

    /**
     * A value as plain collections, objects sorted by key, null as a marker.
     */
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
