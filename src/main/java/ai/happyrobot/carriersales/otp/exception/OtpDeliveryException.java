package ai.happyrobot.carriersales.otp.exception;

// The code couldn't be handed to the delivery channel. Never carries the code or the address.
public class OtpDeliveryException extends RuntimeException {

    public OtpDeliveryException(String reason) {
        super(reason);
    }
}
