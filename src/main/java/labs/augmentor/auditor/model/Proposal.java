package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/** A candidate rule, with the evidence that produced it. Not yet measured. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Proposal(
        String id,
        String queryId,
        String query,
        ProposedChange change,
        VocabularyGap evidence,
        Instant createdAt) {

    public static Proposal of(VocabularyGap gap, ProposedChange change) {
        return new Proposal(UUID.randomUUID().toString().substring(0, 8),
                gap.queryId(), gap.query(), change, gap, Instant.now());
    }

    @JsonIgnore
    public String label() {
        return change.arrow();
    }
}
