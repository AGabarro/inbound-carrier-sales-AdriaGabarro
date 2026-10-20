package ai.happyrobot.carriersales.loads.model;

import ai.happyrobot.carriersales.tms.model.TmsLoad;

// Internal only, never serialized: the full TMS record includes MAX_BUY.
public record LoadLookup(String error, String message, TmsLoad load) {

    public static LoadLookup found(TmsLoad load) {
        return new LoadLookup("", "", load);
    }

    public static LoadLookup problem(String error, String message) {
        return new LoadLookup(error, message, null);
    }

    @Override
    public String toString() {
        return "LoadLookup[error=" + error + ", load=" + load + "]";
    }
}
