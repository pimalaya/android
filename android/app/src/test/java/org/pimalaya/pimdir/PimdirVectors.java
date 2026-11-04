package org.pimalaya.pimdir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pimdir spec io-pimdir vendors (spec/), so its vectors run against the
 * Java owner as they run against io-pimdir's own store.
 *
 * <p>Read from {@code PIMALAYA_IO_PIMDIR_SPEC}, else from the source cargo
 * fetched of the io-pimdir rust/Cargo.lock locks: a git checkout, or a
 * registry package, which leaves the vectors out unless io-pimdir ships them.
 */
final class PimdirVectors {
    private static final Pattern LOCKED =
            Pattern.compile(
                    "name = \"io-pimdir\"\\nversion = \"([^\"]+)\"\\nsource = \"([^\"]+)\"");

    /** A file of spec/, e.g. {@code vectors/objects.json}. */
    static String read(String file) throws IOException {
        return new String(Files.readAllBytes(path(file).toPath()), StandardCharsets.UTF_8);
    }

    private static File path(String file) throws IOException {
        String spec = System.getenv("PIMALAYA_IO_PIMDIR_SPEC");
        if (spec != null) {
            return new File(spec, file);
        }

        File root = new File(System.getProperty("user.dir"));
        File lock = null;
        for (File dir = root; dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, "rust/Cargo.lock");
            if (candidate.isFile()) {
                lock = candidate;
                break;
            }
        }
        if (lock == null) {
            throw new IOException("No rust/Cargo.lock above " + root);
        }
        Matcher locked =
                LOCKED.matcher(new String(Files.readAllBytes(lock.toPath()), StandardCharsets.UTF_8));
        if (!locked.find()) {
            throw new IOException("io-pimdir is not locked in " + lock);
        }
        String version = locked.group(1);
        String source = locked.group(2);

        String home = System.getenv("CARGO_HOME");
        File cargo = home != null ? new File(home) : new File(System.getProperty("user.home"), ".cargo");
        int hash = source.indexOf('#');
        File parent = new File(cargo, hash < 0 ? "registry/src" : "git/checkouts");
        String name = hash < 0 ? "io-pimdir-" + version : source.substring(hash + 1, hash + 8);
        File[] dirs = parent.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                File found = new File(dir, name + "/spec/" + file);
                if (found.isFile()) {
                    return found;
                }
            }
        }
        throw new IOException(
                "No io-pimdir "
                        + version
                        + " spec/"
                        + file
                        + " under "
                        + parent
                        + ": the published crate leaves the vectors out, point"
                        + " PIMALAYA_IO_PIMDIR_SPEC at a checkout's spec/");
    }

    private PimdirVectors() {}
}
