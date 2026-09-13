package labs.augmentor.auditor.slack;

import labs.augmentor.auditor.model.*;

import java.util.*;

/**
 * Slack Block Kit payloads.
 *
 * The card has to carry the evidence, not just the verdict. A reviewer who sees
 * only "approve this rule?" is rubber-stamping; one who can see that "toddler"
 * appears in 2 titles, that the catalogue says "kids" in 23, and that the gate
 * measured +0.0306 with no query worse, is actually deciding something.
 */
public final class Blocks {

    public static final String APPROVE = "rule_approve";
    public static final String REJECT = "rule_reject";

    private Blocks() {}

    public static List<Map<String, Object>> proposal(Proposal proposal, Validation validation) {
        ProposedChange change = proposal.change();
        VocabularyGap gap = proposal.evidence();
        List<Map<String, Object>> blocks = new ArrayList<>();

        blocks.add(header("Vocabulary gap · " + proposal.query()));

        blocks.add(section("*`" + change.from() + "` → `" + change.to() + "`*\n"
                + (change.rationale() == null || change.rationale().isBlank()
                   ? "_no rationale given_" : change.rationale())));

        blocks.add(fields(
                "*Measured overall*\n" + signed(validation.overall()),
                "*Worst single query*\n" + signed(validation.worstQuery()),
                "*Mean NDCG@10*\n" + String.format("%.4f → %.4f", validation.before(), validation.after()),
                "*Products reached*\n" + validation.reachableDocs()));

        if (gap != null && gap.rarestTerm() != null && !gap.catalogueVocabulary().isEmpty()) {
            TermCount rare = gap.rarestTerm();
            TermCount best = gap.catalogueVocabulary().get(0);
            blocks.add(context(String.format(
                    "The shopper says *%s* — %d titles use it. The catalogue says *%s* — %d titles. "
                            + "That word reaches %d of the %d relevant products this query misses.",
                    rare.term(), rare.titles(), best.term(), best.titles(),
                    best.covers(), gap.relevantMissed())));
        }

        blocks.add(context("_Every candidate was measured on a throwaway index before this was posted. "
                + "Rules that lift the average but cost any single query were discarded and you never saw them._"));

        blocks.add(Map.of(
                "type", "actions",
                "block_id", "decision_" + proposal.id(),
                "elements", List.of(
                        button("Approve", APPROVE, proposal.id(), "primary"),
                        button("Reject", REJECT, proposal.id(), "danger"))));

        return blocks;
    }

    /** The card after a click: no buttons, and a record of who decided. */
    public static List<Map<String, Object>> decided(Proposal proposal, Validation validation,
                                                    Decision decision) {
        List<Map<String, Object>> blocks = new ArrayList<>(proposal(proposal, validation));
        blocks.removeIf(b -> "actions".equals(b.get("type")));
        blocks.add(context(String.format("%s *%s* by <@%s>",
                decision.isApproval() ? ":white_check_mark:" : ":no_entry:",
                decision.isApproval() ? "Approved" : "Rejected",
                decision.decidedBy())));
        return blocks;
    }

    /**
     * One flowing line rather than a stacked list: the arrows are the point. A
     * reader should see the sequence the work actually took, without reading it
     * as a checklist of things that have not happened.
     *
     * No per-stage timing. Nine emoji each trailed by a millisecond pill is a
     * wall of numbers nobody reads, and it buries the sequence it was meant to
     * evidence. The real timings are in the run log and the audit row.
     */
    public static String trail(List<Lifecycle.Step> steps) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            Lifecycle.Step step = steps.get(i);
            if (i > 0) out.append("  →  ");
            out.append(step.stage().emoji()).append(" *").append(step.stage().label()).append('*');
        }
        return out.toString();
    }

    /**
     * The approval card while the work is still running.
     *
     * The archive gets the finished trail, but the archive is not where anyone is
     * looking — the channel they just clicked in is. Repainting the card after
     * every stage is what makes the asynchrony visible: the queue, the re-index
     * and the pull request arrive one at a time, on the message they are watching.
     */
    public static List<Map<String, Object>> inFlight(Proposal proposal, Validation validation,
                                                     Decision decision,
                                                     List<Lifecycle.Step> steps) {
        List<Map<String, Object>> blocks = new ArrayList<>(decided(proposal, validation, decision));
        if (!steps.isEmpty()) blocks.add(context(trail(steps)));
        return blocks;
    }

    /**
     * The in-flight card when the stored proposal is not available.
     *
     * snapshot.sh clears data/proposals, so a decision still in flight across a
     * reset has no evidence to redraw. The trail is the part that has to keep
     * arriving; losing the evidence block is better than losing the sequence.
     */
    public static List<Map<String, Object>> inFlight(Decision decision, List<Lifecycle.Step> steps) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(section("*`" + decision.change().from() + "` → `" + decision.change().to() + "`*"));
        blocks.add(context(String.format("%s *%s* by <@%s>",
                decision.isApproval() ? ":white_check_mark:" : ":no_entry:",
                decision.isApproval() ? "Approved" : "Rejected",
                decision.decidedBy())));
        if (!steps.isEmpty()) blocks.add(context(trail(steps)));
        return blocks;
    }

    /**
     * The archive message: the whole lifecycle with real elapsed times, and the
     * measured result next to what the gate predicted.
     */
    public static List<Map<String, Object>> archive(Decision decision,
                                                    List<Lifecycle.Step> steps,
                                                    double actualOverall,
                                                    double actualMean,
                                                    String pullRequestUrl) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header((decision.isApproval() ? "Applied · " : "Rejected · ")
                + decision.change().from() + " → " + decision.change().to()));

        blocks.add(section("*Query*  `" + decision.query() + "`\n"
                + "*Decided by*  <@" + decision.decidedBy() + ">"));

        if (decision.isApproval()) {
            blocks.add(fields(
                    "*Predicted*\n" + signed(decision.predictedOverall()),
                    "*Measured after re-index*\n" + signed(actualOverall),
                    "*Mean NDCG@10*\n" + String.format("%.4f → %.4f",
                            decision.baselineMean(), actualMean),
                    "*Prediction held*\n" + (Math.abs(actualOverall - decision.predictedOverall()) < 0.0005
                            ? ":white_check_mark: exactly" : ":warning: see the sheet")));
        }

        if (!steps.isEmpty()) blocks.add(section(trail(steps)));

        if (pullRequestUrl != null && !pullRequestUrl.isBlank()) {
            blocks.add(context("<" + pullRequestUrl + "|The rule, as code>"));
        }
        return blocks;
    }

    // ---- primitives ---------------------------------------------------------

    public static Map<String, Object> header(String text) {
        return Map.of("type", "header",
                "text", Map.of("type", "plain_text", "text", truncate(text, 150), "emoji", true));
    }

    public static Map<String, Object> section(String markdown) {
        return Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", markdown));
    }

    public static Map<String, Object> context(String markdown) {
        return Map.of("type", "context",
                "elements", List.of(Map.of("type", "mrkdwn", "text", markdown)));
    }

    public static Map<String, Object> fields(String... markdown) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String m : markdown) fields.add(Map.of("type", "mrkdwn", "text", m));
        return Map.of("type", "section", "fields", fields);
    }

    private static Map<String, Object> button(String label, String actionId, String value, String style) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("action_id", actionId);
        button.put("value", value);
        button.put("text", Map.of("type", "plain_text", "text", label, "emoji", true));
        button.put("style", style);
        return button;
    }

    public static String signed(double value) {
        return String.format("%+.4f", value);
    }


    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
