package labs.augmentor.auditor.audit;

import labs.augmentor.auditor.eval.EvalRunner;
import labs.augmentor.auditor.eval.Scorecard;
import labs.augmentor.auditor.model.*;
import labs.augmentor.auditor.retrieve.Analysis;
import labs.augmentor.auditor.retrieve.LuceneIndex;

import java.util.*;

/**
 * Counts words. That is the entire diagnosis, and it is the part that works.
 *
 * For a query that scores badly, three questions:
 *
 *   1. how rare are the shopper's own words in the catalogue?
 *   2. which relevant products is the query failing to surface?
 *   3. what words does the catalogue use for those specific products?
 *
 * Question three is what makes the evidence worth giving to a model. A word taken
 * from the results the query already returns is just co-occurrence — "velvet" and
 * "chaise" travel together constantly and mean different things. A word taken from
 * the relevant products the query MISSED is a word that would demonstrably reach
 * them, and the count of how many it reaches is the addressable gap.
 *
 * No model is involved at this stage. These are facts about the catalogue and the
 * human judgements, and they are what the model is later asked to read rather than
 * recall.
 */
public final class VocabularyGapFinder {

    /** A query's own word is "rare" below this share of the catalogue. */
    private static final double RARE_SHARE = 0.05;
    private static final int VOCABULARY_SIZE = 12;
    private static final int SAMPLE_TITLES = 12;

    /**
     * Words that are common everywhere and mean nothing here. Lucene's stopword
     * list does not cover retail filler like "set" or "piece".
     */
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "and", "or", "of", "with", "for", "in", "on", "to", "by",
            "set", "piece", "pieces", "inch", "inches", "cm", "x", "w", "h", "d", "up",
            "1", "2", "3", "4", "5", "6", "8", "10", "12");

    private final LuceneIndex index;
    private final Map<String, Integer> titleCounts;
    private final Map<String, Integer> docCounts;

    public VocabularyGapFinder(LuceneIndex index) {
        this.index = index;
        this.titleCounts = count(index.products(), Product::title);
        this.docCounts = count(index.products(), Product::searchableText);
    }

    /** How many product titles use this word. The headline number in the brief. */
    public int titlesUsing(String word) {
        return titleCounts.getOrDefault(word.toLowerCase(), 0);
    }

    /** How many products mention it anywhere — the base rate that lift is measured against. */
    public int docsUsing(String word) {
        return docCounts.getOrDefault(word.toLowerCase(), 0);
    }

    /** Judged queries worst first — the agent picks its own target, unprompted. */
    public List<JudgedQuery> worstFirst(List<JudgedQuery> queries, Scorecard card) {
        List<JudgedQuery> sorted = new ArrayList<>(queries);
        sorted.sort(Comparator.comparingDouble(q -> card.scoreOf(q.queryId())));
        return sorted;
    }

    public VocabularyGap analyse(JudgedQuery query, double score) {
        // What the shopper actually sees, and therefore what they do not.
        Set<String> shown = new HashSet<>();
        index.search(query.query(), EvalRunner.K).forEach(h -> shown.add(h.id()));

        List<Product> missed = new ArrayList<>();
        for (String id : query.relevantIds()) {
            if (shown.contains(id)) continue;
            index.product(id).ifPresent(missed::add);
        }
        if (missed.isEmpty()) {
            return VocabularyGap.none(query.queryId(), query.query(), score, 0);
        }

        List<String> terms = tokenise(query.query());
        Set<String> queryWords = new HashSet<>(terms);
        // The index cannot tell "chair" from "chairs", so neither should the
        // evidence. Offering morphological variants as candidates spends a model
        // call on a rule the gate will reject on principle.
        Set<String> queryStems = terms.stream().map(Analysis::stem).collect(java.util.stream.Collectors.toSet());

        List<TermCount> queryTerms = terms.stream()
                .map(t -> TermCount.of(t, titlesUsing(t)))
                .filter(tc -> tc.titles() < index.size() * RARE_SHARE)
                .sorted()
                .toList();

        // The addressable gap: for each candidate word, how many of the relevant
        // products this query missed does it actually reach, and is it reaching
        // them because it describes them or because it describes furniture?
        Map<String, Integer> coverage = new HashMap<>();
        for (Product p : missed) {
            for (String word : new HashSet<>(tokenise(p.searchableText()))) {
                if (queryWords.contains(word) || queryStems.contains(Analysis.stem(word))) continue;
                coverage.merge(word, 1, Integer::sum);
            }
        }

        List<TermCount> vocabulary = coverage.entrySet().stream()
                .filter(e -> e.getValue() >= 2)           // one product is a coincidence
                .filter(e -> titlesUsing(e.getKey()) > 0) // must be a word titles use
                .map(e -> {
                    double inMissed = (double) e.getValue() / missed.size();
                    double inCatalogue = (double) docsUsing(e.getKey()) / index.size();
                    double lift = inCatalogue == 0 ? 0 : inMissed / inCatalogue;
                    return new TermCount(e.getKey(), titlesUsing(e.getKey()), e.getValue(), lift);
                })
                .filter(TermCount::isDiscriminating)
                .sorted()
                .limit(VOCABULARY_SIZE)
                .toList();

        int addressable = vocabulary.stream().mapToInt(TermCount::covers).max().orElse(0);
        List<String> titles = missed.stream().map(Product::title).limit(SAMPLE_TITLES).toList();

        return new VocabularyGap(query.queryId(), query.query(), score,
                missed.size(), addressable, queryTerms, vocabulary, titles);
    }

    private static Map<String, Integer> count(
            List<Product> products, java.util.function.Function<Product, String> text) {
        Map<String, Integer> counts = new HashMap<>();
        for (Product p : products) {
            for (String word : new HashSet<>(tokenise(text.apply(p)))) {
                counts.merge(word, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static List<String> tokenise(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.toLowerCase().split("\\W+")) {
            if (raw.length() < 2 || STOP.contains(raw)) continue;
            out.add(raw);
        }
        return out;
    }
}
