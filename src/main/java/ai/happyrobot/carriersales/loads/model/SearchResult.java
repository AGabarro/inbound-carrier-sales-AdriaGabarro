package ai.happyrobot.carriersales.loads.model;

import java.util.List;

// "found" lets the agent branch without reading the list; message is what it can say.
public record SearchResult(boolean found, String message, List<LoadSummary> loads) {
}
