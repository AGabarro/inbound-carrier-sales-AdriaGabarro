package ai.happyrobot.carriersales.carriers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.happyrobot.carriersales.carriers.model.VerifyResult;
import ai.happyrobot.carriersales.fmcsa.FmcsaClient;
import ai.happyrobot.carriersales.fmcsa.exception.FmcsaUnavailableException;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import ai.happyrobot.carriersales.web.exception.BadRequestException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Carrier values are taken from real FMCSA replies captured on 2026-10-19. */
class CarrierServiceTest {

    private static final FmcsaCarrier SCHNEIDER = new FmcsaCarrier("SCHNEIDER NATIONAL CARRIERS INC", null,
            "Y", "A", "A", "A", "S", "GREEN BAY", "WI", 9866, 11335);
    private static final FmcsaCarrier SMALL_CONTRACT_ONLY = new FmcsaCarrier("MIKE & SON LLC", null,
            "Y", "A", "N", "A", null, "BENTON HARBOR", "MI", 2, 2);

    private final FmcsaClient fmcsa = mock(FmcsaClient.class);
    private final CarrierService service = new CarrierService(fmcsa);

    @Test
    void activeCarrierIsEligibleWithAFriendlyProfile() {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(SCHNEIDER));

        VerifyResult result = service.verify("MC 133655");

        assertThat(result.eligible()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.mcNumber()).isEqualTo("133655");
        assertThat(result.carrierName()).isEqualTo("Schneider National Carriers Inc");
        assertThat(result.homeBase()).isEqualTo("Green Bay, WI");
        assertThat(result.powerUnits()).isEqualTo(9866);
        assertThat(result.drivers()).isEqualTo(11335);
        assertThat(result.safetyRating()).isEqualTo("Satisfactory");
    }

    @Test
    void contractAuthorityAloneIsEnough() {
        when(fmcsa.findByMcNumber("650000")).thenReturn(Optional.of(SMALL_CONTRACT_ONLY));

        VerifyResult result = service.verify("650000");

        assertThat(result.eligible()).isTrue();
        assertThat(result.carrierName()).isEqualTo("Mike & Son LLC");
    }

    @Test
    void unknownMcAsksToRepeat() {
        when(fmcsa.findByMcNumber("999999")).thenReturn(Optional.empty());

        VerifyResult result = service.verify("999999");

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("NOT_FOUND");
        assertThat(result.message()).contains("read it to me again");
        assertThat(result.carrierName()).isNull();
    }

    @Test
    void inactiveCarrierEndsTheCall() {
        // MC 100000: allowedToOperate is still "Y", but the USDOT status is inactive.
        when(fmcsa.findByMcNumber("100000")).thenReturn(Optional.of(new FmcsaCarrier("C & C PRESTIGE LOGISTICS LLC",
                null, "Y", "I", "N", "N", null, "X", "TX", 1, 1)));

        VerifyResult result = service.verify("100000");

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_ACTIVE_AUTHORITY");
        assertThat(result.message()).contains("Thanks for calling HappyRobot Logistics");
        assertThat(result.carrierName()).isNull(); // no profile for a carrier we can't work with
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

    @Test
    void cleansSpokenMcNumbers() {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(SCHNEIDER));

        service.verify("mc-0133655");

        verify(fmcsa).findByMcNumber("133655");
    }

    @Test
    void mcWithoutDigitsIsRejectedBeforeCallingFmcsa() {
        assertThatThrownBy(() -> service.verify("I don't remember")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(fmcsa);
    }

    @Test
    void fmcsaDownIsPassedOnForTheErrorHandler() {
        when(fmcsa.findByMcNumber("133655")).thenThrow(new FmcsaUnavailableException("timeout"));

        assertThatThrownBy(() -> service.verify("133655")).isInstanceOf(FmcsaUnavailableException.class);
    }
}
