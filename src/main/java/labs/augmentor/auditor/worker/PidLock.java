package labs.augmentor.auditor.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One worker per queue.
 *
 * Each SQS message goes to exactly one consumer, so a second worker left running
 * from an earlier session silently handles half the approvals — with older code, a
 * stale rule set, and its output going nowhere anyone is looking. From the outside
 * it presents as "Slack is failing", intermittently, which is the worst possible
 * shape for a bug to have twenty minutes before a demo.
 */
public final class PidLock implements AutoCloseable {

    private final Path file;

    private PidLock(Path file) {
        this.file = file;
    }

    public static PidLock acquire(String name) {
        Path file = Path.of("data", name + ".pid");
        try {
            if (Files.exists(file)) {
                String existing = Files.readString(file).strip();
                if (isAlive(existing)) {
                    throw new IllegalStateException(
                            name + " is already running as pid " + existing
                                    + " — use ./scripts/demo.sh restart, never start one by hand");
                }
                Files.delete(file);
            }
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, String.valueOf(ProcessHandle.current().pid()));
            return new PidLock(file);
        } catch (IOException e) {
            throw new IllegalStateException("could not take the " + name + " lock", e);
        }
    }

    private static boolean isAlive(String pid) {
        try {
            return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
