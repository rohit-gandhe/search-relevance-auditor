package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Comparator;
import java.util.List;

/**
 * A query whose own words are rare in the catalogue, next to the words the
 * catalogue actually uses for the products it is failing to find.
 *
 * This is the whole diagnosis. "sofa" appears in 922 titles of the full WANDS
 * catalogue and "couch" in 30; a shopper using their own word cannot reach most of
 * what they want, and no amount of ranking work fixes it, because the words are not
 * in the index.
 *
 * {@code addressable} is the gate on the diagnosis itself, and it is the lesson of
 * the colour work. 74% of the slice has no colour attribute, which looked like an
 * enormous opportunity — until anyone checked whether the missing colour was
 * recoverable from the product's own text. For "turquoise pillows" and "teal chair"
 * it was recoverable for exactly zero products: Wayfair sells the same sofa in
 * twelve colours, so the copy is deliberately colour-agnostic and colour lives in a
 * variant picker, not prose. Days went into building on a gap that was not there.
 * Measure the addressable gap before building on anything.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VocabularyGap(
        String queryId,
        String query,
        double score,
        int relevantMissed,
        int addressable,
        List<TermCount> queryTerms,
        List<TermCount> catalogueVocabulary,
        List<String> missedTitles) {

    /*
     * A note on the name of hasAddressableGap().
     *
     * It was isAddressable(), and that silently destroyed the `addressable` field.
     * @JsonIgnore on an accessor does not just drop that accessor — it disables the
     * whole PROPERTY of that name, record component included. The field vanished
     * from the JSON and came back as 0, and every consumer read "nothing here to
     * fix" from a gap that reached 17 products.
     *
     * This is the round-trip trap one turn further on than the usual form. The
     * usual form is a derived accessor ADDING a field the constructor then rejects;
     * this is a derived accessor REMOVING one, which throws nothing at all. A
     * derived accessor must never share a name with a component.
     */

    /** Nothing to propose against unless some real word reaches some real product. */
    private static final int MINIMUM_ADDRESSABLE = 5;

    public static VocabularyGap none(String queryId, String query, double score, int missed) {
        return new VocabularyGap(queryId, query, score, missed, 0, List.of(), List.of(), List.of());
    }

    /** The query's own rarest word — the one the shopper cannot reach anything with. */
    @JsonIgnore
    public TermCount rarestTerm() {
        return queryTerms.stream().min(Comparator.comparingInt(TermCount::titles)).orElse(null);
    }

    @JsonIgnore
    public boolean hasAddressableGap() {
        return addressable >= MINIMUM_ADDRESSABLE && !queryTerms.isEmpty() && !catalogueVocabulary.isEmpty();
    }

    @JsonIgnore
    public String summary() {
        TermCount rarest = rarestTerm();
        if (rarest == null || catalogueVocabulary.isEmpty()) {
            return String.format("%d relevant missed, none recoverable from text", relevantMissed);
        }
        TermCount best = catalogueVocabulary.get(0);
        return String.format("\"%s\" in %d titles · best candidate \"%s\" reaches %d of %d missed at %.0fx lift",
                rarest.term(), rarest.titles(), best.term(), best.covers(), relevantMissed, best.lift());
    }
}
