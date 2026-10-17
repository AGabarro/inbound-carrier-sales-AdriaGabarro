package ai.happyrobot.carriersales.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Inbound auth, bound from {@code app.security.*}. {@code apiKey} is the value HappyRobot must send
 * in the {@code X-API-Key} header. {@code toString()} masks it.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@NotBlank String apiKey) {

    @Override
    public String toString() {
        return "SecurityProperties[apiKey=***]";
    }
}
