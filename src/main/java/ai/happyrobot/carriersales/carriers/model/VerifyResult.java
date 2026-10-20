package ai.happyrobot.carriersales.carriers.model;

// reason: NOT_FOUND -> ask again, NO_ACTIVE_AUTHORITY -> end the call. Profile only when eligible.
public record VerifyResult(
        String error,
        String message,
        boolean eligible,
        String reason,
        String mcNumber,
        String carrierName,
        String dbaName,
        String homeBase,
        int powerUnits,
        int drivers,
        String safetyRating) {

    public static VerifyResult notEligible(String error, String reason, String message, String mcNumber) {
        return new VerifyResult(error, message, false, reason, mcNumber, "", "", "", 0, 0, "");
    }
}
