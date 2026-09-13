package labs.augmentor.auditor;

import labs.augmentor.auditor.audit.ClaudeSynonymProposer;
import labs.augmentor.auditor.model.ProposedChange;
import labs.augmentor.auditor.model.TermCount;
import labs.augmentor.auditor.model.VocabularyGap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** No credentials needed: the parsing and the prompt, not the call. */
class ProposerParseTest {

    @Test
    void readsTheRules() {
        List<ProposedChange> rules = ClaudeSynonymProposer.parse(
                "{\"rules\":[{\"from\":\"toddler\",\"to\":\"kids\",\"rationale\":\"same thing\"}]}");
        assertEquals(1, rules.size());
        assertEquals("toddler", rules.get(0).from());
        assertEquals("kids", rules.get(0).to());
    }

    @Test
    void survivesCodeFences() {
        List<ProposedChange> rules = ClaudeSynonymProposer.parse(
                "```json\n{\"rules\":[{\"from\":\"couch\",\"to\":\"sofa\"}]}\n```");
        assertEquals(1, rules.size());
    }

    @Test
    void anEmptyListIsAValidAnswer() {
        assertTrue(ClaudeSynonymProposer.parse("{\"rules\":[]}").isEmpty());
    }

    @Test
    void aWordIsNotASynonymForItself() {
        assertTrue(ClaudeSynonymProposer.parse(
                "{\"rules\":[{\"from\":\"sofa\",\"to\":\"Sofa\"}]}").isEmpty());
    }

    @Test
    void halfARuleIsNoRule() {
        assertTrue(ClaudeSynonymProposer.parse(
                "{\"rules\":[{\"from\":\"couch\",\"to\":\"\"}]}").isEmpty());
    }

    @Test
    void unparseableRepliesThrowRatherThanLookLikeAnAbstention() {
        // A run that failed and a run that declined must never look the same. A
        // billing 400 once produced "140 careful abstentions" in 4.8 seconds.
        assertThrows(ClaudeSynonymProposer.ProposerException.class,
                () -> ClaudeSynonymProposer.parse("I'm sorry, I can't help with that."));
    }

    @Test
    void thePromptCarriesTheCountsTheModelIsMeantToRead() {
        VocabularyGap gap = new VocabularyGap("42", "toddler couch fold out", 0.4045, 18, 17,
                List.of(TermCount.of("toddler", 2)),
                List.of(new TermCount("kids", 23, 17, 16.2)),
                List.of("bentley kids cotton sofa"));
        String prompt = ClaudeSynonymProposer.prompt(gap);

        assertTrue(prompt.contains("toddler couch fold out"));
        assertTrue(prompt.contains("toddler"));
        assertTrue(prompt.contains("kids"));
        assertTrue(prompt.contains("reaches 17"));
        assertTrue(prompt.contains("bentley kids cotton sofa"));
    }
}
