package ai.happyrobot.carriersales.loads.model;

// One search result: just enough for the agent to say "I have a load from X to Y on Z for $N".
public record LoadSummary(
        String loadId,
        String origin,
        String destination,
        String pickupDatetime,
        String pickupDisplay,
        String equipmentType,
        int loadboardRate,
        int miles) {
}
