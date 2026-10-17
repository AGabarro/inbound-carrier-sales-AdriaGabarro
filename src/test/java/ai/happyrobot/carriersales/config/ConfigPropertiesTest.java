package ai.happyrobot.carriersales.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Binding, fail-fast validation and secret masking of the config records. No YAML loaded. */
class ConfigPropertiesTest {

    private static final String[] VALID = {
            "app.tms.host=tms.example.com",
            "app.tms.port=17159",
            "app.tms.token=super-secret-tms-token",
            "app.fmcsa.api-key=super-secret-fmcsa-key",
            "app.security.api-key=super-secret-api-key"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void bindsCompleteConfig() {
        runner.withPropertyValues(VALID).run(context -> {
            assertThat(context).hasNotFailed();
            TmsProperties tms = context.getBean(TmsProperties.class);
            assertThat(tms.host()).isEqualTo("tms.example.com");
            assertThat(tms.port()).isEqualTo(17159);
            assertThat(tms.token()).isEqualTo("super-secret-tms-token");
            assertThat(context.getBean(FmcsaProperties.class).apiKey()).isEqualTo("super-secret-fmcsa-key");
            assertThat(context.getBean(SecurityProperties.class).apiKey()).isEqualTo("super-secret-api-key");
        });
    }

    @Test
    void failsFastOnBlankSecret() {
        runner.withPropertyValues(VALID)
                .withPropertyValues("app.tms.token=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeChain(context.getStartupFailure())).contains("app.tms.token");
                });
    }

    @Test
    void failsFastOnInvalidPort() {
        runner.withPropertyValues(VALID)
                .withPropertyValues("app.tms.port=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeChain(context.getStartupFailure())).contains("app.tms.port");
                });
    }

    @Test
    void startupFailureNeverEchoesSecrets() {
        // Port invalid while the secrets are set: the error must not print their values.
        runner.withPropertyValues(VALID)
                .withPropertyValues("app.tms.port=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeChain(context.getStartupFailure())).doesNotContain("super-secret");
                });
    }

    @Test
    void toStringMasksSecrets() {
        runner.withPropertyValues(VALID).run(context -> {
            assertThat(context.getBean(TmsProperties.class).toString())
                    .doesNotContain("super-secret")
                    .contains("tms.example.com");
            assertThat(context.getBean(FmcsaProperties.class).toString()).doesNotContain("super-secret");
            assertThat(context.getBean(SecurityProperties.class).toString()).doesNotContain("super-secret");
        });
    }

    /** Messages of the whole cause chain: the property name sits in a nested binding exception. */
    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Configuration
    @EnableConfigurationProperties({TmsProperties.class, FmcsaProperties.class, SecurityProperties.class})
    static class PropertiesConfig {
    }
}
