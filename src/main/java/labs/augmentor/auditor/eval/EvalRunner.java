package labs.augmentor.auditor.eval;

import labs.augmentor.auditor.model.Hit;
import labs.augmentor.auditor.model.JudgedQuery;
import labs.augmentor.auditor.retrieve.Searcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scores a Searcher over the judged queries. Step 1, before there is an index. */
public final class EvalRunner {

    public static final int K = 10;

    private final List<JudgedQuery> queries;
    private final int k;

    public EvalRunner(List<JudgedQuery> queries) {
        this(queries, K);
    }

    public EvalRunner(List<JudgedQuery> queries, int k) {
        this.queries = List.copyOf(queries);
        this.k = k;
    }

    public Scorecard run(String label, Searcher searcher) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> text = new LinkedHashMap<>();
        for (JudgedQuery judged : queries) {
            List<String> ids = searcher.search(judged.query(), k).stream().map(Hit::id).toList();
            scores.put(judged.queryId(), Ndcg.at(k, ids, judged));
            text.put(judged.queryId(), judged.query());
        }
        return new Scorecard(label, scores, text);
    }

    public List<JudgedQuery> queries() {
        return queries;
    }

    /** Console rendering — every checkpoint in the build order is a number on this table. */
    public static String render(Scorecard card) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-28s %s%n", card.label(), "NDCG@10"));
        sb.append("-".repeat(46)).append(System.lineSeparator());
        card.perQuery().forEach((id, score) ->
                sb.append(String.format("  %-26s %.4f%n", card.queryText().getOrDefault(id, id), score)));
        sb.append("-".repeat(46)).append(System.lineSeparator());
        sb.append(String.format("  %-26s %.4f%n", "mean of " + card.size(), card.mean()));
        return sb.toString();
    }

    /** Two scorecards side by side, with the per-query movement that the gate reads. */
    public static String renderDelta(Scorecard baseline, Scorecard candidate) {
        Scorecard.Delta delta = candidate.against(baseline);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-28s %8s %8s %9s%n", "query", "before", "after", "delta"));
        sb.append("-".repeat(56)).append(System.lineSeparator());
        for (Map.Entry<String, Double> e : delta.perQuery().entrySet()) {
            sb.append(String.format("  %-26s %8.4f %8.4f %+9.4f%n",
                    baseline.queryText().getOrDefault(e.getKey(), e.getKey()),
                    baseline.scoreOf(e.getKey()),
                    candidate.scoreOf(e.getKey()),
                    e.getValue()));
        }
        sb.append("-".repeat(56)).append(System.lineSeparator());
        sb.append(String.format("  %-26s %8.4f %8.4f %+9.4f%n",
                "mean", baseline.mean(), candidate.mean(), delta.overall()));
        return sb.toString();
    }
}
