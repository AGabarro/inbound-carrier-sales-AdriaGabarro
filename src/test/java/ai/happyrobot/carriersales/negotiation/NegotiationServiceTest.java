package ai.happyrobot.carriersales.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.happyrobot.carriersales.loads.LoadService;
import ai.happyrobot.carriersales.loads.model.LoadLookup;
import ai.happyrobot.carriersales.negotiation.model.OfferResult;
import ai.happyrobot.carriersales.session.Session;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NegotiationServiceTest {

    private static final String CALL = "run-1";
    // LD00979, posted $1,961. MAX_BUY $2,300 must never reach a response, a sentence or a log line.
    private static final int MAX = 2300;
    private static final TmsLoad LOAD = new TmsLoad("LD00979", "Anaheim", "CA", "92801", "El Paso", "TX", "79901",
            LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(3), "DRY_VAN", 1961, 679, "OPEN",
            36031, "Electronics", 19, "44ft x 8ft x 9ft", "", MAX);

    private final SessionStore sessions = new SessionStore();
    private final LoadService loads = mock(LoadService.class);
    private final NegotiationService service = new NegotiationService(sessions, loads, new Random(3));

    @BeforeEach
    void verifiedCall() {
        Session session = sessions.getOrCreate(CALL);
        session.state(SessionState.OTP_VERIFIED);
        session.mcNumber("133655");
        when(loads.findBookable(any())).thenReturn(LoadLookup.found(LOAD));
    }

    @Test
    void refusedBeforeTheOtpPassed() {
        sessions.getOrCreate("run-2").state(SessionState.FMCSA_VERIFIED);

        assertThat(service.offer("run-2", "", "LD00979", "2000").error()).isEqualTo("OTP_NOT_VERIFIED");
        assertThat(service.offer("unknown", "", "LD00979", "2000").error()).isEqualTo("OTP_NOT_VERIFIED");
        assertThat(service.offer(null, "", "LD00979", "2000").error()).isEqualTo("MISSING_CALL_ID");
        verifyNoInteractions(loads);
    }

    @Test
    void acceptAtThePostedRateClosesWithAnAgreedRate() {
        OfferResult result = service.offer(CALL, "133655", "LD00979", "1961");

        assertThat(result.decision()).isEqualTo("accept");
        assertThat(result.rate()).isEqualTo(1961);
        assertThat(result.isFinal()).isTrue();
        Session session = sessions.find(CALL).orElseThrow();
        assertThat(session.state()).isEqualTo(SessionState.AGREED);
        assertThat(session.negotiation().agreedRate()).isEqualTo(1961);
    }

    @Test
    void threeRoundsPerCallThenTheCallIsDone() {
        assertThat(service.offer(CALL, "", "LD00979", "2800").decision()).isEqualTo("counter");
        assertThat(service.offer(CALL, "", "LD00979", "2700").decision()).isEqualTo("counter");
        OfferResult third = service.offer(CALL, "", "LD00979", "2600");

        assertThat(third.decision()).isEqualTo("reject");
        assertThat(third.round()).isEqualTo(3);
        assertThat(third.isFinal()).isTrue();
        assertThat(sessions.find(CALL).orElseThrow().state()).isEqualTo(SessionState.NEGOTIATION_FAILED);
        assertThat(service.offer(CALL, "", "LD00979", "2000").error()).isEqualTo("NEGOTIATION_CLOSED");
    }

    @Test
    void cannotResetTheRoundsBySwitchingLoads() {
        service.offer(CALL, "", "LD00979", "2800");

        OfferResult other = service.offer(CALL, "", "LD01024", "3000");

        assertThat(other.error()).isEqualTo("LOAD_MISMATCH");
        assertThat(other.message()).contains("LD00979");
        assertThat(service.offer(CALL, "", "ld-00979", "2700").round()).isEqualTo(2); // same load, spoken differently
    }

    @Test
    void afterTheAgreedLoadFellThroughANewLoadGetsFreshRounds() {
        service.offer(CALL, "", "LD00979", "1961");
        sessions.find(CALL).orElseThrow().releaseNegotiation(); // booking: LOAD_TAKEN or BOOKING_FAILED

        OfferResult next = service.offer(CALL, "", "LD01024", "2800");

        assertThat(next.error()).isEmpty();
        assertThat(next.round()).isEqualTo(1);
    }

    @Test
    void maxRateIsReadFromTheTmsOnlyOnce() {
        service.offer(CALL, "", "LD00979", "2800");
        service.offer(CALL, "", "LD00979", "2700");

        verify(loads, times(1)).findBookable(any());
    }

    @Test
    void unbookableLoadStartsNoNegotiation() {
        when(loads.findBookable(any())).thenReturn(LoadLookup.problem("NOT_AVAILABLE", "That load is no longer available."));

        OfferResult result = service.offer(CALL, "", "LD00992", "2000");

        assertThat(result.error()).isEqualTo("NOT_AVAILABLE");
        assertThat(sessions.find(CALL).orElseThrow().negotiation()).isNull();
        assertThat(sessions.find(CALL).orElseThrow().state()).isEqualTo(SessionState.OTP_VERIFIED);
    }

    @Test
    void outrageousAskIsToldItIsWellAbove() {
        OfferResult result = service.offer(CALL, "", "LD00979", "3500");

        assertThat(NegotiationService.FIRM).anySatisfy(firm -> assertThat(result.message()).startsWith(firm));
    }

    @Test
    void wordingVariesBetweenCalls() {
        Set<String> counters = new HashSet<>();
        for (int call = 0; call < 40; call++) {
            sessions.getOrCreate("vary-" + call).state(SessionState.OTP_VERIFIED);
            counters.add(service.offer("vary-" + call, "", "LD00979", "2290").message().replaceAll("\\$[\\d,]+", "AMOUNT"));
        }
        assertThat(counters).hasSizeGreaterThan(3);
    }

    @Test
    void anotherMcIsRefused() {
        assertThat(service.offer(CALL, "650000", "LD00979", "2000").error()).isEqualTo("MC_MISMATCH");
    }

    @Test
    void invalidRateAsksAgainWithoutUsingARound() {
        assertThat(service.offer(CALL, "", "LD00979", "a lot").error()).isEqualTo("INVALID_REQUEST");
        assertThat(service.offer(CALL, "", "LD00979", "2800").round()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"2400, 2400", "'2,400', 2400", "$2400, 2400", "'$ 2,400', 2400", "2.4k, 2400", "2K, 2000"})
    void understandsSpokenRates(String spoken, int dollars) {
        assertThat(NegotiationService.parseRate(spoken)).contains(dollars);
    }

    @Test
    void maxRateNeverReachesTheAgentOrTheLogs(CapturedOutput output) {
        Random asks = new Random(11);
        for (int call = 0; call < 500; call++) {
            String callId = "sim-" + call;
            sessions.getOrCreate(callId).state(SessionState.OTP_VERIFIED);
            for (int round = 0; round < 3; round++) {
                int ask = 1900 + asks.nextInt(1500);
                if (ask == MAX) {
                    continue; // the carrier saying the number themselves reveals nothing
                }
                OfferResult result = service.offer(callId, "", "LD00979", String.valueOf(ask));
                assertThat(result.toString()).doesNotContain("2300", "2,300");
                if (result.isFinal()) {
                    break;
                }
            }
            assertThat(sessions.find(callId).orElseThrow().negotiation().toString()).doesNotContain("2300");
        }
        // Only the negotiation lines: a process id or timestamp elsewhere could contain those digits by chance.
        assertThat(output.getOut().lines().filter(line -> line.contains("Negotiation call")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line.substring(line.indexOf("Negotiation call")))
                        .doesNotContain("2300").doesNotContain("2,300"));
    }
}
