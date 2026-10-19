package ai.happyrobot.carriersales.fmcsa.exception;

// FMCSA could not be asked (timeout, non-200, unreadable reply). Never carries the URL: it contains the webKey.
public class FmcsaUnavailableException extends RuntimeException {

    public FmcsaUnavailableException(String reason) {
        super(reason);
    }
}
