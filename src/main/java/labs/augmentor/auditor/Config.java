package labs.augmentor.auditor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads .env once, from the working directory. Nothing here expires, so the file
 * is copied between checkouts rather than regenerated; see .env.template for the
 * non-secret half if it is ever actually lost.
 */
public final class Config {

    private static final Map<String, String> VALUES = load();

    private Config() {}

    private static Map<String, String> load() {
        Map<String, String> out = new HashMap<>();
        Path env = Path.of(".env");
        if (Files.exists(env)) {
            try {
                for (String raw : Files.readAllLines(env)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    // Trailing comments: AUTO_APPLY_THRESHOLD=0.80  # measured
                    int hash = value.indexOf(" #");
                    if (hash >= 0) value = value.substring(0, hash).trim();
                    if (value.length() > 1
                            && ((value.startsWith("\"") && value.endsWith("\""))
                             || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    out.put(key, value);
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not read .env", e);
            }
        }
        // A real environment variable wins, so CI and one-off overrides work.
        System.getenv().forEach(out::put);
        return out;
    }

    public static String get(String key, String fallback) {
        String v = VALUES.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    public static String require(String key) {
        String v = VALUES.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(key + " is not set; copy .env into this directory");
        }
        return v;
    }

    public static boolean has(String key) {
        String v = VALUES.get(key);
        return v != null && !v.isBlank();
    }

    public static double getDouble(String key, double fallback) {
        String v = get(key, null);
        return v == null ? fallback : Double.parseDouble(v);
    }

    public static int getInt(String key, int fallback) {
        String v = get(key, null);
        return v == null ? fallback : Integer.parseInt(v);
    }
}
