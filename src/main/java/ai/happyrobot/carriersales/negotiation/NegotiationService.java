package ai.happyrobot.carriersales.negotiation;

import ai.happyrobot.carriersales.carriers.McNumber;
import ai.happyrobot.carriersales.loads.LoadService;
import ai.happyrobot.carriersales.loads.model.LoadLookup;
import ai.happyrobot.carriersales.negotiation.NegotiationRules.Decision;
import ai.happyrobot.carriersales.negotiation.NegotiationRules.Kind;
import ai.happyrobot.carriersales.negotiation.model.OfferResult;
import ai.happyrobot.carriersales.session.Negotiation.Round;
import ai.happyrobot.carriersales.session.Negotiation;
import ai.happyrobot.carriersales.session.Session;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// One negotiation per call, at most 3 rounds, bound to the load of the first offer.
// max_rate stays in the call session: never in a response, a sentence or a log line.
@Service
public class NegotiationService {

    private static final Logger log = LoggerFactory.getLogger(NegotiationService.class);

    // One short sentence each, the agent reads them as is. Never words that suggest a ceiling ("best", "max",
    // "final"): a counter can still go up next round. No goodbyes: the agent closes the call itself.
    static final List<String> ACCEPT = List.of(
            "Deal, %s it is.",
            "You've got it at %s.",
            "Alright, %s works.",
            "Done, %s.");
    static final List<String> COUNTER = List.of(
            "How about %s?",
            "I can do %s.",
            "Let me meet you at %s.",
            "I could come up to %s on this one.");
    // Asks far above what the load pays get a smaller step, and are told so.
    static final List<String> FIRM = List.of(
            "That's well above what this load pays, but I can do %s.",
            "That's a lot more than this one pays, I can do %s.",
            "We're far apart on this one, how about %s?");
    static final List<String> REJECT = List.of(
            "I'm sorry, I can't go that high on this load.",
            "That's more than this load can pay, so I'll have to pass.",
            "We're too far apart on this one, I'm afraid.");

    private final SessionStore sessions;
    private final LoadService loads;
    private final Random random;

    @Autowired
    public NegotiationService(SessionStore sessions, LoadService loads) {
        this(sessions, loads, new Random());
    }

    NegotiationService(SessionStore sessions, LoadService loads, Random random) {
        this.sessions = sessions;
        this.loads = loads;
        this.random = random;
    }

    public OfferResult offer(String callId, String requestedMc, String loadId, String counterRate) {
        if (callId == null || callId.isBlank()) {
            return OfferResult.problem("MISSING_CALL_ID", "I can't take offers right now.", 0);
        }
        Optional<Session> found = sessions.find(callId);
        if (found.isEmpty() || !found.get().state().passedOtp()) {
            return OfferResult.problem("OTP_NOT_VERIFIED", "I need to verify your identity before we talk rates.", 0);
        }
        Session session = found.get();
        synchronized (session) {
            Negotiation negotiation = session.negotiation();
            int roundsUsed = negotiation == null ? 0 : negotiation.roundsUsed();

            if (requestedMc != null && !requestedMc.isBlank()
                    && !McNumber.clean(requestedMc).map(session.mcNumber()::equals).orElse(false)) {
                return OfferResult.problem("MC_MISMATCH", "This call is verified for a different MC number.", roundsUsed);
            }
            if (session.state() == SessionState.AGREED) {
                return OfferResult.problem("NEGOTIATION_CLOSED", "We already agreed " + money(negotiation.agreedRate())
                        + " on " + negotiation.loadId() + ".", roundsUsed);
            }
            if (session.state() == SessionState.NEGOTIATION_FAILED) {
                return OfferResult.problem("NEGOTIATION_CLOSED",
                        "We couldn't agree on a rate on this call, so I can't take another offer.", roundsUsed);
            }
            Optional<Integer> ask = parseRate(counterRate);
            if (ask.isEmpty()) {
                return OfferResult.problem("INVALID_REQUEST", "What rate are you looking for on this load?", roundsUsed);
            }

            if (negotiation == null) {
                LoadLookup lookup = loads.findBookable(loadId);
                if (lookup.load() == null) {
                    return OfferResult.problem(lookup.error(), lookup.message(), 0);
                }
                TmsLoad load = lookup.load();
                negotiation = new Negotiation(load.loadId(), load.rate(), load.maxBuy(),
                        NegotiationRules.ceilings(load.rate(), load.maxBuy(), random));
                session.negotiation(negotiation);
                session.state(SessionState.NEGOTIATING);
            } else if (!sameLoad(loadId, negotiation.loadId())) {
                return OfferResult.problem("LOAD_MISMATCH", "We're working on " + negotiation.loadId()
                        + " right now. Let's settle that one first.", roundsUsed);
            }

            int round = negotiation.roundsUsed() + 1;
            Decision decision = NegotiationRules.decide(negotiation.maxRate(), negotiation.ceilings(), round,
                    ask.get(), negotiation.lastPosition());
            String kind = decision.kind().name().toLowerCase(Locale.ROOT);
            negotiation.record(new Round(round, ask.get(), kind, decision.rate()));
            if (decision.kind() == Kind.ACCEPT) {
                session.state(SessionState.AGREED);
            } else if (decision.kind() == Kind.REJECT) {
                session.state(SessionState.NEGOTIATION_FAILED);
            }
            log.info("Negotiation call {} load {} round {}: ask {} -> {} {}", callId, negotiation.loadId(), round,
                    ask.get(), kind, decision.rate());

            return new OfferResult("", message(decision), kind, decision.rate(), round,
                    NegotiationRules.MAX_ROUNDS, decision.kind() != Kind.COUNTER);
        }
    }

    private String message(Decision decision) {
        return switch (decision.kind()) {
            case ACCEPT -> pick(ACCEPT).formatted(money(decision.rate()));
            case COUNTER -> pick(decision.firm() ? FIRM : COUNTER).formatted(money(decision.rate()));
            case REJECT -> pick(REJECT);
        };
    }

    private String pick(List<String> options) {
        return options.get(random.nextInt(options.size()));
    }

    // 2400, "2,400", "$2400", "2.4k" -> 2400
    static Optional<Integer> parseRate(String spoken) {
        if (spoken == null) {
            return Optional.empty();
        }
        String text = spoken.toLowerCase(Locale.ROOT).replaceAll("[$,\\s]", "");
        double multiplier = 1;
        if (text.endsWith("k")) {
            multiplier = 1000;
            text = text.substring(0, text.length() - 1);
        }
        try {
            long dollars = Math.round(Double.parseDouble(text) * multiplier);
            return dollars >= 1 && dollars <= 100_000 ? Optional.of((int) dollars) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean sameLoad(String spokenId, String boundId) {
        return spokenId != null && spokenId.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-_.#]", "").equals(boundId);
    }

    private static String money(int dollars) {
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(dollars);
    }
}
