package ai.happyrobot.carriersales.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Which deployment this is, from deployment config only (never from a request).
// Development never writes to the TMS. It must agree with the hosting platform's own environment name,
// so a development setting copied into production stops the app instead of silently faking bookings.
// A test code (for automated test calls) can only exist in development.
@ConfigurationProperties(prefix = "app.deployment")
public record DeploymentProperties(@DefaultValue("production") Environment environment, String platformEnvironment,
                                   String testOtpCode) {

    public enum Environment { PRODUCTION, DEVELOPMENT }

    public DeploymentProperties {
        testOtpCode = testOtpCode == null ? "" : testOtpCode.trim();
        if (environment == Environment.DEVELOPMENT && "production".equalsIgnoreCase(trim(platformEnvironment))) {
            throw new IllegalStateException(
                    "APP_ENV=development is not allowed in the platform's production environment");
        }
        if (!testOtpCode.isEmpty() && environment != Environment.DEVELOPMENT) {
            throw new IllegalStateException("TEST_OTP_CODE is only allowed with APP_ENV=development");
        }
        if (!testOtpCode.isEmpty() && !testOtpCode.matches("\\d{6}")) {
            throw new IllegalStateException("TEST_OTP_CODE must be 6 digits");
        }
    }

    public static DeploymentProperties production() {
        return new DeploymentProperties(Environment.PRODUCTION, "", "");
    }

    public static DeploymentProperties development() {
        return new DeploymentProperties(Environment.DEVELOPMENT, "", "");
    }

    public static DeploymentProperties developmentWithTestCode(String testOtpCode) {
        return new DeploymentProperties(Environment.DEVELOPMENT, "", testOtpCode);
    }

    public boolean isDevelopment() {
        return environment == Environment.DEVELOPMENT;
    }

    public boolean hasTestOtpCode() {
        return !testOtpCode.isEmpty();
    }

    public String label() {
        return environment.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "DeploymentProperties[environment=" + label() + ", platformEnvironment=" + platformEnvironment
                + ", testOtpCode=" + (hasTestOtpCode() ? "***" : "") + "]";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
