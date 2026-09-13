package labs.augmentor.auditor;

import labs.augmentor.auditor.audit.SynonymStore;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SynonymStoreTest {

    @Test
    void plusDoesNotTouchTheStoreItCameFrom() {
        // The gate builds candidates with plus() constantly. If that mutated the
        // live store, measuring a rule would approve it.
        SynonymStore live = SynonymStore.detached();
        SynonymStore candidate = live.plus("toddler", "kids");

        assertTrue(live.isEmpty());
        assertEquals(Set.of("kids"), candidate.targetsOf("toddler"));
    }

    @Test
    void rulesAreDirectional() {
        SynonymStore store = SynonymStore.detached().plus("toddler", "kids");
        assertEquals(Set.of("kids"), store.targetsOf("toddler"));
        assertTrue(store.targetsOf("kids").isEmpty());
    }

    @Test
    void wordsAreNormalisedOnTheWayIn() {
        SynonymStore store = SynonymStore.detached().plus("  Toddler ", "KIDS");
        assertEquals(Set.of("kids"), store.targetsOf("toddler"));
    }

    @Test
    void theReverseDirectionIsAvailableForIndexing() {
        SynonymStore store = SynonymStore.detached().plus("toddler", "kids");
        assertEquals(Set.of("toddler"), store.shopperWordsFor("kids"));
    }

    @Test
    void sizeCountsRulesNotKeys() {
        SynonymStore store = SynonymStore.detached().plus("couch", "sofa").plus("couch", "settee");
        assertEquals(2, store.size());
    }
}
