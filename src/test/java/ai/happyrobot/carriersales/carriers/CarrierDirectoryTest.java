package ai.happyrobot.carriersales.carriers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CarrierDirectoryTest {

    @Test
    void findsTheEmailOnFile() {
        CarrierDirectory directory = new CarrierDirectory(" 133655:ops@schneider.com , 650000:dispatch@mikeandson.com ");

        assertThat(directory.emailFor("133655")).contains("ops@schneider.com");
        assertThat(directory.emailFor("650000")).contains("dispatch@mikeandson.com");
    }

    @Test
    void unknownMcHasNoContact() {
        assertThat(new CarrierDirectory("133655:ops@schneider.com").emailFor("900000")).isEmpty();
    }

    @Test
    void emptyConfigMeansNoContacts() {
        assertThat(new CarrierDirectory("").emailFor("133655")).isEmpty();
    }

    @Test
    void typoFailsAtStartupWithoutEchoingTheEmail() {
        assertThatThrownBy(() -> new CarrierDirectory("133655=ops@schneider.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("schneider");
        assertThatThrownBy(() -> new CarrierDirectory("133655:not-an-email"))
                .isInstanceOf(IllegalStateException.class);
    }
}
