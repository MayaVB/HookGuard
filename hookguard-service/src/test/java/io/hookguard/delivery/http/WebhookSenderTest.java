package io.hookguard.delivery.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.hookguard.delivery.AttemptOutcome;
import io.hookguard.support.TestProperties;

/** Exercises the real JDK HttpClient against a real local HTTP server. */
class WebhookSenderTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(800);

    private HttpServer server;
    private ExecutorService serverThreads;
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private volatile String lastBody;
    private WebhookSender sender;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/ok", exchange -> respond(exchange, 200, "thanks"));
        server.createContext("/error", exchange -> respond(exchange, 500, "{\"error\":\"boom\"}"));
        server.createContext("/redirect", exchange -> {
            capture(exchange);
            exchange.getResponseHeaders().add("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-target", exchange -> {
            redirectTargetHits.incrementAndGet();
            respond(exchange, 200, "should never be reached");
        });
        server.createContext("/slow-headers", exchange -> {
            capture(exchange);
            sleepQuietly(3_000);
            respond(exchange, 200, "too late");
        });
        server.createContext("/slow-body", exchange -> {
            // Headers and a first chunk arrive quickly, then the body stalls.
            capture(exchange);
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("partial".getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleepQuietly(3_000);
                out.write("rest".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // client went away, expected
            }
        });
        server.createContext("/large", exchange -> respond(exchange, 200, "x".repeat(1_000_000)));
        server.start();

        sender = new WebhookSender(
                TestProperties.build(Duration.ofSeconds(1), REQUEST_TIMEOUT, 100, "127.0.0.1"),
                Clock.systemUTC());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void successfulPostCarriesDeliveryHeadersAndBody() {
        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        WebhookResult result = sender.send(new WebhookRequest(url("/ok"), "{\"orderId\":42}", deliveryId, eventId,
                "order.created", 3));

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.responseBody()).isEqualTo("thanks");
        assertThat(result.responseTruncated()).isFalse();
        assertThat(lastBody).isEqualTo("{\"orderId\":42}");
        assertThat(lastHeaders)
                .containsEntry("x-hookguard-delivery-id", deliveryId.toString())
                .containsEntry("x-hookguard-event-id", eventId.toString())
                .containsEntry("x-hookguard-event-type", "order.created")
                .containsEntry("x-hookguard-attempt", "3")
                .containsEntry("content-type", "application/json");
    }

    @Test
    void non2xxIsHttpErrorWithResponseBodyCaptured() {
        WebhookResult result = sender.send(request("/error"));

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.HTTP_ERROR);
        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(result.responseBody()).isEqualTo("{\"error\":\"boom\"}");
        assertThat(result.failureSummary()).isEqualTo("Endpoint responded with HTTP 500");
    }

    @Test
    void redirectsAreNotFollowed() {
        WebhookResult result = sender.send(request("/redirect"));

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.HTTP_ERROR);
        assertThat(result.httpStatus()).isEqualTo(302);
        assertThat(redirectTargetHits).hasValue(0);
    }

    @Test
    void slowResponseHeadersTimeOut() {
        WebhookResult result = sender.send(request("/slow-headers"));

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
        assertThat(result.durationMs()).isBetween(REQUEST_TIMEOUT.toMillis() - 50, 2_000L);
    }

    @Test
    void stalledBodyAfterFastHeadersStillTimesOut() {
        // The JDK's own request timeout only covers waiting for headers; our overall deadline covers the body too.
        WebhookResult result = sender.send(request("/slow-body"));

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
        assertThat(result.durationMs()).isLessThan(2_000L);
    }

    @Test
    void largeResponseBodyIsTruncatedNotBuffered() {
        WebhookResult result = sender.send(request("/large"));

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(result.responseBody()).hasSize(100);
        assertThat(result.responseTruncated()).isTrue();
    }

    @Test
    void connectionRefusedIsConnectionError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        WebhookResult result = sender.send(new WebhookRequest("http://127.0.0.1:" + closedPort + "/x", "{}",
                UUID.randomUUID(), UUID.randomUUID(), "t", 1));

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.CONNECTION_ERROR);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorMessage()).isNotBlank();
    }

    private WebhookRequest request(String path) {
        return new WebhookRequest(url(path), "{}", UUID.randomUUID(), UUID.randomUUID(), "test.event", 1);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        capture(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // client may close early (truncation / timeout tests)
        }
    }

    private void capture(HttpExchange exchange) throws IOException {
        exchange.getRequestHeaders().forEach((name, values) -> lastHeaders.put(name.toLowerCase(), values.getFirst()));
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
