package ai.happyrobot.carriersales.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RequestLogFilterTest {

    private static final String API_KEY = "test-api-key"; // from src/test/resources/application.properties

    @Autowired
    private MockMvc mockMvc;

    @Test
    void logsPathQueryCallIdAndStatusButNeverTheKey(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/loads/").header("X-API-Key", API_KEY).header("X-Call-ID", "run-123"));

        assertThat(output).contains("API GET /api/loads/ call=run-123 -> 200 in ")
                .doesNotContain(API_KEY);
    }

    @Test
    void logsRejectedRequestsToo(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/loads/search").queryParam("origin_state", "TX").header("X-Call-ID", "run-456"));

        assertThat(output).contains("API GET /api/loads/search?origin_state=TX call=run-456 -> 401");
    }

    @Test
    void callerValuesCannotForgeLogLines(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/loads/").header("X-API-Key", API_KEY).header("X-Call-ID", "x\nAPI GET /fake"));

        assertThat(output).contains("call=x_API GET /fake").doesNotContain("\nAPI GET /fake");
    }

    @Test
    void healthIsNotLogged(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/health"));

        assertThat(output).doesNotContain("API GET /health");
    }
}
