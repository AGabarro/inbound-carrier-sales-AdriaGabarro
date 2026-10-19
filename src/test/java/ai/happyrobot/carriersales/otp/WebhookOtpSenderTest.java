package ai.happyrobot.carriersales.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ai.happyrobot.carriersales.config.OtpSenderProperties;
import ai.happyrobot.carriersales.otp.exception.OtpDeliveryException;
import ai.happyrobot.carriersales.otp.model.OtpDelivery;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WebhookOtpSenderTest {

    private static final String URL = "https://workflows.example.test/webhook/otp-sender";
    private static final String KEY = "test-otp-sender-key";
    private static final OtpDelivery DELIVERY =
            new OtpDelivery("run-1", "133655", "Schneider National Carriers Inc", "jane@schneider.com", "482913", 5);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer webhook = MockRestServiceServer.bindTo(builder).build();
    private final WebhookOtpSender sender = new WebhookOtpSender(builder, new OtpSenderProperties(URL, KEY));

    @Test
    void postsTheTriggerSchemaWithTheKeyHeader() {
        webhook.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", KEY))
                .andExpect(content().json("""
                        {"call_id": "run-1", "mc_number": "133655", "carrier_name": "Schneider National Carriers Inc",
                         "channel": "email", "email": "jane@schneider.com", "phone": "", "code": "482913",
                         "expires_in_min": "5"}""", true))
                .andRespond(withSuccess());

        assertThatCode(() -> sender.send(DELIVERY)).doesNotThrowAnyException();
        webhook.verify();
    }

    @Test
    void networkErrorIsRetriedOnce() {
        webhook.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("timed out")));
        webhook.expect(requestTo(URL)).andRespond(withSuccess());

        assertThatCode(() -> sender.send(DELIVERY)).doesNotThrowAnyException();
        webhook.verify();
    }

    @Test
    void twoNetworkErrorsFailWithoutLeakingSecrets() {
        webhook.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("timed out")));
        webhook.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("timed out")));

        assertThatThrownBy(() -> sender.send(DELIVERY))
                .isInstanceOf(OtpDeliveryException.class)
                .hasMessageNotContaining("482913")
                .hasMessageNotContaining(KEY)
                .hasMessageNotContaining("webhook");
    }

    @Test
    void rejectedRequestIsNotRetried() {
        webhook.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> sender.send(DELIVERY))
                .isInstanceOf(OtpDeliveryException.class)
                .hasMessageContaining("401");
        webhook.verify(); // exactly one request
    }

    @Test
    void propertiesNeverPrintTheUrlOrKey() {
        assertThat(new OtpSenderProperties(URL, KEY).toString())
                .doesNotContain(URL).doesNotContain(KEY);
    }
}
