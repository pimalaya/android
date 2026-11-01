package org.pimalaya.pimdir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pimdir spec io-pimdir vendors (spec/), read from the git checkout cargo
 * made of the revision rust/Cargo.toml pins, so its vectors run against the
 * Java owner as they run against io-pimdir's own store.
 */
final class PimdirVectors {
    private static final Pattern REV =
            Pattern.compile("io-pimdir = \\{ git = \"[^\"]+\", rev = \"([0-9a-f]{7})");

    /** A file of spec/, e.g. {@code vectors/objects.json}. */
    static String read(String file) throws IOException {
        return new String(Files.readAllBytes(path(file).toPath()), StandardCharsets.UTF_8);
    }

    private static File path(String file) throws IOException {
        File root = new File(System.getProperty("user.dir"));
        File manifest = null;
        for (File dir = root; dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, "rust/Cargo.toml");
            if (candidate.isFile()) {
                manifest = candidate;
                break;
            }
        }
        if (manifest == null) {
            throw new IOException("No rust/Cargo.toml above " + root);
        }
        Matcher rev =
                REV.matcher(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
        if (!rev.find()) {
            throw new IOException("io-pimdir is not pinned by git rev in " + manifest);
        }
        String home = System.getenv("CARGO_HOME");
        File checkouts =
                new File(
                        home != null ? new File(home) : new File(System.getProperty("user.home"), ".cargo"),
                        "git/checkouts");
        File[] clones = checkouts.listFiles((dir, name) -> name.startsWith("io-pimdir-"));
        if (clones != null) {
            for (File clone : clones) {
                File found = new File(clone, rev.group(1) + "/spec/" + file);
                if (found.isFile()) {
                    return found;
                }
            }
        }
        throw new IOException("io-pimdir " + rev.group(1) + " spec/" + file + " under " + checkouts);
    }

    private PimdirVectors() {}
}
