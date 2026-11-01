package org.pimalaya.pimdir;

import android.util.Log;

import org.pimalaya.client.PimdirSql;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The content-addressed blob directory: item bodies, one file per hash.
 *
 * <p>Beside the database (pimdir STORAGE §5), one shard level deep, {@code
 * objects/<hash[0:2]>/<hash>}, named by a hash the caller decided (engine or
 * {@link PimdirHash}) and checked to be a name before any path is built. A
 * write never replaces a file: the temporary is renamed to its name with
 * {@code RENAME_NOREPLACE}, a name held keeping what holds it, never by a hard
 * link, which Android's SELinux policy denies an app on its own files. No link
 * under objects/ is followed. A body is checked against its name before it is
 * served, parsed, pushed or copied ({@link #get}, {@link #verified}); one
 * failing it is moved into quarantine/ (§7), never unlinked.
 */
public final class PimdirBlobs {
    private final File root;

    /** The blob directory under {@code root}, its quarantine beside it. */
    public PimdirBlobs(File root) {
        this.root = root;
    }

    /**
     * The blob path of a hash, one shard level: {@code objects/ab/ab…}.
     *
     * @throws IllegalArgumentException for anything but a name (§5): no path
     *     is built from one
     */
    File pathOf(String hash) {
        if (!PimdirHash.isName(hash)) {
            throw new IllegalArgumentException("Not a content hash: " + hash);
        }
        return new File(new File(root, hash.substring(0, 2)), hash);
    }

    /** The quarantine directory beside objects/ (STORAGE §3). */
    File quarantine() {
        return new File(root.getParentFile(), "quarantine");
    }

    /**
     * Whether the body is stored: a regular file under its name, in a shard
     * that is a directory, neither of them a link.
     */
    public boolean has(String hash) {
        if (!PimdirHash.isName(hash)) {
            return false;
        }
        File path = pathOf(hash);
        return Files.isDirectory(path.getParentFile().toPath(), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(path.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Files a body under the hash the engine computed for it.
     *
     * <p>Storing one already present is a no-op: the name is the content, so
     * the bytes cannot differ, and a name held is never replaced (§5).
     */
    public void put(String hash, byte[] body) throws IOException {
        File target = pathOf(hash);
        if (has(hash)) {
            return;
        }
        File temp = temporary(target);
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(body);
            out.flush();
            out.getFD().sync();
        }
        commit(temp, target);
    }

    /**
     * Files a file's bytes under their hash; the source stays where it is.
     *
     * <p>On {@link #put(String, byte[])}'s terms.
     */
    void put(String hash, File source) throws IOException {
        File target = pathOf(hash);
        if (has(hash)) {
            return;
        }
        File temp = temporary(target);
        try (InputStream in = new java.io.FileInputStream(source);
                FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        }
        commit(temp, target);
    }

    /**
     * Files a file under its hash by renaming it; the source is gone after.
     *
     * <p>On {@link #put(String, byte[])}'s terms: synced, then renamed into its
     * shard, copied where the rename cannot be made (another file system).
     */
    public void adopt(String hash, File source) throws IOException {
        try {
            File target = pathOf(hash);
            if (has(hash)) {
                return;
            }
            try (java.io.RandomAccessFile synced = new java.io.RandomAccessFile(source, "rw")) {
                synced.getFD().sync();
            }
            shard(target);
            try {
                // NOTE: a name another writer filed meanwhile holds these
                // very bytes, and is kept; the source goes either way.
                if (PimdirSql.renameNoReplace(source, target)) {
                    sync(target.getParentFile());
                }
            } catch (IOException unrenamable) {
                put(hash, source);
            }
        } finally {
            source.delete();
        }
    }

    /**
     * Reads a body back, checked against its name; null when it is not
     * stored or fails its name, which moves it into quarantine/ (§7).
     */
    byte[] get(String hash) throws IOException {
        if (!has(hash)) {
            return null;
        }
        File source = pathOf(hash);
        byte[] body = Files.readAllBytes(source.toPath());
        if (!hash.equals(PimdirHash.of(body))) {
            quarantine(hash);
            return null;
        }
        return body;
    }

    /** Reads a body back as text, or null when {@link #get} answers none. */
    String getText(String hash) throws IOException {
        byte[] body = get(hash);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Whether a body reads whole and hashes to its name (§7), without
     * loading it; one failing is moved into quarantine/, a missing one is
     * not verified.
     */
    public boolean verified(String hash) {
        if (!has(hash)) {
            return false;
        }
        try {
            if (hash.equals(PimdirHash.of(pathOf(hash)))) {
                return true;
            }
            quarantine(hash);
        } catch (IOException unreadable) {
            Log.w("pimalaya", "unreadable body " + hash, unreadable);
        }
        return false;
    }

    /**
     * Moves a body failing its name into quarantine/, under its name, a period
     * and a suffix unique to the move (§7). Nothing reads it as a body again,
     * and only the user empties it.
     */
    void quarantine(String hash) throws IOException {
        Path source = pathOf(hash).toPath();
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        File dir = quarantine();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create the quarantine " + dir);
        }
        Path target = new File(dir, hash + "." + UUID.randomUUID()).toPath();
        Files.move(source, target);
        Log.w("pimalaya", "body " + hash + " fails its name, moved to " + target);
    }

    /** The files quarantine/ holds, by name. */
    List<String> quarantined() {
        List<String> names = new ArrayList<>();
        String[] held = quarantine().list();
        if (held != null) {
            for (String name : held) {
                names.add(name);
            }
        }
        return names;
    }

    /** Unlinks a body the store no longer references; never a link. */
    boolean remove(String hash) {
        if (!has(hash)) {
            return false;
        }
        return pathOf(hash).delete();
    }

    /** A temporary beside {@code target}, its name unique to this writer. */
    private static File temporary(File target) throws IOException {
        File shard = shard(target);
        // NOTE: period-prefixed so a partial write is never mistaken for a
        // blob, unique so two writers of one body never share it (§5).
        return new File(shard, "." + target.getName() + "." + UUID.randomUUID() + ".tmp");
    }

    /** The shard of {@code target}, made if missing. */
    private static File shard(File target) throws IOException {
        File shard = target.getParentFile();
        if (shard != null && !shard.isDirectory() && !shard.mkdirs()) {
            throw new IOException("Could not create the blob shard " + shard);
        }
        return shard;
    }

    /**
     * Renames a written temporary to its name (§5), never replacing it: a name
     * held keeps what holds it, the temporary deleted.
     */
    private static void commit(File temp, File target) throws IOException {
        try {
            // NOTE: a name held was filed by another writer: the same bytes.
            if (PimdirSql.renameNoReplace(temp, target)) {
                sync(target.getParentFile());
            }
        } finally {
            temp.delete();
        }
    }

    /** Flushes a directory's entries, where the platform lets it. */
    private static void sync(File dir) {
        try (FileChannel channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException unsupported) {
            // NOTE: not every file system opens a directory for a flush.
        }
    }
}
