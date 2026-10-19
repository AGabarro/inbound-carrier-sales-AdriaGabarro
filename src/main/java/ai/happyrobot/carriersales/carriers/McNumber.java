package ai.happyrobot.carriersales.carriers;

import java.util.Optional;

public final class McNumber {

    private McNumber() {
    }

    /** "MC 133655", "m c 0133655", "mc# 133655" -> "133655"; empty if there aren't 1 to 8 digits. */
    public static Optional<String> clean(String spoken) {
        String digits = spoken == null ? "" : spoken.replaceAll("\\D", "").replaceFirst("^0+", "");
        return digits.matches("\\d{1,8}") ? Optional.of(digits) : Optional.empty();
    }
}
