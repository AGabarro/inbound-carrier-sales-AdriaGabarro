package ai.happyrobot.carriersales.otp.model;

// What the OTP Sender workflow needs to email the code. The only object that ever holds the code in clear.
public record OtpDelivery(
        String callId,
        String mcNumber,
        String carrierName,
        String email,
        String code,
        int expiresInMin) {

    @Override
    public String toString() {
        return "OtpDelivery[callId=" + callId + ", mc=" + mcNumber + ", code=***]";
    }
}
