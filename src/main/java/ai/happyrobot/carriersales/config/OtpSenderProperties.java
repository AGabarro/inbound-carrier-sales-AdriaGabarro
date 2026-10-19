package ai.happyrobot.carriersales.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HappyRobot "OTP Sender" workflow webhook, bound from {@code app.otp-sender.*}. Both empty means no real
 * delivery (codes are only logged as "would be sent", never the code itself). toString masks the key.
 */
@ConfigurationProperties(prefix = "app.otp-sender")
public record OtpSenderProperties(String webhookUrl, String apiKey) {

    public boolean configured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    @Override
    public String toString() {
        return "OtpSenderProperties[webhookUrl=" + (configured() ? "***" : "") + ", apiKey=***]";
    }
}
