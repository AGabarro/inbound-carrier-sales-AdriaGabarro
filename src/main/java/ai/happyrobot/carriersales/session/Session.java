package ai.happyrobot.carriersales.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// What the backend remembers about one call. Services synchronize on it; only the code's hash is kept.
public class Session {

    private final String callId;
    private final Instant createdAt;
    private SessionState state = SessionState.NEW;
    private String mcNumber = "";
    private String carrierName = "";
    private String otpHash = "";
    private Instant otpExpiresAt;
    private int otpAttempts;
    private int otpSends;
    private Negotiation negotiation;
    private final List<Negotiation> previousNegotiations = new ArrayList<>();
    private String bookingRef = "";

    public Session(String callId, Instant createdAt) {
        this.callId = callId;
        this.createdAt = createdAt;
    }

    public String callId() {
        return callId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public SessionState state() {
        return state;
    }

    public void state(SessionState state) {
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

    public Negotiation negotiation() {
        return negotiation;
    }

    public void negotiation(Negotiation negotiation) {
        this.negotiation = negotiation;
    }

    // The agreed load fell through (taken or refused): keep the history, let the carrier negotiate another one.
    public void releaseNegotiation() {
        if (negotiation != null) {
            previousNegotiations.add(negotiation);
        }
        negotiation = null;
        state = SessionState.OTP_VERIFIED;
    }

    public List<Negotiation> previousNegotiations() {
        return List.copyOf(previousNegotiations);
    }

    public String bookingRef() {
        return bookingRef;
    }

    public void bookingRef(String bookingRef) {
        this.bookingRef = bookingRef;
    }

    @Override
    public String toString() {
        return "Session[callId=" + callId + ", state=" + state + ", mc=" + mcNumber + "]";
    }
}
