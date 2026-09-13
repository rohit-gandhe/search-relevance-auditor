package labs.augmentor.auditor.eval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/** NDCG@10 per query for one configuration of the index. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Scorecard(String label, Map<String, Double> perQuery, Map<String, String> queryText) {

    public Scorecard {
        perQuery = perQuery == null ? Map.of() : new LinkedHashMap<>(perQuery);
        queryText = queryText == null ? Map.of() : new LinkedHashMap<>(queryText);
    }

    @JsonIgnore
    public double mean() {
        if (perQuery.isEmpty()) return 0.0;
        return perQuery.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    @JsonIgnore
    public int size() {
        return perQuery.size();
    }

    public double scoreOf(String queryId) {
        return perQuery.getOrDefault(queryId, 0.0);
    }

    /**
     * This scorecard measured against an earlier one. Both numbers matter: a rule
     * can lift the mean and still ruin a single query, and a shopper does not
     * experience the mean.
     */
    @JsonIgnore
    public Delta against(Scorecard baseline) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        for (String queryId : baseline.perQuery().keySet()) {
            deltas.put(queryId, scoreOf(queryId) - baseline.scoreOf(queryId));
        }
        String worstId = null;
        double worst = 0.0;
        for (Map.Entry<String, Double> e : deltas.entrySet()) {
            if (worstId == null || e.getValue() < worst) {
                worstId = e.getKey();
                worst = e.getValue();
            }
        }
        return new Delta(mean() - baseline.mean(), worst, worstId, deltas, queryText);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(
            double overall,
            double worstQuery,
            String worstQueryId,
            Map<String, Double> perQuery,
            Map<String, String> queryText) {

        @JsonIgnore
        public String worstQueryText() {
            return queryText.getOrDefault(worstQueryId, worstQueryId);
        }

        @JsonIgnore
        public String summary() {
            return String.format("overall %+.4f · worst query %+.4f", overall, worstQuery);
        }
    }
}
