package labs.augmentor.auditor.slack;

import java.util.List;
import java.util.Map;

/**
 * The slice of Slack this project uses. An interface so the tests can exercise the
 * whole approval path without a token and without posting anything.
 */
public interface SlackApi {

    /** Returns the message ts, which is the only handle that finds the message again. */
    String post(String channel, String text, List<Map<String, Object>> blocks);

    void update(String channel, String ts, String text, List<Map<String, Object>> blocks);

    void react(String channel, String ts, String emoji);

    void delete(String channel, String ts);

    /** auth.test — who the bot is. */
    Map<String, Object> whoAmI();

    /** conversations.info — chat:write.public does not cover reactions, so membership matters. */
    boolean isMemberOf(String channel);
}
