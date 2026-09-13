package labs.augmentor.auditor;

import labs.augmentor.auditor.retrieve.Analysis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {

    @Test
    void pluralsAreOneTokenToTheIndex() {
        assertTrue(Analysis.sameToken("chair", "chairs"));
        assertTrue(Analysis.sameToken("pillow", "pillows"));
    }

    @Test
    void differentWordsAreDifferentTokens() {
        assertFalse(Analysis.sameToken("couch", "sofa"));
        assertFalse(Analysis.sameToken("toddler", "kids"));
    }

    @Test
    void aWordTheAnalyzerDropsStillHasAToken() {
        // "out" is a stopword; without a fallback this returns nothing and every
        // comparison against it silently succeeds.
        assertEquals("out", Analysis.stem("out"));
    }
}
