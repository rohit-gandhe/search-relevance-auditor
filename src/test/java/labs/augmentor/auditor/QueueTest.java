package labs.augmentor.auditor;

import labs.augmentor.auditor.model.Decision;
import labs.augmentor.auditor.model.ProposedChange;
import labs.augmentor.auditor.worker.Idempotency;
import labs.augmentor.auditor.worker.SqsConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class QueueTest {

    @BeforeAll
    static void quiet() {
        RunLog.disable();
    }

    private static Decision sample() {
        return new Decision("d1", "p1", Decision.Action.APPROVE,
                ProposedChange.synonym("toddler", "kids", "because"),
                "42", "toddler couch fold out", "U123", "C1", "1789072472.500979",
                0.0306, 0.0, 0.6949, Instant.parse("2026-09-13T10:00:00Z"));
    }

    @Test
    void aRawDeliveredMessageIsTheDecision() {
        Decision parsed = SqsConsumer.parse(Json.write(sample()));
        assertEquals("toddler", parsed.change().from());
        assertEquals("1789072472.500979", parsed.messageTs());
    }

    @Test
    void anSnsEnvelopeIsUnwrapped() {
        // Raw message delivery has been switched off by accident before, and the
        // resulting failure surfaces a long way from its cause.
        String envelope = Json.write(java.util.Map.of(
                "Type", "Notification", "MessageId", "abc", "Message", Json.write(sample())));
        assertEquals("kids", SqsConsumer.parse(envelope).change().to());
    }

    @Test
    void aDecisionCarriesAnInstantThroughTheQueue() {
        assertEquals(sample().decidedAt(), SqsConsumer.parse(Json.write(sample())).decidedAt());
    }

    @Test
    void rubbishOnTheQueueThrowsRatherThanReturningNull() {
        assertThrows(RuntimeException.class, () -> SqsConsumer.parse("not json at all"));
    }

    @Test
    void theDedupeKeyDistinguishesApprovalFromRejection() {
        Decision approve = sample();
        Decision reject = new Decision(approve.id(), approve.proposalId(), Decision.Action.REJECT,
                approve.change(), approve.queryId(), approve.query(), approve.decidedBy(),
                approve.channel(), approve.messageTs(), 0, 0, 0, approve.decidedAt());
        assertNotEquals(approve.dedupeKey(), reject.dedupeKey());
    }

    @Test
    void idempotencySurvivesARestart(@TempDir Path tmp) {
        Path file = tmp.resolve("processed.txt");
        Idempotency first = new Idempotency(file);
        assertTrue(first.isNew("p1:APPROVE"));
        first.remember("p1:APPROVE");

        // A worker restarted mid-demo must not re-apply what it already applied.
        assertFalse(new Idempotency(file).isNew("p1:APPROVE"));
    }
}
