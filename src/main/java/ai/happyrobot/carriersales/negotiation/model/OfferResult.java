package ai.happyrobot.carriersales.negotiation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

// No max_rate field on purpose: rate is our counter, the agreed rate, or 0.
public record OfferResult(
        String error,
        String message,
        String decision,
        int rate,
        int round,
        int maxRounds,
        @JsonProperty("final") boolean isFinal) {

    public static OfferResult problem(String error, String message, int round) {
        return new OfferResult(error, message, "", 0, round, 3, false);
    }
}
