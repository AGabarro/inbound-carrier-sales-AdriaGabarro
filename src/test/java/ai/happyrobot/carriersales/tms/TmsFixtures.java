package ai.happyrobot.carriersales.tms;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Real TMS responses in src/test/resources/tms/, captured from the live system. */
public final class TmsFixtures {

    private TmsFixtures() {
    }

    public static String fixture(String name) {
        try (InputStream in = TmsFixtures.class.getResourceAsStream("/tms/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
