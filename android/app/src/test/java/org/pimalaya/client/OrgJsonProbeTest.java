package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Documents which org.json the JVM tests see, which is not the device's.
 *
 * <p>Plain JUnit loads {@code org.json:json} (HashMap-backed, loses order);
 * Robolectric loads Android's (LinkedHashMap, as on a device). Assert key order
 * only on a raw string or under Robolectric.
 */
@RunWith(Enclosed.class)
public class OrgJsonProbeTest {
    /** The keys, in the order they are put. */
    private static final String[] KEYS = {"zeta", "alpha", "mid", "beta", "omega", "a1", "x"};

    /** What a JSONObject keeping insertion order prints for {@link #KEYS}. */
    private static final String INSERTION =
            "{\"zeta\":1,\"alpha\":1,\"mid\":1,\"beta\":1,\"omega\":1,\"a1\":1,\"x\":1}";

    private static JSONObject built() throws Exception {
        JSONObject built = new JSONObject();
        for (String key : KEYS) {
            built.put(key, 1);
        }
        return built;
    }

    /** A plain JUnit test: the org.json artifact, HashMap-backed. */
    public static class Jvm {
        @Test
        public void theArtifactLosesInsertionOrder() throws Exception {
            String source =
                    String.valueOf(JSONObject.class.getProtectionDomain().getCodeSource());
            assertTrue(source, source.contains("json-20240303.jar"));

            Field map = JSONObject.class.getDeclaredField("map");
            assertEquals(Map.class, map.getType());
            assertNotEquals(INSERTION, built().toString());
        }
    }

    /** A Robolectric test: Android's org.json, LinkedHashMap-backed. */
    @RunWith(RobolectricTestRunner.class)
    public static class Android {
        @Test
        public void androidsKeepsInsertionOrder() throws Exception {
            String source =
                    String.valueOf(JSONObject.class.getProtectionDomain().getCodeSource());
            assertTrue(source, !source.contains("json-20240303.jar"));

            Field pairs = JSONObject.class.getDeclaredField("nameValuePairs");
            assertEquals(LinkedHashMap.class, pairs.getType());
            assertEquals(INSERTION, built().toString());
        }
    }
}
