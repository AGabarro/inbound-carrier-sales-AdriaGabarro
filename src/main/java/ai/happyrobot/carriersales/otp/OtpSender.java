package ai.happyrobot.carriersales.otp;

import ai.happyrobot.carriersales.otp.exception.OtpDeliveryException;
import ai.happyrobot.carriersales.otp.model.OtpDelivery;

/** Hands a one-time code to whatever delivers it to the carrier. Throws {@link OtpDeliveryException} on failure. */
public interface OtpSender {

    void send(OtpDelivery delivery);
}
