package org.pimalaya.pimdir;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The content hash naming a body in the store: pimdir's {@code sha256-128}.
 *
 * <p>SHA-256 truncated to 128 bits (SPEC.md §4.3; blake3 would need a Java
 * implementation), lowercase base32 without padding since it is a path
 * component (§5). The bridge hashes the same and its test pins the two.
 */
public final class PimdirHash {
    /** The value {@code store_meta.hash_algo} carries for this app's stores. */
    static final String ALGORITHM = "sha256-128";

    /** How many bytes of the digest the name keeps. */
    private static final int BYTES = 16;

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    /** The content hash of a text body, as the object store names it. */
    public static String of(String body) {
        return of(body.getBytes(StandardCharsets.UTF_8));
    }

    /** The content hash of a body's bytes. */
    public static String of(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return base32(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    /** The content hash of a file's bytes, read a buffer at a time. */
    public static String of(java.io.File file) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return base32(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    /**
     * The first {@link #BYTES} bytes of a digest as unpadded lowercase base32.
     *
     * <p>The last character carries the three leftover bits zero-padded, as RFC
     * 4648 prescribes once the padding characters are dropped.
     */
    private static String base32(byte[] digest) {
        StringBuilder name = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;

        for (int index = 0; index < BYTES; index++) {
            buffer = (buffer << 8) | (digest[index] & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                name.append(ALPHABET.charAt((buffer >> bits) & 0x1f));
            }
        }
        if (bits > 0) {
            name.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return name.toString();
    }

    private PimdirHash() {}
}
