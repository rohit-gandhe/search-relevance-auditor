package labs.augmentor.auditor;

import labs.augmentor.auditor.audit.SheetsAuditLog;
import labs.augmentor.auditor.ingest.Catalogues;
import labs.augmentor.auditor.publish.RulePublisher;
import labs.augmentor.auditor.slack.HttpSlackApi;
import labs.augmentor.auditor.slack.SlackApi;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Checks everything the demo needs, read-only, before it is needed.
 *
 * Every line here exists because something failed silently once. The channel
 * membership check in particular: chat:write.public lets the bot post to a channel
 * it is not a member of, so posting works and reactions come back
 * channel_not_found, which reads as a token problem and is not one.
 */
public final class Doctor {

    private record Check(String name, boolean ok, String detail) {}

    private final List<Check> checks = new ArrayList<>();

    public static void main(String[] args) {
        new Doctor().run();
    }

    public boolean run() {
        check("catalogue", () -> {
            Catalogues.Catalogue c = Catalogues.active();
            return c.size() + " products · " + c.queries().size() + " judged queries";
        });

        check("llm", () -> {
            Config.require("LLM_API_KEY");
            return Config.get("LLM_MODEL", "claude-sonnet-5");
        });

        SlackApi slack = new HttpSlackApi();
        check("slack auth", () -> {
            Map<String, Object> me = slack.whoAmI();
            return me.get("user") + " in " + me.get("team");
        });

        for (String key : List.of("SLACK_APPROVALS_CHANNEL", "SLACK_ARCHIVE_CHANNEL")) {
            check("slack " + key.replace("SLACK_", "").replace("_CHANNEL", "").toLowerCase(), () -> {
                String channel = Config.require(key);
                if (!slack.isMemberOf(channel)) {
                    // chat:write.public would still let it post. Reactions would not
                    // work, and that failure names the wrong cause.
                    throw new IllegalStateException("bot is not a member of " + channel
                            + " — invite it, or reactions will fail with channel_not_found");
                }
                return channel + " · bot is a member";
            });
        }

        check("slack socket token", () -> {
            String token = Config.require("SLACK_APP_TOKEN");
            if (!token.startsWith("xapp-")) throw new IllegalStateException("not an app-level token");
            return "xapp-… present";
        });

        check("sns topic", () -> {
            try (SnsClient sns = SnsClient.builder()
                    .region(Region.of(Config.get("AWS_REGION", "us-west-2"))).build()) {
                Map<String, String> attrs = sns.getTopicAttributes(r ->
                        r.topicArn(Config.require("DECISIONS_TOPIC_ARN"))).attributes();
                String confirmed = attrs.getOrDefault("SubscriptionsConfirmed", "0");
                if ("0".equals(confirmed)) {
                    throw new IllegalStateException("no confirmed subscription — decisions would go nowhere");
                }
                return confirmed + " confirmed subscription(s)";
            }
        });

        check("sqs queue", () -> {
            try (SqsClient sqs = SqsClient.builder()
                    .region(Region.of(Config.get("AWS_REGION", "us-west-2"))).build()) {
                Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(
                        GetQueueAttributesRequest.builder()
                                .queueUrl(Config.require("ENRICHMENT_QUEUE_URL"))
                                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                        QueueAttributeName.VISIBILITY_TIMEOUT)
                                .build()).attributes();
                return attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES) + " waiting · "
                        + attrs.get(QueueAttributeName.VISIBILITY_TIMEOUT) + "s visibility";
            }
        });

        check("sqs dead letter", () -> {
            try (SqsClient sqs = SqsClient.builder()
                    .region(Region.of(Config.get("AWS_REGION", "us-west-2"))).build()) {
                String depth = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(Config.require("DEAD_LETTER_QUEUE_URL"))
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                        .build()).attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
                if (!"0".equals(depth)) {
                    throw new IllegalStateException(depth + " message(s) in the DLQ — something failed 3 times");
                }
                return "empty";
            }
        });

        check("google sheet", () -> "\"" + new SheetsAuditLog().title() + "\"");

        check("github", () -> {
            RulePublisher publisher = new RulePublisher();
            return publisher.repo();
        });

        System.out.println();
        long failed = checks.stream().filter(c -> !c.ok()).count();
        System.out.println(failed == 0
                ? "all clear"
                : failed + " check(s) failed — the demo will not work until they do");
        return failed == 0;
    }

    private void check(String name, Supplier<String> probe) {
        try {
            String detail = probe.get();
            checks.add(new Check(name, true, detail));
            System.out.printf("  ok    %-22s %s%n", name, detail);
        } catch (Exception e) {
            checks.add(new Check(name, false, e.getMessage()));
            System.out.printf("  FAIL  %-22s %s%n", name, e.getMessage());
        }
    }
}
