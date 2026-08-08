package org.pimalaya;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * The content-addressed blob directory: item bodies, one file per hash.
 *
 * <p>Bodies live beside the database rather than in it (pimdir SPEC.md §5), so
 * a large card or message never travels through a SQLite row, and an identical
 * body reaching two collections or two accounts is stored once. The name is the
 * content hash, so a blob is immutable: editing a card writes a new one and
 * repoints the item at it.
 *
 * <p><strong>Nothing here computes a hash.</strong> A body arrives with its
 * name already decided, by the engine on a fetch or by {@link PimdirHash} on a
 * locally captured document, so this class only has to file the bytes under it.
 * Deriving one here too would be a third implementation to keep byte-identical
 * with the other two, and being wrong would not fail loudly: it would write
 * blobs no reader could find.
 *
 * <p>Writes are atomic in the way the spec requires: temp file, flush, rename.
 * A crash therefore leaves at worst an orphan blob, never a row pointing at a
 * body that is not fully written.
 */
final class PimdirBlobs {
    private final File root;

    PimdirBlobs(File root) {
        this.root = root;
    }

    /**
     * The blob path of a hash, sharded two levels so no directory grows
     * unbounded: {@code objects/ab/cd/abcd...}.
     */
    File pathOf(String hash) {
        if (hash == null || hash.length() < 4) {
            throw new IllegalArgumentException("Not a content hash: " + hash);
        }
        File shard = new File(new File(root, hash.substring(0, 2)), hash.substring(2, 4));
        return new File(shard, hash);
    }

    /** Whether the body is already stored. */
    boolean has(String hash) {
        return pathOf(hash).isFile();
    }

    /**
     * Files a body under the hash the engine computed for it.
     *
     * <p>Storing one already present is a no-op: the name is the content, so
     * the bytes cannot differ.
     */
    void put(String hash, byte[] body) throws IOException {
        File target = pathOf(hash);
        if (target.isFile()) {
            return;
        }

        File shard = target.getParentFile();
        if (shard != null && !shard.isDirectory() && !shard.mkdirs()) {
            throw new IOException("Could not create the blob shard " + shard);
        }

        // NOTE: period-prefixed so a partial write is never mistaken for a
        // blob, and in the same directory so the rename stays atomic.
        File temp = new File(shard, "." + hash + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(body);
            out.flush();
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Could not commit the blob " + hash);
        }
    }

    /** Files text, the shape every current kind stores. */
    void putText(String hash, String body) throws IOException {
        put(hash, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads a body back, or null when it is not stored. */
    byte[] get(String hash) throws IOException {
        File source = pathOf(hash);
        if (!source.isFile()) {
            return null;
        }
        byte[] body = new byte[(int) source.length()];
        try (RandomAccessFile in = new RandomAccessFile(source, "r")) {
            in.readFully(body);
        }
        return body;
    }

    /** Reads a body back as text, or null when it is not stored. */
    String getText(String hash) throws IOException {
        byte[] body = get(hash);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    /** Unlinks a body the store no longer references. */
    boolean remove(String hash) {
        return pathOf(hash).delete();
    }
}
