package ai.happyrobot.carriersales.call;

import org.springframework.stereotype.Component;

/**
 * The compliance gate: load tools only work for a call that passed FMCSA and the OTP.
 * Enforced here, server side, so no wording on the call can talk the agent around it.
 */
@Component
public class CallGate {

    public static final String NOT_VERIFIED = "OTP_NOT_VERIFIED";
    public static final String NOT_VERIFIED_MESSAGE = "I need to verify your identity before I can share loads.";

    private final CallSessionStore sessions;

    public CallGate(CallSessionStore sessions) {
        this.sessions = sessions;
    }

    public boolean isVerified(String callId) {
        if (callId == null || callId.isBlank()) {
            return false;
        }
        return sessions.find(callId).map(session -> session.state() == CallState.OTP_VERIFIED).orElse(false);
    }
}
