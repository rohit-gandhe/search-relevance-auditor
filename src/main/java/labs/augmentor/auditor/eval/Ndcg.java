package labs.augmentor.auditor.eval;

import labs.augmentor.auditor.model.JudgedQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * NDCG@k with exponential gain: (2^grade - 1) / log2(rank + 1).
 *
 * The ideal ranking is taken over every product the judges actually graded, not
 * over the products we happened to retrieve — otherwise a run that returns three
 * mediocre results scores 1.0 for ranking them in the right order.
 */
public final class Ndcg {

    private Ndcg() {}

    public static double at(int k, List<String> rankedIds, JudgedQuery judged) {
        double dcg = 0.0;
        int limit = Math.min(k, rankedIds.size());
        for (int i = 0; i < limit; i++) {
            dcg += gain(judged.gradeOf(rankedIds.get(i))) / discount(i);
        }

        List<Integer> ideal = new ArrayList<>(judged.grades().values());
        ideal.sort(Comparator.reverseOrder());
        double idcg = 0.0;
        int idealLimit = Math.min(k, ideal.size());
        for (int i = 0; i < idealLimit; i++) {
            idcg += gain(ideal.get(i)) / discount(i);
        }

        // No relevant product was ever graded for this query: nothing to score
        // against, and 0.0 would read as a failure that is not one.
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double gain(int grade) {
        return Math.pow(2, grade) - 1;
    }

    private static double discount(int zeroBasedRank) {
        return Math.log(zeroBasedRank + 2) / Math.log(2);
    }
}
