package ai.happyrobot.carriersales.fmcsa;

import ai.happyrobot.carriersales.config.FmcsaProperties;
import ai.happyrobot.carriersales.fmcsa.exception.FmcsaUnavailableException;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// Unknown MC numbers come back as 200 with an empty list: an empty Optional, not an error.
@Component
public class FmcsaClient {

    private static final Logger log = LoggerFactory.getLogger(FmcsaClient.class);
    private static final String BASE_URL = "https://mobile.fmcsa.dot.gov/qc/services";
    // FMCSA sometimes hangs instead of answering: a short timeout plus one retry recovers most of those.
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;
    private final FmcsaProperties properties;

    public FmcsaClient(RestClient.Builder builder, FmcsaProperties properties) {
        this.restClient = builder.baseUrl(BASE_URL).build();
        this.properties = properties;
    }

    public Optional<FmcsaCarrier> findByMcNumber(String mcNumber) {
        JsonNode body = fetch(mcNumber);
        if (body == null || !body.path("content").isArray()) {
            throw new FmcsaUnavailableException("FMCSA reply has no content list");
        }
        JsonNode carrier = body.path("content").path(0).path("carrier");
        return carrier.isMissingNode() ? Optional.empty() : Optional.of(toCarrier(carrier));
    }

    // Only timeouts and connection errors are retried; an HTTP error won't fix itself.
    private JsonNode fetch(String mcNumber) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.get()
                        .uri("/carriers/docket-number/{mc}?webKey={key}", mcNumber, properties.apiKey())
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException e) {
                // The exception message has the URL, which has the key: log the status only.
                log.warn("FMCSA lookup failed with HTTP {}", e.getStatusCode().value());
                throw new FmcsaUnavailableException("FMCSA answered HTTP " + e.getStatusCode().value());
            } catch (RestClientException e) {
                log.warn("FMCSA lookup attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.getClass().getSimpleName());
                if (attempt == MAX_ATTEMPTS) {
                    throw new FmcsaUnavailableException("FMCSA unreachable: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    // Read as a tree: FMCSA's camelCase doesn't match our snake_case mapper.
    private static FmcsaCarrier toCarrier(JsonNode c) {
        return new FmcsaCarrier(
                text(c, "legalName"),
                text(c, "dbaName"),
                text(c, "allowedToOperate"),
                text(c, "statusCode"),
                text(c, "commonAuthorityStatus"),
                text(c, "contractAuthorityStatus"),
                text(c, "safetyRating"),
                text(c, "phyCity"),
                text(c, "phyState"),
                c.path("totalPowerUnits").asInt(0),
                c.path("totalDrivers").asInt(0));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
