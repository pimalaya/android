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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * served, parsed, pushed or copied ({@link #get}, {@link #verified}), and a
 * name a write finds held before it is kept (§5); one failing it is moved into
 * quarantine/ (§7), never unlinked, and the write files its bytes again.
 */
public final class PimdirBlobs {
    /**
     * The process's renames onto a name and its quarantine moves, one at a
     * time (§7: serialised against the owner's own writers): a move never
     * takes a file another writer of this process is still filing, which the
     * exclusive create and copy holds as a part under the name until its copy
     * is synced, and a name held is verified, moved and filed again as one.
     * The app is the store's one process, its owner.
     */
    private static final Object FILING = new Object();

    /** How many times one write moves a failing file aside and files again. */
    private static final int FILINGS_MAX = 3;

    /**
     * The temporaries this process's writers are still to move, by path: what
     * {@link #sweepTemporaries} leaves, the store's other period-prefixed files
     * being a killed writer's (§5).
     */
    private static final Set<String> WRITING = ConcurrentHashMap.newKeySet();

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
     * <p>Storing one already present is a no-op once the file under the name
     * checks against it, and a name held is never replaced (§5): a file
     * failing it, a part a crashed copy left or a link, is moved into
     * quarantine/ and the body filed again.
     */
    public void put(String hash, byte[] body) throws IOException {
        File target = pathOf(hash);
        if (intact(hash)) {
            return;
        }
        File temp = temporary(target);
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(body);
            out.flush();
            out.getFD().sync();
        }
        commit(hash, temp, target);
    }

    /**
     * Files a file's bytes under their hash; the source stays where it is.
     *
     * <p>On {@link #put(String, byte[])}'s terms.
     */
    void put(String hash, File source) throws IOException {
        File target = pathOf(hash);
        if (intact(hash)) {
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
        commit(hash, temp, target);
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
            if (intact(hash)) {
                return;
            }
            try (java.io.RandomAccessFile synced = new java.io.RandomAccessFile(source, "rw")) {
                synced.getFD().sync();
            }
            shard(target);
            try {
                // NOTE: a name another writer filed meanwhile holds these
                // very bytes once it checks, and is kept; the source goes
                // either way.
                file(hash, source, target);
            } catch (IOException unrenamable) {
                put(hash, source);
            }
        } finally {
            source.delete();
        }
    }

    /**
     * Whether the body is stored and its file checks against its name; a
     * file under the name failing it (a link, a part a crashed copy left,
     * other bytes) is moved into quarantine/ first, so a write skipping a
     * name it finds held keeps it only once it checks (§5).
     */
    private boolean intact(String hash) throws IOException {
        if (has(hash) && hash.equals(PimdirHash.of(pathOf(hash)))) {
            return true;
        }
        quarantine(hash);
        return false;
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
     * Moves a file failing its name into quarantine/, under its name, a period
     * and a suffix unique to the move (§7), once it fails again under {@link
     * #FILING}: a link or anything but a regular file holds no body. Nothing
     * reads it as a body again, and only the user empties it.
     */
    void quarantine(String hash) throws IOException {
        File held = pathOf(hash);
        synchronized (FILING) {
            if (!Files.exists(held.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            // NOTE: verified again under the lock: a writer of this process
            // may have set a failing file aside and filed the body since.
            if (has(hash) && hash.equals(PimdirHash.of(held))) {
                return;
            }
            moveAside(hash);
        }
    }

    /** Moves whatever holds {@code hash}'s name into quarantine/ (§7). */
    private void moveAside(String hash) throws IOException {
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

    /**
     * Flushes the shard directories of the bodies a collector unlinked, each
     * once, so it answers only once its unlinks are durable (§5, §11.5).
     */
    void flushShards(Collection<String> hashes) {
        Set<File> shards = new LinkedHashSet<>();
        for (String hash : hashes) {
            shards.add(pathOf(hash).getParentFile());
        }
        for (File shard : shards) {
            sync(shard);
        }
    }

    /**
     * Unlinks the temporaries a killed writer left (§5): every period-prefixed
     * regular file of objects/ and of its shard directories, no link followed,
     * but those a writer of this process is still to move. Each directory it
     * unlinked in is flushed. Answers how many went.
     *
     * <p>The app is the store's one process, so a temporary no writer of it
     * noted is nobody's to move: it may hold a whole body under a second name
     * no row names, which an erase would otherwise leave (§11.5).
     */
    public int sweepTemporaries() {
        int swept = sweep(root);
        File[] shards = root.listFiles();
        for (File shard : shards == null ? new File[0] : shards) {
            if (shard.getName().length() == 2
                    && Files.isDirectory(shard.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                swept += sweep(shard);
            }
        }
        return swept;
    }

    /** {@link #sweepTemporaries} over one directory. */
    private static int sweep(File dir) {
        String[] names = dir.list((parent, name) -> name.startsWith("."));
        int swept = 0;
        for (String name : names == null ? new String[0] : names) {
            File file = new File(dir, name);
            if (WRITING.contains(file.getPath())
                    || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (file.delete()) {
                swept++;
            }
        }
        if (swept > 0) {
            Log.i("pimalaya", "unlinked " + swept + " temporaries a writer left in " + dir);
            sync(dir);
        }
        return swept;
    }

    /**
     * A temporary beside {@code target}, its name unique to this writer,
     * noted as being written until {@link #commit} moved it.
     */
    private static File temporary(File target) throws IOException {
        File shard = shard(target);
        // NOTE: period-prefixed so a partial write is never mistaken for a
        // blob, unique so two writers of one body never share it (§5).
        File temp = new File(shard, "." + target.getName() + "." + UUID.randomUUID() + ".tmp");
        WRITING.add(temp.getPath());
        return temp;
    }

    /**
     * The shard of {@code target}, made if missing: a shard this call
     * created has objects/ flushed, so the new directory outlasts a power
     * loss as the body in it does (§5).
     */
    private static File shard(File target) throws IOException {
        File shard = target.getParentFile();
        if (shard != null && !shard.isDirectory()) {
            if (!shard.mkdirs() && !shard.isDirectory()) {
                throw new IOException("Could not create the blob shard " + shard);
            }
            sync(shard.getParentFile());
        }
        return shard;
    }

    /**
     * Renames a written temporary to its name ({@link #file}), the temporary
     * deleted after.
     */
    private void commit(String hash, File temp, File target) throws IOException {
        try {
            file(hash, temp, target);
        } finally {
            temp.delete();
            WRITING.remove(temp.getPath());
        }
    }

    /**
     * Renames {@code source} to its name (§5), never replacing it: a name held
     * by a file checking against it is kept, {@code source} left; one held by
     * a file failing it is moved into quarantine/ and the rename made again,
     * all under {@link #FILING}, the owner's moves serialised against its own
     * writers (§7).
     *
     * @throws IOException when the rename fails, or the name stays held by a
     *     failing file after {@link #FILINGS_MAX} moves
     */
    private void file(String hash, File source, File target) throws IOException {
        synchronized (FILING) {
            for (int filing = 1; ; filing++) {
                switch (PimdirSql.renameNoReplace(source, target)) {
                    case MOVED:
                        sync(target.getParentFile());
                        return;
                    case HELD:
                        // NOTE: the entry may be another writer's, moved and
                        // not flushed yet: flushed all the same, before
                        // the row naming the body commits (§5).
                        sync(target.getParentFile());
                        return;
                    case FAILING:
                        if (filing == FILINGS_MAX) {
                            throw new IOException(
                                    "The name " + hash + " stays held by a file failing it");
                        }
                        // NOTE: verified by the rename under this lock, so
                        // moved without reading it again.
                        moveAside(hash);
                        break;
                }
            }
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
