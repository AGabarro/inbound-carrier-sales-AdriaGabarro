package ai.happyrobot.carriersales.fmcsa.model;

// The FMCSA carrier fields we use: the operating-authority check plus a few profile details for a
// personal greeting. Values are kept as FMCSA sends them ("Y"/"N", "A"/"I", "S"...).
public record FmcsaCarrier(
        String legalName,
        String dbaName,
        String allowedToOperate,
        String statusCode,
        String commonAuthorityStatus,
        String contractAuthorityStatus,
        String safetyRating,
        String city,
        String state,
        int powerUnits,
        int drivers) {
}
