package ai.happyrobot.carriersales.call;

import java.util.ArrayList;
import java.util.List;

// The call's rate negotiation. Holds max_rate and the ceilings: never in a response or toString().
public class Negotiation {

    public record Round(int number, int carrierAsk, String decision, int rate) {
    }

    private final String loadId;
    private final int loadboardRate;
    private final int maxRate;
    private final int[] ceilings;
    private final List<Round> rounds = new ArrayList<>();
    private int lastPosition;
    private int agreedRate;

    public Negotiation(String loadId, int loadboardRate, int maxRate, int[] ceilings) {
        this.loadId = loadId;
        this.loadboardRate = loadboardRate;
        this.maxRate = maxRate;
        this.ceilings = ceilings.clone();
        this.lastPosition = loadboardRate;
    }

    public String loadId() {
        return loadId;
    }

    public int loadboardRate() {
        return loadboardRate;
    }

    public int maxRate() {
        return maxRate;
    }

    public int[] ceilings() {
        return ceilings.clone();
    }

    public List<Round> rounds() {
        return List.copyOf(rounds);
    }

    public int roundsUsed() {
        return rounds.size();
    }

    public int lastPosition() {
        return lastPosition;
    }

    public int agreedRate() {
        return agreedRate;
    }

    public void record(Round round) {
        rounds.add(round);
        if ("counter".equals(round.decision())) {
            lastPosition = round.rate();
        } else if ("accept".equals(round.decision())) {
            agreedRate = round.rate();
        }
    }

    @Override
    public String toString() {
        return "Negotiation[load=" + loadId + ", rounds=" + rounds.size() + ", agreed=" + agreedRate + "]";
    }
}
