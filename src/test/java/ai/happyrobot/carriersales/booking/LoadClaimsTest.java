package ai.happyrobot.carriersales.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoadClaimsTest {

    private Instant now = Instant.parse("2026-10-20T12:00:00Z");
    private final LoadClaims claims = new LoadClaims(new Clock() {
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
    });

    @Test
    void firstCallGetsTheLoadAndKeepsIt() {
        assertThat(claims.claim("LD01000", "call-a")).isTrue();
        assertThat(claims.claim("LD01000", "call-a")).isTrue();
        assertThat(claims.claim("LD01000", "call-b")).isFalse();
    }

    @Test
    void differentLoadsDontInterfere() {
        claims.claim("LD01000", "call-a");

        assertThat(claims.claim("LD01024", "call-b")).isTrue();
    }

    @Test
    void onlyTheOwnerCanRelease() {
        claims.claim("LD01000", "call-a");

        claims.release("LD01000", "call-b");
        assertThat(claims.claim("LD01000", "call-b")).isFalse();

        claims.release("LD01000", "call-a");
        assertThat(claims.claim("LD01000", "call-b")).isTrue();
    }

    @Test
    void claimsExpireWithTheCalls() {
        claims.claim("LD01000", "call-a");

        now = now.plus(Duration.ofMinutes(61));

        assertThat(claims.claim("LD01000", "call-b")).isTrue();
    }
}
