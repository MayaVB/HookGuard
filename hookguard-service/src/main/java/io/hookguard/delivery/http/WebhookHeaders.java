package io.hookguard.delivery.http;

/** Headers HookGuard adds to every outgoing webhook request. */
public final class WebhookHeaders {

    /**
     * Stable across every attempt of the same delivery. Delivery is at-least-once, so a receiver
     * may see the same delivery more than once; it should use this value as an idempotency key.
     */
    public static final String DELIVERY_ID = "X-HookGuard-Delivery-Id";
    public static final String EVENT_ID = "X-HookGuard-Event-Id";
    public static final String EVENT_TYPE = "X-HookGuard-Event-Type";
    /** 1-based attempt number; informational only, never use it for deduplication. */
    public static final String ATTEMPT = "X-HookGuard-Attempt";

    public static final String USER_AGENT = "HookGuard/0.1";

    private WebhookHeaders() {
    }
}
