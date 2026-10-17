package ai.happyrobot.carriersales.tms;

import ai.happyrobot.carriersales.tms.TmsResponse.Fault;
import ai.happyrobot.carriersales.tms.TmsResponse.FaultKind;
import ai.happyrobot.carriersales.tms.TmsResponse.Success;
import ai.happyrobot.carriersales.tms.TmsResponse.TmsError;
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

/**
 * Strict parser for raw TMS responses. The TMS injects faults that still look valid (cut on a line
 * boundary, garbage spliced in before a correct END), so anything not exactly as expected is a
 * {@link Fault} and no part of it is used. Rules and real examples: docs/tms-discovery-log.md.
 */
public final class TmsResponseParser {

    private static final String CRLF = "\r\n";
    private static final String END = "END";

    // Exact key order and padded width, as captured from the live TMS (discovery log 3.4).
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

    public static TmsResponse parse(TmsCommand command, String raw) {
        Map<String, Integer> layout = switch (command) {
            case LOAD_QUERY -> SUMMARY_LAYOUT;
            case LOAD_GET -> DETAIL_LAYOUT;
            case LOAD_BOOK -> throw new UnsupportedOperationException("LOAD_BOOK responses are parsed from Step 7");
        };

        if (raw == null || raw.isEmpty()) {
            return new Fault(FaultKind.EMPTY, "no bytes received");
        }
        if (!raw.endsWith(CRLF)) {
            return new Fault(FaultKind.PARTIAL, "last line has no CRLF");
        }
        List<String> lines = List.of(raw.substring(0, raw.length() - CRLF.length()).split(CRLF, -1));
        for (String line : lines) {
            if (!PRINTABLE_ASCII.matcher(line).matches()) {
                return new Fault(FaultKind.MALFORMED, "control or non-ASCII character inside a line");
            }
        }

        if (lines.getFirst().startsWith("ERR|")) {
            return lines.size() == 1
                    ? parseError(lines.getFirst())
                    : new Fault(FaultKind.MALFORMED, "ERR line followed by more lines");
        }
        if (!lines.getLast().equals(END)) {
            return new Fault(FaultKind.PARTIAL, "no END terminator");
        }

        List<TmsLoad> loads = new ArrayList<>();
        List<String> records = lines.subList(0, lines.size() - 1);
        for (int i = 0; i < records.size(); i++) {
            try {
                loads.add(toLoad(fields(records.get(i), layout)));
            } catch (MalformedRecord e) {
                return new Fault(FaultKind.MALFORMED, "record " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new Success(loads);
    }

    private static TmsResponse parseError(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 3 || !parts[1].startsWith("CODE:") || !parts[2].startsWith("MSG:")) {
            return new Fault(FaultKind.MALFORMED, "ERR line is not ERR|CODE:..|MSG:..");
        }
        String code = parts[1].substring("CODE:".length()).trim();
        if (!ERROR_CODE.matcher(code).matches()) {
            return new Fault(FaultKind.MALFORMED, "ERR line has an invalid CODE");
        }
        return new TmsError(code, parts[2].substring("MSG:".length()).trim());
    }

    /** Splits a record line and checks keys (exact order) and padded widths. Returns trimmed values. */
    private static Map<String, String> fields(String line, Map<String, Integer> layout) {
        String[] parts = line.split("\\|", -1);
        List<String> expectedKeys = List.copyOf(layout.keySet());
        if (parts.length != expectedKeys.size()) {
            throw new MalformedRecord("has " + parts.length + " fields, expected " + expectedKeys.size());
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < parts.length; i++) {
            int colon = parts[i].indexOf(':');
            String key = colon < 0 ? null : parts[i].substring(0, colon);
            String expected = expectedKeys.get(i);
            if (!expected.equals(key)) {
                throw new MalformedRecord("field " + (i + 1) + " should be " + expected);
            }
            String raw = parts[i].substring(colon + 1);
            if (raw.length() != layout.get(key)) {
                throw new MalformedRecord(key + " has width " + raw.length() + ", expected " + layout.get(key));
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
            throw new MalformedRecord(key + " is blank");
        }
        return value;
    }

    private static String matching(Map<String, String> f, String key, Pattern pattern) {
        if (!pattern.matcher(f.get(key)).matches()) {
            throw new MalformedRecord(key + " has an invalid format");
        }
        return f.get(key);
    }

    private static int number(Map<String, String> f, String key) {
        String value = matching(f, key, DIGITS);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new MalformedRecord(key + " is out of range");
        }
    }

    private static LocalDateTime dateTime(Map<String, String> f, String key) {
        try {
            return LocalDateTime.parse(f.get(key), TMS_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new MalformedRecord(key + " is not a valid yyyyMMddHHmmss date");
        }
    }

    private static EquipmentType equipment(Map<String, String> f) {
        try {
            return EquipmentType.valueOf(f.get("EQTYPE"));
        } catch (IllegalArgumentException e) {
            throw new MalformedRecord("EQTYPE is not a known equipment type");
        }
    }

    private static Map<String, Integer> layout(Object... keyWidthPairs) {
        Map<String, Integer> layout = new LinkedHashMap<>();
        for (int i = 0; i < keyWidthPairs.length; i += 2) {
            layout.put((String) keyWidthPairs[i], (Integer) keyWidthPairs[i + 1]);
        }
        return Collections.unmodifiableMap(layout);
    }

    // Messages name the field and the problem, never the value (it could be MAX_BUY).
    private static final class MalformedRecord extends RuntimeException {
        MalformedRecord(String message) {
            super(message, null, false, false);
        }
    }
}
