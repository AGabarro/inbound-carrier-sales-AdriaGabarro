package ai.happyrobot.carriersales.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionStoreTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-19T12:00:00Z"));
    private final SessionStore store = new SessionStore(clock);

    @Test
    void newCallStartsInStateNew() {
        Session session = store.getOrCreate("run-1");

        assertThat(session.callId()).isEqualTo("run-1");
        assertThat(session.state()).isEqualTo(SessionState.NEW);
        assertThat(session.mcNumber()).isEmpty();
    }

    @Test
    void sameCallIdGivesTheSameSession() {
        Session first = store.getOrCreate("run-1");
        first.state(SessionState.FMCSA_VERIFIED);

        assertThat(store.getOrCreate("run-1")).isSameAs(first);
        assertThat(store.find("run-1")).containsSame(first);
    }

    @Test
    void callsAreKeptApart() {
        store.getOrCreate("run-1").state(SessionState.OTP_VERIFIED);

        assertThat(store.getOrCreate("run-2").state()).isEqualTo(SessionState.NEW);
    }

    @Test
    void unknownCallIsNotFound() {
        assertThat(store.find("never-seen")).isEmpty();
    }

    @Test
    void sessionExpiresAfterAnHour() {
        store.getOrCreate("run-1").state(SessionState.OTP_VERIFIED);

        clock.advance(Duration.ofMinutes(59));
        assertThat(store.find("run-1")).isPresent();

        clock.advance(Duration.ofMinutes(2));
        assertThat(store.find("run-1")).isEmpty();
        // A late request with the same id starts over: verification is never carried past the TTL.
        assertThat(store.getOrCreate("run-1").state()).isEqualTo(SessionState.NEW);
    }

    @Test
    void toStringNeverShowsTheCodeHash() {
        Session session = store.getOrCreate("run-1");
        session.otpHash("deadbeef-hash");

        assertThat(session.toString()).doesNotContain("deadbeef");
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
