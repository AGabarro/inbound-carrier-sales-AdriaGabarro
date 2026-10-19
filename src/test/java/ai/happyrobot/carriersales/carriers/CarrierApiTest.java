package ai.happyrobot.carriersales.carriers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.happyrobot.carriersales.fmcsa.FmcsaClient;
import ai.happyrobot.carriersales.fmcsa.exception.FmcsaUnavailableException;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The real endpoint and JSON, with only FMCSA mocked. Every answer is a 200 with every field. */
@SpringBootTest
@AutoConfigureMockMvc
class CarrierApiTest {

    private static final String API_KEY = "test-api-key"; // from src/test/resources/application.properties
    private static final Set<String> FIELDS = Set.of("error", "message", "eligible", "reason", "mc_number",
            "carrier_name", "dba_name", "home_base", "power_units", "drivers", "safety_rating");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private FmcsaClient fmcsa;

    @Test
    void blankMcIs200WithEveryField() throws Exception {
        JsonNode body = verify("{\"mc_number\": \"\"}");

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.get("message").asText()).isEqualTo("I didn't catch your MC number. Can you read it to me again?");
    }

    @Test
    void eligibleCarrierGetsEveryFieldWithProfile() throws Exception {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(new FmcsaCarrier(
                "SCHNEIDER NATIONAL CARRIERS INC", null, "Y", "A", "A", "A", "S", "GREEN BAY", "WI", 9866, 11335)));

        JsonNode body = verify("{\"mc_number\": \"MC 133655\"}");

        assertFields(body);
        assertThat(body.get("error").asText()).isEmpty();
        assertThat(body.get("eligible").asBoolean()).isTrue();
        assertThat(body.get("carrier_name").asText()).isEqualTo("Schneider National Carriers Inc");
        assertThat(body.get("dba_name").asText()).isEmpty();
        assertThat(body.get("home_base").asText()).isEqualTo("Green Bay, WI");
        assertThat(body.get("power_units").asInt()).isEqualTo(9866);
        assertThat(body.get("safety_rating").asText()).isEqualTo("Satisfactory");
    }

    @Test
    void notAuthorizedIsNotEligibleWithoutProfile() throws Exception {
        when(fmcsa.findByMcNumber(any())).thenReturn(Optional.of(new FmcsaCarrier(
                "C & C PRESTIGE LOGISTICS LLC", null, "Y", "I", "N", "N", null, "X", "TX", 1, 1)));

        JsonNode body = verify("{\"mc_number\": \"100000\"}");

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("NOT_ELIGIBLE");
        assertThat(body.get("reason").asText()).isEqualTo("NO_ACTIVE_AUTHORITY");
        assertThat(body.get("carrier_name").asText()).isEmpty();
        assertThat(body.toString()).doesNotContain("Prestige", "PRESTIGE");
    }

    @Test
    void unknownMcIsNotFound() throws Exception {
        when(fmcsa.findByMcNumber(any())).thenReturn(Optional.empty());

        JsonNode body = verify("{\"mc_number\": \"999999\"}");

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.get("reason").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void fmcsaDownIsA200WithASentence() throws Exception {
        when(fmcsa.findByMcNumber(any())).thenThrow(new FmcsaUnavailableException("FMCSA answered HTTP 403"));

        JsonNode body = verify("{\"mc_number\": \"133655\"}");

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("FMCSA_UNAVAILABLE");
        assertThat(body.get("message").asText())
                .isEqualTo("I can't check your MC number right now. Please try again in a few minutes.");
    }

    @Test
    void brokenOrMissingBodyIsA200InvalidRequest() throws Exception {
        for (String body : new String[] {"{not json", ""}) {
            JsonNode result = verify(body);

            assertFields(result);
            assertThat(result.get("error").asText()).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    void needsTheApiKey() throws Exception {
        mockMvc.perform(post("/api/carriers/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mc_number\": \"133655\"}"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode verify(String body) throws Exception {
        String response = mockMvc.perform(post("/api/carriers/verify")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    /** Exactly these fields, none null: HappyRobot may not detect null or missing fields. */
    private static void assertFields(JsonNode node) {
        assertThat(node.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(FIELDS);
        node.properties().forEach(field -> assertThat(field.getValue().isNull()).as(field.getKey()).isFalse());
    }
}
