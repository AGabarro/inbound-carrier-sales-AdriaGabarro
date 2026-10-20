package ai.happyrobot.carriersales.otp;

import ai.happyrobot.carriersales.config.OtpSenderProperties;
import ai.happyrobot.carriersales.otp.exception.OtpDeliveryException;
import ai.happyrobot.carriersales.otp.model.OtpDelivery;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Hands the code to the OTP Sender workflow. One retry on network errors; never logs URL, key or code.
public class WebhookOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookOtpSender.class);
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;
    private final OtpSenderProperties properties;

    public WebhookOtpSender(RestClient.Builder builder, OtpSenderProperties properties) {
        this.restClient = builder.build();
        this.properties = properties;
    }

    @Override
    public void send(OtpDelivery delivery) {
        // The trigger's schema, all strings.
        Map<String, String> body = new LinkedHashMap<>();
        body.put("call_id", delivery.callId());
        body.put("mc_number", delivery.mcNumber());
        body.put("carrier_name", delivery.carrierName());
        body.put("channel", "email");
        body.put("email", delivery.email());
        body.put("phone", "");
        body.put("code", delivery.code());
        body.put("expires_in_min", String.valueOf(delivery.expiresInMin()));

        for (int attempt = 1; ; attempt++) {
            try {
                restClient.post()
                        .uri(properties.webhookUrl())
                        .header("x-api-key", properties.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("OTP for call {} handed to the OTP Sender workflow", delivery.callId());
                return;
            } catch (RestClientResponseException e) {
                log.warn("OTP Sender rejected the request with HTTP {}", e.getStatusCode().value());
                throw new OtpDeliveryException("OTP Sender answered HTTP " + e.getStatusCode().value());
            } catch (RestClientException e) {
                log.warn("OTP Sender attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.getClass().getSimpleName());
                if (attempt == MAX_ATTEMPTS) {
                    throw new OtpDeliveryException("OTP Sender unreachable: " + e.getClass().getSimpleName());
                }
            }
        }
    }
}
