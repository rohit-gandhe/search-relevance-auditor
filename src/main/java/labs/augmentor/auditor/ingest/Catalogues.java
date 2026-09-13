package labs.augmentor.auditor.ingest;

import labs.augmentor.auditor.Config;
import labs.augmentor.auditor.model.JudgedQuery;
import labs.augmentor.auditor.model.Product;
import java.util.List;

/**
 * Named catalogues. Only one is wired: the curated WANDS slice, which is the one
 * with human relevance judgements and therefore the only one anything can be
 * measured against.
 *
 * The Shopify catalogue is deliberately absent. Category enrichment there measured
 * +0.027 net — genuinely positive — but the products are dropshipping miscellanea
 * and read as noise on screen. Real photos, wrong products.
 */
public final class Catalogues {

    public record Catalogue(String name, List<Product> products, List<JudgedQuery> queries) {
        public int size() {
            return products.size();
        }
    }

    private Catalogues() {}

    public static Catalogue active() {
        return byName(Config.get("DEMO_CATALOGUE", "wands"));
    }

    public static Catalogue byName(String name) {
        if (!"wands".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException(
                    "unknown catalogue '" + name + "' — only 'wands' carries relevance judgements");
        }
        return new Catalogue("wands", DemoSliceLoader.products(), DemoSliceLoader.judgedQueries());
    }
}
