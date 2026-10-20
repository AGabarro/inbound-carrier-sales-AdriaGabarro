package ai.happyrobot.carriersales.tms;

import static ai.happyrobot.carriersales.tms.FakeTmsServer.closeWithoutReply;
import static ai.happyrobot.carriersales.tms.FakeTmsServer.hang;
import static ai.happyrobot.carriersales.tms.FakeTmsServer.reply;
import static ai.happyrobot.carriersales.tms.FakeTmsServer.replyAndHoldOpen;
import static ai.happyrobot.carriersales.tms.TmsFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.happyrobot.carriersales.config.TmsProperties;
import ai.happyrobot.carriersales.tms.exception.FaultKind;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.TmsBooking;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import ai.happyrobot.carriersales.tms.protocol.TmsRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TmsClientTest {

    private static final String TOKEN = "test-secret-token";
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofMillis(300);

    private static final TmsRequest SEARCH = TmsRequest.loadQuery(Map.of("DEST_STATE", "TX"));
    private static final String CLEAN = fixture("query-dest-tx.txt");
    private static final String PARTIAL = fixture("fault-partial-1-query.txt");
    private static final String MALFORMED = fixture("fault-malformed-1-query.txt");

    @Test
    void returnsLoadsOnFirstAttempt() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(reply(CLEAN))) {
            List<TmsLoad> loads = clientFor(tms.port()).send(SEARCH);

            assertThat(loads).hasSize(5);
            assertThat(tms.requestLines()).containsExactly("CMD:LOAD_QUERY|AUTH:" + TOKEN + "|DEST_STATE:TX");
        }
    }

    @Test
    void tmsErrorIsNotRetried() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(reply(fixture("get-not-found.err.txt")), reply(CLEAN))) {
            assertThatThrownBy(() -> clientFor(tms.port()).send(TmsRequest.loadGet("LD99999")))
                    .isInstanceOf(TmsErrorException.class);

            assertThat(tms.requestLines()).hasSize(1);
        }
    }

    @Test
    void partialReplyIsRetried() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(reply(PARTIAL), reply(CLEAN))) {
            assertThat(clientFor(tms.port()).send(SEARCH)).hasSize(5);

            assertThat(tms.requestLines()).hasSize(2);
        }
    }

    @Test
    void givesUpAfterThreeFaults() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(reply(MALFORMED), reply(MALFORMED), reply(MALFORMED), reply(CLEAN))) {
            assertThatThrownBy(() -> clientFor(tms.port()).send(SEARCH))
                    .isInstanceOfSatisfying(TmsFaultException.class,
                            e -> assertThat(e.kind()).isEqualTo(FaultKind.MALFORMED));

            assertThat(tms.requestLines()).hasSize(3);
        }
    }

    @Test
    void timeoutIsRetried() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(hang(), reply(CLEAN))) {
            assertThat(clientFor(tms.port()).send(SEARCH)).hasSize(5);

            assertThat(tms.requestLines()).hasSize(2);
        }
    }

    @Test
    void hangingEveryTimeEndsAsTimeout() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(hang(), hang(), hang())) {
            assertThatThrownBy(() -> clientFor(tms.port()).send(SEARCH))
                    .isInstanceOfSatisfying(TmsFaultException.class,
                            e -> assertThat(e.kind()).isEqualTo(FaultKind.TIMEOUT));
        }
    }

    @Test
    void emptyCloseIsRetried() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(closeWithoutReply(), reply(CLEAN))) {
            assertThat(clientFor(tms.port()).send(SEARCH)).hasSize(5);

            assertThat(tms.requestLines()).hasSize(2);
        }
    }

    @Test
    void delayedCloseDoesNotMakeTheClientWait() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(replyAndHoldOpen(CLEAN))) {
            TmsClient client = clientFor(tms.port(), Duration.ofSeconds(5));

            long start = System.nanoTime();
            assertThat(client.send(SEARCH)).hasSize(5);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isLessThan(1_000); // returned at END, not after the 5s budget
        }
    }

    @Test
    void connectionRefusedIsIoError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> clientFor(closedPort).send(SEARCH))
                .isInstanceOfSatisfying(TmsFaultException.class,
                        e -> assertThat(e.kind()).isEqualTo(FaultKind.IO_ERROR));
    }

    @Test
    void oversizedReplyIsMalformed() throws IOException {
        String huge = "X".repeat(70_000);
        try (FakeTmsServer tms = new FakeTmsServer(reply(huge), reply(huge), reply(huge))) {
            assertThatThrownBy(() -> clientFor(tms.port()).send(SEARCH))
                    .isInstanceOfSatisfying(TmsFaultException.class,
                            e -> assertThat(e.kind()).isEqualTo(FaultKind.MALFORMED));
        }
    }

    @Test
    void bookingNeverGoesThroughTheRetryLoop() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(reply(CLEAN))) {
            assertThatThrownBy(() -> clientFor(tms.port()).send(TmsRequest.loadBook("LD00992", "123456", 11000)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(tms.requestLines()).isEmpty();
        }
    }

    @Test
    void bookSendsOneRequestAndReadsTheReference() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(reply(fixture("book-success.txt")))) {
            TmsBooking booking = clientFor(tms.port()).book("LD00962", "133655", 3833);

            assertThat(booking.bookingRef()).isEqualTo("GFZKYZRT3K4JOEAE");
            assertThat(booking.status()).isEqualTo("PENDING");
            assertThat(tms.requestLines())
                    .containsExactly("CMD:LOAD_BOOK|AUTH:" + TOKEN + "|LOAD_ID:LD00962|MC_NUM:133655|AGREED_RATE:3833");
        }
    }

    @Test
    void bookFaultIsNotRetried() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(hang(), reply(fixture("book-success.txt")))) {
            assertThatThrownBy(() -> clientFor(tms.port()).book("LD00962", "133655", 3833))
                    .isInstanceOfSatisfying(TmsFaultException.class, e -> assertThat(e.kind()).isEqualTo(FaultKind.TIMEOUT));

            assertThat(tms.requestLines()).hasSize(1);
        }
    }

    @Test
    void alreadyBookedIsATmsError() throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(reply(fixture("book-already-booked.err.txt")))) {
            assertThatThrownBy(() -> clientFor(tms.port()).book("LD00962", "133655", 3833))
                    .isInstanceOfSatisfying(TmsErrorException.class, e -> assertThat(e.code()).isEqualTo("ALREADY_BOOKED"));
        }
    }

    @Test
    void logsFailedAttemptsWithoutToken(CapturedOutput output) throws IOException {
        try (FakeTmsServer tms = new FakeTmsServer(reply(PARTIAL), reply(CLEAN))) {
            clientFor(tms.port()).send(SEARCH);
        }

        assertThat(output).contains("attempt 1/3 failed: PARTIAL").doesNotContain(TOKEN);
    }

    private static TmsClient clientFor(int port) {
        return clientFor(port, ATTEMPT_TIMEOUT);
    }

    private static TmsClient clientFor(int port, Duration attemptTimeout) {
        return new TmsClient(new TmsProperties("127.0.0.1", port, TOKEN, attemptTimeout));
    }
}
