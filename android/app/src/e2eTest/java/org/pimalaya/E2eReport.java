package org.pimalaya;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which scenario ran on which target, and how it went.
 *
 * <p>Rewritten whole after every test as `matrix.md` under `e2e.report`, so a
 * run cut short leaves what it reached. Details are failure messages, never a
 * credential.
 */
final class E2eReport {
    private static final List<Result> RESULTS = new ArrayList<>();

    private static final String STARTED = LocalDateTime.now(ZoneOffset.UTC).toString();

    private E2eReport() {}

    private static final class Result {
        final String test;
        final String[] scenarios;
        final String target;
        final String status;
        final String detail;

        Result(String test, String[] scenarios, String target, String status, String detail) {
            this.test = test;
            this.scenarios = scenarios;
            this.target = target;
            this.status = status;
            this.detail = detail;
        }

        String cell() {
            String line = detail == null ? "" : detail.split("\n")[0].replace("|", "/");
            if (line.length() > 160) {
                line = line.substring(0, 160) + "...";
            }
            return line.isEmpty() ? status : status + ": " + line;
        }
    }

    static synchronized void record(
            String test, String[] scenarios, String target, String status, String detail) {
        RESULTS.add(new Result(test, scenarios, target, status, detail));
        String dir = System.getProperty("e2e.report");
        if (dir == null) {
            return;
        }
        try {
            File file = new File(dir, "matrix.md");
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), render().getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            System.err.println("e2e report not written: " + error.getMessage());
        }
    }

    private static String render() {
        StringBuilder out = new StringBuilder();
        out.append("# End-to-end run, started ").append(STARTED).append(" UTC\n\n");
        out.append("Targets asked: ").append(String.join(", ", E2eTarget.asked())).append("\n\n");

        out.append("## Scenarios\n\n");
        header(out, "Scenario");
        Map<String, Map<String, Result>> byScenario = new TreeMap<>();
        for (Result result : RESULTS) {
            for (String scenario : result.scenarios) {
                Map<String, Result> cells =
                        byScenario.computeIfAbsent(scenario, key -> new LinkedHashMap<>());
                Result held = cells.get(result.target);
                if (held == null || rank(result.status) > rank(held.status)) {
                    cells.put(result.target, result);
                }
            }
        }
        for (Map.Entry<String, Map<String, Result>> row : byScenario.entrySet()) {
            row(out, row.getKey(), row.getValue());
        }

        out.append("\n## Tests\n\n");
        header(out, "Test");
        Map<String, Map<String, Result>> byTest = new TreeMap<>();
        for (Result result : RESULTS) {
            byTest.computeIfAbsent(result.test, key -> new LinkedHashMap<>())
                    .put(result.target, result);
        }
        for (Map.Entry<String, Map<String, Result>> row : byTest.entrySet()) {
            row(out, row.getKey(), row.getValue());
        }
        return out.toString();
    }

    private static void header(StringBuilder out, String first) {
        out.append("| ").append(first).append(" |");
        for (String target : columns()) {
            out.append(' ').append(target).append(" |");
        }
        out.append("\n|---|");
        for (int index = 0; index < columns().size(); index++) {
            out.append("---|");
        }
        out.append('\n');
    }

    private static void row(StringBuilder out, String name, Map<String, Result> cells) {
        out.append("| ").append(name.replace("|", "/")).append(" |");
        for (String target : columns()) {
            Result result = cells.get(target);
            out.append(' ').append(result == null ? "" : result.cell()).append(" |");
        }
        out.append('\n');
    }

    /** The targets asked for, every one when none was. */
    private static List<String> columns() {
        Set<String> asked = E2eTarget.asked();
        return asked.isEmpty() ? E2eTarget.ALL : new ArrayList<>(asked);
    }

    /** A failure outweighs a pass, which outweighs a skip. */
    private static int rank(String status) {
        switch (status) {
            case "FAILED":
                return 2;
            case "passed":
                return 1;
            default:
                return 0;
        }
    }
}
