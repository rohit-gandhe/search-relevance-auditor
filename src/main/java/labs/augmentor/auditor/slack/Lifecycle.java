package labs.augmentor.auditor.slack;

import labs.augmentor.auditor.RunLog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Times the stages a decision passes through, so the archive trail shows what
 * actually happened rather than a diagram of what usually does.
 *
 * The queue exists because Slack allows three seconds to acknowledge a click and
 * the work takes rather longer than that. These timings are the evidence for that
 * claim, so they had better be real.
 */
public final class Lifecycle {

    public record Step(Stage stage, String detail, Duration elapsed, Instant at) {}

    private final Instant start = Instant.now();
    private final List<Step> steps = new ArrayList<>();
    private Instant last = start;

    public Lifecycle mark(Stage stage) {
        return mark(stage, null);
    }

    public Lifecycle mark(Stage stage, String detail) {
        Instant now = Instant.now();
        steps.add(new Step(stage, detail, Duration.between(last, now), now));
        last = now;
        RunLog.record(stage.name().toLowerCase(),
                detail == null ? stage.label() : stage.label() + " — " + detail,
                Map.of("elapsedMs", Duration.between(start, now).toMillis()));
        return this;
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public Duration total() {
        return Duration.between(start, last);
    }
}
