package ai.happyrobot.carriersales.session;

import org.springframework.stereotype.Component;

// Load and negotiation tools only work once FMCSA and the OTP passed. Enforced server side.
@Component
public class VerificationGate {

    public static final String NOT_VERIFIED = "OTP_NOT_VERIFIED";
    public static final String NOT_VERIFIED_MESSAGE = "I need to verify your identity before I can share loads.";

    private final SessionStore sessions;

    public VerificationGate(SessionStore sessions) {
        this.sessions = sessions;
    }

    public boolean isVerified(String callId) {
        if (callId == null || callId.isBlank()) {
            return false;
        }
        return sessions.find(callId).map(session -> session.state().passedOtp()).orElse(false);
    }
}
