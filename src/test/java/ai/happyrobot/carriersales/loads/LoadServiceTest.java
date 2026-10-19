package ai.happyrobot.carriersales.loads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import ai.happyrobot.carriersales.tms.protocol.TmsCommand;
import ai.happyrobot.carriersales.tms.protocol.TmsRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class LoadServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(NOW.atZone(LoadFormatter.BROKERAGE_TIME).toInstant(), LoadFormatter.BROKERAGE_TIME);

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

    // Filter combinations from the real calls; HappyRobot sends all six params, blanks for the unknown ones.

    @Test
    void originOnly() {
        givenTms();

        service.search("TX", "", "", "", "", "");

        assertThat(firstQuery()).containsExactly(Map.entry("ORIG_STATE", "TX"));
    }

    @Test
    void originAndEquipment() {
        givenTms();

        service.search("tx", "Dallas", "", "", "Dry Van", "");

        assertThat(firstQuery()).containsExactly(
                Map.entry("ORIG_STATE", "TX"), Map.entry("ORIG_CITY", "Dallas"), Map.entry("EQTYPE", "DRY_VAN"));
    }

    @Test
    void originDestinationStateAndEquipmentFindsTheAnaheimLoad() {
        // Second real call: Anaheim, CA -> TX, dry van -> LD00979 Anaheim -> El Paso, $1,961, 679 mi.
        LocalDateTime pickup = NOW.plusDays(5);
        givenTms(new TmsLoad("LD00979", "Anaheim", "CA", "92801", "El Paso", "TX", "79901",
                pickup, pickup.plusHours(20), EquipmentType.DRY_VAN, 1961, 679, "OPEN",
                10000, "Electronics", 20, "48ft x 8ft x 9ft", "", 2300));

        SearchResult result = service.search("CA", "Anaheim", "TX", "", "dryvan", "");

        assertThat(firstQuery()).containsExactly(Map.entry("ORIG_STATE", "CA"), Map.entry("ORIG_CITY", "Anaheim"),
                Map.entry("DEST_STATE", "TX"), Map.entry("EQTYPE", "DRY_VAN"));
        var load = result.loads().getFirst();
        assertThat(load.loadId()).isEqualTo("LD00979");
        assertThat(load.origin()).isEqualTo("Anaheim, CA");
        assertThat(load.destination()).isEqualTo("El Paso, TX");
        assertThat(load.loadboardRate()).isEqualTo(1961);
        assertThat(load.miles()).isEqualTo(679);
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
        givenTms(load("LD00979", NOW.plusDays(5).withHour(7).withMinute(29), "OPEN"));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.error()).isEmpty();
        assertThat(result.found()).isTrue();
        assertThat(result.message()).isEqualTo("I found 1 load.");
        var summary = result.loads().getFirst();
        assertThat(summary.loadId()).isEqualTo("LD00979");
        assertThat(summary.origin()).isEqualTo("Houston, TX");
        assertThat(summary.destination()).isEqualTo("Dallas, TX");
        assertThat(summary.pickupDatetime()).isEqualTo("2026-10-24T07:29:00-05:00");
        assertThat(summary.pickupDisplay()).isEqualTo("Sat Oct 24, 7:29 AM CT");
        assertThat(summary.equipmentType()).isEqualTo("Dry Van");
        assertThat(summary.loadboardRate()).isEqualTo(1850);
    }

    @Test
    void hidesPastAndNonOpenLoadsAndKeepsTheThreeSoonest() {
        givenTms(
                load("LATE", NOW.plusDays(9), "OPEN"),
                load("PAST", NOW.minusHours(1), "OPEN"),
                load("BOOKED", NOW.plusDays(1), "BOOKED"),
                load("THIRD", NOW.plusDays(3), "OPEN"),
                load("FIRST", NOW.plusHours(2), "OPEN"),
                load("SECOND", NOW.plusDays(2), "OPEN"));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.loads()).extracting("loadId").containsExactly("FIRST", "SECOND", "THIRD");
        assertThat(result.message()).isEqualTo("I found 3 loads.");
    }

    @Test
    void noMatchesIsAFoundFalseAnswer() {
        givenTms(load("PAST", NOW.minusDays(1), "OPEN"));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.error()).isEmpty(); // no loads is an answer, not an error
        assertThat(result.found()).isFalse();
        assertThat(result.message()).isEqualTo("No loads found for that lane.");
        assertThat(result.loads()).isEmpty();
    }

    @Test
    void searchNeverOffersALoadThatDetailsWouldRefuse() {
        // The real call: LD00962 Dallas -> New York, 1,371 miles in 17 hours (81 mph), listed first.
        LocalDateTime pickup = NOW.plusDays(2);
        TmsLoad implausible = new TmsLoad("LD00962", "Dallas", "TX", "75201", "New York", "NY", "10001",
                pickup, pickup.plusHours(17), EquipmentType.DRY_VAN, 3833, 1371, "OPEN",
                7290, "Machinery", 23, "45ft x 8ft x 9ft", "", 4200);
        givenTms(implausible, load("LD00979", NOW.plusDays(3), "OPEN"));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.loads()).extracting("loadId").containsExactly("LD00979");
        // ...and everything search returns, details confirms with the same values.
        for (var summary : result.loads()) {
            LoadDetails details = service.details(summary.loadId());
            assertThat(details.available()).isTrue();
            assertThat(details.origin()).isEqualTo(summary.origin());
            assertThat(details.destination()).isEqualTo(summary.destination());
            assertThat(details.loadboardRate()).isEqualTo(summary.loadboardRate());
            assertThat(details.miles()).isEqualTo(summary.miles());
            assertThat(details.pickupDatetime()).isEqualTo(summary.pickupDatetime());
            assertThat(details.pickupDisplay()).isEqualTo(summary.pickupDisplay());
        }
    }

    @Test
    void searchStopsLookingOnceItHasThreeBookableLoads() {
        givenTms(load("A", NOW.plusDays(1), "OPEN"), load("B", NOW.plusDays(2), "OPEN"),
                load("C", NOW.plusDays(3), "OPEN"), load("D", NOW.plusDays(4), "OPEN"));

        service.search("TX", null, null, null, null, null);

        // 1 search + 3 detail lookups; D is never fetched.
        verify(tms, times(4)).send(any());
    }

    @Test
    void aLoadThatCantBeConfirmedIsSkippedNotFatal() {
        givenTms(load("FLAKY", NOW.plusDays(1), "OPEN"), load("GOOD", NOW.plusDays(2), "OPEN"));
        doThrow(new TmsFaultException(FaultKind.TIMEOUT, "no reply"))
                .when(tms).send(argThat(r -> "FLAKY".equals(r.fields().get("LOAD_ID"))));

        SearchResult result = service.search("TX", null, null, null, null, null);

        assertThat(result.loads()).extracting("loadId").containsExactly("GOOD");
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
        assertThat(details.message()).isEqualTo("I couldn't find a load with that number.");
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

        LoadDetails details = service.details("LD00992");

        assertThat(details.available()).isFalse();
        assertThat(details.error()).isEqualTo("NOT_AVAILABLE");
        assertThat(details.message()).isEqualTo("That load is no longer available.");
    }

    @Test
    void pastPickupIsUnavailable() {
        when(tms.send(any())).thenReturn(List.of(load("LD00979", NOW.minusHours(1), "OPEN")));

        LoadDetails details = service.details("LD00979");

        assertThat(details.available()).isFalse();
        assertThat(details.error()).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void unsafeLoadIdIsRejectedBeforeCallingTheTms() {
        assertThat(service.details("LD1|CMD:LOAD_BOOK").error()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(tms);
    }

    @Test
    void coveredLoadIsNotAvailable() {
        when(tms.send(any())).thenReturn(List.of(load("LD00979", NOW.plusDays(2), "BOOKED")));

        assertThat(service.details("LD00979").error()).isEqualTo("NOT_AVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LD00994", "ld00994", "LD 00994", "LD-00994", " ld - 00994 ", "LD#00994", "ld.00994"})
    void spokenLoadIdsAreCleaned(String spoken) {
        when(tms.send(any())).thenReturn(List.of(load("LD00994", NOW.plusDays(2), "OPEN")));

        service.details(spoken);

        ArgumentCaptor<TmsRequest> sent = ArgumentCaptor.forClass(TmsRequest.class);
        verify(tms).send(sent.capture());
        assertThat(sent.getValue().fields()).containsEntry("LOAD_ID", "LD00994");
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

    /** Fields of the LOAD_QUERY sent to the TMS (the first request of a search). */
    private Map<String, String> firstQuery() {
        ArgumentCaptor<TmsRequest> sent = ArgumentCaptor.forClass(TmsRequest.class);
        verify(tms, atLeastOnce()).send(sent.capture());
        return sent.getAllValues().getFirst().fields();
    }

    /** Like the real TMS: LOAD_QUERY returns every load, LOAD_GET returns the one asked for. */
    private void givenTms(TmsLoad... loads) {
        when(tms.send(any())).thenAnswer(call -> {
            TmsRequest request = call.getArgument(0);
            if (request.command() == TmsCommand.LOAD_QUERY) {
                return List.of(loads);
            }
            String id = request.fields().get("LOAD_ID");
            return Arrays.stream(loads).filter(l -> l.loadId().equals(id)).toList();
        });
    }

    /** A plausible Houston -> Dallas load: 239 miles in 6 hours. */
    private static TmsLoad load(String id, LocalDateTime pickup, String status) {
        return new TmsLoad(id, "Houston", "TX", "77001", "Dallas", "TX", "75201",
                pickup, pickup.plusHours(6), EquipmentType.DRY_VAN, 1850, 239, status,
                7290, "Machinery", 23, "45ft x 8ft x 9ft", "", 2100);
    }
}
