package org.pimalaya;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which package of the app may reference which (java-004).
 *
 * <p>Reads the main sources: every {@code org.pimalaya.<package>} reference
 * outside a file's own package must be an allowed edge, so dependencies
 * point from screens to the store and never back.
 */
public class LayeringTest {

    private static final String ROOT = "";
    private static final String CLIENT = "client";

    /** The packages each package may reference; the root may reference all. */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry(CLIENT, Set.of()),
            Map.entry("account", Set.of(CLIENT)),
            Map.entry("phone", Set.of("account", CLIENT)),
            Map.entry("pimdir", Set.of("account", CLIENT)),
            Map.entry("ui", Set.of()),
            Map.entry("file", Set.of("ui", "pimdir", "account", CLIENT)),
            Map.entry("contact", Set.of("ui", "phone", "pimdir", "account", CLIENT)),
            Map.entry("calendar", Set.of("ui", "phone", "pimdir", "account", CLIENT)),
            Map.entry("mail", Set.of("file", "ui", "pimdir", "account", CLIENT)),
            // NOTE: sync reaches ui because the foreground sync draws the
            // sync dock and posts through the host; ui depends on nothing.
            Map.entry("sync", Set.of("mail", "calendar", "contact", "phone", "pimdir",
                    "ui", "account", CLIENT)),
            Map.entry("setup", Set.of("sync", "mail", "calendar", "contact", "phone",
                    "pimdir", "ui", "account", CLIENT)),
            Map.entry("filter", Set.of("mail", "pimdir", "account")),
            Map.entry("trash", Set.of("contact", "pimdir", "account", CLIENT)),
            Map.entry("link", Set.of("mail", "contact", "ui", "pimdir")));

    /**
     * Screens that may also reference the root, checked before
     * {@link #ALLOWED}: a screen reaches its host activity until phase 4 of
     * java-domain-layout gives screens their collaborators, then this goes.
     */
    private static final Map<String, Set<String>> TOLERATED = Map.ofEntries(
            Map.entry("contact/AdvancedEditor.java", Set.of(ROOT)),
            Map.entry("contact/Birthdays.java", Set.of(ROOT)),
            Map.entry("contact/ContactsList.java", Set.of(ROOT)),
            Map.entry("contact/ContactWriter.java", Set.of(ROOT)),
            Map.entry("contact/DuplicateReview.java", Set.of(ROOT)),
            Map.entry("contact/VcfTransfer.java", Set.of(ROOT)),
            Map.entry("calendar/CalendarList.java", Set.of(ROOT)),
            Map.entry("calendar/EventView.java", Set.of(ROOT)),
            Map.entry("mail/MailList.java", Set.of(ROOT)),
            Map.entry("mail/MessageCompose.java", Set.of(ROOT)),
            Map.entry("mail/MessageView.java", Set.of(ROOT)),
            Map.entry("mail/WindowPicker.java", Set.of(ROOT)),
            Map.entry("file/FileActions.java", Set.of(ROOT)),
            Map.entry("file/FilesList.java", Set.of(ROOT)),
            Map.entry("setup/AccountSettings.java", Set.of(ROOT)),
            Map.entry("setup/OauthFlow.java", Set.of(ROOT)),
            Map.entry("setup/OnboardingFlow.java", Set.of(ROOT)),
            Map.entry("filter/FilterPage.java", Set.of(ROOT)),
            Map.entry("trash/DeletedItemsPage.java", Set.of(ROOT)),
            Map.entry("link/LinkedSection.java", Set.of(ROOT)));

    private static final Pattern REFERENCE =
            Pattern.compile("\\borg\\.pimalaya\\.(?:([a-z]+)\\.|([A-Z]\\w*))");

    /** Classes AGP generates in the root package, usable from every package. */
    private static final Set<String> GENERATED = Set.of("R", "BuildConfig");

    @Test
    public void dependenciesPointOneWay() throws IOException {
        List<String> violations = new ArrayList<>();
        Path main = Paths.get("src/main/java/org/pimalaya");
        try (Stream<Path> files = Files.walk(main)) {
            Iterable<Path> sources = files.filter(p -> p.toString().endsWith(".java"))::iterator;
            for (Path file : sources) {
                String from = packageOf(main.relativize(file.getParent()));
                Set<String> tolerated =
                        TOLERATED.getOrDefault(main.relativize(file).toString(), Set.of());
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Set<String> seen = new TreeSet<>();
                Matcher m = REFERENCE.matcher(source);
                while (m.find()) {
                    if (m.group(2) != null && GENERATED.contains(m.group(2))) {
                        continue;
                    }
                    String to = m.group(1) == null ? ROOT : m.group(1);
                    if (!to.equals(from) && !tolerated.contains(to) && !allowed(from, to)
                            && seen.add(to)) {
                        violations.add(main.relativize(file) + " -> " + label(to));
                    }
                }
            }
        }
        assertTrue("disallowed references:\n" + String.join("\n", violations),
                violations.isEmpty());
    }

    private static String packageOf(Path relative) {
        String p = relative.toString();
        return p.isEmpty() ? ROOT : p.replace('/', '.');
    }

    private static boolean allowed(String from, String to) {
        if (from.equals(ROOT)) {
            return true;
        }
        Set<String> edges = ALLOWED.get(from);
        return edges != null && edges.contains(to);
    }

    private static String label(String pkg) {
        return pkg.isEmpty() ? "org.pimalaya" : "org.pimalaya." + pkg;
    }
}
