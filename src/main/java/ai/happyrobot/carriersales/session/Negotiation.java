package ai.happyrobot.carriersales.session;

import java.time.Instant;
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
    private String status = "open";
    private Instant closedAt;

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

    // open, accepted, rejected, or released (the agreed load fell through).
    public String status() {
        return status;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public void record(Round round) {
        rounds.add(round);
        switch (round.decision()) {
            case "counter" -> lastPosition = round.rate();
            case "accept" -> {
                agreedRate = round.rate();
                close("accepted");
            }
            case "reject" -> close("rejected");
            default -> { }
        }
    }

    void release() {
        close("released");
    }

    private void close(String newStatus) {
        status = newStatus;
        closedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "Negotiation[load=" + loadId + ", rounds=" + rounds.size() + ", agreed=" + agreedRate + "]";
    }
}
