package ai.happyrobot.carriersales.otp.model;

// Always HTTP 200 with every field. Never contains the code, its hash or the full email.
// error: "" | MISSING_CALL_ID | MC_NOT_VERIFIED | MC_MISMATCH | ALREADY_VERIFIED | LOCKED | RESEND_LIMIT
//        | NO_CONTACT_ON_FILE | DELIVERY_FAILED
public record OtpSendResult(
        String error,
        String message,
        boolean sent,
        String channel,
        String destinationMasked,
        int expiresInSec,
        int resendsLeft) {

    public static OtpSendResult notSent(String error, String message, int resendsLeft) {
        return new OtpSendResult(error, message, false, "email", "", 0, resendsLeft);
    }
}
