package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * What the measurement gate decided, and the numbers it decided on.
 *
 * Both conditions are required. A rule that lifts the mean and ruins one query is
 * rejected, because a shopper does not experience the mean.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Validation(
        String proposalId,
        Status status,
        double before,
        double after,
        double overall,
        double worstQuery,
        String worstQueryId,
        String worstQueryText,
        int reachableDocs,
        Map<String, Double> perQuery,
        String reason) {

    public enum Status { PROPOSED, REJECTED }

    @JsonIgnore
    public boolean passed() {
        return status == Status.PROPOSED;
    }

    @JsonIgnore
    public String line() {
        return String.format("%-22s overall %+.4f · worst query %+.4f   %s",
                proposalId, overall, worstQuery, status);
    }
}
