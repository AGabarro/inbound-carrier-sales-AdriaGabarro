package ai.happyrobot.carriersales.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// The key callers must send in X-API-Key.
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@NotBlank String apiKey) {

    @Override
    public String toString() {
        return "SecurityProperties[apiKey=***]";
    }
}
