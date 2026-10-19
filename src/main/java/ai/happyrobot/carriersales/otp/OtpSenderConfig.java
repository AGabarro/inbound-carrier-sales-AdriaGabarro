package ai.happyrobot.carriersales.otp;

import ai.happyrobot.carriersales.config.OtpSenderProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// Real delivery through the OTP Sender webhook when it's configured; otherwise a stub that sends nothing.
@Configuration
public class OtpSenderConfig {

    @Bean
    public OtpSender otpSender(OtpSenderProperties properties, RestClient.Builder builder) {
        if (!properties.configured()) {
            return new LoggingOtpSender();
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("OTP_SENDER_API_KEY must be set when OTP_SENDER_WEBHOOK_URL is set");
        }
        return new WebhookOtpSender(builder, properties);
    }
}
