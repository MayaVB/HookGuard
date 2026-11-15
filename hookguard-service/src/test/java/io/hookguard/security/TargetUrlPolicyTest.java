package io.hookguard.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.hookguard.support.TestProperties;

class TargetUrlPolicyTest {

    private final TargetUrlPolicy policy =
            new TargetUrlPolicy(TestProperties.withAllowedHosts("demo-receiver:9090", "hooks.example.test"));

    @ParameterizedTest
    @ValueSource(strings = {
            "http://demo-receiver:9090/hooks/ok",
            "HTTP://Demo-Receiver:9090/hooks/ok?x=1",
            "https://hooks.example.test/webhook",
            "http://hooks.example.test:8443/any-port-for-host-only-entry"
    })
    void allowsExactlyListedTargets(String url) {
        assertThat(policy.violation(url)).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "http://demo-receiver:9091/hooks/ok           | not in the allowed target list",
            "http://demo-receiver/hooks/ok                | not in the allowed target list",
            "http://evil.demo-receiver:9090/              | not in the allowed target list",
            "http://demo-receiver.evil.test:9090/         | not in the allowed target list",
            "http://127.0.0.1:9090/                       | not in the allowed target list",
            "http://localhost:5432/                       | not in the allowed target list",
            "http://169.254.169.254/latest/meta-data      | not in the allowed target list",
            "ftp://demo-receiver:9090/file                | scheme must be http or https",
            "file:///etc/passwd                           | scheme must be http or https",
            "javascript:alert(1)                          | scheme must be http or https",
            "/relative/path                               | scheme must be http or https",
            "http://user:secret@demo-receiver:9090/       | must not contain credentials",
            "http://demo-receiver:9090/hooks#fragment     | must not contain a fragment",
            "http://under_score:9090/                     | valid host name",
            "http://bad host/                             | not valid"
    })
    void rejectsEverythingElse(String url, String expectedReason) {
        assertThat(policy.violation(url)).hasValueSatisfying(reason -> assertThat(reason).contains(expectedReason));
    }

    @Test
    void rejectsMissingUrl() {
        assertThat(policy.violation(" ")).contains("URL is required");
        assertThat(policy.violation(null)).contains("URL is required");
    }

    @Test
    void validateThrowsWithReason() {
        assertThatThrownBy(() -> policy.validate("http://internal-admin:8080/"))
                .isInstanceOf(TargetNotAllowedException.class)
                .hasMessageContaining("internal-admin:8080");
    }

    @Test
    void emptyAllowlistFailsClosed() {
        TargetUrlPolicy empty = new TargetUrlPolicy(TestProperties.withAllowedHosts());
        assertThat(empty.isAllowed("http://demo-receiver:9090/hooks/ok")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://demo-receiver", "*.example.test", "demo-receiver:notaport", "demo-receiver:0",
            "[::1]:9090", "user@host"})
    void invalidAllowlistEntriesFailAtStartup(String entry) {
        assertThatThrownBy(() -> new TargetUrlPolicy(TestProperties.withAllowedHosts(entry)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
