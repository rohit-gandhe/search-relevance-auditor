package labs.augmentor.auditor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * One mapper for the whole process.
 *
 * FAIL_ON_UNKNOWN_PROPERTIES is off deliberately. Records serialise their derived
 * accessors too — isAbstention() becomes "abstention": true — and the canonical
 * constructor then rejects that field on read-back. The exception surfaces as an
 * empty catalogue rather than an error, so it is very hard to find. Derived
 * accessors also carry @JsonIgnore; this is the second half of the same belt.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise " + value.getClass(), e);
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise " + value.getClass(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("could not parse " + type.getSimpleName(), e);
        }
    }
}
