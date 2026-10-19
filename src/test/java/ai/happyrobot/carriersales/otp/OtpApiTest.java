package ai.happyrobot.carriersales.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.happyrobot.carriersales.call.CallSession;
import ai.happyrobot.carriersales.call.CallSessionStore;
import ai.happyrobot.carriersales.call.CallState;
import ai.happyrobot.carriersales.otp.model.OtpDelivery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The real OTP endpoints and JSON; only delivery is mocked. Contact on file: 133655 -> jane@schneider.com. */
@SpringBootTest
@AutoConfigureMockMvc
class OtpApiTest {

    private static final String API_KEY = "test-api-key"; // from src/test/resources/application.properties
    private static final Set<String> SEND_FIELDS = Set.of("error", "message", "sent", "channel",
            "destination_masked", "expires_in_sec", "resends_left");
    private static final Set<String> VERIFY_FIELDS = Set.of("error", "message", "verified", "attempts_left", "locked");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CallSessionStore sessions;

    @MockitoBean
    private OtpSender sender;

    // --- HappyRobot's test calls: blank inputs ---

    @Test
    void blankSendIs200WithEveryField() throws Exception {
        JsonNode body = call("/api/otp/send", "{\"mc_number\": \"\", \"channel\": \"\"}", null);

        assertFields(body, SEND_FIELDS);
        assertThat(body.get("error").asText()).isEqualTo("MISSING_CALL_ID");
    }

    @Test
    void blankVerifyIs200WithEveryField() throws Exception {
        JsonNode body = call("/api/otp/verify", "{\"mc_number\": \"\", \"code\": \"\"}", null);

        assertFields(body, VERIFY_FIELDS);
        assertThat(body.get("error").asText()).isEqualTo("MISSING_CALL_ID");
    }

    @Test
    void brokenJsonKeepsEachEndpointsShape() throws Exception {
        String callId = verifiedCall();

        assertFields(call("/api/otp/send", "{not json", callId), SEND_FIELDS);
        assertFields(call("/api/otp/verify", "{not json", callId), VERIFY_FIELDS);
    }

    // --- real flow ---

    @Test
    void sendThenVerifyOverHttp() throws Exception {
        String callId = verifiedCall();

        JsonNode sent = call("/api/otp/send", "{\"mc_number\": \"133655\", \"channel\": \"email\"}", callId);

        assertFields(sent, SEND_FIELDS);
        assertThat(sent.get("sent").asBoolean()).isTrue();
        assertThat(sent.get("destination_masked").asText()).isEqualTo("j***@schneider.com");
        String code = deliveredCode();
        assertThat(sent.toString()).doesNotContain(code);

        JsonNode verified = call("/api/otp/verify", "{\"mc_number\": \"133655\", \"code\": \"" + code + "\"}", callId);

        assertFields(verified, VERIFY_FIELDS);
        assertThat(verified.get("verified").asBoolean()).isTrue();
        assertThat(sessions.find(callId).orElseThrow().state()).isEqualTo(CallState.OTP_VERIFIED);
    }

    @Test
    void anEmailInTheRequestIsIgnored() throws Exception {
        String callId = verifiedCall();

        call("/api/otp/send", "{\"mc_number\": \"133655\", \"email\": \"attacker@evil.com\"}", callId);

        ArgumentCaptor<OtpDelivery> delivery = ArgumentCaptor.forClass(OtpDelivery.class);
        verify(sender).send(delivery.capture());
        assertThat(delivery.getValue().email()).isEqualTo("jane@schneider.com");
    }

    @Test
    void needsTheApiKey() throws Exception {
        mockMvc.perform(post("/api/otp/send").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private String verifiedCall() {
        String callId = "run-" + UUID.randomUUID();
        CallSession session = sessions.getOrCreate(callId);
        session.state(CallState.FMCSA_VERIFIED);
        session.mcNumber("133655");
        session.carrierName("Schneider National Carriers Inc");
        return callId;
    }

    private String deliveredCode() {
        ArgumentCaptor<OtpDelivery> delivery = ArgumentCaptor.forClass(OtpDelivery.class);
        verify(sender).send(delivery.capture());
        return delivery.getValue().code();
    }

    private JsonNode call(String path, String body, String callId) throws Exception {
        var request = post(path).header("X-API-Key", API_KEY).contentType(MediaType.APPLICATION_JSON).content(body);
        if (callId != null) {
            request.header("X-Call-ID", callId);
        }
        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    /** Exactly these fields, none null: HappyRobot may not detect null or missing fields. */
    private static void assertFields(JsonNode node, Set<String> expected) {
        assertThat(node.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(expected);
        node.properties().forEach(field -> assertThat(field.getValue().isNull()).as(field.getKey()).isFalse());
    }
}
