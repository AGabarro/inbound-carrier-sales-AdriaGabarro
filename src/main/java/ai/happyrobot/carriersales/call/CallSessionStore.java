package ai.happyrobot.carriersales.call;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// In memory, one per X-Call-ID, 1h TTL. Single instance only; production would use a shared store.
@Component
public class CallSessionStore {

    static final Duration TTL = Duration.ofHours(1);

    private final Map<String, CallSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public CallSessionStore() {
        this(Clock.systemUTC());
    }

    CallSessionStore(Clock clock) {
        this.clock = clock;
    }

    public CallSession getOrCreate(String callId) {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> isExpired(session, now));
        return sessions.computeIfAbsent(callId, id -> new CallSession(id, now));
    }

    public Optional<CallSession> find(String callId) {
        CallSession session = sessions.get(callId);
        if (session == null || isExpired(session, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private static boolean isExpired(CallSession session, Instant now) {
        return session.createdAt().plus(TTL).isBefore(now);
    }
}
