package labs.augmentor.auditor.retrieve;

import labs.augmentor.auditor.model.Hit;
import java.util.List;

/** Anything the evaluator can score. One method, so a throwaway index is cheap. */
@FunctionalInterface
public interface Searcher {
    List<Hit> search(String query, int k);
}
