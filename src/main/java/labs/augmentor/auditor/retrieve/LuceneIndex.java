package labs.augmentor.auditor.retrieve;

import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.audit.SynonymStore;
import labs.augmentor.auditor.model.Hit;
import labs.augmentor.auditor.model.Product;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.util.*;

/**
 * BM25 over the slice. Held in memory: 617 documents build in well under a second,
 * which is what makes the measurement gate affordable — every candidate rule gets
 * its own throwaway index.
 *
 * EnglishAnalyzer, not StandardAnalyzer: stemming is worth +0.0396 on the mean
 * (0.6553 -> 0.6949) and it raises the floor the agent has to clear. Without it the
 * first rule the agent proposed was chair -> chairs, which passes the gate honestly
 * (+0.0087, no query worse) and is morphology rather than vocabulary. A stemmer is
 * the right tool for that, and removing the whole class of trivial proposals means
 * anything the agent does find is a real gap between what shoppers say and what the
 * catalogue says.
 *
 * Field weights:
 *   title       3.0   what the product is
 *   category    1.0
 *   description 1.0
 *
 * The WANDS attribute fields are indexed but deliberately not queried. Adding them
 * measured mean 0.6489 against 0.6553 without — they are sparse (74% of the slice
 * has no colour at all) and the ones that exist mostly restate the title.
 *
 * Vocabulary rules are applied at query time, at {@link #SYNONYM_WEIGHT}. The
 * principle is the one from the attribute work: a word the catalogue never used is
 * worth less than one it did — enough to surface a buried product, not enough to
 * displace one that genuinely says the word.
 *
 * Applying them at INDEX time, by writing the shopper's word into a dedicated
 * sparse field, does not work, and fails silently. Only the documents a rule
 * reaches get the field at all, so within that field every document contains the
 * term: docCount == docFreq, and BM25 idf collapses from 2.83 to 0.0136. The rule
 * fires on all 36 documents and moves the score by 0.003. It reads exactly like a
 * rule that had no effect, which is the most expensive kind of bug this project
 * has. Scored against the real fields, the same word keeps its real statistics.
 */
public final class LuceneIndex implements Searcher, AutoCloseable {

    /**
     * What a rule's word counts for, relative to the shopper's own. Swept, not
     * chosen: the curve is flat from 0.8 to 1.5 (mean 0.6872) and falls away above
     * 2.0, where the rule starts outranking literal matches. 1.0 is the middle of
     * the plateau, and is the honest reading of a synonym — the two words mean the
     * same thing. Run `sweep` before changing it.
     */
    public static final float SYNONYM_WEIGHT =
            (float) Config.getDouble("SYNONYM_WEIGHT", 1.0);

    private static final String F_ID = "id";
    private static final String F_TITLE = "title";
    private static final String F_DESC = "description";
    private static final String F_CATEGORY = "category";
    private static final String F_ATTRS = "attrs";

    private static final Map<String, Float> BOOSTS = Map.of(
            F_TITLE, 3.0f,
            F_CATEGORY, 1.0f,
            F_DESC, 1.0f);

    private static final String[] FIELDS = {F_TITLE, F_CATEGORY, F_DESC};

    private final Directory directory = new ByteBuffersDirectory();
    private final EnglishAnalyzer analyzer = new EnglishAnalyzer();
    private final Map<String, Product> byId = new LinkedHashMap<>();
    private final SynonymStore synonyms;
    private final float synonymWeight;
    private final IndexSearcher searcher;
    private final DirectoryReader reader;

    public LuceneIndex(List<Product> products) {
        this(products, SynonymStore.detached());
    }

    /**
     * The store is held by reference, not copied. Two stores means two caches: the
     * applier writes into one, search reads the other, and the evaluator then
     * reports +0.0000 for a change that works — and writes that zero into the
     * audit row. Pass {@code SynonymStore.shared()}, or a deliberate {@code plus()}
     * for validation.
     */
    public LuceneIndex(List<Product> products, SynonymStore synonyms) {
        this(products, synonyms, SYNONYM_WEIGHT);
    }

    public LuceneIndex(List<Product> products, SynonymStore synonyms, float synonymWeight) {
        this.synonyms = synonyms;
        this.synonymWeight = synonymWeight;
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (Product p : products) {
                byId.put(p.id(), p);
                Document doc = new Document();
                doc.add(new StringField(F_ID, p.id(), Field.Store.YES));
                doc.add(new TextField(F_TITLE, p.title(), Field.Store.NO));
                doc.add(new TextField(F_DESC, p.description(), Field.Store.NO));
                doc.add(new TextField(F_CATEGORY, p.category(), Field.Store.NO));
                doc.add(new TextField(F_ATTRS, String.join(" ", p.attrs().values()), Field.Store.NO));
                writer.addDocument(doc);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not build the index", e);
        }
        try {
            this.reader = DirectoryReader.open(directory);
        } catch (IOException e) {
            throw new IllegalStateException("could not open the index", e);
        }
        this.searcher = new IndexSearcher(reader);
        this.searcher.setSimilarity(new BM25Similarity());
    }

    @Override
    public List<Hit> search(String query, int k) {
        if (query == null || query.isBlank()) return List.of();
        try {
            TopDocs top = searcher.search(build(query), Math.max(k, 1));
            StoredFields stored = searcher.storedFields();
            List<Hit> hits = new ArrayList<>();
            for (ScoreDoc sd : top.scoreDocs) {
                String id = stored.document(sd.doc).get(F_ID);
                hits.add(new Hit(id, sd.score, byId.get(id)));
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("search failed for: " + query, e);
        }
    }

    private Query build(String query) throws Exception {
        String lower = query.toLowerCase();
        Query base = parser().parse(QueryParser.escape(lower));
        if (synonyms.isEmpty()) return base;

        BooleanQuery.Builder combined = new BooleanQuery.Builder();
        combined.add(base, BooleanClause.Occur.SHOULD);
        for (String target : expansionsFor(lower)) {
            combined.add(new BoostQuery(parser().parse(QueryParser.escape(target)), synonymWeight),
                    BooleanClause.Occur.SHOULD);
        }
        return combined.build();
    }

    /**
     * Catalogue words this query's own words are allowed to reach.
     *
     * Rule keys can be phrases. The model proposes "fold out -> sleeper" readily,
     * and matching only single terms made that rule silently inert — which the gate
     * then reported as "no gain", a true number attached to the wrong reason. A
     * rule that cannot fire and a rule that fires and does nothing are different
     * findings, and only one of them is about the rule.
     */
    public Set<String> expansionsFor(String query) {
        String lower = " " + query.toLowerCase().replaceAll("\\W+", " ").trim() + " ";
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> rule : synonyms.rules().entrySet()) {
            String key = rule.getKey();
            if (lower.contains(" " + key + " ")) out.addAll(rule.getValue());
        }
        return out;
    }

    /**
     * A MultiFieldQueryParser is not thread safe and carries parse state, so it is
     * built per call rather than shared. WANDS queries also carry characters the
     * parser treats as syntax — a stray "+" or "-" turns a search into a parse
     * error, which presents as zero results for a query that plainly should match.
     */
    private MultiFieldQueryParser parser() {
        MultiFieldQueryParser parser = new MultiFieldQueryParser(FIELDS, analyzer, BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        return parser;
    }

    /** Documents any active rule can actually reach. Zero means the rule set is inert. */
    public int reachableDocs() {
        if (synonyms.isEmpty()) return 0;
        Set<String> targets = new LinkedHashSet<>();
        synonyms.rules().values().forEach(targets::addAll);
        int count = 0;
        for (Product p : byId.values()) {
            Set<String> words = new HashSet<>(Arrays.asList(p.searchableText().split("\\W+")));
            if (targets.stream().anyMatch(words::contains)) count++;
        }
        return count;
    }

    public int size() {
        return byId.size();
    }

    public Optional<Product> product(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Product> products() {
        return List.copyOf(byId.values());
    }

    @Override
    public void close() {
        try {
            reader.close();
            directory.close();
            analyzer.close();
        } catch (IOException e) {
            // A closed throwaway index has nothing left to lose.
        }
    }
}
