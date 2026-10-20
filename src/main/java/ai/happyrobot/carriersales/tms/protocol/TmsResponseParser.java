package ai.happyrobot.carriersales.tms.protocol;

import ai.happyrobot.carriersales.tms.exception.FaultKind;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.EquipmentType;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// Strict: faults can look valid (cut on a line boundary, garbage before a correct END), so anything
// not exactly as expected is a fault and none of it is used.
public final class TmsResponseParser {

    private static final String CRLF = "\r\n";
    private static final String END = "END";

    // Exact key order and padded width, as captured from the live TMS.
    private static final Map<String, Integer> SUMMARY_LAYOUT = layout(
            "LOAD_ID", 12, "ORIG_CITY", 30, "ORIG_STATE", 2, "ORIG_ZIP", 5,
            "DEST_CITY", 30, "DEST_STATE", 2, "DEST_ZIP", 5, "PICKUP_DT", 14,
            "EQTYPE", 10, "RATE", 8, "MILES", 6, "STATUS", 8);

    private static final Map<String, Integer> DETAIL_LAYOUT = layout(
            "LOAD_ID", 12, "ORIG_CITY", 30, "ORIG_STATE", 2, "ORIG_ZIP", 5,
            "DEST_CITY", 30, "DEST_STATE", 2, "DEST_ZIP", 5, "PICKUP_DT", 14,
            "DELIVERY_DT", 14, "EQTYPE", 10, "RATE", 8, "WEIGHT", 8,
            "COMMODITY", 30, "PIECES", 6, "MILES", 6, "DIMS", 30,
            "NOTES", 120, "STATUS", 8, "MAX_BUY", 8);

    private static final Pattern PRINTABLE_ASCII = Pattern.compile("[\\x20-\\x7E]*");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z_]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");
    private static final Pattern ZIP = Pattern.compile("\\d{5}");
    private static final DateTimeFormatter TMS_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);

    private TmsResponseParser() {
    }

    public static List<TmsLoad> parse(TmsCommand command, String raw) {
        Map<String, Integer> layout = switch (command) {
            case LOAD_QUERY -> SUMMARY_LAYOUT;
            case LOAD_GET -> DETAIL_LAYOUT;
            case LOAD_BOOK -> throw new UnsupportedOperationException("LOAD_BOOK responses are not parsed yet");
        };

        if (raw == null || raw.isEmpty()) {
            throw new TmsFaultException(FaultKind.EMPTY, "no bytes received");
        }
        if (!raw.endsWith(CRLF)) {
            throw new TmsFaultException(FaultKind.PARTIAL, "last line has no CRLF");
        }
        List<String> lines = List.of(raw.substring(0, raw.length() - CRLF.length()).split(CRLF, -1));
        for (String line : lines) {
            if (!PRINTABLE_ASCII.matcher(line).matches()) {
                throw malformed("control or non-ASCII character inside a line");
            }
        }

        if (lines.getFirst().startsWith("ERR|")) {
            if (lines.size() > 1) {
                throw malformed("ERR line followed by more lines");
            }
            throw parseError(lines.getFirst());
        }
        if (!lines.getLast().equals(END)) {
            throw new TmsFaultException(FaultKind.PARTIAL, "no END terminator");
        }

        List<TmsLoad> loads = new ArrayList<>();
        for (String record : lines.subList(0, lines.size() - 1)) {
            loads.add(toLoad(fields(record, layout)));
        }
        return loads;
    }

    private static RuntimeException parseError(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 3 || !parts[1].startsWith("CODE:") || !parts[2].startsWith("MSG:")) {
            return malformed("ERR line is not ERR|CODE:..|MSG:..");
        }
        String code = parts[1].substring("CODE:".length()).trim();
        if (!ERROR_CODE.matcher(code).matches()) {
            return malformed("ERR line has an invalid CODE");
        }
        return new TmsErrorException(code, parts[2].substring("MSG:".length()).trim());
    }

    private static Map<String, String> fields(String line, Map<String, Integer> layout) {
        String[] parts = line.split("\\|", -1);
        List<String> expectedKeys = List.copyOf(layout.keySet());
        if (parts.length != expectedKeys.size()) {
            throw malformed("record has " + parts.length + " fields, expected " + expectedKeys.size());
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < parts.length; i++) {
            int colon = parts[i].indexOf(':');
            String key = colon < 0 ? null : parts[i].substring(0, colon);
            String expected = expectedKeys.get(i);
            if (!expected.equals(key)) {
                throw malformed("field " + (i + 1) + " should be " + expected);
            }
            String raw = parts[i].substring(colon + 1);
            if (raw.length() != layout.get(key)) {
                throw malformed(key + " has width " + raw.length() + ", expected " + layout.get(key));
            }
            values.put(key, raw.trim());
        }
        return values;
    }

    private static TmsLoad toLoad(Map<String, String> f) {
        boolean detail = f.containsKey("MAX_BUY");
        return new TmsLoad(
                required(f, "LOAD_ID"),
                required(f, "ORIG_CITY"), matching(f, "ORIG_STATE", STATE), matching(f, "ORIG_ZIP", ZIP),
                required(f, "DEST_CITY"), matching(f, "DEST_STATE", STATE), matching(f, "DEST_ZIP", ZIP),
                dateTime(f, "PICKUP_DT"),
                detail ? dateTime(f, "DELIVERY_DT") : null,
                equipment(f),
                number(f, "RATE"),
                number(f, "MILES"),
                required(f, "STATUS"),
                detail ? number(f, "WEIGHT") : null,
                detail ? f.get("COMMODITY") : null,
                detail ? number(f, "PIECES") : null,
                detail ? f.get("DIMS") : null,
                detail ? f.get("NOTES") : null,
                detail ? number(f, "MAX_BUY") : null);
    }

    private static String required(Map<String, String> f, String key) {
        String value = f.get(key);
        if (value.isEmpty()) {
            throw malformed(key + " is blank");
        }
        return value;
    }

    private static String matching(Map<String, String> f, String key, Pattern pattern) {
        if (!pattern.matcher(f.get(key)).matches()) {
            throw malformed(key + " has an invalid format");
        }
        return f.get(key);
    }

    private static int number(Map<String, String> f, String key) {
        String value = matching(f, key, DIGITS);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw malformed(key + " is out of range");
        }
    }

    private static LocalDateTime dateTime(Map<String, String> f, String key) {
        try {
            return LocalDateTime.parse(f.get(key), TMS_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw malformed(key + " is not a valid yyyyMMddHHmmss date");
        }
    }

    private static EquipmentType equipment(Map<String, String> f) {
        try {
            return EquipmentType.valueOf(f.get("EQTYPE"));
        } catch (IllegalArgumentException e) {
            throw malformed("EQTYPE is not a known equipment type");
        }
    }

    // Messages name the field and the problem, never the value (it could be MAX_BUY).
    private static TmsFaultException malformed(String detail) {
        return new TmsFaultException(FaultKind.MALFORMED, detail);
    }

    private static Map<String, Integer> layout(Object... keyWidthPairs) {
        Map<String, Integer> layout = new LinkedHashMap<>();
        for (int i = 0; i < keyWidthPairs.length; i += 2) {
            layout.put((String) keyWidthPairs[i], (Integer) keyWidthPairs[i + 1]);
        }
        return Collections.unmodifiableMap(layout);
    }
}
