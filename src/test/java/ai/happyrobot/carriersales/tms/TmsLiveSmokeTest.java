package ai.happyrobot.carriersales.tms;

import static org.assertj.core.api.Assertions.assertThat;

import ai.happyrobot.carriersales.config.TmsProperties;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import ai.happyrobot.carriersales.tms.protocol.TmsRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * One read-only search against the real TMS. Skipped unless TMS_TOKEN is set, so it never runs in
 * the Docker build or without credentials. Run it on purpose with:
 * {@code set -a; . ./.env; set +a; ./mvnw test -Dtest=TmsLiveSmokeTest}
 */
@EnabledIfEnvironmentVariable(named = "TMS_TOKEN", matches = ".+")
class TmsLiveSmokeTest {

    @Test
    void searchesTheRealTms() {
        TmsProperties properties = new TmsProperties(
                System.getenv().getOrDefault("TMS_HOST", "tramway.proxy.rlwy.net"),
                Integer.valueOf(System.getenv().getOrDefault("TMS_PORT", "17159")),
                System.getenv("TMS_TOKEN"),
                Duration.ofSeconds(2), Duration.ofSeconds(4));

        List<TmsLoad> loads = new TmsClient(properties).send(TmsRequest.loadQuery(Map.of("DEST_STATE", "TX")));

        assertThat(loads).isNotEmpty();
        assertThat(loads).allSatisfy(load -> {
            assertThat(load.destinationState()).isEqualTo("TX");
            assertThat(load.maxBuy()).isNull();
        });
    }
}
