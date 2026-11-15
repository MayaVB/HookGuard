package io.hookguard.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All HookGuard-specific configuration. Values come from application.yml, which maps
 * them to environment variables (see .env.example).
 */
@ConfigurationProperties("hookguard")
public record HookGuardProperties(
        @DefaultValue Api api,
        @DefaultValue Worker worker,
        @DefaultValue Delivery delivery,
        @DefaultValue Targets targets,
        @DefaultValue Demo demo) {

    public HookGuardProperties {
        // A claim lease shorter than the longest possible HTTP attempt would let another
        // worker reclaim a delivery that is still legitimately in flight.
        Duration longestAttempt = delivery.connectTimeout().plus(delivery.requestTimeout());
        if (worker.leaseDuration().compareTo(longestAttempt.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "hookguard.worker.lease-duration (" + worker.leaseDuration() + ") must be at least twice "
                            + "connect-timeout + request-timeout (" + longestAttempt + ")");
        }
    }

    /** Settings for the REST API role. */
    public record Api(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("262144") int maxPayloadBytes) {
    }

    /** Settings for the delivery worker role. */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            String id,
            @DefaultValue("60s") Duration leaseDuration) {

        /** Identifier written to claims and attempts, so it is visible which process did the work. */
        public String effectiveId() {
            if (id != null && !id.isBlank()) {
                return id;
            }
            String host = System.getenv().getOrDefault("HOSTNAME", "local");
            return host + "-" + ProcessHandle.current().pid();
        }
    }

    /** Outbound HTTP behaviour. */
    public record Delivery(
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("10s") Duration requestTimeout,
            @DefaultValue("4096") int maxResponseBodyBytes) {
    }

    /** Which webhook targets may be registered and called. */
    public record Targets(@DefaultValue List<String> allowedHosts) {
    }

    /**
     * Demo-only API (duplicate message injection for the live demo page). Off by default;
     * enabled in docker-compose. {@code receiverBaseUrl} is the demo receiver as HookGuard reaches it.
     */
    public record Demo(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("http://localhost:9090") String receiverBaseUrl) {
    }
}
