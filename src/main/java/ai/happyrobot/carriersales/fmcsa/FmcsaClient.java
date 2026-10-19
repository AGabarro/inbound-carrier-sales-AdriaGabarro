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

/**
 * Looks up a carrier by MC (docket) number in the FMCSA QCMobile API. Unknown MC numbers come back as
 * HTTP 200 with an empty "content" list, so they are an empty Optional, not an error.
 */
@Component
public class FmcsaClient {

    private static final Logger log = LoggerFactory.getLogger(FmcsaClient.class);
    private static final String BASE_URL = "https://mobile.fmcsa.dot.gov/qc/services";

    private final RestClient restClient;
    private final FmcsaProperties properties;

    // Spring Boot's builder: timeouts come from spring.http.client.* in application.yml.
    public FmcsaClient(RestClient.Builder builder, FmcsaProperties properties) {
        this.restClient = builder.baseUrl(BASE_URL).build();
        this.properties = properties;
    }

    public Optional<FmcsaCarrier> findByMcNumber(String mcNumber) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri("/carriers/docket-number/{mc}?webKey={key}", mcNumber, properties.apiKey())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Only the status: the exception message contains the URL, and the URL contains the key.
            log.warn("FMCSA lookup failed with HTTP {}", e.getStatusCode().value());
            throw new FmcsaUnavailableException("FMCSA answered HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            log.warn("FMCSA lookup failed: {}", e.getClass().getSimpleName());
            throw new FmcsaUnavailableException("FMCSA unreachable: " + e.getClass().getSimpleName());
        }

        if (body == null || !body.path("content").isArray()) {
            throw new FmcsaUnavailableException("FMCSA reply has no content list");
        }
        JsonNode carrier = body.path("content").path(0).path("carrier");
        return carrier.isMissingNode() ? Optional.empty() : Optional.of(toCarrier(carrier));
    }

    // FMCSA uses camelCase names; reading the JSON tree avoids our app-wide snake_case setting.
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

    // FMCSA sends JSON null for missing values; keep those as Java null.
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
