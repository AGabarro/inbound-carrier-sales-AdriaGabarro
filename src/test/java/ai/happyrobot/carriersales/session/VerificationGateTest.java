package ai.happyrobot.carriersales.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class VerificationGateTest {

    private final SessionStore sessions = new SessionStore();
    private final VerificationGate gate = new VerificationGate(sessions);

    @ParameterizedTest
    @EnumSource(value = SessionState.class, names = {"OTP_VERIFIED", "NEGOTIATING", "AGREED", "NEGOTIATION_FAILED", "BOOKED"})
    void openOnceTheOtpPassed(SessionState state) {
        sessions.getOrCreate("run-1").state(state);

        assertThat(gate.isVerified("run-1")).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = SessionState.class, names = {"NEW", "FMCSA_VERIFIED", "OTP_SENT", "OTP_LOCKED"})
    void closedBeforeTheOtpPassedOrWhenLocked(SessionState state) {
        sessions.getOrCreate("run-1").state(state);

        assertThat(gate.isVerified("run-1")).isFalse();
    }
}
