package ai.happyrobot.carriersales.tms.exception;

public enum FaultKind {
    // detected by the parser
    EMPTY,
    PARTIAL,
    MALFORMED,
    // detected by the client
    TIMEOUT,
    IO_ERROR
}
