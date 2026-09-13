package labs.augmentor.auditor.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import labs.augmentor.auditor.Json;
import labs.augmentor.auditor.model.JudgedQuery;
import labs.augmentor.auditor.model.Product;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads the curated slice and its qrels. Both are build artefacts of
 * scripts/build-demo-slice.py, which is deterministic — regenerating them takes
 * seconds and produces byte-identical files.
 *
 * (If you ever go back to raw WANDS: those files are TSV despite the .csv
 * extension, and the column order is not stable. Resolve columns by header name.)
 */
public final class DemoSliceLoader {

    public static final Path SLICE = Path.of("data", "demo-slice.jsonl");
    public static final Path QRELS = Path.of("data", "demo-qrels.json");

    private DemoSliceLoader() {}

    public static List<Product> products() {
        return products(SLICE);
    }

    public static List<Product> products(Path file) {
        require(file);
        List<Product> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                out.add(Json.mapper().readValue(line, Product.class));
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
        if (out.isEmpty()) {
            // Jackson catches and logs its own round-trip failures, so a broken
            // record presents as an empty catalogue rather than an error. Say so.
            throw new IllegalStateException(file + " parsed to zero products");
        }
        return out;
    }

    public static List<JudgedQuery> judgedQueries() {
        return judgedQueries(QRELS);
    }

    /** The query id is the object key, so this cannot be a straight record mapping. */
    public static List<JudgedQuery> judgedQueries(Path file) {
        require(file);
        List<JudgedQuery> out = new ArrayList<>();
        try {
            JsonNode root = Json.mapper().readTree(Files.readString(file));
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode node = e.getValue();
                Map<String, Integer> grades = Json.mapper().convertValue(
                        node.get("grades"), new TypeReference<Map<String, Integer>>() {});
                out.add(new JudgedQuery(e.getKey(), node.get("query").asText(), grades));
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
        if (out.isEmpty()) throw new IllegalStateException(file + " parsed to zero queries");
        return out;
    }

    private static void require(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalStateException(
                    file + " is missing — run ./scripts/build-demo-slice.py, or check the working directory");
        }
    }
}
