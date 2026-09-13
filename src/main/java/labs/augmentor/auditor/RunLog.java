package labs.augmentor.auditor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * An append-only trail of what the pipeline did, so the agent's work is visible
 * rather than asserted. One JSON object per line; the UI timeline reads the tail.
 *
 * {@link #disable()} exists because the tests exercise the worker end to end, and
 * without it the demo timeline fills up with "sheets 503" and "boom" from fixtures
 * — entries that look exactly like a real run having gone wrong, five minutes
 * before presenting. Tests call it in @BeforeAll.
 */
public final class RunLog {

    private static final Path FILE = Path.of("data", "run-log.jsonl");
    private static final int TAIL = 400;
    /**
     * Off for the whole test source set, set by the build rather than by each test
     * class remembering to ask.
     *
     * The per-class version of this — RunLog.disable() in @BeforeAll — is what the
     * notes recommended, and it failed the first time a new test class was added:
     * BlocksTest builds a Lifecycle, Lifecycle records stages, and six "rule
     * applied — toddler → kids" entries appeared in the demo timeline from a test
     * that never mentions the run log. A default that has to be remembered is not a
     * default.
     */
    private static volatile boolean enabled = !"off".equals(System.getProperty("auditor.runlog"));

    private RunLog() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String stage, String message, Instant at, Map<String, Object> data) {
        public Entry {
            data = data == null ? Map.of() : new LinkedHashMap<>(data);
        }
    }

    /** Tests write to the same log the demo reads. They must not. */
    public static void disable() {
        enabled = false;
    }

    public static void enable() {
        enabled = true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void record(String stage, String message) {
        record(stage, message, Map.of());
    }

    public static void record(String stage, String message, Map<String, Object> data) {
        if (!enabled) return;
        Entry entry = new Entry(stage, message, Instant.now(), data);
        try {
            Path parent = FILE.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(FILE, Json.write(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The trail is evidence, not control flow. A run must not die because
            // it could not describe itself.
            System.err.println("run log: " + e.getMessage());
        }
    }

    public static List<Entry> recent(int limit) {
        if (!Files.exists(FILE)) return List.of();
        try {
            List<String> lines = Files.readAllLines(FILE, StandardCharsets.UTF_8);
            List<Entry> out = new ArrayList<>();
            for (String line : lines.subList(Math.max(0, lines.size() - TAIL), lines.size())) {
                if (line.isBlank()) continue;
                try {
                    out.add(Json.read(line, Entry.class));
                } catch (RuntimeException ignored) {
                    // A half-written line from a killed process is not a reason to
                    // lose the rest of the trail.
                }
            }
            Collections.reverse(out);
            return out.size() > limit ? out.subList(0, limit) : out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * One run, newest first — never the accumulation of every run in the file.
     *
     * The trail is append-only, so the panel used to show whatever the last 24
     * entries happened to be: the tail of one run, the head of the next, and
     * every restart mark in between. After three passes the interesting beat had
     * scrolled off the end and a reader could not tell which pass they were
     * looking at.
     *
     * A run starts at "evaluate" — the agent beginning a pass — and ends at the
     * "archived" that closes it, if it has closed. Restart marks logged after a
     * run finished are not a new run, so they are ignored rather than shown in
     * its place.
     */
    public static List<Entry> currentRun(int limit) {
        return currentRun(recent(TAIL), limit);
    }

    /** The boundary logic, separated from the file so it can be tested. */
    static List<Entry> currentRun(List<Entry> newestFirst, int limit) {
        List<Entry> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);

        int start = -1;
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            if ("evaluate".equals(oldestFirst.get(i).stage())) { start = i; break; }
        }
        // Nothing has run yet: the startup marks are all there is to show.
        if (start < 0) {
            List<Entry> out = new ArrayList<>(newestFirst);
            return out.size() > limit ? out.subList(0, limit) : out;
        }

        int end = oldestFirst.size() - 1;
        for (int i = start; i < oldestFirst.size(); i++) {
            if ("archived".equals(oldestFirst.get(i).stage())) { end = i; break; }
        }

        List<Entry> run = collapse(oldestFirst.subList(start, end + 1));
        Collections.reverse(run);
        return run.size() > limit ? run.subList(0, limit) : run;
    }

    /**
     * One chip per stage, not two.
     *
     * Most stages are written twice: once by the component that did the work and
     * once by the Lifecycle mark that timed it — "pull request for toddler → kids"
     * immediately followed by "pull request opened — https://...". On the panel
     * that reads as the same step happening twice, which is indistinguishable from
     * the trail having kept an earlier run.
     *
     * The longer message is kept because it is the one carrying the detail: the
     * URL, the rule, the measured delta. Stages that legitimately repeat with
     * different content — a diagnose per candidate query — are not adjacent, so
     * they survive.
     */
    private static List<Entry> collapse(List<Entry> oldestFirst) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : oldestFirst) {
            int last = out.size() - 1;
            if (last >= 0 && out.get(last).stage().equals(e.stage())) {
                if (e.message().length() > out.get(last).message().length()) out.set(last, e);
            } else {
                out.add(e);
            }
        }
        return out;
    }

    public static void clear() {
        try {
            Files.deleteIfExists(FILE);
        } catch (IOException e) {
            System.err.println("run log: " + e.getMessage());
        }
    }
}
