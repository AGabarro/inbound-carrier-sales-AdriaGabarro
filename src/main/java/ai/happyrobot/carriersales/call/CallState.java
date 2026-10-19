package ai.happyrobot.carriersales.call;

// Where a call is in the verification flow. Load tools only work from OTP_VERIFIED on.
// NEW -> FMCSA_VERIFIED -> OTP_SENT -> OTP_VERIFIED, or OTP_SENT -> OTP_LOCKED (terminal).
public enum CallState {
    NEW,
    FMCSA_VERIFIED,
    OTP_SENT,
    OTP_VERIFIED,
    OTP_LOCKED
}
