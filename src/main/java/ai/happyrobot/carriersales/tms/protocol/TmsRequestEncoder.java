package ai.happyrobot.carriersales.tms.protocol;

public final class TmsRequestEncoder {

    static final int MAX_FRAME_BYTES = 4096;

    private TmsRequestEncoder() {
    }

    /** Full wire line including CRLF. The token is used here only and never appears in exceptions. */
    public static String encode(TmsRequest request, String token) {
        if (token == null || !TmsRequest.WIRE_SAFE.matcher(token).matches()) {
            throw new IllegalArgumentException("TMS token is missing or not wire-safe");
        }
        String frame = line(request, token) + "\r\n";
        if (frame.length() > MAX_FRAME_BYTES) { // ASCII-only, so chars == bytes
            throw new IllegalArgumentException("TMS request exceeds " + MAX_FRAME_BYTES + " bytes");
        }
        return frame;
    }

    /** Wire line with the token masked, for logs. */
    public static String redacted(TmsRequest request) {
        return line(request, "***");
    }

    private static String line(TmsRequest request, String auth) {
        StringBuilder sb = new StringBuilder("CMD:").append(request.command().name()).append("|AUTH:").append(auth);
        request.fields().forEach((k, v) -> sb.append('|').append(k).append(':').append(v));
        return sb.toString();
    }
}
