package ai.happyrobot.carriersales.otp;

import ai.happyrobot.carriersales.carriers.CarrierDirectory;
import ai.happyrobot.carriersales.carriers.McNumber;
import ai.happyrobot.carriersales.otp.exception.OtpDeliveryException;
import ai.happyrobot.carriersales.otp.model.OtpDelivery;
import ai.happyrobot.carriersales.otp.model.OtpSendResult;
import ai.happyrobot.carriersales.otp.model.OtpVerifyResult;
import ai.happyrobot.carriersales.session.Session;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// Code generated and checked here; only its HMAC is kept. 5 min, 3 attempts, 2 sends per call.
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    static final Duration TTL = Duration.ofMinutes(5);
    public static final int MAX_ATTEMPTS = 3;
    public static final int MAX_SENDS = 2;

    private static final Map<String, String> SPOKEN_DIGITS = Map.ofEntries(
            Map.entry("zero", "0"), Map.entry("oh", "0"), Map.entry("o", "0"), Map.entry("one", "1"),
            Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"), Map.entry("five", "5"),
            Map.entry("six", "6"), Map.entry("seven", "7"), Map.entry("eight", "8"), Map.entry("nine", "9"));

    private final SessionStore sessions;
    private final CarrierDirectory directory;
    private final OtpSender sender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    // Random per process: sessions are in memory too, so a restart loses both.
    private final byte[] hmacKey = new byte[32];

    @Autowired
    public OtpService(SessionStore sessions, CarrierDirectory directory, OtpSender sender) {
        this(sessions, directory, sender, Clock.systemUTC());
    }

    OtpService(SessionStore sessions, CarrierDirectory directory, OtpSender sender, Clock clock) {
        this.sessions = sessions;
        this.directory = directory;
        this.sender = sender;
        this.clock = clock;
        random.nextBytes(hmacKey);
    }

    public OtpSendResult send(String callId, String requestedMc) {
        if (isBlank(callId)) {
            return OtpSendResult.notSent("MISSING_CALL_ID", "I can't send a code right now.", 0);
        }
        Optional<Session> found = sessions.find(callId);
        if (found.isEmpty() || found.get().state() == SessionState.NEW) {
            return OtpSendResult.notSent("MC_NOT_VERIFIED", "Let's verify your MC number first.", MAX_SENDS);
        }
        Session session = found.get();
        synchronized (session) {
            int resendsLeft = Math.max(0, MAX_SENDS - session.otpSends());
            if (isOtherMc(requestedMc, session)) {
                return OtpSendResult.notSent("MC_MISMATCH",
                        "This call is verified for a different MC number.", resendsLeft);
            }
            if (session.state() == SessionState.OTP_VERIFIED) {
                return OtpSendResult.notSent("ALREADY_VERIFIED", "You're already verified.", resendsLeft);
            }
            if (session.state() == SessionState.OTP_LOCKED) {
                return OtpSendResult.notSent("LOCKED", lockedMessage(), 0);
            }
            if (session.otpSends() >= MAX_SENDS) {
                return OtpSendResult.notSent("RESEND_LIMIT",
                        "I've already sent you a code twice. Please use the latest one.", 0);
            }
            Optional<String> email = directory.emailFor(session.mcNumber());
            if (email.isEmpty()) {
                return OtpSendResult.notSent("NO_CONTACT_ON_FILE", "I don't have a contact on file for your MC "
                        + "number, so I can't verify you on this call. A rep will follow up with you.", resendsLeft);
            }

            String code = "%06d".formatted(random.nextInt(1_000_000));
            try {
                sender.send(new OtpDelivery(callId, session.mcNumber(), session.carrierName(), email.get(), code,
                        (int) TTL.toMinutes()));
            } catch (OtpDeliveryException e) {
                log.warn("OTP delivery failed for call {}: {}", callId, e.getMessage());
                return OtpSendResult.notSent("DELIVERY_FAILED",
                        "I couldn't send the code just now. Let me try again in a moment.", resendsLeft);
            }

            session.otpHash(hash(callId, code));
            session.otpExpiresAt(clock.instant().plus(TTL));
            session.otpAttempts(0);
            session.otpSends(session.otpSends() + 1);
            session.state(SessionState.OTP_SENT);
            String masked = mask(email.get());
            session.otpDestinationMasked(masked);
            session.otpSentAt(clock.instant());
            return new OtpSendResult("", "I just sent a 6-digit code to " + masked + ".", true, "email", masked,
                    (int) TTL.toSeconds(), MAX_SENDS - session.otpSends());
        }
    }

    public OtpVerifyResult verify(String callId, String requestedMc, String spokenCode) {
        if (isBlank(callId)) {
            return new OtpVerifyResult("MISSING_CALL_ID", "I can't check the code right now.", false, 0, false);
        }
        Optional<Session> found = sessions.find(callId);
        if (found.isEmpty()) {
            return noActiveOtp();
        }
        Session session = found.get();
        synchronized (session) {
            int attemptsLeft = MAX_ATTEMPTS - session.otpAttempts();
            switch (session.state()) {
                case OTP_VERIFIED:
                    return new OtpVerifyResult("", "You're verified.", true, attemptsLeft, false);
                case OTP_LOCKED:
                    return new OtpVerifyResult("LOCKED", lockedMessage(), false, 0, true);
                case OTP_SENT:
                    break;
                default:
                    return noActiveOtp();
            }
            if (isOtherMc(requestedMc, session)) {
                return new OtpVerifyResult("MC_MISMATCH", "This call is verified for a different MC number.",
                        false, attemptsLeft, false);
            }
            String code = digits(spokenCode);
            if (code.length() != 6) {
                // A mishearing, not a guess: no attempt used.
                return new OtpVerifyResult("INVALID_FORMAT", "The code has 6 digits. Can you read it to me again?",
                        false, attemptsLeft, false);
            }
            if (clock.instant().isAfter(session.otpExpiresAt())) {
                String offer = session.otpSends() < MAX_SENDS ? " I can send you a new one." : "";
                return new OtpVerifyResult("EXPIRED", "That code has expired." + offer, false, attemptsLeft, false);
            }
            boolean match = MessageDigest.isEqual(
                    hash(callId, code).getBytes(StandardCharsets.UTF_8),
                    session.otpHash().getBytes(StandardCharsets.UTF_8));
            if (match) {
                session.state(SessionState.OTP_VERIFIED);
                session.otpHash(""); // used up
                session.otpVerifiedAt(clock.instant());
                return new OtpVerifyResult("", "Thanks, you're verified.", true, attemptsLeft, false);
            }
            session.otpAttempts(session.otpAttempts() + 1);
            if (session.otpAttempts() >= MAX_ATTEMPTS) {
                session.state(SessionState.OTP_LOCKED);
                session.otpHash("");
                log.info("OTP locked for call {} after {} wrong codes", callId, MAX_ATTEMPTS);
                return new OtpVerifyResult("LOCKED", lockedMessage(), false, 0, true);
            }
            int left = MAX_ATTEMPTS - session.otpAttempts();
            return new OtpVerifyResult("INVALID_CODE", "That code didn't match. You have " + left
                    + (left == 1 ? " attempt" : " attempts") + " left.", false, left, false);
        }
    }

    // "jane@schneider.com" -> "j***@schneider.com"
    static String mask(String email) {
        int at = email.indexOf('@');
        return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    // "4-8-2 9 1 3", "four eight two nine one three" -> "482913"
    static String digits(String spoken) {
        if (spoken == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (String token : spoken.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.matches("\\d+")) {
                digits.append(token);
            } else if (SPOKEN_DIGITS.containsKey(token)) {
                digits.append(SPOKEN_DIGITS.get(token));
            }
        }
        return digits.toString();
    }

    private String hash(String callId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((callId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean isOtherMc(String requestedMc, Session session) {
        return !isBlank(requestedMc)
                && !McNumber.clean(requestedMc).map(session.mcNumber()::equals).orElse(false);
    }

    private static OtpVerifyResult noActiveOtp() {
        return new OtpVerifyResult("NO_ACTIVE_OTP", "I haven't sent you a code yet.", false, MAX_ATTEMPTS, false);
    }

    private static String lockedMessage() {
        return "I'm sorry, that's too many wrong codes, so I can't verify you on this call. "
                + "Please call us back. Thanks for calling HappyRobot Logistics.";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
