package labs.augmentor.auditor.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * SQS delivers at least once. Without this, a redelivery applies the rule twice,
 * writes a second row to the sheet, and opens a second pull request — all of which
 * look like the system working, which is what makes it dangerous.
 */
public final class Idempotency {

    private final Path file;
    private final Set<String> seen = new HashSet<>();

    public Idempotency() {
        this(Path.of("data", "processed.txt"));
    }

    public Idempotency(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                seen.addAll(Files.readAllLines(file));
            } catch (IOException e) {
                throw new IllegalStateException("could not read " + file, e);
            }
        }
    }

    public synchronized boolean isNew(String key) {
        return !seen.contains(key);
    }

    public synchronized void remember(String key) {
        if (!seen.add(key)) return;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, key + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }

    public synchronized void clear() {
        seen.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
