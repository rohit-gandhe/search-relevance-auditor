package labs.augmentor.auditor.worker;

import com.fasterxml.jackson.databind.JsonNode;
import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.Json;
import labs.augmentor.auditor.RunLog;
import labs.augmentor.auditor.model.Decision;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Long-polls the work queue and hands each decision to the worker.
 *
 * A message is deleted only after it has been handled. A handler that throws leaves
 * the message to reappear after the visibility timeout, and after three attempts
 * the queue moves it to the dead-letter queue rather than retrying it forever — so
 * a rule that cannot be applied stops, visibly, instead of quietly looping.
 */
public final class SqsConsumer implements AutoCloseable {

    private final SqsClient sqs;
    private final String queueUrl;
    private volatile boolean running = true;

    public SqsConsumer() {
        this(Config.require("ENRICHMENT_QUEUE_URL"), Config.get("AWS_REGION", "us-west-2"));
    }

    public SqsConsumer(String queueUrl, String region) {
        this.queueUrl = queueUrl;
        this.sqs = SqsClient.builder().region(Region.of(region)).build();
    }

    public void poll(Consumer<Decision> handler) {
        RunLog.record("worker", "listening on the decision queue");
        while (running) {
            List<Message> messages;
            try {
                messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(20)             // long poll
                        .build()).messages();
            } catch (RuntimeException e) {
                RunLog.record("worker", "receive failed: " + e.getMessage());
                sleep(2000);
                continue;
            }
            for (Message message : messages) {
                try {
                    handler.accept(parse(message.body()));
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
                } catch (RuntimeException e) {
                    // Left on the queue on purpose: three attempts, then the DLQ.
                    RunLog.record("worker", "handling failed, leaving it on the queue: " + e.getMessage(),
                            Map.of("messageId", message.messageId()));
                }
            }
        }
    }

    /**
     * The topic has raw message delivery on, so the body is the decision itself.
     * It has been switched off by accident before, and an SNS envelope arriving at
     * a parser expecting a decision produces a confusing failure a long way from
     * its cause — so unwrap one if it turns up.
     */
    public static Decision parse(String body) {
        JsonNode node;
        try {
            node = Json.mapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("queue message was not JSON: " + body, e);
        }
        if (node.has("Type") && node.has("Message") && "Notification".equals(node.get("Type").asText())) {
            return Json.read(node.get("Message").asText(), Decision.class);
        }
        return Json.read(body, Decision.class);
    }

    public void stop() {
        running = false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        sqs.close();
    }
}
