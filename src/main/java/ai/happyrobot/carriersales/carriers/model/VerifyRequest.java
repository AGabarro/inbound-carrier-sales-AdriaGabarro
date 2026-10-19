package ai.happyrobot.carriersales.carriers.model;

// {"mc_number": "MC 133655"}: the number as the carrier said it; the service cleans it.
public record VerifyRequest(String mcNumber) {
}
