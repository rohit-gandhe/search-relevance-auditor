package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A candidate word, with the three numbers that say whether it is worth anything.
 *
 *   titles    how many product titles in the whole catalogue use it
 *   covers    how many of the relevant products this query missed it would reach
 *   lift      how much more often it appears in those missed products than in the
 *             catalogue at large
 *
 * Coverage alone is not a signal. Every relevant product this query missed is a
 * chair, so "chairs" covers 55 of 58 of them — and is no more the catalogue's word
 * for "teal" than "furniture" would be. Lift is what separates a word that
 * describes this specific set from a word that describes furniture.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TermCount(String term, int titles, int covers, double lift) implements Comparable<TermCount> {

    public static TermCount of(String term, int titles) {
        return new TermCount(term, titles, 0, 0);
    }

    @JsonIgnore
    public boolean isDiscriminating() {
        return lift >= 3.0;
    }

    /**
     * Coverage first, lift as the tiebreak — and lift as a filter, not a ranking.
     * Ranking by lift alone promotes the rarest words in the set: "dominic" and
     * "bjorn" score 12x because they are product names appearing twice. What is
     * wanted is the word that reaches the most missed products while still being
     * about those products rather than about furniture.
     */
    @Override
    public int compareTo(TermCount other) {
        int byCoverage = Integer.compare(other.covers, covers);
        return byCoverage != 0 ? byCoverage : Double.compare(other.lift, lift);
    }
}
