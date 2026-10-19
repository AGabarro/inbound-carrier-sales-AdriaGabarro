package ai.happyrobot.carriersales.carriers;

import ai.happyrobot.carriersales.carriers.model.VerifyResult;
import ai.happyrobot.carriersales.fmcsa.FmcsaClient;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import ai.happyrobot.carriersales.web.exception.BadRequestException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Carrier verification as the brief defines it: look the MC number up in FMCSA and check for active
 * operating authority. If the carrier does not pass, the call ends.
 */
@Service
public class CarrierService {

    static final String NOT_FOUND = "NOT_FOUND";
    static final String NO_ACTIVE_AUTHORITY = "NO_ACTIVE_AUTHORITY";

    private final FmcsaClient fmcsa;

    public CarrierService(FmcsaClient fmcsa) {
        this.fmcsa = fmcsa;
    }

    public VerifyResult verify(String spokenMcNumber) {
        String mc = mcNumber(spokenMcNumber);
        Optional<FmcsaCarrier> found = fmcsa.findByMcNumber(mc);

        if (found.isEmpty()) {
            return VerifyResult.notEligible(NOT_FOUND,
                    "I couldn't find that MC number. Can you read it to me again?", mc);
        }
        FmcsaCarrier carrier = found.get();
        if (!hasActiveOperatingAuthority(carrier)) {
            return VerifyResult.notEligible(NO_ACTIVE_AUTHORITY,
                    "I'm sorry, I can't verify active operating authority for that MC number, so I can't book "
                            + "loads with you today. Thanks for calling HappyRobot Logistics.", mc);
        }
        return new VerifyResult(true, null, "You're verified.", mc,
                titleCase(carrier.legalName()),
                titleCase(carrier.dbaName()),
                homeBase(carrier),
                carrier.powerUnits() > 0 ? carrier.powerUnits() : null,
                carrier.drivers() > 0 ? carrier.drivers() : null,
                safetyRating(carrier.safetyRating()));
    }

    // The brief's check, in FMCSA terms: allowed to operate, USDOT status active, and for-hire authority
    // (common or contract) active. allowedToOperate alone is not enough: it is "Y" even for inactive carriers.
    static boolean hasActiveOperatingAuthority(FmcsaCarrier carrier) {
        return "Y".equals(carrier.allowedToOperate())
                && "A".equals(carrier.statusCode())
                && ("A".equals(carrier.commonAuthorityStatus()) || "A".equals(carrier.contractAuthorityStatus()));
    }

    /** "MC 133655", "mc-133655", "133655" -> "133655". */
    private static String mcNumber(String spoken) {
        String digits = spoken == null ? "" : spoken.replaceAll("\\D", "").replaceFirst("^0+", "");
        if (!digits.matches("\\d{1,8}")) {
            throw new BadRequestException("I didn't catch your MC number. Can you read it to me again?");
        }
        return digits;
    }

    private static String homeBase(FmcsaCarrier carrier) {
        if (carrier.city() == null || carrier.state() == null) {
            return null;
        }
        return titleCase(carrier.city()) + ", " + carrier.state();
    }

    private static String safetyRating(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "S" -> "Satisfactory";
            case "C" -> "Conditional";
            case "U" -> "Unsatisfactory";
            default -> null;
        };
    }

    // FMCSA names are upper case; "SCHNEIDER NATIONAL CARRIERS INC" reads better as "Schneider National Carriers Inc".
    // "LLC" stays as is, otherwise the voice would read "Llc" as a word.
    private static String titleCase(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Arrays.stream(text.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .map(word -> word.equals("llc") ? "LLC" : Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }
}
