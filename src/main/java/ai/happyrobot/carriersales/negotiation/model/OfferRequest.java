package ai.happyrobot.carriersales.negotiation.model;

// counter_rate as a number or text ("2,400", "$2400", "2.4k").
public record OfferRequest(String loadId, String mcNumber, String counterRate) {
}
