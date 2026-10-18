package ai.happyrobot.carriersales.tms.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TmsRequestTest {

    private static final String TOKEN = "hr-fde-secret-token";

    @Test
    void encodesLoadGet() {
        String frame = TmsRequestEncoder.encode(TmsRequest.loadGet("LD00992"), TOKEN);

        assertThat(frame).isEqualTo("CMD:LOAD_GET|AUTH:hr-fde-secret-token|LOAD_ID:LD00992\r\n");
    }

    @Test
    void encodesLoadBookFieldsInOrder() {
        String frame = TmsRequestEncoder.encode(TmsRequest.loadBook("LD00992", "123456", 11500), TOKEN);

        assertThat(frame).endsWith("|LOAD_ID:LD00992|MC_NUM:123456|AGREED_RATE:11500\r\n");
    }

    @Test
    void redactedHidesToken() {
        String line = TmsRequestEncoder.redacted(TmsRequest.loadGet("LD00992"));

        assertThat(line).isEqualTo("CMD:LOAD_GET|AUTH:***|LOAD_ID:LD00992");
    }

    @Test
    void rejectsFieldInjectionThroughPipe() {
        assertThatThrownBy(() -> TmsRequest.loadQuery(Map.of("DEST_CITY", "Dallas|LOAD_ID:LD00001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEST_CITY");
    }

    @Test
    void rejectsLineBreakInValue() {
        assertThatThrownBy(() -> TmsRequest.loadGet("LD00992\r\nCMD:LOAD_BOOK"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonAsciiValue() {
        assertThatThrownBy(() -> TmsRequest.loadQuery(Map.of("DEST_CITY", "São Paulo")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> TmsRequest.loadGet(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReservedFieldNames() {
        assertThatThrownBy(() -> TmsRequest.loadQuery(Map.of("AUTH", "other-token")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TmsRequest.loadQuery(Map.of("CMD", "LOAD_BOOK")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsQueryWithoutFilters() {
        assertThatThrownBy(() -> TmsRequest.loadQuery(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFrameOver4096Bytes() {
        TmsRequest huge = TmsRequest.loadQuery(Map.of("DEST_CITY", "A".repeat(4096)));

        assertThatThrownBy(() -> TmsRequestEncoder.encode(huge, TOKEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void invalidTokenIsNotEchoed() {
        String badToken = "token|with-pipe";

        assertThatThrownBy(() -> TmsRequestEncoder.encode(TmsRequest.loadGet("LD00992"), badToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(badToken);
    }
}
