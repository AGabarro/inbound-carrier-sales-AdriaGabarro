package ai.happyrobot.carriersales.otp.model;

// Always HTTP 200 with every field. Never says which digits were right or what the code was.
// error: "" | MISSING_CALL_ID | MC_MISMATCH | NO_ACTIVE_OTP | INVALID_FORMAT | EXPIRED | INVALID_CODE | LOCKED
public record OtpVerifyResult(
        String error,
        String message,
        boolean verified,
        int attemptsLeft,
        boolean locked) {
}
