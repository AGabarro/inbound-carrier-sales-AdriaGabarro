package ai.happyrobot.carriersales.tms;

import java.util.List;

public sealed interface TmsResponse {

    record Success(List<TmsLoad> loads) implements TmsResponse {
        public Success {
            loads = List.copyOf(loads);
        }
    }

    // A deliberate answer from the TMS (NOT_FOUND, MISSING_FIELD...): never retried.
    record TmsError(String code, String message) implements TmsResponse {
    }

    // The response can't be trusted and none of it is used: retried by the client.
    // detail describes what was wrong, never field values (they may include MAX_BUY).
    record Fault(FaultKind kind, String detail) implements TmsResponse {
    }

    enum FaultKind {
        EMPTY,
        PARTIAL,
        MALFORMED
    }
}
