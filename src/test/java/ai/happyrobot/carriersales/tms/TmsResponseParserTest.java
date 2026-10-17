package ai.happyrobot.carriersales.tms;

import static ai.happyrobot.carriersales.tms.TmsCommand.LOAD_GET;
import static ai.happyrobot.carriersales.tms.TmsCommand.LOAD_QUERY;
import static org.assertj.core.api.Assertions.assertThat;

import ai.happyrobot.carriersales.tms.TmsResponse.Fault;
import ai.happyrobot.carriersales.tms.TmsResponse.FaultKind;
import ai.happyrobot.carriersales.tms.TmsResponse.Success;
import ai.happyrobot.carriersales.tms.TmsResponse.TmsError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** Fixtures in src/test/resources/tms/ are real TMS responses captured by tools/tms-probes/capture.py. */
class TmsResponseParserTest {

    // --- clean responses ---

    @Test
    void parsesSearchResults() {
        Success success = (Success) TmsResponseParser.parse(LOAD_QUERY, fixture("query-dest-tx.txt"));

        assertThat(success.loads()).hasSize(5);
        TmsLoad first = success.loads().getFirst();
        assertThat(first.loadId()).isEqualTo("LD00992");
        assertThat(first.originCity()).isEqualTo("Honolulu");
        assertThat(first.destinationState()).isEqualTo("TX");
        assertThat(first.pickupAt()).isEqualTo(LocalDateTime.of(2026, 10, 17, 7, 27, 0));
        assertThat(first.equipmentType()).isEqualTo(EquipmentType.REEFER);
        assertThat(first.rate()).isEqualTo(11147);
        assertThat(first.maxBuy()).isNull();
    }

    @Test
    void parsesFullRecord() {
        Success success = (Success) TmsResponseParser.parse(LOAD_GET, fixture("get-LD00992.txt"));

        TmsLoad load = success.loads().getFirst();
        assertThat(load.deliveryAt()).isEqualTo(LocalDateTime.of(2026, 10, 18, 4, 27, 0));
        assertThat(load.weightLbs()).isEqualTo(7290);
        assertThat(load.commodity()).isEqualTo("Machinery");
        assertThat(load.pieces()).isEqualTo(23);
        assertThat(load.dimensions()).isEqualTo("45ft x 8ft x 9ft");
        assertThat(load.notes()).isEmpty();
        assertThat(load.maxBuy()).isEqualTo(12922);
    }

    @Test
    void endOnlyMeansNoResults() {
        Success success = (Success) TmsResponseParser.parse(LOAD_QUERY, fixture("query-no-results.txt"));

        assertThat(success.loads()).isEmpty();
    }

    @Test
    void parsesErrors() {
        assertThat(TmsResponseParser.parse(LOAD_GET, fixture("get-not-found.err.txt")))
                .isEqualTo(new TmsError("NOT_FOUND", "Load not found"));
        assertThat(TmsResponseParser.parse(LOAD_QUERY, fixture("query-bad-eqtype.err.txt")))
                .isEqualTo(new TmsError("MISSING_FIELD", "Invalid EQTYPE"));
    }

    // --- real faults captured from the live TMS ---

    @Test
    void garbageBetweenFieldsIsMalformed() {
        assertFault(LOAD_QUERY, fixture("fault-malformed-1-query.txt"), FaultKind.MALFORMED);
    }

    @Test
    void garbageInsideKeyIsMalformed() {
        assertFault(LOAD_GET, fixture("fault-malformed-2-get.txt"), FaultKind.MALFORMED);
    }

    @Test
    void responseCutOnLineBoundaryIsPartial() {
        // Two complete, valid-looking records and no END: must not be read as "2 loads found".
        assertFault(LOAD_QUERY, fixture("fault-partial-1-query.txt"), FaultKind.PARTIAL);
        assertFault(LOAD_QUERY, fixture("fault-partial-2-query.txt"), FaultKind.PARTIAL);
    }

    // --- other fault shapes from the spec, built from the clean fixtures ---

    @Test
    void emptyResponseIsEmpty() {
        assertFault(LOAD_QUERY, "", FaultKind.EMPTY);
    }

    @Test
    void responseCutMidLineIsPartial() {
        String clean = fixture("query-dest-tx.txt");

        assertFault(LOAD_QUERY, clean.substring(0, 300), FaultKind.PARTIAL);
    }

    @Test
    void valueWiderThanItsFieldIsMalformed() {
        String corrupted = fixture("get-LD00992.txt").replace("RATE:11147   ", "RATE:11147    ");

        assertFault(LOAD_GET, corrupted, FaultKind.MALFORMED);
    }

    @Test
    void impossibleDateIsMalformed() {
        String corrupted = fixture("get-LD00992.txt").replace("PICKUP_DT:20261017072700", "PICKUP_DT:20260230072700");

        assertFault(LOAD_GET, corrupted, FaultKind.MALFORMED);
    }

    @Test
    void nonNumericRateIsMalformed() {
        String corrupted = fixture("get-LD00992.txt").replace("RATE:11147   ", "RATE:11X47   ");

        assertFault(LOAD_GET, corrupted, FaultKind.MALFORMED);
    }

    @Test
    void summaryLayoutIsRejectedForLoadGet() {
        assertFault(LOAD_GET, fixture("query-dest-tx.txt"), FaultKind.MALFORMED);
    }

    @Test
    void errLineAfterRecordsIsMalformed() {
        String mixed = "ERR|CODE:SERVER_ERROR|MSG:oops\r\n" + fixture("query-dest-tx.txt");

        assertFault(LOAD_QUERY, mixed, FaultKind.MALFORMED);
    }

    @Test
    void bareLineFeedIsMalformed() {
        String corrupted = fixture("query-dest-tx.txt").replaceFirst("\r\n", "\n");

        assertFault(LOAD_QUERY, corrupted, FaultKind.MALFORMED);
    }

    // --- MAX_BUY never leaks ---

    @Test
    void maxBuyIsNotInToStringNorInFaultDetails() {
        Success success = (Success) TmsResponseParser.parse(LOAD_GET, fixture("get-LD00992.txt"));
        assertThat(success.loads().getFirst().toString()).doesNotContain("12922");

        String corrupted = fixture("get-LD00992.txt").replace("MAX_BUY:12922   ", "MAX_BUY:12X22   ");
        Fault fault = (Fault) TmsResponseParser.parse(LOAD_GET, corrupted);
        assertThat(fault.detail()).contains("MAX_BUY").doesNotContain("12X22");
    }

    private static void assertFault(TmsCommand command, String raw, FaultKind expected) {
        assertThat(TmsResponseParser.parse(command, raw))
                .isInstanceOfSatisfying(Fault.class, f -> assertThat(f.kind()).isEqualTo(expected));
    }

    private static String fixture(String name) {
        try (InputStream in = TmsResponseParserTest.class.getResourceAsStream("/tms/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
