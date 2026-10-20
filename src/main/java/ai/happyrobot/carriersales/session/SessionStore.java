package ai.happyrobot.carriersales.session;

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
public class SessionStore {

    static final Duration TTL = Duration.ofHours(1);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public SessionStore() {
        this(Clock.systemUTC());
    }

    SessionStore(Clock clock) {
        this.clock = clock;
    }

    public Session getOrCreate(String callId) {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> isExpired(session, now));
        return sessions.computeIfAbsent(callId, id -> new Session(id, now));
    }

    public Optional<Session> find(String callId) {
        Session session = sessions.get(callId);
        if (session == null || isExpired(session, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private static boolean isExpired(Session session, Instant now) {
        return session.createdAt().plus(TTL).isBefore(now);
    }
}
