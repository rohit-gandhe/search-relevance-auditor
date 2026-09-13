package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * A human's answer to a measured proposal, on its way to the queue.
 *
 * It carries the Slack coordinates so the worker can come back and update the very
 * message that was clicked, and it carries what the gate predicted so the worker
 * can check the prediction against what actually happened after re-indexing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Decision(
        String id,
        String proposalId,
        Action action,
        ProposedChange change,
        String queryId,
        String query,
        String decidedBy,
        String channel,
        String messageTs,
        double predictedOverall,
        double predictedWorst,
        double baselineMean,
        Instant decidedAt) {

    public enum Action { APPROVE, REJECT }

    public static Decision of(Proposal proposal, Validation validation, Action action,
                              String decidedBy, String channel, String messageTs) {
        return new Decision(
                UUID.randomUUID().toString().substring(0, 8),
                proposal.id(), action, proposal.change(),
                proposal.queryId(), proposal.query(),
                decidedBy, channel, messageTs,
                validation.overall(), validation.worstQuery(), validation.before(),
                Instant.now());
    }

    @JsonIgnore
    public boolean isApproval() {
        return action == Action.APPROVE;
    }

    /**
     * The idempotency key. SQS is at-least-once, so the same decision can arrive
     * twice; this is what makes the second arrival a no-op rather than a second
     * rule and a second row in the sheet.
     */
    @JsonIgnore
    public String dedupeKey() {
        return proposalId + ":" + action;
    }
}
