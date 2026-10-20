package ai.happyrobot.carriersales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.happyrobot.carriersales.fmcsa.FmcsaClient;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import ai.happyrobot.carriersales.loads.LoadFormatter;
import ai.happyrobot.carriersales.otp.OtpSender;
import ai.happyrobot.carriersales.otp.model.OtpDelivery;
import ai.happyrobot.carriersales.tms.TmsClient;
import ai.happyrobot.carriersales.tms.model.TmsBooking;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

/**
 * A whole call as the voice agent would make it, over HTTP: loads stay locked until FMCSA and the OTP both pass.
 * Only the outside systems (FMCSA, TMS, OTP delivery) are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CallFlowTest {

    private static final String API_KEY = "test-api-key"; // from src/test/resources/application.properties
    private static final String CALL = "run-flow-1";

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
    void loadsUnlockOnlyAfterFmcsaAndOtp() throws Exception {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(new FmcsaCarrier(
                "SCHNEIDER NATIONAL CARRIERS INC", null, "Y", "A", "A", "A", "S", "GREEN BAY", "WI", 9866, 11335)));
        LocalDateTime pickup = LocalDateTime.now(LoadFormatter.BROKERAGE_TIME).plusDays(2);
        when(tms.send(any())).thenReturn(List.of(new TmsLoad("LD00979", "Anaheim", "CA", "92801", "El Paso", "TX",
                "79901", pickup, pickup.plusHours(20), "DRY_VAN", 1961, 679, "OPEN",
                36031, "Electronics", 19, "44ft x 8ft x 9ft", "", 2300)));

        // 1. "Anything going to Texas?" before any verification: refused.
        assertThat(search().get("error").asText()).isEqualTo("OTP_NOT_VERIFIED");

        // 2. MC verified with FMCSA: still refused, the OTP is missing.
        JsonNode carrier = post("/api/carriers/verify", "{\"mc_number\": \"MC 133655\"}");
        assertThat(carrier.get("eligible").asBoolean()).isTrue();
        assertThat(search().get("error").asText()).isEqualTo("OTP_NOT_VERIFIED");

        // 3. Code sent to the contact on file, and read back by the carrier.
        JsonNode sent = post("/api/otp/send", "{\"mc_number\": \"133655\"}");
        assertThat(sent.get("destination_masked").asText()).isEqualTo("j***@schneider.com");
        ArgumentCaptor<OtpDelivery> delivery = ArgumentCaptor.forClass(OtpDelivery.class);
        verify(sender).send(delivery.capture());
        String code = delivery.getValue().code();
        String spoken = String.join(" ", code.split(""));
        assertThat(post("/api/otp/verify", "{\"code\": \"" + spoken + "\"}").get("verified").asBoolean()).isTrue();

        // 4. Now the loads open up.
        JsonNode loads = search();
        assertThat(loads.get("error").asText()).isEmpty();
        assertThat(loads.get("found").asBoolean()).isTrue();
        assertThat(get("/api/loads/LD00979", CALL).get("available").asBoolean()).isTrue();

        // 5. Another call is not verified by this one.
        assertThat(get("/api/loads/LD00979", "run-flow-2").get("error").asText()).isEqualTo("OTP_NOT_VERIFIED");

        // 6. Negotiation: an outrageous ask gets a counter, then the posted rate is accepted.
        JsonNode counter = post("/api/negotiations/offer", "{\"load_id\": \"LD00979\", \"counter_rate\": 5000}");
        assertThat(counter.get("decision").asText()).isEqualTo("counter");
        assertThat(counter.get("rate").asInt()).isLessThan(2300);
        JsonNode deal = post("/api/negotiations/offer", "{\"load_id\": \"LD00979\", \"counter_rate\": 1961}");
        assertThat(deal.get("decision").asText()).isEqualTo("accept");
        assertThat(deal.get("final").asBoolean()).isTrue();
        assertThat(counter.toString() + deal).doesNotContain("2300");

        // 7. Booked at the agreed rate (never a rate from the request), then handed to a rep.
        when(tms.book("LD00979", "133655", 1961)).thenReturn(new TmsBooking("LD00979", "GFZKYZRT3K4JOEAE", "PENDING",
                LocalDateTime.now()));
        JsonNode booking = post("/api/bookings", "{\"load_id\": \"LD00979\"}");
        assertThat(booking.get("booked").asBoolean()).isTrue();
        assertThat(booking.get("agreed_rate").asInt()).isEqualTo(1961);
        assertThat(booking.get("booking_reference").asText()).isEqualTo("GFZKYZRT3K4JOEAE");
        assertThat(booking.get("transferred").asBoolean()).isTrue();
    }

    private JsonNode search() throws Exception {
        return get("/api/loads/search?destination_state=TX", CALL);
    }

    private JsonNode get(String path, String callId) throws Exception {
        return perform(MockMvcRequestBuilders.get(path)
                .header("X-Call-ID", callId));
    }

    private JsonNode post(String path, String body) throws Exception {
        return perform(MockMvcRequestBuilders.post(path)
                .header("X-Call-ID", CALL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode perform(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
