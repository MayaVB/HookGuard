package io.hookguard.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class HookGuardPropertiesTest {

    @Test
    void rejectsLeaseThatCouldExpireDuringALegitimateAttempt() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
    }

    @Test
    void acceptsLeaseWithSafetyMargin() {
        assertThatNoException().isThrownBy(
                () -> properties(Duration.ofSeconds(24), Duration.ofSeconds(2), Duration.ofSeconds(10)));
    }

    private static HookGuardProperties properties(Duration lease, Duration connect, Duration request) {
        return new HookGuardProperties(
                new HookGuardProperties.Api(true, 1024),
                new HookGuardProperties.Worker(true, "w", lease),
                new HookGuardProperties.Delivery(connect, request, 4096),
                new HookGuardProperties.Targets(List.of()),
                new HookGuardProperties.Demo(false, "http://localhost:9090"));
    }
}
