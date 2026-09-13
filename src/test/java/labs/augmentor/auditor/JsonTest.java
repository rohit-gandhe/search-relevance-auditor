package labs.augmentor.auditor;

import labs.augmentor.auditor.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jackson silently breaks record round-trips.
 *
 * A derived accessor gets serialised — isAddressable() becomes "addressable": true
 * — and the canonical constructor then rejects the unknown field on read-back. The
 * exception is caught and logged upstream, so it presents as an empty catalogue
 * rather than an error, and it costs hours. Both halves of the fix are load-bearing:
 * FAIL_ON_UNKNOWN_PROPERTIES off, and @JsonIgnore on every computed accessor.
 */
class JsonTest {

    @Test
    void productSurvivesARoundTrip() {
        Product before = new Product("109", "charleroi teal planter", "a pot",
                "planters", Map.of("color", "blue"), "w109.jpg");
        Product after = Json.read(Json.write(before), Product.class);
        assertEquals(before, after);
    }

    @Test
    void derivedAccessorsAreNotSerialised() {
        String json = Json.write(new Product("1", "a sofa", "", "seating", Map.of(), "w1.jpg"));
        assertFalse(json.contains("searchableText"), json);
        assertFalse(json.contains("hasImage"), json);
    }

    @Test
    void vocabularyGapSurvivesARoundTrip() {
        VocabularyGap before = new VocabularyGap("42", "toddler couch fold out", 0.4045,
                18, 17,
                List.of(TermCount.of("toddler", 2)),
                List.of(new TermCount("kids", 23, 17, 16.2)),
                List.of("bentley kids cotton sofa"));
        VocabularyGap after = Json.read(Json.write(before), VocabularyGap.class);
        assertEquals(before, after);
    }

    @Test
    void aDerivedAccessorDoesNotDeleteTheFieldItIsNamedAfter() {
        // isAddressable() and the `addressable` component were one Jackson property,
        // so @JsonIgnore on the accessor dropped the component too. The field came
        // back as 0 and every consumer read "nothing to fix" from a gap that reached
        // 17 products. Nothing throws; the number is just wrong.
        VocabularyGap before = new VocabularyGap("42", "toddler couch fold out", 0.4045,
                18, 17, List.of(), List.of(), List.of());
        assertTrue(Json.write(before).contains("\"addressable\":17"), Json.write(before));
        assertEquals(17, Json.read(Json.write(before), VocabularyGap.class).addressable());
    }

    @Test
    void proposalCarryingAnInstantSerialises() {
        // Without jackson-datatype-jsr310, findAndRegisterModules() finds nothing
        // and every publish fails at serialisation time.
        Proposal before = Proposal.of(VocabularyGap.none("42", "a query", 0.4, 3),
                ProposedChange.synonym("toddler", "kids", "because"));
        Proposal after = Json.read(Json.write(before), Proposal.class);
        assertEquals(before.change(), after.change());
        assertEquals(before.createdAt().toEpochMilli(), after.createdAt().toEpochMilli());
    }

    @Test
    void anUnknownFieldDoesNotEmptyTheCatalogue() {
        String json = """
            {"id":"1","title":"a sofa","description":"","category":"seating",
             "attrs":{},"image":null,"somethingAddedLater":true}""";
        assertDoesNotThrow(() -> Json.read(json, Product.class));
    }
}
