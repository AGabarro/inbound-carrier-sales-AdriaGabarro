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
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The real endpoints, JSON and error handling, with only the TMS mocked. */
@SpringBootTest
@AutoConfigureMockMvc
class LoadApiTest {

    private static final String API_KEY = "test-api-key"; // from src/test/resources/application.properties
    private static final int SECRET_MAX_BUY = 98765;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TmsClient tms;

    // --- search ---

    @Test
    void searchReturnsSmallSnakeCaseJson() throws Exception {
        when(tms.send(any())).thenReturn(List.of(futureLoad()));

        String body = mockMvc.perform(withKey(get("/api/loads/search").param("origin_state", "tx").param("equipment", "dry van")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.message").value("I found 1 load."))
                .andExpect(jsonPath("$.loads[0].load_id").value("LD00979"))
                .andExpect(jsonPath("$.loads[0].origin").value("Houston, TX"))
                .andExpect(jsonPath("$.loads[0].equipment_type").value("Dry Van"))
                .andExpect(jsonPath("$.loads[0].loadboard_rate").value(1850))
                .andExpect(jsonPath("$.loads[0].weight").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertNoMaxRate(body);
    }

    @Test
    void noLoadsIsA200WithFoundFalse() throws Exception {
        when(tms.send(any())).thenReturn(List.of());

        mockMvc.perform(withKey(get("/api/loads/search").param("destination_state", "TX")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.message").value("No loads found for that lane."))
                .andExpect(jsonPath("$.loads").doesNotExist());
    }

    @Test
    void searchNeedsTheApiKey() throws Exception {
        mockMvc.perform(get("/api/loads/search").param("origin_state", "TX"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownEquipmentIs400WithASentence() throws Exception {
        mockMvc.perform(withKey(get("/api/loads/search").param("equipment", "tanker")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "I didn't catch the equipment type. Is it dry van, reefer, flatbed or power only?"));
    }

    @Test
    void searchWithoutFiltersIs400() throws Exception {
        mockMvc.perform(withKey(get("/api/loads/search")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    // --- details ---

    @Test
    void detailsReturnPitchFieldsWithoutMaxRate() throws Exception {
        when(tms.send(any())).thenReturn(List.of(futureLoad()));

        String body = mockMvc.perform(withKey(get("/api/loads/LD00979")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.load_id").value("LD00979"))
                .andExpect(jsonPath("$.commodity_type").value("Machinery"))
                .andExpect(jsonPath("$.num_of_pieces").value(23))
                .andExpect(jsonPath("$.notes").doesNotExist()) // blank in the TMS, so left out
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertNoMaxRate(body);
    }

    @Test
    void unknownLoadIsA200WithAvailableFalseOnly() throws Exception {
        when(tms.send(any())).thenThrow(new TmsErrorException("NOT_FOUND", "Load not found"));

        String body = mockMvc.perform(withKey(get("/api/loads/LD99999")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("{\"available\":false,\"message\":\"That load isn't available.\"}");
    }

    // --- TMS problems ---

    @Test
    void tmsDownIs503WithASentence() throws Exception {
        when(tms.send(any())).thenThrow(new TmsFaultException(FaultKind.TIMEOUT, "no reply"));

        mockMvc.perform(withKey(get("/api/loads/LD00979")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("TMS_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "Our load system is slow right now, give me a moment and I'll try again."));
    }

    @Test
    void tmsRejectingTheSearchIs400() throws Exception {
        when(tms.send(any())).thenThrow(new TmsErrorException("MISSING_FIELD", "Invalid EQTYPE"));

        mockMvc.perform(withKey(get("/api/loads/search").param("origin_state", "TX")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void unexpectedTmsErrorIs502() throws Exception {
        when(tms.send(any())).thenThrow(new TmsErrorException("SERVER_ERROR", "internal"));

        mockMvc.perform(withKey(get("/api/loads/search").param("origin_state", "TX")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("TMS_ERROR"));
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request) {
        return request.header("X-API-Key", API_KEY);
    }

    private static void assertNoMaxRate(String body) {
        assertThat(body).doesNotContain(String.valueOf(SECRET_MAX_BUY)).doesNotContainIgnoringCase("max");
    }

    private static TmsLoad futureLoad() {
        LocalDateTime pickup = LocalDateTime.now(LoadService.BROKERAGE_TIME).plusDays(3);
        return new TmsLoad("LD00979", "Houston", "TX", "77001", "Dallas", "TX", "75201",
                pickup, pickup.plusHours(6), EquipmentType.DRY_VAN, 1850, 239, "OPEN",
                7290, "Machinery", 23, "45ft x 8ft x 9ft", "", SECRET_MAX_BUY);
    }
}
