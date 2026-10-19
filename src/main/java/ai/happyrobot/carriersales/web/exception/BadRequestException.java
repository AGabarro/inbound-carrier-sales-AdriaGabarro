package ai.happyrobot.carriersales.web.exception;

// Input the carrier can fix (unknown equipment, bad date...). Services turn it into a 200 result with
// error INVALID_REQUEST; the message is said to the carrier.
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
