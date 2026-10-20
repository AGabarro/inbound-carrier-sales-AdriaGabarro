package ai.happyrobot.carriersales.negotiation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.happyrobot.carriersales.negotiation.NegotiationRules.Decision;
import ai.happyrobot.carriersales.negotiation.NegotiationRules.Kind;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NegotiationRulesTest {

    // LD00979: posted $1,961; max $2,300 (gap $339). Fixed ceilings for readable examples.
    private static final int LOADBOARD = 1961;
    private static final int MAX = 2300;
    private static final int[] CEILINGS = {2100, 2215, 2300};

    // --- the examples from the design ---

    @Test
    void reasonableCarrierIsMetHalfwayThenAccepted() {
        Decision first = NegotiationRules.decide(MAX, CEILINGS, 1, 2200, LOADBOARD);
        assertThat(first).isEqualTo(new Decision(Kind.COUNTER, 2075, false)); // halfway 2080.5 -> 2075

        Decision second = NegotiationRules.decide(MAX, CEILINGS, 2, 2150, 2075);
        assertThat(second).isEqualTo(new Decision(Kind.ACCEPT, 2150, false)); // under round 2's ceiling
    }

    @Test
    void outrageousAskOnlyGetsHalfAStepThenFinalReject() {
        Decision first = NegotiationRules.decide(MAX, CEILINGS, 1, 2800, LOADBOARD);
        assertThat(first.kind()).isEqualTo(Kind.COUNTER);
        assertThat(first.firm()).isTrue();
        assertThat(first.rate()).isEqualTo(2025); // capped at 2100, then half the step: 2030.5 -> 2025

        Decision second = NegotiationRules.decide(MAX, CEILINGS, 2, 2600, 2025);
        assertThat(second).isEqualTo(new Decision(Kind.COUNTER, 2200, false)); // halfway 2312.5, capped 2215 -> 2200

        Decision third = NegotiationRules.decide(MAX, CEILINGS, 3, 2500, 2200);
        assertThat(third).isEqualTo(new Decision(Kind.REJECT, 0, false));
    }

    @Test
    void askAtOrUnderThePostedRateIsAcceptedAtTheirNumber() {
        assertThat(NegotiationRules.decide(MAX, CEILINGS, 1, LOADBOARD, LOADBOARD))
                .isEqualTo(new Decision(Kind.ACCEPT, LOADBOARD, false));
        assertThat(NegotiationRules.decide(MAX, CEILINGS, 1, 1900, LOADBOARD))
                .isEqualTo(new Decision(Kind.ACCEPT, 1900, false));
    }

    @Test
    void lastRoundAcceptsUpToMaxAndNeverAbove() {
        assertThat(NegotiationRules.decide(MAX, CEILINGS, 3, MAX, 2200).kind()).isEqualTo(Kind.ACCEPT);
        assertThat(NegotiationRules.decide(MAX, CEILINGS, 3, MAX + 1, 2200).kind()).isEqualTo(Kind.REJECT);
    }

    @Test
    void badDataWithMaxBelowThePostedRateNeverCountersAboveMax() {
        int[] ceilings = NegotiationRules.ceilings(2000, 1900, new Random(1));

        assertThat(ceilings).containsOnly(1900);
        assertThat(NegotiationRules.decide(1900, ceilings, 1, 1850, 2000).kind()).isEqualTo(Kind.ACCEPT);
        assertThat(NegotiationRules.decide(1900, ceilings, 1, 2100, 2000).kind()).isEqualTo(Kind.REJECT);
    }

    // --- per-call variation ---

    @Test
    void ceilingsShiftPerCallWithinTheirBands() {
        Set<Integer> firstCeilings = new HashSet<>();
        Random random = new Random(42);
        for (int call = 0; call < 200; call++) {
            int[] c = NegotiationRules.ceilings(LOADBOARD, MAX, random);
            firstCeilings.add(c[0]);
            assertThat(c[0]).isBetween(LOADBOARD + (int) (339 * 0.33), LOADBOARD + (int) (339 * 0.47));
            assertThat(c[1]).isBetween(LOADBOARD + (int) (339 * 0.68), LOADBOARD + (int) (339 * 0.82));
            assertThat(c[2]).isEqualTo(MAX);
        }
        assertThat(firstCeilings).hasSizeGreaterThan(20); // not a fixed, learnable number
    }

    // --- the guarantees, over thousands of random negotiations ---

    @Test
    void guaranteesHoldForAnyLoadAskAndCall() {
        Random random = new Random(7);
        for (int run = 0; run < 20_000; run++) {
            int loadboard = 500 + random.nextInt(4500);
            int max = loadboard - 200 + random.nextInt(3200); // includes bad data (max <= loadboard)
            int[] ceilings = NegotiationRules.ceilings(loadboard, max, random);
            int lastPosition = loadboard;

            for (int round = 1; round <= NegotiationRules.MAX_ROUNDS; round++) {
                // Asks from $300 under the posted rate to $1,500 over max.
                int ask = loadboard - 300 + random.nextInt(Math.max(max - loadboard, 0) + 1800);
                Decision d = NegotiationRules.decide(max, ceilings, round, ask, lastPosition);

                switch (d.kind()) {
                    case ACCEPT -> {
                        assertThat(d.rate()).as("accepted at their ask").isEqualTo(ask);
                        assertThat(d.rate()).as("never above max").isLessThanOrEqualTo(max);
                    }
                    case COUNTER -> {
                        assertThat(round).as("no counter in the last round").isLessThan(NegotiationRules.MAX_ROUNDS);
                        assertThat(d.rate()).as("counter below max, never equal").isLessThan(max);
                        assertThat(d.rate()).as("counters never go down").isGreaterThanOrEqualTo(lastPosition);
                        assertThat(d.rate()).as("counter below their ask").isLessThan(ask);
                        assertThat(d.rate() % 25 == 0 || d.rate() == lastPosition)
                                .as("multiple of $25 unless holding our last number").isTrue();
                    }
                    case REJECT -> assertThat(d.rate()).isZero();
                }
                if (d.kind() != Kind.COUNTER) {
                    break;
                }
                lastPosition = d.rate();
            }
        }
    }
}
