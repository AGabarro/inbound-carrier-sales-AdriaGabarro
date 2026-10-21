package ai.happyrobot.carriersales.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.tms")
public record TmsProperties(
        @NotBlank String host,
        @NotNull @Positive @Max(65535) Integer port,
        @NotBlank String token,
        // Per read attempt (connect plus all reads). Healthy replies take under 1s; faults never answer.
        @NotNull @DefaultValue("2s") Duration attemptTimeout,
        // A booking is sent once, so it gets more time before its result becomes uncertain.
        @NotNull @DefaultValue("4s") Duration bookTimeout) {

    @Override
    public String toString() {
        return "TmsProperties[host=" + host + ", port=" + port + ", token=***"
                + ", attemptTimeout=" + attemptTimeout + ", bookTimeout=" + bookTimeout + "]";
    }
}
