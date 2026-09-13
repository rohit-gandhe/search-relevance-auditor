package labs.augmentor.auditor.web;

import io.javalin.Javalin;
import io.javalin.http.ContentType;
import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.RunLog;
import labs.augmentor.auditor.audit.RuleValidator;
import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.audit.VocabularyGapFinder;
import labs.augmentor.auditor.eval.EvalRunner;
import labs.augmentor.auditor.eval.Scorecard;
import labs.augmentor.auditor.eval.Ndcg;
import labs.augmentor.auditor.ingest.Catalogues;
import labs.augmentor.auditor.model.*;
import labs.augmentor.auditor.model.Hit;
import labs.augmentor.auditor.retrieve.LuceneIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Two indexes of the same catalogue, side by side: what the shopper gets today and
 * what they get with the approved rules applied.
 *
 * With nothing approved the panels are identical, which is the point — it is the
 * demo's zero state, and it is what makes the change legible when a rule lands.
 */
public final class SearchApi {

    private static final Path IMAGES = Path.of("data", "images");

    private final Catalogues.Catalogue catalogue;
    private final SynonymStore rules;
    private final LuceneIndex before;                 // no rules, ever
    private LuceneIndex after;                        // rebuilt when a rule is approved
    private final EvalRunner runner;

    public SearchApi() {
        this.catalogue = Catalogues.active();
        this.rules = SynonymStore.shared();
        this.before = new LuceneIndex(catalogue.products(), SynonymStore.detached());
        this.after = new LuceneIndex(catalogue.products(), rules);
        this.runner = new EvalRunner(catalogue.queries());
    }

    public void start(int port) {
        Javalin app = Javalin.create(config -> config.showJavalinBanner = false);

        app.get("/", ctx -> ctx.contentType(ContentType.TEXT_HTML).result(page()));
        app.get("/api/search", ctx -> { refresh(); ctx.json(search(ctx.queryParam("q"))); });
        app.get("/api/queries", ctx -> { refresh(); ctx.json(queries()); });
        app.get("/api/rules", ctx -> ctx.json(ruleList()));
        app.get("/api/timeline", ctx -> ctx.json(RunLog.currentRun(24)));
        app.post("/api/reindex", ctx -> ctx.json(reindex()));
        app.post("/api/approve", ctx -> ctx.json(
                approve(ctx.queryParam("from"), ctx.queryParam("to"))));
        app.get("/images/{file}", ctx -> {
            Path file = IMAGES.resolve(ctx.pathParam("file")).normalize();
            // A render is illustration, not data — but a path traversal is a path
            // traversal even when the payload is a picture of a chair.
            if (!file.startsWith(IMAGES) || !Files.exists(file)) {
                ctx.status(404);
                return;
            }
            ctx.contentType(ContentType.IMAGE_JPEG).result(Files.readAllBytes(file));
        });

        app.start(port);
        System.out.printf("ui          http://localhost:%d · %d products · %d rules%n",
                port, catalogue.size(), rules.size());
    }

    /**
     * Approving a rule changes the live store, and the "after" index was built from
     * it. One index per rule set, rebuilt deliberately — the store is shared by
     * reference so the new index sees the approval, and nothing caches a stale copy.
     */
    public synchronized Map<String, Object> reindex() {
        LuceneIndex stale = after;
        after = new LuceneIndex(catalogue.products(), rules);
        stale.close();
        return Map.of("rules", rules.size(), "reachable", after.reachableDocs());
    }

    /**
     * Approve a rule and rebuild. Approval goes through the gate first, even here:
     * the button is a human saying yes to a measured proposal, not a way around the
     * measurement. Step seven puts this behind Slack and a queue; the code path a
     * click reaches is this one.
     *
     * Approving in the same process as search is not a convenience. Two processes
     * means two SynonymStore instances and two caches — the applier writes into one,
     * search reads the other, and the board reports +0.0000 for a change that works.
     */
    public synchronized Map<String, Object> approve(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            return Map.of("ok", false, "reason", "from and to are both required");
        }
        RuleValidator validator = new RuleValidator(catalogue.products(), catalogue.queries(), rules);
        VocabularyGap stub = VocabularyGap.none("-", "-", 0, 0);
        Validation v = validator.validate(
                Proposal.of(stub, ProposedChange.synonym(from, to, "approved in the ui")));
        if (!v.passed()) {
            return Map.of("ok", false, "reason", v.reason());
        }
        rules.add(from, to);
        reindex();
        return Map.of("ok", true, "rule", from + " -> " + to,
                "overall", round(v.overall()), "after", round(v.after()));
    }

    /**
     * The worker approves rules in a different process. Before answering anything,
     * notice if the rule file moved underneath us and rebuild. Without this the UI
     * happily reports +0.0000 for an approval that has demonstrably landed.
     */
    private synchronized void refresh() {
        if (rules.reloadIfChanged()) {
            reindex();
            RunLog.record("reindexed", "ui picked up " + rules.size() + " rule(s) from disk");
        }
    }

    private Map<String, Object> search(String query) {
        if (query == null || query.isBlank()) {
            return Map.of("query", "", "before", List.of(), "after", List.of());
        }
        List<Hit> baseline = before.search(query, 10);
        List<Hit> candidate = after.search(query, 10);
        Set<String> baselineIds = new HashSet<>();
        baseline.forEach(h -> baselineIds.add(h.id()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("expansions", after.expansionsFor(query));
        out.put("before", baseline.stream().map(h -> view(h, false)).toList());
        out.put("after", candidate.stream().map(h -> view(h, !baselineIds.contains(h.id()))).toList());
        out.put("newCount", candidate.stream().filter(h -> !baselineIds.contains(h.id())).count());

        // Only a judged query has an answer key. Anything typed by hand is a real
        // search but an unscoreable one, and a number invented for it would be the
        // one dishonest thing on the page.
        JudgedQuery judged = judgedFor(query);
        out.put("ndcgBefore", judged == null ? null : round(Ndcg.at(10, ids(baseline), judged)));
        out.put("ndcgAfter", judged == null ? null : round(Ndcg.at(10, ids(candidate), judged)));
        return out;
    }

    private JudgedQuery judgedFor(String query) {
        return catalogue.queries().stream()
                .filter(q -> q.query().equalsIgnoreCase(query.trim()))
                .findFirst().orElse(null);
    }

    private static List<String> ids(List<Hit> hits) {
        return hits.stream().map(Hit::id).toList();
    }

    private static Map<String, Object> view(Hit hit, boolean isNew) {
        Product p = hit.product();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", hit.id());
        out.put("score", Math.round(hit.score() * 1000) / 1000.0);
        out.put("title", p == null ? hit.id() : p.title());
        out.put("category", p == null ? "" : p.category());
        out.put("image", p == null || !p.hasImage() ? null : "/images/" + p.image());
        out.put("isNew", isNew);
        return out;
    }

    /** The judged queries with both scores, so the board is visible without a console. */
    private List<Map<String, Object>> queries() {
        Scorecard baseline = runner.run("before", before);
        Scorecard candidate = runner.run("after", after);
        VocabularyGapFinder finder = new VocabularyGapFinder(before);

        List<Map<String, Object>> out = new ArrayList<>();
        for (JudgedQuery q : catalogue.queries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query", q.query());
            row.put("before", round(baseline.scoreOf(q.queryId())));
            row.put("after", round(candidate.scoreOf(q.queryId())));
            row.put("delta", round(candidate.scoreOf(q.queryId()) - baseline.scoreOf(q.queryId())));
            row.put("gap", finder.analyse(q, baseline.scoreOf(q.queryId())).summary());
            out.add(row);
        }
        out.add(Map.of("query", "mean", "before", round(baseline.mean()),
                "after", round(candidate.mean()), "delta", round(candidate.mean() - baseline.mean()),
                "gap", ""));
        return out;
    }

    private List<Map<String, String>> ruleList() {
        List<Map<String, String>> out = new ArrayList<>();
        rules.rules().forEach((from, targets) ->
                targets.forEach(to -> out.add(Map.of("from", from, "to", to))));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static String page() {
        try (var in = SearchApi.class.getResourceAsStream("/web/index.html")) {
            return new String(Objects.requireNonNull(in).readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException("could not read the UI page", e);
        }
    }

    public static int defaultPort() {
        return Config.getInt("UI_PORT", 7070);
    }
}
