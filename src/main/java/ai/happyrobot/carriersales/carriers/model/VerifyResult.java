package ai.happyrobot.carriersales.carriers.model;

// Always HTTP 200 with every field present (HappyRobot learns the fields from one test call).
// error: "" when eligible, else INVALID_REQUEST | NOT_FOUND | NOT_ELIGIBLE | FMCSA_UNAVAILABLE.
// reason: the FMCSA detail when not eligible (NOT_FOUND -> ask again, NO_ACTIVE_AUTHORITY -> end the call).
// Profile fields are only filled when eligible, for a short personal remark.
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
