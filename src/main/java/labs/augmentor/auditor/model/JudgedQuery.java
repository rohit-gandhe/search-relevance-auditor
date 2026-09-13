package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One WANDS query with its human grades. 2 = exact, 1 = partial, 0 = irrelevant.
 * Products the judges never saw are absent, which is not the same as a 0.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgedQuery(String queryId, String query, Map<String, Integer> grades) {

    public JudgedQuery {
        grades = grades == null ? Map.of() : Map.copyOf(grades);
    }

    public int gradeOf(String productId) {
        return grades.getOrDefault(productId, 0);
    }

    @JsonIgnore
    public Set<String> relevantIds() {
        return grades.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
