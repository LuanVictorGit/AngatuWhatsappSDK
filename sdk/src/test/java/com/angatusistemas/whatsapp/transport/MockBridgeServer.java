package com.angatusistemas.whatsapp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal RFC 6455 WebSocket server for tests, hand-rolled on plain blocking
 * {@link java.net.ServerSocket} rather than {@code org.java_websocket}'s own
 * server (which opens a {@link java.nio.channels.Selector} internally -
 * unusable in some sandboxed/restricted environments; see
 * {@code NodeRuntimeDownloader}'s class Javadoc for the equivalent
 * client-side story that led to this project's WebSocket client choice).
 * The real bridge is always the server, Java is always the client, so this
 * class exists purely so {@code BridgeConnectionTest} can script protocol
 * scenarios (bad token, timeouts, heartbeat loss, ...) fast and
 * deterministically without spawning a real Node process.
 *
 * <p>Supports exactly one connection at a time and only what this protocol
 * actually uses: text frames and close frames (ping/pong are answered but
 * never needed in practice, since {@code BridgeConnection} disables the
 * WebSocket-level keepalive in favour of its own application-level
 * heartbeat).
 */
final class MockBridgeServer implements AutoCloseable {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final ExecutorService acceptExecutor = newDaemonExecutor("mock-bridge-accept");
    private final ExecutorService readExecutor = newDaemonExecutor("mock-bridge-read");
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<JsonNode> receivedMessages = new LinkedBlockingQueue<>();
    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private final AtomicInteger lastCloseCode = new AtomicInteger(-1);
    private final AtomicBoolean closeSent = new AtomicBoolean(false);

    private volatile Socket clientSocket;
    private volatile OutputStream rawOut;

    private MockBridgeServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    static MockBridgeServer start() throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        MockBridgeServer server = new MockBridgeServer(serverSocket);
        server.acceptExecutor.submit(server::acceptLoop);
        return server;
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    boolean awaitConnection(Duration timeout) throws InterruptedException {
        return connectionLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Waits for and returns the next full JSON message the client sent, or null on timeout. */
    JsonNode nextMessage(Duration timeout) throws InterruptedException {
        return receivedMessages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    int lastCloseCode() {
        return lastCloseCode.get();
    }

    void sendJson(Object value) throws IOException {
        sendFrame(0x1, mapper.writeValueAsBytes(value));
    }

    void closeConnection(int code, String reason) throws IOException {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((code >>> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        closeSent.set(true);
        sendFrame(0x8, payload);
    }

    /** Drops the TCP connection without a WebSocket close handshake - simulates a crashed/killed peer. */
    void dropConnectionAbruptly() throws IOException {
        Socket socket = this.clientSocket;
        if (socket != null) {
            socket.close();
        }
    }

    private void acceptLoop() {
        try {
            Socket socket = serverSocket.accept();
            this.clientSocket = socket;
            performHandshake(socket);
            this.rawOut = socket.getOutputStream();
            connectionLatch.countDown();
            readExecutor.submit(() -> readLoop(socket));
        } catch (IOException e) {
            // server closed (test teardown) or handshake failed before it completed; nothing more to do here.
        }
    }

    private void performHandshake(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String key = null;
        String line;
        while (!(line = readAsciiLine(in)).isEmpty()) {
            if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, "Sec-WebSocket-Key:".length())) {
                key = line.substring("Sec-WebSocket-Key:".length()).trim();
            }
        }
        if (key == null) {
            throw new IOException("requisicao de handshake sem cabecalho Sec-WebSocket-Key");
        }
        String accept = computeAccept(key);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (b != '\r') {
                line.write(b);
            }
        }
        if (b == -1 && line.size() == 0) {
            throw new EOFException("conexao fechada durante o handshake");
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static String computeAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 nao disponivel nesta JVM", e);
        }
    }

    private void readLoop(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            Frame frame;
            while ((frame = readFrame(in)) != null) {
                switch (frame.opcode()) {
                    case 0x1 -> receivedMessages.put(mapper.readTree(frame.payload()));
                    case 0x8 -> {
                        lastCloseCode.set(parseCloseCode(frame.payload()));
                        // RFC 6455 5.5.1: an endpoint that receives a close frame and has not
                        // itself already sent one must echo a close frame back before either
                        // side may tear down the TCP connection. Skipping this left the real
                        // org.java_websocket client waiting forever for its own close() to be
                        // acknowledged, since nothing ever completed its closing handshake.
                        if (closeSent.compareAndSet(false, true)) {
                            try {
                                sendFrame(0x8, frame.payload());
                            } catch (IOException ignored) {
                                // peer is likely already tearing down its side; nothing more to do.
                            }
                        }
                        socket.close();
                        return;
                    }
                    case 0x9 -> sendFrame(0xA, frame.payload());
                    default -> { /* binary/pong/continuation: unused by this protocol */ }
                }
            }
        } catch (Exception e) {
            // Socket closed/reset, typically at test teardown or dropConnectionAbruptly() - not a test failure.
        }
    }

    private static int parseCloseCode(byte[] payload) {
        return payload.length >= 2 ? (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) : 1005;
    }

    private record Frame(int opcode, byte[] payload) {
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null;
        }
        int b1 = in.read();
        if (b1 == -1) {
            return null;
        }
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;
        if (length == 126) {
            length = (readUnsigned(in) << 8) | readUnsigned(in);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readUnsigned(in);
            }
        }
        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            readFully(in, maskKey);
        }
        byte[] payload = new byte[Math.toIntExact(length)];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }
        return new Frame(opcode, payload);
    }

    private static long readUnsigned(InputStream in) throws IOException {
        int value = in.read();
        if (value == -1) {
            throw new EOFException();
        }
        return value & 0xFFL;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read == -1) {
                throw new EOFException();
            }
            total += read;
        }
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        OutputStream out = this.rawOut;
        if (out == null) {
            throw new IOException("nenhum cliente conectado ao mock bridge server");
        }
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x80 | (opcode & 0x0F));
        int len = payload.length;
        if (len <= 125) {
            header.write(len);
        } else if (len <= 0xFFFF) {
            header.write(126);
            header.write((len >>> 8) & 0xFF);
            header.write(len & 0xFF);
        } else {
            header.write(127);
            for (int i = 7; i >= 0; i--) {
                header.write((len >>> (8 * i)) & 0xFF);
            }
        }
        out.write(header.toByteArray());
        out.write(payload);
        out.flush();
    }

    private static ExecutorService newDaemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void close() throws IOException {
        acceptExecutor.shutdownNow();
        readExecutor.shutdownNow();
        Socket socket = this.clientSocket;
        if (socket != null) {
            socket.close();
        }
        serverSocket.close();
    }
}
