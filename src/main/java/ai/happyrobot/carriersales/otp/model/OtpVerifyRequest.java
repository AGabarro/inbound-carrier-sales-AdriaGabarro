package ai.happyrobot.carriersales.otp.model;

// {"mc_number": "133655", "code": "4 8 2 9 1 3"}. The code as the carrier read it out.
public record OtpVerifyRequest(String mcNumber, String code) {
}
