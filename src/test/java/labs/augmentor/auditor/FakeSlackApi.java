package labs.augmentor.auditor;

import labs.augmentor.auditor.slack.SlackApi;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Records what would have been sent. No token, no network, nothing posted. */
public final class FakeSlackApi implements SlackApi {

    public record Sent(String channel, String ts, String text, List<Map<String, Object>> blocks) {}

    public final List<Sent> posted = new ArrayList<>();
    public final List<Sent> updated = new ArrayList<>();
    public final List<String> reactions = new ArrayList<>();
    public final List<String> deleted = new ArrayList<>();

    private final AtomicInteger counter = new AtomicInteger();
    public boolean member = true;

    @Override
    public String post(String channel, String text, List<Map<String, Object>> blocks) {
        // Slack ts values look like this, and the shape matters: it is what the
        // sheet has to store without losing precision.
        String ts = "17890724" + (72 + counter.incrementAndGet()) + ".500979";
        posted.add(new Sent(channel, ts, text, blocks));
        return ts;
    }

    @Override
    public void update(String channel, String ts, String text, List<Map<String, Object>> blocks) {
        updated.add(new Sent(channel, ts, text, blocks));
    }

    @Override
    public void react(String channel, String ts, String emoji) {
        reactions.add(emoji);
    }

    @Override
    public void delete(String channel, String ts) {
        deleted.add(ts);
    }

    @Override
    public Map<String, Object> whoAmI() {
        return Map.of("ok", true, "user", "relevance_auditor", "team", "test");
    }

    @Override
    public boolean isMemberOf(String channel) {
        return member;
    }
}
