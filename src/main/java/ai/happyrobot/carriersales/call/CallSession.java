package ai.happyrobot.carriersales.call;

import java.time.Instant;

/**
 * Everything the backend remembers about one call, keyed by HappyRobot's X-Call-ID.
 * Mutable on purpose: the state moves forward as the call goes on. Services that change it
 * synchronize on the session, since two tool calls of the same call could overlap.
 * The one-time code is never stored, only its hash.
 */
public class CallSession {

    private final String callId;
    private final Instant createdAt;
    private CallState state = CallState.NEW;
    private String mcNumber = "";
    private String carrierName = "";
    private String otpHash = "";
    private Instant otpExpiresAt;
    private int otpAttempts;
    private int otpSends;

    public CallSession(String callId, Instant createdAt) {
        this.callId = callId;
        this.createdAt = createdAt;
    }

    public String callId() {
        return callId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CallState state() {
        return state;
    }

    public void state(CallState state) {
        this.state = state;
    }

    public String mcNumber() {
        return mcNumber;
    }

    public void mcNumber(String mcNumber) {
        this.mcNumber = mcNumber;
    }

    public String carrierName() {
        return carrierName;
    }

    public void carrierName(String carrierName) {
        this.carrierName = carrierName;
    }

    public String otpHash() {
        return otpHash;
    }

    public void otpHash(String otpHash) {
        this.otpHash = otpHash;
    }

    public Instant otpExpiresAt() {
        return otpExpiresAt;
    }

    public void otpExpiresAt(Instant otpExpiresAt) {
        this.otpExpiresAt = otpExpiresAt;
    }

    public int otpAttempts() {
        return otpAttempts;
    }

    public void otpAttempts(int otpAttempts) {
        this.otpAttempts = otpAttempts;
    }

    public int otpSends() {
        return otpSends;
    }

    public void otpSends(int otpSends) {
        this.otpSends = otpSends;
    }

    @Override
    public String toString() {
        // No hash, on purpose: nothing about the code goes into logs.
        return "CallSession[callId=" + callId + ", state=" + state + ", mc=" + mcNumber + "]";
    }
}
