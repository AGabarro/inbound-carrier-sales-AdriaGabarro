package ai.happyrobot.carriersales.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Which deployment this is, from deployment config only (never from a request).
// Development never writes to the TMS. It must agree with the hosting platform's own environment name,
// so a development setting copied into production stops the app instead of silently faking bookings.
@ConfigurationProperties(prefix = "app.deployment")
public record DeploymentProperties(@DefaultValue("production") Environment environment, String platformEnvironment) {

    public enum Environment { PRODUCTION, DEVELOPMENT }

    public DeploymentProperties {
        if (environment == Environment.DEVELOPMENT && "production".equalsIgnoreCase(trim(platformEnvironment))) {
            throw new IllegalStateException(
                    "APP_ENV=development is not allowed in the platform's production environment");
        }
    }

    public static DeploymentProperties production() {
        return new DeploymentProperties(Environment.PRODUCTION, "");
    }

    public static DeploymentProperties development() {
        return new DeploymentProperties(Environment.DEVELOPMENT, "");
    }

    public boolean isDevelopment() {
        return environment == Environment.DEVELOPMENT;
    }

    public String label() {
        return environment.name().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
