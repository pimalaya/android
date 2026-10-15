package org.pimalaya.client;

import static org.junit.Assert.assertEquals;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the account argument of the network verbs: the keys rs/account.rs
 * {@code AccountJson} reads, compared as JSON values.
 */
public class AccountTest {
    /** Every component crosses under the key the bridge reads it by. */
    @Test
    public void everyComponentCrossesUnderItsWireKey() throws JSONException {
        Account account =
                new Account(
                        "imaps://imap.example.org:993",
                        "jane",
                        "pw",
                        "smtps://smtp.example.org",
                        "jane@example.org");

        assertEquals(
                Map.of(
                        "address", "jane@example.org",
                        "baseUrl", "imaps://imap.example.org:993",
                        "login", "jane",
                        "secret", "pw",
                        "submitUrl", "smtps://smtp.example.org"),
                values(account.toJson()));
    }

    /** A null component is an absent key, which the bridge reads empty. */
    @Test
    public void aNullComponentIsAnAbsentKey() throws JSONException {
        Account account = new Account("jmap://api.fastmail.com", "", "pw");

        assertEquals(
                Map.of("baseUrl", "jmap://api.fastmail.com", "login", "", "secret", "pw"),
                values(account.toJson()));
    }

    /** The object's string values by key. */
    private static Map<String, String> values(JSONObject json) throws JSONException {
        Map<String, String> values = new TreeMap<>();
        for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
            String key = keys.next();
            values.put(key, json.getString(key));
        }
        return values;
    }
}
