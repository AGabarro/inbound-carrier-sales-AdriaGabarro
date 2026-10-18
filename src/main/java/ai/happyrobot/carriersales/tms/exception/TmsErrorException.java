package ai.happyrobot.carriersales.tms.exception;

// A deliberate answer from the TMS (NOT_FOUND, MISSING_FIELD...). Never retried.
public class TmsErrorException extends RuntimeException {

    private final String code;
    private final String tmsMessage;

    public TmsErrorException(String code, String tmsMessage) {
        super("TMS error " + code + ": " + tmsMessage);
        this.code = code;
        this.tmsMessage = tmsMessage;
    }

    public String code() {
        return code;
    }

    public String tmsMessage() {
        return tmsMessage;
    }
}
