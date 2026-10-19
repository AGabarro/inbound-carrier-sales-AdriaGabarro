package ai.happyrobot.carriersales.loads.model;

import java.util.List;

// Always HTTP 200 with every field present (HappyRobot learns the fields from one test call).
// error is "" on success, otherwise a code; message is what the agent says. No loads is not an error: found=false.
public record SearchResult(String error, String message, boolean found, List<LoadSummary> loads) {

    public static SearchResult problem(String error, String message) {
        return new SearchResult(error, message, false, List.of());
    }
}
