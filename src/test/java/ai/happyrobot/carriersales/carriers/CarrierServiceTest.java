package ai.happyrobot.carriersales.carriers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.happyrobot.carriersales.carriers.model.VerifyResult;
import ai.happyrobot.carriersales.fmcsa.FmcsaClient;
import ai.happyrobot.carriersales.fmcsa.exception.FmcsaUnavailableException;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import ai.happyrobot.carriersales.session.Session;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Carrier values are taken from real FMCSA replies captured on 2026-10-19. */
class CarrierServiceTest {

    private static final FmcsaCarrier SCHNEIDER = new FmcsaCarrier("SCHNEIDER NATIONAL CARRIERS INC", null,
            "Y", "A", "A", "A", "S", "GREEN BAY", "WI", 9866, 11335);
    private static final FmcsaCarrier SMALL_CONTRACT_ONLY = new FmcsaCarrier("MIKE & SON LLC", null,
            "Y", "A", "N", "A", null, "BENTON HARBOR", "MI", 2, 2);

    private final FmcsaClient fmcsa = mock(FmcsaClient.class);
    private final SessionStore sessions = new SessionStore();
    private final CarrierService service = new CarrierService(fmcsa, sessions);

    @Test
    void activeCarrierIsEligibleWithAFriendlyProfile() {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(SCHNEIDER));

        VerifyResult result = service.verify("MC 133655", null);

        assertThat(result.error()).isEmpty();
        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isEmpty();
        assertThat(result.mcNumber()).isEqualTo("133655");
        assertThat(result.carrierName()).isEqualTo("Schneider National Carriers Inc");
        assertThat(result.homeBase()).isEqualTo("Green Bay, WI");
        assertThat(result.powerUnits()).isEqualTo(9866);
        assertThat(result.drivers()).isEqualTo(11335);
        assertThat(result.safetyRating()).isEqualTo("Satisfactory");
    }

    // --- binding the verified MC to the call ---

    @Test
    void eligibleCarrierIsBoundToTheCall() {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(SCHNEIDER));

        service.verify("MC 133655", "run-1");

        Session session = sessions.find("run-1").orElseThrow();
        assertThat(session.state()).isEqualTo(SessionState.FMCSA_VERIFIED);
        assertThat(session.mcNumber()).isEqualTo("133655");
        assertThat(session.carrierName()).isEqualTo("Schneider National Carriers Inc");
    }

    @Test
    void notEligibleCarrierLeavesTheCallUnverified() {
        when(fmcsa.findByMcNumber("999999")).thenReturn(Optional.empty());

        service.verify("999999", "run-1");

        assertThat(sessions.find("run-1")).isEmpty();
    }

    @Test
    void aVerifiedCallCannotSwitchToAnotherMc() {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(SCHNEIDER));
        service.verify("133655", "run-1");

        VerifyResult result = service.verify("650000", "run-1");

        assertThat(result.error()).isEqualTo("MC_MISMATCH");
        assertThat(result.eligible()).isFalse();
        verify(fmcsa, never()).findByMcNumber("650000");
        assertThat(sessions.find("run-1").orElseThrow().mcNumber()).isEqualTo("133655");
    }

    @Test
    void verifyingTheSameMcAgainKeepsProgress() {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(SCHNEIDER));
        service.verify("133655", "run-1");
        sessions.find("run-1").orElseThrow().state(SessionState.OTP_VERIFIED);

        VerifyResult again = service.verify("MC 133655", "run-1");

        assertThat(again.eligible()).isTrue();
        assertThat(sessions.find("run-1").orElseThrow().state()).isEqualTo(SessionState.OTP_VERIFIED);
    }

    @Test
    void withoutCallIdNothingIsRemembered() {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(SCHNEIDER));

        assertThat(service.verify("133655", null).eligible()).isTrue();
        assertThat(service.verify("133655", " ").eligible()).isTrue();
    }

    // --- the authority rule ---

    @Test
    void contractAuthorityAloneIsEnough() {
        when(fmcsa.findByMcNumber("650000")).thenReturn(Optional.of(SMALL_CONTRACT_ONLY));

        VerifyResult result = service.verify("650000", null);

        assertThat(result.eligible()).isTrue();
        assertThat(result.carrierName()).isEqualTo("Mike & Son LLC");
    }

    @Test
    void unknownMcAsksToRepeat() {
        when(fmcsa.findByMcNumber("999999")).thenReturn(Optional.empty());

        VerifyResult result = service.verify("999999", null);

        assertThat(result.error()).isEqualTo("NOT_FOUND");
        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("NOT_FOUND");
        assertThat(result.message()).contains("read it to me again");
        assertThat(result.carrierName()).isEmpty();
    }

    @Test
    void inactiveCarrierEndsTheCall() {
        // MC 100000: allowedToOperate is still "Y", but the USDOT status is inactive.
        when(fmcsa.findByMcNumber("100000")).thenReturn(Optional.of(new FmcsaCarrier("C & C PRESTIGE LOGISTICS LLC",
                null, "Y", "I", "N", "N", null, "X", "TX", 1, 1)));

        VerifyResult result = service.verify("100000", null);

        assertThat(result.error()).isEqualTo("NOT_ELIGIBLE");
        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_ACTIVE_AUTHORITY");
        assertThat(result.message()).contains("Thanks for calling HappyRobot Logistics");
        assertThat(result.carrierName()).isEmpty(); // no profile for a carrier we can't work with
        assertThat(result.powerUnits()).isZero();
    }

    @Test
    void eachAuthorityRuleIsRequired() {
        // MC 550000: active USDOT, but no for-hire authority at all.
        assertThat(CarrierService.hasActiveOperatingAuthority(
                new FmcsaCarrier("A", null, "Y", "A", "N", "N", null, null, null, 0, 0))).isFalse();
        // MC 750000: out of service, not allowed to operate.
        assertThat(CarrierService.hasActiveOperatingAuthority(
                new FmcsaCarrier("B", null, "N", "I", "I", "I", null, null, null, 0, 0))).isFalse();
        // Allowed and licensed, but USDOT status inactive.
        assertThat(CarrierService.hasActiveOperatingAuthority(
                new FmcsaCarrier("C", null, "Y", "I", "A", "A", null, null, null, 0, 0))).isFalse();
        assertThat(CarrierService.hasActiveOperatingAuthority(SCHNEIDER)).isTrue();
    }

    // Regression: formats seen in real calls ("MC 133655", "m c 133655") and other ways of saying it.
    @ParameterizedTest
    @ValueSource(strings = {"133655", "MC133655", "MC 133655", "m c 133655", "MC-133655", "mc# 133655", "mc-0133655"})
    void cleansSpokenMcNumbers(String spoken) {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(SCHNEIDER));

        VerifyResult result = service.verify(spoken, null);

        verify(fmcsa).findByMcNumber("133655");
        assertThat(result.eligible()).isTrue();
        assertThat(result.carrierName()).isEqualTo("Schneider National Carriers Inc");
    }

    @Test
    void mcWithoutDigitsIsRejectedBeforeCallingFmcsa() {
        for (String spoken : new String[] {"I don't remember", "", null}) {
            VerifyResult result = service.verify(spoken, null);

            assertThat(result.error()).isEqualTo("INVALID_REQUEST");
            assertThat(result.eligible()).isFalse();
        }
        verifyNoInteractions(fmcsa);
    }

    @Test
    void fmcsaDownIsAnFmcsaUnavailableAnswer() {
        when(fmcsa.findByMcNumber("133655")).thenThrow(new FmcsaUnavailableException("timeout"));

        VerifyResult result = service.verify("133655", null);

        assertThat(result.error()).isEqualTo("FMCSA_UNAVAILABLE");
        assertThat(result.message()).contains("try again in a few minutes");
    }
}
