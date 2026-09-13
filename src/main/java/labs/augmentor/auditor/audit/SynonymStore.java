package labs.augmentor.auditor.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import labs.augmentor.auditor.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The approved vocabulary rules. A rule is directional: {@code toddler -> kids}
 * means a shopper typing "toddler" should be able to reach products the catalogue
 * describes as "kids".
 *
 * There is exactly one live store per process, behind {@link #shared()}. Two
 * instances means two in-memory caches: the applier writes into one, the search
 * pipeline reads the other, and the evaluator then reports +0.0000 for a change
 * that demonstrably works — and writes that zero into the audit row. Validation
 * runs against {@link #plus} instead, which is detached on purpose and never
 * touches disk.
 */
public final class SynonymStore {

    private static final Path DEFAULT_FILE = Path.of("data", "synonyms.json");
    private static volatile SynonymStore shared;

    private final Path file;                       // null = detached, never persisted
    private final Map<String, Set<String>> rules;
    private volatile long loadedAt;

    private SynonymStore(Path file, Map<String, Set<String>> rules) {
        this.file = file;
        this.rules = rules;
        this.loadedAt = modified(file);
    }

    /**
     * Re-read the rule file if another process has written to it. Returns true when
     * something changed, so the caller knows to rebuild its index.
     *
     * The worker applies rules in its own process; the UI would otherwise serve a
     * rule set loaded at startup and show +0.0000 for an approval that plainly
     * worked. Within a process there is still exactly one store — this is how the
     * one store finds out that the file underneath it moved.
     */
    public synchronized boolean reloadIfChanged() {
        if (file == null) return false;
        long current = modified(file);
        if (current == loadedAt) return false;
        Map<String, Set<String>> fresh = load(file).rules;
        rules.clear();
        rules.putAll(fresh);
        loadedAt = current;
        return true;
    }

    private static long modified(Path file) {
        if (file == null || !Files.exists(file)) return 0L;
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** The one live store. Everything that reads or writes approved rules uses this. */
    public static SynonymStore shared() {
        if (shared == null) {
            synchronized (SynonymStore.class) {
                if (shared == null) shared = load(DEFAULT_FILE);
            }
        }
        return shared;
    }

    public static SynonymStore load(Path file) {
        Map<String, Set<String>> rules = new LinkedHashMap<>();
        if (Files.exists(file)) {
            try {
                Map<String, List<String>> raw = Json.mapper().readValue(
                        Files.readString(file), new TypeReference<Map<String, List<String>>>() {});
                raw.forEach((from, to) -> rules.put(norm(from), new LinkedHashSet<>(to.stream().map(SynonymStore::norm).toList())));
            } catch (IOException e) {
                throw new IllegalStateException("could not read " + file, e);
            }
        }
        return new SynonymStore(file, rules);
    }

    /** An empty store that is never written to disk. For tests. */
    public static SynonymStore detached() {
        return new SynonymStore(null, new LinkedHashMap<>());
    }

    /**
     * This store plus one candidate rule, detached. The validator indexes against
     * the result and throws it away; nothing a human has not approved is persisted.
     */
    public SynonymStore plus(String from, String to) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        rules.forEach((k, v) -> copy.put(k, new LinkedHashSet<>(v)));
        copy.computeIfAbsent(norm(from), k -> new LinkedHashSet<>()).add(norm(to));
        return new SynonymStore(null, copy);
    }

    public synchronized void add(String from, String to) {
        rules.computeIfAbsent(norm(from), k -> new LinkedHashSet<>()).add(norm(to));
        persist();
    }

    /** Catalogue words that a shopper's word should be allowed to reach. */
    public Set<String> targetsOf(String queryTerm) {
        return rules.getOrDefault(norm(queryTerm), Set.of());
    }

    /**
     * Shopper words that this catalogue word should answer to — the reverse index,
     * which is the direction the indexer needs.
     */
    public Set<String> shopperWordsFor(String catalogueTerm) {
        String term = norm(catalogueTerm);
        Set<String> out = new LinkedHashSet<>();
        rules.forEach((from, targets) -> {
            if (targets.contains(term)) out.add(from);
        });
        return out;
    }

    public Map<String, Set<String>> rules() {
        return Collections.unmodifiableMap(rules);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.values().stream().mapToInt(Set::size).sum();
    }

    private void persist() {
        if (file == null) return;                  // detached: validation state, not a decision
        try {
            Map<String, List<String>> out = new LinkedHashMap<>();
            rules.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Json.write(out));
            loadedAt = modified(file);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
