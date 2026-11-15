package io.hookguard.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;

/**
 * A real HTTP server standing in for a webhook receiver in integration tests.
 * Behaviour (status, body, delay) can be changed per test; every request is recorded.
 */
public final class StubWebhookServer {

    public record ReceivedRequest(String path, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private record Behaviour(int status, String body, long delayMillis) {
    }

    private static final StubWebhookServer SHARED = new StubWebhookServer();

    private final HttpServer server;
    private final int unusedPort;
    private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    private volatile Behaviour behaviour = new Behaviour(200, "ok", 0);

    private StubWebhookServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try (ServerSocket socket = new ServerSocket(0)) {
                unusedPort = socket.getLocalPort();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            Map<String, String> headers = exchange.getRequestHeaders().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), e -> e.getValue().getFirst(), (a, b) -> a));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new ReceivedRequest(exchange.getRequestURI().getPath(), headers, body));
            Behaviour current = behaviour;
            sleep(current.delayMillis());
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(current.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException clientGone) {
                // the client timed out and closed the connection; expected in timeout tests
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public static StubWebhookServer shared() {
        return SHARED;
    }

    public void respondWith(int status, String body) {
        behaviour = new Behaviour(status, body, 0);
    }

    public void respondWith(int status, String body, long delayMillis) {
        behaviour = new Behaviour(status, body, delayMillis);
    }

    public void reset() {
        behaviour = new Behaviour(200, "ok", 0);
        received.clear();
    }

    public List<ReceivedRequest> received() {
        return List.copyOf(received);
    }

    /** Requests carrying the given delivery ID, so assertions ignore traffic from other tests. */
    public List<ReceivedRequest> receivedFor(UUID deliveryId) {
        return received.stream()
                .filter(r -> deliveryId.toString().equals(r.header("X-HookGuard-Delivery-Id")))
                .toList();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** A port nothing listens on, for connection-refused scenarios. */
    public int unusedPort() {
        return unusedPort;
    }

    public String url(String path) {
        return "http://localhost:" + port() + path;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
