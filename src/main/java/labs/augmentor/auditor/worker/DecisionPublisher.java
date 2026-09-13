package labs.augmentor.auditor.worker;

import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.Json;
import labs.augmentor.auditor.RunLog;
import labs.augmentor.auditor.model.Decision;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.Map;

/**
 * Publishes a decision to SNS, which fans out to the work queue.
 *
 * The queue is here for a specific reason: Slack gives three seconds to acknowledge
 * a button click, and applying a rule — re-index, re-evaluate nine queries, write a
 * sheet row, open a pull request — takes considerably longer. Acknowledging and
 * queueing is the only honest way to answer in time.
 */
public final class DecisionPublisher implements AutoCloseable {

    private final SnsClient sns;
    private final String topicArn;

    public DecisionPublisher() {
        this(Config.require("DECISIONS_TOPIC_ARN"), Config.get("AWS_REGION", "us-west-2"));
    }

    public DecisionPublisher(String topicArn, String region) {
        this.topicArn = topicArn;
        this.sns = SnsClient.builder().region(Region.of(region)).build();
    }

    public String publish(Decision decision) {
        String body = Json.write(decision);
        String messageId = sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(body)
                .build()).messageId();
        RunLog.record("queued", "decision " + decision.id() + " published to SNS",
                Map.of("messageId", messageId, "rule", decision.change().arrow()));
        return messageId;
    }

    @Override
    public void close() {
        sns.close();
    }
}
