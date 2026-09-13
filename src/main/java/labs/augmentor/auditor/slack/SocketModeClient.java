package labs.augmentor.auditor.slack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.Json;
import labs.augmentor.auditor.RunLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Receives button clicks over a WebSocket the app opens itself.
 *
 * Socket Mode means Slack never POSTs to the Request URL. If it is enabled, the
 * HTTP endpoint is dead no matter what the URL field says — which is worth knowing
 * before spending an afternoon debugging an endpoint that was never going to be
 * called. It is also the better choice here: no tunnel, nothing exposed, and no URL
 * to re-save every time ngrok hands out a new one.
 *
 * Slack expects the envelope acknowledged within three seconds. The ack goes back
 * immediately and the actual work is handed to another thread — which is the entire
 * reason there is a queue downstream.
 */
public final class SocketModeClient implements AutoCloseable {

    private final String appToken;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService work = Executors.newFixedThreadPool(2);
    private volatile WebSocket socket;
    private volatile boolean running = true;

    public SocketModeClient() {
        this(Config.require("SLACK_APP_TOKEN"));
    }

    public SocketModeClient(String appToken) {
        if (!appToken.startsWith("xapp-")) {
            throw new IllegalArgumentException(
                    "SLACK_APP_TOKEN must be an app-level token (xapp-…), not the bot token");
        }
        this.appToken = appToken;
    }

    /** Blocks, reconnecting when Slack asks it to. */
    public void listen(Consumer<Interaction> handler) {
        while (running) {
            CountDownLatch closed = new CountDownLatch(1);
            try {
                String url = openConnection();
                socket = http.newWebSocketBuilder()
                        .buildAsync(URI.create(url), new Listener(handler, closed))
                        .join();
                RunLog.record("slack", "socket mode connected");
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                RunLog.record("slack", "socket mode connect failed: " + e.getMessage());
            }
            if (running) {
                // Slack cycles connections every few minutes by design.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String openConnection() {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://slack.com/api/apps.connections.open"))
                    .header("Authorization", "Bearer " + appToken)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = Json.mapper().readValue(
                    response.body(), new TypeReference<Map<String, Object>>() {});
            if (!Boolean.TRUE.equals(parsed.get("ok"))) {
                throw new IllegalStateException("apps.connections.open: " + parsed.get("error")
                        + " — is Socket Mode enabled and the app token scoped connections:write?");
            }
            return String.valueOf(parsed.get("url"));
        } catch (Exception e) {
            throw new IllegalStateException("could not open a socket mode connection", e);
        }
    }

    /** One button click, already unwrapped. */
    public record Interaction(String actionId, String value, String userId,
                              String channel, String messageTs) {}

    private final class Listener implements WebSocket.Listener {

        private final Consumer<Interaction> handler;
        private final CountDownLatch closed;
        private final StringBuilder buffer = new StringBuilder();

        Listener(Consumer<Interaction> handler, CountDownLatch closed) {
            this.handler = handler;
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(webSocket, message);
                } catch (RuntimeException e) {
                    RunLog.record("slack", "socket frame failed: " + e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        private void handle(WebSocket webSocket, String message) {
            JsonNode root;
            try {
                root = Json.mapper().readTree(message);
            } catch (Exception e) {
                return;
            }
            String type = root.path("type").asText("");
            if ("disconnect".equals(type)) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "slack asked");
                closed.countDown();
                return;
            }
            if (!root.has("envelope_id")) return;                 // hello, and anything else

            // Acknowledge first, always, and before doing any work at all.
            webSocket.sendText(Json.write(Map.of("envelope_id", root.get("envelope_id").asText())), true);

            JsonNode payload = root.path("payload");
            JsonNode action = payload.path("actions").path(0);
            if (action.isMissingNode()) return;

            Interaction interaction = new Interaction(
                    action.path("action_id").asText(""),
                    action.path("value").asText(""),
                    payload.path("user").path("id").asText(""),
                    payload.path("channel").path("id").asText(""),
                    payload.path("message").path("ts").asText(""));

            work.submit(() -> {
                try {
                    handler.accept(interaction);
                } catch (RuntimeException e) {
                    RunLog.record("slack", "interaction handler failed: " + e.getMessage());
                }
            });
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            RunLog.record("slack", "socket error: " + error.getMessage());
            closed.countDown();
        }
    }

    @Override
    public void close() {
        running = false;
        WebSocket open = socket;
        if (open != null) open.sendClose(WebSocket.NORMAL_CLOSURE, "shutting down");
        work.shutdownNow();
    }
}
