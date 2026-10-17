package ai.happyrobot.carriersales.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** FMCSA API access, bound from {@code app.fmcsa.*}. {@code toString()} masks the key. */
@Validated
@ConfigurationProperties(prefix = "app.fmcsa")
public record FmcsaProperties(@NotBlank String apiKey) {

    @Override
    public String toString() {
        return "FmcsaProperties[apiKey=***]";
    }
}
