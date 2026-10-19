package ai.happyrobot.carriersales.loads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.happyrobot.carriersales.loads.model.LoadDetails;
import ai.happyrobot.carriersales.loads.model.SearchResult;
import ai.happyrobot.carriersales.tms.TmsClient;
import ai.happyrobot.carriersales.tms.exception.FaultKind;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.EquipmentType;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import ai.happyrobot.carriersales.tms.protocol.TmsRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LoadServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(NOW.atZone(LoadService.BROKERAGE_TIME).toInstant(), LoadService.BROKERAGE_TIME);

    private final TmsClient tms = mock(TmsClient.class);
    private final LoadService service = new LoadService(tms, FIXED_CLOCK);

    // --- search ---

    @Test
    void turnsSpokenFiltersIntoTmsQuery() {
        when(tms.send(any())).thenReturn(List.of());

        service.search(" tx ", null, null, "Dallas", "dry van", "2026-10-20");

        ArgumentCaptor<TmsRequest> sent = ArgumentCaptor.forClass(TmsRequest.class);
        verify(tms).send(sent.capture());
        assertThat(sent.getValue().fields()).containsExactly(
                Map.entry("ORIG_STATE", "TX"),
                Map.entry("DEST_CITY", "Dallas"),
                Map.entry("EQTYPE", "DRY_VAN"),
                Map.entry("PICKUP_DATE", "20261020"));
    }

    @Test
    void blankFiltersAreIgnoredAndAllBlankAsksForOne() {
        // HappyRobot sends all six params, empty when the carrier didn't mention them.
        SearchResult result = service.search("", " ", "", "", "", "");

        assertThat(result.error()).isEqualTo("INVALID_REQUEST");
        assertThat(result.found()).isFalse();
        assertThat(result.loads()).isEmpty();
        assertThat(result.message()).contains("pick up or deliver");
        verifyNoInteractions(tms);
    }

    @Test
    void unknownEquipmentIsAnInvalidRequestAnswer() {
        SearchResult result = service.search("TX", "", "", "", "tanker", "");

        assertThat(result.error()).isEqualTo("INVALID_REQUEST");
        assertThat(result.message()).contains("dry van, reefer, flatbed or power only");
        verifyNoInteractions(tms);
    }

    @Test
    void tmsDownIsATmsUnavailableAnswer() {
        when(tms.send(any())).thenThrow(new TmsFaultException(FaultKind.TIMEOUT, "no reply"));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.error()).isEqualTo("TMS_UNAVAILABLE");
        assertThat(result.found()).isFalse();
    }

    @Test
    void tmsRejectingTheSearchIsAnInvalidRequestAnswer() {
        when(tms.send(any())).thenThrow(new TmsErrorException("MISSING_FIELD", "Invalid EQTYPE"));

        assertThat(service.search("TX", null, null, null, null, null).error()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void mapsResultsForSpeech() {
        when(tms.send(any())).thenReturn(List.of(load("LD00979", NOW.plusDays(5).withHour(7).withMinute(29), "OPEN")));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.error()).isEmpty();
        assertThat(result.found()).isTrue();
        assertThat(result.message()).isEqualTo("I found 1 load.");
        var summary = result.loads().getFirst();
        assertThat(summary.loadId()).isEqualTo("LD00979");
        assertThat(summary.origin()).isEqualTo("Houston, TX");
        assertThat(summary.destination()).isEqualTo("Dallas, TX");
        assertThat(summary.pickupDatetime()).isEqualTo("Oct 24, 7:29 AM");
        assertThat(summary.equipmentType()).isEqualTo("Dry Van");
        assertThat(summary.loadboardRate()).isEqualTo(1850);
    }

    @Test
    void hidesPastAndNonOpenLoadsAndKeepsTheThreeSoonest() {
        when(tms.send(any())).thenReturn(List.of(
                load("LATE", NOW.plusDays(9), "OPEN"),
                load("PAST", NOW.minusHours(1), "OPEN"),
                load("BOOKED", NOW.plusDays(1), "BOOKED"),
                load("THIRD", NOW.plusDays(3), "OPEN"),
                load("FIRST", NOW.plusHours(2), "OPEN"),
                load("SECOND", NOW.plusDays(2), "OPEN")));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.loads()).extracting("loadId").containsExactly("FIRST", "SECOND", "THIRD");
        assertThat(result.message()).isEqualTo("I found 3 loads.");
    }

    @Test
    void noMatchesIsAFoundFalseAnswer() {
        when(tms.send(any())).thenReturn(List.of(load("PAST", NOW.minusDays(1), "OPEN")));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.error()).isEmpty(); // no loads is an answer, not an error
        assertThat(result.found()).isFalse();
        assertThat(result.message()).isEqualTo("No loads found for that lane.");
        assertThat(result.loads()).isEmpty();
    }

    // --- details ---

    @Test
    void mapsFullDetailsForThePitch() {
        when(tms.send(any())).thenReturn(List.of(load("LD00979", NOW.plusDays(5), "OPEN")));

        LoadDetails details = service.details(" ld00979 ");

        assertThat(details.error()).isEmpty();
        assertThat(details.available()).isTrue();
        assertThat(details.loadId()).isEqualTo("LD00979");
        assertThat(details.weight()).isEqualTo(7290);
        assertThat(details.commodityType()).isEqualTo("Machinery");
        assertThat(details.numOfPieces()).isEqualTo(23);
        assertThat(details.dimensions()).isEqualTo("45ft x 8ft x 9ft");
    }

    @Test
    void unknownLoadIsUnavailableNotAnError() {
        when(tms.send(any())).thenThrow(new TmsErrorException("NOT_FOUND", "Load not found"));

        LoadDetails details = service.details("LD99999");

        assertThat(details.error()).isEqualTo("NOT_FOUND");
        assertThat(details.available()).isFalse();
        assertThat(details.message()).isEqualTo("That load isn't available.");
        assertThat(details.loadId()).isEmpty();
        assertThat(details.loadboardRate()).isZero();
    }

    @Test
    void implausibleLoadIsUnavailable() {
        // LD00992 from the real TMS: Honolulu -> Amarillo, 3,495 miles in 21 hours.
        LocalDateTime pickup = NOW.plusDays(1);
        TmsLoad honolulu = new TmsLoad("LD00992", "Honolulu", "HI", "96813", "Amarillo", "TX", "79101",
                pickup, pickup.plusHours(21), EquipmentType.REEFER, 11147, 3495, "OPEN",
                7290, "Machinery", 23, "45ft x 8ft x 9ft", "", 12922);
        when(tms.send(any())).thenReturn(List.of(honolulu));

        assertThat(service.details("LD00992").available()).isFalse();
    }

    @Test
    void pastPickupIsUnavailable() {
        when(tms.send(any())).thenReturn(List.of(load("LD00979", NOW.minusHours(1), "OPEN")));

        assertThat(service.details("LD00979").available()).isFalse();
    }

    @Test
    void unsafeLoadIdIsRejectedBeforeCallingTheTms() {
        assertThat(service.details("LD1|CMD:LOAD_BOOK").error()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(tms);
    }

    @Test
    void emptyLoadIdAsksWhichLoad() {
        LoadDetails details = service.details("");

        assertThat(details.error()).isEqualTo("INVALID_REQUEST");
        assertThat(details.message()).isEqualTo("Which load number are you calling about?");
        verifyNoInteractions(tms);
    }

    @Test
    void tmsDownIsATmsUnavailableDetailsAnswer() {
        when(tms.send(any())).thenThrow(new TmsFaultException(FaultKind.TIMEOUT, "no reply"));

        LoadDetails details = service.details("LD00979");

        assertThat(details.error()).isEqualTo("TMS_UNAVAILABLE");
        assertThat(details.available()).isFalse();
    }

    /** A plausible Houston -> Dallas load: 239 miles in 6 hours. */
    private static TmsLoad load(String id, LocalDateTime pickup, String status) {
        return new TmsLoad(id, "Houston", "TX", "77001", "Dallas", "TX", "75201",
                pickup, pickup.plusHours(6), EquipmentType.DRY_VAN, 1850, 239, status,
                7290, "Machinery", 23, "45ft x 8ft x 9ft", "", 2100);
    }
}
