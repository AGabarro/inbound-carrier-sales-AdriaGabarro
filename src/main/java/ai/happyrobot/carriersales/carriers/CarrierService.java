package ai.happyrobot.carriersales.carriers;

import ai.happyrobot.carriersales.call.CallSession;
import ai.happyrobot.carriersales.call.CallSessionStore;
import ai.happyrobot.carriersales.call.CallState;
import ai.happyrobot.carriersales.carriers.model.VerifyResult;
import ai.happyrobot.carriersales.fmcsa.FmcsaClient;
import ai.happyrobot.carriersales.fmcsa.exception.FmcsaUnavailableException;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import ai.happyrobot.carriersales.web.exception.BadRequestException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Carrier verification as the brief defines it: look the MC number up in FMCSA and check for active
 * operating authority. If the carrier does not pass, the call ends. A pass binds the MC to the call,
 * which is the first step towards unlocking loads (the OTP is the second).
 */
@Service
public class CarrierService {

    static final String INVALID_REQUEST = "INVALID_REQUEST";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String NOT_ELIGIBLE = "NOT_ELIGIBLE";
    static final String NO_ACTIVE_AUTHORITY = "NO_ACTIVE_AUTHORITY";
    static final String FMCSA_UNAVAILABLE = "FMCSA_UNAVAILABLE";
    static final String MC_MISMATCH = "MC_MISMATCH";

    private final FmcsaClient fmcsa;
    private final CallSessionStore sessions;

    public CarrierService(FmcsaClient fmcsa, CallSessionStore sessions) {
        this.fmcsa = fmcsa;
        this.sessions = sessions;
    }

    // Every outcome is a normal result with an error code and a sentence: HappyRobot only reads 200 responses.
    public VerifyResult verify(String spokenMcNumber, String callId) {
        String mc;
        try {
            mc = mcNumber(spokenMcNumber);
        } catch (BadRequestException e) {
            return VerifyResult.notEligible(INVALID_REQUEST, "", e.getMessage(), "");
        }

        // Once a call has verified an MC, it can't switch to another one (e.g. after the OTP went elsewhere).
        Optional<CallSession> session = Optional.ofNullable(callId).filter(id -> !id.isBlank()).flatMap(sessions::find);
        if (session.isPresent() && session.get().state() != CallState.NEW && !mc.equals(session.get().mcNumber())) {
            return VerifyResult.notEligible(MC_MISMATCH, "",
                    "This call is already verified for a different MC number, so I can't switch carriers now.", mc);
        }

        Optional<FmcsaCarrier> found;
        try {
            found = fmcsa.findByMcNumber(mc);
        } catch (FmcsaUnavailableException e) {
            return VerifyResult.notEligible(FMCSA_UNAVAILABLE, "",
                    "I can't check your MC number right now. Please try again in a few minutes.", mc);
        }

        if (found.isEmpty()) {
            return VerifyResult.notEligible(NOT_FOUND, NOT_FOUND,
                    "I couldn't find that MC number. Can you read it to me again?", mc);
        }
        FmcsaCarrier carrier = found.get();
        if (!hasActiveOperatingAuthority(carrier)) {
            return VerifyResult.notEligible(NOT_ELIGIBLE, NO_ACTIVE_AUTHORITY,
                    "I'm sorry, I can't verify active operating authority for that MC number, so I can't book "
                            + "loads with you today. Thanks for calling HappyRobot Logistics.", mc);
        }
        String carrierName = titleCase(carrier.legalName());
        bindToCall(callId, mc, carrierName);
        return new VerifyResult("", "You're verified.", true, "", mc,
                carrierName,
                titleCase(carrier.dbaName()),
                homeBase(carrier),
                carrier.powerUnits(),
                carrier.drivers(),
                safetyRating(carrier.safetyRating()));
    }

    // Marks the call as FMCSA_VERIFIED for this MC. Verifying the same MC again doesn't reset later progress.
    private void bindToCall(String callId, String mc, String carrierName) {
        if (callId == null || callId.isBlank()) {
            return; // nothing to remember; the load gate will still refuse this call
        }
        CallSession session = sessions.getOrCreate(callId);
        synchronized (session) {
            if (session.state() == CallState.NEW) {
                session.state(CallState.FMCSA_VERIFIED);
                session.mcNumber(mc);
                session.carrierName(carrierName);
            }
        }
    }

    // The brief's check, in FMCSA terms: allowed to operate, USDOT status active, and for-hire authority
    // (common or contract) active. allowedToOperate alone is not enough: it is "Y" even for inactive carriers.
    static boolean hasActiveOperatingAuthority(FmcsaCarrier carrier) {
        return "Y".equals(carrier.allowedToOperate())
                && "A".equals(carrier.statusCode())
                && ("A".equals(carrier.commonAuthorityStatus()) || "A".equals(carrier.contractAuthorityStatus()));
    }

    private static String mcNumber(String spoken) {
        return McNumber.clean(spoken).orElseThrow(
                () -> new BadRequestException("I didn't catch your MC number. Can you read it to me again?"));
    }

    private static String homeBase(FmcsaCarrier carrier) {
        if (carrier.city() == null || carrier.state() == null) {
            return "";
        }
        return titleCase(carrier.city()) + ", " + carrier.state();
    }

    private static String safetyRating(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "S" -> "Satisfactory";
            case "C" -> "Conditional";
            case "U" -> "Unsatisfactory";
            default -> "";
        };
    }

    // FMCSA names are upper case; "SCHNEIDER NATIONAL CARRIERS INC" reads better as "Schneider National Carriers Inc".
    // "LLC" stays as is, otherwise the voice would read "Llc" as a word.
    private static String titleCase(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Arrays.stream(text.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .map(word -> word.equals("llc") ? "LLC" : Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }
}
