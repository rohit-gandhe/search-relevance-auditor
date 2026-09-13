package labs.augmentor.auditor.retrieve;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The index's own analyzer, exposed so that everything reasoning about words
 * reasons about the same words the index stores.
 *
 * This exists because of a rule that passed the gate dishonestly. With stemming on,
 * "chair" and "chairs" are one token, so chair -> chairs is not a synonym rule at
 * all — but expansion adds a second query clause for it, which simply doubles the
 * weight on a term the shopper already typed. That measured +0.0277 and no query
 * worse. The gain was real; the explanation was not. A rule has to be a rule about
 * vocabulary, and a word cannot be a synonym for itself.
 */
public final class Analysis {

    private static final EnglishAnalyzer ANALYZER = new EnglishAnalyzer();

    private Analysis() {}

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        try (TokenStream stream = ANALYZER.tokenStream("title", new StringReader(text))) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) out.add(term.toString());
            stream.end();
        } catch (Exception e) {
            throw new IllegalStateException("could not analyse: " + text, e);
        }
        return out;
    }

    /** The single token a word becomes in the index, or the word itself if it is dropped. */
    public static String stem(String word) {
        List<String> tokens = tokens(word);
        return tokens.isEmpty() ? word.toLowerCase() : tokens.get(0);
    }

    /** True when the index cannot tell these two words apart. */
    public static boolean sameToken(String a, String b) {
        return stem(a).equals(stem(b));
    }
}
