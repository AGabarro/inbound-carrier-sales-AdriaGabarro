package ai.happyrobot.carriersales.carriers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.happyrobot.carriersales.fmcsa.FmcsaClient;
import ai.happyrobot.carriersales.fmcsa.exception.FmcsaUnavailableException;
import ai.happyrobot.carriersales.fmcsa.model.FmcsaCarrier;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The real endpoint, JSON and error handling, with only FMCSA mocked. */
@SpringBootTest
@AutoConfigureMockMvc
class CarrierApiTest {

    private static final String API_KEY = "test-api-key"; // from src/test/resources/application.properties

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FmcsaClient fmcsa;

    @Test
    void eligibleCarrierGetsSnakeCaseProfile() throws Exception {
        when(fmcsa.findByMcNumber("133655")).thenReturn(Optional.of(new FmcsaCarrier(
                "SCHNEIDER NATIONAL CARRIERS INC", null, "Y", "A", "A", "A", "S", "GREEN BAY", "WI", 9866, 11335)));

        mockMvc.perform(verify("{\"mc_number\": \"MC 133655\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.message").value("You're verified."))
                .andExpect(jsonPath("$.mc_number").value("133655"))
                .andExpect(jsonPath("$.carrier_name").value("Schneider National Carriers Inc"))
                .andExpect(jsonPath("$.home_base").value("Green Bay, WI"))
                .andExpect(jsonPath("$.power_units").value(9866))
                .andExpect(jsonPath("$.safety_rating").value("Satisfactory"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.dba_name").doesNotExist());
    }

    @Test
    void notAuthorizedIsA200ThatEndsTheCall() throws Exception {
        when(fmcsa.findByMcNumber(any())).thenReturn(Optional.of(new FmcsaCarrier(
                "C & C PRESTIGE LOGISTICS LLC", null, "Y", "I", "N", "N", null, "X", "TX", 1, 1)));

        String body = mockMvc.perform(verify("{\"mc_number\": \"100000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("NO_ACTIVE_AUTHORITY"))
                .andExpect(jsonPath("$.mc_number").value("100000"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("carrier_name", "home_base", "Prestige");
    }

    @Test
    void unknownMcIsA200AskingAgain() throws Exception {
        when(fmcsa.findByMcNumber(any())).thenReturn(Optional.empty());

        mockMvc.perform(verify("{\"mc_number\": \"999999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_FOUND"));
    }

    @Test
    void needsTheApiKey() throws Exception {
        mockMvc.perform(post("/api/carriers/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mc_number\": \"133655\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mcWithoutDigitsIs400() throws Exception {
        mockMvc.perform(verify("{\"mc_number\": \"not sure\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("I didn't catch your MC number. Can you read it to me again?"));
    }

    @Test
    void brokenJsonIs400InTheUsualShape() throws Exception {
        mockMvc.perform(verify("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void fmcsaDownIs503WithASentence() throws Exception {
        when(fmcsa.findByMcNumber(any())).thenThrow(new FmcsaUnavailableException("FMCSA answered HTTP 403"));

        mockMvc.perform(verify("{\"mc_number\": \"133655\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("FMCSA_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "I can't check your MC number right now. Please try again in a few minutes."));
    }

    private static MockHttpServletRequestBuilder verify(String json) {
        return post("/api/carriers/verify")
                .header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }
}
