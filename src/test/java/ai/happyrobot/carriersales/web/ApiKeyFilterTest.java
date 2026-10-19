package ai.happyrobot.carriersales.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

// The test API key comes from src/test/resources/application.properties.
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsApiCallWithoutKey() throws Exception {
        mockMvc.perform(get("/api/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsApiCallWithWrongKey() throws Exception {
        mockMvc.perform(get("/api/anything").header(ApiKeyFilter.HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void letsApiCallWithRightKeyThrough() throws Exception {
        // No endpoint exists at this path yet: 404 proves the request got past the filter.
        mockMvc.perform(get("/api/anything").header(ApiKeyFilter.HEADER, "test-api-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthNeedsNoKey() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }
}
