package ai.happyrobot.carriersales.tms;

import static java.nio.charset.StandardCharsets.US_ASCII;

import ai.happyrobot.carriersales.config.TmsProperties;
import ai.happyrobot.carriersales.tms.exception.FaultKind;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.TmsBooking;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import ai.happyrobot.carriersales.tms.protocol.TmsCommand;
import ai.happyrobot.carriersales.tms.protocol.TmsRequest;
import ai.happyrobot.carriersales.tms.protocol.TmsRequestEncoder;
import ai.happyrobot.carriersales.tms.protocol.TmsResponseParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// One fresh connection per attempt. Faults are retried; TMS errors are real answers and are not.
@Component
public class TmsClient {

    private static final Logger log = LoggerFactory.getLogger(TmsClient.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long PAUSE_BETWEEN_ATTEMPTS_MS = 200;
    // A full 10-load reply is ~6 KB; anything far bigger is not a real reply.
    private static final int MAX_REPLY_BYTES = 64 * 1024;

    private final TmsProperties properties;

    public TmsClient(TmsProperties properties) {
        this.properties = properties;
    }

    public List<TmsLoad> send(TmsRequest request) {
        if (request.command() == TmsCommand.LOAD_BOOK) {
            throw new IllegalArgumentException("use book(): a booking must never go through the retry loop");
        }
        log.debug("TMS request {}", TmsRequestEncoder.redacted(request));

        for (int attempt = 1; ; attempt++) {
            try {
                return TmsResponseParser.parse(request.command(), exchange(request, properties.attemptTimeout()));
            } catch (TmsFaultException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("TMS {} failed after {} attempts: {}", request.command(), MAX_ATTEMPTS, e.getMessage());
                    throw e;
                }
                log.warn("TMS {} attempt {}/{} failed: {}", request.command(), attempt, MAX_ATTEMPTS, e.getMessage());
                pause(e);
            }
        }
    }

    // Exactly one attempt: retrying a booking blindly could double-book. The caller verifies after a fault.
    public TmsBooking book(String loadId, String mcNumber, int agreedRate) {
        TmsRequest request = TmsRequest.loadBook(loadId, mcNumber, agreedRate);
        log.info("TMS LOAD_BOOK {} for MC {} at {}", loadId, mcNumber, agreedRate);
        return TmsResponseParser.parseBooking(exchange(request, properties.bookTimeout()));
    }

    private String exchange(TmsRequest request, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.host(), properties.port()), remainingMillis(deadline));

            OutputStream out = socket.getOutputStream();
            out.write(TmsRequestEncoder.encode(request, properties.token()).getBytes(US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                socket.setSoTimeout(remainingMillis(deadline));
                int read = in.read(buffer);
                if (read == -1) {
                    break; // server closed: the parser decides whether the reply is complete
                }
                reply.write(buffer, 0, read);
                if (reply.size() > MAX_REPLY_BYTES) {
                    throw new TmsFaultException(FaultKind.MALFORMED, "reply larger than " + MAX_REPLY_BYTES + " bytes");
                }
                if (isComplete(reply.toString(US_ASCII))) {
                    break; // don't wait for the server to close (it sometimes holds the connection open)
                }
            }
            return reply.toString(US_ASCII);
        } catch (SocketTimeoutException e) {
            throw new TmsFaultException(FaultKind.TIMEOUT,
                    "no complete reply within " + timeout.toMillis() + " ms");
        } catch (IOException e) {
            throw new TmsFaultException(FaultKind.IO_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static boolean isComplete(String reply) {
        return reply.equals("END\r\n")
                || reply.endsWith("\r\nEND\r\n")
                || (reply.startsWith("ERR|") && reply.endsWith("\r\n"));
    }

    // Never 0: a socket timeout of 0 means "wait forever".
    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) {
            throw new SocketTimeoutException("attempt deadline reached");
        }
        return (int) remaining;
    }

    private static void pause(TmsFaultException lastFault) {
        try {
            Thread.sleep(PAUSE_BETWEEN_ATTEMPTS_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw lastFault;
        }
    }
}
