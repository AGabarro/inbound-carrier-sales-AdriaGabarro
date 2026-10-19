package ai.happyrobot.carriersales.loads;

import ai.happyrobot.carriersales.tms.model.EquipmentType;
import ai.happyrobot.carriersales.web.exception.BadRequestException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Translates between what the voice agent sends/says and what the TMS expects/returns:
 * "dry van" -> DRY_VAN -> "Dry Van", "2026-10-20" -> 20261020, 2026-10-20T07:30 -> "Oct 20, 7:30 AM".
 */
public final class LoadFormatter {

    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");
    // Letters, spaces and the punctuation real US city names use ("St. Louis", "Coeur d'Alene", "Winston-Salem").
    private static final Pattern CITY = Pattern.compile("[A-Za-z .'-]{2,30}");
    private static final DateTimeFormatter TMS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter SPOKEN = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US);

    private LoadFormatter() {
    }

    public static EquipmentType equipment(String spoken) {
        String key = spoken.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (key) {
            case "dryvan", "van", "dry" -> EquipmentType.DRY_VAN;
            case "reefer", "refer", "refrigerated", "fridge" -> EquipmentType.REEFER;
            case "flatbed", "flat" -> EquipmentType.FLATBED;
            case "poweronly", "power" -> EquipmentType.POWER_ONLY;
            default -> throw new BadRequestException(
                    "I didn't catch the equipment type. Is it dry van, reefer, flatbed or power only?");
        };
    }

    public static String equipmentLabel(EquipmentType type) {
        return switch (type) {
            case DRY_VAN -> "Dry Van";
            case REEFER -> "Reefer";
            case FLATBED -> "Flatbed";
            case POWER_ONLY -> "Power Only";
        };
    }

    public static String state(String input) {
        String code = input.trim().toUpperCase(Locale.ROOT);
        if (!STATE.matcher(code).matches()) {
            throw new BadRequestException("Please use the two-letter state code, like TX.");
        }
        return code;
    }

    public static String city(String input) {
        String city = input.trim();
        if (!CITY.matcher(city).matches()) {
            throw new BadRequestException("I didn't catch the city name. Can you repeat it?");
        }
        return city;
    }

    /** ISO date from the agent ("2026-10-20") to the TMS search format ("20261020"). */
    public static String tmsDate(String isoDate) {
        try {
            return LocalDate.parse(isoDate.trim()).format(TMS_DATE);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("I didn't catch the pickup date. Which day works for you?");
        }
    }

    /** "Oct 20, 7:30 AM": short enough to read out on a call. */
    public static String spoken(LocalDateTime dateTime) {
        return dateTime.format(SPOKEN);
    }
}
