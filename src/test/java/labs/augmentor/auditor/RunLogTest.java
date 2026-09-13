package labs.augmentor.auditor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timeline panel shows one run. The trail it reads from is append-only and
 * holds every run, so the boundary is the whole of the logic.
 */
class RunLogTest {

    /** Builds a trail oldest-first, and hands back what the API returns: newest first. */
    private static List<RunLog.Entry> trail(String... stages) {
        List<RunLog.Entry> out = new ArrayList<>();
        for (int i = 0; i < stages.length; i++) {
            out.add(new RunLog.Entry(stages[i], stages[i] + " msg",
                    Instant.parse("2026-09-13T20:00:00Z").plusSeconds(i), Map.of()));
        }
        Collections.reverse(out);
        return out;
    }

    private static RunLog.Entry entry(String stage, String message) {
        return new RunLog.Entry(stage, message, Instant.parse("2026-09-13T20:00:00Z"), Map.of());
    }

    private static List<String> stages(List<RunLog.Entry> newestFirst) {
        List<RunLog.Entry> run = new ArrayList<>(RunLog.currentRun(newestFirst, 24));
        Collections.reverse(run);
        return run.stream().map(RunLog.Entry::stage).toList();
    }

    private static List<String> shown(List<RunLog.Entry> newestFirst) {
        List<RunLog.Entry> run = RunLog.currentRun(newestFirst, 24);
        List<RunLog.Entry> oldestFirst = new ArrayList<>(run);
        Collections.reverse(oldestFirst);
        return oldestFirst.stream().map(RunLog.Entry::stage).toList();
    }

    @Test
    void aRunInProgressIsShownFromTheEvaluateThatStartedIt() {
        assertEquals(List.of("evaluate", "diagnose", "proposed"),
                shown(trail("slack", "worker", "evaluate", "diagnose", "proposed")));
    }

    @Test
    void theStartupMarksBeforeARunAreNotPartOfIt() {
        assertEquals(List.of("evaluate", "applied", "archived"),
                shown(trail("slack", "worker", "evaluate", "applied", "archived")));
    }

    @Test
    void onlyTheLastRunSurvivesWhenTwoAreInTheFile() {
        assertEquals(List.of("evaluate", "diagnose", "archived"),
                shown(trail("evaluate", "applied", "archived",
                            "evaluate", "diagnose", "archived")));
    }

    @Test
    void aRestartAfterARunDoesNotReplaceTheRunItFollowed() {
        // The reason this matters: a judge opening the UI after the demo has
        // finished should still see what the agent did, not one idle chip.
        assertEquals(List.of("evaluate", "applied", "archived"),
                shown(trail("evaluate", "applied", "archived", "slack", "worker")));
    }

    @Test
    void withNothingRunYetTheStartupMarksAreAllThereIsToShow() {
        assertEquals(List.of("slack", "worker"), shown(trail("slack", "worker")));
    }

    @Test
    void theSameStageLoggedTwiceInARowBecomesOneChip() {
        // The component logs the work and the Lifecycle mark times it, so most
        // stages arrive twice. Two identical chips read as a repeated run.
        List<RunLog.Entry> t = new ArrayList<>(List.of(
                entry("evaluate", "9 queries"),
                entry("published", "pull request for toddler -> kids"),
                entry("published", "pull request opened — https://example/pull/3"),
                entry("archived", "archived")));
        Collections.reverse(t);
        assertEquals(List.of("evaluate", "published", "archived"), stages(t));
    }

    @Test
    void theLongerOfTwoIsKeptBecauseItCarriesTheDetail() {
        List<RunLog.Entry> t = new ArrayList<>(List.of(
                entry("evaluate", "9 queries"),
                entry("published", "pull request for toddler -> kids"),
                entry("published", "pull request opened — https://example/pull/3"),
                entry("archived", "archived")));
        Collections.reverse(t);
        List<RunLog.Entry> run = RunLog.currentRun(t, 24);
        assertTrue(run.stream().anyMatch(e -> e.message().contains("https://example/pull/3")),
                "the chip that survives should be the one carrying the URL");
    }

    @Test
    void aStageThatRepeatsWithDifferentContentIsNotCollapsed() {
        // One diagnose per candidate query, and they are not adjacent.
        List<RunLog.Entry> t = new ArrayList<>(List.of(
                entry("evaluate", "9 queries"),
                entry("diagnose", "teal chair"),
                entry("measure", "rejected chair -> armchair"),
                entry("diagnose", "toddler couch fold out"),
                entry("archived", "archived")));
        Collections.reverse(t);
        assertEquals(List.of("evaluate", "diagnose", "measure", "diagnose", "archived"), stages(t));
    }

    @Test
    void anEmptyTrailIsNotAFailure() {
        assertEquals(List.of(), shown(trail()));
    }
}
