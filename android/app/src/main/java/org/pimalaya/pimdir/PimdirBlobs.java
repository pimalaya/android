package org.pimalaya.pimdir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * The content-addressed blob directory: item bodies, one file per hash.
 *
 * <p>Beside the database (pimdir SPEC.md §5), immutable, named by a hash the
 * caller decided (engine or {@link PimdirHash}): never hashed here. Writes are
 * temp file, flush, rename: a crash leaves at worst an orphan blob.
 */
public final class PimdirBlobs {
    private final File root;

    /** The blob directory under {@code root}. */
    public PimdirBlobs(File root) {
        this.root = root;
    }

    /** The blob path of a hash, sharded two levels: {@code objects/ab/cd/…}. */
    File pathOf(String hash) {
        if (hash == null || hash.length() < 4) {
            throw new IllegalArgumentException("Not a content hash: " + hash);
        }
        File shard = new File(new File(root, hash.substring(0, 2)), hash.substring(2, 4));
        return new File(shard, hash);
    }

    /** Whether the body is already stored. */
    public boolean has(String hash) {
        return pathOf(hash).isFile();
    }

    /**
     * Files a body under the hash the engine computed for it.
     *
     * <p>Storing one already present is a no-op: the name is the content, so
     * the bytes cannot differ.
     */
    public void put(String hash, byte[] body) throws IOException {
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

    /**
     * Files a file's bytes under their hash; the source stays where it is.
     *
     * <p>On {@link #put(String, byte[])}'s terms.
     */
    void put(String hash, File source) throws IOException {
        File target = pathOf(hash);
        if (target.isFile()) {
            return;
        }
        File shard = target.getParentFile();
        if (shard != null && !shard.isDirectory() && !shard.mkdirs()) {
            throw new IOException("Could not create the blob shard " + shard);
        }
        File temp = new File(shard, "." + hash + ".tmp");
        try (java.io.InputStream in = new java.io.FileInputStream(source);
                FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Could not commit the blob " + hash);
        }
    }

    /**
     * Files a file under its hash by moving it; the source is gone after.
     *
     * <p>On {@link #put(String, byte[])}'s terms: synced then renamed into its
     * shard, copied where the rename cannot (another file system).
     */
    public void adopt(String hash, File source) throws IOException {
        try {
            File target = pathOf(hash);
            if (target.isFile()) {
                return;
            }
            File shard = target.getParentFile();
            if (shard != null && !shard.isDirectory() && !shard.mkdirs()) {
                throw new IOException("Could not create the blob shard " + shard);
            }
            try (RandomAccessFile synced = new RandomAccessFile(source, "rw")) {
                synced.getFD().sync();
            }
            if (!source.renameTo(target)) {
                put(hash, source);
            }
        } finally {
            source.delete();
        }
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
