package labs.augmentor.auditor;

import labs.augmentor.auditor.audit.RuleValidator;
import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate. These are the tests that matter most: everything else in the project
 * can be wrong and produce a bad demo, and this being wrong produces a confident
 * bad demo.
 */
class RuleValidatorTest {

    private static Product p(String id, String title) {
        return new Product(id, title, "", "seating", Map.of(), null);
    }

    private static final List<Product> CATALOGUE = List.of(
            p("1", "bentley kids cotton sofa"),
            p("2", "errai kids cotton sofa"),
            p("3", "donaldson teak couch"),
            p("4", "velvet chaise lounge couch"),
            p("5", "stainless steel toaster"));

    private static final List<JudgedQuery> QUERIES = List.of(
            new JudgedQuery("1", "toddler couch", Map.of("1", 2, "2", 2, "5", 0)),
            new JudgedQuery("2", "chaise lounge couch", Map.of("4", 2, "3", 1, "5", 0)));

    private static RuleValidator validator() {
        return new RuleValidator(CATALOGUE, QUERIES, SynonymStore.detached());
    }

    private static Validation check(String from, String to) {
        return validator().validate(Proposal.of(
                VocabularyGap.none("-", "-", 0, 0),
                ProposedChange.synonym(from, to, "test")));
    }

    @Test
    void aRuleThatHelpsAndHurtsNothingPasses() {
        Validation v = check("toddler", "kids");
        assertEquals(Validation.Status.PROPOSED, v.status());
        assertTrue(v.overall() > 0, "expected a gain, got " + v.overall());
        assertTrue(v.worstQuery() >= 0);
    }

    @Test
    void aRuleNoProductCanSatisfyIsRejectedAsInert() {
        Validation v = check("couch", "davenport");
        assertEquals(Validation.Status.REJECTED, v.status());
        assertEquals(0, v.reachableDocs());
        assertTrue(v.reason().contains("inert"), v.reason());
    }

    @Test
    void aMorphologicalVariantIsRejectedBeforeItIsEvenMeasured() {
        // chair -> chairs is one token to the index, so it adds no vocabulary. It
        // adds a second clause for a term the shopper already typed and doubles its
        // weight, which measures as a real gain and is not a vocabulary discovery.
        Validation v = check("sofa", "sofas");
        assertEquals(Validation.Status.REJECTED, v.status());
        assertTrue(v.reason().contains("one token"), v.reason());
    }

    @Test
    void aRuleThatChangesNothingIsRejectedForNoGain() {
        Validation v = check("toaster", "steel");
        assertEquals(Validation.Status.REJECTED, v.status());
    }

    @Test
    void validationNeverWritesToTheLiveStore() {
        SynonymStore live = SynonymStore.detached();
        RuleValidator validator = new RuleValidator(CATALOGUE, QUERIES, live);
        validator.validate(Proposal.of(VocabularyGap.none("-", "-", 0, 0),
                ProposedChange.synonym("toddler", "kids", "test")));
        assertTrue(live.isEmpty(), "the gate must not approve anything by measuring it");
    }

    @Test
    void theBaselineIsTakenOnceAndDoesNotDrift() {
        RuleValidator validator = validator();
        double first = validator.baseline().mean();
        validator.validate(Proposal.of(VocabularyGap.none("-", "-", 0, 0),
                ProposedChange.synonym("toddler", "kids", "test")));
        assertEquals(first, validator.baseline().mean(), 1e-12);
    }
}
