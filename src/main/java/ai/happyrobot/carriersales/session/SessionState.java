package ai.happyrobot.carriersales.session;

import java.util.EnumSet;
import java.util.Set;

public enum SessionState {
    NEW,
    FMCSA_VERIFIED,
    OTP_SENT,
    OTP_VERIFIED,
    OTP_LOCKED,
    NEGOTIATING,
    AGREED,
    NEGOTIATION_FAILED,
    BOOKED;

    private static final Set<SessionState> PASSED_OTP = EnumSet.of(OTP_VERIFIED, NEGOTIATING, AGREED, NEGOTIATION_FAILED, BOOKED);

    public boolean passedOtp() {
        return PASSED_OTP.contains(this);
    }
}
