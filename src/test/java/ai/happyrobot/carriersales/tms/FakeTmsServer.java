package ai.happyrobot.carriersales.tms;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-JVM stand-in for the legacy TMS. Each test scripts what happens on each connection, in order:
 * {@code new FakeTmsServer(reply(PARTIAL), reply(CLEAN))} answers the first attempt with a partial
 * reply and the second with a clean one. It records every request line it receives.
 */
final class FakeTmsServer implements AutoCloseable {

    /** What the server does on one connection, after reading the request line. */
    interface Behaviour {
        void handle(Socket socket) throws IOException;
    }

    /** Writes the raw reply, then closes. */
    static Behaviour reply(String raw) {
        return socket -> write(socket, raw);
    }

    /** Waits, then writes the raw reply and closes ("slow but healthy"). */
    static Behaviour replyAfter(long delayMillis, String raw) {
        return socket -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            write(socket, raw);
        };
    }

    /** Writes the raw reply, then keeps the connection open until the client closes it ("delayed close"). */
    static Behaviour replyAndHoldOpen(String raw) {
        return socket -> {
            write(socket, raw);
            waitForClientToClose(socket);
        };
    }

    /** Never answers; the connection stays open until the client gives up ("timeout"). */
    static Behaviour hang() {
        return FakeTmsServer::waitForClientToClose;
    }

    /** Closes without writing anything ("empty close"). */
    static Behaviour closeWithoutReply() {
        return socket -> { };
    }

    private final ServerSocket serverSocket;
    private final Queue<Behaviour> script;
    private final List<String> requestLines = new CopyOnWriteArrayList<>();

    FakeTmsServer(Behaviour... perConnection) throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.script = new ConcurrentLinkedQueue<>(List.of(perConnection));
        Thread.ofVirtual().start(this::acceptLoop);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** Request lines received, in order, without the trailing CRLF. One per connection. */
    List<String> requestLines() {
        return requestLines;
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Behaviour behaviour = script.poll();
                Thread.ofVirtual().start(() -> serve(socket, behaviour == null ? closeWithoutReply() : behaviour));
            } catch (IOException e) {
                return; // server socket closed by close()
            }
        }
    }

    private void serve(Socket socket, Behaviour behaviour) {
        try (socket) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII));
            String line = reader.readLine();
            if (line != null) {
                requestLines.add(line);
            }
            behaviour.handle(socket);
        } catch (IOException ignored) {
            // the client closed first; nothing to do
        }
    }

    private static void write(Socket socket, String raw) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(raw.getBytes(US_ASCII));
        out.flush();
    }

    private static void waitForClientToClose(Socket socket) throws IOException {
        while (socket.getInputStream().read() != -1) {
            // discard anything else the client sends
        }
    }
}
