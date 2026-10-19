package ai.happyrobot.carriersales.loads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.happyrobot.carriersales.tms.model.EquipmentType;
import ai.happyrobot.carriersales.web.exception.BadRequestException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoadFormatterTest {

    @ParameterizedTest
    @CsvSource({
            "dry van, DRY_VAN", "Dry-Van, DRY_VAN", "DRY_VAN, DRY_VAN", "van, DRY_VAN",
            "reefer, REEFER", "Refrigerated, REEFER", "refer, REEFER",
            "flatbed, FLATBED", "flat bed, FLATBED",
            "power only, POWER_ONLY", "POWER_ONLY, POWER_ONLY"
    })
    void understandsSpokenEquipment(String spoken, EquipmentType expected) {
        assertThat(LoadFormatter.equipment(spoken)).isEqualTo(expected);
    }

    @Test
    void unknownEquipmentAsksAgain() {
        assertThatThrownBy(() -> LoadFormatter.equipment("tanker"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dry van, reefer, flatbed or power only");
    }

    @Test
    void labelsEquipmentForSpeech() {
        assertThat(LoadFormatter.equipmentLabel(EquipmentType.DRY_VAN)).isEqualTo("Dry Van");
        assertThat(LoadFormatter.equipmentLabel(EquipmentType.POWER_ONLY)).isEqualTo("Power Only");
    }

    @Test
    void acceptsTwoLetterStatesOnly() {
        assertThat(LoadFormatter.state(" tx ")).isEqualTo("TX");
        assertThatThrownBy(() -> LoadFormatter.state("Texas")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void acceptsRealCityNamesAndRejectsWireUnsafeOnes() {
        assertThat(LoadFormatter.city(" St. Louis ")).isEqualTo("St. Louis");
        assertThat(LoadFormatter.city("Winston-Salem")).isEqualTo("Winston-Salem");
        assertThatThrownBy(() -> LoadFormatter.city("Dallas|LOAD_ID:LD00001")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> LoadFormatter.city("São Paulo")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void convertsIsoDateToTmsFormat() {
        assertThat(LoadFormatter.tmsDate("2026-10-20")).isEqualTo("20261020");
        assertThatThrownBy(() -> LoadFormatter.tmsDate("next tuesday")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void formatsDateTimeForSpeech() {
        assertThat(LoadFormatter.spoken(LocalDateTime.of(2026, 10, 20, 7, 30))).isEqualTo("Oct 20, 7:30 AM");
        assertThat(LoadFormatter.spoken(LocalDateTime.of(2026, 10, 20, 15, 5))).isEqualTo("Oct 20, 3:05 PM");
    }
}
