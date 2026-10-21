package ai.happyrobot.carriersales.session;

import java.time.Instant;

public record VerificationAttempt(
        String mcNumberInput,
        String mcNumber,
        boolean eligible,
        String reason,
        String carrierName,
        String homeBase,
        int powerUnits,
        int drivers,
        String safetyRating,
        Instant at) {
}
