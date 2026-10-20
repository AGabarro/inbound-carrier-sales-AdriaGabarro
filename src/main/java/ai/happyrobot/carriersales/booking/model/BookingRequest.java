package ai.happyrobot.carriersales.booking.model;

// No rate on purpose: the booking always uses the rate agreed in the negotiation.
public record BookingRequest(String loadId, String mcNumber) {
}
