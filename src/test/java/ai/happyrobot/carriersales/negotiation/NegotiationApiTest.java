package ai.happyrobot.carriersales.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.happyrobot.carriersales.loads.LoadFormatter;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import ai.happyrobot.carriersales.tms.TmsClient;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NegotiationApiTest {

    private static final String API_KEY = "test-api-key";
    private static final int MAX = 2300;
    private static final Set<String> FIELDS = Set.of("error", "message", "decision", "rate", "round", "max_rounds", "final");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionStore sessions;

    @MockitoBean
    private TmsClient tms;

    private String callId;

    @BeforeEach
    void verifiedCallAndLoad() {
        callId = "run-" + UUID.randomUUID();
        sessions.getOrCreate(callId).state(SessionState.OTP_VERIFIED);
        LocalDateTime pickup = LocalDateTime.now(LoadFormatter.BROKERAGE_TIME).plusDays(2);
        when(tms.send(any())).thenReturn(List.of(new TmsLoad("LD00979", "Anaheim", "CA", "92801", "El Paso", "TX",
                "79901", pickup, pickup.plusHours(20), "DRY_VAN", 1961, 679, "OPEN",
                36031, "Electronics", 19, "44ft x 8ft x 9ft", "", MAX)));
    }

    @Test
    void blankInputsAre200WithEveryField() throws Exception {
        JsonNode body = offer("{\"load_id\": \"\", \"mc_number\": \"\", \"counter_rate\": \"\"}", "");

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("MISSING_CALL_ID");
        assertThat(body.get("max_rounds").asInt()).isEqualTo(3);
    }

    @Test
    void refusedBeforeTheOtpWithTheSameFields() throws Exception {
        JsonNode body = offer("{\"load_id\": \"LD00979\", \"counter_rate\": 2000}", "run-not-verified");

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("OTP_NOT_VERIFIED");
    }

    @Test
    void numberOrTextRatesWork() throws Exception {
        assertThat(offer("{\"load_id\": \"LD00979\", \"counter_rate\": 5000}", callId).get("round").asInt()).isEqualTo(1);
        assertThat(offer("{\"load_id\": \"LD00979\", \"counter_rate\": \"$4,000\"}", callId).get("round").asInt()).isEqualTo(2);
    }

    @Test
    void brokenJsonKeepsTheShape() throws Exception {
        JsonNode body = offer("{not json", callId);

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void maxRateNeverInAnyResponse() throws Exception {
        for (String ask : List.of("2800", "2600", "2500")) {
            JsonNode body = offer("{\"load_id\": \"LD00979\", \"counter_rate\": " + ask + "}", callId);

            assertFields(body);
            assertThat(body.toString()).doesNotContain("2300", "2,300");
        }
    }

    @Test
    void needsTheApiKey() throws Exception {
        mockMvc.perform(post("/api/negotiations/offer").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode offer(String body, String call) throws Exception {
        String response = mockMvc.perform(post("/api/negotiations/offer")
                        .header("X-API-Key", API_KEY).header("X-Call-ID", call)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static void assertFields(JsonNode node) {
        assertThat(node.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(FIELDS);
        node.properties().forEach(field -> assertThat(field.getValue().isNull()).as(field.getKey()).isFalse());
    }
}
