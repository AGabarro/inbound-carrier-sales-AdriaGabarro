package ai.happyrobot.carriersales.summary;

import ai.happyrobot.carriersales.otp.OtpService;
import ai.happyrobot.carriersales.session.Negotiation;
import ai.happyrobot.carriersales.session.Session;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import ai.happyrobot.carriersales.summary.model.CallSummary;
import ai.happyrobot.carriersales.summary.model.CallSummary.BookingRow;
import ai.happyrobot.carriersales.summary.model.CallSummary.NegotiationRow;
import ai.happyrobot.carriersales.summary.model.CallSummary.OfferRow;
import ai.happyrobot.carriersales.summary.model.CallSummary.OtpRow;
import ai.happyrobot.carriersales.summary.model.CallSummary.VerificationRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

// Read-only view of a call's session for the end-of-call workflow, which writes it to Twin.
@Service
public class CallSummaryService {

    private final SessionStore sessions;

    public CallSummaryService(SessionStore sessions) {
        this.sessions = sessions;
    }

    public CallSummary summary(String callId) {
        Optional<Session> found = callId == null || callId.isBlank() ? Optional.empty() : sessions.find(callId);
        if (found.isEmpty()) {
            return CallSummary.notFound(callId == null ? "" : callId);
        }
        Session s = found.get();
        synchronized (s) {
            List<VerificationRow> verifications = s.verifications().stream()
                    .map(v -> new VerificationRow(callId, v.mcNumberInput(), v.mcNumber(), v.eligible(), v.reason(),
                            v.carrierName(), v.homeBase(), v.powerUnits(), v.drivers(), v.safetyRating(), iso(v.at())))
                    .toList();

            List<Negotiation> all = new ArrayList<>(s.previousNegotiations());
            if (s.negotiation() != null) {
                all.add(s.negotiation());
            }
            List<NegotiationRow> negotiations = all.stream()
                    .map(n -> new NegotiationRow(callId, n.loadId(), s.mcNumber(), n.loadboardRate(), n.roundsUsed(),
                            n.status(), n.agreedRate(), iso(n.closedAt())))
                    .toList();
            List<OfferRow> offers = all.stream()
                    .flatMap(n -> n.rounds().stream().map(r -> new OfferRow(callId, n.loadId(), r.number(),
                            r.carrierAsk(), r.decision(), r.rate())))
                    .toList();

            return new CallSummary("", "", callId, s.state().name(), verifications, otp(s), negotiations, offers,
                    s.booking() == null ? List.of() : List.of(new BookingRow(callId, s.booking().loadId(),
                            s.booking().mcNumber(), s.booking().agreedRate(), s.booking().reference(),
                            s.booking().status(), s.booking().transferred(), s.booking().repQueue())));
        }
    }

    private static List<OtpRow> otp(Session s) {
        if (s.otpSentAt() == null) {
            return List.of();
        }
        return List.of(new OtpRow(s.callId(), s.mcNumber(), "email", s.otpDestinationMasked(), iso(s.otpSentAt()),
                iso(s.otpExpiresAt()), OtpService.MAX_ATTEMPTS - s.otpAttempts(), OtpService.MAX_SENDS - s.otpSends(),
                iso(s.otpVerifiedAt()), s.state() == SessionState.OTP_LOCKED));
    }

    private static String iso(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
