package labs.augmentor.auditor;

import labs.augmentor.auditor.audit.ClaudeSynonymProposer;
import labs.augmentor.auditor.audit.RuleValidator;
import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.audit.VocabularyGapFinder;
import labs.augmentor.auditor.eval.EvalRunner;
import labs.augmentor.auditor.eval.Scorecard;
import labs.augmentor.auditor.ingest.Catalogues;
import labs.augmentor.auditor.model.*;
import labs.augmentor.auditor.retrieve.LuceneIndex;
import labs.augmentor.auditor.audit.AuditLog;
import labs.augmentor.auditor.audit.InMemoryAuditLog;
import labs.augmentor.auditor.audit.SheetsAuditLog;
import labs.augmentor.auditor.publish.RulePublisher;
import labs.augmentor.auditor.slack.Approvals;
import labs.augmentor.auditor.slack.HttpSlackApi;
import labs.augmentor.auditor.slack.SlackApi;
import labs.augmentor.auditor.slack.SocketModeClient;
import labs.augmentor.auditor.web.SearchApi;
import labs.augmentor.auditor.worker.DecisionPublisher;
import labs.augmentor.auditor.worker.DecisionWorker;
import labs.augmentor.auditor.worker.Idempotency;
import labs.augmentor.auditor.worker.PidLock;
import labs.augmentor.auditor.worker.SqsConsumer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class App {

    /** How many failing queries one unprompted run will spend model calls on. */
    private static final int MAX_QUERIES_PER_RUN = 4;


    public static void main(String[] args) {
        String command = args.length == 0 ? "help" : args[0];
        String rest = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        try {
            switch (command) {
                case "eval" -> eval();
                case "compare" -> compare(rest);
                case "sweep" -> sweep(rest);
                case "gap" -> gap(rest);
                case "validate" -> validate(rest);
                case "audit" -> audit();
                case "apply" -> apply(rest);
                case "rules" -> rules();
                case "ui" -> new SearchApi().start(SearchApi.defaultPort());
                case "doctor" -> { if (!new Doctor().run()) System.exit(1); }
                case "propose" -> propose();
                case "socket" -> socket();
                case "worker" -> worker();
                case "timeline" -> timeline();
                case "decide" -> decide(rest);
                case "pending" -> pending();
                case "sheet" -> sheet();
                case "help" -> help();
                default -> {
                    System.err.println("unknown command: " + command);
                    help();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("\n" + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    // ---- step 1-3: a number on the board ------------------------------------

    private static void eval() {
        Catalogues.Catalogue catalogue = Catalogues.active();
        SynonymStore synonyms = SynonymStore.shared();
        try (LuceneIndex index = new LuceneIndex(catalogue.products(), synonyms)) {
            System.out.printf("catalogue %s · %d products · %d judged queries · %d rules · %d docs reachable%n%n",
                    catalogue.name(), catalogue.size(), catalogue.queries().size(),
                    synonyms.size(), index.reachableDocs());
            System.out.println(EvalRunner.render(new EvalRunner(catalogue.queries()).run("current index", index)));
        }
    }

    /** Two indexes, same query: what the shopper sees now, and what a rule would change. */
    private static void compare(String arg) {
        String[] parts = arg.split("\\|");
        String query = parts[0].trim();
        String from = parts.length > 1 ? parts[1].trim() : null;
        String to = parts.length > 2 ? parts[2].trim() : null;

        Catalogues.Catalogue catalogue = Catalogues.active();
        SynonymStore base = SynonymStore.shared();
        SynonymStore candidate = from == null ? base : base.plus(from, to);

        try (LuceneIndex a = new LuceneIndex(catalogue.products(), base);
             LuceneIndex b = new LuceneIndex(catalogue.products(), candidate)) {
            System.out.printf("%s%n%nreachable docs  baseline %d · candidate %d%n%n",
                    query, a.reachableDocs(), b.reachableDocs());
            print("baseline", a.search(query, 10), List.of());
            if (from != null) {
                List<String> before = a.search(query, 10).stream().map(Hit::id).toList();
                print("with " + from + " -> " + to, b.search(query, 10), before);
            }
        }
    }

    /**
     * How much a rule should be allowed to count for. Swept against the judged
     * queries rather than chosen, because the right answer is not obvious and a
     * wrong one is invisible.
     */
    private static void sweep(String arg) {
        String[] parts = arg.split("\\|");
        String from = parts[0].trim();
        String to = parts[1].trim();

        Catalogues.Catalogue catalogue = Catalogues.active();
        SynonymStore base = SynonymStore.shared();
        SynonymStore candidate = base.plus(from, to);
        EvalRunner runner = new EvalRunner(catalogue.queries());

        Scorecard baseline;
        try (LuceneIndex index = new LuceneIndex(catalogue.products(), base)) {
            baseline = runner.run("baseline", index);
        }
        System.out.printf("%s -> %s · baseline mean %.4f%n%n", from, to, baseline.mean());
        System.out.printf("%8s %10s %13s  %s%n", "weight", "mean", "worst query", "the query it costs most");
        System.out.println("-".repeat(70));
        for (float w : new float[]{0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 1.5f, 2.0f, 3.0f}) {
            try (LuceneIndex index = new LuceneIndex(catalogue.products(), candidate, w)) {
                Scorecard.Delta delta = runner.run("w=" + w, index).against(baseline);
                System.out.printf("%8.1f %10.4f %+13.4f  %s%n",
                        w, baseline.mean() + delta.overall(), delta.worstQuery(), delta.worstQueryText());
            }
        }
    }

    // ---- step 5: the diagnosis, no model involved ---------------------------

    private static void gap(String arg) {
        Catalogues.Catalogue catalogue = Catalogues.active();
        try (LuceneIndex index = new LuceneIndex(catalogue.products(), SynonymStore.shared())) {
            Scorecard card = new EvalRunner(catalogue.queries()).run("current", index);
            VocabularyGapFinder finder = new VocabularyGapFinder(index);
            List<JudgedQuery> worst = finder.worstFirst(catalogue.queries(), card);

            if (!arg.isBlank()) {
                JudgedQuery target = worst.stream()
                        .filter(q -> q.query().equalsIgnoreCase(arg.trim()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("no judged query: " + arg));
                printGap(finder.analyse(target, card.scoreOf(target.queryId())));
                return;
            }
            for (JudgedQuery q : worst) {
                VocabularyGap g = finder.analyse(q, card.scoreOf(q.queryId()));
                System.out.printf("%.4f  %-26s %s%n", g.score(), g.query(),
                        g.hasAddressableGap() ? g.summary() : "no addressable gap");
            }
        }
    }

    private static void printGap(VocabularyGap gap) {
        System.out.printf("%s   NDCG@10 %.4f   %d relevant products missed%n%n",
                gap.query(), gap.score(), gap.relevantMissed());
        System.out.println("the query's own words, and how many titles use them");
        gap.queryTerms().forEach(t -> System.out.printf("    %-16s %4d titles%n", t.term(), t.titles()));
        System.out.println("\nwhat the catalogue says for the products it missed");
        System.out.printf("    %-16s %6s %8s  %s%n", "", "titles", "lift", "reaches");
        gap.catalogueVocabulary().forEach(t ->
                System.out.printf("    %-16s %6d %7.1fx  %d of %d missed%n",
                        t.term(), t.titles(), t.lift(), t.covers(), gap.relevantMissed()));
        System.out.println("\nrelevant products it missed");
        gap.missedTitles().forEach(t -> System.out.println("    " + t));
    }

    // ---- step 6: the gate ---------------------------------------------------

    private static void validate(String arg) {
        String[] parts = arg.split("\\|");
        Catalogues.Catalogue catalogue = Catalogues.active();
        RuleValidator validator = new RuleValidator(
                catalogue.products(), catalogue.queries(), SynonymStore.shared());

        ProposedChange change = ProposedChange.synonym(parts[0].trim(), parts[1].trim(), "manual");
        VocabularyGap stub = VocabularyGap.none("-", "-", 0, 0);
        printVerdict(validator, validator.validate(Proposal.of(stub, change)), change);
    }

    /**
     * The whole loop, unprompted: evaluate, diagnose, propose, measure.
     *
     * It walks the judged queries worst first and stops at the first rule that
     * passes the gate. Walking rather than picking the single worst query matters:
     * "teal chair" scores 0.0444 and is the obvious target, and there is nothing
     * there to find — Wayfair sells the same sofa in twelve colours, so the copy is
     * deliberately colour-agnostic and the colour lives in a variant picker, not in
     * prose. Everything the agent tries and discards is printed, because a run that
     * silently proposed nothing and a run whose API calls all failed look identical
     * otherwise.
     */
    private static void audit() {
        Catalogues.Catalogue catalogue = Catalogues.active();
        SynonymStore synonyms = SynonymStore.shared();
        ClaudeSynonymProposer proposer = new ClaudeSynonymProposer();

        Scorecard card;
        List<VocabularyGap> gaps;
        try (LuceneIndex index = new LuceneIndex(catalogue.products(), synonyms)) {
            card = new EvalRunner(catalogue.queries()).run("current", index);
            VocabularyGapFinder finder = new VocabularyGapFinder(index);
            gaps = finder.worstFirst(catalogue.queries(), card).stream()
                    .map(q -> finder.analyse(q, card.scoreOf(q.queryId())))
                    .filter(VocabularyGap::hasAddressableGap)
                    .limit(MAX_QUERIES_PER_RUN)
                    .toList();
        }
        System.out.printf("evaluate    %d queries · mean NDCG@10 %.4f%n", card.size(), card.mean());
        System.out.printf("diagnose    %d queries with an addressable vocabulary gap%n%n", gaps.size());

        RuleValidator validator = new RuleValidator(catalogue.products(), catalogue.queries(), synonyms);
        int proposed = 0, rejected = 0, declined = 0;

        for (VocabularyGap gap : gaps) {
            System.out.printf("\"%s\" at %.4f%n    %s%n", gap.query(), gap.score(), gap.summary());
            List<ProposedChange> candidates = proposer.propose(gap);
            if (candidates.isEmpty()) {
                declined++;
                System.out.printf("    %-22s Claude declined — no word here means what the shopper meant%n%n", "");
                continue;
            }
            for (ProposedChange change : candidates) {
                Validation v = validator.validate(Proposal.of(gap, change));
                printVerdict(validator, v, change);
                if (v.passed()) proposed++; else rejected++;
            }
            if (proposed > 0) break;
        }

        System.out.printf("measure     %d proposed · %d rejected by the gate · %d declined by the model%n",
                proposed, rejected, declined);
        System.out.println(proposed == 0
                ? "            nothing reaches a human"
                : "            approve with:  ./gradlew run --args=\"apply <from>|<to>\"");
    }

    private static void printVerdict(RuleValidator validator, Validation v, ProposedChange change) {
        System.out.printf("  %-22s %-10s %s%n", change.arrow(), v.status(), v.reason());
        System.out.printf("  %-22s mean %.4f -> %.4f   reaches %d products%n%n",
                "", v.before(), v.after(), v.reachableDocs());
        if (v.passed()) {
            System.out.println(EvalRunner.renderDelta(
                    validator.baseline(), validator.scoreWith(change.from(), change.to())));
        }
    }

    // ---- the human in the loop ---------------------------------------------

    /**
     * Run the audit and put whatever survives the gate in front of a person.
     * Rejected candidates are printed here and go no further — nobody is asked to
     * review a rule that was already measured and found wanting.
     */
    private static void propose() {
        Catalogues.Catalogue catalogue = Catalogues.active();
        SynonymStore synonyms = SynonymStore.shared();

        Scorecard card;
        List<VocabularyGap> gaps;
        try (LuceneIndex index = new LuceneIndex(catalogue.products(), synonyms)) {
            card = new EvalRunner(catalogue.queries()).run("current", index);
            VocabularyGapFinder finder = new VocabularyGapFinder(index);
            gaps = finder.worstFirst(catalogue.queries(), card).stream()
                    .map(q -> finder.analyse(q, card.scoreOf(q.queryId())))
                    .filter(VocabularyGap::hasAddressableGap)
                    .limit(MAX_QUERIES_PER_RUN)
                    .toList();
        }
        RunLog.record("evaluate", String.format("%d queries · mean NDCG@10 %.4f",
                card.size(), card.mean()), Map.of("mean", card.mean()));
        System.out.printf("evaluate    %d queries · mean NDCG@10 %.4f%n", card.size(), card.mean());

        ClaudeSynonymProposer proposer = new ClaudeSynonymProposer();
        RuleValidator validator = new RuleValidator(catalogue.products(), catalogue.queries(), synonyms);

        try (DecisionPublisher publisher = new DecisionPublisher()) {
            Approvals approvals = new Approvals(new HttpSlackApi(), publisher);
            for (VocabularyGap gap : gaps) {
                RunLog.record("diagnose", gap.query() + " — " + gap.summary());
                System.out.printf("%ndiagnose    \"%s\" at %.4f%n            %s%n",
                        gap.query(), gap.score(), gap.summary());
                for (ProposedChange change : proposer.propose(gap)) {
                    Proposal proposal = Proposal.of(gap, change);
                    Validation v = validator.validate(proposal);
                    System.out.printf("  %-22s %-10s %s%n", change.arrow(), v.status(), v.reason());
                    if (!v.passed()) {
                        RunLog.record("measure", "rejected " + change.arrow() + " — " + v.reason());
                        continue;
                    }
                    String ts = approvals.post(proposal, v);
                    System.out.printf("  %-22s posted to %s (ts %s)%n%n", "", approvals.channel(), ts);
                    return;
                }
            }
        }
        System.out.println("\nnothing survived the gate — nothing was posted");
    }

    /** Receives button clicks and puts decisions on the queue. One process. */
    private static void socket() {
        try (PidLock lock = PidLock.acquire("socket");
             DecisionPublisher publisher = new DecisionPublisher();
             SocketModeClient client = new SocketModeClient()) {
            Approvals approvals = new Approvals(new HttpSlackApi(), publisher);
            System.out.println("socket      listening for approvals · ctrl-c to stop");
            Runtime.getRuntime().addShutdownHook(new Thread(client::close));
            client.listen(interaction -> approvals.onInteraction(interaction)
                    .ifPresent(d -> System.out.printf("  %s %s by %s%n",
                            d.action(), d.change().arrow(), d.decidedBy())));
        }
    }

    /** Applies decisions off the queue. One process — two would split the queue. */
    private static void worker() {
        try (PidLock lock = PidLock.acquire("worker");
             SqsConsumer consumer = new SqsConsumer()) {
            Catalogues.Catalogue catalogue = Catalogues.active();
            SlackApi slack = new HttpSlackApi();
            AuditLog auditLog = sheetsOrMemory();
            DecisionWorker worker = new DecisionWorker(catalogue, SynonymStore.shared(),
                    auditLog, slack, new RulePublisher(), new Idempotency());

            System.out.println("worker      polling the decision queue · ctrl-c to stop");
            Runtime.getRuntime().addShutdownHook(new Thread(consumer::close));
            consumer.poll(decision -> {
                System.out.printf("  %s %s%n", decision.action(), decision.change().arrow());
                DecisionWorker.Result result = worker.handle(decision);
                System.out.printf("  %-10s measured %+.4f · mean %.4f%s%n", "",
                        result.actualOverall(), result.meanAfter(),
                        result.pullRequest() == null ? "" : " · " + result.pullRequest());
            });
        }
    }

    /**
     * Decide a pending proposal from the command line.
     *
     * It goes through the same {@link Approvals#onInteraction} the socket handler
     * calls, with the same payload shape, so everything downstream — the card
     * update, the decision on SNS, the worker, the sheet, the pull request — is the
     * real path. What it does not exercise is Slack's delivery of the click, which
     * is what the socket process is for.
     *
     * It exists because a demo needs a way to drive the loop when the room's wifi
     * will not carry a WebSocket, and because reaching for a real button every time
     * you want to test the worker is a poor way to spend a hackathon.
     */
    private static void decide(String arg) {
        String[] parts = arg.split("\\|");
        String proposalId = parts[0].trim();
        boolean approve = parts.length < 2 || !parts[1].trim().equalsIgnoreCase("reject");

        Proposals.Pending found = Proposals.load(proposalId).orElseThrow(() ->
                new IllegalArgumentException("no pending proposal " + proposalId
                        + " — run `pending` to see what is waiting"));

        try (DecisionPublisher publisher = new DecisionPublisher()) {
            Approvals approvals = new Approvals(new HttpSlackApi(), publisher);
            approvals.onInteraction(new SocketModeClient.Interaction(
                            approve ? labs.augmentor.auditor.slack.Blocks.APPROVE
                                    : labs.augmentor.auditor.slack.Blocks.REJECT,
                            proposalId, Config.get("DEMO_USER", "console"),
                            found.channel(), found.messageTs()))
                    .ifPresentOrElse(
                            d -> System.out.printf("%s %s · queued as %s%n",
                                    d.action(), d.change().arrow(), d.id()),
                            () -> System.out.println("nothing to decide"));
        }
    }

    private static void pending() {
        List<Proposals.Pending> all = Proposals.all();
        if (all.isEmpty()) {
            System.out.println("nothing waiting on a human");
            return;
        }
        for (Proposals.Pending p : all) {
            System.out.printf("  %-10s %-22s %+.4f   ts %s%n",
                    p.proposal().id(), p.proposal().change().arrow(),
                    p.validation().overall(), p.messageTs());
        }
        System.out.println("\n  ./gradlew run --args=\"decide <id>|approve\"");
    }

    private static void sheet() {
        SheetsAuditLog sheets = new SheetsAuditLog();
        System.out.printf("\"%s\"%n%n", sheets.title());
        List<List<Object>> rows = sheets.rows();
        if (rows.isEmpty()) {
            System.out.println("no rows yet");
            return;
        }
        List<Object> headers = rows.get(0);
        for (int r = 1; r < rows.size(); r++) {
            System.out.println("row " + r);
            List<Object> row = rows.get(r);
            for (int c = 0; c < headers.size() && c < row.size(); c++) {
                System.out.printf("  %-18s %s%n", headers.get(c), row.get(c));
            }
            System.out.println();
        }
    }

    private static AuditLog sheetsOrMemory() {
        try {
            SheetsAuditLog sheets = new SheetsAuditLog();
            sheets.ensureHeaders();
            return sheets;
        } catch (RuntimeException e) {
            // A sheet that will not open must not stop a rule being applied. Say so
            // loudly and carry on; the row is a record, not a gate.
            System.err.println("sheets unavailable, recording in memory only: " + e.getMessage());
            return new InMemoryAuditLog();
        }
    }

    private static void timeline() {
        List<RunLog.Entry> entries = RunLog.recent(30);
        if (entries.isEmpty()) {
            System.out.println("nothing in the run log yet");
            return;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            RunLog.Entry e = entries.get(i);
            System.out.printf("  %-12s %-58s %s%n", e.stage(), e.message(), e.at());
        }
    }

    // ---- applying an approved rule locally ---------------------------------

    private static void apply(String arg) {
        String[] parts = arg.split("\\|");
        SynonymStore.shared().add(parts[0].trim(), parts[1].trim());
        System.out.printf("applied %s -> %s%n%n", parts[0].trim(), parts[1].trim());
        eval();
    }

    private static void rules() {
        SynonymStore synonyms = SynonymStore.shared();
        if (synonyms.isEmpty()) {
            System.out.println("no approved rules");
            return;
        }
        synonyms.rules().forEach((from, targets) ->
                targets.forEach(to -> System.out.printf("  %s -> %s%n", from, to)));
    }

    private static void print(String label, List<Hit> hits, List<String> before) {
        System.out.println(label);
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            String mark = before.isEmpty() || before.contains(h.id()) ? " " : "*";
            System.out.printf("  %2d %s %6.3f  %-8s %s%n", i + 1, mark, h.score(), h.id(),
                    h.product() == null ? "?" : h.product().title());
        }
        if (!before.isEmpty()) {
            long fresh = hits.stream().map(Hit::id).filter(id -> !before.contains(id)).count();
            System.out.printf("     %d of %d results are new%n", fresh, hits.size());
        }
        System.out.println();
    }

    private static void help() {
        System.out.println("""
            relevance-auditor

              eval                           score the judged queries
              gap [query]                    the vocabulary diagnosis, no model involved
              audit                          evaluate, diagnose, propose, measure
              validate "<from>|<to>"         put one rule through the gate
              compare  "<query>|<from>|<to>" top 10 with and without a rule
              sweep    "<from>|<to>"         NDCG across rule weights
              apply    "<from>|<to>"         approve a rule and re-score
              rules                          what is approved
              ui                             http://localhost:7070

              doctor                         check every credential and resource
              propose                        audit, then post what survives to Slack
              socket                         receive approvals, queue decisions
              worker                         apply decisions off the queue
              timeline                       what the pipeline has done
              sheet                          the audit rows, as written
              pending                        proposals waiting on a human
              decide  "<id>|approve|reject"  decide one without reaching for Slack
            """);
    }
}
