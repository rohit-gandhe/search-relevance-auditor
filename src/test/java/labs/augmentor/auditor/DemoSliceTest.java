package labs.augmentor.auditor;

import labs.augmentor.auditor.audit.RuleValidator;
import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.audit.VocabularyGapFinder;
import labs.augmentor.auditor.eval.EvalRunner;
import labs.augmentor.auditor.eval.Scorecard;
import labs.augmentor.auditor.ingest.Catalogues;
import labs.augmentor.auditor.model.JudgedQuery;
import labs.augmentor.auditor.model.Product;
import labs.augmentor.auditor.model.*;
import labs.augmentor.auditor.model.VocabularyGap;
import labs.augmentor.auditor.retrieve.LuceneIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The real slice. Deterministic, so these are assertions and not observations. */
class DemoSliceTest {

    private static final Catalogues.Catalogue CATALOGUE = Catalogues.active();

    @Test
    void theSliceIsTheSizeItIsMeantToBe() {
        assertEquals(617, CATALOGUE.size());
        assertEquals(9, CATALOGUE.queries().size());
    }

    @Test
    void everyProductHasATitleAndARender() {
        for (Product p : CATALOGUE.products()) {
            assertNotNull(p.id());
            assertFalse(p.title().isBlank(), p.id());
            assertTrue(p.hasImage(), p.id() + " has no render");
        }
    }

    @Test
    void everyJudgedQueryHasSomethingRelevantInTheSlice() {
        for (JudgedQuery q : CATALOGUE.queries()) {
            assertFalse(q.relevantIds().isEmpty(), q.query());
        }
    }

    @Test
    void theVocabularyGapIsReal() {
        // The whole premise, asserted against the data rather than described.
        try (LuceneIndex index = new LuceneIndex(CATALOGUE.products(), SynonymStore.detached())) {
            VocabularyGapFinder finder = new VocabularyGapFinder(index);
            assertTrue(finder.titlesUsing("sofa") > 8 * finder.titlesUsing("couch"),
                    "sofa " + finder.titlesUsing("sofa") + " vs couch " + finder.titlesUsing("couch"));
        }
    }

    @Test
    void theColourQueriesHaveNoAddressableGap() {
        // Dead end one, kept as a test so nobody spends another day on it. Wayfair
        // sells the same sofa in twelve colours, so the copy is deliberately
        // colour-agnostic; colour lives in a variant picker, not in prose.
        try (LuceneIndex index = new LuceneIndex(CATALOGUE.products(), SynonymStore.detached())) {
            Scorecard card = new EvalRunner(CATALOGUE.queries()).run("baseline", index);
            VocabularyGapFinder finder = new VocabularyGapFinder(index);
            JudgedQuery teal = CATALOGUE.queries().stream()
                    .filter(q -> q.query().equals("teal chair")).findFirst().orElseThrow();
            VocabularyGap gap = finder.analyse(teal, card.scoreOf(teal.queryId()));
            assertTrue(gap.catalogueVocabulary().stream().noneMatch(t -> t.term().equals("teal")),
                    "no product recovers its colour from its own text");
        }
    }

    @Test
    void couchToSofaLiftsTheAverageAndIsRejectedAnyway() {
        // The whole argument of the project in one assertion, on the real slice.
        // couch -> sofa is a textbook synonym and improves the mean. It costs
        // -0.0842 on "chaise lounge couch", because a chaise lounge couch is not
        // any sofa. A shopper does not experience the mean.
        RuleValidator validator = new RuleValidator(
                CATALOGUE.products(), CATALOGUE.queries(), SynonymStore.detached());
        Validation v = validator.validate(Proposal.of(VocabularyGap.none("-", "-", 0, 0),
                ProposedChange.synonym("couch", "sofa", "a textbook synonym")));

        assertTrue(v.overall() > 0, "it really does lift the mean: " + v.overall());
        assertTrue(v.worstQuery() < 0, "and it really does cost a query: " + v.worstQuery());
        assertEquals(Validation.Status.REJECTED, v.status());
        assertTrue(v.reason().contains("ruins a real query"), v.reason());
    }

    @Test
    void toddlerToKidsIsTheOneThatSurvives() {
        RuleValidator validator = new RuleValidator(
                CATALOGUE.products(), CATALOGUE.queries(), SynonymStore.detached());
        Validation v = validator.validate(Proposal.of(VocabularyGap.none("-", "-", 0, 0),
                ProposedChange.synonym("toddler", "kids", "the catalogue's word")));

        assertEquals(Validation.Status.PROPOSED, v.status());
        assertEquals(0.0306, v.overall(), 0.002);
        assertEquals(0.0, v.worstQuery(), 1e-9);
    }

    @Test
    void theBaselineIsWhereItWasLeft() {
        try (LuceneIndex index = new LuceneIndex(CATALOGUE.products(), SynonymStore.detached())) {
            Scorecard card = new EvalRunner(CATALOGUE.queries()).run("baseline", index);
            assertEquals(0.6949, card.mean(), 0.0005);
        }
    }
}
