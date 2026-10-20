package ai.happyrobot.carriersales.tms.protocol;

import static ai.happyrobot.carriersales.tms.TmsFixtures.fixture;
import static ai.happyrobot.carriersales.tms.protocol.TmsCommand.LOAD_GET;
import static ai.happyrobot.carriersales.tms.protocol.TmsCommand.LOAD_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ai.happyrobot.carriersales.tms.exception.FaultKind;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.TmsBooking;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TmsResponseParserTest {

    // --- clean responses ---

    @Test
    void parsesSearchResults() {
        List<TmsLoad> loads = TmsResponseParser.parse(LOAD_QUERY, fixture("query-dest-tx.txt"));

        assertThat(loads).hasSize(5);
        TmsLoad first = loads.getFirst();
        assertThat(first.loadId()).isEqualTo("LD00992");
        assertThat(first.originCity()).isEqualTo("Honolulu");
        assertThat(first.destinationState()).isEqualTo("TX");
        assertThat(first.pickupAt()).isEqualTo(LocalDateTime.of(2026, 10, 17, 7, 27, 0));
        assertThat(first.equipmentType()).isEqualTo("REEFER");
        assertThat(first.rate()).isEqualTo(11147);
        assertThat(first.maxBuy()).isNull();
    }

    @Test
    void parsesFullRecord() {
        TmsLoad load = TmsResponseParser.parse(LOAD_GET, fixture("get-LD00992.txt")).getFirst();

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
        assertThat(TmsResponseParser.parse(LOAD_QUERY, fixture("query-no-results.txt"))).isEmpty();
    }

    @Test
    void parsesErrors() {
        TmsErrorException notFound = catchThrowableOfType(TmsErrorException.class,
                () -> TmsResponseParser.parse(LOAD_GET, fixture("get-not-found.err.txt")));
        assertThat(notFound.code()).isEqualTo("NOT_FOUND");
        assertThat(notFound.tmsMessage()).isEqualTo("Load not found");

        TmsErrorException badEquipment = catchThrowableOfType(TmsErrorException.class,
                () -> TmsResponseParser.parse(LOAD_QUERY, fixture("query-bad-eqtype.err.txt")));
        assertThat(badEquipment.code()).isEqualTo("MISSING_FIELD");
        assertThat(badEquipment.tmsMessage()).isEqualTo("Invalid EQTYPE");
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
    void errLineFollowedByRecordsIsMalformed() {
        String mixed = "ERR|CODE:SERVER_ERROR|MSG:oops\r\n" + fixture("query-dest-tx.txt");

        assertFault(LOAD_QUERY, mixed, FaultKind.MALFORMED);
    }

    @Test
    void bareLineFeedIsMalformed() {
        String corrupted = fixture("query-dest-tx.txt").replaceFirst("\r\n", "\n");

        assertFault(LOAD_QUERY, corrupted, FaultKind.MALFORMED);
    }

    // --- equipment types ---

    @Test
    void equipmentTypesWeDontKnowYetDoNotBreakASearch() {
        // 26% of real loads are STEP_DECK, which the brief never mentions; LOWBOY stands for a future type.
        String mixed = fixture("query-dest-tx.txt")
                .replaceFirst("EQTYPE:REEFER    ", "EQTYPE:STEP_DECK ")
                .replaceFirst("EQTYPE:DRY_VAN   ", "EQTYPE:LOWBOY    ");

        List<TmsLoad> loads = TmsResponseParser.parse(LOAD_QUERY, mixed);

        assertThat(loads).hasSize(5);
        assertThat(loads).extracting(TmsLoad::equipmentType).contains("STEP_DECK", "LOWBOY");
    }

    @Test
    void malformedEquipmentIsStillAFault() {
        String corrupted = fixture("get-LD00992.txt").replace("EQTYPE:REEFER    ", "EQTYPE:reefer    ");

        assertFault(LOAD_GET, corrupted, FaultKind.MALFORMED);
    }

    // --- booking replies (real, from the approved test on LD00962) ---

    @Test
    void parsesABooking() {
        TmsBooking booking = TmsResponseParser.parseBooking(fixture("book-success.txt"));

        assertThat(booking.loadId()).isEqualTo("LD00962");
        assertThat(booking.bookingRef()).isEqualTo("GFZKYZRT3K4JOEAE");
        assertThat(booking.status()).isEqualTo("PENDING");
        assertThat(booking.bookedAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 3, 37, 45));
    }

    @Test
    void alreadyBookedIsAnError() {
        TmsErrorException e = catchThrowableOfType(TmsErrorException.class,
                () -> TmsResponseParser.parseBooking(fixture("book-already-booked.err.txt")));

        assertThat(e.code()).isEqualTo("ALREADY_BOOKED");
    }

    @Test
    void bookingRepliesAreCheckedStrictly() {
        String clean = fixture("book-success.txt");

        assertThatThrownBy(() -> TmsResponseParser.parseBooking(clean.replace("STATUS:PENDING ", "STATUS:PENDING  ")))
                .isInstanceOfSatisfying(TmsFaultException.class, e -> assertThat(e.kind()).isEqualTo(FaultKind.MALFORMED));
        assertThatThrownBy(() -> TmsResponseParser.parseBooking("END\r\n"))
                .isInstanceOfSatisfying(TmsFaultException.class, e -> assertThat(e.kind()).isEqualTo(FaultKind.MALFORMED));
        assertThatThrownBy(() -> TmsResponseParser.parseBooking(clean.replace("END\r\n", "")))
                .isInstanceOfSatisfying(TmsFaultException.class, e -> assertThat(e.kind()).isEqualTo(FaultKind.PARTIAL));
    }

    @Test
    void bookingIsNotParsedAsLoads() {
        assertThatThrownBy(() -> TmsResponseParser.parse(TmsCommand.LOAD_BOOK, fixture("book-success.txt")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- MAX_BUY never leaks ---

    @Test
    void maxBuyIsNotInToStringNorInFaultDetails() {
        TmsLoad load = TmsResponseParser.parse(LOAD_GET, fixture("get-LD00992.txt")).getFirst();
        assertThat(load.toString()).doesNotContain("12922");

        String corrupted = fixture("get-LD00992.txt").replace("MAX_BUY:12922   ", "MAX_BUY:12X22   ");
        assertThatThrownBy(() -> TmsResponseParser.parse(LOAD_GET, corrupted))
                .isInstanceOf(TmsFaultException.class)
                .hasMessageContaining("MAX_BUY")
                .hasMessageNotContaining("12X22");
    }

    private static void assertFault(TmsCommand command, String raw, FaultKind expected) {
        assertThatThrownBy(() -> TmsResponseParser.parse(command, raw))
                .isInstanceOfSatisfying(TmsFaultException.class, e -> assertThat(e.kind()).isEqualTo(expected));
    }
}
