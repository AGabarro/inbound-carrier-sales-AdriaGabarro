package ai.happyrobot.carriersales.web.exception;

// Input the caller can fix (unknown equipment, bad date...). The message is said to the carrier.
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
