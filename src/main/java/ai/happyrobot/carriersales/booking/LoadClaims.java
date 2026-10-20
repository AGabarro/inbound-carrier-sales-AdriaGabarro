package ai.happyrobot.carriersales.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// Which call is booking each load, taken before LOAD_BOOK. The TMS can't tell our calls apart
// ("already booked by this token"). Single instance: with several, this moves to a shared store
// (Redis SET NX with a TTL, or a unique row per load).
@Component
public class LoadClaims {

    static final Duration TTL = Duration.ofHours(1);

    private record Claim(String callId, Instant at) {
    }

    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public LoadClaims() {
        this(Clock.systemUTC());
    }

    LoadClaims(Clock clock) {
        this.clock = clock;
    }

    /** True if the load is now claimed by this call (or already was). */
    public boolean claim(String loadId, String callId) {
        Instant now = clock.instant();
        claims.values().removeIf(claim -> claim.at().plus(TTL).isBefore(now));
        return claims.computeIfAbsent(loadId, id -> new Claim(callId, now)).callId().equals(callId);
    }

    /** Only the owner can release, e.g. when its booking definitely failed. */
    public void release(String loadId, String callId) {
        claims.computeIfPresent(loadId, (id, claim) -> claim.callId().equals(callId) ? null : claim);
    }
}
