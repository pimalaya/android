package org.pimalaya;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The content hash naming a body in the pimdir object store.
 *
 * <p>pimdir records the algorithm in {@code store_meta.hash_algo} and admits two
 * (SPEC.md §4.3): {@code blake3}, recommended, or {@code sha256-128}. This app
 * takes the second, because {@link MessageDigest} ships SHA-256 on every Android
 * release while blake3 would mean bundling an implementation in Java, a second
 * one in Rust, and keeping them byte-identical forever.
 *
 * <p>The encoding is not incidental either: SPEC.md §5 requires lowercase base32
 * (RFC 4648, no padding), because the hash is also a path component
 * ({@code objects/ab/cd/abcd...}) and a single-case, filesystem-safe alphabet is
 * what makes that path valid on every target filesystem. Hex would work on Linux
 * and collide on a case-insensitive one.
 *
 * <p>Truncation to 128 bits is what {@code sha256-128} means: the store needs
 * collision resistance for content addressing, not signature strength, and a
 * 26-character name keeps the blob paths short.
 *
 * <p>The Rust bridge computes the same value for the bodies it hashes, and its
 * own test pins the two together. A disagreement would not fail loudly: it would
 * write blobs no reader could find.
 */
final class PimdirHash {
    /** The value {@code store_meta.hash_algo} carries for this app's stores. */
    static final String ALGORITHM = "sha256-128";

    /** How many bytes of the digest the name keeps. */
    private static final int BYTES = 16;

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    /** The content hash of a text body, as the object store names it. */
    static String of(String body) {
        return of(body.getBytes(StandardCharsets.UTF_8));
    }

    /** The content hash of a body's bytes. */
    static String of(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return base32(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    /** The content hash of a file's bytes, read a buffer at a time. */
    static String of(java.io.File file) throws java.io.IOException {
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
     * The first {@link #BYTES} bytes of a digest as lowercase base32, no padding.
     *
     * <p>128 bits is not a multiple of five, so the last character carries the
     * three leftover bits padded with zeroes, which is what RFC 4648 prescribes
     * once the padding characters are dropped.
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
