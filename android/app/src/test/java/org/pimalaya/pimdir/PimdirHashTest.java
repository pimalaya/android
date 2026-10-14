package org.pimalaya.pimdir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * The content address naming a body in the object store.
 *
 * <p>Pinned, not recomputed: the bridge hashes fetched bodies and this class
 * edited ones, so both must agree byte for byte. rust/src/store.rs asserts the
 * same three vectors.
 */
public class PimdirHashTest {
    @Test
    public void theHashIsSha256128InLowercaseBase32() {
        assertEquals("4oymiquy7qobjgx36tejs35zeq", PimdirHash.of(""));
        assertEquals("xj4bnp4pahh6uqkbidpf3lrcem", PimdirHash.of("abc"));
    }

    @Test
    public void theNameIsAValidBlobPathComponent() {
        // NOTE: pimdir SPEC.md 5: the hash is a path component, sharded two
        // levels, so it has to be single-case and filesystem-safe. 128 bits of
        // base32 is 26 characters with the padding dropped.
        String hash = PimdirHash.of("BEGIN:VCARD\r\nEND:VCARD\r\n");

        assertEquals(26, hash.length());
        assertEquals(hash.toLowerCase(java.util.Locale.ROOT), hash);
        assertEquals("", hash.replaceAll("[a-z2-7]", ""));
    }

    @Test
    public void differentBodiesGetDifferentNames() {
        assertNotEquals(PimdirHash.of("one"), PimdirHash.of("two"));
        assertEquals(PimdirHash.of("same"), PimdirHash.of("same"));
    }
}
