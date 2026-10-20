package ai.happyrobot.carriersales.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BookingApiTest {

    private static final Set<String> FIELDS = Set.of("error", "message", "booked", "load_id", "agreed_rate",
            "booking_reference", "transferred", "rep_queue");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void blankInputsAre200WithEveryField() throws Exception {
        JsonNode body = book("{\"load_id\": \"\", \"mc_number\": \"\"}", "");

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("MISSING_CALL_ID");
    }

    @Test
    void refusedBeforeVerificationWithTheSameFields() throws Exception {
        JsonNode body = book("{\"load_id\": \"LD00979\"}", "never-seen");

        assertFields(body);
        assertThat(body.get("error").asText()).isEqualTo("OTP_NOT_VERIFIED");
    }

    @Test
    void brokenJsonKeepsTheShape() throws Exception {
        assertFields(book("{not json", "never-seen"));
    }

    private JsonNode book(String body, String callId) throws Exception {
        String response = mockMvc.perform(post("/api/bookings").header("X-API-Key", "test-api-key")
                        .header("X-Call-ID", callId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static void assertFields(JsonNode node) {
        assertThat(node.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(FIELDS);
        node.properties().forEach(field -> assertThat(field.getValue().isNull()).as(field.getKey()).isFalse());
    }
}
