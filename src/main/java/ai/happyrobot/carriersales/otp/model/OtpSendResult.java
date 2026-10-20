package ai.happyrobot.carriersales.otp.model;

// Never contains the code, its hash or the full email.
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
