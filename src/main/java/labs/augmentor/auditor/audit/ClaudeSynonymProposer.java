package labs.augmentor.auditor.audit;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.JsonNode;
import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.Json;
import labs.augmentor.auditor.model.ProposedChange;
import labs.augmentor.auditor.model.TermCount;
import labs.augmentor.auditor.model.VocabularyGap;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Asks Claude to name the catalogue's word for the thing the shopper asked for.
 *
 * The model is given counts, not judgement: the query, how rare each of its words
 * is in the catalogue, the vocabulary of the products the query already retrieves,
 * and their titles. It is being asked to read the evidence, not to recall what
 * furniture words mean.
 *
 * Whatever it says is a candidate and nothing more. Every proposal goes through
 * {@link RuleValidator} before a human sees it, which is what makes it safe for
 * this to be non-deterministic — it proposes toddler→kids on one run and
 * couch→sofa on another, and the gate sorts them out.
 */
public final class ClaudeSynonymProposer {

    private static final String SYSTEM = """
        You are auditing a furniture catalogue's search index for vocabulary gaps.

        A vocabulary gap is a word a shopper uses that the catalogue does not. The
        products exist; the words are not in the index, so no amount of ranking
        work reaches them.

        You will be given a query that scores badly, how many product titles use
        each of its words, and the vocabulary of the products the query does
        retrieve. Propose synonym rules of the form: shopper's word -> catalogue's
        word.

        Rules:
        - The catalogue word must be one that appears in the evidence you are given.
        - Propose a rule only when the two words denote the same kind of product.
          Words that merely co-occur are not synonyms: "velvet" and "chaise" appear
          together constantly and mean entirely different things.
        - Be careful with near-misses that narrow or widen meaning. A children's
          product is not the adult product, and vice versa.
        - Propose at most 3. Fewer is better. An empty list is a valid answer.

        Reply with JSON only, no prose and no code fences:
        {"rules":[{"from":"...","to":"...","rationale":"one sentence"}]}
        """;

    private final AnthropicClient client;
    private final String model;

    public ClaudeSynonymProposer() {
        this(Config.require("LLM_API_KEY"), Config.get("LLM_MODEL", "claude-sonnet-5"));
    }

    public ClaudeSynonymProposer(String apiKey, String model) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    public List<ProposedChange> propose(VocabularyGap gap) {
        String reply = call(prompt(gap));
        return parse(reply);
    }

    /**
     * Transport failures must throw, never return empty.
     *
     * A billing 400 once produced "140 careful abstentions" in 4.8 seconds: every
     * call failed, every failure was swallowed as "no proposal", and the run looked
     * like a model exercising good judgement. An empty list has to mean the model
     * read the evidence and declined.
     */
    private String call(String prompt) {
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024)
                    .system(SYSTEM)
                    .addUserMessage(prompt)
                    .build();
            Message message = client.messages().create(params);
            String text = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());
            if (text.isBlank()) {
                throw new IllegalStateException("the model returned no text");
            }
            return text;
        } catch (RuntimeException e) {
            throw new ProposerException("proposal call failed: " + e.getMessage(), e);
        }
    }

    /** Public for tests: the prompt is evidence, and evidence is worth asserting on. */
    public static String prompt(VocabularyGap gap) {
        StringBuilder sb = new StringBuilder();
        sb.append("Query: \"").append(gap.query()).append("\"\n");
        sb.append(String.format("NDCG@10: %.4f%n", gap.score()));
        sb.append(String.format("Relevant products it fails to show: %d%n%n", gap.relevantMissed()));

        sb.append("How many product titles in the catalogue use each of the query's own words:\n");
        for (TermCount tc : gap.queryTerms()) {
            sb.append(String.format("  %-16s %d titles%n", tc.term(), tc.titles()));
        }

        sb.append("\nWords the catalogue uses for the relevant products this query MISSES.\n");
        sb.append("\"titles\" is how common the word is overall; \"reaches\" is how many of\n");
        sb.append("the ").append(gap.relevantMissed()).append(" missed products it would reach:\n");
        for (TermCount tc : gap.catalogueVocabulary()) {
            sb.append(String.format("  %-16s %4d titles   reaches %d%n", tc.term(), tc.titles(), tc.covers()));
        }

        sb.append("\nTitles of the relevant products it misses:\n");
        for (String title : gap.missedTitles()) {
            sb.append("  ").append(title).append('\n');
        }
        return sb.toString();
    }

    public static List<ProposedChange> parse(String reply) {
        String cleaned = reply.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        List<ProposedChange> out = new ArrayList<>();
        try {
            JsonNode root = Json.mapper().readTree(cleaned);
            JsonNode rules = root.get("rules");
            if (rules == null || !rules.isArray()) return out;
            for (JsonNode rule : rules) {
                String from = rule.path("from").asText("");
                String to = rule.path("to").asText("");
                if (from.isBlank() || to.isBlank() || from.equalsIgnoreCase(to)) continue;
                out.add(ProposedChange.synonym(from, to, rule.path("rationale").asText("")));
            }
        } catch (Exception e) {
            throw new ProposerException("could not parse the model's reply: " + cleaned, e);
        }
        return out;
    }

    /** Distinct from "the model declined", which is an empty list. */
    public static final class ProposerException extends RuntimeException {
        public ProposerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
