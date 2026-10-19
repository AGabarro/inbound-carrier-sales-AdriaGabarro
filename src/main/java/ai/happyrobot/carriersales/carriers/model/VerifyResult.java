package ai.happyrobot.carriersales.carriers.model;

// eligible is what the agent branches on. reason says how to go on when not eligible:
// NOT_FOUND -> ask for the MC again, NO_ACTIVE_AUTHORITY -> say message and end the call.
// Profile fields are only set when eligible, for a short personal remark.
public record VerifyResult(
        boolean eligible,
        String reason,
        String message,
        String mcNumber,
        String carrierName,
        String dbaName,
        String homeBase,
        Integer powerUnits,
        Integer drivers,
        String safetyRating) {

    public static VerifyResult notEligible(String reason, String message, String mcNumber) {
        return new VerifyResult(false, reason, message, mcNumber, null, null, null, null, null, null);
    }
}
