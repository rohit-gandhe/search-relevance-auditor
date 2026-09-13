package labs.augmentor.auditor;

import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.model.Hit;
import labs.augmentor.auditor.model.Product;
import labs.augmentor.auditor.retrieve.LuceneIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LuceneIndexTest {

    private static Product p(String id, String title, String description) {
        return new Product(id, title, description, "seating", Map.of(), null);
    }

    private static final List<Product> CATALOGUE = List.of(
            p("1", "bentley kids cotton sofa", "a small sofa for a child's room"),
            p("2", "donaldson teak couch", "an outdoor couch in solid teak"),
            p("3", "velvet chaise lounge", "a long upholstered chaise"),
            p("4", "stainless steel toaster", "four slices"));

    private static List<String> ids(List<Hit> hits) {
        return hits.stream().map(Hit::id).toList();
    }

    @Test
    void findsTheObviousThing() {
        try (LuceneIndex index = new LuceneIndex(CATALOGUE)) {
            assertEquals("2", ids(index.search("teak couch", 10)).get(0));
        }
    }

    @Test
    void titleOutweighsDescription() {
        try (LuceneIndex index = new LuceneIndex(CATALOGUE)) {
            // "couch" is in both documents; only one has it in the title.
            assertEquals("2", ids(index.search("couch", 10)).get(0));
        }
    }

    @Test
    void queryPunctuationDoesNotBlowUp() {
        // A stray "+" or "-" is query syntax to the parser. Unescaped it throws,
        // which presents as zero results for a query that plainly should match.
        try (LuceneIndex index = new LuceneIndex(CATALOGUE)) {
            assertDoesNotThrow(() -> index.search("sofa + couch -teak (velvet)", 10));
            assertDoesNotThrow(() -> index.search("3 in 1 fold-out \"kids\"", 10));
            assertFalse(index.search("sofa + couch", 10).isEmpty());
        }
    }

    @Test
    void withoutARuleTheShoppersWordFindsNothing() {
        try (LuceneIndex index = new LuceneIndex(CATALOGUE)) {
            assertTrue(ids(index.search("toddler", 10)).isEmpty());
        }
    }

    @Test
    void aRuleReachesProductsTheWordNeverAppearsIn() {
        SynonymStore rules = SynonymStore.detached().plus("toddler", "kids");
        try (LuceneIndex index = new LuceneIndex(CATALOGUE, rules)) {
            assertEquals(List.of("1"), ids(index.search("toddler", 10)));
        }
    }

    @Test
    void theRuleWeightIsActuallyApplied() {
        // Weight is a real knob, swept against the judged queries rather than
        // chosen. Turned down, a product that literally says the word beats one
        // that only reaches the query through a rule.
        SynonymStore rules = SynonymStore.detached().plus("couch", "chaise");
        try (LuceneIndex quiet = new LuceneIndex(CATALOGUE, rules, 0.05f)) {
            assertEquals("2", ids(quiet.search("couch", 10)).get(0));
        }
        try (LuceneIndex loud = new LuceneIndex(CATALOGUE, rules, 8.0f)) {
            assertEquals("3", ids(loud.search("couch", 10)).get(0));
        }
    }

    @Test
    void ruleKeysCanBePhrases() {
        // "fold out -> sleeper" is a rule the model proposes readily. Matching only
        // single terms made it silently inert, and the gate then reported "no gain"
        // — a true number attached to the wrong reason.
        SynonymStore rules = SynonymStore.detached().plus("fold out", "chaise");
        try (LuceneIndex index = new LuceneIndex(CATALOGUE, rules)) {
            assertTrue(ids(index.search("fold out", 10)).contains("3"));
        }
    }

    @Test
    void aRuleNoProductCanSatisfyIsInert() {
        SynonymStore rules = SynonymStore.detached().plus("couch", "davenport");
        try (LuceneIndex index = new LuceneIndex(CATALOGUE, rules)) {
            assertEquals(0, index.reachableDocs());
        }
    }

    @Test
    void reachableDocsCountsWhatARuleCanTouch() {
        SynonymStore rules = SynonymStore.detached().plus("toddler", "kids");
        try (LuceneIndex index = new LuceneIndex(CATALOGUE, rules)) {
            assertEquals(1, index.reachableDocs());
        }
    }

    @Test
    void emptyQueryReturnsNothingRatherThanEverything() {
        try (LuceneIndex index = new LuceneIndex(CATALOGUE)) {
            assertTrue(index.search("", 10).isEmpty());
            assertTrue(index.search(null, 10).isEmpty());
        }
    }
}
