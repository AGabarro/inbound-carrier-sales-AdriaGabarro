package ai.happyrobot.carriersales.loads;

import ai.happyrobot.carriersales.tms.model.EquipmentType;
import ai.happyrobot.carriersales.web.exception.BadRequestException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Between what the agent says and what the TMS expects: "dry van" <-> DRY_VAN, dates, labels.
public final class LoadFormatter {

    // TMS dates have no timezone: read as the brokerage's local time.
    public static final ZoneId BROKERAGE_TIME = ZoneId.of("America/Chicago");

    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");
    // Real US city names: "St. Louis", "Coeur d'Alene", "Winston-Salem".
    private static final Pattern CITY = Pattern.compile("[A-Za-z .'-]{2,30}");
    private static final DateTimeFormatter TMS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a 'CT'", Locale.US);

    private LoadFormatter() {
    }

    // Empty when we can't filter on it (the search then covers every equipment type on the lane).
    public static Optional<EquipmentType> equipment(String spoken) {
        String key = spoken.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return Optional.ofNullable(switch (key) {
            case "dryvan", "van", "dry" -> EquipmentType.DRY_VAN;
            case "reefer", "refer", "refrigerated", "fridge" -> EquipmentType.REEFER;
            case "flatbed", "flat" -> EquipmentType.FLATBED;
            case "poweronly", "power" -> EquipmentType.POWER_ONLY;
            case "stepdeck", "step", "dropdeck", "drop" -> EquipmentType.STEP_DECK;
            default -> null;
        });
    }

    // STEP_DECK -> "Step Deck", works for codes we don't know yet too.
    public static String equipmentLabel(String code) {
        return Arrays.stream(code.toLowerCase(Locale.ROOT).split("_"))
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
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

    public static String tmsDate(String isoDate) {
        try {
            return LocalDate.parse(isoDate.trim()).format(TMS_DATE);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("I didn't catch the pickup date. Which day works for you?");
        }
    }

    // ISO with offset, so the agent can reason about dates.
    public static String iso(LocalDateTime dateTime) {
        return dateTime.atZone(BROKERAGE_TIME).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // "Tue Oct 20, 7:30 AM CT", for speaking.
    public static String display(LocalDateTime dateTime) {
        return dateTime.format(DISPLAY);
    }
}
