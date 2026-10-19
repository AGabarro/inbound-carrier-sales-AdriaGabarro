package ai.happyrobot.carriersales.otp.model;

// {"mc_number": "133655", "channel": "email"}. Both optional: the MC is already bound to the call.
// There is deliberately no email field: codes only go to the contact on file.
public record OtpSendRequest(String mcNumber, String channel) {
}
