package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One row per decision: what was proposed, what a human said, what it actually did. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditRecord(
        Instant decidedAt,
        String proposalId,
        String decisionId,
        String query,
        String from,
        String to,
        String action,
        String decidedBy,
        double predictedOverall,
        double actualOverall,
        double worstQuery,
        double meanBefore,
        double meanAfter,
        int reachableDocs,
        String slackTs,
        String pullRequest,
        String rationale) {

    public static final List<String> HEADERS = List.of(
            "decided at", "proposal", "decision", "query", "from", "to", "action", "decided by",
            "predicted overall", "actual overall", "worst query", "mean before", "mean after",
            "products reached", "slack ts", "pull request", "rationale");

    /**
     * Everything as a string, because the sheet is written with RAW.
     *
     * USER_ENTERED parses 1789072472.500979 as a number and silently drops the
     * precision — destroying the one field that can find the Slack message again.
     * Formatting here keeps the choice of representation in one place.
     */
    @JsonIgnore
    public List<Object> toRow() {
        List<Object> row = new ArrayList<>();
        row.add(decidedAt.toString());
        row.add(proposalId);
        row.add(decisionId);
        row.add(query);
        row.add(from);
        row.add(to);
        row.add(action);
        row.add(decidedBy);
        row.add(String.format("%+.4f", predictedOverall));
        row.add(String.format("%+.4f", actualOverall));
        row.add(String.format("%+.4f", worstQuery));
        row.add(String.format("%.4f", meanBefore));
        row.add(String.format("%.4f", meanAfter));
        row.add(String.valueOf(reachableDocs));
        row.add(slackTs == null ? "" : slackTs);
        row.add(pullRequest == null ? "" : pullRequest);
        row.add(rationale == null ? "" : rationale);
        return row;
    }
}
