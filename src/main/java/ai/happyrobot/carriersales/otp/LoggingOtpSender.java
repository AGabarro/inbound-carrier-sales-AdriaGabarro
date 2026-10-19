package ai.happyrobot.carriersales.otp;

import ai.happyrobot.carriersales.otp.model.OtpDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Used when no OTP Sender webhook is configured (local runs, tests): sends nothing. Never logs the code.
public class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(OtpDelivery delivery) {
        log.info("OTP for call {} (MC {}) would be sent to {}", delivery.callId(), delivery.mcNumber(),
                OtpService.mask(delivery.email()));
    }
}
