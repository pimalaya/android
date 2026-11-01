package org.pimalaya.pimdir;

import org.pimalaya.client.PimdirSql;

import java.nio.charset.StandardCharsets;

/**
 * The content hash naming a body in the store: pimdir's BLAKE3 (STORAGE
 * §5).
 *
 * <p>The 32-byte digest as 52 characters of lowercase base32 without
 * padding, computed by io-pimdir over JNI ({@link PimdirSql#hash}), so the
 * app names a body as every other pimdir writer does.
 */
public final class PimdirHash {
    /** The value {@code store_meta.hash_algo} carries for this app's stores. */
    static final String ALGORITHM = "blake3";

    /** How many characters a name holds. */
    static final int LENGTH = 52;

    /** The content hash of a text body, as the object store names it. */
    public static String of(String body) {
        return of(body.getBytes(StandardCharsets.UTF_8));
    }

    /** The content hash of a body's bytes. */
    public static String of(byte[] body) {
        return PimdirSql.hash(body);
    }

    /** The content hash of a file's bytes, read a buffer at a time. */
    public static String of(java.io.File file) throws java.io.IOException {
        return PimdirSql.hashFile(file);
    }

    /**
     * Whether {@code name} is a name a body may be filed under: 52 characters
     * of {@code a-z2-7}, checked before any path is built from it (STORAGE §5).
     */
    public static boolean isName(String name) {
        if (name == null || name.length() != LENGTH) {
            return false;
        }
        for (int index = 0; index < LENGTH; index++) {
            char character = name.charAt(index);
            boolean letter = character >= 'a' && character <= 'z';
            boolean digit = character >= '2' && character <= '7';
            if (!letter && !digit) {
                return false;
            }
        }
        return true;
    }

    private PimdirHash() {}
}
