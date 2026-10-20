package ai.happyrobot.carriersales.call;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CallGateTest {

    private final CallSessionStore sessions = new CallSessionStore();
    private final CallGate gate = new CallGate(sessions);

    @ParameterizedTest
    @EnumSource(value = CallState.class, names = {"OTP_VERIFIED", "NEGOTIATING", "AGREED", "NEGOTIATION_FAILED"})
    void openOnceTheOtpPassed(CallState state) {
        sessions.getOrCreate("run-1").state(state);

        assertThat(gate.isVerified("run-1")).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = CallState.class, names = {"NEW", "FMCSA_VERIFIED", "OTP_SENT", "OTP_LOCKED"})
    void closedBeforeTheOtpPassedOrWhenLocked(CallState state) {
        sessions.getOrCreate("run-1").state(state);

        assertThat(gate.isVerified("run-1")).isFalse();
    }
}
