package ai.happyrobot.carriersales.loads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.happyrobot.carriersales.tms.TmsClient;
import ai.happyrobot.carriersales.tms.exception.FaultKind;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.EquipmentType;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The real endpoints and JSON, with only the TMS mocked. HappyRobot learns a tool's fields from one
 * test call with blank inputs and only reads 200 responses, so every answer is a 200 with every field.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoadApiTest {

    private static final String API_KEY = "test-api-key"; // from src/test/resources/application.properties
    private static final int SECRET_MAX_BUY = 98765;

    private static final Set<String> SEARCH_FIELDS = Set.of("error", "message", "found", "loads");
    private static final Set<String> SUMMARY_FIELDS = Set.of("load_id", "origin", "destination",
            "pickup_datetime", "pickup_display", "equipment_type", "loadboard_rate", "miles");
    private static final Set<String> DETAILS_FIELDS = Set.of("error", "message", "available", "load_id", "origin",
            "destination", "pickup_datetime", "pickup_display", "delivery_datetime", "delivery_display",
            "equipment_type", "loadboard_rate", "miles",
            "weight", "commodity_type", "num_of_pieces", "dimensions", "notes");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private TmsClient tms;

    // --- HappyRobot's test calls: blank inputs ---

    @Test
    void blankSearchIs200WithEveryField() throws Exception {
        JsonNode body = call(get("/api/loads/search").param("origin_state", "").param("origin_city", "")
                .param("destination_state", "").param("destination_city", "").param("equipment", "")
                .param("pickup_date", ""));

        assertFields(body, SEARCH_FIELDS);
        assertThat(body.get("error").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.get("loads").isArray()).isTrue();
    }

    @Test
    void emptyLoadIdIs200WithEveryField() throws Exception {
        for (String path : List.of("/api/loads/", "/api/loads")) {
            JsonNode body = call(get(path));

            assertFields(body, DETAILS_FIELDS);
            assertThat(body.get("error").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(body.get("message").asText()).isEqualTo("Which load number are you calling about?");
        }
    }

    // --- real answers ---

    @Test
    void searchReturnsEveryFieldAndNoMaxRate() throws Exception {
        when(tms.send(any())).thenReturn(List.of(futureLoad()));

        JsonNode body = call(get("/api/loads/search").param("origin_state", "tx").param("equipment", "dry van"));

        assertFields(body, SEARCH_FIELDS);
        assertThat(body.get("error").asText()).isEmpty();
        assertThat(body.get("found").asBoolean()).isTrue();
        JsonNode load = body.get("loads").get(0);
        assertFields(load, SUMMARY_FIELDS);
        assertThat(load.get("load_id").asText()).isEqualTo("LD00979");
        assertThat(load.get("equipment_type").asText()).isEqualTo("Dry Van");
        assertThat(load.get("loadboard_rate").asInt()).isEqualTo(1850);
        assertNoMaxRate(body);
    }

    @Test
    void noLoadsIsFoundFalseWithAnEmptyList() throws Exception {
        when(tms.send(any())).thenReturn(List.of());

        JsonNode body = call(get("/api/loads/search").param("destination_state", "TX"));

        assertFields(body, SEARCH_FIELDS);
        assertThat(body.get("found").asBoolean()).isFalse();
        assertThat(body.get("loads")).isEmpty();
        assertThat(body.get("message").asText()).isEqualTo("No loads found for that lane.");
    }

    @Test
    void detailsReturnEveryFieldAndNoMaxRate() throws Exception {
        when(tms.send(any())).thenReturn(List.of(futureLoad()));

        JsonNode body = call(get("/api/loads/LD00979"));

        assertFields(body, DETAILS_FIELDS);
        assertThat(body.get("available").asBoolean()).isTrue();
        assertThat(body.get("commodity_type").asText()).isEqualTo("Machinery");
        assertThat(body.get("num_of_pieces").asInt()).isEqualTo(23);
        assertThat(body.get("notes").asText()).isEmpty();
        assertNoMaxRate(body);
    }

    @Test
    void unknownLoadIsAvailableFalseWithEmptyFields() throws Exception {
        when(tms.send(any())).thenThrow(new TmsErrorException("NOT_FOUND", "Load not found"));

        JsonNode body = call(get("/api/loads/LD99999"));

        assertFields(body, DETAILS_FIELDS);
        assertThat(body.get("error").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.get("available").asBoolean()).isFalse();
        assertThat(body.get("loadboard_rate").asInt()).isZero();
    }

    @Test
    void tmsDownIsA200WithASentence() throws Exception {
        when(tms.send(any())).thenThrow(new TmsFaultException(FaultKind.TIMEOUT, "no reply"));

        JsonNode body = call(get("/api/loads/LD00979"));

        assertFields(body, DETAILS_FIELDS);
        assertThat(body.get("error").asText()).isEqualTo("TMS_UNAVAILABLE");
        assertThat(body.get("message").asText())
                .isEqualTo("Our load system is slow right now, give me a moment and I'll try again.");
    }

    @Test
    void unknownEquipmentIsA200InvalidRequest() throws Exception {
        JsonNode body = call(get("/api/loads/search").param("equipment", "tanker"));

        assertFields(body, SEARCH_FIELDS);
        assertThat(body.get("error").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.get("message").asText())
                .isEqualTo("I didn't catch the equipment type. Is it dry van, reefer, flatbed or power only?");
    }

    @Test
    void searchNeedsTheApiKey() throws Exception {
        mockMvc.perform(get("/api/loads/search").param("origin_state", "TX"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    private JsonNode call(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** Exactly these fields, none null: HappyRobot may not detect null or missing fields. */
    private static void assertFields(JsonNode node, Set<String> expected) {
        assertThat(node.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(expected);
        node.properties().forEach(field -> assertThat(field.getValue().isNull()).as(field.getKey()).isFalse());
    }

    private static void assertNoMaxRate(JsonNode body) {
        assertThat(body.toString()).doesNotContain(String.valueOf(SECRET_MAX_BUY)).doesNotContainIgnoringCase("max");
    }

    private static TmsLoad futureLoad() {
        LocalDateTime pickup = LocalDateTime.now(LoadFormatter.BROKERAGE_TIME).plusDays(3);
        return new TmsLoad("LD00979", "Houston", "TX", "77001", "Dallas", "TX", "75201",
                pickup, pickup.plusHours(6), EquipmentType.DRY_VAN, 1850, 239, "OPEN",
                7290, "Machinery", 23, "45ft x 8ft x 9ft", "", SECRET_MAX_BUY);
    }
}
