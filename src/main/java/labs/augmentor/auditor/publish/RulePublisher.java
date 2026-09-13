package labs.augmentor.auditor.publish;

import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.Json;
import labs.augmentor.auditor.RunLog;
import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.model.Decision;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opens a pull request carrying the approved rule.
 *
 * The point is that an approval is not a setting changed in a running process that
 * nobody can review later. It is a diff, on a branch, with the measurement in the
 * description — reviewable, revertable, and attributable to the person who clicked
 * the button.
 */
public final class RulePublisher {

    private static final String PATH = "rules/synonyms.json";

    private final String repo;
    private final String base;

    public RulePublisher() {
        this(Config.require("GITHUB_REPO"), Config.get("GITHUB_BASE", "main"));
    }

    public RulePublisher(String repo, String base) {
        this.repo = repo;
        this.base = base;
    }

    /** Returns the pull request URL. */
    public String publish(Decision decision, SynonymStore rules, double actualOverall, double meanAfter) {
        String branch = "rule/" + decision.change().from() + "-to-" + decision.change().to()
                + "-" + decision.proposalId();

        String baseSha = Gh.require("api", "repos/" + repo + "/git/ref/heads/" + base,
                "--jq", ".object.sha").stdout();

        // A re-run of the same demo hits an existing branch. Reuse it rather than
        // failing: the second push just updates the same pull request.
        Gh.Result created = Gh.run("api", "repos/" + repo + "/git/refs",
                "--method", "POST",
                "-f", "ref=refs/heads/" + branch,
                "-f", "sha=" + baseSha);
        if (!created.ok() && !created.stderr().contains("Reference already exists")) {
            throw new IllegalStateException("could not create branch: " + created.stderr());
        }

        String content = Json.writePretty(asRuleFile(rules)) + "\n";
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

        List<String> put = new ArrayList<>(List.of(
                "api", "repos/" + repo + "/contents/" + PATH,
                "--method", "PUT",
                "-f", "message=" + commitMessage(decision, actualOverall),
                "-f", "content=" + encoded,
                "-f", "branch=" + branch));
        existingSha(branch).ifPresent(sha -> put.addAll(List.of("-f", "sha=" + sha)));
        Gh.require(put.toArray(String[]::new));

        String url = openOrFindPullRequest(branch, decision, actualOverall, meanAfter);
        RunLog.record("published", "pull request for " + decision.change().arrow(),
                Map.of("url", url, "branch", branch));
        return url;
    }

    private Optional<String> existingSha(String branch) {
        Gh.Result result = Gh.run("api",
                "repos/" + repo + "/contents/" + PATH + "?ref=" + branch, "--jq", ".sha");
        return result.ok() && !result.stdout().isBlank()
                ? Optional.of(result.stdout()) : Optional.empty();
    }

    private String openOrFindPullRequest(String branch, Decision decision,
                                         double actualOverall, double meanAfter) {
        Gh.Result existing = Gh.run("api",
                "repos/" + repo + "/pulls?head=" + repo.split("/")[0] + ":" + branch + "&state=open",
                "--jq", ".[0].html_url");
        if (existing.ok() && !existing.stdout().isBlank() && !"null".equals(existing.stdout())) {
            return existing.stdout();
        }
        return Gh.require("api", "repos/" + repo + "/pulls",
                "--method", "POST",
                "-f", "title=" + commitMessage(decision, actualOverall),
                "-f", "head=" + branch,
                "-f", "base=" + base,
                "-f", "body=" + body(decision, actualOverall, meanAfter),
                "--jq", ".html_url").stdout();
    }

    private static String commitMessage(Decision decision, double actualOverall) {
        return String.format("Vocabulary rule: %s → %s (%+.4f)",
                decision.change().from(), decision.change().to(), actualOverall);
    }

    private static String body(Decision decision, double actualOverall, double meanAfter) {
        return String.format("""
            A shopper searching `%s` could not reach products the catalogue describes
            differently. This rule lets `%s` find `%s`.

            | | |
            |---|---|
            | Predicted by the gate | %+.4f |
            | Measured after re-index | %+.4f |
            | Mean NDCG@10 | %.4f → %.4f |
            | Worst single query | %+.4f |

            Every candidate was measured on a throwaway index before a human saw it.
            Rules that lift the average but cost any single query were rejected and
            never posted for approval — a shopper does not experience the average.

            Approved in Slack by `%s`, applied through SNS → SQS by the worker.

            🤖 Generated with [Claude Code](https://claude.com/claude-code)
            """,
                decision.query(), decision.change().from(), decision.change().to(),
                decision.predictedOverall(), actualOverall,
                decision.baselineMean(), meanAfter, decision.predictedWorst(),
                decision.decidedBy());
    }

    private static Map<String, Object> asRuleFile(SynonymStore rules) {
        Map<String, Object> out = new LinkedHashMap<>();
        rules.rules().forEach((from, targets) -> out.put(from, new ArrayList<>(targets)));
        return out;
    }

    /** snapshot/reset: a demo should not open on yesterday's pull requests. */
    public int closeOpenPullRequests() {
        Gh.Result open = Gh.run("api", "repos/" + repo + "/pulls?state=open", "--jq", ".[].number");
        if (!open.ok() || open.stdout().isBlank()) return 0;
        int closed = 0;
        for (String number : open.stdout().split("\\R")) {
            if (number.isBlank()) continue;
            Gh.run("api", "repos/" + repo + "/pulls/" + number.strip(),
                    "--method", "PATCH", "-f", "state=closed");
            closed++;
        }
        return closed;
    }

    public String repo() {
        return repo;
    }
}
