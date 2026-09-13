package labs.augmentor.auditor.slack;

import com.fasterxml.jackson.core.type.TypeReference;
import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Slack Web API over HTTP. */
public final class HttpSlackApi implements SlackApi {

    private static final String BASE = "https://slack.com/api/";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String token;

    public HttpSlackApi() {
        this(Config.require("SLACK_BOT_TOKEN"));
    }

    public HttpSlackApi(String token) {
        this.token = token;
    }

    @Override
    public String post(String channel, String text, List<Map<String, Object>> blocks) {
        Map<String, Object> reply = call("chat.postMessage", Map.of(
                "channel", channel, "text", text, "blocks", blocks));
        return String.valueOf(reply.get("ts"));
    }

    @Override
    public void update(String channel, String ts, String text, List<Map<String, Object>> blocks) {
        call("chat.update", Map.of("channel", channel, "ts", ts, "text", text, "blocks", blocks));
    }

    @Override
    public void react(String channel, String ts, String emoji) {
        try {
            call("reactions.add", Map.of("channel", channel, "timestamp", ts, "name", emoji));
        } catch (SlackException e) {
            // already_reacted is not a failure, and a reaction is decoration. The
            // one that matters is channel_not_found: chat:write.public lets the bot
            // POST to a channel it is not in, but reactions need real membership.
            if (!e.getMessage().contains("already_reacted")) {
                System.err.println("slack reaction: " + e.getMessage());
            }
        }
    }

    @Override
    public void delete(String channel, String ts) {
        call("chat.delete", Map.of("channel", channel, "ts", ts));
    }

    @Override
    public Map<String, Object> whoAmI() {
        return call("auth.test", Map.of());
    }

    @Override
    public boolean isMemberOf(String channel) {
        // conversations.info does not accept a JSON body — it answers
        // invalid_arguments "missing required field: channel" for one, which reads
        // like the argument was omitted rather than encoded the wrong way.
        Map<String, Object> reply = query("conversations.info", Map.of("channel", channel));
        Object info = reply.get("channel");
        return info instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("is_member"));
    }

    /** Form-encoded GET, for the read methods that will not take JSON. */
    private Map<String, Object> query(String method, Map<String, String> params) {
        StringBuilder url = new StringBuilder(BASE).append(method).append('?');
        params.forEach((k, v) -> url.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = Json.mapper().readValue(
                    response.body(), new TypeReference<Map<String, Object>>() {});
            if (!Boolean.TRUE.equals(parsed.get("ok"))) {
                throw new SlackException(method + ": " + parsed.get("error"));
            }
            return parsed;
        } catch (SlackException e) {
            throw e;
        } catch (Exception e) {
            throw new SlackException(method + ": " + e.getMessage(), e);
        }
    }

    private Map<String, Object> call(String method, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + method))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = Json.mapper().readValue(
                    response.body(), new TypeReference<Map<String, Object>>() {});
            if (!Boolean.TRUE.equals(parsed.get("ok"))) {
                throw new SlackException(method + ": " + parsed.get("error")
                        + (parsed.containsKey("response_metadata") ? " " + parsed.get("response_metadata") : ""));
            }
            return parsed;
        } catch (SlackException e) {
            throw e;
        } catch (Exception e) {
            throw new SlackException(method + ": " + e.getMessage(), e);
        }
    }

    public static final class SlackException extends RuntimeException {
        public SlackException(String message) {
            super(message);
        }

        public SlackException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
