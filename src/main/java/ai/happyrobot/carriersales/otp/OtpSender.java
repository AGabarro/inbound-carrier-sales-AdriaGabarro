package ai.happyrobot.carriersales.otp;

import ai.happyrobot.carriersales.otp.model.OtpDelivery;

// Throws OtpDeliveryException when the code can't be handed over.
public interface OtpSender {

    void send(OtpDelivery delivery);
}
