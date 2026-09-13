package labs.augmentor.auditor.slack;

import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.Proposals;
import labs.augmentor.auditor.RunLog;
import labs.augmentor.auditor.model.Decision;
import labs.augmentor.auditor.model.Proposal;
import labs.augmentor.auditor.model.Validation;
import labs.augmentor.auditor.worker.DecisionPublisher;

import java.util.Map;
import java.util.Optional;

/**
 * The human in the loop.
 *
 * Posting a card and handling the click are the same concern, because the click
 * has to find the proposal that was actually measured — which means the card's
 * message ts and the proposal id have to be written down together at the moment
 * the card goes out.
 *
 * The click itself does almost nothing: look the proposal up, build a decision,
 * put it on the queue, mark the card. Slack allows three seconds; the work that
 * follows takes longer than that, so it happens somewhere else.
 */
public final class Approvals {

    private final SlackApi slack;
    private final DecisionPublisher publisher;
    private final String approvalsChannel;

    public Approvals(SlackApi slack, DecisionPublisher publisher) {
        this.slack = slack;
        this.publisher = publisher;
        this.approvalsChannel = Config.require("SLACK_APPROVALS_CHANNEL");
    }

    /** Post a measured proposal for approval. Returns the message ts. */
    public String post(Proposal proposal, Validation validation) {
        if (!validation.passed()) {
            throw new IllegalArgumentException(
                    "only proposals that passed the gate are posted; this one was "
                            + validation.status() + " — " + validation.reason());
        }
        String ts = slack.post(approvalsChannel,
                "Vocabulary rule proposed: " + proposal.change().arrow(),
                Blocks.proposal(proposal, validation));

        Proposals.save(new Proposals.Pending(proposal, validation, approvalsChannel, ts));
        RunLog.record("proposed", "posted " + proposal.change().arrow() + " for approval",
                Map.of("ts", ts, "overall", validation.overall(), "proposal", proposal.id()));
        return ts;
    }

    /** A button click. Returns the decision if one was made. */
    public Optional<Decision> onInteraction(SocketModeClient.Interaction interaction) {
        boolean approve = Blocks.APPROVE.equals(interaction.actionId());
        boolean reject = Blocks.REJECT.equals(interaction.actionId());
        if (!approve && !reject) return Optional.empty();

        Optional<Proposals.Pending> pending = Proposals.load(interaction.value());
        if (pending.isEmpty()) {
            RunLog.record("approved", "click for an unknown proposal " + interaction.value());
            return Optional.empty();
        }

        Proposals.Pending found = pending.get();
        Decision decision = Decision.of(found.proposal(), found.validation(),
                approve ? Decision.Action.APPROVE : Decision.Action.REJECT,
                interaction.userId(),
                interaction.channel().isBlank() ? found.channel() : interaction.channel(),
                interaction.messageTs().isBlank() ? found.messageTs() : interaction.messageTs());

        // Mark the card before queueing, so it stops offering a decision that has
        // already been made even if the queue is slow.
        try {
            slack.update(decision.channel(), decision.messageTs(),
                    decision.change().arrow(),
                    Blocks.decided(found.proposal(), found.validation(), decision));
            slack.react(decision.channel(), decision.messageTs(),
                    approve ? "white_check_mark" : "no_entry");
        } catch (RuntimeException e) {
            RunLog.record("approved", "could not settle the card: " + e.getMessage());
        }

        publisher.publish(decision);
        RunLog.record("approved", decision.action() + " " + decision.change().arrow()
                + " by " + decision.decidedBy(), Map.of("decision", decision.id()));
        return Optional.of(decision);
    }

    public String channel() {
        return approvalsChannel;
    }
}
