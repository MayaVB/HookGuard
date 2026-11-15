package io.hookguard.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.hookguard.config.HookGuardProperties;

/**
 * Decides which URLs HookGuard may send requests to.
 *
 * <p>HookGuard makes outbound HTTP requests to user-supplied URLs, which is a classic
 * server-side request forgery (SSRF) vector: without a restriction, anyone who can register
 * an endpoint can make HookGuard call internal services (databases, metadata endpoints, admin
 * consoles). For the local MVP the policy is a strict, operator-controlled allowlist:
 *
 * <ul>
 *   <li>Only {@code http} and {@code https}.</li>
 *   <li>The host must exactly match an allowlist entry ({@code host} or {@code host:port}).
 *       No wildcards, no suffix matching, no IP ranges.</li>
 *   <li>No credentials in the URL and no fragment.</li>
 *   <li>An empty allowlist rejects everything (fail closed).</li>
 * </ul>
 *
 * The policy is checked when an endpoint is registered and again by the worker right before
 * each attempt, so tightening the allowlist also stops deliveries to existing endpoints.
 * Redirects are never followed by the sender, so a 3xx cannot bounce a request elsewhere.
 */
@Component
public class TargetUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(TargetUrlPolicy.class);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Set<AllowedTarget> allowedTargets;

    public TargetUrlPolicy(HookGuardProperties properties) {
        this.allowedTargets = parse(properties.targets().allowedHosts());
        if (allowedTargets.isEmpty()) {
            log.warn("hookguard.targets.allowed-hosts is empty: every webhook target will be rejected");
        } else {
            log.info("Webhook target allowlist: {}", allowedTargets);
        }
    }

    /** Throws {@link TargetNotAllowedException} with a human-readable reason if the URL is not allowed. */
    public void validate(String rawUrl) {
        violation(rawUrl).ifPresent(reason -> {
            throw new TargetNotAllowedException(reason);
        });
    }

    public boolean isAllowed(String rawUrl) {
        return violation(rawUrl).isEmpty();
    }

    /** Returns the reason the URL is rejected, or empty if it is allowed. */
    public Optional<String> violation(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Optional.of("URL is required");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            return Optional.of("URL is not valid: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || !ALLOWED_SCHEMES.contains(scheme)) {
            return Optional.of("URL scheme must be http or https");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of("URL must not contain credentials");
        }
        if (uri.getRawFragment() != null) {
            return Optional.of("URL must not contain a fragment");
        }
        if (uri.getHost() == null) {
            return Optional.of("URL must contain a valid host name");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() != -1 ? uri.getPort() : defaultPort(scheme);
        boolean allowed = allowedTargets.stream().anyMatch(target -> target.matches(host, port));
        if (!allowed) {
            return Optional.of("Target " + host + ":" + port + " is not in the allowed target list");
        }
        return Optional.empty();
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    private static Set<AllowedTarget> parse(List<String> entries) {
        return entries.stream()
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(AllowedTarget::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** One allowlist entry; {@code port == null} means any port on that host. */
    record AllowedTarget(String host, Integer port) {

        static AllowedTarget parse(String entry) {
            if (entry.contains("/") || entry.contains("[") || entry.contains("*") || entry.contains("@")) {
                throw new IllegalArgumentException("Invalid allowed-hosts entry '" + entry
                        + "': expected 'host' or 'host:port' (no scheme, path, wildcard or IPv6 literal)");
            }
            int colon = entry.lastIndexOf(':');
            if (colon < 0) {
                return new AllowedTarget(entry.toLowerCase(Locale.ROOT), null);
            }
            String host = entry.substring(0, colon).toLowerCase(Locale.ROOT);
            int port;
            try {
                port = Integer.parseInt(entry.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port in allowed-hosts entry '" + entry + "'", e);
            }
            if (host.isEmpty() || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid allowed-hosts entry '" + entry + "'");
            }
            return new AllowedTarget(host, port);
        }

        boolean matches(String candidateHost, int candidatePort) {
            return host.equals(candidateHost) && (port == null || port == candidatePort);
        }

        @Override
        public String toString() {
            return port == null ? host + ":*" : host + ":" + port;
        }
    }
}
