package ai.happyrobot.carriersales.tms;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record TmsRequest(TmsCommand command, Map<String, String> fields) {

    private static final Pattern FIELD_NAME = Pattern.compile("[A-Z_]+");
    /** Printable ASCII except '|': rules out field injection, CR/LF and non-ASCII in one check. */
    static final Pattern WIRE_SAFE = Pattern.compile("[\\x20-\\x7E&&[^|]]+");

    public TmsRequest {
        Objects.requireNonNull(command, "command");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields)); // keeps field order on the wire
        fields.forEach(TmsRequest::requireValidField);
    }

    public static TmsRequest loadQuery(Map<String, String> filters) {
        if (filters.isEmpty()) {
            throw new IllegalArgumentException("LOAD_QUERY needs at least one filter");
        }
        return new TmsRequest(TmsCommand.LOAD_QUERY, filters);
    }

    public static TmsRequest loadGet(String loadId) {
        return new TmsRequest(TmsCommand.LOAD_GET, Map.of("LOAD_ID", loadId));
    }

    public static TmsRequest loadBook(String loadId, String mcNumber, int agreedRate) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("LOAD_ID", loadId);
        f.put("MC_NUM", mcNumber);
        f.put("AGREED_RATE", Integer.toString(agreedRate));
        return new TmsRequest(TmsCommand.LOAD_BOOK, f);
    }

    private static void requireValidField(String name, String value) {
        if (name == null || !FIELD_NAME.matcher(name).matches() || name.equals("CMD") || name.equals("AUTH")) {
            throw new IllegalArgumentException("Invalid TMS field name: " + name);
        }
        if (value == null || value.isBlank() || !WIRE_SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid value for TMS field " + name);
        }
    }
}
