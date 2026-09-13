package labs.augmentor.auditor;

import labs.augmentor.auditor.audit.InMemoryAuditLog;
import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.ingest.Catalogues;
import labs.augmentor.auditor.model.*;
import labs.augmentor.auditor.worker.DecisionWorker;
import labs.augmentor.auditor.worker.Idempotency;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The worker, end to end, with no network.
 *
 * RunLog is disabled for the whole class. These tests exercise the same code the
 * demo does, including its failure paths, and without this the timeline fills with
 * "sheets 503" and "boom" — entries indistinguishable from a real run going wrong,
 * discovered five minutes before presenting.
 */
class DecisionWorkerTest {

    @BeforeAll
    static void quiet() {
        RunLog.disable();
    }

    @Test
    void theRunLogIsOffForTheWholeTestSourceSet() {
        // Belt and braces on the build's systemProperty. A test that writes to the
        // demo's timeline is a test that lies to an audience.
        assertFalse(RunLog.isEnabled(), "set -Dauditor.runlog=off for the test task");
    }

    private static final Catalogues.Catalogue CATALOGUE = Catalogues.active();

    private static Decision decision(String from, String to, Decision.Action action) {
        VocabularyGap gap = VocabularyGap.none("42", "toddler couch fold out", 0.4045, 18);
        Proposal proposal = Proposal.of(gap, ProposedChange.synonym(from, to, "because"));
        Validation validation = new Validation(proposal.id(), Validation.Status.PROPOSED,
                0.6949, 0.7254, 0.0306, 0.0, "42", "toddler couch fold out", 36, Map.of(), "ok");
        return Decision.of(proposal, validation, action, "U123", "C1", "1789072472.500979");
    }

    private record Rig(DecisionWorker worker, SynonymStore rules,
                       InMemoryAuditLog audit, FakeSlackApi slack) {}

    private static Rig rig(Path tmp) {
        return rig(tmp, "C-archive", -1);
    }

    /**
     * The archive channel and the delete delay are stated here, never read from
     * .env: a clean clone has no .env, and a test that reads one asserts on
     * whatever the developer last set for a demo.
     */
    private static Rig rig(Path tmp, String archiveChannel, int deleteDelay) {
        SynonymStore rules = SynonymStore.detached();
        InMemoryAuditLog audit = new InMemoryAuditLog();
        FakeSlackApi slack = new FakeSlackApi();
        // No RulePublisher: opening a pull request is not this test's business.
        DecisionWorker worker = new DecisionWorker(CATALOGUE, rules, audit, slack, null,
                new Idempotency(tmp.resolve("processed.txt")), archiveChannel, deleteDelay);
        return new Rig(worker, rules, audit, slack);
    }

    @Test
    void approvingAppliesTheRuleAndMeasuresWhatItDid(@TempDir Path tmp) {
        Rig rig = rig(tmp);
        DecisionWorker.Result result = rig.worker().handle(decision("toddler", "kids", Decision.Action.APPROVE));

        assertTrue(result.applied());
        assertEquals(0.0306, result.actualOverall(), 0.002);
        assertEquals(0.7254, result.meanAfter(), 0.002);
        assertEquals(java.util.Set.of("kids"), rig.rules().targetsOf("toddler"));
    }

    @Test
    void theMeasuredResultMatchesWhatTheGatePredicted(@TempDir Path tmp) {
        // The proposal was measured on a throwaway index. This is where that
        // promise is checked against a real rebuild — and if the applier and the
        // evaluator ever stop sharing one store, this is the test that catches it.
        Decision decision = decision("toddler", "kids", Decision.Action.APPROVE);
        DecisionWorker.Result result = rig(tmp).worker().handle(decision);
        assertEquals(decision.predictedOverall(), result.actualOverall(), 0.002);
    }

    @Test
    void rejectingChangesNothing(@TempDir Path tmp) {
        Rig rig = rig(tmp);
        DecisionWorker.Result result = rig.worker().handle(decision("couch", "sofa", Decision.Action.REJECT));

        assertFalse(result.applied());
        assertEquals(0.0, result.actualOverall(), 1e-9);
        assertTrue(rig.rules().isEmpty());
    }

    @Test
    void aRedeliveryIsANoOp(@TempDir Path tmp) {
        // SQS is at-least-once. Twice applied is twice in the sheet and twice in
        // the pull request list, all of which look like the system working.
        Rig rig = rig(tmp);
        Decision decision = decision("toddler", "kids", Decision.Action.APPROVE);

        assertTrue(rig.worker().handle(decision).applied());
        assertFalse(rig.worker().handle(decision).applied());
        assertEquals(1, rig.audit().all().size());
        assertEquals(1, rig.rules().size());
    }

    @Test
    void theAuditRowKeepsTheSlackTimestampIntact(@TempDir Path tmp) {
        Rig rig = rig(tmp);
        rig.worker().handle(decision("toddler", "kids", Decision.Action.APPROVE));

        AuditRecord row = rig.audit().all().get(0);
        assertEquals("1789072472.500979", row.slackTs());
        // The row is written with RAW precisely so this survives; a numeric parse
        // drops the tail and the ts stops finding the message.
        assertTrue(row.toRow().contains("1789072472.500979"));
    }

    @Test
    void theArchiveTrailIsPosted(@TempDir Path tmp) {
        Rig rig = rig(tmp);
        rig.worker().handle(decision("toddler", "kids", Decision.Action.APPROVE));
        assertEquals(1, rig.slack().posted.size());
        assertTrue(Json.write(rig.slack().posted.get(0).blocks()).contains("re-evaluated"));
    }

    @Test
    void aDelayOfMinusOneKeepsTheApprovalCard(@TempDir Path tmp) {
        Rig rig = rig(tmp, "C-archive", -1);
        rig.worker().handle(decision("toddler", "kids", Decision.Action.APPROVE));
        assertTrue(rig.slack().deleted.isEmpty(),
                "-1 keeps the card so the whole lifecycle stays on screen");
    }

    @Test
    void aDelayOfZeroClearsTheApprovalCard(@TempDir Path tmp) {
        Rig rig = rig(tmp, "C-archive", 0);
        rig.worker().handle(decision("toddler", "kids", Decision.Action.APPROVE));
        assertEquals(1, rig.slack().deleted.size(),
                "0 deletes the card, and the trail then lives only in the archive");
    }

    @Test
    void theCardIsRepaintedAsEachStageLands(@TempDir Path tmp) {
        // The archive is not where anyone is looking. The channel they clicked in
        // is, so the trail has to arrive there while the work is still running.
        Rig rig = rig(tmp, "C-archive", -1);
        rig.worker().handle(decision("toddler", "kids", Decision.Action.APPROVE));
        assertTrue(rig.slack().updated.size() > 1,
                "the card should be updated once per stage, not once at the end");
    }
}
