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
 * Documents which org.json the JVM tests see, since it is not the one the
 * device runs: a plain JUnit test loads the {@code org.json:json} artifact
 * (build.gradle.kts), whose JSONObject is backed by a HashMap and so loses
 * insertion order, while a Robolectric test loads Android's own (from the
 * android-all jar), backed by a LinkedHashMap as on a device. An assertion
 * on key order is therefore only sound on a raw string, or under
 * Robolectric, never on a re-printed JSONObject in a plain JUnit test.
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

    /** A Robolectric test: Android's org.json, LinkedHashMap-backed as on a device. */
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
