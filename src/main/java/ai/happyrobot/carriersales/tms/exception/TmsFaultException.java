package ai.happyrobot.carriersales.tms.exception;

// The TMS reply can't be trusted and none of it is used. The client retries these.
// detail describes what was wrong, never field values (they may include MAX_BUY).
public class  TmsFaultException extends RuntimeException {

    private final FaultKind kind;
    private final String detail;

    public TmsFaultException(FaultKind kind, String detail) {
        super(kind + ": " + detail);
        this.kind = kind;
        this.detail = detail;
    }

    public FaultKind kind() {
        return kind;
    }

    public String detail() {
        return detail;
    }
}
