package ai.happyrobot.carriersales.summary.model;

import java.util.List;

// Everything the backend knows about one call, shaped like the Twin tables so the workflow can write it as is.
// Never contains max_rate, the one-time code or its hash, or a full email address.
public record CallSummary(
        String error,
        String message,
        String callId,
        // "production" or "development", so the call log can leave test calls out of the analytics.
        String environment,
        String state,
        List<VerificationRow> carrierVerifications,
        List<OtpRow> otpChallenges,
        List<NegotiationRow> negotiations,
        List<OfferRow> negotiationOffers,
        List<BookingRow> bookings) {

    public record VerificationRow(String callId, String mcNumberInput, String mcNumber, boolean eligible, String reason,
                                  String carrierName, String homeBase, int powerUnits, int drivers,
                                  String safetyRating, String createdAt) {
    }

    public record OtpRow(String callId, String mcNumber, String channel, String destinationMasked, String sentAt,
                         String expiresAt, int attemptsLeft, int resendsLeft, String verifiedAt, boolean locked) {
    }

    public record NegotiationRow(String callId, String loadId, String mcNumber, int loadboardRate, int rounds,
                                 String status, int finalRate, String closedAt) {
    }

    public record OfferRow(String callId, String loadId, int round, int carrierOffer, String decision,
                           int responseRate) {
    }

    public record BookingRow(String callId, String loadId, String mcNumber, int agreedRate, String bookingReference,
                             String status, boolean transferred, String repQueue) {
    }

    public static CallSummary notFound(String callId, String environment) {
        return new CallSummary("NOT_FOUND", "No data for that call.", callId, environment, "", List.of(), List.of(),
                List.of(), List.of(), List.of());
    }
}
