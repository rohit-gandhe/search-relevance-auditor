package labs.augmentor.auditor;

import labs.augmentor.auditor.model.*;
import labs.augmentor.auditor.slack.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlocksTest {

    private static final VocabularyGap GAP = new VocabularyGap("42", "toddler couch fold out",
            0.4045, 18, 17,
            List.of(TermCount.of("toddler", 2)),
            List.of(new TermCount("kids", 23, 17, 16.2)),
            List.of("bentley kids cotton sofa"));

    private static final Proposal PROPOSAL = Proposal.of(GAP,
            ProposedChange.synonym("toddler", "kids", "the catalogue's word for the same thing"));

    private static final Validation PASSED = new Validation(PROPOSAL.id(),
            Validation.Status.PROPOSED, 0.6949, 0.7254, 0.0306, 0.0, "42",
            "toddler couch fold out", 36, Map.of(), "overall +0.0306, no query worse");

    private static String flatten(List<Map<String, Object>> blocks) {
        return Json.write(blocks);
    }

    @Test
    void theCardCarriesTheEvidenceNotJustTheVerdict() {
        String json = flatten(Blocks.proposal(PROPOSAL, PASSED));
        assertTrue(json.contains("toddler"), json);
        assertTrue(json.contains("kids"));
        assertTrue(json.contains("+0.0306"));
        assertTrue(json.contains("2 titles"), "the shopper's word's rarity is the whole argument");
        assertTrue(json.contains("17 of the 18"), "how many products the rule would actually reach");
    }

    @Test
    void theButtonsCarryTheProposalId() {
        // A button carries a value, not an object. If this is not the id, the click
        // cannot find the proposal that was measured.
        String json = flatten(Blocks.proposal(PROPOSAL, PASSED));
        assertTrue(json.contains("\"value\":\"" + PROPOSAL.id() + "\""), json);
        assertTrue(json.contains(Blocks.APPROVE));
        assertTrue(json.contains(Blocks.REJECT));
    }

    @Test
    void aDecidedCardNoLongerOffersADecision() {
        Decision decision = Decision.of(PROPOSAL, PASSED, Decision.Action.APPROVE,
                "U123", "C1", "1789072472.500979");
        List<Map<String, Object>> blocks = Blocks.decided(PROPOSAL, PASSED, decision);

        assertTrue(blocks.stream().noneMatch(b -> "actions".equals(b.get("type"))));
        assertTrue(flatten(blocks).contains("U123"));
    }

    @Test
    void theArchiveShowsThePredictionAgainstWhatHappened() {
        Decision decision = Decision.of(PROPOSAL, PASSED, Decision.Action.APPROVE,
                "U123", "C1", "1789072472.500979");
        var lifecycle = new labs.augmentor.auditor.slack.Lifecycle();
        lifecycle.mark(labs.augmentor.auditor.slack.Stage.APPROVED)
                 .mark(labs.augmentor.auditor.slack.Stage.APPLIED, "toddler → kids")
                 .mark(labs.augmentor.auditor.slack.Stage.VERIFIED);

        String json = flatten(Blocks.archive(decision, lifecycle.steps(), 0.0306, 0.7254,
                "https://github.com/x/y/pull/1"));
        assertTrue(json.contains("Predicted"));
        assertTrue(json.contains("Measured after re-index"));
        assertTrue(json.contains("pull/1"));
    }

    @Test
    void theTrailIsOneArrowedSequenceNotAStackedList() {
        Decision decision = Decision.of(PROPOSAL, PASSED, Decision.Action.APPROVE,
                "U123", "C1", "1789072472.500979");
        var lifecycle = new labs.augmentor.auditor.slack.Lifecycle();
        lifecycle.mark(labs.augmentor.auditor.slack.Stage.APPROVED)
                 .mark(labs.augmentor.auditor.slack.Stage.APPLIED, "toddler → kids")
                 .mark(labs.augmentor.auditor.slack.Stage.ARCHIVED);

        String json = flatten(Blocks.archive(decision, lifecycle.steps(), 0.0306, 0.7254, null));
        assertTrue(json.contains("→"), "the stages should be joined by arrows");
        assertTrue(json.contains(labs.augmentor.auditor.slack.Stage.APPROVED.emoji()),
                "each stage carries its emoji");
        assertTrue(json.contains(labs.augmentor.auditor.slack.Stage.ARCHIVED.emoji()));
    }

    @Test
    void anEmptyTrailDoesNotPostAnEmptySection() {
        // Slack rejects a section whose text is blank, and a decision that failed
        // before its first mark would otherwise take the archive post down with it.
        Decision decision = Decision.of(PROPOSAL, PASSED, Decision.Action.APPROVE,
                "U123", "C1", "1789072472.500979");
        String json = flatten(Blocks.archive(decision, java.util.List.of(), 0.0306, 0.7254, null));
        assertTrue(json.contains("Predicted"), "the rest of the message still builds");
    }

    @Test
    void headersAreTruncatedRatherThanRejectedBySlack() {
        String json = Json.write(Blocks.header("x".repeat(400)));
        assertTrue(json.length() < 400, "Slack rejects a header over 150 characters");
    }
}
