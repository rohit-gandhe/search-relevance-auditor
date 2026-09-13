package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One vocabulary rule. Directional: {@code from} is the shopper's word, {@code to}
 * is the catalogue's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProposedChange(String type, String from, String to, String rationale) {

    public static ProposedChange synonym(String from, String to, String rationale) {
        return new ProposedChange("synonym", from.trim().toLowerCase(), to.trim().toLowerCase(), rationale);
    }

    @JsonIgnore
    public String arrow() {
        return from + " → " + to;
    }
}
