package ai.happyrobot.carriersales.session;

import static org.assertj.core.api.Assertions.assertThat;

import ai.happyrobot.carriersales.session.Negotiation.Round;
import org.junit.jupiter.api.Test;

class NegotiationTest {

    private final Negotiation negotiation = new Negotiation("LD00979", 1961, 2300, new int[] {2100, 2215, 2300});

    @Test
    void opensAtThePostedRate() {
        assertThat(negotiation.lastPosition()).isEqualTo(1961);
        assertThat(negotiation.roundsUsed()).isZero();
        assertThat(negotiation.agreedRate()).isZero();
    }

    @Test
    void countersMoveOurPositionAndAcceptSetsTheAgreedRate() {
        negotiation.record(new Round(1, 2200, "counter", 2075));
        assertThat(negotiation.lastPosition()).isEqualTo(2075);

        negotiation.record(new Round(2, 2150, "accept", 2150));
        assertThat(negotiation.agreedRate()).isEqualTo(2150);
        assertThat(negotiation.rounds()).extracting(Round::carrierAsk).containsExactly(2200, 2150);
    }

    @Test
    void toStringNeverShowsMaxRateOrCeilings() {
        assertThat(negotiation.toString()).doesNotContain("2300", "2100", "2215");
    }

    @Test
    void ceilingsCannotBeChangedFromOutside() {
        negotiation.ceilings()[0] = 9999;

        assertThat(negotiation.ceilings()[0]).isEqualTo(2100);
    }
}
