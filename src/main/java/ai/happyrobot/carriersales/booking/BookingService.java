package ai.happyrobot.carriersales.booking;

import ai.happyrobot.carriersales.booking.model.BookingResult;
import ai.happyrobot.carriersales.carriers.McNumber;
import ai.happyrobot.carriersales.session.Negotiation;
import ai.happyrobot.carriersales.session.Session;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import ai.happyrobot.carriersales.tms.TmsClient;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.TmsBooking;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import ai.happyrobot.carriersales.tms.protocol.TmsRequest;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Books the agreed load at the agreed rate (never a rate from the request: the TMS doesn't check MAX_BUY),
// then hands the carrier to a senior rep (mocked). A booking is never retried blindly.
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    static final String REP_QUEUE = "senior-carrier-sales";

    private final SessionStore sessions;
    private final LoadClaims claims;
    private final TmsClient tms;

    public BookingService(SessionStore sessions, LoadClaims claims, TmsClient tms) {
        this.sessions = sessions;
        this.claims = claims;
        this.tms = tms;
    }

    public BookingResult book(String callId, String requestedMc, String requestedLoadId) {
        if (callId == null || callId.isBlank()) {
            return BookingResult.problem("MISSING_CALL_ID", "I can't book right now.");
        }
        Optional<Session> found = sessions.find(callId);
        if (found.isEmpty() || !found.get().state().passedOtp()) {
            return BookingResult.problem("OTP_NOT_VERIFIED", "I need to verify your identity before booking.");
        }
        Session session = found.get();
        synchronized (session) {
            Negotiation negotiation = session.negotiation();
            if (requestedMc != null && !requestedMc.isBlank()
                    && !McNumber.clean(requestedMc).map(session.mcNumber()::equals).orElse(false)) {
                return BookingResult.problem("MC_MISMATCH", "This call is verified for a different MC number.");
            }
            if (session.state() == SessionState.BOOKED) {
                return bookedResult(session, negotiation); // asked again: same answer, no second booking or handoff
            }
            if (session.state() == SessionState.NEGOTIATION_FAILED) {
                return BookingResult.problem("NO_AGREEMENT", "We didn't agree on a rate, so I can't book this one.");
            }
            if (session.state() != SessionState.AGREED) {
                return BookingResult.problem("NO_AGREEMENT", "Let's agree on a rate first.");
            }
            String loadId = negotiation.loadId();
            if (requestedLoadId != null && !requestedLoadId.isBlank() && !loadId.equals(cleanLoadId(requestedLoadId))) {
                return BookingResult.problem("LOAD_MISMATCH", "We agreed a rate on " + loadId + ", so that's the one I can book.");
            }
            if (!claims.claim(loadId, callId)) {
                session.releaseNegotiation();
                return BookingResult.problem("LOAD_TAKEN", "Ah, sorry, another carrier just grabbed that one. "
                        + "Want me to look for something else on your lane?");
            }
            return attempt(session, negotiation);
        }
    }

    private BookingResult attempt(Session session, Negotiation negotiation) {
        String loadId = negotiation.loadId();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                TmsBooking booking = tms.book(loadId, session.mcNumber(), negotiation.agreedRate());
                session.bookingRef(booking.bookingRef());
                session.state(SessionState.BOOKED);
                return booked(session, negotiation);
            } catch (TmsErrorException e) {
                if ("ALREADY_BOOKED".equals(e.code())) {
                    // We hold the claim, but the TMS can't say which of our requests booked it: let a rep confirm.
                    return unconfirmed(session, negotiation);
                }
                log.warn("Booking {} for call {} refused: {}", loadId, session.callId(), e.code());
                claims.release(loadId, session.callId());
                session.releaseNegotiation();
                return BookingResult.problem("BOOKING_FAILED", "Hmm, the system wouldn't let me book that one. "
                        + "Let's find you another load.");
            } catch (TmsFaultException e) {
                // Did it go through anyway? Check before anything else.
                Optional<String> status = status(loadId);
                if (status.isEmpty()) {
                    return unconfirmed(session, negotiation);
                }
                if (!"OPEN".equals(status.get())) {
                    // Still claimed by this call, so no other call of ours booked it.
                    session.state(SessionState.BOOKED);
                    return booked(session, negotiation);
                }
                log.warn("Booking {} for call {} failed ({}), still OPEN, attempt {}/2", loadId, session.callId(),
                        e.kind(), attempt);
            }
        }
        return unconfirmed(session, negotiation);
    }

    private Optional<String> status(String loadId) {
        try {
            List<TmsLoad> loads = tms.send(TmsRequest.loadGet(loadId));
            return loads.isEmpty() ? Optional.empty() : Optional.of(loads.getFirst().status());
        } catch (TmsFaultException | TmsErrorException e) {
            return Optional.empty();
        }
    }

    private BookingResult booked(Session session, Negotiation negotiation) {
        handoff(session, negotiation, "booked");
        return bookedResult(session, negotiation);
    }

    private BookingResult bookedResult(Session session, Negotiation negotiation) {
        return new BookingResult("", "You're all set: " + negotiation.loadId() + " is booked at "
                + money(negotiation.agreedRate()) + ". I'm passing you to a senior rep to wrap up the paperwork.",
                true, negotiation.loadId(), negotiation.agreedRate(), session.bookingRef(), true, REP_QUEUE);
    }

    private BookingResult unconfirmed(Session session, Negotiation negotiation) {
        handoff(session, negotiation, "needs confirmation");
        return new BookingResult("BOOKING_UNCONFIRMED", "Your booking is going through. I'm passing you to a "
                + "senior rep who'll confirm it with you.", false, negotiation.loadId(), negotiation.agreedRate(),
                "", true, REP_QUEUE);
    }

    // Mocked transfer: web calls can't be transferred, so the handoff is logged for the rep queue.
    private void handoff(Session session, Negotiation negotiation, String status) {
        log.info("Handoff to {}: call {}, {} (MC {}), load {} at {}, {}, ref {}", REP_QUEUE, session.callId(),
                session.carrierName(), session.mcNumber(), negotiation.loadId(), negotiation.agreedRate(), status,
                session.bookingRef().isEmpty() ? "-" : session.bookingRef());
    }

    private static String cleanLoadId(String spoken) {
        return spoken.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-_.#]", "");
    }

    private static String money(int dollars) {
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(dollars);
    }
}
