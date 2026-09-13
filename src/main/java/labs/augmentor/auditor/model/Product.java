package labs.augmentor.auditor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(
        String id,
        String title,
        String description,
        String category,
        Map<String, String> attrs,
        String image) {

    public Product {
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        description = description == null ? "" : description;
        category = category == null ? "" : category;
    }

    /** Everything a shopper's words could plausibly land on. */
    @JsonIgnore
    public String searchableText() {
        return (title + " " + description + " " + category + " " + String.join(" ", attrs.values()))
                .toLowerCase();
    }

    @JsonIgnore
    public boolean hasImage() {
        return image != null && !image.isBlank();
    }
}
