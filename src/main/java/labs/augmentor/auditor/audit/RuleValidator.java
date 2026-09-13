package labs.augmentor.auditor.audit;

import labs.augmentor.auditor.eval.EvalRunner;
import labs.augmentor.auditor.eval.Scorecard;
import labs.augmentor.auditor.model.JudgedQuery;
import labs.augmentor.auditor.model.Product;
import labs.augmentor.auditor.model.Proposal;
import labs.augmentor.auditor.model.Validation;
import labs.augmentor.auditor.retrieve.Analysis;
import labs.augmentor.auditor.retrieve.LuceneIndex;

import java.util.List;
import java.util.Map;

/**
 * The measurement gate. This is the differentiator, and it runs before a human
 * sees anything.
 *
 * The model proposes different candidates on different runs — toddler→kids, then
 * fold→sleeper, then couch→sofa. Without a gate the result swings between +0.21
 * and −0.15 depending on which one it happened to say. With one, the swing is
 * absorbed: a good rule gets through and a bad one never reaches Slack.
 *
 * Two conditions, both required:
 *
 *   1. the mean over the judged queries goes up, and
 *   2. no individual query goes down.
 *
 * The second is the one that earns its keep. couch→sofa is a textbook synonym and
 * lifts the mean by +0.0089 — while costing −0.1075 on "toddler couch fold out",
 * because a toddler couch is not an adult sofa. It improves the average and ruins
 * a real search. A shopper does not experience the average.
 *
 * Validation runs against a detached SynonymStore and a throwaway index. Nothing a
 * human has not approved is ever written to disk.
 */
public final class RuleValidator {

    /** Floating-point slack. A query that moves by 1e-9 has not moved. */
    private static final double EPSILON = 1e-9;

    private final List<Product> products;
    private final SynonymStore live;
    private final EvalRunner runner;
    private final Scorecard baseline;

    public RuleValidator(List<Product> products, List<JudgedQuery> queries, SynonymStore live) {
        this.products = products;
        this.live = live;
        this.runner = new EvalRunner(queries);
        try (LuceneIndex index = new LuceneIndex(products, live)) {
            this.baseline = runner.run("baseline", index);
        }
    }

    public Scorecard baseline() {
        return baseline;
    }

    public Validation validate(Proposal proposal) {
        String from = proposal.change().from();
        String to = proposal.change().to();

        // Before measuring anything: the two words must actually be different words
        // to the index. chair -> chairs stems to one token, so it adds no vocabulary
        // — it just adds a second clause for a term the shopper already typed, and
        // doubles its weight. That measures +0.0277 with no query worse, and passing
        // it off as a vocabulary discovery would be a lie told with a real number.
        if (Analysis.sameToken(from, to)) {
            return new Validation(proposal.id(), Validation.Status.REJECTED,
                    baseline.mean(), baseline.mean(), 0, 0, null, null, 0, Map.of(),
                    "\"" + from + "\" and \"" + to + "\" are one token to the index — "
                            + "a morphological variant is the stemmer's job, not a rule");
        }

        SynonymStore candidate = live.plus(from, to);
        Scorecard after;
        int reachable;
        try (LuceneIndex index = new LuceneIndex(products, candidate)) {
            after = runner.run("candidate", index);
            reachable = index.reachableDocs();
        }

        Scorecard.Delta delta = after.against(baseline);
        Validation.Status status;
        String reason;

        if (reachable == 0) {
            status = Validation.Status.REJECTED;
            reason = "inert: no product in the catalogue uses \"" + to + "\"";
        } else if (delta.overall() <= EPSILON) {
            status = Validation.Status.REJECTED;
            reason = String.format("no gain: overall %+.4f", delta.overall());
        } else if (delta.worstQuery() < -EPSILON) {
            status = Validation.Status.REJECTED;
            reason = String.format("costs %+.4f on \"%s\" — improves the average, ruins a real query",
                    delta.worstQuery(), delta.worstQueryText());
        } else {
            status = Validation.Status.PROPOSED;
            reason = String.format("overall %+.4f, no query worse", delta.overall());
        }

        return new Validation(
                proposal.id(), status,
                baseline.mean(), after.mean(),
                delta.overall(), delta.worstQuery(),
                delta.worstQueryId(), delta.worstQueryText(),
                reachable, delta.perQuery(), reason);
    }

    /** The scorecard a passing rule would produce, for the before/after panel. */
    public Scorecard scoreWith(String from, String to) {
        try (LuceneIndex index = new LuceneIndex(products, live.plus(from, to))) {
            return runner.run(from + " -> " + to, index);
        }
    }
}
