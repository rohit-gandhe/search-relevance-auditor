package labs.augmentor.auditor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import labs.augmentor.auditor.model.Proposal;
import labs.augmentor.auditor.model.Validation;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proposals that have passed the gate and are waiting on a human, stored on disk.
 *
 * A Slack button carries a value, not an object. The button carries the proposal
 * id and this is where the rest lives — so the decision the worker acts on is the
 * proposal that was actually measured, not one reconstructed from a label. It also
 * means a restarted process can still honour a card posted before it started.
 */
public final class Proposals {

    private static final Path DIR = Path.of("data", "proposals");

    private Proposals() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pending(Proposal proposal, Validation validation, String channel, String messageTs) {
        public Pending withMessage(String channel, String messageTs) {
            return new Pending(proposal, validation, channel, messageTs);
        }
    }

    public static void save(Pending pending) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve(pending.proposal().id() + ".json"), Json.writePretty(pending));
        } catch (IOException e) {
            throw new IllegalStateException("could not save proposal " + pending.proposal().id(), e);
        }
    }

    public static Optional<Pending> load(String id) {
        Path file = DIR.resolve(id + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(Json.read(Files.readString(file), Pending.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static List<Pending> all() {
        if (!Files.exists(DIR)) return List.of();
        List<Pending> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR, "*.json")) {
            for (Path file : stream) {
                try {
                    out.add(Json.read(Files.readString(file), Pending.class));
                } catch (RuntimeException | IOException ignored) {
                    // One unreadable proposal is not a reason to lose the others.
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static void clear() {
        if (!Files.exists(DIR)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR, "*.json")) {
            for (Path file : stream) Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
