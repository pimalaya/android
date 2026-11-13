package org.pimalaya.pimdir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The content address naming a body in the object store, against pimdir's
 * objects.json (STORAGE §5): every profile passes it, since two writers
 * naming one body apart lose it silently.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirHashTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * The temporaries a killed writer left are unlinked, in objects/ and in
     * its shard directories, a body and a period-prefixed directory left
     * (pimdir STORAGE §5).
     */
    @Test
    public void aKilledWritersTemporariesAreUnlinked() throws Exception {
        File objects = new File(folder.getRoot(), "objects");
        PimdirBlobs blobs = new PimdirBlobs(objects);
        byte[] body = "kept".getBytes("UTF-8");
        String hash = PimdirHash.of(body);
        blobs.put(hash, body);
        File shard = blobs.pathOf(hash).getParentFile();
        File left = new File(shard, "." + hash + ".left.tmp");
        File above = new File(objects, ".stray.tmp");
        File directory = new File(shard, ".kept");
        Files.write(left.toPath(), body);
        Files.write(above.toPath(), body);
        assertTrue(directory.mkdir());

        assertEquals(2, blobs.sweepTemporaries());

        assertTrue(!left.exists() && !above.exists());
        assertTrue("a body stays", blobs.has(hash));
        assertTrue("no regular file, left in place", directory.isDirectory());
        assertEquals(0, blobs.sweepTemporaries());
    }

    @Test
    public void everyObjectVectorIsNamedAndPlacedAsPimdirSays() throws Exception {
        JSONArray objects = new JSONObject(PimdirVectors.read("vectors/objects.json"))
                .getJSONArray("objects");
        PimdirBlobs blobs = new PimdirBlobs(new File(folder.getRoot(), "objects"));
        assertTrue(objects.length() >= 10);

        for (int index = 0; index < objects.length(); index++) {
            JSONObject object = objects.getJSONObject(index);
            byte[] body = body(object);
            JSONObject named = object.getJSONObject("blake3");
            String label = object.getString("label");

            assertEquals(label, named.getString("name"), PimdirHash.of(body));
            assertTrue(label, PimdirHash.isName(named.getString("name")));
            assertEquals(
                    label,
                    new File(folder.getRoot(), named.getString("path")),
                    blobs.pathOf(named.getString("name")));

            File file = folder.newFile();
            Files.write(file.toPath(), body);
            assertEquals(label, named.getString("name"), PimdirHash.of(file));
        }
    }

    @Test
    public void onlyANameMakesAPath() {
        PimdirBlobs blobs = new PimdirBlobs(folder.getRoot());
        assertFalse(PimdirHash.isName("../../etc/passwd"));
        assertFalse("a sha256-128 name", PimdirHash.isName("4oymiquy7qobjgx36tejs35zeq"));
        assertFalse(blobs.has("../x"));
        try {
            blobs.pathOf("ab/../../x");
            throw new AssertionError("a path from no name");
        } catch (IllegalArgumentException refused) {
            // NOTE: refused before any path is built.
        }
    }

    /** A case's body: its hex, else {@code body_len} bytes of {@code i mod 251}. */
    private static byte[] body(JSONObject object) throws Exception {
        String hex = object.optString("body_hex", null);
        if (hex != null && object.has("body_hex")) {
            byte[] body = new byte[hex.length() / 2];
            for (int index = 0; index < body.length; index++) {
                body[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
            }
            return body;
        }
        byte[] body = new byte[object.getInt("body_len")];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) (index % 251);
        }
        return body;
    }
}
