package labs.augmentor.auditor;

import labs.augmentor.auditor.eval.Ndcg;
import labs.augmentor.auditor.model.JudgedQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NdcgTest {

    private static JudgedQuery judged(Map<String, Integer> grades) {
        return new JudgedQuery("q1", "a query", grades);
    }

    @Test
    void perfectRankingScoresOne() {
        JudgedQuery q = judged(Map.of("a", 2, "b", 1, "c", 0));
        assertEquals(1.0, Ndcg.at(10, List.of("a", "b", "c"), q), 1e-9);
    }

    @Test
    void reversingTheBestResultCosts() {
        JudgedQuery q = judged(Map.of("a", 2, "b", 1, "c", 0));
        assertTrue(Ndcg.at(10, List.of("c", "b", "a"), q) < 1.0);
    }

    @Test
    void gradeTwoIsWorthMoreThanTwiceGradeOne() {
        // Exponential gain: 2^2-1 = 3 against 2^1-1 = 1.
        JudgedQuery q = judged(Map.of("exact", 2, "partial", 1));
        double exactFirst = Ndcg.at(10, List.of("exact", "partial"), q);
        double partialFirst = Ndcg.at(10, List.of("partial", "exact"), q);
        assertTrue(exactFirst > partialFirst);
        assertEquals(1.0, exactFirst, 1e-9);
    }

    @Test
    void idealIsTakenOverEverythingJudgedNotOverWhatWasRetrieved() {
        // Three relevant products exist; the run returns one, in first place.
        // Scoring that 1.0 would say a run that misses two thirds of the answer
        // is perfect, which is the bug this guards.
        JudgedQuery q = judged(Map.of("a", 2, "b", 2, "c", 2));
        assertTrue(Ndcg.at(10, List.of("a"), q) < 0.6);
    }

    @Test
    void truncatesAtK() {
        JudgedQuery q = judged(Map.of("a", 1, "b", 1));
        assertEquals(0.0, Ndcg.at(1, List.of("x", "a"), q), 1e-9);
    }

    @Test
    void nothingRelevantIsNotAFailure() {
        assertEquals(0.0, Ndcg.at(10, List.of("a"), judged(Map.of("a", 0))), 1e-9);
    }

    @Test
    void emptyResultsScoreZero() {
        assertEquals(0.0, Ndcg.at(10, List.of(), judged(Map.of("a", 2))), 1e-9);
    }
}
