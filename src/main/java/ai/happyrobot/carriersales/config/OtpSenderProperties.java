package ai.happyrobot.carriersales.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Empty URL: codes aren't delivered (local runs).
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
