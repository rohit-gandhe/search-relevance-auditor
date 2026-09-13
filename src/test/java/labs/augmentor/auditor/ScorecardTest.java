package labs.augmentor.auditor;

import labs.augmentor.auditor.eval.Scorecard;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScorecardTest {

    private static Scorecard card(String label, double a, double b, double c) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("1", a);
        scores.put("2", b);
        scores.put("3", c);
        Map<String, String> text = new LinkedHashMap<>();
        text.put("1", "first");
        text.put("2", "second");
        text.put("3", "third");
        return new Scorecard(label, scores, text);
    }

    @Test
    void meanIsOverEveryQuery() {
        assertEquals(0.5, card("x", 0.25, 0.5, 0.75).mean(), 1e-9);
    }

    @Test
    void deltaReportsBothTheMeanAndTheWorstQuery() {
        Scorecard before = card("before", 0.5, 0.5, 0.5);
        Scorecard after = card("after", 0.9, 0.5, 0.4);

        Scorecard.Delta delta = after.against(before);
        assertEquals(0.1, delta.overall(), 1e-9);
        assertEquals(-0.1, delta.worstQuery(), 1e-9);
        assertEquals("third", delta.worstQueryText());
    }

    @Test
    void worstQueryIsZeroNotNegativeWhenNothingRegressed() {
        Scorecard before = card("before", 0.5, 0.5, 0.5);
        Scorecard after = card("after", 0.9, 0.5, 0.5);
        assertEquals(0.0, after.against(before).worstQuery(), 1e-9);
    }
}
