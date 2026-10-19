package ai.happyrobot.carriersales.fmcsa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.happyrobot.carriersales.config.FmcsaProperties;
import ai.happyrobot.carriersales.fmcsa.exception.FmcsaUnavailableException;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

/** Replays real FMCSA replies (src/test/resources/fmcsa/, captured from the live API on 2026-10-19). */
class FmcsaClientTest {

    private static final String KEY = "test-fmcsa-key";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer fmcsa = MockRestServiceServer.bindTo(builder).build();
    private final FmcsaClient client = new FmcsaClient(builder, new FmcsaProperties(KEY));

    @Test
    void readsAnActiveCarrier() {
        expectLookup("133655").andRespond(withSuccess(fixture("docket-133655.json"), MediaType.APPLICATION_JSON));

        FmcsaCarrier carrier = client.findByMcNumber("133655").orElseThrow();

        assertThat(carrier.legalName()).isEqualTo("SCHNEIDER NATIONAL CARRIERS INC");
        assertThat(carrier.allowedToOperate()).isEqualTo("Y");
        assertThat(carrier.statusCode()).isEqualTo("A");
        assertThat(carrier.commonAuthorityStatus()).isEqualTo("A");
        assertThat(carrier.safetyRating()).isEqualTo("S");
        assertThat(carrier.city()).isEqualTo("GREEN BAY");
        assertThat(carrier.state()).isEqualTo("WI");
        assertThat(carrier.powerUnits()).isEqualTo(9866);
        fmcsa.verify();
    }

    @Test
    void readsAnOutOfServiceCarrier() {
        expectLookup("750000").andRespond(withSuccess(fixture("docket-750000.json"), MediaType.APPLICATION_JSON));

        FmcsaCarrier carrier = client.findByMcNumber("750000").orElseThrow();

        assertThat(carrier.allowedToOperate()).isEqualTo("N");
        assertThat(carrier.statusCode()).isEqualTo("I");
    }

    @Test
    void unknownMcIsEmptyNotAnError() {
        expectLookup("999999999").andRespond(withSuccess(fixture("docket-999999999.json"), MediaType.APPLICATION_JSON));

        assertThat(client.findByMcNumber("999999999")).isEmpty();
    }

    @Test
    void blockedOrFailingFmcsaIsUnavailableWithoutLeakingTheKey() {
        // What FMCSA sends from a geo-blocked network: an HTML 403 page.
        expectLookup("133655").andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.TEXT_HTML).body("<html>403 Forbidden</html>"));

        assertThatThrownBy(() -> client.findByMcNumber("133655"))
                .isInstanceOf(FmcsaUnavailableException.class)
                .hasMessageContaining("403")
                .hasMessageNotContaining(KEY);
    }

    @Test
    void timeoutIsUnavailable() {
        expectLookup("133655").andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.findByMcNumber("133655"))
                .isInstanceOf(FmcsaUnavailableException.class)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void unexpectedReplyShapeIsUnavailable() {
        expectLookup("133655").andRespond(withSuccess("{\"something\":\"else\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findByMcNumber("133655")).isInstanceOf(FmcsaUnavailableException.class);
    }

    private ResponseActions expectLookup(String mc) {
        return fmcsa.expect(requestTo(Matchers.startsWith(
                        "https://mobile.fmcsa.dot.gov/qc/services/carriers/docket-number/" + mc + "?")))
                .andExpect(queryParam("webKey", KEY));
    }

    private static String fixture(String name) {
        try (InputStream in = FmcsaClientTest.class.getResourceAsStream("/fmcsa/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
