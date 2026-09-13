package labs.augmentor.auditor.worker;

import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.Proposals;
import labs.augmentor.auditor.RunLog;
import labs.augmentor.auditor.audit.AuditLog;
import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.eval.EvalRunner;
import labs.augmentor.auditor.eval.Scorecard;
import labs.augmentor.auditor.ingest.Catalogues;
import labs.augmentor.auditor.model.*;
import labs.augmentor.auditor.publish.RulePublisher;
import labs.augmentor.auditor.retrieve.LuceneIndex;
import labs.augmentor.auditor.slack.Blocks;
import labs.augmentor.auditor.slack.Lifecycle;
import labs.augmentor.auditor.slack.SlackApi;
import labs.augmentor.auditor.slack.Stage;

import java.time.Instant;
import java.util.Map;
import java.util.List;

/**
 * What happens after a human clicks Approve.
 *
 * Apply the rule, rebuild the index, re-score every judged query, and check the
 * result against what the gate predicted. Then write the row, open the pull
 * request, and post the trail. The measurement after the fact is the part that
 * matters: a proposal that was measured on a throwaway index is a promise, and
 * this is where the promise is checked against a real rebuild.
 */
public final class DecisionWorker {

    private final Catalogues.Catalogue catalogue;
    private final SynonymStore rules;
    private final EvalRunner runner;
    private final AuditLog auditLog;
    private final SlackApi slack;
    private final RulePublisher publisher;
    private final Idempotency idempotency;
    private Proposals.Pending pending;                // held so a repaint is not a disk read
    private final String archiveChannel;
    private final int archiveDeleteDelay;

    /** Production: the archive channel and the delete delay come from .env. */
    public DecisionWorker(Catalogues.Catalogue catalogue, SynonymStore rules, AuditLog auditLog,
                          SlackApi slack, RulePublisher publisher, Idempotency idempotency) {
        this(catalogue, rules, auditLog, slack, publisher, idempotency,
                Config.get("SLACK_ARCHIVE_CHANNEL", null),
                Config.getInt("ARCHIVE_DELETE_DELAY_SECONDS", -1));
    }

    /**
     * Both archive settings stated rather than read from the environment.
     *
     * A test that asserts on the delete delay by reading .env is a test that
     * passes or fails on whatever the developer last set for a demo — and fails
     * outright on a clean clone, where there is no .env at all. The behaviour
     * under each setting is the thing worth testing, so the setting is an
     * argument.
     */
    public DecisionWorker(Catalogues.Catalogue catalogue, SynonymStore rules, AuditLog auditLog,
                          SlackApi slack, RulePublisher publisher, Idempotency idempotency,
                          String archiveChannel, int archiveDeleteDelay) {
        this.catalogue = catalogue;
        this.rules = rules;
        this.runner = new EvalRunner(catalogue.queries());
        this.auditLog = auditLog;
        this.slack = slack;
        this.publisher = publisher;
        this.idempotency = idempotency;
        this.archiveChannel = archiveChannel;
        this.archiveDeleteDelay = archiveDeleteDelay;
    }

    public record Result(boolean applied, double actualOverall, double meanAfter, String pullRequest) {}

    public Result handle(Decision decision) {
        if (!idempotency.isNew(decision.dedupeKey())) {
            RunLog.record("worker", "already handled " + decision.dedupeKey() + ", ignoring redelivery");
            return new Result(false, 0, 0, null);
        }

        Lifecycle lifecycle = new Lifecycle().onMark(steps -> repaintCard(decision, steps));
        lifecycle.mark(Stage.APPROVED, "by " + decision.decidedBy());
        lifecycle.mark(Stage.QUEUED, "via SNS → SQS");

        // The state as it was, measured now rather than trusted from the proposal.
        Scorecard before = score();

        boolean applied = false;
        if (decision.isApproval()) {
            // The same store instance the evaluation below reads from. Two stores
            // means two caches: the rule lands in one, the index is built from the
            // other, and the worker reports +0.0000 for a change that works — then
            // writes that zero into the audit row.
            rules.add(decision.change().from(), decision.change().to());
            applied = true;
            lifecycle.mark(Stage.APPLIED, decision.change().arrow());
        }

        Scorecard after = score();
        lifecycle.mark(Stage.REINDEXED, catalogue.size() + " products");

        double actualOverall = after.mean() - before.mean();
        lifecycle.mark(Stage.VERIFIED, String.format("%+.4f measured, %+.4f predicted",
                actualOverall, decision.predictedOverall()));

        String pullRequest = null;
        if (applied && publisher != null) {
            try {
                pullRequest = publisher.publish(decision, rules, actualOverall, after.mean());
                lifecycle.mark(Stage.PUBLISHED, pullRequest);
            } catch (RuntimeException e) {
                RunLog.record("published", "pull request failed: " + e.getMessage());
            }
        }

        if (auditLog != null) {
            try {
                auditLog.append(record(decision, before, after, actualOverall, pullRequest));
                lifecycle.mark(Stage.RECORDED);
            } catch (RuntimeException e) {
                RunLog.record("recorded", "sheet append failed: " + e.getMessage());
            }
        }

        archive(decision, lifecycle, actualOverall, after.mean(), pullRequest);
        idempotency.remember(decision.dedupeKey());
        return new Result(applied, actualOverall, after.mean(), pullRequest);
    }

    private Scorecard score() {
        try (LuceneIndex index = new LuceneIndex(catalogue.products(), rules)) {
            return runner.run("worker", index);
        }
    }

    private AuditRecord record(Decision decision, Scorecard before, Scorecard after,
                               double actualOverall, String pullRequest) {
        int reachable;
        try (LuceneIndex index = new LuceneIndex(catalogue.products(), rules)) {
            reachable = index.reachableDocs();
        }
        return new AuditRecord(
                Instant.now(), decision.proposalId(), decision.id(), decision.query(),
                decision.change().from(), decision.change().to(),
                decision.action().name(), decision.decidedBy(),
                decision.predictedOverall(), actualOverall, decision.predictedWorst(),
                before.mean(), after.mean(), reachable,
                decision.messageTs(), pullRequest, decision.change().rationale());
    }

    private void archive(Decision decision, Lifecycle lifecycle,
                         double actualOverall, double meanAfter, String pullRequest) {
        if (slack == null || archiveChannel == null) return;
        try {
            lifecycle.mark(Stage.ARCHIVED);
            slack.post(archiveChannel,
                    decision.change().arrow() + " " + Blocks.signed(actualOverall),
                    Blocks.archive(decision, lifecycle.steps(), actualOverall, meanAfter, pullRequest));

            // -1 keeps the approval card so the whole lifecycle stays visible on
            // screen. 0 deletes it the instant the work completes, and the trail
            // then survives only in the archive channel.
            if (archiveDeleteDelay >= 0 && decision.messageTs() != null) {
                if (archiveDeleteDelay > 0) Thread.sleep(archiveDeleteDelay * 1000L);
                slack.delete(decision.channel(), decision.messageTs());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            RunLog.record("archived", "archive post failed: " + e.getMessage());
        }
    }

    /**
     * Repaint the approval card with the trail so far.
     *
     * The proposal is read once and held, because this runs after every stage and
     * the disk read would otherwise happen nine times per decision. Failures are
     * swallowed by the Lifecycle listener: a cosmetic update must not be able to
     * fail a decision that applied correctly.
     */
    private void repaintCard(Decision decision, List<Lifecycle.Step> steps) {
        if (slack == null || decision.messageTs() == null) return;
        if (pending == null) pending = Proposals.load(decision.proposalId()).orElse(null);
        slack.update(decision.channel(), decision.messageTs(),
                decision.change().arrow(),
                pending == null
                        ? Blocks.inFlight(decision, steps)
                        : Blocks.inFlight(pending.proposal(), pending.validation(), decision, steps));
    }

    /** Update the card that was clicked, so it stops offering a decision already made. */
    public void settleCard(Decision decision) {
        if (slack == null || decision.messageTs() == null) return;
        Proposals.load(decision.proposalId()).ifPresent(pending -> {
            try {
                slack.update(decision.channel(), decision.messageTs(),
                        decision.change().arrow(),
                        Blocks.decided(pending.proposal(), pending.validation(), decision));
                slack.react(decision.channel(), decision.messageTs(),
                        decision.isApproval() ? "white_check_mark" : "no_entry");
            } catch (RuntimeException e) {
                RunLog.record("approved", "could not settle the card: " + e.getMessage(),
                        Map.of("ts", decision.messageTs()));
            }
        });
    }
}
