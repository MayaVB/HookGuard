package io.hookguard.delivery.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import io.hookguard.config.HookGuardProperties;
import io.hookguard.delivery.AttemptOutcome;

/**
 * Performs one webhook HTTP POST with bounded time and bounded memory.
 *
 * <ul>
 *   <li>Connect timeout: how long to wait for the TCP/TLS connection.</li>
 *   <li>Request timeout: a hard deadline for the whole exchange, including reading the body.
 *       The JDK request timeout alone only covers waiting for response headers, so a server
 *       that sends headers and then trickles the body could otherwise hold a worker forever.</li>
 *   <li>Redirects are never followed (a redirect could point at a non-allowlisted host).</li>
 * </ul>
 *
 * Network failures become a {@link WebhookResult}; they are expected outcomes, not exceptions.
 */
@Component
public class WebhookSender {

    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxResponseBodyBytes;
    private final Clock clock;

    public WebhookSender(HookGuardProperties properties, Clock clock) {
        HookGuardProperties.Delivery config = properties.delivery();
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                // Plain HTTP/1.1: avoids h2c upgrade negotiation with arbitrary receivers.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.requestTimeout = config.requestTimeout();
        this.maxResponseBodyBytes = config.maxResponseBodyBytes();
        this.clock = clock;
    }

    public WebhookResult send(WebhookRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", WebhookHeaders.USER_AGENT)
                .header(WebhookHeaders.DELIVERY_ID, request.deliveryId().toString())
                .header(WebhookHeaders.EVENT_ID, request.eventId().toString())
                .header(WebhookHeaders.EVENT_TYPE, request.eventType())
                .header(WebhookHeaders.ATTEMPT, Integer.toString(request.attemptNumber()))
                .POST(HttpRequest.BodyPublishers.ofString(request.jsonBody(), StandardCharsets.UTF_8))
                .build();

        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        CompletableFuture<HttpResponse<CapturedBody>> future =
                client.sendAsync(httpRequest, responseInfo -> new LimitedBodySubscriber(maxResponseBodyBytes));
        try {
            HttpResponse<CapturedBody> response = future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return WebhookResult.response(response.statusCode(), response.body(), startedAt, clock.instant(), elapsedMs(startNanos));
        } catch (TimeoutException e) {
            future.cancel(true); // aborts the underlying exchange
            return timeout(startedAt, startNanos);
        } catch (ExecutionException e) {
            return classifyFailure(e.getCause(), startedAt, startNanos);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            // We cannot know whether the receiver got the request; do not record a guess.
            throw new IllegalStateException("Interrupted while delivering " + request.deliveryId(), e);
        }
    }

    private WebhookResult classifyFailure(Throwable cause, Instant startedAt, long startNanos) {
        if (cause instanceof HttpConnectTimeoutException) {
            return WebhookResult.error(AttemptOutcome.CONNECTION_ERROR, "Connect timed out", startedAt, clock.instant(), elapsedMs(startNanos));
        }
        if (cause instanceof HttpTimeoutException) {
            return timeout(startedAt, startNanos);
        }
        if (cause instanceof ConnectException) {
            return WebhookResult.error(AttemptOutcome.CONNECTION_ERROR, "Connection refused or unreachable: " + describe(cause),
                    startedAt, clock.instant(), elapsedMs(startNanos));
        }
        if (cause instanceof IOException) {
            return WebhookResult.error(AttemptOutcome.CONNECTION_ERROR, "I/O error: " + describe(cause),
                    startedAt, clock.instant(), elapsedMs(startNanos));
        }
        // Not a network condition (e.g. a bug building the request): surface it, do not record it as a delivery failure.
        throw new IllegalStateException("Unexpected error during webhook delivery", cause);
    }

    private WebhookResult timeout(Instant startedAt, long startNanos) {
        return WebhookResult.error(AttemptOutcome.TIMEOUT, "No complete response within " + requestTimeout.toMillis() + " ms",
                startedAt, clock.instant(), elapsedMs(startNanos));
    }

    private static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
