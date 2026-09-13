package labs.augmentor.auditor.slack;

/**
 * The lifecycle a decision passes through, rendered as a trail on the archive
 * message. Each stage is stamped with the real elapsed time, so what the audience
 * sees is what happened rather than a picture of what usually happens.
 */
public enum Stage {

    PROPOSED(":mag:", "measured and proposed"),
    APPROVED(":white_check_mark:", "approved"),
    QUEUED(":inbox_tray:", "queued"),
    APPLIED(":pencil2:", "rule applied"),
    REINDEXED(":arrows_counterclockwise:", "re-indexed"),
    VERIFIED(":bar_chart:", "re-evaluated"),
    RECORDED(":ledger:", "written to the sheet"),
    // :octocat: is a GitHub emoji, not a Slack one — it renders as literal text
    // in the channel. Every emoji here has to exist in Slack's standard set.
    PUBLISHED(":twisted_rightwards_arrows:", "pull request opened"),
    ARCHIVED(":package:", "archived");

    private final String emoji;
    private final String label;

    Stage(String emoji, String label) {
        this.emoji = emoji;
        this.label = label;
    }

    public String emoji() {
        return emoji;
    }

    public String label() {
        return label;
    }
}
