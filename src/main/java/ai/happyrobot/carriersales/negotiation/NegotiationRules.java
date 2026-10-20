package ai.happyrobot.carriersales.negotiation;

import java.util.Random;

// Each round has a ceiling rising towards max_rate, shifted per call so it can't be learned.
// At or under it: accept the ask. Above: meet halfway, capped at the ceiling (half a step if the ask
// is way above). Last round: accept or reject. Never above max_rate; a counter never equals it.
public final class NegotiationRules {

    public static final int MAX_ROUNDS = 3;
    // Share of (max_rate - loadboard_rate) for rounds 1 and 2.
    private static final double[] CEILING_SHARE = {0.40, 0.75};
    private static final double SHIFT = 0.07;
    private static final double WAY_ABOVE = 0.15;
    private static final int STEP = 25;

    public enum Kind { ACCEPT, COUNTER, REJECT }

    public record Decision(Kind kind, int rate, boolean firm) {
    }

    private NegotiationRules() {
    }

    public static int[] ceilings(int loadboardRate, int maxRate, Random random) {
        int gap = maxRate - loadboardRate;
        if (gap <= 0) {
            // Bad data: never counter above max_rate.
            return new int[] {maxRate, maxRate, maxRate};
        }
        int[] ceilings = new int[MAX_ROUNDS];
        for (int i = 0; i < CEILING_SHARE.length; i++) {
            double share = CEILING_SHARE[i] + (random.nextDouble() * 2 - 1) * SHIFT;
            ceilings[i] = loadboardRate + (int) Math.floor(gap * share);
        }
        ceilings[MAX_ROUNDS - 1] = maxRate;
        return ceilings;
    }

    // lastPosition: loadboard_rate before any counter, then our previous counter.
    public static Decision decide(int maxRate, int[] ceilings, int round, int ask, int lastPosition) {
        int ceiling = ceilings[round - 1];
        if (ask <= ceiling) {
            return new Decision(Kind.ACCEPT, ask, false);
        }
        if (round >= MAX_ROUNDS || lastPosition >= maxRate) {
            return new Decision(Kind.REJECT, 0, false);
        }

        double target = Math.min(lastPosition + (ask - lastPosition) / 2.0, ceiling);
        boolean firm = ask > maxRate * (1 + WAY_ABOVE);
        if (firm) {
            target = lastPosition + (target - lastPosition) / 2.0;
        }
        // Rounded down to $25: sounds natural and never lands on max_rate.
        int counter = (int) (Math.floor(target / STEP) * STEP);
        return new Decision(Kind.COUNTER, Math.max(counter, lastPosition), firm);
    }
}
