package ai.happyrobot.carriersales.call;

import java.util.EnumSet;
import java.util.Set;

public enum CallState {
    NEW,
    FMCSA_VERIFIED,
    OTP_SENT,
    OTP_VERIFIED,
    OTP_LOCKED,
    NEGOTIATING,
    AGREED,
    NEGOTIATION_FAILED;

    private static final Set<CallState> PASSED_OTP = EnumSet.of(OTP_VERIFIED, NEGOTIATING, AGREED, NEGOTIATION_FAILED);

    public boolean passedOtp() {
        return PASSED_OTP.contains(this);
    }
}
