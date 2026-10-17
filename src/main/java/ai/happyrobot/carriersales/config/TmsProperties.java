package ai.happyrobot.carriersales.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Legacy TMS connection, bound from {@code app.tms.*} in {@code application.yml}.
 * {@code toString()} masks the token so a stray log line can't leak it.
 */
@Validated
@ConfigurationProperties(prefix = "app.tms")
public record TmsProperties(
        @NotBlank String host,
        @NotNull @Positive @Max(65535) Integer port,
        @NotBlank String token) {

    @Override
    public String toString() {
        return "TmsProperties[host=" + host + ", port=" + port + ", token=***]";
    }
}
