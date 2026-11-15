package io.hookguard.support;

import java.time.Duration;
import java.util.List;

import io.hookguard.config.HookGuardProperties;

/** Builds HookGuardProperties for plain unit tests (no Spring context). */
public final class TestProperties {

    private TestProperties() {
    }

    public static HookGuardProperties withAllowedHosts(String... allowedHosts) {
        return build(Duration.ofSeconds(1), Duration.ofSeconds(2), 4096, allowedHosts);
    }

    public static HookGuardProperties build(Duration connectTimeout, Duration requestTimeout, int maxResponseBodyBytes,
                                            String... allowedHosts) {
        return new HookGuardProperties(
                new HookGuardProperties.Api(true, 262_144),
                new HookGuardProperties.Worker(true, "test-worker", Duration.ofSeconds(60)),
                new HookGuardProperties.Delivery(connectTimeout, requestTimeout, maxResponseBodyBytes),
                new HookGuardProperties.Targets(List.of(allowedHosts)),
                new HookGuardProperties.Demo(false, "http://localhost:9090"));
    }
}
