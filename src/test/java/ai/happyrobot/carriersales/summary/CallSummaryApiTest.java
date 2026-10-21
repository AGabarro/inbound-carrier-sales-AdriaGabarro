package ai.happyrobot.carriersales.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.happyrobot.carriersales.fmcsa.FmcsaClient;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import ai.happyrobot.carriersales.loads.LoadFormatter;
import ai.happyrobot.carriersales.otp.OtpSender;
import ai.happyrobot.carriersales.otp.model.OtpDelivery;
import ai.happyrobot.carriersales.tms.TmsClient;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.model.TmsBooking;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import ai.happyrobot.carriersales.tms.protocol.TmsRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** A whole call with a lost load and a second deal, then the summary the workflow writes to Twin. */
@SpringBootTest
@AutoConfigureMockMvc
class CallSummaryApiTest {

    // Own call and load IDs: sessions and load claims are shared across tests in the same context.
    private static final String CALL = "run-" + UUID.randomUUID();
    private static final int MAX = 2300;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private FmcsaClient fmcsa;

    @MockitoBean
    private TmsClient tms;

    @MockitoBean
    private OtpSender sender;

    @Test
    void unknownCallIs200WithEveryField() throws Exception {
        JsonNode body = call(get("/api/calls/never-seen/summary"));

        assertThat(fieldNames(body)).containsExactlyInAnyOrder("error", "message", "call_id", "environment", "state",
                "carrier_verifications", "otp_challenges", "negotiations", "negotiation_offers", "bookings");
        assertThat(body.get("error").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.get("environment").asText()).isEqualTo("production");
    }

    @Test
    void summarisesAWholeCallInTwinShape() throws Exception {
        when(fmcsa.findByMcNumber("999999")).thenReturn(Optional.empty());
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(new FmcsaCarrier(
                "SCHNEIDER NATIONAL CARRIERS INC", null, "Y", "A", "A", "A", "S", "GREEN BAY", "WI", 9866, 11335)));
        when(tms.send(any())).thenAnswer(invocation -> {
            String id = ((TmsRequest) invocation.getArgument(0)).fields()
                    .getOrDefault("LOAD_ID", "LD09901");
            return List.of(load(id));
        });
        when(tms.book(eq("LD09901"), anyString(), anyInt())).thenThrow(new TmsErrorException("UNKNOWN_LOAD", "x"));
        when(tms.book(eq("LD09902"), anyString(), anyInt()))
                .thenReturn(new TmsBooking("LD09902", "SM75C6T6D5YXN8H6", "PENDING", LocalDateTime.now()));

        post("/api/carriers/verify", "{\"mc_number\": \"999999\"}");
        post("/api/carriers/verify", "{\"mc_number\": \"MC 133655\"}");
        post("/api/otp/send", "{}");
        ArgumentCaptor<OtpDelivery> delivery = ArgumentCaptor.forClass(OtpDelivery.class);
        verify(sender).send(delivery.capture());
        String code = delivery.getValue().code();
        post("/api/otp/verify", "{\"code\": \"" + code + "\"}");
        post("/api/negotiations/offer", "{\"load_id\": \"LD09901\", \"counter_rate\": 1961}");
        post("/api/bookings", "{\"load_id\": \"LD09901\"}");                // refused: negotiation released
        post("/api/negotiations/offer", "{\"load_id\": \"LD09902\", \"counter_rate\": 5000}");
        post("/api/negotiations/offer", "{\"load_id\": \"LD09902\", \"counter_rate\": 1961}");
        post("/api/bookings", "{\"load_id\": \"LD09902\"}");

        JsonNode s = call(get("/api/calls/" + CALL + "/summary"));

        assertThat(s.get("error").asText()).isEmpty();
        assertThat(s.get("state").asText()).isEqualTo("BOOKED");
        assertThat(s.get("environment").asText()).isEqualTo("production");
        assertThat(s.get("carrier_verifications")).extracting(v -> v.get("reason").asText()).containsExactly("NOT_FOUND", "");
        JsonNode otp = s.get("otp_challenges").get(0);
        assertThat(otp.get("destination_masked").asText()).isEqualTo("j***@schneider.com");
        assertThat(otp.get("verified_at").asText()).isNotEmpty();
        assertThat(s.get("negotiations")).extracting(n -> n.get("load_id").asText() + ":" + n.get("status").asText())
                .containsExactly("LD09901:released", "LD09902:accepted");
        assertThat(s.get("negotiation_offers")).hasSize(3);
        JsonNode booking = s.get("bookings").get(0);
        assertThat(booking.get("booking_reference").asText()).isEqualTo("SM75C6T6D5YXN8H6");
        assertThat(booking.get("status").asText()).isEqualTo("booked");
        assertThat(s.get("call_id").asText()).isEqualTo(CALL);

        String text = s.toString();
        assertThat(text).doesNotContain(String.valueOf(MAX)).doesNotContain(code).doesNotContain("jane@");
    }

    private static TmsLoad load(String id) {
        LocalDateTime pickup = LocalDateTime.now(LoadFormatter.BROKERAGE_TIME).plusDays(2);
        return new TmsLoad(id, "Anaheim", "CA", "92801", "El Paso", "TX", "79901", pickup, pickup.plusHours(20),
                "DRY_VAN", 1961, 679, "OPEN", 36031, "Electronics", 19, "44ft x 8ft x 9ft", "", MAX);
    }

    private void post(String path, String body) throws Exception {
        call(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode call(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request.header("X-API-Key", "test-api-key").header("X-Call-ID", CALL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.properties().stream().map(Map.Entry::getKey).toList());
    }
}
